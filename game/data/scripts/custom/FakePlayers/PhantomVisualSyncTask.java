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

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.managers.PhantomManager;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.network.serverpackets.CharInfo;

/**
 * Abnormal visual effects (Sleep's closed eyes, Silence's icon, etc.) never appear on Player-typed
 * phantoms/recruits/buddies/regulars for observers, even though the effect itself lands and applies
 * normally server-side. Root cause (decompiled): the generic engine path for this is
 * BuffInfo.addAbnormalVisualEffects() -> Creature.updateAbnormalEffect() -> for a Player, that's
 * Player.broadcastUserInfo() -> Player.broadcastCharInfo(), and broadcastCharInfo() opens with
 * "if (isOnlineInt() == 0) return;" - a silent no-op. Player.isOnlineInt() requires both
 * _isOnline == true AND a non-null _client (GameClient) - a real network session. A Player-typed
 * bot has no GameClient at all, so isOnlineInt() is always 0 and this broadcast never happens for them.
 * (Their initial visibility on spawn works via a separate one-time packet PhantomManager sends itself,
 * which is why they're visible at all - it's only later incremental state changes, like an abnormal
 * visual effect starting or ending, that never propagate.)
 * This works around it by polling each bot-controlled Player's Creature.getAbnormalVisualEffects()
 * bitmask, and whenever it changes since the last check, manually broadcasting a fresh
 * CharInfo packet (the same packet class broadcastCharInfo() would have sent) to nearby real players -
 * bypassing the isOnlineInt() gate entirely by constructing and sending it ourselves, the same way
 * Npc.updateAbnormalEffect() already does for Npc-based fake players via FakePlayerInfo/NpcInfo (that
 * path has no such gate, which is why this problem is specific to the Player-typed side).
 * @author Living World
 */
public class PhantomVisualSyncTask
{
	private static final Logger LOGGER = Logger.getLogger(PhantomVisualSyncTask.class.getName());
	private static final long CHECK_INTERVAL = 500;

	private final Map<Player, Integer> _lastAbnormalVisualMask = new ConcurrentHashMap<>();

	private PhantomVisualSyncTask()
	{
		ThreadPool.scheduleAtFixedRate(this::sweep, CHECK_INTERVAL, CHECK_INTERVAL);
		LOGGER.info("PhantomVisualSyncTask: started, sweeping every " + CHECK_INTERVAL + "ms.");
	}

	private void sweep()
	{
		try
		{
			final PhantomManager phantomManager = PhantomManager.getInstance();
			final Set<Player> stillTracked = new HashSet<>();
			for (WorldObject worldObject : World.getInstance().getVisibleObjects())
			{
				if (!(worldObject instanceof Player))
				{
					continue;
				}

				final Player player = (Player) worldObject;
				if (!isBotControlled(phantomManager, player))
				{
					continue;
				}

				stillTracked.add(player);
				final int mask = player.getAbnormalVisualEffects();
				final Integer previous = _lastAbnormalVisualMask.put(player, mask);
				if ((previous == null) || (previous.intValue() != mask))
				{
					broadcastCharInfo(player);
				}
			}

			// Player is a strong map key here - without this, a despawned/logged-off phantom's entry
			// would never be collectable and would sit in this map forever.
			_lastAbnormalVisualMask.keySet().removeIf(tracked -> !stillTracked.contains(tracked));
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "PhantomVisualSyncTask: error while sweeping.", e);
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

	private static void broadcastCharInfo(Player player)
	{
		World.getInstance().forEachVisibleObject(player, Player.class, observer ->
		{
			if (observer != player)
			{
				observer.sendPacket(new CharInfo(player, false));
			}
		});
	}

	public static void main(String[] args)
	{
		new PhantomVisualSyncTask();
	}
}
