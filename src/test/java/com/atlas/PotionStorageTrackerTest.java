package com.atlas;

import com.google.gson.Gson;
import com.google.inject.Guice;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.Item;
import org.junit.Test;
import static org.junit.Assert.*;

public class PotionStorageTrackerTest
{
    private static final int ONE = 101, TWO = 102, THREE = 103, FOUR = 104, UNFINISHED = 201;
    private final HoldingsTracker bank = new HoldingsTracker(id -> id, id -> false);
    private long time = 100;

    private PotionStorageTracker tracker(long doses)
    {
        PotionStorageTracker tracker = new PotionStorageTracker();
        tracker.register(new int[]{ONE, TWO, THREE, FOUR});
        tracker.register(new int[]{UNFINISHED});
        tracker.observe(true, doses == 0 ? Collections.emptyMap() : Collections.singletonMap(ONE, doses));
        tracker.takeReconciliation();
        bank.observeBank(new Item[0], time);
        return tracker;
    }

    private Wealth.Snapshot snapshot(PotionStorageTracker tracker, Item... inventory)
    {
        Map<Integer, long[]> quantities = bank.combine(inventory, null);
        Map<Integer, Long> stored = tracker.include(quantities);
        Map<Integer, Wealth.Entry> entries = new LinkedHashMap<>();
        quantities.forEach((id, q) -> entries.put(id, new Wealth.Entry(id, "Potion " + id,
            q[0], q[1], q[2], tracker.price(id, item -> item == FOUR ? 4000 : 1731),
            0, true, true, false, 0, stored.getOrDefault(id, 0L))));
        return new Wealth.Snapshot(++time, bank.getBankTime(), entries);
    }

    @Test
    public void withdrawAndDepositEveryDoseSizeKeepTotalAndSessionNeutral()
    {
        for (int doses = 1; doses <= 4; doses++)
        {
            PotionStorageTracker tracker = tracker(40);
            SessionTracker session = new SessionTracker(time);
            Wealth.Snapshot before = snapshot(tracker);
            session.observe(tracker.forSession(before), true);
            tracker.observe(true, Collections.singletonMap(ONE, 40L - doses));
            Wealth.Snapshot withdrawn = snapshot(tracker, new Item(100 + doses, 1));
            session.observe(tracker.forSession(withdrawn), true);
            assertEquals(before.total(), withdrawn.total());
            assertEquals(0, session.current().change);
            tracker.observe(true, Collections.singletonMap(ONE, 40L));
            Wealth.Snapshot deposited = snapshot(tracker);
            session.observe(tracker.forSession(deposited), true);
            assertEquals(before.total(), deposited.total());
            assertEquals(0, session.current().change);
        }
    }

    @Test
    public void drinkingCostsOneDoseAndReturningRemainderDoesNotAddAnotherLoss()
    {
        PotionStorageTracker tracker = tracker(36);
        SessionTracker session = new SessionTracker(time);
        session.observe(tracker.forSession(snapshot(tracker, new Item(FOUR, 1))), true);
        session.observe(tracker.forSession(snapshot(tracker, new Item(THREE, 1))), true);
        assertEquals(-1000, session.current().change);
        tracker.observe(true, Collections.singletonMap(ONE, 39L));
        session.observe(tracker.forSession(snapshot(tracker)), true);
        assertEquals(-1000, session.current().change);
    }

    @Test
    public void realLootStillCountsAndDecantingIsNeutral()
    {
        PotionStorageTracker tracker = tracker(0);
        SessionTracker session = new SessionTracker(time);
        session.observe(tracker.forSession(snapshot(tracker)), true);
        session.observe(tracker.forSession(snapshot(tracker, new Item(FOUR, 1))), true);
        assertEquals(4000, session.current().change);
        session.observe(tracker.forSession(snapshot(tracker, new Item(TWO, 2))), true);
        assertEquals(4000, session.current().change);
        tracker.observe(true, Collections.singletonMap(ONE, 4L));
        session.observe(tracker.forSession(snapshot(tracker)), true);
        assertEquals(4000, session.current().change);
    }

    @Test
    public void movingBetweenRegularBankAndPotionStorageIsNeutral()
    {
        PotionStorageTracker tracker = tracker(0);
        bank.observeBank(new Item[]{new Item(THREE, 10)}, ++time);
        SessionTracker session = new SessionTracker(time);
        Wealth.Snapshot before = snapshot(tracker);
        session.observe(tracker.forSession(before), true);
        bank.observeBank(new Item[0], ++time);
        tracker.observe(true, Collections.singletonMap(ONE, 30L));
        Wealth.Snapshot after = snapshot(tracker);
        session.observe(tracker.forSession(after), true);
        assertEquals(before.total(), after.total());
        assertEquals(0, session.current().change);
    }

