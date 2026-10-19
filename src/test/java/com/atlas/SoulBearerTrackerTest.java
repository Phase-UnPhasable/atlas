package com.atlas;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.Item;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import static org.junit.Assert.*;

public class SoulBearerTrackerTest
{
    private static final int HEAD = ItemID.ARCEUUS_CORPSE_ABYSSAL;
    private static final int INITIAL = ItemID.ARCEUUS_CORPSE_ABYSSAL_INITIAL;
    private static final Item BEARER = new Item(ItemID.ARCEUUS_SOULBEARER, 1);
    private static final Item[] EMPTY = new Item[0];
    private static final Item[] CARRIED = {BEARER};

    private HoldingsTracker holdings()
    { return new HoldingsTracker(SoulBearerTracker::canonicalHead, id -> false); }

    private Wealth.Snapshot snapshot(HoldingsTracker bank, Item[] inventory, long time)
    {
        Map<Integer, Wealth.Entry> entries = new LinkedHashMap<>();
        bank.combine(inventory, EMPTY).forEach((id, q) -> entries.put(id,
            new Wealth.Entry(id, "Item", q[0], q[1], q[2],
                id == HEAD ? 3008 : id == ItemID.BLOODRUNE ? 300 : id == ItemID.SOULRUNE ? 443 : 0,
                0, true, true, id == BEARER.getId())));
        return new Wealth.Snapshot(time, bank.getBankTime(), entries);
    }

    private Map<Integer, Long> runeTransfer(long charges)
    {
        Map<Integer, Long> result = new LinkedHashMap<>();
        result.put(ItemID.BLOODRUNE, charges); result.put(ItemID.SOULRUNE, charges);
        return result;
    }

    @Test
    public void pickupThenSendPreservesHeadAndSubtractsOneChargeAtFixedPrices()
    {
        HoldingsTracker bank = holdings(); bank.observeBank(EMPTY, 100);
        SessionTracker session = new SessionTracker(100);
        session.observe(snapshot(bank, CARRIED, 100), true);
        Item[] head = {BEARER, new Item(INITIAL, 1)};
        session.observe(snapshot(bank, head, 110), true);
        assertEquals(3008, session.current().change);
        SoulBearerTracker bearer = new SoulBearerTracker();
        bearer.menu("Fill", BEARER.getId(), head, null, 10);
        assertEquals(0, bearer.finish(head, null, 11).spent());
        SoulBearerTracker.Result sent = bearer.finish(CARRIED, null, 12);
        assertEquals(1, sent.spent());
        bank.creditBank(sent.heads);
        session.spend(ItemID.BLOODRUNE, sent.spent(), 300);
        session.spend(ItemID.SOULRUNE, sent.spent(), 443);
        session.observe(snapshot(bank, CARRIED, 120), true);
        assertEquals(2265, session.current().change);
        assertEquals(3008, snapshot(bank, CARRIED, 120).bank());
        assertEquals(100, bank.getBankTime()); // Remote transfer is not a fresh whole-bank observation.
        assertEquals(0, bearer.finish(CARRIED, null, 13).spent());
        bank.observeBank(new Item[]{new Item(HEAD, 1)}, 130);
        session.observe(snapshot(bank, CARRIED, 130), true);
        assertEquals(2265, session.current().change);
        // A later price movement must not reprice the charge already booked this session.
        session.spend(ItemID.BLOODRUNE, 1, 1000); session.spend(ItemID.SOULRUNE, 1, 2000);
        assertEquals(1522, session.current().change);
    }

    @Test
    public void bulkSendCostsOneChargeForEachActuallyRemovedHead()
    {
        SoulBearerTracker bearer = new SoulBearerTracker();
        Item[] before = {BEARER, new Item(INITIAL, 2), new Item(ItemID.ARCEUUS_CORPSE_GOBLIN_INITIAL, 1)};
        bearer.menu("Fill", BEARER.getId(), before, null, 1);
        // Only enough charges for two heads: the remaining head must not be charged.
        SoulBearerTracker.Result result = bearer.finish(new Item[]{BEARER, new Item(INITIAL, 1)}, null, 2);
        assertEquals(2, result.spent());
        assertEquals(1L, (long) result.heads.get(HEAD));
        assertEquals(1L, (long) result.heads.get(ItemID.ARCEUUS_CORPSE_GOBLIN));
    }

