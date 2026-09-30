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
package custom.CastleTaxDebug;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.managers.TownManager;
import org.l2jmobius.gameserver.managers.ZoneManager;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.siege.Castle;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.model.zone.type.TownZone;

/**
 * TEMPORARY - remove this whole file once the boot-order theory for the Aden castle-tax bug documented in
 * CLAUDE.md ("Community Board custom pages - gotchas") is confirmed or refuted. Every line is prefixed
 * "AdenCastleZoneBootTrace:" for easy grepping out of game/log/.
 * Logs, at boot and at several delays afterward: whether ZoneManager has any TownZone loaded yet, what
 * TownManager.getTown() resolves for Lorenzo's exact spawn coordinates (146893,28982,-2250, Aden), and what
 * Lorenzo's own (possibly already wrongly cached) Npc.getCastle() currently returns. If getCastle() shows a
 * non-Aden residence id even at the +60s check (long after zones must be loaded), the bug is the caching
 * itself, not a boot-order race. If getTown() is null at "boot" but valid by +5s/+15s, and getCastle()'s
 * result matches whichever castle was resolvable at the moment it was FIRST called (by anything, not
 * necessarily this task) after boot, that confirms the race.
 * @author Living World
 */
public class AdenCastleZoneBootTraceTask
{
	private static final Logger LOGGER = Logger.getLogger(AdenCastleZoneBootTraceTask.class.getName());
	private static final int LORENZO_NPC_ID = 30840;
	private static final int LORENZO_X = 146893;
	private static final int LORENZO_Y = 28982;
	private static final int LORENZO_Z = -2250;
	private static final long[] DELAYS_MS =
	{
		0,
		5000,
		15000,
		30000,
		60000,
	};

	private AdenCastleZoneBootTraceTask()
	{
		for (long delay : DELAYS_MS)
		{
			ThreadPool.schedule(() -> check(delay), delay);
		}
	}

	private void check(long delay)
	{
		try
		{
			final int townZoneCount = ZoneManager.getInstance().getAllZones(TownZone.class).size();
			final TownZone zone = TownManager.getTown(LORENZO_X, LORENZO_Y, LORENZO_Z);
			final String zoneInfo = (zone == null) ? "null" : ("townId=" + zone.getTownId() + " taxById=" + zone.getTaxById());

			String npcCastleInfo = "npc-not-spawned";
			final Spawn spawn = SpawnTable.getInstance().getAnySpawn(LORENZO_NPC_ID);
			if (spawn != null)
			{
				final Npc lorenzo = spawn.getLastSpawn();
				if (lorenzo != null)
				{
					final Castle castle = lorenzo.getCastle();
					npcCastleInfo = (castle == null) ? "null" : ("residenceId=" + castle.getResidenceId() + " name=" + castle.getName());
				}
				else
				{
					npcCastleInfo = "npc-null-lastSpawn";
				}
			}

			LOGGER.info("AdenCastleZoneBootTrace: +" + delay + "ms townZoneCount=" + townZoneCount + " getTown(Lorenzo)=" + zoneInfo + " Lorenzo.getCastle()=" + npcCastleInfo);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "AdenCastleZoneBootTraceTask: error at +" + delay + "ms.", e);
		}
	}

	public static void main(String[] args)
	{
		new AdenCastleZoneBootTraceTask();
	}
}
