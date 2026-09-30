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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.managers.CastleManager;
import org.l2jmobius.gameserver.managers.CastleManorManager;
import org.l2jmobius.gameserver.managers.PhantomManager;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.siege.Castle;
import org.l2jmobius.gameserver.model.siege.manor.Seed;
import org.l2jmobius.gameserver.model.siege.manor.SeedProduction;

/**
 * Decompile-confirmed money mechanics (RequestBuySeed/RequestProcureCropList/CastleManorManager.changeMode()):
 * buying a seed from a castle's Manor Manager is the ONLY manor action that credits the castle treasury -
 * Castle.addToTreasuryNoTax(price*count) is called for every unit actually sold. Selling crops back never
 * touches the treasury at all (reward items are minted to the seller; the 5% cash-out fee is a pure sink).
 * So this task only ever makes bots buy seeds, never sow/harvest/sell - the latter would add real
 * complexity for zero treasury benefit.
 * Only Player-typed bots (phantoms/hunters/recruits/buddies/regulars, via PhantomManager) can participate -
 * confirmed Npc-based fake players have no inventory/adena at all (asPlayer() returns null for them), same
 * gotcha PhantomFullBuffTask/PhantomVisualSyncTask already document.
 * CastleManorManager.updateCurrentProduction() looked like a way to inject a live seed offer directly, but
 * decompiling it (javap -p -c) showed it is actually just a DB persistence helper - it only UPDATEs rows
 * that already exist for a castle/seed pair, it never touches the in-memory _production map and can't
 * create a brand new offer. On a fresh install castle_manor_production has zero rows for any castle, so
 * there is no seed offer to buy from until one is created - the only way that normally happens is through
 * the real lord's client submission (RequestSetSeed -> setNextSeedProduction()) followed by the
 * MODIFIABLE -> MAINTENANCE -> APPROVED period transition in changeMode(), which is deliberately NOT used
 * here: that transition pre-debits the treasury by the full theoretical cost of whatever is configured
 * (getManorCost()) and only refunds the unsold portion later - a real risk of (temporarily, or if the
 * timing assumptions here are ever wrong, longer) draining the vault for no reason, when the whole point of
 * this task is to only ever add money to it.
 * Instead, this task reflectively writes directly into CastleManorManager's private final _production map
 * (the same map getSeedProduction()/getSeedProduct() read from, and the same map CastleManorManager's own
 * public storeMe() persists to castle_manor_production) - same class of reflection already used elsewhere
 * in this datapack (SubclassUnlock.java on VillageMaster.subclassSetMap, HomeBoard.java on MultisellData's
 * private _entries) for private static/instance fields with no public setter. This never touches
 * _productionNext, so changeMode()'s upfront debit logic never sees anything to charge for.
 * @author Living World
 */
public class ManorBotBuyerTask
{
	private static final Logger LOGGER = Logger.getLogger(ManorBotBuyerTask.class.getName());
	private static final long SWEEP_INTERVAL = 60000;
	private static final int CASTLE_ID = 5; // Aden
	private static final double BATCH_FRACTION = 0.1; // ~10 sweeps to exhaust one seed's full stock

	private static final Random RANDOM = new Random();

	// Drained by custom.CastleVault.CastleTaxTracker to tell apart this task's own income from real castle
	// tax when it reconstructs tax income from treasury deltas (the closed engine doesn't expose that split
	// itself - see CastleTaxTracker's own javadoc).
	private static final AtomicLong INCOME_SINCE_LAST_CHECK = new AtomicLong();

	private final Field productionField;

	private ManorBotBuyerTask()
	{
		Field field = null;
		try
		{
			field = CastleManorManager.class.getDeclaredField("_production");
			field.setAccessible(true);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "ManorBotBuyerTask: could not access CastleManorManager._production reflectively; disabling.", e);
		}
		productionField = field;

