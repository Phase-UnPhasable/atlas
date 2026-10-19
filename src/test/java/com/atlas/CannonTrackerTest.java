package com.atlas;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Item;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import static org.junit.Assert.*;

public class CannonTrackerTest
{
    private static final int[] NORMAL = {ItemID.TWPART1, ItemID.TWPART2, ItemID.TWPART3, ItemID.TWPART4};
    private static final int[] SKINNED = {ItemID.LEAGUE_3_MULTICANNON_BASE, ItemID.LEAGUE_3_MULTICANNON_STAND,
        ItemID.LEAGUE_3_MULTICANNON_BARRELS, ItemID.LEAGUE_3_MULTICANNON_FURNACE};
    private static final Item[] EMPTY = new Item[0];

    private Item[] inventory(int[] parts, int assembly, int balls)
    {
        List<Item> inventory = new ArrayList<>();
        for (int component = assembly; component < parts.length; component++) { inventory.add(new Item(parts[component], 1)); }
        if (balls > 0) { inventory.add(new Item(ItemID.MCANNONBALL, balls)); }
        return inventory.toArray(EMPTY);
    }

    private Wealth.Snapshot observe(CannonTracker cannon, HoldingsTracker holdings, int assembly, Item[] inventory, long time)
    {
        cannon.observe(assembly, false, inventory, id -> id);
        Map<Integer, long[]> counts = holdings.combine(inventory, EMPTY);
        cannon.deployed().forEach((id, quantity) -> counts.computeIfAbsent(id, ignored -> new long[3]));
        Map<Integer, Wealth.Entry> entries = new LinkedHashMap<>();
        counts.forEach((id, q) -> entries.put(id, new Wealth.Entry(id, "Item " + id, q[0], q[1], q[2],
            id == ItemID.MCANNONBALL ? 200 : id >= ItemID.LEAGUE_3_MULTICANNON_BASE ? 180_000 : 150_000,
            0, true, true, false, 0, 0, cannon.deployed().getOrDefault(id, 0L))));
        return new Wealth.Snapshot(time, holdings.getBankTime(), entries);
    }

    private HoldingsTracker holdings()
    {
        HoldingsTracker holdings = new HoldingsTracker(id -> id, id -> false);
        holdings.observeBank(EMPTY, 100);
        return holdings;
    }

    @Test
    public void regularAndSkinnedCannonsRemainOwnedThroughEveryAssemblyStageAndPickup()
    {
        for (int[] parts : new int[][]{NORMAL, SKINNED})
        {
            CannonTracker cannon = new CannonTracker(); HoldingsTracker holdings = holdings();
            Wealth.Snapshot start = observe(cannon, holdings, 0, inventory(parts, 0, 100), 110);
            SessionTracker session = new SessionTracker(100); session.observe(start, true);
            for (int stage = 1; stage <= 4; stage++)
            {
                Wealth.Snapshot next = observe(cannon, holdings, stage, inventory(parts, stage, 100), 110 + stage);
                session.observe(next, true);
                assertEquals(start.total(), next.total());
                assertEquals(0L, session.current().change);
                assertEquals(stage, cannon.deployed().size());
                assertTrue(cannon.deployed().containsKey(parts[stage - 1]));
                assertFalse(cannon.estimated());
            }
            Wealth.Snapshot pickedUp = observe(cannon, holdings, 0, inventory(parts, 0, 100), 120);
            session.observe(pickedUp, true);
            assertEquals(start.total(), pickedUp.total());
            assertEquals(0L, session.current().change);
            assertEquals(0L, pickedUp.deployed());
        }
    }

    @Test
    public void assemblyCompletingBetweenCapturesStillRecognizesEverySkinnedPiece()
    {
        CannonTracker cannon = new CannonTracker(); HoldingsTracker holdings = holdings();
        Wealth.Snapshot start = observe(cannon, holdings, 0, inventory(SKINNED, 0, 0), 110);
        Wealth.Snapshot placed = observe(cannon, holdings, 4, EMPTY, 120);
        assertEquals(start.total(), placed.total());
        assertEquals(720_000L, placed.deployed());
        for (int id : SKINNED) { assertTrue(cannon.deployed().containsKey(id)); }
    }

    @Test
    public void loadingBallsIsStillALossAndUnusedBallsReturnedOnPickupOffsetThatCost()
    {
        CannonTracker cannon = new CannonTracker(); HoldingsTracker holdings = holdings();
        SessionTracker session = new SessionTracker(100);
        session.observe(observe(cannon, holdings, 0, inventory(SKINNED, 0, 100), 110), true);
        session.observe(observe(cannon, holdings, 4, inventory(SKINNED, 4, 70), 120), true);
        assertEquals(-6000L, session.current().change); // 30 balls loaded at 200 GP each.
        // Firing is not counted again: loaded cannonballs have already left tracked inventory.
        session.observe(observe(cannon, holdings, 4, inventory(SKINNED, 4, 70), 130), true);
        assertEquals(-6000L, session.current().change);
        session.observe(observe(cannon, holdings, 0, inventory(SKINNED, 0, 80), 140), true);
        assertEquals(-4000L, session.current().change); // Ten unused balls returned; twenty spent.
    }

