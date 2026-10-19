package com.atlas;

import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class WealthTest
{
    private static Wealth.Entry item(int id, long bank, long inv, long equip, long price)
    {
        return new Wealth.Entry(id, "Item " + id, bank, inv, equip, price, price / 2, true, true, price == 0);
    }

    private static Wealth.Snapshot snapshot(long time, Wealth.Entry... entries)
    {
        Map<Integer, Wealth.Entry> map = new LinkedHashMap<>();
        for (Wealth.Entry entry : entries) { map.put(entry.id, entry); }
        return new Wealth.Snapshot(time, time, map);
    }

    @Test
    public void movingExpensiveGearBetweenBankInventoryAndEquipmentDoesNotChangeWealth()
    {
        Wealth.Snapshot bank = snapshot(1, item(1, 1, 0, 0, 300_000_000));
        Wealth.Snapshot inv = snapshot(2, item(1, 0, 1, 0, 300_000_000));
        Wealth.Snapshot worn = snapshot(3, item(1, 0, 0, 1, 300_000_000));
        Map<Integer, Long> quotes = Collections.singletonMap(1, 300_000_000L);
        Wealth.State state = Wealth.State.empty("test").append(bank, quotes).append(inv, quotes).append(worn, quotes);
        for (Wealth.Point point : state.history)
        {
            assertEquals(300_000_000L, point.total);
            assertEquals(0L, point.market);
            assertEquals(0L, point.items);
        }
    }

    @Test
    public void separatesPriceChangeFromAcquiringMoreItems()
    {
        Wealth.Snapshot first = snapshot(1, item(1, 10, 0, 0, 100));
        Wealth.Snapshot next = snapshot(2, item(1, 12, 0, 0, 120));
        Wealth.Point delta = Wealth.point(first, next, null, Collections.singletonMap(1, 120L));
        assertEquals(1440L, delta.total);
        assertEquals(200L, delta.market);
        assertEquals(240L, delta.items);
        assertEquals(next.total() - first.total(), delta.market + delta.items);
    }

    @Test
    public void saleUsesCurrentQuoteForItemNoLongerOwned()
    {
        Wealth.Snapshot first = snapshot(1, item(1, 1, 0, 0, 100));
        Wealth.Snapshot next = snapshot(2, item(2, 120, 0, 0, 1));
        Map<Integer, Long> quotes = new LinkedHashMap<>();
        quotes.put(1, 120L); quotes.put(2, 1L);
        Wealth.Point delta = Wealth.point(first, next, null, quotes);
        assertEquals(20L, delta.market);
        assertEquals(0L, delta.items);
    }

    @Test
    public void consumingOrLosingItemsIsNotMislabelledAsMarketLoss()
    {
        Wealth.Snapshot first = snapshot(1, item(1, 10, 0, 0, 100));
        Wealth.Snapshot next = snapshot(2, item(1, 7, 0, 0, 100));
        Wealth.Point delta = Wealth.point(first, next, null, Collections.singletonMap(1, 100L));
        assertEquals(0L, delta.market);
        assertEquals(-300L, delta.items);
    }

    @Test
    public void usesLongArithmeticForStacksAndNetWorth()
    {
        Wealth.Snapshot first = snapshot(1, item(1, 2_000_000_000L, 1_000_000_000L, 0, 1000));
        assertEquals(3_000_000_000_000L, first.total());
        Wealth.State state = Wealth.State.empty("test").append(first, Collections.singletonMap(1, 1000L));
        Wealth.State loaded = WealthStore.decode("test", WealthStore.encode(state));
        assertEquals(first.total(), loaded.latest.total());
        assertEquals(3_000_000_000L, loaded.latest.items.get(1).quantity());
    }

    @Test
    public void accountHistoryRoundTripRetainsAttributionAndLocationBreakdown()
    {
        Wealth.State state = Wealth.State.empty("account-a")
            .append(snapshot(1, item(1, 2, 1, 1, 100)), Collections.singletonMap(1, 100L))
            .append(snapshot(2, item(1, 1, 0, 3, 80)), Collections.singletonMap(1, 80L));
        Wealth.State loaded = WealthStore.decode("account-a", WealthStore.encode(state));
        assertEquals(state.revision, loaded.revision);
        assertEquals(2, loaded.history.size());
        assertEquals(80L, loaded.latest.bank());
        assertEquals(240L, loaded.latest.equipment());
        assertEquals(-80L, loaded.history.get(1).market);
        assertEquals(0L, loaded.history.get(1).items);
        assertNull(Wealth.State.empty("account-b").latest);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownHistorySchemaInsteadOfResettingAndOverwritingIt()
    {
        JsonObject object = new JsonObject(); object.addProperty("schema", 900);
        WealthStore.decode("test", object);
    }

    @Test
    public void missingPriceUsesPreviousQuoteForAttribution()
    {
        Wealth.Snapshot first = snapshot(1, item(1, 1, 0, 0, 500));
        Wealth.Snapshot next = snapshot(2, item(1, 1, 0, 0, 500));
        Wealth.Point delta = Wealth.point(first, next, null, Collections.emptyMap());
        assertEquals(0L, delta.market);
        assertEquals(0L, delta.items);
    }

    @Test
    public void newerBankCheckpointSurvivesRestartBeforeNextGraphSnapshot()
    {
        Wealth.State state = Wealth.State.empty("test").append(
            snapshot(100, item(1, 100, 0, 0, 1), item(2, 1, 0, 0, 104_000_000)),
            Collections.singletonMap(2, 104_000_000L));
        // Withdraw bow, then stop before the next graph snapshot. Only the bank checkpoint changes.
        state = state.withBank(new Wealth.Bank(200, Collections.singletonMap(1, 100L)));
        Wealth.State loaded = WealthStore.decode("test", WealthStore.encode(state));
        assertEquals(1, loaded.history.size());
        assertEquals(100L, loaded.latest.time);
        assertEquals(200L, loaded.bank.time);
        assertFalse(loaded.bank.quantities.containsKey(2));
        HoldingsTracker tracker = new HoldingsTracker(id -> id, id -> false);
        tracker.restoreBank(loaded.bank);
        Map<Integer, long[]> counts = tracker.combine(new net.runelite.api.Item[0],
            new net.runelite.api.Item[]{new net.runelite.api.Item(2, 1)});
        assertArrayEquals(new long[]{0, 0, 1}, counts.get(2));
    }

    @Test
    public void oldHistoryFilesCanRestoreBankWithoutImportingOldCarriedGear()
    {
        Wealth.State state = Wealth.State.empty("test").append(
            snapshot(100, item(1, 50, 20, 5, 1)), Collections.singletonMap(1, 1L));
        JsonObject legacy = WealthStore.encode(state);
        legacy.remove("bankCheckpoint");
        Wealth.State loaded = WealthStore.decode("test", legacy);
        assertEquals(Long.valueOf(50), loaded.bank.quantities.get(1));
        assertEquals(100L, loaded.bank.time);
    }

    @Test
    public void bankCheckpointCanBeSavedBeforeAnyHistoryExists()
    {
        Wealth.State state = Wealth.State.empty("test").withBank(
            new Wealth.Bank(100, Collections.singletonMap(1, 50L)));
        Wealth.State loaded = WealthStore.decode("test", WealthStore.encode(state));
        assertNull(loaded.latest);
        assertTrue(loaded.history.isEmpty());
        assertEquals(Long.valueOf(50), loaded.bank.quantities.get(1));
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownBankIsNeverRecordedAsZeroInNetWorthHistory()
    {
        Wealth.Snapshot partial = new Wealth.Snapshot(100, 0,
            Collections.singletonMap(1, item(1, 0, 500, 0, 1)));
        Wealth.State.empty("test").append(partial, Collections.singletonMap(1, 1L));
    }

    @Test
    public void finalOlderGraphSampleDoesNotOverwriteNewerBankCheckpoint()
    {
        Wealth.State state = Wealth.State.empty("test").withBank(
            new Wealth.Bank(200, Collections.singletonMap(1, 50L)));
        state = state.append(snapshot(150, item(1, 100, 0, 0, 1)), Collections.singletonMap(1, 1L));
        assertEquals(Long.valueOf(50), state.bank.quantities.get(1));
        assertEquals(200L, state.bank.time);
    }
}
