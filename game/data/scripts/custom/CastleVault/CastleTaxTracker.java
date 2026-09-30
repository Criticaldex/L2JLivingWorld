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
package custom.CastleVault;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.managers.CastleManager;
import org.l2jmobius.gameserver.model.siege.Castle;

import custom.FakePlayers.ManorBotBuyerTask;

/**
 * Backs the "%tax_income_reserved%" placeholder on the Castle Chamberlain's manage-vault page
 * (CastleChamberlain.java, castlemanagevault.html) - that placeholder was hardcoded to the literal string
 * "0" with a "// TODO: Implement me!" comment, i.e. real castle tax income was never actually tracked
 * anywhere. The closed engine has no field/event that exposes "how much of this castle's treasury came
 * from tax specifically" - Castle.getTreasury() is just the raw current balance, mixing tax income with
 * anything else that ever touched it. This reconstructs tax income indirectly, every SWEEP_INTERVAL, from
 * the treasury's own delta since the last sweep, subtracting out the two other treasury-affecting sources
 * this datapack itself controls (ManorBotBuyerTask's seed-buying income and CastleVaultGoldBarTask's
 * withdrawals, both Aden-only) so what's left is attributable to real tax (native NPC/CB purchase tax via
 * RequestBuyItem/RequestBuySeed/MultiSellChoose, or - for castles other than Aden - simply whatever the
 * engine itself ever credits to them, since this datapack doesn't touch any other castle's treasury at all).
 * A negative delta (e.g. a lord manually withdrawing via the Chamberlain's own vault "Withdraw" button) is
 * ignored rather than allowed to decrement the counter - this tracks cumulative tax *collected*, not the
 * current balance, so a later withdrawal shouldn't erase history; the tradeoff is that a withdrawal and a
 * real tax credit landing in the same sweep window can partially mask each other, undercounting that
 * window - acceptable for a "roughly how much tax have we made" display, not exact accounting.
 * Not persisted - resets to 0 on every GameServer restart, same as ManorBotBuyerTask/CastleVaultGoldBarTask
 * not persisting their own counters either.
 * @author Living World
 */
public class CastleTaxTracker
{
	private static final Logger LOGGER = Logger.getLogger(CastleTaxTracker.class.getName());
	private static final long SWEEP_INTERVAL = 60000;
	private static final int ADEN_CASTLE_ID = 5;

	private static final Map<Integer, Long> LAST_TREASURY = new ConcurrentHashMap<>();
	private static final Map<Integer, Long> TAX_INCOME = new ConcurrentHashMap<>();

	private CastleTaxTracker()
	{
		ThreadPool.scheduleAtFixedRate(this::sweep, SWEEP_INTERVAL, SWEEP_INTERVAL);
		LOGGER.info("CastleTaxTracker: started, sweeping every " + SWEEP_INTERVAL + "ms.");
	}

	private void sweep()
	{
		try
		{
			final List<Castle> castles = CastleManager.getInstance().getCastles();
			for (Castle castle : castles)
			{
				final int id = castle.getResidenceId();
				final long current = castle.getTreasury();
				final long last = LAST_TREASURY.getOrDefault(id, current);
				LAST_TREASURY.put(id, current);

				long delta = current - last;
				if (id == ADEN_CASTLE_ID)
				{
					delta -= ManorBotBuyerTask.drainIncomeSinceLastCheck();
					delta += CastleVaultGoldBarTask.drainWithdrawnSinceLastCheck();
				}

				if (delta > 0)
				{
					TAX_INCOME.merge(id, delta, Long::sum);
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "CastleTaxTracker: error while sweeping.", e);
		}
	}

	/**
	 * @param castleId the castle's residence id
	 * @return the cumulative Adena attributed to real castle tax since this GameServer process started
	 */
	public static long getTaxIncome(int castleId)
	{
		return TAX_INCOME.getOrDefault(castleId, 0L);
	}

	public static void main(String[] args)
	{
		new CastleTaxTracker();
	}
}
