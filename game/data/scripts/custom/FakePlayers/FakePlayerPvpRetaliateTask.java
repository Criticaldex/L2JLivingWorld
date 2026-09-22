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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.events.Containers;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.actor.creature.OnCreatureDamageReceived;
import org.l2jmobius.gameserver.model.events.listeners.ConsumerEventListener;
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
 * last player to hit each fake player for a short window, and repeatedly forcing Intention.ATTACK against
 * them - always overriding whatever else the fake player (or, for phantoms, PhantomPartyManager) was
 * having it do, since a player hitting it is the priority here. The reinforcement sweep runs faster than
 * PhantomPartyManager's own 1-second tick specifically to win that tug-of-war over who the target/intention
 * is. Skips (and does not start or continue) any of this in a peace zone; PeaceZoneCombatStopTask
 * separately disengages any Attackable-based fight that ends up there anyway.
 * @author Living World
 */
public class FakePlayerPvpRetaliateTask
{
	private static final Logger LOGGER = Logger.getLogger(FakePlayerPvpRetaliateTask.class.getName());
	private static final long REINFORCE_INTERVAL = 400;
	private static final long MEMORY_MS = 8000;

	private final Map<Creature, Attacker> _recentAttackers = new ConcurrentHashMap<>();

	private FakePlayerPvpRetaliateTask()
	{
		Containers.Global().addListener(new ConsumerEventListener(Containers.Global(), EventType.ON_CREATURE_DAMAGE_RECEIVED, (OnCreatureDamageReceived event) -> onDamageReceived(event), this));
		ThreadPool.scheduleAtFixedRate(this::reinforce, REINFORCE_INTERVAL, REINFORCE_INTERVAL);
		LOGGER.info("FakePlayerPvpRetaliateTask: started, listening for ON_CREATURE_DAMAGE_RECEIVED, reinforcing every " + REINFORCE_INTERVAL + "ms.");
	}

	private void onDamageReceived(OnCreatureDamageReceived event)
	{
		final Creature target = event.getTarget();
		final Creature attacker = event.getAttacker();
		if ((target == null) || (attacker == null) || !target.isFakePlayer() || !attacker.isPlayer() || attacker.isFakePlayer())
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

	private void retaliate(Creature target, Player attacker)
	{
		if (target.isDead() || target.isInsideZone(ZoneId.PEACE) || attacker.isInsideZone(ZoneId.PEACE))
		{
			return;
		}

		if (!target.isRunning())
		{
			target.setRunning();
		}

		target.getAI().setIntention(Intention.ATTACK, attacker);
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
