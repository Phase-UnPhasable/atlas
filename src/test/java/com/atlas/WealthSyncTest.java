package com.atlas;

import com.google.gson.Gson;
import com.google.inject.Guice;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.GZIPOutputStream;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import static org.junit.Assert.*;

public class WealthSyncTest
{
    private final Gson gson = Guice.createInjector().getInstance(Gson.class);

    private Wealth.State state(long... rows)
    {
        List<Wealth.Point> points = new ArrayList<>();
        for (int i = 0; i < rows.length; i += 3)
        { points.add(new Wealth.Point(rows[i], rows[i + 1], 0, 1, rows[i + 2], rows[i + 1] - rows[1] - rows[i + 2])); }
        if (points.isEmpty()) { return Wealth.State.empty("fixture"); }
        Wealth.Point last = points.get(points.size() - 1);
        Map<Integer, Wealth.Entry> entries = Collections.singletonMap(ItemID.COINS,
            new Wealth.Entry(ItemID.COINS, "Coins", last.total, 0, 0, 1, 0, false, true, false));
        return new Wealth.State("fixture", new Wealth.Snapshot(last.time, last.time, entries), points, 1);
    }

    private String archive(Wealth.State state)
    { return gson.toJson(WealthStore.encode(state)); }

    @Test
    public void pcDeckPcRoundTripRetainsEverySessionAndGraphPointExactlyOnce() throws Exception
    {
        Wealth.State pc = state(100, 1000, 0, 200, 1100, 10)
            .withActiveSession(new Wealth.Session(100, 200, 90, false, false)).closeSession(false);
        Wealth.State deck = WealthSync.merge(Wealth.State.empty("fixture"),
            Collections.singletonList(WealthSync.decode(WealthSync.encode(pc, gson), "fixture", gson)), true);
        assertEquals(2, deck.history.size()); assertEquals(1, deck.sessions.size());
        assertEquals(1100, deck.bank.quantities.get(ItemID.COINS).longValue());
        deck = deck.append(new Wealth.Snapshot(300, 300,
            Collections.singletonMap(ItemID.COINS, new Wealth.Entry(ItemID.COINS, "Coins", 1200, 0, 0, 1, 0, false, true, false))),
            Collections.singletonMap(ItemID.COINS, 1L))
            .withActiveSession(new Wealth.Session(210, 300, 100, false, false)).closeSession(false);
        Wealth.State shared = WealthSync.decode(WealthSync.encode(deck, gson), "fixture", gson);
        Wealth.State returned = WealthSync.merge(pc, Arrays.asList(pc, shared, shared), true);
        assertEquals(3, returned.history.size()); assertEquals(2, returned.sessions.size());
        assertEquals(1200, returned.latest.total());
        assertEquals(10, returned.history.get(2).market);
        assertTrue(returned.history.get(2).attributed);
        Wealth.State again = WealthSync.merge(returned, Collections.singletonList(shared), true);
        assertEquals(returned.history.size(), again.history.size());
        assertEquals(returned.sessions.size(), again.sessions.size());
        assertEquals(returned.history.get(2).items, again.history.get(2).items);
        assertEquals(2, pc.history.size()); // The original local backup remains immutable.
        assertEquals(1, pc.sessions.size());
    }

    @Test
    public void offlineForksMergeInEitherOrderWithoutInventingMarketAttribution() throws Exception
    {
        Wealth.State pc = state(100, 1000, 0, 200, 1100, 10);
        Wealth.State deck = state(100, 1000, 0, 300, 1200, 20);
        Wealth.State merged = WealthSync.merge(pc, Collections.singletonList(deck), true);
        Wealth.State reverse = WealthSync.merge(deck, Collections.singletonList(pc), true);
        assertEquals(3, merged.history.size());
        assertTrue(merged.history.get(1).attributed);
        assertFalse(merged.history.get(2).attributed);
        assertEquals(10, merged.history.get(2).market);
        for (int i = 0; i < merged.history.size(); i++)
        {
            assertEquals(merged.history.get(i).time, reverse.history.get(i).time);
            assertEquals(merged.history.get(i).market, reverse.history.get(i).market);
            assertEquals(merged.history.get(i).attributed, reverse.history.get(i).attributed);
        }
        Wealth.State restored = WealthSync.decode(WealthSync.encode(merged, gson), "fixture", gson);
        assertFalse(restored.history.get(2).attributed);
        assertFalse(WealthSync.merge(restored, Arrays.asList(pc, deck), true).history.get(2).attributed);
    }

