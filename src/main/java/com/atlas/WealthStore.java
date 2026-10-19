package com.atlas;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.client.util.Filepath;

/** Explicit JSON fields; never Java object serialization or account credentials. */
final class WealthStore
{
    private final Gson gson;
    private final Map<String, Long> written = new HashMap<>();

    WealthStore(Gson gson) { this.gson = gson; }

    synchronized Wealth.State load(Filepath directory, String key) throws IOException
    {
        directory.createDirectories();
        Filepath file = directory.joinSegment(key + ".json");
        if (!file.exists()) { return Wealth.State.empty(key); }
        try (Reader reader = file.openBufferedReader())
        {
            Wealth.State state = decode(key, gson.fromJson(reader, JsonObject.class));
            written.put(key, state.revision);
            return state;
        }
        catch (RuntimeException ex)
        {
            // Keep the original file for recovery. The caller must NOT overwrite it.
            throw new IOException("History could not be read; original file kept", ex);
        }
    }

    synchronized String deviceId(Filepath directory) throws IOException
    {
        directory.createDirectories();
        Filepath file = directory.joinSegment("sync-device-id.txt");
        if (file.exists())
        {
            try (Reader reader = file.openBufferedReader())
            {
                char[] chars = new char[64]; int length = reader.read(chars);
                String id = length < 0 ? "" : new String(chars, 0, length).trim();
                if (id.matches("[a-f0-9]{32}")) { return id; }
                throw new IOException("Invalid sync device ID");
            }
        }
        String id = java.util.UUID.randomUUID().toString().replace("-", "");
        file.write(id);
        return id;
    }

    synchronized void backupBeforeSync(Filepath directory, Wealth.State state) throws IOException
    {
        directory.createDirectories();
        Filepath backup = directory.joinSegment(state.profile + ".before-sync.json");
        if (!backup.exists()) { backup.write(gson.toJson(encode(state))); }
    }

