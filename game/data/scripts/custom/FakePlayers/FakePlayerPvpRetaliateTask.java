/*
 * Copyright (c) 2013 L2jMobius
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package custom.FakePlayers;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.custom.FakePlayersConfig;
import org.l2jmobius.gameserver.managers.PhantomManager;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.events.Containers;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.actor.creature.OnCreatureDamageReceived;
import org.l2jmobius.gameserver.model.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.targets.TargetType;
import org.l2jmobius.gameserver.model.zone.ZoneId;

/**
 * Two entirely different closed-engine systems answer to "fake player" here, and neither retaliates
 * against a player attacker on its own:
 * <ul>
 * <li>Ambient/vending fake players (FakePlayerBehaviorManager) are real Npc/Attackable instances, so in
 * theory the generic Attackable#addDamage -> addDamageHate -> thinkActive#getMostHated/Intention.ATTACK
 * chain should fire - but is not observed to.</li>
 * <li>Recruited buddies (!lf, PhantomManager#spawnPartyMember/spawnFriendRegular) and auto-hunt field
 * hunters (PhantomManager#spawnPhantom, PhantomAutoHuntingZones) are actual Player instances
 * (PhantomPartyManager$Member.npc is declared as Player, not Npc), puppeteered every tick by
 * PhantomPartyManager. A Player has no aggro list at all - that is an Attackable-only concept - and
 * PlayerAI has no onActionAttacked override (it inherits CreatureAI's trivial clientStartAutoAttack-only
 * version), so there is structurally no mechanism for a Player-typed phantom to decide to fight back;
 * PhantomPartyManager's own tick only ever reads Player/Monster combat state to drive its OWN hunting and
 * follow logic, never to react to an attacker (confirmed empty via the //phantom debug on trace).
 * </ul>
 * This works uniformly across both by listening to the generic EventType.ON_CREATURE_DAMAGE_RECEIVED
 * (fires for any Creature, Player or Npc) rather than relying on Attackable's aggro list, remembering the
 * last player to hit each fake player for a short window, and repeatedly forcing Intention.ATTACK plus a
 * direct doAttack()/doCast() against them - always overriding whatever else the fake player (or, for
 * phantoms, PhantomPartyManager) was having it do, since a player hitting it is the priority here. The
 * reinforcement sweep runs faster than PhantomPartyManager's own 1-second tick specifically to win that
 * tug-of-war over who the target/intention is. Skips (and does not start or continue) any of this in a
 * peace zone; PeaceZoneCombatStopTask separately disengages any Attackable-based fight that ends up there
 * anyway.
 * @author Living World
 */
public class FakePlayerPvpRetaliateTask
{
	private static final Logger LOGGER = Logger.getLogger(FakePlayerPvpRetaliateTask.class.getName());
	private static final long REINFORCE_INTERVAL = 200;
	private static final long MEMORY_MS = 30000;
	private static final long AGGRO_SCAN_INTERVAL = 1500;
	private static final int AGGRO_RANGE = 500;
	// hasNegativeEffect() alone is not reliable - a PARTY/SELF/CLAN-targeted support skill can still carry
	// that flag (e.g. some sacrifice-HP-to-heal-party skills), and doCast() then applies it per the skill's
	// OWN target type regardless of what target we set, landing back on the attacker if the caster is still
	// partied with them (a recruited buddy attacking its own owner). Only genuinely single/area-hostile
	// target types are safe to actually fire at an explicit enemy target.
	private static final Set<TargetType> HOSTILE_TARGET_TYPES = EnumSet.of(TargetType.ONE, TargetType.AURA, TargetType.AREA, TargetType.FRONT_AURA, TargetType.FRONT_AREA, TargetType.BEHIND_AURA, TargetType.BEHIND_AREA, TargetType.ENEMY_SUMMON);

	private final Map<Creature, Attacker> _recentAttackers = new ConcurrentHashMap<>();

	private FakePlayerPvpRetaliateTask()
	{
		Containers.Global().addListener(new ConsumerEventListener(Containers.Global(), EventType.ON_CREATURE_DAMAGE_RECEIVED, (OnCreatureDamageReceived event) -> onDamageReceived(event), this));
		ThreadPool.scheduleAtFixedRate(this::reinforce, REINFORCE_INTERVAL, REINFORCE_INTERVAL);
		ThreadPool.scheduleAtFixedRate(this::checkProactiveAggro, AGGRO_SCAN_INTERVAL, AGGRO_SCAN_INTERVAL);
		LOGGER.info("FakePlayerPvpRetaliateTask: started, listening for ON_CREATURE_DAMAGE_RECEIVED, reinforcing every " + REINFORCE_INTERVAL + "ms, scanning for proactive aggro every " + AGGRO_SCAN_INTERVAL + "ms.");
	}

