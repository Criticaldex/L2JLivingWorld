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

import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.zone.ZoneId;

/**
 * A player attacking a fake player is supposed to trigger the normal NPC hate/combat system
 * (Attackable#addDamage -> addDamageHate -> thinkActive's getMostHated/Intention.ATTACK), but in practice
 * fake players (including plain auto-hunt field hunters with no core AI disabled and nothing to do with
 * PhantomPartyManager's mob-only hunting tick - confirmed via the phantom combat debug trace staying
 * completely silent for a player attacker) do not visibly fight back. Rather than continue guessing at the
 * closed engine's silent failure, this makes retaliation happen explicitly from the datapack side: every
 * tick, any fake player that currently has hate on a player (dead ones excepted) is pointed at the
 * highest-hate player and told to attack, overriding whatever else it was doing (a player
 * hitting it is always the priority over any monster it was hunting). Deliberately does not skip fake
 * players with core AI disabled (FakePlayerBehaviorManager sets that on a bot summoned via !lf while it
 * waits to be recruited/traded with) - the engine's own automatic retaliation shortcuts (thinkActive's
 * idle-scan, onActionAttacked) check isCoreAIDisabled before acting, but the actual execution path
 * (AbstractAI#setIntention, AttackableAI#onIntentionAttack, thinkAttack) never does, so forcing the
 * intention here still works even for a bot stuck waiting on you mid-recruit. Skips starting or continuing
 * this in a peace zone; PeaceZoneCombatStopTask separately disengages any fight that ends up there anyway.
 * @author Living World
 */
public class FakePlayerPvpRetaliateTask
{
	private static final Logger LOGGER = Logger.getLogger(FakePlayerPvpRetaliateTask.class.getName());
	private static final long CHECK_INTERVAL = 1000;

	private FakePlayerPvpRetaliateTask()
	{
		ThreadPool.scheduleAtFixedRate(this::checkFakePlayers, CHECK_INTERVAL, CHECK_INTERVAL);
	}

	private void checkFakePlayers()
	{
		try
		{
			for (WorldObject worldObject : World.getInstance().getVisibleObjects())
			{
				if (!(worldObject instanceof Npc) || !(worldObject instanceof Attackable))
				{
					continue;
				}

				final Npc npc = (Npc) worldObject;
				if (!npc.isFakePlayer() || npc.isDead())
				{
					continue;
				}

				final Player attacker = mostHatedPlayer((Attackable) npc);
				if ((attacker == null) || npc.isInsideZone(ZoneId.PEACE) || attacker.isInsideZone(ZoneId.PEACE))
				{
					continue;
				}

				if (!npc.isRunning())
				{
					npc.setRunning();
				}

				npc.getAI().setIntention(Intention.ATTACK, attacker);
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "FakePlayerPvpRetaliateTask: error while sweeping fake players.", e);
		}
	}

	private Player mostHatedPlayer(Attackable attackable)
	{
		Player best = null;
		long bestHate = 0;
		for (Creature creature : attackable.getAggroList().keySet())
		{
			if (!creature.isPlayer())
			{
				continue;
			}

			final long hate = attackable.getHating(creature);
			if ((hate > 0) && ((best == null) || (hate > bestHate)))
			{
				best = creature.asPlayer();
				bestHate = hate;
			}
		}

		return best;
	}

	public static void main(String[] args)
	{
		new FakePlayerPvpRetaliateTask();
	}
}