    @Test
    public void savedSkinnedCannonRestoresWithoutABankVisitAndPickupDoesNotDoubleCount()
    {
        CannonTracker cannon = new CannonTracker(); HoldingsTracker holdings = holdings();
        observe(cannon, holdings, 0, inventory(SKINNED, 0, 0), 110);
        Wealth.Snapshot placed = observe(cannon, holdings, 4, EMPTY, 120);
        Wealth.State saved = Wealth.State.empty("test").withExtraContainers(
            Collections.singletonMap(CannonTracker.STORAGE_KEY, cannon.deployed())).append(placed, Collections.emptyMap());
        Wealth.State loaded = WealthStore.decode("test", WealthStore.encode(saved));
        assertTrue(loaded.bank.quantities.isEmpty()); // Deployed parts must never become saved bank items.
        assertEquals(720_000L, loaded.latest.deployed());
        CannonTracker restored = new CannonTracker(); restored.restore(loaded.extraContainers.get(CannonTracker.STORAGE_KEY));
        Wealth.Snapshot resumed = observe(restored, holdings, 4, EMPTY, 130);
        assertEquals(placed.total(), resumed.total());
        SessionTracker session = new SessionTracker(125); session.observe(resumed, false);
        Wealth.Snapshot pickedUp = observe(restored, holdings, 0, inventory(SKINNED, 0, 0), 140);
        session.observe(pickedUp, false, restored.takeReconciliation());
        assertEquals(placed.total(), pickedUp.total());
        assertEquals(0L, session.current().change);
    }

    @Test
    public void unknownSkinOnFirstDeployedLoginIsAnEstimateAndDiscoveryIsNotProfit()
    {
        CannonTracker cannon = new CannonTracker(); HoldingsTracker holdings = holdings();
        Wealth.Snapshot unknown = observe(cannon, holdings, 4, EMPTY, 110);
        assertTrue(cannon.estimated());
        assertEquals(600_000L, unknown.deployed());
        SessionTracker session = new SessionTracker(100); session.observe(unknown, true);
        Wealth.Snapshot actual = observe(cannon, holdings, 0, inventory(SKINNED, 0, 0), 120);
        assertTrue(cannon.takeReconciliation());
        session.observe(actual, true, true);
        assertEquals(0L, session.current().change);
        assertEquals(720_000L, actual.total());
    }

    @Test
    public void decayedRecoverableCannonKeepsItsOwnedPieces()
    {
        CannonTracker cannon = new CannonTracker(); HoldingsTracker holdings = holdings();
        observe(cannon, holdings, 0, inventory(SKINNED, 0, 0), 110);
        observe(cannon, holdings, 4, EMPTY, 120);
        cannon.observe(0, true, EMPTY, id -> id);
        assertEquals(4, cannon.deployed().size());
        for (int id : SKINNED) { assertEquals(1L, (long) cannon.deployed().get(id)); }
    }

    @Test
    public void extraCopiesInBankAndInventoryAreNotMistakenForTheDeployedSet()
    {
        CannonTracker cannon = new CannonTracker(); HoldingsTracker holdings = holdings();
        holdings.observeBank(inventory(NORMAL, 0, 0), 100);
        Item[] carried = inventory(SKINNED, 0, 0);
        Wealth.Snapshot start = observe(cannon, holdings, 0, carried, 110);
        Wealth.Snapshot placed = observe(cannon, holdings, 4, EMPTY, 120);
        assertEquals(start.total(), placed.total());
        assertEquals(600_000L, placed.bank());
        assertEquals(720_000L, placed.deployed());
        assertEquals(1_320_000L, placed.total());
    }

    @Test
    public void noDeploymentMeansNoImaginedCannonAndNoCompensationForOrdinaryLosses()
    {
        CannonTracker cannon = new CannonTracker(); HoldingsTracker holdings = holdings();
        SessionTracker session = new SessionTracker(100);
        session.observe(observe(cannon, holdings, 0, inventory(NORMAL, 0, 0), 110), true);
        session.observe(observe(cannon, holdings, 0, EMPTY, 120), true);
        assertEquals(-600_000L, session.current().change); // Dropping/trading parts is still a loss.
        assertTrue(cannon.deployed().isEmpty());
        assertFalse(CannonTracker.isPart(ItemID.MCANNONBALL));
        assertFalse(CannonTracker.isPart(ItemID.GRANITE_CANNONBALL));
        assertFalse(CannonTracker.isPart(ItemID.LEAGUE_3_MULTICANNON_PACK));
    }

    @Test
    public void legacyHistoryWithoutDeployedFieldStillLoadsAndResetSeparatesCharacters()
    {
        CannonTracker cannon = new CannonTracker(); HoldingsTracker holdings = holdings();
        Wealth.Snapshot start = observe(cannon, holdings, 0, inventory(NORMAL, 0, 0), 110);
        Wealth.State state = Wealth.State.empty("test").append(start, Collections.emptyMap());
        JsonObject old = WealthStore.encode(state);
        old.getAsJsonObject("snapshot").getAsJsonArray("entries").forEach(e -> e.getAsJsonObject().remove("deployed"));
        assertEquals(start.total(), WealthStore.decode("test", old).latest.total());
        observe(cannon, holdings, 4, EMPTY, 120);
        cannon.reset();
        assertTrue(cannon.deployed().isEmpty());
        cannon.observe(0, false, EMPTY, id -> id);
        assertFalse(cannon.estimated());
    }
}
