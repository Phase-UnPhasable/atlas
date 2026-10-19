package com.atlas;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Bounded experimental RuneLite-config payloads and deterministic, non-destructive history merge. */
final class WealthSync
{
    static final String GROUP = "atlas";
    static final String PREFIX = "historyV1_";
    // Prototype budgets, not a claim about RuneLite server quotas. Never trim local history to fit.
    static final int MAX_ENCODED = 32_000;
    static final int MAX_DECODED = 4_000_000;

    static String encode(Wealth.State state, Gson gson) throws IOException
    {
        // A running session belongs to its device and is shared only once it has been closed/recovered.
        Wealth.State completed = new Wealth.State(state.profile, state.latest, state.history, 0, state.bank,
            state.sessions, null, state.herbSack, state.extraContainers);
        JsonObject envelope = new JsonObject();
        envelope.addProperty("syncSchema", 1);
        envelope.addProperty("profile", state.profile);
        envelope.addProperty("compact", true);
        envelope.add("state", pack(WealthStore.encode(completed)));
        byte[] plain = gson.toJson(envelope).getBytes(StandardCharsets.UTF_8);
        if (plain.length > MAX_DECODED) { throw new IOException("History exceeds the test sync size budget"); }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) { gzip.write(plain); }
        String encoded = Base64.getEncoder().encodeToString(bytes.toByteArray());
        if (encoded.length() > MAX_ENCODED) { throw new IOException("History exceeds the test sync size budget"); }
        return encoded;
    }

    static Wealth.State decode(String encoded, String profile, Gson gson) throws IOException
    {
        if (encoded == null || encoded.length() > MAX_ENCODED) { throw new IOException("Invalid sync payload size"); }
        try
        {
            ByteArrayOutputStream plain = new ByteArrayOutputStream();
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded))))
            {
                byte[] buffer = new byte[4096]; int count;
                while ((count = gzip.read(buffer)) != -1)
                {
                    if (plain.size() + count > MAX_DECODED) { throw new IOException("Expanded sync payload is too large"); }
                    plain.write(buffer, 0, count);
                }
            }
            JsonObject root = gson.fromJson(new String(plain.toByteArray(), StandardCharsets.UTF_8), JsonObject.class);
            if (root == null || root.get("syncSchema").getAsInt() != 1) { throw new IOException("Unknown sync version"); }
            if (!profile.equals(root.get("profile").getAsString())) { throw new IOException("Sync character does not match"); }
            JsonObject data = root.getAsJsonObject("state");
            Wealth.State state = WealthStore.decode(profile, root.has("compact") && root.get("compact").getAsBoolean() ? unpack(data) : data);
            if (state.activeSession != null) { throw new IOException("Sync payload contains a running session"); }
            return state;
        }
        catch (RuntimeException ex) { throw new IOException("Invalid sync payload", ex); }
    }

    /** Numeric rows avoid repeating field names and item labels; live capture resolves item names. */
    private static JsonObject pack(JsonObject state)
    {
        if (state.has("snapshot"))
        {
            JsonObject snapshot = state.getAsJsonObject("snapshot");
            JsonArray rows = new JsonArray();
            for (JsonElement element : snapshot.getAsJsonArray("entries"))
            {
                JsonObject item = element.getAsJsonObject();
                JsonArray row = row(item, "id", "bank", "inventory", "equipment", "price", "alch", "bankPouch", "bankSack", "deployed");
                row.add((item.get("members").getAsBoolean() ? 1 : 0) | (item.get("tradeable").getAsBoolean() ? 2 : 0)
                    | (item.get("unpriced").getAsBoolean() ? 4 : 0));
                rows.add(row);
            }
            snapshot.remove("entries"); snapshot.add("rows", rows);
        }
        JsonArray points = new JsonArray();
        for (JsonElement element : state.getAsJsonArray("history"))
        { points.add(row(element.getAsJsonObject(), "time", "total", "alch", "unique", "market", "items", "attributed")); }
        state.remove("history"); state.add("points", points);
        JsonArray sessions = new JsonArray();
        for (JsonElement element : state.getAsJsonArray("sessions"))
        { sessions.add(row(element.getAsJsonObject(), "start", "end", "change", "estimated", "interrupted")); }
        state.add("sessions", sessions);
        if (state.has("bankCheckpoint"))
        {
            JsonObject bank = state.getAsJsonObject("bankCheckpoint");
            bank.add("quantities", packQuantities(bank.getAsJsonArray("quantities")));
        }
        if (state.has("herbSack")) { state.add("herbSack", packQuantities(state.getAsJsonArray("herbSack"))); }
        state.getAsJsonObject("extraContainers").entrySet().forEach(e ->
            e.setValue(packQuantities(e.getValue().getAsJsonArray())));
        return state;
    }

    private static JsonObject unpack(JsonObject state)
    {
        if (state.has("snapshot"))
        {
            JsonObject snapshot = state.getAsJsonObject("snapshot");
            JsonArray entries = new JsonArray();
            for (JsonElement element : snapshot.getAsJsonArray("rows"))
            {
                JsonArray row = element.getAsJsonArray();
                if (row.size() != 10) { throw new IllegalArgumentException("Invalid item row"); }
                JsonObject item = object(row, "id", "bank", "inventory", "equipment", "price", "alch", "bankPouch", "bankSack", "deployed");
                int flags = row.get(9).getAsInt();
                item.addProperty("members", (flags & 1) != 0); item.addProperty("tradeable", (flags & 2) != 0);
                item.addProperty("unpriced", (flags & 4) != 0);
                item.addProperty("name", "Item #" + item.get("id").getAsInt());
                entries.add(item);
            }
            snapshot.remove("rows"); snapshot.add("entries", entries);
        }
        JsonArray points = new JsonArray();
        for (JsonElement row : state.getAsJsonArray("points"))
        { points.add(object(row.getAsJsonArray(), "time", "total", "alch", "unique", "market", "items", "attributed")); }
        state.remove("points"); state.add("history", points);
        JsonArray sessions = new JsonArray();
        for (JsonElement row : state.getAsJsonArray("sessions"))
        { sessions.add(object(row.getAsJsonArray(), "start", "end", "change", "estimated", "interrupted")); }
        state.add("sessions", sessions);
        if (state.has("bankCheckpoint"))
        {
            JsonObject bank = state.getAsJsonObject("bankCheckpoint");
            bank.add("quantities", unpackQuantities(bank.getAsJsonArray("quantities")));
        }
        if (state.has("herbSack")) { state.add("herbSack", unpackQuantities(state.getAsJsonArray("herbSack"))); }
        state.getAsJsonObject("extraContainers").entrySet().forEach(e ->
            e.setValue(unpackQuantities(e.getValue().getAsJsonArray())));
        return state;
    }

    private static JsonArray row(JsonObject object, String... fields)
    {
        JsonArray row = new JsonArray();
        for (String field : fields) { row.add(object.get(field)); }
        return row;
    }

    private static JsonObject object(JsonArray row, String... fields)
    {
        if (row.size() < fields.length) { throw new IllegalArgumentException("Truncated sync row"); }
        JsonObject object = new JsonObject();
        for (int i = 0; i < fields.length; i++) { object.add(fields[i], row.get(i)); }
        return object;
    }

    private static JsonArray packQuantities(JsonArray quantities)
    {
        JsonArray rows = new JsonArray();
        for (JsonElement item : quantities) { rows.add(row(item.getAsJsonObject(), "id", "quantity")); }
        return rows;
    }

    private static JsonArray unpackQuantities(JsonArray rows)
    {
        JsonArray quantities = new JsonArray();
        for (JsonElement row : rows) { quantities.add(object(row.getAsJsonArray(), "id", "quantity")); }
        return quantities;
    }

    static Wealth.State merge(Wealth.State local, List<Wealth.State> remote, boolean importHoldings)
    {
        List<Wealth.State> sources = new ArrayList<>(); sources.add(local); sources.addAll(remote);
        Map<Long, Sample> samples = new TreeMap<>();
        Map<Long, Wealth.Session> sessions = new TreeMap<>();
        Wealth.Snapshot latest = local.latest;
        Wealth.State holdings = local;
        Wealth.State bankOwner = local;
        for (Wealth.State source : sources)
        {
            if (!local.profile.equals(source.profile)) { throw new IllegalArgumentException("Wrong sync character"); }
            Wealth.Point previous = null;
            for (Wealth.Point point : source.history)
            {
                Sample sample = new Sample(point, previous);
                samples.merge(point.time, sample, WealthSync::chooseSample);
                previous = point;
            }
            for (Wealth.Session session : source.sessions)
            {
                // Never import an older checkpoint over this device's still-running session.
                if (local.activeSession == null || session.start != local.activeSession.start)
                { sessions.merge(session.start, session, WealthSync::chooseSession); }
            }
            if (source.latest != null && (latest == null || compareSnapshots(source.latest, latest) > 0))
            { latest = source.latest; }
            if (holdingsTime(source) > holdingsTime(holdings)) { holdings = source; }
            if (source.bank != null && (bankOwner.bank == null || source.bank.time > bankOwner.bank.time
                || (source.bank.time == bankOwner.bank.time && holdingsTime(source) > holdingsTime(bankOwner))))
            { bankOwner = source; }
        }
        List<Wealth.Point> points = new ArrayList<>();
        Wealth.Point previous = null;
        long market = 0, items = 0;
        for (Sample sample : samples.values())
        {
            Wealth.Point p = sample.point;
            boolean known = previous == null || (sample.previousTime == previous.time && p.attributed);
            if (previous != null)
            {
                long marketDelta = known ? sample.marketDelta : 0;
                market += marketDelta;
                items += p.total - previous.total - marketDelta;
            }
            previous = new Wealth.Point(p.time, p.total, p.alch, p.unique, market, items, known);
            points.add(previous);
        }
        if (latest != null && !points.isEmpty())
        {
            // The final snapshot and the final graph endpoint must represent the same observation.
            Wealth.Point last = points.get(points.size() - 1);
            if (latest.time != last.time || latest.total() != last.total)
            { throw new IllegalArgumentException("Inconsistent merged history endpoint"); }
        }
        Wealth.State carry = importHoldings ? holdings : local;
        Wealth.Bank bank = importHoldings ? bankOwner.bank : local.bank;
        Map<String, Map<Integer, Long>> extra = new LinkedHashMap<>(carry.extraContainers);
        if (importHoldings && bank != null && carry.bank == null && holdingsTime(carry) > bank.time)
        {
            Map<Integer, Long> credits = extra.get(SoulBearerTracker.REMOTE_BANK_KEY);
            if (credits != null && !credits.isEmpty())
            {
                Map<Integer, Long> quantities = new LinkedHashMap<>(bank.quantities);
                credits.forEach((id, quantity) -> quantities.merge(id, quantity, Long::sum));
                bank = new Wealth.Bank(bank.time, quantities);
                extra.put(SoulBearerTracker.REMOTE_BANK_KEY, java.util.Collections.emptyMap());
            }
        }
        return new Wealth.State(local.profile, latest, points, local.revision + 1, bank,
            new ArrayList<>(sessions.values()), local.activeSession, carry.herbSack, extra);
    }

    private static long holdingsTime(Wealth.State state)
    {
        long time = state.latest == null ? 0 : state.latest.time;
        if (state.bank != null) { time = Math.max(time, state.bank.time); }
        if (state.activeSession != null) { time = Math.max(time, state.activeSession.end); }
        for (Wealth.Session session : state.sessions) { time = Math.max(time, session.end); }
        return time;
    }

    private static int compareSnapshots(Wealth.Snapshot a, Wealth.Snapshot b)
    {
        int time = Long.compare(a.time, b.time);
        if (time != 0) { return time; }
        int total = Long.compare(a.total(), b.total());
        if (total != 0) { return total; }
        int alch = Long.compare(a.alch(), b.alch());
        return alch != 0 ? alch : Integer.compare(a.items.size(), b.items.size());
    }

    private static Wealth.Session chooseSession(Wealth.Session a, Wealth.Session b)
    {
        Comparator<Wealth.Session> order = Comparator.comparingLong((Wealth.Session s) -> s.end)
            .thenComparing(s -> !s.interrupted).thenComparingLong(s -> s.change).thenComparing(s -> s.estimated);
        return order.compare(a, b) >= 0 ? a : b;
    }

    private static Sample chooseSample(Sample a, Sample b)
    {
        Comparator<Sample> order = Comparator.comparingLong((Sample s) -> s.point.total)
            .thenComparingLong(s -> s.point.alch).thenComparingLong(s -> s.point.unique)
            .thenComparingLong(s -> s.previousTime).thenComparing(s -> s.point.attributed)
            .thenComparingLong(s -> s.marketDelta);
        return order.compare(a, b) >= 0 ? a : b;
    }

    private static final class Sample
    {
        final Wealth.Point point;
        final long previousTime, marketDelta;
        Sample(Wealth.Point point, Wealth.Point previous)
        {
            this.point = point; previousTime = previous == null ? -1 : previous.time;
            marketDelta = previous == null ? 0 : point.market - previous.market;
        }
    }
}