    @Test
    public void itemUseWorksInEitherDirectionAndScopesTheSelectedHead()
    {
        for (boolean reverse : new boolean[]{false, true})
        {
            SoulBearerTracker bearer = new SoulBearerTracker();
            Item[] before = {BEARER, new Item(INITIAL, 1), new Item(ItemID.ARCEUUS_CORPSE_GOBLIN, 1)};
            bearer.use(reverse ? INITIAL : BEARER.getId(), reverse ? BEARER.getId() : INITIAL, before, null, 1);
            SoulBearerTracker.Result result = bearer.finish(CARRIED, null, 2);
            assertEquals(Collections.singletonMap(HEAD, 1L), result.heads);
        }
    }

    @Test
    public void failedCancelledExpiredAndOrdinaryBankDepositsBookNothing()
    {
        SoulBearerTracker bearer = new SoulBearerTracker();
        Item[] head = {BEARER, new Item(INITIAL, 1)};
        bearer.menu("Fill", BEARER.getId(), head, null, 1);
        assertEquals(0, bearer.finish(head, null, 2).spent()); // No charges/full bank/cancelled dialogue.
        bearer.menu("Drop", INITIAL, head, null, 3);
        assertEquals(0, bearer.finish(CARRIED, null, 4).spent());
        bearer.menu("Fill", BEARER.getId(), head, null, 5);
        assertEquals(0, bearer.finish(CARRIED, null, 106).spent());
        bearer.menu("Fill", BEARER.getId(), head, null, 110);
        assertEquals(0, bearer.finish(CARRIED, new Item[]{new Item(HEAD, 1)}, 111).spent());
        bearer.menu("Fill", BEARER.getId(), head, null, 120);
        assertEquals(0, bearer.finish(EMPTY, null, 121).spent()); // Lost bearer/death is not a send.
        bearer.menu("Fill", BEARER.getId(), head, null, 130);
        bearer.cancel(); // Logout/world hop.
        assertEquals(0, bearer.finish(CARRIED, null, 131).spent());
    }

    @Test
    public void initialAndBankableHeadFormsRemainTheSameHolding()
    {
        assertTrue(SoulBearerTracker.isHead(ItemID.ARCEUUS_CORPSE_HELLHOUND_INITIAL));
        assertTrue(SoulBearerTracker.isHead(ItemID.ARCEUUS_CORPSE_DRAGON));
        assertFalse(SoulBearerTracker.isHead(ItemID.BLOODRUNE));
        SoulBearerTracker bearer = new SoulBearerTracker();
        bearer.menu("Fill", BEARER.getId(), new Item[]{BEARER, new Item(INITIAL, 1)}, null, 1);
        // Conversion of an initial head in inventory alone is not a successful send.
        assertEquals(0, bearer.finish(new Item[]{BEARER, new Item(HEAD, 1)}, null, 2).spent());
    }

    @Test
    public void reanimationCancelsPendingSendAndDoesNotCreditTheConsumedHeadToBank()
    {
        SoulBearerTracker bearer = new SoulBearerTracker();
        Item[] head = {BEARER, new Item(INITIAL, 1)};
        bearer.menu("Fill", BEARER.getId(), head, null, 1);
        bearer.menu("Cast", -1, head, null, 2);
        assertEquals(0, bearer.finish(CARRIED, null, 3).spent());
    }