    synchronized void save(Filepath directory, Wealth.State state) throws IOException
    {
        if (state.revision <= written.getOrDefault(state.profile, -1L)) { return; }
        directory.createDirectories();
        Filepath file = directory.joinSegment(state.profile + ".json");
        Filepath temp = directory.joinSegment(state.profile + ".pending.json");
        temp.write(gson.toJson(encode(state)));
        try { temp.moveTo(file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ex) { temp.moveTo(file, StandardCopyOption.REPLACE_EXISTING); }
        written.put(state.profile, state.revision);
    }

    static JsonObject encode(Wealth.State state)
    {
        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        root.addProperty("revision", state.revision);
        JsonObject containers = new JsonObject();
        state.extraContainers.forEach((key, items) ->
        {
            JsonArray quantities = new JsonArray();
            items.forEach((id, quantity) ->
            {
                JsonObject item = new JsonObject();
                item.addProperty("id", id); item.addProperty("quantity", quantity); quantities.add(item);
            });
            containers.add(key, quantities);
        });
        root.add("extraContainers", containers);
        if (state.herbSack != null)
        {
            JsonArray herbs = new JsonArray();
            state.herbSack.forEach((id, quantity) ->
            {
                JsonObject herb = new JsonObject();
                herb.addProperty("id", id);
                herb.addProperty("quantity", quantity);
                herbs.add(herb);
            });
            root.add("herbSack", herbs);
        }
        if (state.bank != null)
        {
            JsonObject bank = new JsonObject();
            bank.addProperty("time", state.bank.time);
            JsonArray quantities = new JsonArray();
            state.bank.quantities.forEach((id, quantity) ->
            {
                JsonObject holding = new JsonObject();
                holding.addProperty("id", id);
                holding.addProperty("quantity", quantity);
                quantities.add(holding);
            });
            bank.add("quantities", quantities);
            root.add("bankCheckpoint", bank);
        }
        if (state.latest != null)
        {
            JsonObject snapshot = new JsonObject();
            snapshot.addProperty("time", state.latest.time);
            snapshot.addProperty("bankTime", state.latest.bankTime);
            JsonArray entries = new JsonArray();
            for (Wealth.Entry e : state.latest.items.values())
            {
                JsonObject item = new JsonObject();
                item.addProperty("id", e.id);
                item.addProperty("name", e.name);
                item.addProperty("bank", e.bank);
                item.addProperty("bankPouch", e.bankPouch);
                item.addProperty("bankSack", e.bankSack);
                item.addProperty("inventory", e.inventory);
                item.addProperty("equipment", e.equipment);
                item.addProperty("deployed", e.deployed);
                item.addProperty("price", e.price);
                item.addProperty("alch", e.alch);
                item.addProperty("members", e.members);
                item.addProperty("tradeable", e.tradeable);
                item.addProperty("unpriced", e.unpriced);
                entries.add(item);
            }
            snapshot.add("entries", entries);
            root.add("snapshot", snapshot);
        }
        JsonArray points = new JsonArray();
        for (Wealth.Point p : state.history)
        {
            JsonObject point = new JsonObject();
            point.addProperty("time", p.time);
            point.addProperty("total", p.total);
            point.addProperty("alch", p.alch);
            point.addProperty("unique", p.unique);
            point.addProperty("market", p.market);
            point.addProperty("items", p.items);
            point.addProperty("attributed", p.attributed);
            points.add(point);
        }
        root.add("history", points);
        JsonArray sessions = new JsonArray();
        for (Wealth.Session session : state.sessions) { sessions.add(encodeSession(session)); }
        root.add("sessions", sessions);
        if (state.activeSession != null) { root.add("activeSession", encodeSession(state.activeSession)); }
        return root;
    }

    private static JsonObject encodeSession(Wealth.Session session)
    {
        JsonObject object = new JsonObject();
        object.addProperty("start", session.start);
        object.addProperty("end", session.end);
        object.addProperty("change", session.change);
        object.addProperty("estimated", session.estimated);
        object.addProperty("interrupted", session.interrupted);
        return object;
    }

    private static Wealth.Session decodeSession(JsonObject object)
    {
        long start = nonnegative(object, "start"), end = nonnegative(object, "end");
        if (end < start) { throw new IllegalArgumentException("Invalid session dates"); }
        return new Wealth.Session(start, end, object.get("change").getAsLong(),
            object.get("estimated").getAsBoolean(), object.get("interrupted").getAsBoolean());
    }

    static Wealth.State decode(String key, JsonObject root)
    {
        if (root == null || root.get("schema").getAsInt() != 1) { throw new IllegalArgumentException("Unknown history format"); }
        Wealth.Snapshot snapshot = null;
        if (root.has("snapshot"))
        {
            JsonObject raw = root.getAsJsonObject("snapshot");
            Map<Integer, Wealth.Entry> entries = new LinkedHashMap<>();
            for (JsonElement value : raw.getAsJsonArray("entries"))
            {
                JsonObject e = value.getAsJsonObject();
                int id = e.get("id").getAsInt();
                Wealth.Entry entry = new Wealth.Entry(id, e.get("name").getAsString(),
                    nonnegative(e, "bank"), nonnegative(e, "inventory"), nonnegative(e, "equipment"),
                    nonnegative(e, "price"), nonnegative(e, "alch"), e.get("members").getAsBoolean(),
                    e.get("tradeable").getAsBoolean(), e.get("unpriced").getAsBoolean(),
                    e.has("bankPouch") ? nonnegative(e, "bankPouch") : 0,
                    e.has("bankSack") ? nonnegative(e, "bankSack") : 0,
                    e.has("deployed") ? nonnegative(e, "deployed") : 0);
                if (entry.bankPouch > entry.bank || entry.bankSack > entry.bank - entry.bankPouch)
                { throw new IllegalArgumentException("Invalid bank container quantity"); }
                if (entry.deployed > 1 || (entry.deployed > 0 && !CannonTracker.isPart(id)))
                { throw new IllegalArgumentException("Invalid deployed cannon piece"); }
                if (id < 0 || entry.quantity() == 0 || entries.put(id, entry) != null) { throw new IllegalArgumentException("Invalid holding"); }
            }
            snapshot = new Wealth.Snapshot(nonnegative(raw, "time"), nonnegative(raw, "bankTime"), entries);
        }
        List<Wealth.Point> history = new ArrayList<>();
        long lastTime = -1;
        for (JsonElement value : root.getAsJsonArray("history"))
        {
            JsonObject p = value.getAsJsonObject();
            long time = nonnegative(p, "time");
            if (time < lastTime) { throw new IllegalArgumentException("Unordered history"); }
            history.add(new Wealth.Point(time, nonnegative(p, "total"), nonnegative(p, "alch"),
                nonnegative(p, "unique"), p.get("market").getAsLong(), p.get("items").getAsLong(),
                !p.has("attributed") || p.get("attributed").getAsBoolean()));
            lastTime = time;
        }
        if ((snapshot == null) != history.isEmpty()) { throw new IllegalArgumentException("Missing history baseline"); }
        if (snapshot != null && (snapshot.time != lastTime || snapshot.total() != history.get(history.size() - 1).total))
        { throw new IllegalArgumentException("Inconsistent snapshot"); }
        // Older files have no checkpoint; recover just their bank quantities, never their carried gear.
        Wealth.Bank bank = Wealth.Bank.from(snapshot);
        if (root.has("bankCheckpoint"))
        {
            JsonObject rawBank = root.getAsJsonObject("bankCheckpoint");
            Map<Integer, Long> quantities = new LinkedHashMap<>();
            for (JsonElement value : rawBank.getAsJsonArray("quantities"))
            {
                JsonObject holding = value.getAsJsonObject();
                int id = holding.get("id").getAsInt();
                long quantity = nonnegative(holding, "quantity");
                if (id < 0 || quantity == 0 || quantities.put(id, quantity) != null)
                { throw new IllegalArgumentException("Invalid bank checkpoint"); }
            }
            long time = nonnegative(rawBank, "time");
            if (time == 0) { throw new IllegalArgumentException("Missing bank observation time"); }
            if (bank == null || time >= bank.time) { bank = new Wealth.Bank(time, quantities); }
        }
        List<Wealth.Session> sessions = new ArrayList<>();
        if (root.has("sessions"))
        {
            for (JsonElement value : root.getAsJsonArray("sessions")) { sessions.add(decodeSession(value.getAsJsonObject())); }
        }
        Wealth.Session active = root.has("activeSession") ? decodeSession(root.getAsJsonObject("activeSession")) : null;
        Map<Integer, Long> herbSack = null;
        if (root.has("herbSack"))
        {
            herbSack = new LinkedHashMap<>();
            for (JsonElement value : root.getAsJsonArray("herbSack"))
            {
                JsonObject herb = value.getAsJsonObject();
                int id = herb.get("id").getAsInt();
                long quantity = nonnegative(herb, "quantity");
                if (id < 0 || quantity == 0 || herbSack.put(id, quantity) != null)
                { throw new IllegalArgumentException("Invalid herb sack contents"); }
            }
        }
        Map<String, Map<Integer, Long>> extraContainers = new LinkedHashMap<>();
        if (root.has("extraContainers"))
        {
            root.getAsJsonObject("extraContainers").entrySet().forEach(container ->
            {
                Map<Integer, Long> items = new LinkedHashMap<>();
                for (JsonElement value : container.getValue().getAsJsonArray())
                {
                    JsonObject item = value.getAsJsonObject();
                    int id = item.get("id").getAsInt();
                    long quantity = nonnegative(item, "quantity");
                    if (id < 0 || quantity == 0 || items.put(id, quantity) != null)
                    { throw new IllegalArgumentException("Invalid stored container quantity"); }
                }
                extraContainers.put(container.getKey(), items);
            });
        }
        return new Wealth.State(key, snapshot, history, nonnegative(root, "revision"), bank, sessions, active, herbSack, extraContainers);
    }

    private static long nonnegative(JsonObject object, String field)
    {
        long value = object.get(field).getAsLong();
        if (value < 0) { throw new IllegalArgumentException("Negative " + field); }
        return value;
    }
}
