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

import org.l2jmobius.gameserver.managers.PhantomManager;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.events.Containers;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.actor.creature.OnCreatureDamageReceived;
import org.l2jmobius.gameserver.model.events.holders.actor.creature.OnCreatureDeath;
import org.l2jmobius.gameserver.model.events.holders.actor.creature.OnCreatureKilled;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerPvPChanged;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerPvPKill;
import org.l2jmobius.gameserver.model.events.listeners.ConsumerEventListener;

/**
 * TEMPORARY - remove this whole file once boot-testing confirms the v0.1.25 native Phantom PvP system
 * (which replaced the removed FakePlayerPvpRetaliateTask/PeaceZoneCombatStopTask) is actually working,
 * and that the still-unpatched AntiFeedManager NPE risk documented in CLAUDE.md either didn't materialize
 * or has been dealt with. Every line is prefixed "PhantomPvpDebug:" for easy grepping out of game/log/.
 * Logs:
 * - a real player hitting a bot-controlled phantom (native self-defense should react to this; whether it
 *   actually swings back still has to be checked in-game, this only proves the trigger fired)
 * - a bot-controlled phantom's PvP points changing (proof the native system is awarding it PvP kills)
 * - any PvP kill involving a bot-controlled phantom on either side
 * - a bot-controlled phantom dying while PvP-flagged or karma-bearing, from BOTH ON_CREATURE_DEATH and
 *   ON_CREATURE_KILLED - if only one of the two logs for the same death, that is the signature of
 *   AntiFeedManager.check()'s unguarded victim.getClient().isDetached() NPE (getClient() is null for a
 *   Player-typed phantom, still present in the v0.1.25 jar per decompile) aborting Playable.doDie()
 *   partway through.
 * @author Living World
 */
public class PhantomPvpDebugLogTask
{
	private static final Logger LOGGER = Logger.getLogger(PhantomPvpDebugLogTask.class.getName());
	private static final long DAMAGE_LOG_COOLDOWN_MS = 10000;

	private final Map<Long, Long> _lastDamageLog = new ConcurrentHashMap<>();

	private PhantomPvpDebugLogTask()
	{
		Containers.Global().addListener(new ConsumerEventListener(Containers.Global(), EventType.ON_CREATURE_DAMAGE_RECEIVED, (OnCreatureDamageReceived event) -> onDamageReceived(event), this));
		Containers.Global().addListener(new ConsumerEventListener(Containers.Global(), EventType.ON_PLAYER_PVP_CHANGED, (OnPlayerPvPChanged event) -> onPvpChanged(event), this));
		Containers.Global().addListener(new ConsumerEventListener(Containers.Global(), EventType.ON_PLAYER_PVP_KILL, (OnPlayerPvPKill event) -> onPvpKill(event), this));
		Containers.Global().addListener(new ConsumerEventListener(Containers.Global(), EventType.ON_CREATURE_DEATH, (OnCreatureDeath event) -> logIfRiskyPhantomDeath("ON_CREATURE_DEATH", event.getAttacker(), event.getTarget()), this));
		Containers.Global().addListener(new ConsumerEventListener(Containers.Global(), EventType.ON_CREATURE_KILLED, (OnCreatureKilled event) -> logIfRiskyPhantomDeath("ON_CREATURE_KILLED", event.getAttacker(), event.getTarget()), this));
		LOGGER.info("PhantomPvpDebugLogTask: started (temporary debug logging for the v0.1.25 Phantom PvP changeover - remove once confirmed).");
	}

	private void onDamageReceived(OnCreatureDamageReceived event)
	{
		try
		{
			final Creature attacker = event.getAttacker();
			final Creature target = event.getTarget();
			if ((attacker == null) || (target == null) || !target.isPlayer() || !attacker.isPlayer())
			{
				return;
			}

			final Player targetPlayer = target.asPlayer();
			final Player attackerPlayer = attacker.asPlayer();
			if (!isBotControlled(targetPlayer) || isBotControlled(attackerPlayer))
			{
				return;
			}

			final long key = (((long) attackerPlayer.getObjectId()) << 32) | (targetPlayer.getObjectId() & 0xFFFFFFFFL);
			final long now = System.currentTimeMillis();
			final Long last = _lastDamageLog.get(key);
			if ((last != null) && ((now - last) < DAMAGE_LOG_COOLDOWN_MS))
			{
				return;
			}
			_lastDamageLog.put(key, now);

			LOGGER.info("PhantomPvpDebug: real player " + attackerPlayer.getName() + " hit bot-controlled phantom " + targetPlayer.getName() + " - expecting native self-defense to react.");
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "PhantomPvpDebugLogTask: error in onDamageReceived.", e);
		}
	}

	private void onPvpChanged(OnPlayerPvPChanged event)
	{
		try
		{
			final Player player = event.getPlayer();
			if ((player == null) || !isBotControlled(player))
			{
				return;
			}

			LOGGER.info("PhantomPvpDebug: bot-controlled phantom " + player.getName() + " PvP points changed " + event.getOldPoints() + " -> " + event.getNewPoints() + ".");
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "PhantomPvpDebugLogTask: error in onPvpChanged.", e);
		}
	}

	private void onPvpKill(OnPlayerPvPKill event)
	{
		try
		{
			final Player killer = event.getPlayer();
			final Player target = event.getTarget();
			if ((killer == null) || (target == null))
			{
				return;
			}

			final boolean killerIsBot = isBotControlled(killer);
			final boolean targetIsBot = isBotControlled(target);
			if (!killerIsBot && !targetIsBot)
			{
				return;
			}

			LOGGER.info("PhantomPvpDebug: PvP kill - " + killer.getName() + " (bot=" + killerIsBot + ") killed " + target.getName() + " (bot=" + targetIsBot + ").");
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "PhantomPvpDebugLogTask: error in onPvpKill.", e);
		}
	}

	private void logIfRiskyPhantomDeath(String eventName, Creature attacker, Creature target)
	{
		try
		{
			if ((target == null) || !target.isPlayer())
			{
				return;
			}

			final Player targetPlayer = target.asPlayer();
			if (!isBotControlled(targetPlayer))
			{
				return;
			}

			final int karma = targetPlayer.getKarma();
			final byte pvpFlag = targetPlayer.getPvpFlag();
			if ((karma <= 0) && (pvpFlag <= 0))
			{
				return;
			}

			final String attackerName = (attacker == null) ? "unknown" : attacker.getName();
			LOGGER.info("PhantomPvpDebug: " + eventName + " - flagged/karma phantom " + targetPlayer.getName() + " (karma=" + karma + ", pvpFlag=" + pvpFlag + ") died to " + attackerName
				+ ". If only ONE of ON_CREATURE_DEATH/ON_CREATURE_KILLED logs for this death, that's the AntiFeedManager NPE (see CLAUDE.md) aborting doDie() partway through.");
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "PhantomPvpDebugLogTask: error in logIfRiskyPhantomDeath.", e);
		}
	}

	/**
	 * Npc-based fake players set isFakePlayer(); Player-typed phantoms/recruits/buddies/regulars never do -
	 * PhantomManager's own isPhantom/isRecruit/isBuddy/isRegular checks are the only way to identify them.
	 */
	private static boolean isBotControlled(Player player)
	{
		final PhantomManager phantomManager = PhantomManager.getInstance();
		return player.isFakePlayer() || phantomManager.isPhantom(player) || phantomManager.isRecruit(player) || phantomManager.isBuddy(player) || phantomManager.isRegular(player);
	}

	public static void main(String[] args)
	{
		new PhantomPvpDebugLogTask();
	}
}
