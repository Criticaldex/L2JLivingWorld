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

import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.managers.CastleManager;
import org.l2jmobius.gameserver.model.clan.Clan;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.itemcontainer.ItemContainer;
import org.l2jmobius.gameserver.model.siege.Castle;

/**
 * Every SWEEP_INTERVAL, converts each full GOLD_BAR_THRESHOLD (1,000,000,000 adena) sitting in Aden's
 * castle treasury into one Gold Bar (item 3470, the same item the stock Banking voiced command trades
 * 1-for-1 against BankingConfig.BANKING_SYSTEM_ADENA) minted directly into Aden's owning clan's guild
 * warehouse, deducting the same amount from the treasury. Uses Castle.addToTreasuryNoTax(long) with a
 * NEGATIVE amount to withdraw - decompile-confirmed (javap -p -c on libs/GameServer.jar) that method
 * already handles a negative delta correctly (negates it, refuses if _treasury is short, otherwise
 * subtracts and persists via the same "UPDATE castle SET treasury=?" it uses for deposits), so no new
 * Castle API/reflection is needed for the withdrawal half of this.
 * @author Living World
 */
public class CastleVaultGoldBarTask
{
	private static final Logger LOGGER = Logger.getLogger(CastleVaultGoldBarTask.class.getName());
	private static final long SWEEP_INTERVAL = 60000;
	private static final int CASTLE_ID = 5; // Aden
	private static final long GOLD_BAR_THRESHOLD = 1_000_000_000L;
	private static final int GOLD_BAR_ITEM_ID = 3470;

	private CastleVaultGoldBarTask()
	{
		ThreadPool.scheduleAtFixedRate(this::sweep, SWEEP_INTERVAL, SWEEP_INTERVAL);
		LOGGER.info("CastleVaultGoldBarTask: started, sweeping every " + SWEEP_INTERVAL + "ms.");
	}

	private void sweep()
	{
		try
		{
			final Castle castle = CastleManager.getInstance().getCastleById(CASTLE_ID);
			if (castle == null)
			{
				return;
			}

			final int bars = (int) (castle.getTreasury() / GOLD_BAR_THRESHOLD);
			if (bars <= 0)
			{
				return;
			}

			final Clan owner = castle.getOwner();
			if (owner == null)
			{
				return;
			}

			final ItemContainer warehouse = owner.getWarehouse();
			if (warehouse == null)
			{
				return;
			}

			final long withdrawAmount = bars * GOLD_BAR_THRESHOLD;
			if (!castle.addToTreasuryNoTax(-withdrawAmount))
			{
				return;
			}

			warehouse.addItem(ItemProcessType.REWARD, GOLD_BAR_ITEM_ID, bars, null, null);
			LOGGER.info("CastleVaultGoldBarTask: converted " + withdrawAmount + " Adena from Aden's treasury into " + bars + " Gold Bar(s) in " + owner.getName() + "'s guild warehouse.");
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "CastleVaultGoldBarTask: error while sweeping.", e);
		}
	}

	public static void main(String[] args)
	{
		new CastleVaultGoldBarTask();
	}
}
