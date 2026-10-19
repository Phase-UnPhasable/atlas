package com.atlas;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Item;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import static org.junit.Assert.*;

public class BagTrackerTest
{
    @Test
    public void syncRemindersFollowCarriedContainersAndDisappearAfterChecks()
    {
        HerbSackTracker herbs = new HerbSackTracker();
        BagTracker gems = bag(BagTracker.Kind.GEM_SACK);
        BagTracker seeds = bag(BagTracker.Kind.SEED_BOX);
        List<BagTracker> bags = Arrays.asList(gems, seeds);
        herbs.restore(Collections.singletonMap(ItemID.UNIDENTIFIED_RANARR, 2L));
        gems.restore(Collections.singletonMap(ItemID.UNCUT_OPAL, 3L));
        seeds.restore(Collections.singletonMap(SEED, 4L));
        // Saved banked contents alone must never produce a carried-container reminder.
        assertEquals("", AtlasPlugin.containerSyncStatus(EMPTY, herbs, bags));
        Item[] carried = {new Item(ItemID.SLAYER_HERB_SACK_SILK_OPEN, 1),
            new Item(ItemID.GEM_SACK_OPEN, 1), new Item(ItemID.SEED_BOX, 1)};
        assertEquals("Check container to sync this session.<br>• Herb Sack<br>• Gem Sack<br>• Seed Box",
            AtlasPlugin.containerSyncStatus(carried, herbs, bags));
        assertEquals("", AtlasPlugin.containerSyncStatus(EMPTY, herbs, bags));
        assertEquals(2L, (long) herbs.contents().get(ItemID.UNIDENTIFIED_RANARR));
        assertEquals(3L, (long) gems.contents().get(ItemID.UNCUT_OPAL));
        assertEquals(4L, (long) seeds.contents().get(SEED));
        herbs.chat("You look in your herb sack and see:"); herbs.finishCheck();
        assertEquals("Check container to sync this session.<br>• Gem Sack<br>• Seed Box",
            AtlasPlugin.containerSyncStatus(carried, herbs, bags));
        gems.menu("Check", carried, EMPTY, 10);
        gems.chat("The gem sack is empty.", 10); assertTrue(gems.finishCheck(11));
        seeds.observeSeeds(EMPTY);
        assertEquals("", AtlasPlugin.containerSyncStatus(carried, herbs, bags));
    }

    @Test
    public void syncRemindersRecognizeEachGemVariantAndIgnoreMissingInventory()
    {
        HerbSackTracker herbs = new HerbSackTracker();
        for (BagTracker.Kind kind : BagTracker.Kind.values())
        {
            List<BagTracker> bags = Collections.singletonList(bag(kind));
            assertEquals("", AtlasPlugin.containerSyncStatus(null, herbs, bags));
            assertEquals("", AtlasPlugin.containerSyncStatus(new Item[]{new Item(kind.open, 0)}, herbs, bags));
            String closed = AtlasPlugin.containerSyncStatus(new Item[]{new Item(kind.closed, 1)}, herbs, bags);
            assertFalse(kind.name, closed.isEmpty());
            assertEquals(closed, AtlasPlugin.containerSyncStatus(new Item[]{new Item(kind.open, 1)}, herbs, bags));
        }
    }

    private static final Item[] EMPTY = new Item[0];
    private static final int SEED = ItemID.RANARR_SEED;
    private BagTracker bag(BagTracker.Kind kind)
    { return new BagTracker(kind, name -> name.equalsIgnoreCase("Ranarr seed") ? SEED : -1, id -> id == SEED); }
    private BagTracker checked(BagTracker.Kind kind, String line)
    {
        BagTracker tracker = bag(kind);
        tracker.menu("Check", new Item[]{new Item(kind.open, 1)}, EMPTY, 10);
        tracker.chat(line, 10);
        assertTrue(kind.name, tracker.finishCheck(11));
        tracker.takeReconciliation();
        return tracker;
    }

