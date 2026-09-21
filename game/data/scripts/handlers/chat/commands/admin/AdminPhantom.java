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
package handlers.chat.commands.admin;

import java.util.List;

import org.l2jmobius.gameserver.data.xml.PhantomPlaystyleData;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.managers.PhantomManager;
import org.l2jmobius.gameserver.managers.PhantomPartyManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Player;

/**
 * Admin control for the real-Player phantom slice.<br>
 * Usage:
 * <ul>
 * <li>{@code //phantom spawn [count] [level] [classId]} - spawn N clientless phantoms at your position
 * (default 1) brought to the given level (default 1). Optional {@code classId} pins the exact class instead
 * of the random roll (e.g. 21 = Sword Singer, 100 = Sword Muse), for targeted class testing.</li>
 * <li>{@code //phantom reload} - re-read PhantomPopulations.xml live (populations + editor-authored
 * {@code <friend>} orders): everything despawns, zones redeploy on approach, friends rejoin in ~15s.</li>
 * <li>{@code //phantom clear} - despawn all phantoms.</li>
 * <li>{@code //phantom count} - report how many are active.</li>
 * <li>{@code //phantom debug [on|off]} - toggle the phantom combat trace (logs to the gameserver console).</li>
 * <li>{@code //debug_on} / {@code //debug_off} - shortcuts for the same combat trace, usable on their own.</li>
 * <li>{@code //phantom playstyle} - re-read PhantomPlaystyles.xml live (per-class combat playstyles);
 * recruited members pick the new data up on their next combat tick.</li>
 * <li>{@code //phantom playstyle check} - report problems found in the file (unknown condition, bad
 * skill id, duplicate class id) without reloading.</li>
 * </ul>
 */
public class AdminPhantom implements IAdminCommandHandler
{
	private static final String[] ADMIN_COMMANDS =
	{
		"admin_phantom",
		"admin_debug_on",
		"admin_debug_off"
	};

	@Override
	public boolean onCommand(String command, Player activeChar)
	{
		final String[] words = command.split(" ");
		// Standalone shortcuts //debug_on and //debug_off flip the same PhantomPartyManager.DEBUG flag as
		// "//phantom debug on|off", so a tester can turn the combat trace on or off without the "phantom" prefix.
		final String base = words[0].toLowerCase();
		if (base.equals("admin_debug_on") || base.equals("admin_debug_off"))
		{
			PhantomPartyManager.DEBUG = base.equals("admin_debug_on");
			activeChar.sendSysMessage("Phantom combat debug trace: " + (PhantomPartyManager.DEBUG ? "ON" : "OFF") + " (logs to the gameserver console).");
			return true;
		}
		if (words.length < 2)
		{
			activeChar.sendSysMessage("Usage: //phantom spawn [count] [level] [classId] | reload | clear | count | debug [on|off] | playstyle [check]");
			return false;
		}

		switch (words[1].toLowerCase())
		{
			case "spawn":
			{
				int count = 1;
				if (words.length > 2)
				{
					try
					{
						count = Math.max(1, Math.min(20, Integer.parseInt(words[2])));
					}
					catch (NumberFormatException e)
					{
						activeChar.sendSysMessage("Count must be a number (1-20).");
						return false;
					}
				}

				int level = 1;
				if (words.length > 3)
				{
					try
					{
						level = Math.max(1, Math.min(80, Integer.parseInt(words[3])));
					}
					catch (NumberFormatException e)
					{
						activeChar.sendSysMessage("Level must be a number (1-80).");
						return false;
					}
				}

				// Optional 4th arg: pin the exact class id (e.g. 21 = Sword Singer, 100 = Sword Muse) instead of
				// rolling a random class - for targeted class testing. 0/absent keeps the normal random pick.
				int classId = 0;
				if (words.length > 4)
				{
					try
					{
						classId = Math.max(0, Integer.parseInt(words[4]));
					}
					catch (NumberFormatException e)
					{
						activeChar.sendSysMessage("Class id must be a number (e.g. 21 = Sword Singer).");
						return false;
					}
				}

				int spawned = 0;
				for (int i = 0; i < count; i++)
				{
					// Scatter slightly around the admin so they do not stack on one tile.
					final Location location = new Location(activeChar.getX() + ((i % 5) * 40), activeChar.getY() + ((i / 5) * 40), activeChar.getZ());
					if (PhantomManager.getInstance().spawnPhantom(location, level, classId) != null)
					{
						spawned++;
					}
				}
				activeChar.sendSysMessage("Spawned " + spawned + "/" + count + " phantom(s) at level " + level + (classId > 0 ? " class " + classId : "") + ". Active: " + PhantomManager.getInstance().getCount());
				break;
			}
			case "reload":
			{
				// Re-read PhantomPopulations.xml live: populations + editor-authored <friend> orders.
				activeChar.sendSysMessage(PhantomManager.getInstance().reloadPopulations());
				break;
			}
			case "clear":
			{
				final int removed = PhantomManager.getInstance().clear();
				activeChar.sendSysMessage("Despawned " + removed + " phantom(s).");
				break;
			}
			case "count":
			{
				activeChar.sendSysMessage("Active phantoms: " + PhantomManager.getInstance().getCount());
				break;
			}
			case "playstyle":
			{
				// "check" reports the problems found by the last parse without reloading; bare "playstyle"
				// re-reads the file live and members re-resolve on their next combat tick.
				if ((words.length > 2) && words[2].equalsIgnoreCase("check"))
				{
					final List<String> warnings = PhantomPlaystyleData.getInstance().getWarnings();
					if (warnings.isEmpty())
					{
						activeChar.sendSysMessage("PhantomPlaystyles.xml: no problems found.");
					}
					else
					{
						activeChar.sendSysMessage("PhantomPlaystyles.xml: " + warnings.size() + " problem(s):");
						for (String warning : warnings)
						{
							activeChar.sendSysMessage(" - " + warning);
						}
					}
					break;
				}
				activeChar.sendSysMessage(PhantomPlaystyleData.getInstance().reload());
				break;
			}
			case "debug":
			{
				// Toggle the raid combat trace (//phantom debug on|off). Logs go to the gameserver log, raid-only.
				if (words.length > 2)
				{
					PhantomPartyManager.DEBUG = words[2].equalsIgnoreCase("on") || words[2].equalsIgnoreCase("true") || words[2].equals("1");
				}
				else
				{
					PhantomPartyManager.DEBUG = !PhantomPartyManager.DEBUG; // no arg = flip it
				}
				activeChar.sendSysMessage("Phantom raid debug trace: " + (PhantomPartyManager.DEBUG ? "ON" : "OFF") + " (logs to the gameserver console).");
				break;
			}
			default:
			{
				activeChar.sendSysMessage("Usage: //phantom spawn [count] [level] [classId] | reload | clear | count | debug [on|off] | playstyle [check]");
				return false;
			}
		}

		return true;
	}

	@Override
	public String[] getCommandList()
	{
		return ADMIN_COMMANDS;
	}
}
