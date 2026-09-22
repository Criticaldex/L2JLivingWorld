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

import java.lang.reflect.Field;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.managers.PhantomManager;
import org.l2jmobius.gameserver.managers.PhantomPartyManager;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Player;

/**
 * A recruited party member (!lf) told to "attack freely" is handed off to the stock AutoPlay engine
 * (5000-range monster search, PhantomManager#setRecruitHunting), but the closed PhantomPartyManager#combatTick
 * also runs an unconditional owner-leash check every ~1s tick regardless of the member's free-hunt/assist
 * state: once the recruit drifts more than LEASH_RANGE (1400) units from its owner, it force-disables AutoPlay
 * hunting and walks/teleports the recruit back, only re-arming hunting again via a flat 4-second scheduled
 * callback. That 4s stall on every leash trip is why free-hunting recruits "stop sometimes" and struggle to
 * sustain a fight on anything far from the owner even though AutoPlay's own search range is much larger.
 *
 * AutoPlayTaskManager#startAutoPlay is idempotent (early-returns if the player's task is already running), so
 * rather than trying to detect the exact moment combatTick disables hunting, this just re-arms hunting on every
 * sweep for any free-hunt recruit currently back within leash range - a harmless no-op if it's already hunting,
 * and an immediate re-arm (instead of waiting out the engine's flat 4s timer) if it's mid leash-recall.
 *
 * PhantomPartyManager exposes no public accessor for a recruit's Member (assist flag) or owner other than the
 * public getRecruitOwner(Player), so the private "_members" map and the package-private Member#assist field are
 * read via reflection - same setAccessible(true) technique custom/SubclassUnlock/SubclassUnlock.java already
 * uses for closed-engine private fields.
 * @author Living World
 */
public class PhantomFreeHuntLeashFixTask
{
	private static final Logger LOGGER = Logger.getLogger(PhantomFreeHuntLeashFixTask.class.getName());
	private static final long CHECK_INTERVAL = 500;
	private static final double LEASH_RANGE = 1400.0;

	private final Field membersField;

	private PhantomFreeHuntLeashFixTask()
	{
		Field field = null;
		try
		{
			field = PhantomPartyManager.class.getDeclaredField("_members");
			field.setAccessible(true);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "PhantomFreeHuntLeashFixTask: failed to access PhantomPartyManager._members, disabling.", e);
		}

		membersField = field;
		if (membersField != null)
		{
			ThreadPool.scheduleAtFixedRate(this::checkRecruits, CHECK_INTERVAL, CHECK_INTERVAL);
			LOGGER.info("PhantomFreeHuntLeashFixTask: started, sweeping every " + CHECK_INTERVAL + "ms.");
		}
	}

	private void checkRecruits()
	{
		try
		{
			final Map<?, ?> members = (Map<?, ?>) membersField.get(PhantomPartyManager.getInstance());
			final PhantomManager phantomManager = PhantomManager.getInstance();
			for (Player player : World.getInstance().getPlayers())
			{
				if (player.isDead() || !phantomManager.isRecruit(player))
				{
					continue;
				}

				final Object member = members.get(player.getObjectId());
				if ((member == null) || isAssisting(member))
				{
					continue;
				}

				final Player owner = phantomManager.getRecruitOwner(player);
				if ((owner != null) && (player.calculateDistance2D(owner) <= LEASH_RANGE))
				{
					phantomManager.setRecruitHunting(player, true);
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "PhantomFreeHuntLeashFixTask: error while sweeping recruits.", e);
		}
	}

	private boolean isAssisting(Object member)
	{
		try
		{
			final Field assistField = member.getClass().getDeclaredField("assist");
			assistField.setAccessible(true);
			return assistField.getBoolean(member);
		}
		catch (Exception e)
		{
			return true;
		}
	}

	public static void main(String[] args)
	{
		new PhantomFreeHuntLeashFixTask();
	}
}