    @Test
    public void allFiveGemVariantsParseChecksAndKeepSupportedGemsSeparate()
    {
        for (BagTracker.Kind kind : BagTracker.Kind.values())
        {
            if (kind.seeds()) { continue; }
            String line = kind.semiPrecious ? "Opal: 2, Jade: 1, Red topaz: 0" : "Sapphires: 2, Emeralds: 1, Ruby: 3, Diamonds: 0, Dragonstones: 0";
            BagTracker tracker = checked(kind, line);
            assertTrue(tracker.confirmed());
            assertEquals(2L, (long) tracker.contents().get(kind.semiPrecious ? ItemID.UNCUT_OPAL : ItemID.UNCUT_SAPPHIRE));
            assertEquals(kind == BagTracker.Kind.GEM_SACK || kind.semiPrecious, tracker.accepts(ItemID.UNCUT_JADE));
            assertEquals(kind == BagTracker.Kind.GEM_SACK || !kind.semiPrecious, tracker.accepts(ItemID.UNCUT_RUBY));
            assertTrue(kind.owns(kind.closed)); assertTrue(kind.owns(kind.open));
        }
    }

    @Test
    public void checkingTwoGemContainersDoesNotCopyContentsBetweenThem()
    {
        BagTracker pouch = bag(BagTracker.Kind.GEM_POUCH);
        BagTracker tote = bag(BagTracker.Kind.GEM_TOTE);
        pouch.menu("Check", EMPTY, EMPTY, 10);
        pouch.chat("Opal: 3, Jade: 0, Red topaz: 0", 10);
        tote.chat("Opal: 3, Jade: 0, Red topaz: 0", 10);
        assertTrue(pouch.finishCheck(11));
        assertFalse(tote.finishCheck(11));
        assertNull(tote.contents());
        tote.menu("Check", EMPTY, EMPTY, 12);
        tote.chat("Opal: 8, Jade: 0, Red topaz: 0", 12);
        tote.finishCheck(13);
        assertEquals(3L, (long) pouch.contents().get(ItemID.UNCUT_OPAL));
        assertEquals(8L, (long) tote.contents().get(ItemID.UNCUT_OPAL));
    }

    @Test
    public void seedBoxCheckAndPickupUpdateCountsWithoutInventorySlotChanges()
    {
        BagTracker seeds = bag(BagTracker.Kind.SEED_BOX);
        seeds.chat("The seed box contains:", 10);
        seeds.chat("12 x Ranarr seed.", 10);
        assertTrue(seeds.finishCheck(11));
        assertEquals(12L, (long) seeds.contents().get(SEED));
        assertTrue(seeds.chat("You put 3 x Ranarr seed straight into your open seed box.", 12));
        assertEquals(15L, (long) seeds.contents().get(SEED));
        assertFalse(seeds.chat("3 x Ranarr seed.", 13));
        assertEquals(15L, (long) seeds.contents().get(SEED));
    }

    @Test
    public void seedFillAndEmptyMessagesAreNotCountedAgainAlongsideContainerDiffs()
    {
        BagTracker seeds = checked(BagTracker.Kind.SEED_BOX, "The seed box is empty.");
        Item box = new Item(ItemID.SEED_BOX_OPEN, 1);
        seeds.menu("Fill", new Item[]{box, new Item(SEED, 10)}, EMPTY, 12);
        assertFalse(seeds.chat("Stored 10 x Ranarr seed in your seed box.", 12));
        assertTrue(seeds.finishTransfer(new Item[]{box}, EMPTY, 14));
        assertEquals(10L, (long) seeds.contents().get(SEED));
        seeds.menu("Empty", new Item[]{box}, EMPTY, 15);
        assertFalse(seeds.chat("Emptied 3 x Ranarr seed to your inventory.", 15));
        assertTrue(seeds.finishTransfer(new Item[]{box, new Item(SEED, 3)}, EMPTY, 17));
        assertEquals(7L, (long) seeds.contents().get(SEED));
    }

