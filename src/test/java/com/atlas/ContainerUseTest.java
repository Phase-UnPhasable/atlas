package com.atlas;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.Item;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.InterfaceID;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContainerUseTest
{
    private static final Item[] EMPTY = new Item[0];

    @Test
    public void selectedInventorySlotFallbackDoesNotMistakeASpellForAnInventoryItem()
    {
        Item[] inventory = {new Item(ItemID.SLAYER_HERB_SACK, 1)};
        assertEquals(ItemID.SLAYER_HERB_SACK,
            ContainerUse.inventoryItem(InterfaceID.Inventory.ITEMS, -1, 0, inventory));
        assertEquals(ItemID.UNIDENTIFIED_TORSTOL,
            ContainerUse.inventoryItem(InterfaceID.Inventory.ITEMS, ItemID.UNIDENTIFIED_TORSTOL, -1, inventory));
        assertEquals(-1, ContainerUse.inventoryItem(InterfaceID.Bankmain.DEPOSITINV, -1, 0, inventory));
        assertEquals(-1, ContainerUse.inventoryItem(InterfaceID.Inventory.ITEMS, -1, -1, inventory));
    }

    @Test
    public void torstolOnSackPreservesNetWorthAndSessionInsteadOfLosing3671Gp()
    {
        for (boolean reverse : new boolean[]{false, true})
        {
            int herb = ItemID.UNIDENTIFIED_TORSTOL;
            int sackId = ItemID.SLAYER_HERB_SACK_OPEN;
            int selected = reverse ? sackId : herb;
            int target = reverse ? herb : sackId;
            HerbSackTracker sack = new HerbSackTracker();
            sack.restore(Collections.emptyMap());
            Item[] before = {new Item(herb, 1), new Item(sackId, 1)};
            Item[] after = {new Item(-1, 0), before[1]};
            HoldingsTracker holdings = new HoldingsTracker(id -> id, id -> false);
            holdings.observeBank(EMPTY, 100);
            Wealth.Snapshot start = snapshot(holdings.combine(before, EMPTY), 110, herb, 3671);
            SessionTracker session = new SessionTracker(100);
            session.observe(start, true);
            assertEquals(sackId, ContainerUse.container(selected, target));
            sack.beginItemUse(ContainerUse.storedItem(selected, target), before, null, 10);
            assertFalse(sack.finishTransfer(before, null, 11));
            // Some storage actions also send the pickup-looking message; the transfer counts once.
            assertFalse(sack.chat("You put the Grimy torstol herb into your herb sack."));
            assertTrue(sack.finishTransfer(after, null, 12));
            assertEquals(1L, (long) sack.contents().get(herb));
            Map<Integer, long[]> counts = holdings.combine(after, EMPTY);
            sack.include(counts);
            Wealth.Snapshot end = snapshot(counts, 120, herb, 3671);
            session.observe(end, true);
            assertEquals(3671L, end.total());
            assertEquals(start.total(), end.total());
            assertEquals(0L, session.current().change);
            assertFalse(sack.finishTransfer(after, null, 13));
        }
    }

    @Test
    public void seedStacksAndEveryGemVariantTransferWithoutSessionLossInEitherDirection()
    {
        for (BagTracker.Kind kind : BagTracker.Kind.values())
        {
            for (boolean reverse : new boolean[]{false, true})
            {
                int id = kind.seeds() ? ItemID.RANARR_SEED : kind.semiPrecious ? ItemID.UNCUT_OPAL : ItemID.UNCUT_SAPPHIRE;
                int quantity = kind.seeds() ? 150 : 3;
                BagTracker bag = new BagTracker(kind, name -> id, item -> item == id);
                bag.restore(Collections.emptyMap());
                Item[] before = {new Item(id, quantity), new Item(kind.closed, 1)};
                Item[] after = {new Item(-1, 0), before[1]};
                int selected = reverse ? kind.closed : id;
                int target = reverse ? id : kind.closed;
                assertEquals(kind.closed, ContainerUse.container(selected, target));
                HoldingsTracker holdings = new HoldingsTracker(item -> item, item -> false);
                holdings.observeBank(EMPTY, 100);
                Wealth.Snapshot start = snapshot(holdings.combine(before, EMPTY), 110, id, 1000);
                SessionTracker session = new SessionTracker(100);
                session.observe(start, true);
                bag.beginItemUse(ContainerUse.storedItem(selected, target), before, null, 10);
                if (kind.seeds()) { assertFalse(bag.chat("Stored 150 x Ranarr seed in your seed box.", 10)); }
                else { assertFalse(bag.chat("You put a gem in your " + kind.name + ": Uncut "
                    + (kind.semiPrecious ? "opal" : "sapphire"), 10)); }
                assertTrue(kind.name, bag.finishTransfer(after, null, 12));
                assertEquals(quantity, (long) bag.contents().get(id));
                Map<Integer, long[]> counts = holdings.combine(after, EMPTY);
                bag.include(counts);
                Wealth.Snapshot end = snapshot(counts, 120, id, 1000);
                session.observe(end, true);
                assertEquals(kind.name, start.total(), end.total());
                assertEquals(kind.name, 0L, session.current().change);
            }
        }
    }

    @Test
    public void fullBagOrFailedUseNeverInventsStoredItems()
    {
        BagTracker bag = new BagTracker(BagTracker.Kind.GEM_POUCH, name -> -1, id -> false);
        bag.restore(Collections.singletonMap(ItemID.UNCUT_OPAL, 5L));
        Item[] inventory = {new Item(ItemID.GEM_POUCH, 1), new Item(ItemID.UNCUT_OPAL, 1)};
        bag.beginItemUse(ItemID.UNCUT_OPAL, inventory, null, 10);
        assertFalse(bag.finishTransfer(inventory, null, 12));
        assertFalse(bag.finishTransfer(EMPTY, null, 19)); // Expired action is not interpreted as a fill.
        assertEquals(5L, (long) bag.contents().get(ItemID.UNCUT_OPAL));
    }

    @Test
    public void usingOneHerbDoesNotAttributeUnrelatedDisappearingHerbsToTheSack()
    {
        HerbSackTracker sack = new HerbSackTracker();
        sack.restore(Collections.emptyMap());
        Item[] before = {new Item(ItemID.UNIDENTIFIED_TORSTOL, 1), new Item(ItemID.UNIDENTIFIED_RANARR, 1)};
        sack.beginItemUse(ItemID.UNIDENTIFIED_TORSTOL, before, null, 10);
        assertTrue(sack.finishTransfer(EMPTY, null, 12));
        assertEquals(1, sack.contents().size());
        assertEquals(1L, (long) sack.contents().get(ItemID.UNIDENTIFIED_TORSTOL));
    }

    @Test
    public void wrongGemAndInvalidSelectionsDoNotStartStorageTransfers()
    {
        assertEquals(-1, ContainerUse.container(-1, ItemID.GEM_BAG));
        assertEquals(-1, ContainerUse.container(ItemID.UNCUT_SAPPHIRE, ItemID.UNCUT_RUBY));
        assertEquals(-1, ContainerUse.container(ItemID.GEM_BAG, ItemID.SLAYER_HERB_SACK));
        BagTracker pouch = new BagTracker(BagTracker.Kind.GEM_POUCH, name -> -1, id -> false);
        pouch.restore(Collections.emptyMap());
        pouch.beginItemUse(ItemID.UNCUT_SAPPHIRE, new Item[]{new Item(ItemID.UNCUT_SAPPHIRE, 1)}, null, 10);
        assertFalse(pouch.finishTransfer(EMPTY, null, 12));
        assertTrue(pouch.contents().isEmpty());
    }

    @Test
    public void itemOnContainerAtBankUsesCombinedQuantitiesAndHandlesPartialFill()
    {
        HerbSackTracker sack = new HerbSackTracker();
        sack.restore(Collections.emptyMap());
        int herb = ItemID.UNIDENTIFIED_TORSTOL;
        Item[] bank = {new Item(herb, 10)};
        sack.beginItemUse(herb, new Item[]{new Item(herb, 3)}, bank, 10);
        assertTrue(sack.finishTransfer(new Item[]{new Item(herb, 1)}, bank, 12));
        assertEquals(2L, (long) sack.contents().get(herb));
        // A loose item moving to the bank is not a successful item-on-sack fill.
        sack.beginItemUse(herb, new Item[]{new Item(herb, 1)}, bank, 13);
        assertFalse(sack.finishTransfer(EMPTY, new Item[]{new Item(herb, 11)}, 15));
        assertEquals(2L, (long) sack.contents().get(herb));
    }

    private static Wealth.Snapshot snapshot(Map<Integer, long[]> quantities, long time, int valuedItem, long price)
    {
        Map<Integer, Wealth.Entry> entries = new LinkedHashMap<>();
        quantities.forEach((id, q) -> entries.put(id, new Wealth.Entry(id, "Item", q[0], q[1], q[2],
            id == valuedItem ? price : 0, 0, true, true, false)));
        return new Wealth.Snapshot(time, 100, entries);
    }
}