	private void onDamageReceived(OnCreatureDamageReceived event)
	{
		final Creature target = event.getTarget();
		final Creature attacker = event.getAttacker();
		if ((target == null) || (attacker == null) || !isBotControlled(target) || !attacker.isPlayer() || isBotControlled(attacker))
		{
			return;
		}

		if (!_recentAttackers.containsKey(target))
		{
			LOGGER.info("FakePlayerPvpRetaliateTask: " + target.getName() + " hit by " + attacker.getName() + ", forcing retaliation.");
		}

		_recentAttackers.put(target, new Attacker(attacker.asPlayer(), System.currentTimeMillis()));
		retaliate(target, attacker.asPlayer());
	}

	/**
	 * Npc-based fake players set isFakePlayer(); Player-typed phantoms/recruits/buddies/regulars never do -
	 * they only set a private Player.isBuddyBot field with no public getter, so PhantomManager's own
	 * isPhantom/isRecruit/isBuddy/isRegular checks are the only way to identify them from outside.
	 */
	private static boolean isBotControlled(Creature creature)
	{
		if (creature.isFakePlayer())
		{
			return true;
		}

		final Player player = creature.asPlayer();
		if (player == null)
		{
			return false;
		}

		final PhantomManager phantomManager = PhantomManager.getInstance();
		return phantomManager.isPhantom(player) || phantomManager.isRecruit(player) || phantomManager.isBuddy(player) || phantomManager.isRegular(player);
	}

	private void reinforce()
	{
		try
		{
			final long now = System.currentTimeMillis();
			_recentAttackers.entrySet().removeIf(entry -> (now - entry.getValue().time) > MEMORY_MS);
			for (Map.Entry<Creature, Attacker> entry : _recentAttackers.entrySet())
			{
				retaliate(entry.getKey(), entry.getValue().player);
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "FakePlayerPvpRetaliateTask: error while reinforcing retaliation.", e);
		}
	}