    @Test
    public void seedInterfaceIsAuthoritativeAndOnlyFirstDiscoveryReconcilesSession()
    {
        BagTracker seeds = bag(BagTracker.Kind.SEED_BOX);
        seeds.observeSeeds(new Item[]{new Item(SEED, 50)});
        assertEquals(50L, (long) seeds.contents().get(SEED));
        assertTrue(seeds.takeReconciliation());
        seeds.observeSeeds(new Item[]{new Item(SEED, 49)});
        assertFalse(seeds.takeReconciliation());
        seeds.observeSeeds(EMPTY);
        assertTrue(seeds.contents().isEmpty());
    }

    @Test
    public void contextualBankEmptyPreservesTotalAcrossEveryContainerKind()
    {
        for (BagTracker.Kind kind : BagTracker.Kind.values())
        {
            int id = kind.seeds() ? SEED : kind.semiPrecious ? ItemID.UNCUT_OPAL : ItemID.UNCUT_SAPPHIRE;
            BagTracker tracker = bag(kind);
            tracker.restore(Collections.singletonMap(id, 4L));
            Item[] inventory = {new Item(kind.open, 1)};
            tracker.menu("Empty", inventory, new Item[]{new Item(id, 5)}, 10);
            assertFalse(tracker.finishTransfer(inventory, new Item[]{new Item(id, 5)}, 11));
            assertEquals(4L, (long) tracker.contents().get(id));
            assertTrue(kind.name, tracker.finishTransfer(inventory, new Item[]{new Item(id, 9)}, 12));
            assertTrue(tracker.contents().isEmpty());
            assertFalse(tracker.finishTransfer(inventory, new Item[]{new Item(id, 9)}, 13));
        }
    }

    @Test
    public void depositLooseItemsDoesNotRemoveItemsFromBags()
    {
        BagTracker tracker = bag(BagTracker.Kind.GEM_POUCH);
        int id = ItemID.UNCUT_OPAL;
        tracker.restore(Collections.singletonMap(id, 4L));
        tracker.menu("Deposit", new Item[]{new Item(id, 3)}, new Item[]{new Item(id, 5)}, 10);
        assertFalse(tracker.finishTransfer(EMPTY, new Item[]{new Item(id, 8)}, 12));
        assertEquals(4L, (long) tracker.contents().get(id));
    }

    @Test
    public void explicitGemMessagesAndPickupInferenceAreDeduplicated()
    {
        BagTracker tracker = checked(BagTracker.Kind.GEM_POUCH, "Opal: 2, Jade: 0, Red topaz: 0");
        Item[] inventory = {new Item(ItemID.GEM_POUCH_OPEN, 1)};
        tracker.settleAcquisitions(inventory);
        tracker.candidate(ItemID.UNCUT_OPAL, 1);
        assertTrue(tracker.chat("You put a gem in your gem pouch: Uncut opal", 12));
        assertFalse(tracker.settleAcquisitions(inventory));
        assertEquals(3L, (long) tracker.contents().get(ItemID.UNCUT_OPAL));
    }

