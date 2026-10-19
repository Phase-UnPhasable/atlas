package com.atlas;

import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.Item;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;
import static org.junit.Assert.*;

public class RunePouchContentsTest
{
    private static final Item[] EMPTY = new Item[0];
    private static final int RUNE = ItemID.BLOODRUNE;

    private HoldingsTracker holdings()
    {
        HoldingsTracker tracker = new HoldingsTracker(id -> id, id -> false);
        tracker.observeBank(EMPTY, 100);
        return tracker;
    }

    private Wealth.Snapshot snapshot(HoldingsTracker tracker, long time, Item[] inventory, long pouchRunes)
    {
        Map<Integer, long[]> quantities = tracker.combine(inventory, EMPTY);
        Map<Integer, Long> bankPouch = RunePouchContents.include(quantities, Collections.singletonMap(RUNE, pouchRunes));
        Map<Integer, Wealth.Entry> entries = new LinkedHashMap<>();
        quantities.forEach((id, q) -> entries.put(id, new Wealth.Entry(id, "Item " + id, q[0], q[1], q[2],
            id == RUNE ? 149 : 0, 1, true, true, false, bankPouch.getOrDefault(id, 0L))));
        return new Wealth.Snapshot(time, tracker.getBankTime(), entries);
    }

    @Test
    public void movingOnePointFourNineMillionOfRunesIntoPouchIsNotALoss()
    {
        HoldingsTracker tracker = holdings();
        Item pouch = new Item(ItemID.BH_RUNE_POUCH, 1);
        Wealth.Snapshot loose = snapshot(tracker, 110, new Item[]{pouch, new Item(RUNE, 10_000)}, 0);
        Wealth.Snapshot inside = snapshot(tracker, 120, new Item[]{pouch}, 10_000);
        SessionTracker session = new SessionTracker(100);
        session.observe(loose, true);
        session.observe(inside, true);
        assertEquals(1_490_000L, inside.total());
        assertEquals(loose.total(), inside.total());
        assertEquals(0L, session.current().change);
        assertEquals(10_000L, inside.items.get(RUNE).inventory);
        session.observe(loose, true); // Emptying the pouch also preserves value.
        assertEquals(0L, session.current().change);
    }

    @Test
    public void consumingRunesWithoutChangingInventoryCountsAsASupplyLoss()
    {
        HoldingsTracker tracker = holdings();
        Item[] inventory = {new Item(ItemID.DIVINE_RUNE_POUCH, 1)};
        SessionTracker session = new SessionTracker(100);
        session.observe(snapshot(tracker, 110, inventory, 10_000), true);
        session.observe(snapshot(tracker, 120, inventory, 9990), true);
        assertEquals(-1490L, session.current().change);
    }

    @Test
    public void bankingPouchMovesItsContentsToBankWithoutChangingTotalOrSession()
    {
        HoldingsTracker tracker = holdings();
        Item pouch = new Item(ItemID.DIVINE_RUNE_POUCH_TROUVER, 1);
        Wealth.Snapshot carried = snapshot(tracker, 110, new Item[]{pouch}, 10_000);
        tracker.observeBank(new Item[]{pouch, new Item(RUNE, 500)}, 120);
        Wealth.Snapshot banked = snapshot(tracker, 120, EMPTY, 9500);
        SessionTracker session = new SessionTracker(100);
        session.observe(carried, true);
        session.observe(banked, true);
        assertEquals(carried.total(), banked.total());
        assertEquals(1_490_000L, banked.bank());
        assertEquals(0L, banked.inventory());
        assertEquals(0L, session.current().change);
        assertEquals(500L, (long) Wealth.Bank.from(banked).quantities.get(RUNE));
    }

