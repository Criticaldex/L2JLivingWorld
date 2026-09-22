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
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.managers.PhantomBuffs;
import org.l2jmobius.gameserver.managers.PhantomManager;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.skill.Skill;

/**
 * PhantomBuffs.applyFullBuffs() (called once, on spawn) only ever grants its own hardcoded
 * PREBUFF_COMMON/PREBUFF_MELEE/PREBUFF_CASTER/PREBUFF_BERSERKER arrays - a short "starter kit" (weapon
 * mastery, Haste, Wind Walk, a handful of resists/stat buffs), nowhere close to the ~50 buffs/songs/dances
 * a real support-buffed party member carries in retail play, and never re-applied once any of them expire.
 * Rather than invent a new buff list, this reuses the SAME two groups this server's own Community-Board
 * buffer already exposes to real players as its "full buff" presets - game/data/SchemeBufferSkills.xml's
 * FIGHTER_GROUP/MAGE_GROUP category ids - hardcoded here (a datapack script has no XML-parsing precedent
 * elsewhere in this tree; PhantomBuffs itself hardcodes its own arrays the same way) so a phantom ends up
 * carrying exactly what a player would get from using that buffer on themselves.
 * Applies via Skill.applyEffects(player, player), the same low-level primitive PhantomBuffs.applyBuffs()
 * itself uses internally (confirmed via decompile) - a direct effect grant that bypasses skill-known/MP/
 * cast time entirely, appropriate here since these are NPC-driven Player instances, not real casts.
 * Uses PhantomBuffs.needsBuff() (public, and the exact check PhantomBuffs uses internally) rather than a
 * naive "already affected by this skill id" test, since several buffs in each group share an abnormal type
 * with each other (e.g. weapon-mastery variants) - needsBuff correctly skips reapplying when an
 * equal-or-stronger buff sharing that slot is already active with time left, instead of thrashing it every
 * sweep.
 * @author Living World
 */
public class PhantomFullBuffTask
{
	private static final Logger LOGGER = Logger.getLogger(PhantomFullBuffTask.class.getName());
	private static final long SWEEP_INTERVAL = 15000;
	private static final int MIN_REMAINING_MS = 30000;

	// game/data/SchemeBufferSkills.xml, category FIGHTER_GROUP.
	private static final int[] FIGHTER_GROUP =
	{
		1257,
		311,
		1033,
		1032,
		1191,
		1182,
		1189,
		307,
		309,
		306,
		308,
		270,
		1392,
		1393,
		1044,
		265,
		266,
		1087,
		1354,
		1353,
		1352,
		1259,
		267,
		304,
		364,
		310,
		1268,
		4700,
		1062,
		264,
		349,
		1036,
		1035,
		272,
		1240,
		1040,
		1045,
		269,
		271,
		274,
		275,
		1242,
		1077,
		1388,
		1363,
		1086,
		1068,
		268,
		1204
	};

	// game/data/SchemeBufferSkills.xml, category MAGE_GROUP.
	private static final int[] MAGE_GROUP =
	{
		1257,
		311,
		1033,
		1032,
		1191,
		1182,
		1189,
		307,
		309,
		306,
		308,
		270,
		1392,
		1393,
		1044,
		265,
		266,
		1087,
		1354,
		1353,
		1352,
		1389,
		267,
		264,
		304,
		268,
		1259,
		1397,
		363,
		349,
		1035,
		1036,
		1040,
		1045,
		1048,
		1062,
		365,
		273,
		276,
		1413,
		4703,
		1303,
		1078,
		1059,
		1085,
		1204
	};

	private PhantomFullBuffTask()
	{
		ThreadPool.scheduleAtFixedRate(this::sweep, SWEEP_INTERVAL, SWEEP_INTERVAL);
		LOGGER.info("PhantomFullBuffTask: started, sweeping every " + SWEEP_INTERVAL + "ms.");
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

				applyFullBuffs(player);
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "PhantomFullBuffTask: error while sweeping.", e);
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

	private void applyFullBuffs(Player player)
	{
		final int[] group = PhantomBuffs.isCaster(player) ? MAGE_GROUP : FIGHTER_GROUP;
		for (int skillId : group)
		{
			final int maxLevel = SkillData.getInstance().getMaxLevel(skillId);
			if (maxLevel <= 0)
			{
				continue;
			}

			final Skill skill = SkillData.getInstance().getSkill(skillId, maxLevel);
			if ((skill != null) && PhantomBuffs.needsBuff(player, skill, MIN_REMAINING_MS))
			{
				skill.applyEffects(player, player);
			}
		}
	}

	public static void main(String[] args)
	{
		new PhantomFullBuffTask();
	}
}