    @Test
    public void matchedGroundPickupAddsOnlyWhenOpenCheckedBagHasRoomAndNoLooseGemArrives()
    {
        BagTracker tracker = checked(BagTracker.Kind.GEM_POUCH, "Opal: 2, Jade: 0, Red topaz: 0");
        Item[] inventory = {new Item(ItemID.GEM_POUCH_OPEN, 1)};
        tracker.settleAcquisitions(inventory);
        assertTrue(tracker.canAcquire(ItemID.UNCUT_OPAL, inventory));
        tracker.candidate(ItemID.UNCUT_OPAL, 1);
        assertTrue(tracker.settleAcquisitions(inventory));
        assertEquals(3L, (long) tracker.contents().get(ItemID.UNCUT_OPAL));
        assertTrue(tracker.estimated());
        tracker.candidate(ItemID.UNCUT_OPAL, 1);
        assertFalse(tracker.settleAcquisitions(new Item[]{inventory[0], new Item(ItemID.UNCUT_OPAL, 1)}));
        assertEquals(3L, (long) tracker.contents().get(ItemID.UNCUT_OPAL));
        assertFalse(tracker.canAcquire(ItemID.UNCUT_OPAL, new Item[]{new Item(ItemID.GEM_POUCH, 1)}));
        tracker.restore(Collections.singletonMap(ItemID.UNCUT_OPAL, 100L)); // Restore never overwrites live observations.
        assertEquals(3L, (long) tracker.contents().get(ItemID.UNCUT_OPAL));
    }

    @Test
    public void fullOrUncheckedBagsDoNotGuessAdditionalGems()
    {
        BagTracker full = checked(BagTracker.Kind.GEM_POUCH, "Opal: 5, Jade: 0, Red topaz: 0");
        Item[] inventory = {new Item(ItemID.GEM_POUCH_OPEN, 1)};
        assertFalse(full.canAcquire(ItemID.UNCUT_OPAL, inventory));
        BagTracker unknown = bag(BagTracker.Kind.GEM_POUCH);
        assertFalse(unknown.canAcquire(ItemID.UNCUT_OPAL, inventory));
        unknown.restore(Collections.singletonMap(ItemID.UNCUT_OPAL, 2L));
        assertFalse(unknown.canAcquire(ItemID.UNCUT_OPAL, inventory));
    }

    @Test
    public void bankDepositButtonSubtractsSharedGemOnlyOnceAcrossTwoBags()
    {
        BagTracker pouch = bag(BagTracker.Kind.GEM_POUCH);
        BagTracker tote = bag(BagTracker.Kind.GEM_TOTE);
        int id = ItemID.UNCUT_OPAL;
        pouch.restore(Collections.singletonMap(id, 2L)); tote.restore(Collections.singletonMap(id, 8L));
        List<BagTracker> bags = Arrays.asList(pouch, tote);
        Set<BagTracker.Kind> eligible = new HashSet<>(Arrays.asList(pouch.kind, tote.kind));
        HerbSackTracker herbs = new HerbSackTracker();
        Map<Integer, Long> before = BagTracker.combinedContents(herbs, false, bags, eligible);
        BagTracker.settleBulkDeposit(Collections.singletonMap(id, -3L), before, herbs, false, bags, eligible);
        assertEquals(7L, (long) BagTracker.combinedContents(herbs, false, bags, eligible).get(id));
        assertTrue(pouch.contents().isEmpty());
        assertEquals(7L, (long) tote.contents().get(id));
    }

    @Test
    public void explicitEmptyConfirmationAndBulkBankDiffCannotSubtractAnotherBagTwice()
    {
        BagTracker pouch = checked(BagTracker.Kind.GEM_POUCH, "Opal: 2, Jade: 0, Red topaz: 0");
        BagTracker tote = checked(BagTracker.Kind.GEM_TOTE, "Opal: 8, Jade: 0, Red topaz: 0");
        List<BagTracker> bags = Arrays.asList(pouch, tote);
        Set<BagTracker.Kind> eligible = new HashSet<>(Arrays.asList(pouch.kind, tote.kind));
        HerbSackTracker herbs = new HerbSackTracker();
        Map<Integer, Long> before = BagTracker.combinedContents(herbs, false, bags, eligible);
        assertTrue(pouch.chat("You empty your gem pouch into the bank.", 12));
        BagTracker.settleBulkDeposit(Collections.singletonMap(ItemID.UNCUT_OPAL, -2L), before, herbs, false, bags, eligible);
        assertEquals(8L, (long) tote.contents().get(ItemID.UNCUT_OPAL));
    }

