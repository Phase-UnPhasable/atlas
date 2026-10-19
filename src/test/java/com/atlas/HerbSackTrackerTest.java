package com.atlas;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.Item;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import static org.junit.Assert.*;

public class HerbSackTrackerTest
{
    private static final int HERB = ItemID.UNIDENTIFIED_RANARR;
    private static final Item[] EMPTY = new Item[0];
    private static final Item SACK = new Item(ItemID.SLAYER_HERB_SACK_OPEN, 1);

    private HerbSackTracker checked(long quantity)
    {
        HerbSackTracker tracker = new HerbSackTracker();
        tracker.chat("You look in your herb sack and see:");
        if (quantity > 0) { tracker.chat(quantity + " x Grimy ranarr weed"); }
        tracker.finishCheck();
        tracker.takeReconciliation();
        return tracker;
    }

    private Wealth.Snapshot snapshot(HoldingsTracker holdings, HerbSackTracker sack, Item[] inventory, long time)
    {
        Map<Integer, long[]> quantities = holdings.combine(inventory, EMPTY);
        Map<Integer, Long> bankSack = sack.include(quantities);
        Map<Integer, Wealth.Entry> entries = new LinkedHashMap<>();
        quantities.forEach((id, q) -> entries.put(id, new Wealth.Entry(id, "Item " + id, q[0], q[1], q[2],
            id == HERB ? 7000 : 0, 1, true, true, false, 0, bankSack.getOrDefault(id, 0L))));
        return new Wealth.Snapshot(time, holdings.getBankTime(), entries);
    }

    @Test
    public void checkReplacesFullContentsTogetherWithoutTreatingDiscoveryAsLoot()
    {
        HerbSackTracker sack = checked(2);
        sack.chat("You look in your herb sack and see:");
        sack.chat("1 x Grimy guam leaf");
        sack.chat("5 x Grimy ranarr weed");
        assertEquals(2L, (long) sack.contents().get(HERB));
        assertTrue(sack.finishCheck());
        assertEquals(2, sack.contents().size());
        assertEquals(5L, (long) sack.contents().get(HERB));
        assertTrue(sack.takeReconciliation());
        assertFalse(sack.takeReconciliation());
        assertTrue(sack.confirmed());
    }

    @Test
    public void pickupImmediatelyAddsOneHerbAndSessionGain()
    {
        HerbSackTracker sack = checked(2);
        HoldingsTracker holdings = new HoldingsTracker(id -> id, id -> false);
        holdings.observeBank(EMPTY, 100);
        SessionTracker session = new SessionTracker(100);
        session.observe(snapshot(holdings, sack, new Item[]{SACK}, 110), true);
        assertTrue(sack.chat("You put the Grimy ranarr weed herb into your herb sack."));
        Wealth.Snapshot next = snapshot(holdings, sack, new Item[]{SACK}, 120);
        session.observe(next, true);
        assertEquals(21_000L, next.inventory());
        assertEquals(7000L, session.current().change);
    }

    @Test
    public void unknownStartingContentsTrackNewPickupsButNeedCheckToConfirm()
    {
        HerbSackTracker sack = new HerbSackTracker();
        assertNull(sack.contents());
        sack.chat("You put the Grimy ranarr weed herb into your herb sack.");
        assertEquals(1L, (long) sack.contents().get(HERB));
        assertFalse(sack.confirmed());
        sack.chat("You look in your herb sack and see:");
        sack.chat("10 x Grimy ranarr weed");
        sack.finishCheck();
        assertEquals(10L, (long) sack.contents().get(HERB));
        assertTrue(sack.takeReconciliation());
    }

    @Test
    public void checkMessagesOutsideBatchAndOtherPluginPriceOutputDoNotAddHerbs()
    {
        HerbSackTracker sack = checked(2);
        assertFalse(sack.chat("99 x Grimy ranarr weed"));
        assertFalse(sack.chat("Total value of herbs in sack: 14,000"));
        assertFalse(sack.chat("You put the Grimy mystery herb into your herb sack."));
        assertEquals(2L, (long) sack.contents().get(HERB));
    }

    @Test
    public void emptyCheckResetsOldContentsAndSilklinedVariantIsRecognized()
    {
        HerbSackTracker sack = checked(2);
        sack.chat("The herb sack is empty.");
        sack.finishCheck();
        assertTrue(sack.contents().isEmpty());
        sack.chat("You look in your silklined herb sack and see:");
        sack.chat("1 x Grimy huasca");
        sack.finishCheck();
        assertEquals(1L, (long) sack.contents().get(ItemID.UNIDENTIFIED_HUASCA));
        assertTrue(sack.chat("You put the Grimy huasca herb into your silklined herb sack."));
        assertEquals(2L, (long) sack.contents().get(ItemID.UNIDENTIFIED_HUASCA));
    }

