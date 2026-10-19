package com.atlas;

import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class SessionTrackerTest
{
    @Test
    public void hourlyChangeUsesExactElapsedTimeForGainsLossesAndShortSessions()
    {
        long start = 1_000_000;
        Wealth.Session gain = new Wealth.Session(start, start + 29 * 60_000, 901_100, true, false);
        assertEquals(29 * 60_000, gain.elapsedMillis());
        assertEquals(1_864_345, gain.changePerHour());
        Wealth.Session loss = new Wealth.Session(start, start + 90 * 60_000, -4_100_000, false, false);
        assertEquals(-2_733_333, loss.changePerHour());
        assertEquals(60_000, new Wealth.Session(start, start + 30_000, 500, false, false).changePerHour());
        assertEquals(0, new Wealth.Session(start, start, 100, false, false).changePerHour());
        assertEquals(0, new Wealth.Session(start, start + 3_600_000, 0, false, false).changePerHour());
        // Scale without overflowing a long multiplication before division.
        assertEquals(10_000_000_000_000L,
            new Wealth.Session(start, start + 3_600_000, 10_000_000_000_000L, false, false).changePerHour());
    }

    private static Wealth.Entry item(int id, long bank, long inventory, long equipment, long price)
    {
        return new Wealth.Entry(id, "Item " + id, bank, inventory, equipment, price, 1, true, true, price == 0);
    }

    private static Wealth.Snapshot snapshot(long time, boolean bankKnown, Wealth.Entry... entries)
    {
        Map<Integer, Wealth.Entry> map = new LinkedHashMap<>();
        for (Wealth.Entry entry : entries) { map.put(entry.id, entry); }
        return new Wealth.Snapshot(time, bankKnown ? time : 0, map);
    }

    @Test
    public void twistedBowFallingFiftyMillionDoesNotHideSlayerSupplyLoss()
    {
        SessionTracker tracker = new SessionTracker(100);
        tracker.observe(snapshot(110, true, item(1, 1, 0, 0, 1_500_000_000), item(2, 0, 10_000, 0, 1000)), true);
        tracker.observe(snapshot(200, true, item(1, 1, 0, 0, 1_450_000_000), item(2, 0, 5900, 0, 1000)), true);
        assertEquals(-4_100_000L, tracker.current().change);
        assertEquals(100L, tracker.current().start);
        assertEquals(200L, tracker.current().end);
    }

    @Test
    public void unchangedHoldingsOnlyChangingPriceHaveZeroSessionChange()
    {
        SessionTracker tracker = new SessionTracker(100);
        tracker.observe(snapshot(110, true, item(1, 1, 0, 0, 1_500_000_000)), true);
        tracker.observe(snapshot(200, true, item(1, 1, 0, 0, 1_450_000_000)), true);
        assertEquals(0L, tracker.current().change);
    }

    @Test
    public void newLootIsValuedOnceAndSubsequentMarketChangesDoNotRepriceIt()
    {
        SessionTracker tracker = new SessionTracker(100);
        tracker.observe(snapshot(110, true), true);
        tracker.observe(snapshot(120, true, item(1, 0, 1, 0, 7_510_000)), true);
        tracker.observe(snapshot(130, true, item(1, 0, 1, 0, 2_000_000)), true);
        assertEquals(7_510_000L, tracker.current().change);
    }

    @Test
    public void gainingThenLosingTheSameItemCancelsEvenIfMarketPricesChanged()
    {
        SessionTracker tracker = new SessionTracker(100);
        tracker.observe(snapshot(110, true), true);
        tracker.observe(snapshot(120, true, item(1, 0, 10, 0, 100)), true);
        tracker.observe(snapshot(130, true, item(1, 0, 10, 0, 50)), true);
        tracker.observe(snapshot(140, true), true);
        assertEquals(0L, tracker.current().change);
    }

    @Test
    public void bankInventoryAndEquipmentTransfersDoNotCreateSessionProfit()
    {
        SessionTracker tracker = new SessionTracker(100);
        tracker.observe(snapshot(110, true, item(1, 1, 0, 0, 104_000_000)), true);
        tracker.observe(snapshot(120, true, item(1, 0, 1, 0, 104_000_000)), true);
        tracker.observe(snapshot(130, true, item(1, 0, 0, 1, 100_000_000)), true);
        tracker.observe(snapshot(140, true, item(1, 1, 0, 0, 90_000_000)), true);
        assertEquals(0L, tracker.current().change);
    }

    @Test
    public void firstBankDiscoveryDoesNotCountTheEntireBankAsNewLoot()
    {
        SessionTracker tracker = new SessionTracker(100);
        tracker.observe(snapshot(110, false, item(1, 0, 100, 0, 1)), false);
        tracker.observe(snapshot(120, false, item(1, 0, 150, 0, 1)), false);
        tracker.observe(snapshot(130, true, item(1, 1_000_000, 150, 0, 1)), true);
        assertEquals(50L, tracker.current().change);
        assertTrue(tracker.current().estimated);
    }

    @Test
    public void firstFreshBankReconcilesOfflineDifferencesWithoutChangingTaskResult()
    {
        SessionTracker tracker = new SessionTracker(100);
        tracker.observe(snapshot(110, true, item(1, 1_000_000, 100, 0, 1)), false);
        tracker.observe(snapshot(120, true, item(1, 1_000_000, 150, 0, 1)), false);
        tracker.observe(snapshot(130, true, item(1, 500_150, 0, 0, 1)), true);
        assertEquals(50L, tracker.current().change);
    }

    @Test
    public void sessionsStartFreshAndDoNotAttributeChangesMadeWhileLoggedOut()
    {
        SessionTracker first = new SessionTracker(100);
        first.observe(snapshot(110, true, item(1, 100, 0, 0, 1)), true);
        first.observe(snapshot(120, true, item(1, 150, 0, 0, 1)), true);
        SessionTracker second = new SessionTracker(200);
        second.observe(snapshot(210, true, item(1, 500, 0, 0, 1)), true);
        assertEquals(50L, first.current().change);
        assertEquals(0L, second.current().change);
    }

    @Test
    public void completedAndActiveSessionsRoundTripWithoutDuplicatingNormalLogout()
    {
        Wealth.State state = Wealth.State.empty("test")
            .withActiveSession(new Wealth.Session(100, 200, -4_100_000, false, false)).closeSession(false)
            .withActiveSession(new Wealth.Session(300, 400, 7_510_000, true, false));
        Wealth.State loaded = WealthStore.decode("test", WealthStore.encode(state));
        assertEquals(1, loaded.sessions.size());
        assertEquals(-4_100_000L, loaded.sessions.get(0).change);
        assertEquals(7_510_000L, loaded.activeSession.change);
        loaded = loaded.closeSession(false).closeSession(false);
        assertEquals(2, loaded.sessions.size());
        assertNull(loaded.activeSession);
    }

    @Test
    public void unfinishedSessionRecoveryUsesLastCheckpointEndNotNextLoginTime()
    {
        Wealth.State state = Wealth.State.empty("test")
            .withActiveSession(new Wealth.Session(100, 200, 123, false, false));
        state = WealthStore.decode("test", WealthStore.encode(state)).closeSession(true);
        assertTrue(state.sessions.get(0).interrupted);
        assertEquals(200L, state.sessions.get(0).end);
        assertNull(state.activeSession);
    }

    @Test
    public void olderWealthFilesStartWithEmptySessionHistory()
    {
        JsonObject legacy = WealthStore.encode(Wealth.State.empty("test"));
        legacy.remove("sessions");
        Wealth.State loaded = WealthStore.decode("test", legacy);
        assertTrue(loaded.sessions.isEmpty());
        assertNull(loaded.activeSession);
    }

    @Test
    public void regularWealthAndBankCheckpointsPreserveSessionHistory()
    {
        Wealth.State state = Wealth.State.empty("test")
            .withActiveSession(new Wealth.Session(100, 200, 50, false, false)).closeSession(false)
            .withActiveSession(new Wealth.Session(300, 400, -25, false, false))
            .withBank(new Wealth.Bank(400, Collections.singletonMap(1, 100L)))
            .append(snapshot(400, true, item(1, 100, 0, 0, 1)), Collections.singletonMap(1, 1L));
        assertEquals(1, state.sessions.size());
        assertEquals(-25L, state.activeSession.change);
        assertEquals(1, state.history.size());
    }

    @Test
    public void historyTableShowsCurrentFirstThenCompletedNewestFirst()
    {
        Wealth.State state = Wealth.State.empty("test")
            .withActiveSession(new Wealth.Session(100, 200, -10, false, false)).closeSession(false)
            .withActiveSession(new Wealth.Session(300, 400, 20, false, false)).closeSession(false)
            .withActiveSession(new Wealth.Session(500, 600, 30, false, false));
        WealthPanel.SessionHistoryModel model = new WealthPanel.SessionHistoryModel();
        model.setState(state);
        assertEquals(3, model.getRowCount());
        assertEquals(500L, model.row(0).start);
        assertEquals(300L, model.row(1).start);
        assertEquals(100L, model.row(2).start);
    }
}
