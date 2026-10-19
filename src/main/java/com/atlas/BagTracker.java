package com.atlas;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.Item;
import net.runelite.api.gameval.ItemID;

/** Separate contents for every gem container; seed names are resolved through the item cache. */
final class BagTracker
{
    enum Kind
    {
        GEM_BAG("gem bag", ItemID.GEM_BAG, ItemID.GEM_BAG_OPEN, 60, false),
        GEM_POUCH("gem pouch", ItemID.GEM_POUCH, ItemID.GEM_POUCH_OPEN, 5, true),
        GEM_SATCHEL("gem satchel", ItemID.GEM_SATCHEL, ItemID.GEM_SATCHEL_OPEN, 10, true),
        GEM_TOTE("gem tote", ItemID.GEM_TOTE, ItemID.GEM_TOTE_OPEN, 20, true),
        GEM_SACK("gem sack", ItemID.GEM_SACK, ItemID.GEM_SACK_OPEN, 60, true),
        SEED_BOX("seed box", ItemID.SEED_BOX, ItemID.SEED_BOX_OPEN, 0, false);

        final String name;
        final int closed, open, capacity;
        final boolean semiPrecious;
        Kind(String name, int closed, int open, int capacity, boolean semiPrecious)
        { this.name = name; this.closed = closed; this.open = open; this.capacity = capacity; this.semiPrecious = semiPrecious; }
        boolean owns(int id) { return id == closed || id == open; }
        boolean seeds() { return this == SEED_BOX; }
    }