    @Test
    public void fillCountsCompletedInventoryDifferenceAndNeverCountsClickAsSuccess()
    {
        HerbSackTracker sack = checked(2);
        Item[] before = {SACK, new Item(HERB, 5)};
        sack.beginTransfer("Fill", before, 10);
        assertFalse(sack.finishTransfer(before, 11));
        assertEquals(2L, (long) sack.contents().get(HERB));
        assertFalse(sack.chat("You put the Grimy ranarr weed herb into your herb sack."));
        assertTrue(sack.finishTransfer(new Item[]{SACK, new Item(HERB, 2)}, 12));
        assertEquals(5L, (long) sack.contents().get(HERB));
    }

    @Test
    public void fillingAndEmptyingInventoryAreNeutralTransfersForSessionAndTotal()
    {
        HerbSackTracker sack = checked(2);
        HoldingsTracker holdings = new HoldingsTracker(id -> id, id -> false);
        holdings.observeBank(EMPTY, 100);
        Item[] loose = {SACK, new Item(HERB, 5)};
        SessionTracker session = new SessionTracker(100);
        Wealth.Snapshot before = snapshot(holdings, sack, loose, 110);
        session.observe(before, true);
        sack.beginTransfer("Fill", loose, 10);
        sack.finishTransfer(new Item[]{SACK}, 12);
        Wealth.Snapshot filled = snapshot(holdings, sack, new Item[]{SACK}, 120);
        session.observe(filled, true);
        assertEquals(before.total(), filled.total());
        assertEquals(0L, session.current().change);
        sack.beginTransfer("Empty", new Item[]{SACK}, 13);
        Item[] emptied = {SACK, new Item(HERB, 7)};
        sack.finishTransfer(emptied, 15);
        Wealth.Snapshot after = snapshot(holdings, sack, emptied, 130);
        session.observe(after, true);
        assertEquals(before.total(), after.total());
        assertEquals(0L, session.current().change);
    }

    @Test
    public void partialEmptySubtractsOnlyHerbsActuallyMovedToInventory()
    {
        HerbSackTracker sack = checked(10);
        sack.beginTransfer("Empty", new Item[]{SACK, new Item(HERB, 2)}, 10);
        assertTrue(sack.finishTransfer(new Item[]{SACK, new Item(HERB, 5)}, 12));
        assertEquals(7L, (long) sack.contents().get(HERB));
        assertFalse(sack.finishTransfer(new Item[]{SACK, new Item(HERB, 5)}, 13));
    }

    @Test
    public void failedOrExpiredTransferDoesNotChangeContents()
    {
        HerbSackTracker sack = checked(10);
        sack.beginTransfer("Empty", new Item[]{SACK}, 10);
        assertFalse(sack.finishTransfer(new Item[]{SACK}, 12));
        assertFalse(sack.finishTransfer(new Item[]{SACK, new Item(HERB, 10)}, 19));
        assertEquals(10L, (long) sack.contents().get(HERB));
    }

    @Test
    public void emptyingIntoBankDoesNotCreateProfitOrDuplicateOnRestart()
    {
        HerbSackTracker sack = checked(10);
        HoldingsTracker holdings = new HoldingsTracker(id -> id, id -> false);
        holdings.observeBank(new Item[]{new Item(HERB, 5)}, 100);
        Wealth.Snapshot before = snapshot(holdings, sack, new Item[]{SACK}, 110);
        sack.beginTransfer("Empty to bank", new Item[]{new Item(HERB, 5)}, 10);
        assertTrue(sack.transferringToBank());
        Item[] bankAfter = {new Item(HERB, 15)};
        holdings.observeBank(bankAfter, 120);
        assertTrue(sack.finishTransfer(bankAfter, 12));
        Wealth.Snapshot after = snapshot(holdings, sack, new Item[]{SACK}, 120);
        assertEquals(before.total(), after.total());
        SessionTracker session = new SessionTracker(100);
        session.observe(before, true);
        session.observe(after, true);
        assertEquals(0L, session.current().change);
        Wealth.State state = Wealth.State.empty("test").withHerbSack(sack.contents()).append(after, Collections.emptyMap());
        Wealth.State loaded = WealthStore.decode("test", WealthStore.encode(state));
        assertTrue(loaded.herbSack.isEmpty());
        assertEquals(15L, (long) loaded.bank.quantities.get(HERB));
    }