    @Test
    public void reloadAndWithdrawBankedPouchNeverDuplicateItsRunes()
    {
        HoldingsTracker tracker = holdings();
        tracker.observeBank(new Item[]{new Item(ItemID.BH_RUNE_POUCH_TROUVER, 1), new Item(RUNE, 500)}, 120);
        Wealth.Snapshot banked = snapshot(tracker, 130, EMPTY, 9500);
        Wealth.State saved = Wealth.State.empty("test").append(banked, Collections.emptyMap());
        Wealth.State loaded = WealthStore.decode("test", WealthStore.encode(saved));
        assertEquals(9500L, loaded.latest.items.get(RUNE).bankPouch);
        HoldingsTracker restored = new HoldingsTracker(id -> id, id -> false);
        restored.restoreBank(loaded.bank);
        assertEquals(banked.total(), snapshot(restored, 140, EMPTY, 9500).total());
        restored.observeBank(new Item[]{new Item(RUNE, 500)}, 150);
        Wealth.Snapshot withdrawn = snapshot(restored, 150, new Item[]{new Item(ItemID.BH_RUNE_POUCH_TROUVER, 1)}, 9500);
        assertEquals(banked.total(), withdrawn.total());
        assertEquals(0L, withdrawn.items.get(RUNE).bankPouch);
    }

    @Test
    public void leftoverVarbitsDoNotCountRunesWithoutAnOwnedPouch()
    {
        assertEquals(0L, snapshot(holdings(), 120, EMPTY, 10_000).total());
    }

    @Test
    public void multiplePouchVariantsShareOneSetOfContentsWithCarriedPouchPreferred()
    {
        HoldingsTracker tracker = holdings();
        tracker.observeBank(new Item[]{new Item(ItemID.BH_RUNE_POUCH, 1)}, 100);
        Wealth.Snapshot snapshot = snapshot(tracker, 120, new Item[]{new Item(ItemID.DIVINE_RUNE_POUCH, 1)}, 10_000);
        assertEquals(1_490_000L, snapshot.total());
        assertEquals(1_490_000L, snapshot.inventory());
        assertEquals(0L, snapshot.bank());
    }

    @Test
    public void readsAllSixSlotsUsingRuneEnumAndMergesDuplicateTypes()
    {
        Map<Integer, Integer> varbits = new LinkedHashMap<>();
        varbits.put(VarbitID.RUNE_POUCH_TYPE_1, 7);
        varbits.put(VarbitID.RUNE_POUCH_QUANTITY_1, 400);
        varbits.put(VarbitID.RUNE_POUCH_TYPE_4, 7);
        varbits.put(VarbitID.RUNE_POUCH_QUANTITY_4, 600);
        varbits.put(VarbitID.RUNE_POUCH_TYPE_5, 8);
        varbits.put(VarbitID.RUNE_POUCH_QUANTITY_5, 100);
        varbits.put(VarbitID.RUNE_POUCH_TYPE_6, 9);
        varbits.put(VarbitID.RUNE_POUCH_QUANTITY_6, 200);
        Map<Integer, Long> runes = RunePouchContents.read(id -> varbits.getOrDefault(id, 0), type -> type + 1000);
        assertEquals(1000L, (long) runes.get(1007));
        assertEquals(100L, (long) runes.get(1008));
        assertEquals(200L, (long) runes.get(1009));
        assertEquals(3, runes.size());
        varbits.put(VarbitID.RUNE_POUCH_QUANTITY_1, 399);
        assertNotEquals(runes, RunePouchContents.read(id -> varbits.getOrDefault(id, 0), type -> type + 1000));
    }

    @Test
    public void ignoresEmptySlotsAndUnmappedRuneTypes()
    {
        assertTrue(RunePouchContents.read(id -> 0, type -> { fail("Empty slots must not look up runes"); return -1; }).isEmpty());
        assertTrue(RunePouchContents.read(id -> 1, type -> -1).isEmpty());
    }

    @Test
    public void legacySnapshotsWithoutPouchMetadataStillLoad()
    {
        HoldingsTracker tracker = holdings();
        tracker.observeBank(new Item[]{new Item(RUNE, 500)}, 100);
        Wealth.State state = Wealth.State.empty("test").append(snapshot(tracker, 120, EMPTY, 0), Collections.emptyMap());
        JsonObject json = WealthStore.encode(state);
        json.getAsJsonObject("snapshot").getAsJsonArray("entries").forEach(e -> e.getAsJsonObject().remove("bankPouch"));
        assertEquals(state.latest.total(), WealthStore.decode("test", json).latest.total());
    }
}