    @Test
    public void sessionPricesRemainFixedWhenMarketMovesBeforeWithdrawal()
    {
        PotionStorageTracker tracker = tracker(4);
        SessionTracker session = new SessionTracker(time);
        session.observe(tracker.forSession(snapshot(tracker)), true);
        tracker.observe(true, Collections.emptyMap());
        Wealth.Snapshot withdrawn = new Wealth.Snapshot(++time, 100, Collections.singletonMap(FOUR,
            new Wealth.Entry(FOUR, "Potion (4)", 0, 1, 0, 8000, 0, true, true, false)));
        session.observe(tracker.forSession(withdrawn), true);
        assertEquals(0, session.current().change);
    }

    @Test
    public void unfinishedPotionsAreWholeItemsAndTwoDoseMixesWork()
    {
        PotionStorageTracker tracker = tracker(0);
        tracker.register(new int[]{301, 302, -1, -1});
        assertEquals(400, tracker.price(301, id -> id == 302 ? 800 : 600));
        assertEquals(1731, tracker.price(UNFINISHED, id -> 1731));
        tracker.observe(true, Collections.singletonMap(UNFINISHED, 7L));
        assertEquals(12117, snapshot(tracker).total());
    }

    @Test
    public void firstDiscoveryReconcilesAndEmptyStorageRemainsUnlocked()
    {
        PotionStorageTracker tracker = new PotionStorageTracker();
        tracker.observe(true, Collections.singletonMap(ONE, 100L));
        assertTrue(tracker.takeReconciliation());
        assertFalse(tracker.takeReconciliation());
        tracker.observe(true, Collections.emptyMap());
        assertFalse(tracker.takeReconciliation());
        assertTrue(tracker.unlocked());
        assertTrue(tracker.contents().isEmpty());
    }

    @Test
    public void lockedAccountDoesNotIncludeCachedPotionsOrRepriceNormalItems()
    {
        PotionStorageTracker tracker = tracker(20);
        tracker.reset();
        tracker.restore(Collections.singletonMap(ONE, 20L));
        tracker.observe(false, Collections.emptyMap());
        assertFalse(tracker.unlocked());
        assertTrue(tracker.include(new LinkedHashMap<>()).isEmpty());
        assertEquals(1234, tracker.price(FOUR, id -> 1234));
    }

    @Test
    public void storedDosesPersistAndSyncWithoutBecomingLooseBankItems() throws Exception
    {
        PotionStorageTracker tracker = tracker(17);
        bank.observeBank(new Item[]{new Item(ONE, 3)}, ++time);
        Wealth.Snapshot snapshot = snapshot(tracker);
        Wealth.State state = Wealth.State.empty("fixture")
            .withExtraContainers(Collections.singletonMap(PotionStorageTracker.STORAGE_KEY, tracker.contents()))
            .append(snapshot, Collections.emptyMap());
        assertEquals(3L, state.bank.quantities.get(ONE).longValue());
        Wealth.State loaded = WealthStore.decode("fixture", WealthStore.encode(state));
        Gson gson = Guice.createInjector().getInstance(Gson.class);
        Wealth.State synced = WealthSync.decode(WealthSync.encode(loaded, gson), "fixture", gson);
        assertEquals(17L, synced.extraContainers.get(PotionStorageTracker.STORAGE_KEY).get(ONE).longValue());
        assertEquals(3L, synced.bank.quantities.get(ONE).longValue());
        PotionStorageTracker restored = tracker(0);
        restored.reset();
        restored.restore(synced.extraContainers.get(PotionStorageTracker.STORAGE_KEY));
        assertTrue(restored.unlocked());
        assertEquals(17L, restored.contents().get(ONE).longValue());
    }
    @Test
    public void sidebarShowsStorageOnlyForUnlockedCharacters() throws Exception
    {
        javax.swing.SwingUtilities.invokeAndWait(() ->
        {
            WealthPanel panel = new WealthPanel(null, () -> {});
            Wealth.State locked = Wealth.State.empty("fixture");
            Wealth.Snapshot empty = new Wealth.Snapshot(100, 100, Collections.emptyMap());
            panel.update(empty, locked, true, true, "");
            assertFalse(labels(panel).contains("Potion storage"));
            Wealth.State unlocked = locked.withExtraContainers(
                Collections.singletonMap(PotionStorageTracker.STORAGE_KEY, Collections.emptyMap()));
            panel.update(empty, unlocked, true, true, "");
            assertTrue(labels(panel).contains("Potion storage 0"));
            panel.update(empty, locked, true, true, "");
            assertFalse(labels(panel).contains("Potion storage"));
        });
    }

    private String labels(java.awt.Container container)
    {
        StringBuilder text = new StringBuilder();
        for (java.awt.Component child : container.getComponents())
        {
            if (child instanceof javax.swing.JLabel) { text.append(((javax.swing.JLabel) child).getText()); }
            if (child instanceof java.awt.Container) { text.append(labels((java.awt.Container) child)); }
        }
        return text.toString();
    }
}