    @Test
    public void currentSessionStaysLocalWhileCompletedSessionsUseTheNewestFinalResult() throws Exception
    {
        Wealth.Session running = new Wealth.Session(400, 500, 50, false, false);
        Wealth.State local = state(100, 1000, 0).withActiveSession(running);
        Wealth.State stale = state(100, 1000, 0)
            .withActiveSession(new Wealth.Session(200, 250, 30, true, true)).closeSession(true);
        Wealth.State finalCopy = state(100, 1000, 0)
            .withActiveSession(new Wealth.Session(200, 300, 60, false, false)).closeSession(false);
        Wealth.State merged = WealthSync.merge(local, Arrays.asList(finalCopy, stale), true);
        assertSame(running, merged.activeSession);
        assertEquals(1, merged.sessions.size()); assertEquals(60, merged.sessions.get(0).change);
        assertFalse(merged.sessions.get(0).interrupted);
        Wealth.State exported = WealthSync.decode(WealthSync.encode(merged, gson), "fixture", gson);
        assertNull(exported.activeSession); assertEquals(1, exported.sessions.size());
        Wealth.State duplicate = local.withActiveSession(running).closeSession(false);
        assertEquals(0, WealthSync.merge(local, Collections.singletonList(duplicate), false).sessions.size());
    }

    @Test
    public void bankObservationTimeBeatsDeviceRevisionOrAnUnbankedLaterSession()
    {
        Wealth.State stale = state(100, 1000, 0)
            .withActiveSession(new Wealth.Session(300, 400, 10, false, false)).closeSession(false);
        Wealth.State fresh = state(200, 2000, 0);
        Wealth.State merged = WealthSync.merge(stale, Collections.singletonList(fresh), true);
        assertEquals(200, merged.bank.time);
        assertEquals(2000, merged.bank.quantities.get(ItemID.COINS).longValue());
        // Incoming history must not overwrite the bank currently being used by a running client.
        Wealth.State live = WealthSync.merge(stale, Collections.singletonList(fresh), false);
        assertSame(stale.bank, live.bank);
    }

    @Test
    public void unknownBankRemoteCreditsArePreservedWhenAFullOlderCheckpointArrives()
    {
        Wealth.State partial = Wealth.State.empty("fixture")
            .withExtraContainers(Collections.singletonMap(SoulBearerTracker.REMOTE_BANK_KEY,
                Collections.singletonMap(ItemID.ARCEUUS_CORPSE_ABYSSAL, 1L)))
            .withActiveSession(new Wealth.Session(200, 300, 0, true, false)).closeSession(false);
        Wealth.State merged = WealthSync.merge(partial, Collections.singletonList(state(100, 1000, 0)), true);
        assertEquals(1L, merged.bank.quantities.get(ItemID.ARCEUUS_CORPSE_ABYSSAL).longValue());
        assertTrue(merged.extraContainers.get(SoulBearerTracker.REMOTE_BANK_KEY).isEmpty());
    }

