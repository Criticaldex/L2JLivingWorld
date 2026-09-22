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
import org.l2jmobius.gameserver.managers.PhantomManager;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.zone.ZoneId;

/**
 * The closed-source combat AI only checks ZoneId.PEACE/NO_PVP when a fake player first decides to go
 * hostile (AttackableAI#isAggressiveTowards, and the FakePlayerAggroPlayers-gated target pick in
 * AttackableAI#lambda$thinkActive$0) - once hate/intention exists, AttackableAI#thinkAttack (which drives
 * every attack tick) never re-checks either side's zone. So a fight that starts outside town keeps going
 * if either the fake player or its target crosses into a peace zone. This periodically sweeps every
 * in-combat fake player - both Npc-based (isFakePlayer()) and Player-typed Phantoms/recruits/buddies
 * (PhantomManager.isPhantom/isRecruit/isBuddy/isRegular, same detection as FakePlayerPvpRetaliateTask) -
 * and force-disengages it if it, or whatever it is currently targeting, is inside a peace zone. Covering
 * only Npc originally let a Player-typed phantom's already-set Intention.ATTACK keep swinging at a target
 * that walked into town, since nothing was watching that type at all. A workaround from the datapack side;
 * the actual fix belongs in the closed AttackableAI.
 * @author Living World
 */
public class PeaceZoneCombatStopTask
{
	private static final Logger LOGGER = Logger.getLogger(PeaceZoneCombatStopTask.class.getName());
	private static final long CHECK_INTERVAL = 200;

	private PeaceZoneCombatStopTask()
	{
		ThreadPool.scheduleAtFixedRate(this::checkFakePlayers, CHECK_INTERVAL, CHECK_INTERVAL);
		LOGGER.info("PeaceZoneCombatStopTask: started, sweeping every " + CHECK_INTERVAL + "ms.");
	}

	private void checkFakePlayers()
	{
		try
		{
			for (WorldObject worldObject : World.getInstance().getVisibleObjects())
			{
				if (!(worldObject instanceof Creature))
				{
					continue;
				}

				final Creature creature = (Creature) worldObject;
				if (!isBotControlled(creature) || !creature.isInCombat())
				{
					continue;
				}

				if (creature.isInsideZone(ZoneId.PEACE) || isTargetInPeaceZone(creature))
				{
					stopCombat(creature);
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "PeaceZoneCombatStopTask: error while sweeping fake players.", e);
		}
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

	private boolean isTargetInPeaceZone(Creature creature)
	{
		final WorldObject target = creature.getTarget();
		return (target instanceof Creature) && ((Creature) target).isInsideZone(ZoneId.PEACE);
	}

	private void stopCombat(Creature creature)
	{
		creature.abortAttack();
		creature.abortCast();
		if (creature instanceof Attackable)
		{
			((Attackable) creature).clearAggroList();
		}

		creature.setTarget(null);
		creature.getAI().setIntention(Intention.ACTIVE);
	}

	public static void main(String[] args)
	{
		new PeaceZoneCombatStopTask();
	}
}
