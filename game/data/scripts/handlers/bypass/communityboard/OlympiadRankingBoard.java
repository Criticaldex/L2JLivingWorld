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
package handlers.bypass.communityboard;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.cache.HtmCache;
import org.l2jmobius.gameserver.config.OlympiadConfig;
import org.l2jmobius.gameserver.data.sql.CharInfoTable;
import org.l2jmobius.gameserver.handler.CommunityBoardHandler;
import org.l2jmobius.gameserver.handler.IParseBoardHandler;
import org.l2jmobius.gameserver.managers.PhantomOlympiadRules;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.olympiad.Olympiad;

/**
 * Community Board tab for live Grand Olympiad rankings, grouped by (3rd) class. The main page lists every
 * class Olympiad actually runs classed matches for - derived at request time from
 * PlayerClass.values()/PhantomOlympiadRules.isThirdClass(int) rather than a hardcoded id range, so this
 * never drifts from whatever the engine itself considers a classed-match-eligible class. Each class links
 * to a sub-page built from Olympiad.getInstance().getClassLeaderBoard(classId) - the same public API the
 * closed engine's own Olympiad Manager NPC dialog uses, a live top-10-by-points query straight against the
 * `olympiad_nobles` table (or `olympiad_nobles_eom` if OlympiadShowMonthlyWinners is true; this server runs
 * with that False, so it's always the live in-progress standings, not last month's).
 * @author Living World
 */
public class OlympiadRankingBoard implements IParseBoardHandler
{
	private static final Logger LOGGER = Logger.getLogger(OlympiadRankingBoard.class.getName());

	private static final String NAVIGATION_PATH = "data/html/CommunityBoard/Custom/navigation.html";
	private static final String MAIN_PATH = "data/html/CommunityBoard/Custom/olympiad/main.html";
	private static final String CLASS_PATH = "data/html/CommunityBoard/Custom/olympiad/class.html";

	private static final String[] COMMANDS =
	{
		"_bbsolympiad",
		"_bbsolympiadclass"
	};

	@Override
	public boolean onCommand(String command, Player player)
	{
		try
		{
			final String navigation = HtmCache.getInstance().getHtm(player, NAVIGATION_PATH);
			String html;
			if (command.startsWith("_bbsolympiadclass;"))
			{
				final int classId = Integer.parseInt(command.substring("_bbsolympiadclass;".length()));
				html = HtmCache.getInstance().getHtm(player, CLASS_PATH);
				if (html == null)
				{
					LOGGER.warning("OlympiadRankingBoard: missing HTML at " + CLASS_PATH);
					return true;
				}

				html = html.replace("%classname%", formatClassName(classId));
				html = html.replace("%rankings%", buildRankingRows(classId));
			}
			else
			{
				html = HtmCache.getInstance().getHtm(player, MAIN_PATH);
				if (html == null)
				{
					LOGGER.warning("OlympiadRankingBoard: missing HTML at " + MAIN_PATH);
					return true;
				}

				html = html.replace("%classlist%", buildClassList());
			}

			html = html.replace("%navigation%", navigation);
			CommunityBoardHandler.separateAndSend(html, player);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "OlympiadRankingBoard: error handling command '" + command + "'.", e);
			player.sendMessage("The Olympiad rankings board is temporarily unavailable.");
		}

		return true;
	}

	private static String buildClassList()
	{
		if (!OlympiadConfig.OLYMPIAD_ENABLED)
		{
			return "<tr><td align=\"center\">The Grand Olympiad is not currently enabled.</td></tr>";
		}

		final List<PlayerClass> thirdClasses = new ArrayList<>();
		for (PlayerClass playerClass : PlayerClass.values())
		{
			if (PhantomOlympiadRules.isThirdClass(playerClass.getId()))
			{
				thirdClasses.add(playerClass);
			}
		}

		final StringBuilder sb = new StringBuilder(thirdClasses.size() * 60);
		int column = 0;
		for (PlayerClass playerClass : thirdClasses)
		{
			if (column == 0)
			{
				sb.append("<tr>");
			}

			sb.append("<td width=153><a action=\"bypass _bbsolympiadclass;").append(playerClass.getId()).append("\">").append(formatClassName(playerClass.getId())).append("</a></td>");

			column++;
			if (column == 3)
			{
				sb.append("</tr>");
				column = 0;
			}
		}

		if (column != 0)
		{
			sb.append("</tr>");
		}

		return sb.toString();
	}

	private static String buildRankingRows(int classId)
	{
		final List<String> names = Olympiad.getInstance().getClassLeaderBoard(classId);
		if (names.isEmpty())
		{
			return "<tr><td align=\"center\">No ranked competitors yet for this class.</td></tr>";
		}

		final StringBuilder sb = new StringBuilder(names.size() * 50);
		int rank = 1;
		for (String name : names)
		{
			final int objectId = CharInfoTable.getInstance().getIdByName(name);
			final int points = (objectId > 0) ? Olympiad.getInstance().getNoblePoints(objectId) : 0;
			sb.append("<tr><td width=40>").append(rank).append(".</td><td width=200>").append(name).append("</td><td width=80>").append(points).append(" pts</td></tr>");
			rank++;
		}

		return sb.toString();
	}

	private static String formatClassName(int classId)
	{
		final PlayerClass playerClass = PlayerClass.getPlayerClass(classId);
		if (playerClass == null)
		{
			return "Unknown";
		}

		final String[] words = playerClass.name().split("_");
		final StringBuilder sb = new StringBuilder(playerClass.name().length());
		for (String word : words)
		{
			if (sb.length() > 0)
			{
				sb.append(' ');
			}

			sb.append(word.charAt(0)).append(word.substring(1).toLowerCase());
		}

		return sb.toString();
	}

	@Override
	public String[] getCommandList()
	{
		return COMMANDS;
	}
}
