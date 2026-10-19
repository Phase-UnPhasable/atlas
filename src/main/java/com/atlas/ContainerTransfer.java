package com.atlas;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntPredicate;
import net.runelite.api.Item;

/** A transfer changes loose quantities across BOTH locations, regardless of its menu label. */
final class ContainerTransfer
{
    private Map<Integer, Long> before;
    private boolean fill, includeBank;
    private int started;
    private int usedItem = -1;

    void cancel() { before = null; usedItem = -1; }
    boolean pending() { return before != null; }
    boolean filling() { return pending() && fill; }

    void begin(String option, Item[] inventory, Item[] bank, int tick, IntPredicate accepts)
    {
        cancel();
        String action = option.toLowerCase(Locale.ROOT).replace('-', ' ').trim();
        fill = action.equals("fill");
        if (inventory == null || !(fill || action.equals("empty") || action.equals("empty to bank")
            || action.startsWith("deposit") || action.startsWith("withdraw"))) { return; }
        includeBank = bank != null;
        before = count(inventory, includeBank ? bank : null, accepts);
        started = tick;
    }

    void beginItemUse(int id, Item[] inventory, Item[] bank, int tick, IntPredicate accepts)
    {
        cancel();
        if (id <= 0 || !accepts.test(id)) { return; }
        begin("Fill", inventory, bank, tick, accepts);
        if (!pending()) { return; }
        usedItem = id;
        before.keySet().removeIf(item -> item != id);
    }

    Map<Integer, Long> finish(Item[] inventory, Item[] bank, int tick, IntPredicate accepts)
    {
        Map<Integer, Long> changes = new LinkedHashMap<>();
        if (!pending()) { return changes; }
        if (tick - started > 8) { cancel(); return changes; }
        if (inventory == null || (includeBank && bank == null)) { return changes; }
        Map<Integer, Long> after = count(inventory, includeBank ? bank : null,
            id -> (usedItem < 0 || id == usedItem) && accepts.test(id));
        Map<Integer, Long> ids = new LinkedHashMap<>(before);
        after.forEach(ids::putIfAbsent);
        ids.forEach((id, ignored) ->
        {
            long delta = after.getOrDefault(id, 0L) - before.getOrDefault(id, 0L);
            if ((fill && delta < 0) || (!fill && delta > 0)) { changes.put(id, -delta); }
        });
        if (!changes.isEmpty()) { cancel(); }
        return changes;
    }

    private static Map<Integer, Long> count(Item[] inventory, Item[] bank, IntPredicate accepts)
    {
        Map<Integer, Long> result = new LinkedHashMap<>();
        for (Item[] items : new Item[][]{inventory, bank})
        {
            if (items == null) { continue; }
            for (Item item : items)
            {
                if (item.getQuantity() > 0 && accepts.test(item.getId()))
                { result.merge(item.getId(), (long) item.getQuantity(), Long::sum); }
            }
        }
        return result;
    }
}
