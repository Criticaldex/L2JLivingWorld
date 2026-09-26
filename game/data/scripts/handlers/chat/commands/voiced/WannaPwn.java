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
package handlers.chat.commands.voiced;

import java.util.HashMap;
import java.util.Map;

import org.l2jmobius.gameserver.config.custom.CommunityBoardConfig;
import org.l2jmobius.gameserver.data.xml.TeleporterData;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.teleporter.TeleportHolder;
import org.l2jmobius.gameserver.model.teleporter.TeleportLocation;

/**
 * @author L2 Living World
 */
public class WannaPwn implements IVoicedCommandHandler
{
	private static final String[] VOICED_COMMANDS =
	{
		"wannapwn"
	};

	// Real "Gatekeeper" NPCs under game/data/teleporters/town/*.xml (their filenames are their npc ids).
	// No single gatekeeper's list is close to complete, so all of them are queried and merged.
	private static final int[] GATEKEEPER_NPC_IDS =
	{
		30006, 30059, 30080, 30134, 30146, 30177, 30233, 30256, 30320, 30540,
		30576, 30836, 30848, 30878, 30899, 31275, 31320, 31698, 31699, 31964
	};

	// Every gatekeeper teleport list worth naming as a landmark, not just the plain-fee menu -
	// NOBLES_TOKEN/NOBLES_ADENA cover farm/dungeon spots (e.g. Stakato Nest) that NORMAL lacks.
	private static final String[] GATEKEEPER_LIST_NAMES =
	{
		"NORMAL", "NOBLES_TOKEN", "NOBLES_ADENA"
	};

	private static Map<String, Location> allTeleports;

	@Override
	public boolean onCommand(String command, Player player, String target)
	{
		if (command.equals("wannapwn"))
		{
			if ((target == null) || target.trim().isEmpty())
			{
				player.sendMessage("Usage: .wannapwn <player name>");
				return false;
			}

			final String targetName = target.trim();
			final Player targetPlayer = World.getInstance().getPlayer(targetName);
			if (targetPlayer == null)
			{
				player.sendMessage(targetName + " is not online.");
				return false;
			}

			if (targetPlayer == player)
			{
				player.sendMessage("You're already there.");
				return false;
			}

			if (player.getInstanceId() != targetPlayer.getInstanceId())
			{
				player.sendMessage(targetName + " is not in this world.");
				return false;
			}

			final int deltaX = targetPlayer.getX() - player.getX();
			final int deltaY = targetPlayer.getY() - player.getY();

			final StringBuilder directions = new StringBuilder();
			if (deltaY != 0)
			{
				directions.append(Math.abs(deltaY)).append(' ').append(deltaY > 0 ? "North" : "South");
			}

			if (deltaX != 0)
			{
				if (directions.length() > 0)
				{
					directions.append(", ");
				}

				directions.append(Math.abs(deltaX)).append(' ').append(deltaX > 0 ? "East" : "West");
			}

			final String moveText = directions.length() > 0 ? directions.toString() : "0";
			final Map.Entry<String, Location> nearestTeleport = findNearestTeleport(targetPlayer.getX(), targetPlayer.getY());
			final String nearLabel = nearestTeleport != null ? nearestTeleport.getKey() : "an unknown location";

			final String message = "Target " + targetName + ", near " + nearLabel + " (" + targetPlayer.getX() + ", " + targetPlayer.getY() + ", " + targetPlayer.getZ() + "). Move " + moveText + " to reach " + targetName + " from your current position.";
			player.sendMessage(message);
		}

		return true;
	}

	/**
	 * Finds the nearest known teleport destination to the given world coordinates, searching both the
	 * Community Board's teleport list and every real Gatekeeper NPC's teleport lists.
	 * @param x the target X coordinate
	 * @param y the target Y coordinate
	 * @return the nearest name/location entry, or {@code null} if no teleport destinations are known
	 */
	private static Map.Entry<String, Location> findNearestTeleport(int x, int y)
	{
		Map.Entry<String, Location> nearest = null;
		long nearestDistance = Long.MAX_VALUE;
		for (Map.Entry<String, Location> entry : getAllTeleports().entrySet())
		{
			final Location location = entry.getValue();
			final long deltaX = location.getX() - x;
			final long deltaY = location.getY() - y;
			final long distance = (deltaX * deltaX) + (deltaY * deltaY);
			if (distance < nearestDistance)
			{
				nearestDistance = distance;
				nearest = entry;
			}
		}

		return nearest;
	}

	/**
	 * Lazily builds and caches the merged set of known teleport destinations: the Community Board's
	 * teleport list plus every real Gatekeeper NPC's teleport lists. Built once per server
	 * lifetime since none of this data changes at runtime outside of an admin reload.
	 * @return the merged, name-deduplicated teleport map
	 */
	private static Map<String, Location> getAllTeleports()
	{
		if (allTeleports == null)
		{
			final Map<String, Location> merged = new HashMap<>(CommunityBoardConfig.COMMUNITY_AVAILABLE_TELEPORTS);
			for (int npcId : GATEKEEPER_NPC_IDS)
			{
				for (String listName : GATEKEEPER_LIST_NAMES)
				{
					final TeleportHolder holder = TeleporterData.getInstance().getHolder(npcId, listName);
					if (holder == null)
					{
						continue;
					}

					for (TeleportLocation location : holder.getLocations())
					{
						merged.putIfAbsent(location.getName(), location);
					}
				}
			}

			allTeleports = merged;
		}

		return allTeleports;
	}

	@Override
	public String[] getCommandList()
	{
		return VOICED_COMMANDS;
	}
}
