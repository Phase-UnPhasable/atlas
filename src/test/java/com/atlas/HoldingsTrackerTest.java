package com.atlas;

import java.util.Map;
import net.runelite.api.Item;
import org.junit.Test;
import static org.junit.Assert.*;

public class HoldingsTrackerTest
{
    private static final Item[] EMPTY = new Item[0];

    private HoldingsTracker tracker()
    {
        return new HoldingsTracker(id -> id, id -> false);
    }

    @Test
    public void equippingBowAfterClosingBankUpdatesLocationsWithoutLosingBankOrWealth()
    {
        HoldingsTracker tracker = tracker();
        // The bank is synced AFTER withdrawing the bow, then its live container becomes unavailable.
        tracker.observeBank(new Item[]{new Item(1, 20_000_000)}, 100);
        Map<Integer, long[]> carried = tracker.combine(new Item[]{new Item(2, 1)}, EMPTY);
        // No further bank observation. Equip the bow while walking away from the bank.
        Map<Integer, long[]> equipped = tracker.combine(EMPTY, new Item[]{new Item(2, 1)});
        assertNotNull(equipped);
        assertArrayEquals(new long[]{20_000_000, 0, 0}, equipped.get(1));
        assertArrayEquals(new long[]{0, 1, 0}, carried.get(2));
        assertArrayEquals(new long[]{0, 0, 1}, equipped.get(2));
        assertEquals(total(carried), total(equipped));
        assertEquals(124_000_000L, total(equipped));
        assertEquals(100L, tracker.getBankTime());
    }

    private long total(Map<Integer, long[]> counts)
    {
        return counts.entrySet().stream().mapToLong(e ->
            (e.getValue()[0] + e.getValue()[1] + e.getValue()[2]) * (e.getKey() == 1 ? 1 : 104_000_000L)).sum();
    }

    @Test
    public void swappingWeaponsOutsideBankIncludesTheWeaponReturnedToInventory()
    {
        HoldingsTracker tracker = tracker();
        tracker.observeBank(EMPTY, 100);
        Map<Integer, long[]> before = tracker.combine(new Item[]{new Item(2, 1)}, new Item[]{new Item(3, 1)});
        Map<Integer, long[]> after = tracker.combine(new Item[]{new Item(3, 1)}, new Item[]{new Item(2, 1)});
        assertArrayEquals(new long[]{0, 1, 0}, before.get(2));
        assertArrayEquals(new long[]{0, 0, 1}, after.get(2));
        assertArrayEquals(new long[]{0, 1, 0}, after.get(3));
        assertEquals(total(before), total(after));
    }

    @Test
    public void inventoryLootAndConsumptionUpdateAgainstUnchangedCachedBank()
    {
        HoldingsTracker tracker = tracker();
        tracker.observeBank(new Item[]{new Item(1, 100)}, 100);
        assertArrayEquals(new long[]{100, 50, 0}, tracker.combine(new Item[]{new Item(1, 50)}, EMPTY).get(1));
        assertArrayEquals(new long[]{100, 25, 0}, tracker.combine(new Item[]{new Item(1, 25)}, EMPTY).get(1));
        assertArrayEquals(new long[]{100, 0, 0}, tracker.combine(EMPTY, EMPTY).get(1));
    }

    @Test
    public void cachesCopyOfObservedBankAndAcceptsRealEmptyBankOnNextVisit()
    {
        HoldingsTracker tracker = tracker();
        Item[] mutable = {new Item(1, 50)};
        tracker.observeBank(mutable, 100);
        mutable[0] = new Item(1, 0);
        assertEquals(50L, tracker.combine(EMPTY, EMPTY).get(1)[0]);
        tracker.observeBank(null, 101);
        assertEquals(50L, tracker.combine(EMPTY, EMPTY).get(1)[0]);
        tracker.observeBank(EMPTY, 102);
        assertTrue(tracker.combine(EMPTY, EMPTY).isEmpty());
        assertEquals(102L, tracker.getBankTime());
    }

    @Test
    public void missingCarriedDataIsNotZeroAndResetDoesNotReuseAnotherSessionsBank()
    {
        HoldingsTracker tracker = tracker();
        assertFalse(tracker.hasBank());
        assertTrue(tracker.combine(EMPTY, EMPTY).isEmpty());
        tracker.observeBank(new Item[]{new Item(1, 50)}, 100);
        assertNull(tracker.combine(null, EMPTY));
        assertNull(tracker.combine(EMPTY, null));
        tracker.reset();
        assertFalse(tracker.hasBank());
        assertTrue(tracker.combine(EMPTY, EMPTY).isEmpty());
        assertEquals(0L, tracker.getBankTime());
    }

    @Test
    public void notesPlaceholdersAndLargeMergedQuantitiesAreHandledInAllLocations()
    {
        HoldingsTracker tracker = new HoldingsTracker(id -> id == 2 ? 1 : id, id -> id == 3);
        tracker.observeBank(new Item[]{new Item(1, 2_000_000_000), new Item(3, 1)}, 100);
        Map<Integer, long[]> result = tracker.combine(new Item[]{new Item(2, 1_000_000_000), new Item(-1, 1)}, EMPTY);
        assertEquals(1, result.size());
        assertArrayEquals(new long[]{2_000_000_000L, 1_000_000_000L, 0}, result.get(1));
    }

    @Test
    public void loginRestoresOnlyBankAndImmediatelyUsesCurrentInventoryAndEquipment()
    {
        HoldingsTracker tracker = tracker();
        java.util.Map<Integer, Wealth.Entry> old = new java.util.LinkedHashMap<>();
        old.put(1, new Wealth.Entry(1, "Coins", 100, 25, 0, 1, 1, false, true, false));
        old.put(2, new Wealth.Entry(2, "Bow", 0, 1, 0, 104_000_000L, 1, true, true, false));
        Wealth.Snapshot previous = new Wealth.Snapshot(100, 90, old);
        tracker.restoreBank(Wealth.Bank.from(previous));
        Map<Integer, long[]> current = tracker.combine(new Item[]{new Item(1, 40)}, new Item[]{new Item(2, 1)});
        assertTrue(tracker.hasBank());
        assertArrayEquals(new long[]{100, 40, 0}, current.get(1));
        assertArrayEquals(new long[]{0, 0, 1}, current.get(2));
        assertEquals(104_000_140L, total(current));
        assertEquals(90L, tracker.getBankTime());
    }

    @Test
    public void firstEverLoginShowsCarriedHoldingsWhileBankRemainsUnknown()
    {
        HoldingsTracker tracker = tracker();
        tracker.restoreBank(null);
        Map<Integer, long[]> current = tracker.combine(new Item[]{new Item(1, 500)}, new Item[]{new Item(2, 1)});
        assertFalse(tracker.hasBank());
        assertEquals(0L, tracker.getBankTime());
        assertEquals(104_000_500L, total(current));
        assertArrayEquals(new long[]{0, 0, 1}, current.get(2));
    }

    @Test
    public void lateDiskLoadNeverReplacesBankAlreadyObservedThisSession()
    {
        HoldingsTracker tracker = tracker();
        tracker.observeBank(new Item[]{new Item(1, 100)}, 200);
        tracker.restoreBank(new Wealth.Bank(50, java.util.Collections.singletonMap(1, 500L)));
        assertEquals(100L, tracker.combine(EMPTY, EMPTY).get(1)[0]);
        assertEquals(200L, tracker.getBankTime());
    }
}