    private static final Map<String, Integer> GEMS = new LinkedHashMap<>();
    static
    {
        GEMS.put("opal", ItemID.UNCUT_OPAL);
        GEMS.put("jade", ItemID.UNCUT_JADE);
        GEMS.put("red topaz", ItemID.UNCUT_RED_TOPAZ);
        GEMS.put("sapphire", ItemID.UNCUT_SAPPHIRE);
        GEMS.put("emerald", ItemID.UNCUT_EMERALD);
        GEMS.put("ruby", ItemID.UNCUT_RUBY);
        GEMS.put("diamond", ItemID.UNCUT_DIAMOND);
        GEMS.put("dragonstone", ItemID.UNCUT_DRAGONSTONE);
    }
    private static final Pattern SEED_LINE = Pattern.compile("([\\d,]+)\\s*x\\s*(.+?)\\.?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEED_PICKUP = Pattern.compile("You put ([\\d,]+) x (.+?) straight into your open seed box\\.", Pattern.CASE_INSENSITIVE);
    private static final Pattern GEM_ADDED = Pattern.compile("You put a gem in your (gem (?:bag|pouch|satchel|tote|sack)): Uncut ([A-Za-z ]+)\\.?", Pattern.CASE_INSENSITIVE);
    final Kind kind;
    private final ToIntFunction<String> resolve;
    private final IntPredicate seedItem;
    private final ContainerTransfer transfer = new ContainerTransfer();
    private Map<Integer, Long> contents;
    private Map<Integer, Long> checking;
    private boolean checkResponse, confirmed, reconcile, inferred;
    private int checkTick;
    private final Map<Integer, Long> candidates = new LinkedHashMap<>();
    private final Map<Integer, Long> explicitAcquisitions = new LinkedHashMap<>();
    private Map<Integer, Long> previousInventory = new LinkedHashMap<>();

    BagTracker(Kind kind, ToIntFunction<String> resolve, IntPredicate seedItem)
    { this.kind = kind; this.resolve = resolve; this.seedItem = seedItem; }

    void reset()
    {
        contents = null; checking = null; confirmed = false; reconcile = false; inferred = false;
        cancelTransfer(); previousInventory.clear(); candidates.clear(); explicitAcquisitions.clear();
    }
    void restore(Map<Integer, Long> saved)
    { if (contents == null && saved != null) { contents = new LinkedHashMap<>(saved); } }
    Map<Integer, Long> contents() { return contents == null ? null : Collections.unmodifiableMap(contents); }
    boolean confirmed() { return confirmed; }
    long removeStored(int id, long quantity)
    {
        if (contents == null) { return 0; }
        long removed = Math.min(quantity, contents.getOrDefault(id, 0L));
        add(id, -removed);
        return removed;
    }

    static Map<Integer, Long> combinedContents(HerbSackTracker herbs, boolean includeHerbs,
        List<BagTracker> bags, Set<Kind> eligible)
    {
        Map<Integer, Long> result = new LinkedHashMap<>();
        if (includeHerbs && herbs.contents() != null) { result.putAll(herbs.contents()); }
        for (BagTracker bag : bags)
        {
            if (eligible.contains(bag.kind) && bag.contents != null)
            { bag.contents.forEach((id, quantity) -> result.merge(id, quantity, Long::sum)); }
        }
        return result;
    }

    static void settleBulkDeposit(Map<Integer, Long> looseChanges, Map<Integer, Long> beforeStored,
        HerbSackTracker herbs, boolean includeHerbs, List<BagTracker> bags, Set<Kind> eligible)
    {
        Map<Integer, Long> current = combinedContents(herbs, includeHerbs, bags, eligible);
        looseChanges.forEach((id, delta) ->
        {
            // An explicit "now empty" message may already have removed part of this bank gain.
            long alreadyRemoved = Math.max(0, beforeStored.getOrDefault(id, 0L) - current.getOrDefault(id, 0L));
            long remaining = Math.max(0, -delta - alreadyRemoved);
            if (includeHerbs) { remaining -= herbs.removeStored(id, remaining); }
            for (BagTracker bag : bags)
            { if (eligible.contains(bag.kind)) { remaining -= bag.removeStored(id, remaining); } }
            long original = beforeStored.getOrDefault(id, 0L);
            if (-delta < original && eligible.size() > 1)
            {
                // A shared partial bank delta cannot prove which overlapping bag kept each gem.
                for (BagTracker bag : bags)
                { if (eligible.contains(bag.kind) && bag.accepts(id)) { bag.invalidate(); } }
            }
        });
    }
    boolean estimated() { return !confirmed || inferred; }
    void invalidate() { confirmed = false; cancelTransfer(); }
    void cancelTransfer() { transfer.cancel(); }
    boolean takeReconciliation() { boolean result = reconcile; reconcile = false; return result; }

    boolean accepts(int id)
    {
        if (id <= 0) { return false; }
        if (kind.seeds()) { return seedItem.test(id); }
        if (!GEMS.containsValue(id)) { return false; }
        boolean semi = id == ItemID.UNCUT_OPAL || id == ItemID.UNCUT_JADE || id == ItemID.UNCUT_RED_TOPAZ;
        return kind == Kind.GEM_SACK || (kind.semiPrecious == semi);
    }

    void menu(String option, Item[] inventory, Item[] bank, int tick)
    {
        if (option.equalsIgnoreCase("Check"))
        {
            checking = new LinkedHashMap<>(); checkResponse = false; checkTick = tick;
        }
        transfer.begin(option, inventory, bank, tick, this::accepts);
    }

    void beginItemUse(int id, Item[] inventory, Item[] bank, int tick)
    {
        transfer.beginItemUse(id, inventory, bank, tick, this::accepts);
    }

    boolean finishTransfer(Item[] inventory, Item[] bank, int tick)
    {
        Map<Integer, Long> changes = transfer.finish(inventory, bank, tick, this::accepts);
        if (changes.isEmpty()) { return false; }
        changes.forEach(this::add);
        return true;
    }

    private void add(int id, long delta)
    {
        if (contents == null) { contents = new LinkedHashMap<>(); }
        long quantity = Math.max(0, contents.getOrDefault(id, 0L) + delta);
        if (quantity == 0) { contents.remove(id); } else { contents.put(id, quantity); }
    }

    boolean chat(String message, int tick)
    {
        String lower = message.toLowerCase(Locale.ROOT);
        if (kind.seeds())
        {
            if (lower.equals("the seed box contains:"))
            { checking = new LinkedHashMap<>(); checkResponse = true; checkTick = tick; return false; }
            if (lower.equals("the seed box is empty."))
            { checking = new LinkedHashMap<>(); checkResponse = true; checkTick = tick; return false; }
            if (lower.equals("your seed box is now empty."))
            { contents = new LinkedHashMap<>(); transfer.cancel(); confirmed = true; return true; }
            Matcher pickup = SEED_PICKUP.matcher(message);
            if (pickup.matches())
            {
                int id = resolve.applyAsInt(pickup.group(2));
                if (id > 0) { add(id, number(pickup.group(1))); return true; }
            }
            if (checking != null && checkResponse)
            {
                Matcher line = SEED_LINE.matcher(message);
                if (line.matches())
                {
                    int id = resolve.applyAsInt(line.group(2));
                    long quantity = number(line.group(1));
                    if (id > 0 && quantity > 0) { checking.put(id, quantity); }
                }
            }
            // Stored/Emptied messages accompany loose-container transfers. The diff is counted once.
            return false;
        }
        Matcher added = GEM_ADDED.matcher(message);
        if (added.matches() && added.group(1).equalsIgnoreCase(kind.name))
        {
            Integer id = GEMS.get(added.group(2).toLowerCase(Locale.ROOT));
            if (id != null && accepts(id) && !transfer.filling())
            { add(id, 1); explicitAcquisitions.merge(id, 1L, Long::sum); return true; }
        }
        Matcher stolen = Pattern.compile("(?:You put the stolen Uncut (.+?) into your |The following stolen loot gets added to your )"
            + Pattern.quote(kind.name) + "(?:\\.|: Uncut (.+?) x ([\\d,]+))", Pattern.CASE_INSENSITIVE).matcher(message);
        if (stolen.matches())
        {
            int id = gemId(stolen.group(1) != null ? stolen.group(1) : stolen.group(2));
            long quantity = stolen.group(3) == null ? 1 : number(stolen.group(3));
            if (accepts(id)) { add(id, quantity); explicitAcquisitions.merge(id, quantity, Long::sum); return true; }
        }
        if (checking != null && tick - checkTick <= 8)
        {
            for (Map.Entry<String, Integer> gem : GEMS.entrySet())
            {
                if (!accepts(gem.getValue())) { continue; }
                String plural = gem.getKey().equals("ruby") ? "ruby|rubies" : gem.getKey() + "s?";
                Matcher line = Pattern.compile("\\b(?:uncut )?(?:" + plural + ")\\s*:\\s*([\\d,]+)", Pattern.CASE_INSENSITIVE).matcher(message);
                if (line.find())
                {
                    long quantity = number(line.group(1));
                    if (quantity > 0) { checking.put(gem.getValue(), quantity); }
                    checkResponse = true;
                }
            }
            if (lower.equals("the " + kind.name + " is empty.") || lower.equals("the gem bag is empty."))
            { checkResponse = true; }
        }
        // Explicit server confirmations do not depend on the exact menu label.
        if (lower.equals("you empty your " + kind.name + " into the bank.")
            || lower.equals("the " + kind.name + " is now empty."))
        { contents = new LinkedHashMap<>(); transfer.cancel(); confirmed = true; return true; }
        return false;
    }

    boolean finishCheck(int tick)
    {
        if (checking == null) { return false; }
        if (tick - checkTick > 8) { checking = null; return false; }
        if (!checkResponse) { return false; }
        contents = checking; checking = null; confirmed = true; inferred = false; reconcile = true;
        transfer.cancel();
        return true;
    }

    boolean observeSeeds(Item[] items)
    {
        Map<Integer, Long> actual = new LinkedHashMap<>();
        for (Item item : items)
        { if (item.getId() > 0 && item.getQuantity() > 0) { actual.merge(item.getId(), (long) item.getQuantity(), Long::sum); } }
        if (confirmed && actual.equals(contents)) { return false; }
        reconcile |= !confirmed;
        contents = actual; confirmed = true; transfer.cancel();
        return true;
    }

    int location(Map<Integer, long[]> holdings)
    {
        int location = -1;
        for (Map.Entry<Integer, long[]> entry : holdings.entrySet())
        {
            if (!kind.owns(entry.getKey())) { continue; }
            if (entry.getValue()[1] > 0) { return 1; }
            if (entry.getValue()[0] > 0) { location = 0; }
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

    boolean canAcquire(int id, Item[] inventory)
    {
        if (kind.seeds() || !confirmed || !accepts(id) || contents.getOrDefault(id, 0L) >= kind.capacity) { return false; }
        for (Item item : inventory) { if (item.getId() == kind.open && item.getQuantity() > 0) { return true; } }
        return false;
    }

    void candidate(int id, long quantity) { candidates.merge(id, quantity, Long::sum); }

    /** Mining and matched ground pickups can be inferred only when no loose gem was received. */
    boolean settleAcquisitions(Item[] inventory)
    {
        Map<Integer, Long> next = new LinkedHashMap<>();
        for (Item item : inventory)
        { if (accepts(item.getId()) && item.getQuantity() > 0) { next.merge(item.getId(), (long) item.getQuantity(), Long::sum); } }
        boolean changed = false;
        for (Map.Entry<Integer, Long> candidate : candidates.entrySet())
        {
            int id = candidate.getKey();
            long looseGain = Math.max(0, next.getOrDefault(id, 0L) - previousInventory.getOrDefault(id, 0L));
            long quantity = Math.min(kind.capacity - contents.getOrDefault(id, 0L), Math.max(0,
                candidate.getValue() - looseGain - explicitAcquisitions.getOrDefault(id, 0L)));
            if (quantity > 0 && canAcquire(id, inventory)) { add(id, quantity); inferred = true; changed = true; }
        }
        previousInventory = next; candidates.clear(); explicitAcquisitions.clear();
        return changed;
    }

    static int gemId(String name)
    { return GEMS.getOrDefault(name.toLowerCase(Locale.ROOT).replace("uncut ", "").trim(), -1); }
    private static long number(String quantity) { return Long.parseLong(quantity.replace(",", "")); }
}
