package com.atlas;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.Item;
import net.runelite.api.gameval.ItemID;

/** Observe server chat and completed container transfers, never infer success from a click alone. */
final class HerbSackTracker
{
    private static final Pattern PICKUP = Pattern.compile(
        "You put the (Grimy [A-Za-z ]+) herb into your (?:silklined )?herb sack\\.", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHECK_LINE = Pattern.compile("([0-9,]+)\\s*x\\s*(Grimy [A-Za-z ]+)", Pattern.CASE_INSENSITIVE);
    private static final Map<String, Integer> HERBS = new LinkedHashMap<>();
    static
    {
        HERBS.put("grimy guam leaf", ItemID.UNIDENTIFIED_GUAM);
        HERBS.put("grimy marrentill", ItemID.UNIDENTIFIED_MARENTILL);
        HERBS.put("grimy tarromin", ItemID.UNIDENTIFIED_TARROMIN);
        HERBS.put("grimy harralander", ItemID.UNIDENTIFIED_HARRALANDER);
        HERBS.put("grimy ranarr weed", ItemID.UNIDENTIFIED_RANARR);
        HERBS.put("grimy toadflax", ItemID.UNIDENTIFIED_TOADFLAX);
        HERBS.put("grimy irit leaf", ItemID.UNIDENTIFIED_IRIT);
        HERBS.put("grimy avantoe", ItemID.UNIDENTIFIED_AVANTOE);
        HERBS.put("grimy kwuarm", ItemID.UNIDENTIFIED_KWUARM);
        HERBS.put("grimy snapdragon", ItemID.UNIDENTIFIED_SNAPDRAGON);
        HERBS.put("grimy cadantine", ItemID.UNIDENTIFIED_CADANTINE);
        HERBS.put("grimy lantadyme", ItemID.UNIDENTIFIED_LANTADYME);
        HERBS.put("grimy dwarf weed", ItemID.UNIDENTIFIED_DWARF_WEED);
        HERBS.put("grimy torstol", ItemID.UNIDENTIFIED_TORSTOL);
        HERBS.put("grimy huasca", ItemID.UNIDENTIFIED_HUASCA);
    }

    private Map<Integer, Long> contents;
    private Map<Integer, Long> check;
    private boolean confirmed, reconcile;
    private boolean legacyBankTransfer;
    private final ContainerTransfer combinedTransfer = new ContainerTransfer();

    void reset() { contents = null; check = null; confirmed = false; reconcile = false; cancelTransfer(); }
    void restore(Map<Integer, Long> saved)
    {
        if (contents == null && saved != null) { contents = new LinkedHashMap<>(saved); }
    }
    Map<Integer, Long> contents() { return contents == null ? null : Collections.unmodifiableMap(contents); }
    boolean confirmed() { return confirmed; }
    long removeStored(int id, long quantity)
    {
        if (contents == null) { return 0; }
        long removed = Math.min(quantity, contents.getOrDefault(id, 0L));
        long left = contents.getOrDefault(id, 0L) - removed;
        if (left == 0) { contents.remove(id); } else { contents.put(id, left); }
        return removed;
    }
    void invalidate() { confirmed = false; cancelTransfer(); }
    boolean takeReconciliation() { boolean result = reconcile; reconcile = false; return result; }
    void cancelTransfer() { combinedTransfer.cancel(); }

    void beginTransfer(String option, Item[] inventory, Item[] bank, int tick)
    {
        cancelTransfer();
        combinedTransfer.begin(option, inventory, bank, tick, HERBS::containsValue);
    }

    void beginItemUse(int id, Item[] inventory, Item[] bank, int tick)
    {
        combinedTransfer.beginItemUse(id, inventory, bank, tick, HERBS::containsValue);
    }

    boolean finishTransfer(Item[] inventory, Item[] bank, int tick)
    {
        Map<Integer, Long> changes = combinedTransfer.finish(inventory, bank, tick, HERBS::containsValue);
        if (changes.isEmpty()) { return false; }
        if (contents == null) { contents = new LinkedHashMap<>(); }
        changes.forEach((id, delta) ->
        {
            long quantity = Math.max(0, contents.getOrDefault(id, 0L) + delta);
            if (quantity == 0) { contents.remove(id); } else { contents.put(id, quantity); }
        });
        return true;
    }

    boolean chat(String message)
    {
        if (message.equalsIgnoreCase("The herb sack is empty.") || message.equalsIgnoreCase("The silklined herb sack is empty."))
        {
            check = new LinkedHashMap<>();
            return false;
        }
        if (message.equalsIgnoreCase("You look in your herb sack and see:")
            || message.equalsIgnoreCase("You look in your silklined herb sack and see:"))
        {
            check = new LinkedHashMap<>();
            return false;
        }
        Matcher line = CHECK_LINE.matcher(message);
        if (check != null && line.matches())
        {
            Integer id = HERBS.get(line.group(2).toLowerCase(Locale.ROOT));
            if (id != null)
            {
                long quantity = Long.parseLong(line.group(1).replace(",", ""));
                if (quantity > 0) { check.put(id, quantity); }
            }
            return false;
        }
        Matcher pickup = PICKUP.matcher(message);
        if (!pickup.matches() || combinedTransfer.filling()) { return false; }
        Integer id = HERBS.get(pickup.group(1).toLowerCase(Locale.ROOT));
        if (id == null) { return false; }
        if (contents == null) { contents = new LinkedHashMap<>(); }
        contents.merge(id, 1L, Long::sum);
        return true;
    }

    /** Check's full message batch is committed together before a wealth reading. */
    boolean finishCheck()
    {
        if (check == null) { return false; }
        contents = check;
        check = null;
        confirmed = true;
        reconcile = true; // Discovering/correcting old sack contents is not new session loot.
        cancelTransfer();
        return true;
    }

    void beginTransfer(String option, Item[] items, int tick)
    {
        legacyBankTransfer = option.equals("Empty to bank");
        beginTransfer(option, legacyBankTransfer ? new Item[0] : items, legacyBankTransfer ? items : null, tick);
    }

    boolean transferringToBank() { return legacyBankTransfer; }

    boolean finishTransfer(Item[] after, int tick)
    {
        return finishTransfer(legacyBankTransfer ? new Item[0] : after, legacyBankTransfer ? after : null, tick);
    }

    static boolean isSack(int id)
    {
        return id == ItemID.SLAYER_HERB_SACK || id == ItemID.SLAYER_HERB_SACK_OPEN
            || id == ItemID.SLAYER_HERB_SACK_SILK || id == ItemID.SLAYER_HERB_SACK_SILK_OPEN;
    }

    static int location(Map<Integer, long[]> holdings)
    {
        int location = -1;
        for (Map.Entry<Integer, long[]> item : holdings.entrySet())
        {
            if (!isSack(item.getKey())) { continue; }
            if (item.getValue()[1] > 0) { return 1; }
            if (item.getValue()[0] > 0) { location = 0; }
        }
        return location;
    }

    Map<Integer, Long> include(Map<Integer, long[]> holdings)
    {
        int location = location(holdings);
        if (location < 0 || contents == null) { return Collections.emptyMap(); }
        contents.forEach((id, quantity) -> holdings.computeIfAbsent(id, ignored -> new long[3])[location] += quantity);
        return location == 0 ? contents : Collections.emptyMap();
    }
}