		if (productionField != null)
		{
			ThreadPool.scheduleAtFixedRate(this::sweep, SWEEP_INTERVAL, SWEEP_INTERVAL);
			LOGGER.info("ManorBotBuyerTask: started, sweeping every " + SWEEP_INTERVAL + "ms.");
		}
	}

	private void sweep()
	{
		try
		{
			final CastleManorManager manorManager = CastleManorManager.getInstance();
			List<SeedProduction> production = manorManager.getSeedProduction(CASTLE_ID, false);
			if ((production == null) || production.isEmpty() || production.stream().allMatch(seedProduction -> seedProduction.getAmount() <= 0))
			{
				production = refillProduction(manorManager);
				if (production == null)
				{
					return;
				}
			}

			final List<Player> bots = getOnlineBots();
			if (bots.isEmpty())
			{
				return;
			}

			final Castle castle = CastleManager.getInstance().getCastleById(CASTLE_ID);
			if (castle == null)
			{
				return;
			}

			for (SeedProduction seedProduction : production)
			{
				final int available = seedProduction.getAmount();
				if (available <= 0)
				{
					continue;
				}

				final int batch = Math.max(1, Math.min(available, (int) Math.ceil(seedProduction.getStartAmount() * BATCH_FRACTION)));
				if (!seedProduction.decreaseAmount(batch))
				{
					continue;
				}

				buySeed(bots.get(RANDOM.nextInt(bots.size())), castle, seedProduction, batch);
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "ManorBotBuyerTask: error while sweeping.", e);
		}
	}

	/**
	 * Mints the exact purchase cost onto the chosen bot and immediately spends it, mirroring the real
	 * RequestBuySeed money movement on the buyer's side (a plain reduceAdena would otherwise almost always
	 * fail, since these bots don't naturally carry adena) - no seed item is granted, since there is no
	 * sow/harvest/sell step in this task to ever consume it.
	 */
	private void buySeed(Player buyer, Castle castle, SeedProduction seedProduction, int batch)
	{
		final long cost = (long) seedProduction.getPrice() * batch;
		buyer.addAdena(ItemProcessType.BUY, (int) cost, null, false);
		if (!buyer.reduceAdena(ItemProcessType.BUY, (int) cost, null, false))
		{
			return;
		}

		castle.addToTreasuryNoTax(cost);
		INCOME_SINCE_LAST_CHECK.addAndGet(cost);
		LOGGER.info("ManorBotBuyerTask: " + buyer.getName() + " bought " + batch + "x seed " + seedProduction.getId() + " for " + cost + " adena - credited to Aden treasury.");
	}

	/**
	 * @return the total this task has credited to Aden's treasury since the last call, then resets the
	 * counter to zero. Called by custom.CastleVault.CastleTaxTracker only - not intended for general use.
	 */
	public static long drainIncomeSinceLastCheck()
	{
		return INCOME_SINCE_LAST_CHECK.getAndSet(0);
	}

	@SuppressWarnings("unchecked")
	private List<SeedProduction> refillProduction(CastleManorManager manorManager)
	{
		try
		{
			final List<SeedProduction> fresh = new ArrayList<>();
			for (Seed seed : manorManager.getSeedsForCastle(CASTLE_ID))
			{
				final int limit = seed.getSeedLimit();
				if (limit <= 0)
				{
					continue;
				}

				fresh.add(new SeedProduction(seed.getSeedId(), limit, seed.getSeedMaxPrice(), limit));
			}

			if (fresh.isEmpty())
			{
				return null;
			}

			final Map<Integer, List<SeedProduction>> production = (Map<Integer, List<SeedProduction>>) productionField.get(manorManager);
			production.put(CASTLE_ID, fresh);
			manorManager.storeMe();

			LOGGER.info("ManorBotBuyerTask: refilled Aden's manor seed offer (" + fresh.size() + " seed types) at max price/quantity.");
			return fresh;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "ManorBotBuyerTask: failed to refill Aden's manor seed offer.", e);
			return null;
		}
	}

	private List<Player> getOnlineBots()
	{
		final PhantomManager phantomManager = PhantomManager.getInstance();
		final List<Player> bots = new ArrayList<>();
		for (Player player : World.getInstance().getPlayers())
		{
			if (!player.isDead() && isBotControlled(phantomManager, player))
			{
				bots.add(player);
			}
		}
		return bots;
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

	public static void main(String[] args)
	{
		new ManorBotBuyerTask();
	}
}
