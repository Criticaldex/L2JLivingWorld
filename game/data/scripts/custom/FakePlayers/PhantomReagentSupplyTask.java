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
import org.l2jmobius.gameserver.managers.PhantomManager;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.skill.Skill;

/**
 * FakePlayerPvpRetaliateTask#ensureCastReagent only tops up a caster's reagent item right before OUR OWN
 * skill-picker (pickOffensiveSkill) tries it during PvP retaliation - confirmed by the user that a
 * recruited Necromancer's Death Spike (needs a Cursed Bone) works fine against them (that path) but never
 * fires against monsters. Normal monster combat for every bot-controlled Player goes through the closed
 * PhantomPlaystyleEngine.pick() instead, which this datapack has no hook into, and
 * Creature.checkDoCastConditions() hard-rejects a reagent-gated skill outright if the caster's inventory
 * doesn't hold it - so Death Spike loses to non-reagent skills there too, with no way to top up the item
 * right before that specific decision point the way ensureCastReagent does for our own picker.
 * Sidesteps this the same way PhantomFullBuffTask sidesteps PhantomBuffs' one-shot spawn buffs: a periodic
 * sweep that keeps every bot-controlled Player's inventory pre-stocked with REAGENT_STOCK of any
 * consumable item referenced by any of its known skills, well ahead of any particular cast attempt, so
 * whichever skill-picker (closed engine or our own) ends up trying it never finds the reagent missing.
 * @author Living World
 */
public class PhantomReagentSupplyTask
{
	private static final Logger LOGGER = Logger.getLogger(PhantomReagentSupplyTask.class.getName());
	private static final long SWEEP_INTERVAL = 10000;
	private static final int REAGENT_STOCK = 20;

	private PhantomReagentSupplyTask()
	{
		ThreadPool.scheduleAtFixedRate(this::sweep, SWEEP_INTERVAL, SWEEP_INTERVAL);
		LOGGER.info("PhantomReagentSupplyTask: started, sweeping every " + SWEEP_INTERVAL + "ms.");
	}

	private void sweep()
	{
		try
		{
			final PhantomManager phantomManager = PhantomManager.getInstance();
			for (WorldObject worldObject : World.getInstance().getVisibleObjects())
			{
				if (!(worldObject instanceof Player))
				{
					continue;
				}

				final Player player = (Player) worldObject;
				if (player.isDead() || !isBotControlled(phantomManager, player))
				{
					continue;
				}

				restockReagents(player);
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "PhantomReagentSupplyTask: error while sweeping.", e);
		}
	}

	/**
	 * Npc-based fake players set isFakePlayer(); Player-typed phantoms/recruits/buddies/regulars never do -
	 * they only set a private Player.isBuddyBot field with no public getter, so PhantomManager's own
	 * isPhantom/isRecruit/isBuddy/isRegular checks are the only way to identify them from outside.
	 */
	private static boolean isBotControlled(PhantomManager phantomManager, Player player)
	{
		return player.isFakePlayer() || phantomManager.isPhantom(player) || phantomManager.isRecruit(player) || phantomManager.isBuddy(player) || phantomManager.isRegular(player);
	}

	private void restockReagents(Player player)
	{
		for (Skill skill : player.getAllSkills())
		{
			final int itemId = skill.getItemConsumeId();
			if (itemId <= 0)
			{
				continue;
			}

			final int required = Math.max(Math.max(skill.getItemConsumeCount(), 1), REAGENT_STOCK);
			final int current = player.getInventory().getInventoryItemCount(itemId, -1);
			if (current < required)
			{
				player.addItem(ItemProcessType.QUEST, itemId, required - current, player, false);
			}
		}
	}

	public static void main(String[] args)
	{
		new PhantomReagentSupplyTask();
	}
}