    @Test
    public void payloadCannotCrossCharactersOrAcceptCorruptOrNewerSchemaData() throws Exception
    {
        String valid = WealthSync.encode(state(100, 1000, 0), gson);
        expectDecodeFailure(valid, "another-character");
        expectDecodeFailure("not-base64", "fixture");
        ByteArrayOutputStream unknown = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(unknown))
        { gzip.write("{\"syncSchema\":2}".getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        expectDecodeFailure(Base64.getEncoder().encodeToString(unknown.toByteArray()), "fixture");
        expectDecodeFailure(valid.substring(0, valid.length() - 8), "fixture");
        expectDecodeFailure(String.join("", Collections.nCopies(WealthSync.MAX_ENCODED + 1, "a")), "fixture");
        try
        {
            WealthSync.merge(state(100, 1000, 0), Collections.singletonList(Wealth.State.empty("other")), true);
            fail("Cross-character merge accepted");
        }
        catch (IllegalArgumentException expected) { }
    }

    @Test
    public void compactPayloadPreservesPouchBagCannonAndCachedContainerQuantities() throws Exception
    {
        Map<Integer, Wealth.Entry> entries = new LinkedHashMap<>();
        entries.put(ItemID.BLOODRUNE, new Wealth.Entry(ItemID.BLOODRUNE, "Blood rune", 20, 30, 0,
            300, 100, true, true, false, 5, 2, 0));
        entries.put(ItemID.TWPART1, new Wealth.Entry(ItemID.TWPART1, "Cannon base", 0, 0, 0,
            180_000, 100, true, true, false, 0, 0, 1));
        Map<String, Map<Integer, Long>> extra = new LinkedHashMap<>();
        extra.put(CannonTracker.STORAGE_KEY, Collections.singletonMap(ItemID.TWPART1, 1L));
        extra.put("SEED_BOX", Collections.singletonMap(ItemID.RANARR_SEED, 7L));
        Wealth.State original = Wealth.State.empty("fixture")
            .append(new Wealth.Snapshot(100, 100, entries), Collections.emptyMap())
            .withHerbSack(Collections.singletonMap(ItemID.UNIDENTIFIED_RANARR, 2L)).withExtraContainers(extra);
        Wealth.State restored = WealthSync.decode(WealthSync.encode(original, gson), "fixture", gson);
        Wealth.Entry rune = restored.latest.items.get(ItemID.BLOODRUNE);
        assertEquals(5, rune.bankPouch); assertEquals(2, rune.bankSack);
        assertEquals(13L, restored.bank.quantities.get(ItemID.BLOODRUNE).longValue());
        assertEquals(1, restored.latest.items.get(ItemID.TWPART1).deployed);
        assertEquals(original.latest.total(), restored.latest.total());
        assertEquals(original.extraContainers, restored.extraContainers);
        assertEquals(original.herbSack, restored.herbSack);
    }

    @Test
    public void decompressionIsBoundedEvenForASmallCompressedPayload() throws Exception
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) { gzip.write(new byte[WealthSync.MAX_DECODED + 1]); }
        String bomb = Base64.getEncoder().encodeToString(bytes.toByteArray());
        assertTrue(bomb.length() < WealthSync.MAX_ENCODED);
        expectDecodeFailure(bomb, "fixture");
    }

    @Test
    public void exceedingTestBudgetRejectsExportWithoutTrimmingLocalData() throws Exception
    {
        Random random = new Random(42);
        List<Wealth.Point> points = new ArrayList<>();
        for (int i = 1; i < 5000; i++)
        { points.add(new Wealth.Point(i, random.nextInt(1_000_000_000), random.nextInt(1_000_000_000), 900, random.nextInt(1_000_000_000), random.nextInt(1_000_000_000))); }
        points.add(new Wealth.Point(5000, 1, 0, 1, 0, 0));
        Map<Integer, Wealth.Entry> entries = Collections.singletonMap(1,
            new Wealth.Entry(1, "Fixture", 1, 0, 0, 1, 0, true, true, false));
        Wealth.State large = new Wealth.State("fixture", new Wealth.Snapshot(5000, 5000, entries), points, 1);
        String before = archive(large);
        try { WealthSync.encode(large, gson); fail("Oversized export accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("size budget")); }
        assertEquals(before, archive(large));
    }

    @Test
    public void aRepresentativeBankAndDayOfHistoryFitsThePrototypeBudget() throws Exception
    {
        Map<Integer, Wealth.Entry> entries = new LinkedHashMap<>();
        for (int i = 1; i <= 900; i++)
        { entries.put(i, new Wealth.Entry(i, "Fixture item " + i, 10 + i, 0, 0, 100 + i, 50, true, true, false)); }
        List<Wealth.Point> points = new ArrayList<>();
        Wealth.Snapshot snapshot = new Wealth.Snapshot(288 * 300_000L, 288 * 300_000L, entries);
        for (int i = 1; i <= 288; i++)
        { points.add(new Wealth.Point(i * 300_000L, snapshot.total(), snapshot.alch(), 900, i * 7, -i * 7)); }
        Wealth.State fixture = new Wealth.State("fixture", snapshot, points, 1);
        String encoded = WealthSync.encode(fixture, gson);
        assertTrue(encoded.length() < WealthSync.MAX_ENCODED);
        Wealth.State decoded = WealthSync.decode(encoded, "fixture", gson);
        assertEquals(900, decoded.latest.items.size()); assertEquals(288, decoded.history.size());
        assertEquals(fixture.latest.total(), decoded.latest.total());
        System.out.println("Representative sync payload: " + encoded.length() + " characters (fixture only)");
    }

    private void expectDecodeFailure(String encoded, String profile) throws Exception
    {
        try { WealthSync.decode(encoded, profile, gson); fail("Invalid sync data accepted"); }
        catch (IOException expected) { }
    }
}
