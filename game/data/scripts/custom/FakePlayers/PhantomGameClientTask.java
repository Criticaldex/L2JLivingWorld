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

import org.l2jmobius.commons.network.Connection;
import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.managers.PhantomManager;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.network.GameClient;

/**
 * Player-typed phantoms/recruits/buddies/regulars (PhantomManager-driven Player instances, as opposed to
 * the Npc-based ambient fake players - see the "two systems" note elsewhere in this package) are never
 * associated with a real network session, so Player.getClient() returns null for them. Already known to
 * break Player.updateAbnormalEffect()'s visual broadcast (worked around by PhantomVisualSyncTask) and,
 * decompile-confirmed here, Player.onKillUpdatePvPKarma() too: the moment a real player lands the killing
 * blow on a PvP-flagged (or karma-bearing) phantom, increasePvpKills() calls
 * AntiFeedManager.check(killer, victim), which does victimPlayer.getClient().isDetached() - a
 * NullPointerException for a phantom victim. That exception is uncaught and propagates out of
 * Playable.doDie() itself, both skipping the PvP-kill credit and cutting off the tail of the death
 * sequence (notifyAction(DEATH), updateEffectIcons()) - the same failure class as the ResurrectionSpecial
 * bug documented elsewhere. Killing an *unflagged* phantom already worked correctly before this fix, since
 * that branch (increasePkKillsAndKarma) never calls AntiFeedManager at all.
 * <p>
 * Fixes this at the root instead of patching each symptom individually: gives every bot-controlled Player
 * a real (but connection-less) GameClient once, so Player.getClient() stops returning null anywhere in the
 * engine. GameClient's own constructor unconditionally calls connection.getRemoteAddress() (to seed its
 * _ip field), and its superclass Client's constructor rejects a null/closed connection outright
 * (IllegalArgumentException - decompile-confirmed), so a literal null connection does not work. Connection
 * itself only stores its four constructor arguments without dereferencing them (decompile-confirmed), so a
 * FakeConnection subclass overriding just getRemoteAddress()/isOpen() (neither is final) is enough to
 * satisfy both constructors without ever touching a real socket channel.
 * <p>
 * <b>Known behavior change, not just a theoretical risk</b>: PhantomPartyManager#findResTarget()
 * (decompile-confirmed) explicitly prefers resurrecting a party member whose getClient() is non-null over
 * a phantom party member, precisely to make a phantom healer prioritize the real human player. Since this
 * task makes that check true for every bot too, that heuristic degrades to "whichever dead member comes
 * first in iteration order" once more than one member of a party is bot-controlled - a phantom could now
 * occasionally get rezzed ahead of the human owner. Accepted trade-off for fixing the PvP/PK crash; revisit
 * if that ordering regression turns out to matter in practice.
 * @author Living World
 */
public class PhantomGameClientTask
{
	private static final Logger LOGGER = Logger.getLogger(PhantomGameClientTask.class.getName());
	private static final long SWEEP_INTERVAL = 2000;

	private PhantomGameClientTask()
	{
		ThreadPool.scheduleAtFixedRate(this::sweep, SWEEP_INTERVAL, SWEEP_INTERVAL);
		LOGGER.info("PhantomGameClientTask: started, sweeping every " + SWEEP_INTERVAL + "ms.");
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
				if ((player.getClient() != null) || !isBotControlled(phantomManager, player))
				{
					continue;
				}

				player.setClient(new GameClient(new FakeConnection()));
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "PhantomGameClientTask: error while sweeping.", e);
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

	/**
	 * Connection's own constructor only stores its four arguments (decompile-confirmed, no
	 * dereferencing), so nulling out the real channel/read-handler/write-handler/config is safe as long as
	 * nothing later calls a method that touches them - hence overriding both methods that GameClient's and
	 * Client's constructors actually call on this object.
	 */
	private static final class FakeConnection extends Connection<GameClient>
	{
		private FakeConnection()
		{
			super(null, null, null, null);
		}

		@Override
		public String getRemoteAddress()
		{
			return "0.0.0.0";
		}

		@Override
		public boolean isOpen()
		{
			return true;
		}
	}

	public static void main(String[] args)
	{
		new PhantomGameClientTask();
	}
}