    @Test
    public void rechargingAndUnchargingAreTransfersRatherThanAdditionalSessionCosts()
    {
        HoldingsTracker bank = holdings(); bank.observeBank(EMPTY, 100);
        SoulBearerTracker bearer = new SoulBearerTracker();
        Item[] runes = {BEARER, new Item(ItemID.BLOODRUNE, 10), new Item(ItemID.SOULRUNE, 10)};
        SessionTracker session = new SessionTracker(100);
        session.observe(snapshot(bank, runes, 100), true);
        bearer.use(ItemID.BLOODRUNE, BEARER.getId(), runes, null, 1);
        SoulBearerTracker.Result loaded = bearer.finish(CARRIED, null, 5);
        assertEquals(10, loaded.chargeTransfer);
        session.observe(snapshot(bank, CARRIED, 110), true, false, runeTransfer(loaded.chargeTransfer));
        assertEquals(0, session.current().change);
        session.spend(ItemID.BLOODRUNE, 1, 300); session.spend(ItemID.SOULRUNE, 1, 443);
        session.observe(snapshot(bank, CARRIED, 120), true);
        assertEquals(-743, session.current().change);
        Item[] returned = {BEARER, new Item(ItemID.BLOODRUNE, 9), new Item(ItemID.SOULRUNE, 9)};
        bearer.menu("Uncharge", BEARER.getId(), CARRIED, null, 10);
        SoulBearerTracker.Result unloaded = bearer.finish(returned, null, 11);
        assertEquals(-9, unloaded.chargeTransfer);
        session.observe(snapshot(bank, returned, 130), true, false, runeTransfer(unloaded.chargeTransfer));
        assertEquals(-743, session.current().change);
    }

    @Test
    public void configuringBankChargesWaitsForBothRuneDeltasAndDoesNotCountWithdrawals()
    {
        SoulBearerTracker bearer = new SoulBearerTracker();
        Item[] bank = {BEARER, new Item(ItemID.BLOODRUNE, 10), new Item(ItemID.SOULRUNE, 10)};
        bearer.menu("Configure-Charges", BEARER.getId(), EMPTY, bank, 1);
        assertEquals(0, bearer.finish(new Item[]{new Item(ItemID.BLOODRUNE, 10)},
            new Item[]{BEARER, new Item(ItemID.SOULRUNE, 10)}, 2).chargeTransfer);
        assertEquals(0, bearer.finish(EMPTY,
            new Item[]{BEARER, new Item(ItemID.SOULRUNE, 10)}, 3).chargeTransfer);
        assertEquals(10, bearer.finish(EMPTY, new Item[]{BEARER}, 4).chargeTransfer);
        assertEquals(0, bearer.finish(EMPTY, new Item[]{BEARER}, 5).chargeTransfer);
    }

    @Test
    public void firstBankOrContainerReconciliationDoesNotTurnAChargeTransferIntoProfit()
    {
        HoldingsTracker bank = holdings();
        SessionTracker session = new SessionTracker(100);
        Item[] runes = {BEARER, new Item(ItemID.BLOODRUNE, 10), new Item(ItemID.SOULRUNE, 10)};
        session.observe(snapshot(bank, runes, 100), false);
        bank.observeBank(EMPTY, 110);
        session.spend(ItemID.BLOODRUNE, 1, 300); session.spend(ItemID.SOULRUNE, 1, 443);
        session.observe(snapshot(bank, CARRIED, 110), true, true, runeTransfer(10));
        assertEquals(-743, session.current().change);
    }

    @Test
    public void remoteBankCreditsSurviveRestartWithoutInventingAFullBankOrDuplicatingOnRefresh()
    {
        HoldingsTracker bank = holdings();
        bank.creditBank(Collections.singletonMap(HEAD, 1L));
        assertFalse(bank.hasBank()); assertNull(bank.bankSnapshot());
        assertEquals(3008, snapshot(bank, CARRIED, 100).total());
        Map<String, Map<Integer, Long>> extra = new LinkedHashMap<>();
        extra.put(SoulBearerTracker.REMOTE_BANK_KEY, bank.remoteBankSnapshot());
        Wealth.State state = Wealth.State.empty("fixture").withExtraContainers(extra);
        Wealth.State loaded = WealthStore.decode("fixture", WealthStore.encode(state));
        HoldingsTracker restarted = holdings();
        restarted.restoreRemoteBank(loaded.extraContainers.get(SoulBearerTracker.REMOTE_BANK_KEY));
        assertEquals(3008, snapshot(restarted, CARRIED, 110).total());
        restarted.observeBank(new Item[]{new Item(HEAD, 1)}, 120);
        assertTrue(restarted.hasBank()); assertTrue(restarted.remoteBankSnapshot().isEmpty());
        assertEquals(3008, snapshot(restarted, CARRIED, 120).total());
        restarted.restoreRemoteBank(Collections.singletonMap(HEAD, 1L));
        assertEquals(3008, snapshot(restarted, CARRIED, 130).total());
    }
}