	/**
	 * FakePlayerAggroPlayers has no effect at all on the Phantom system (the closed engine only reads it in
	 * AttackableAI, which never drives a Player-typed phantom) - the Npc-based fake players get their own
	 * (buggy, drop-defense-gated) proactive aggro natively, but auto-hunt field hunters never proactively go
	 * after a player at all. This adds that for them specifically (PhantomManager#isPhantom - not recruited
	 * buddies/regulars, which stay passive/friendly), gated on the same config flag so it is off by default.
	 * Deliberately does not require the hunter to be idle first - a hunter is basically always mid-fight
	 * with a monster (that is its whole purpose), so gating on "not already in combat" meant this almost
	 * never fired; it finds a real, non-GM, non-dead player within range regardless of what it is currently
	 * doing and starts "retaliating" against them exactly like it would against an actual attacker (which
	 * already always overrides its monster target - see retaliate()), peace-zone check included from the
	 * start rather than after the fact.
	 */
	private void checkProactiveAggro()
	{
		if (!FakePlayersConfig.FAKE_PLAYER_AGGRO_PLAYERS)
		{
			return;
		}

		try
		{
			final PhantomManager phantomManager = PhantomManager.getInstance();
			for (WorldObject worldObject : World.getInstance().getVisibleObjects())
			{
				if (!(worldObject instanceof Player))
				{
					continue;
				}

				final Player hunter = (Player) worldObject;
				if (!phantomManager.isPhantom(hunter) || hunter.isDead() || hunter.isInsideZone(ZoneId.PEACE) || _recentAttackers.containsKey(hunter))
				{
					continue;
				}

				final Player victim = findNearbyRealPlayer(hunter);
				if (victim != null)
				{
					LOGGER.info("FakePlayerPvpRetaliateTask: " + hunter.getName() + " proactively aggroing " + victim.getName() + ".");
					_recentAttackers.put(hunter, new Attacker(victim, System.currentTimeMillis()));
					retaliate(hunter, victim);
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "FakePlayerPvpRetaliateTask: error while scanning for proactive aggro.", e);
		}
	}

	private Player findNearbyRealPlayer(Player hunter)
	{
		for (Player candidate : World.getInstance().getVisibleObjectsInRange(hunter, Player.class, AGGRO_RANGE))
		{
			if (!isBotControlled(candidate) && !candidate.isDead() && !candidate.isGM() && !candidate.isInsideZone(ZoneId.PEACE))
			{
				return candidate;
			}
		}

		return null;
	}

	private void retaliate(Creature target, Player attacker)
	{
		if (target.isDead() || target.isInsideZone(ZoneId.PEACE) || attacker.isInsideZone(ZoneId.PEACE))
		{
			return;
		}

		// This runs every REINFORCE_INTERVAL regardless of whether the previous cast/swing finished yet. A
		// fresh doCast() while one is already in progress interrupts it (that is how a real player can also
		// cancel/change skills mid-cast), which at a 200ms interval against skills with a real cast time
		// looked like "casts, cancels, casts a different one" on a loop with nothing ever landing. Leave an
		// already-busy target alone and let the current action finish naturally.
		if (target.isCastingNow() || target.isCastingSimultaneouslyNow())
		{
			return;
		}

		if (!target.isRunning())
		{
			target.setRunning();
		}

		target.getAI().setIntention(Intention.ATTACK, attacker);
		// setIntention alone is a no-op once PhantomPartyManager's own tick has the AI mid-action (casting a
		// buff, moving to its hunting target, etc.), which is exactly when a player attack needs to cut in -
		// so also trigger the attack/cast directly rather than only queuing the intention and hoping it is
		// honored before PhantomPartyManager's next 1-second tick reasserts its own target.
		target.setTarget(attacker);
		final Skill skill = pickOffensiveSkill(target, attacker);
		if (skill != null)
		{
			target.doCast(skill);
		}
		else
		{
			target.doAttack(attacker);
		}
	}

	/**
	 * PhantomPlaystyleEngine (the existing skill-rotation AI for phantoms/hunters) only ever picks skills
	 * against a Monster - its own pick() method takes one as a required parameter - so it has no path for
	 * casting at a player attacker at all. This picks the strongest known, off-cooldown, affordable,
	 * in-range skill whose target type is genuinely hostile (HOSTILE_TARGET_TYPES, not just
	 * hasNegativeEffect() - see that constant's comment), ranked by |getEffectPoint()| - the same value the
	 * closed engine's own Phantom managers (PhantomBuddyManager/PhantomManager/PhantomPartyManager) use
	 * internally to compare skill priority - rather than just returning the first candidate found in
	 * getAllSkills()' arbitrary iteration order (which read as "weak random skills" to a player watching).
	 * The magnitude, not the raw signed value: hostile skills' effectPoint in retail data is negative and
	 * gets MORE negative as the skill gets stronger (e.g. Wind Strike -92..-162 vs. Hurricane -360..-655),
	 * so ranking by the raw value picked the weakest legal candidate every single time. Not running the
	 * full rotation logic PhantomPlaystyleEngine has for monsters, just ranking candidates.
	 */
	private Skill pickOffensiveSkill(Creature caster, Player target)
	{
		Skill best = null;
		for (Skill skill : caster.getAllSkills())
		{
			if (skill.isPassive() || skill.isToggle() || skill.isDance() || !skill.hasNegativeEffect() || !HOSTILE_TARGET_TYPES.contains(skill.getTargetType()))
			{
				continue;
			}

			if (caster.hasSkillReuse(skill.getId()))
			{
				continue;
			}

			if (caster.calculateDistance2D(target) > skill.getCastRange())
			{
				continue;
			}

			if (!caster.checkDoCastConditions(skill))
			{
				continue;
			}

			// Hostile skills' effectPoint is negative in the retail data, and gets MORE negative as the skill
			// gets stronger (e.g. Wind Strike -92..-162 vs. Hurricane -360..-655), not less - so the raw value
			// ranks the weakest candidate as "greatest" every time. Compare by magnitude instead.
			if ((best == null) || (Math.abs(skill.getEffectPoint()) > Math.abs(best.getEffectPoint())))
			{
				best = skill;
			}
		}

		return best;
	}

	private static final class Attacker
	{
		private final Player player;
		private final long time;

		private Attacker(Player player, long time)
		{
			this.player = player;
			this.time = time;
		}
	}

	public static void main(String[] args)
	{
		new FakePlayerPvpRetaliateTask();
	}
}
