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

import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Player;

/**
 * @author L2 Living World
 */
public class WannaPwn implements IVoicedCommandHandler
{
	private static final String[] VOICED_COMMANDS =
	{
		"wannapwn"
	};

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
			player.sendMessage("Target " + targetName + " @ " + targetPlayer.getX() + ", " + targetPlayer.getY() + ", " + targetPlayer.getZ() + ". Move " + moveText + " to reach.");
		}

		return true;
	}

	@Override
	public String[] getCommandList()
	{
		return VOICED_COMMANDS;
	}
}