    @Test
    public void bankDepositButtonsMoveHerbsToBankWithoutBookingPickupAgain()
    {
        HerbSackTracker herbs = new HerbSackTracker();
        herbs.restore(Collections.singletonMap(ItemID.UNIDENTIFIED_RANARR, 1L));
        ContainerTransfer transfer = new ContainerTransfer();
        Item[] invBefore = {new Item(ItemID.SLAYER_HERB_SACK_OPEN, 1), new Item(ItemID.UNIDENTIFIED_RANARR, 3)};
        Item[] bankBefore = {new Item(ItemID.UNIDENTIFIED_RANARR, 5)};
        transfer.begin("Empty", invBefore, bankBefore, 10, id -> true);
        Map<Integer, Long> before = BagTracker.combinedContents(herbs, true, Collections.emptyList(), Collections.emptySet());
        Map<Integer, Long> changes = transfer.finish(new Item[]{invBefore[0]}, new Item[]{new Item(ItemID.UNIDENTIFIED_RANARR, 9)}, 12, id -> true);
        BagTracker.settleBulkDeposit(changes, before, herbs, true, Collections.emptyList(), Collections.emptySet());
        assertTrue(herbs.contents().isEmpty());
        assertEquals(-1L, (long) changes.get(ItemID.UNIDENTIFIED_RANARR));
        assertTrue(transfer.finish(EMPTY, bankBefore, 13, id -> true).isEmpty());
    }

    @Test
    public void storedCountsPersistPerVariantWithoutBecomingLooseBankItems()
    {
        BagTracker pouch = bag(BagTracker.Kind.GEM_POUCH);
        BagTracker tote = bag(BagTracker.Kind.GEM_TOTE);
        pouch.restore(Collections.singletonMap(ItemID.UNCUT_OPAL, 2L));
        tote.restore(Collections.singletonMap(ItemID.UNCUT_OPAL, 8L));
        Map<Integer, long[]> holdings = new LinkedHashMap<>();
        holdings.put(ItemID.GEM_POUCH, new long[]{1, 0, 0});
        holdings.put(ItemID.GEM_TOTE_OPEN, new long[]{0, 1, 0});
        holdings.put(ItemID.UNCUT_OPAL, new long[]{3, 0, 0});
        Map<Integer, Long> bankStored = pouch.include(holdings);
        tote.include(holdings);
        Map<Integer, Wealth.Entry> entries = new LinkedHashMap<>();
        holdings.forEach((id, q) -> entries.put(id, new Wealth.Entry(id, "Item", q[0], q[1], q[2],
            id == ItemID.UNCUT_OPAL ? 1000 : 0, 0, true, true, false, 0, bankStored.getOrDefault(id, 0L))));
        Wealth.Snapshot snapshot = new Wealth.Snapshot(110, 100, entries);
        assertEquals(13_000L, snapshot.total());
        Map<String, Map<Integer, Long>> containers = new LinkedHashMap<>();
        containers.put(pouch.kind.name(), pouch.contents()); containers.put(tote.kind.name(), tote.contents());
        Wealth.State state = Wealth.State.empty("test").withExtraContainers(containers).append(snapshot, Collections.emptyMap())
            .withHerbSack(Collections.emptyMap()).withActiveSession(new Wealth.Session(100, 110, 0, false, false)).closeSession(false);
        Wealth.State loaded = WealthStore.decode("test", WealthStore.encode(state));
        assertEquals(3L, (long) loaded.bank.quantities.get(ItemID.UNCUT_OPAL));
        assertEquals(2L, (long) loaded.extraContainers.get("GEM_POUCH").get(ItemID.UNCUT_OPAL));
        assertEquals(8L, (long) loaded.extraContainers.get("GEM_TOTE").get(ItemID.UNCUT_OPAL));
        assertEquals(13_000L, loaded.latest.total());
    }
}