    @Test
    public void pickupThenContextualEmptyOrDepositAtBankBooksLootOnlyOnce()
    {
        for (String option : new String[]{"Empty", "Empty to bank", "Deposit", "Deposit-All"})
        {
            HerbSackTracker sack = checked(0);
            HoldingsTracker holdings = new HoldingsTracker(id -> id, id -> false);
            holdings.observeBank(new Item[]{new Item(HERB, 5)}, 100);
            Item[] inventory = {SACK};
            SessionTracker session = new SessionTracker(100);
            session.observe(snapshot(holdings, sack, inventory, 110), true);
            sack.chat("You put the Grimy ranarr weed herb into your herb sack.");
            Wealth.Snapshot pickup = snapshot(holdings, sack, inventory, 120);
            session.observe(pickup, true);
            assertEquals(7000L, session.current().change);
            sack.beginTransfer(option, inventory, new Item[]{new Item(HERB, 5)}, 10);
            Item[] bank = {new Item(HERB, 6)};
            holdings.observeBank(bank, 130);
            assertTrue(option, sack.finishTransfer(inventory, bank, 12));
            Wealth.Snapshot deposited = snapshot(holdings, sack, inventory, 130);
            session.observe(deposited, true);
            assertEquals(option, pickup.total(), deposited.total());
            assertEquals(option, 7000L, session.current().change);
            assertTrue(sack.contents().isEmpty());
            assertFalse(sack.finishTransfer(inventory, bank, 13));
        }
    }

    @Test
    public void depositingLooseHerbsWhileCarryingSackDoesNotSubtractSackContents()
    {
        HerbSackTracker sack = checked(10);
        sack.beginTransfer("Deposit-All", new Item[]{SACK, new Item(HERB, 3)}, new Item[]{new Item(HERB, 5)}, 10);
        assertFalse(sack.finishTransfer(new Item[]{SACK}, new Item[]{new Item(HERB, 8)}, 12));
        assertEquals(10L, (long) sack.contents().get(HERB));
    }

    @Test
    public void filledSackInBankPersistsSeparateFromLooseBankHerbs()
    {
        HerbSackTracker sack = checked(10);
        HoldingsTracker holdings = new HoldingsTracker(id -> id, id -> false);
        holdings.observeBank(new Item[]{SACK, new Item(HERB, 5)}, 100);
        Wealth.Snapshot snapshot = snapshot(holdings, sack, EMPTY, 110);
        assertEquals(105_000L, snapshot.bank());
        assertEquals(10L, snapshot.items.get(HERB).bankSack);
        Wealth.State saved = Wealth.State.empty("test").withHerbSack(sack.contents()).append(snapshot, Collections.emptyMap())
            .withActiveSession(new Wealth.Session(100, 110, 0, false, false)).closeSession(false);
        Wealth.State loaded = WealthStore.decode("test", WealthStore.encode(saved));
        HerbSackTracker restored = new HerbSackTracker();
        restored.restore(loaded.herbSack);
        assertFalse(restored.confirmed()); // A new login may have missed changes in another client.
        HoldingsTracker restoredHoldings = new HoldingsTracker(id -> id, id -> false);
        restoredHoldings.restoreBank(loaded.bank);
        assertEquals(5L, (long) loaded.bank.quantities.get(HERB));
        assertEquals(snapshot.total(), snapshot(restoredHoldings, restored, EMPTY, 120).total());
        restoredHoldings.observeBank(new Item[]{new Item(HERB, 5)}, 130);
        assertEquals(snapshot.total(), snapshot(restoredHoldings, restored, new Item[]{SACK}, 130).total());
    }

    @Test
    public void firstCheckRebasesOnlyCorrectionAndPreservesPreviouslyMeasuredLoot()
    {
        HerbSackTracker sack = checked(2);
        HoldingsTracker holdings = new HoldingsTracker(id -> id, id -> false);
        holdings.observeBank(EMPTY, 100);
        SessionTracker session = new SessionTracker(100);
        session.observe(snapshot(holdings, sack, new Item[]{SACK}, 110), true);
        sack.chat("You put the Grimy ranarr weed herb into your herb sack.");
        session.observe(snapshot(holdings, sack, new Item[]{SACK}, 120), true);
        sack.chat("You look in your herb sack and see:");
        sack.chat("10 x Grimy ranarr weed");
        sack.finishCheck();
        session.observe(snapshot(holdings, sack, new Item[]{SACK}, 130), true, sack.takeReconciliation());
        assertEquals(7000L, session.current().change);
    }

    @Test
    public void cachedContentsWithoutOwnedSackAreExcludedAndAllVariantsSupported()
    {
        HerbSackTracker sack = checked(2);
        Map<Integer, long[]> holdings = new LinkedHashMap<>();
        assertTrue(sack.include(holdings).isEmpty());
        assertTrue(holdings.isEmpty());
        assertTrue(HerbSackTracker.isSack(ItemID.SLAYER_HERB_SACK));
        assertTrue(HerbSackTracker.isSack(ItemID.SLAYER_HERB_SACK_OPEN));
        assertTrue(HerbSackTracker.isSack(ItemID.SLAYER_HERB_SACK_SILK));
        assertTrue(HerbSackTracker.isSack(ItemID.SLAYER_HERB_SACK_SILK_OPEN));
        sack.invalidate();
        assertFalse(sack.confirmed());
    }
}
