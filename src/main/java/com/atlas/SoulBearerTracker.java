package com.atlas;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.Item;
import net.runelite.api.gameval.ItemID;

/** Match Soul Bearer actions to observed item changes; clicking alone never books a cost. */
final class SoulBearerTracker
{
    static final String REMOTE_BANK_KEY = "soulBearerRemoteBank";
    private enum Action { SEND, CHARGE, UNCHARGE }
    private Action action;
    private Map<Integer, Long> before;
    private boolean includeBank;
    private int started;

    void cancel() { action = null; before = null; }

    void menu(String option, int itemId, Item[] inventory, Item[] bank, int tick)
    {
        if (itemId != ItemID.ARCEUUS_SOULBEARER)
        {
            // A subsequent item action (deposit/drop/cast/etc.) cannot complete an earlier send.
            if (itemId > 0 || option.equalsIgnoreCase("Cast")) { cancel(); }
            return;
        }
        if (option.equalsIgnoreCase("Fill") || option.equalsIgnoreCase("Bank-All"))
        { begin(Action.SEND, inventory, bank, tick, -1); }
        else if (option.equalsIgnoreCase("Uncharge")) { begin(Action.UNCHARGE, inventory, bank, tick, -1); }
        else if (option.equalsIgnoreCase("Configure-Charges")) { begin(Action.CHARGE, inventory, bank, tick, -1); }
        else { cancel(); }
    }

    void use(int selected, int target, Item[] inventory, Item[] bank, int tick)
    {
        cancel();
        int other = selected == ItemID.ARCEUUS_SOULBEARER ? target
            : target == ItemID.ARCEUUS_SOULBEARER ? selected : -1;
        if (isHead(other)) { begin(Action.SEND, inventory, bank, tick, canonicalHead(other)); }
        else if (other == ItemID.BLOODRUNE || other == ItemID.SOULRUNE)
        { begin(Action.CHARGE, inventory, bank, tick, -1); }
    }

    private void begin(Action next, Item[] inventory, Item[] bank, int tick, int usedHead)
    {
        cancel();
        if (inventory == null || (next == Action.SEND && (bank != null || !hasBearer(inventory)))) { return; }
        action = next; includeBank = bank != null; started = tick;
        before = count(inventory, includeBank ? bank : null, action == Action.SEND);
        if (usedHead > 0) { before.keySet().removeIf(id -> id != usedHead); }
    }

    Result finish(Item[] inventory, Item[] bank, int tick)
    {
        if (action == null) { return Result.NONE; }
        // Allow the normal confirmation/quantity dialogue without guessing its outcome.
        if (tick - started > 100 || (action == Action.SEND && bank != null)) { cancel(); return Result.NONE; }
        if (inventory == null || (includeBank && bank == null)) { return Result.NONE; }
        if (action == Action.SEND && !hasBearer(inventory)) { cancel(); return Result.NONE; }
        Map<Integer, Long> after = count(inventory, includeBank ? bank : null, action == Action.SEND);
        if (action == Action.SEND)
        {
            Map<Integer, Long> heads = new LinkedHashMap<>();
            before.forEach((id, quantity) ->
            {
                long sent = quantity - after.getOrDefault(id, 0L);
                if (sent > 0) { heads.put(id, sent); }
            });
            if (heads.isEmpty()) { return Result.NONE; }
            cancel(); return new Result(heads, 0);
        }
        long blood = before.getOrDefault(ItemID.BLOODRUNE, 0L) - after.getOrDefault(ItemID.BLOODRUNE, 0L);
        long soul = before.getOrDefault(ItemID.SOULRUNE, 0L) - after.getOrDefault(ItemID.SOULRUNE, 0L);
        // Configure-Charges supports both adding and removing charges; both rune deltas must agree.
        long charges = blood > 0 && soul > 0 && action == Action.CHARGE ? Math.min(blood, soul)
            : blood < 0 && soul < 0 ? -Math.min(-blood, -soul) : 0;
        if (charges == 0) { return Result.NONE; }
        cancel(); return new Result(Collections.emptyMap(), charges);
    }

    static final class Result
    {
        static final Result NONE = new Result(Collections.emptyMap(), 0);
        final Map<Integer, Long> heads;
        // Positive = loose runes loaded, negative = loose runes returned on uncharging.
        final long chargeTransfer;
        Result(Map<Integer, Long> heads, long chargeTransfer) { this.heads = heads; this.chargeTransfer = chargeTransfer; }
        long spent() { return heads.values().stream().mapToLong(Long::longValue).sum(); }
    }

    private static boolean hasBearer(Item[] inventory)
    {
        for (Item item : inventory)
        { if (item.getId() == ItemID.ARCEUUS_SOULBEARER && item.getQuantity() > 0) { return true; } }
        return false;
    }

    private static Map<Integer, Long> count(Item[] inventory, Item[] bank, boolean heads)
    {
        Map<Integer, Long> result = new LinkedHashMap<>();
        for (Item[] items : new Item[][]{inventory, bank})
        {
            if (items == null) { continue; }
            for (Item item : items)
            {
                int id = item.getId();
                if (item.getQuantity() > 0 && (heads ? isHead(id) : id == ItemID.BLOODRUNE || id == ItemID.SOULRUNE))
                { result.merge(canonicalHead(id), (long) item.getQuantity(), Long::sum); }
            }
        }
        return result;
    }

    static boolean isHead(int id)
    {
        for (int[] pair : HEADS) { if (id == pair[0] || id == pair[1]) { return true; } }
        return false;
    }

    static int canonicalHead(int id)
    {
        for (int[] pair : HEADS) { if (id == pair[0]) { return pair[1]; } }
        return id;
    }

    private static final int[][] HEADS = {
        {ItemID.ARCEUUS_CORPSE_GOBLIN_INITIAL, ItemID.ARCEUUS_CORPSE_GOBLIN},
        {ItemID.ARCEUUS_CORPSE_MONKEY_INITIAL, ItemID.ARCEUUS_CORPSE_MONKEY},
        {ItemID.ARCEUUS_CORPSE_IMP_INITIAL, ItemID.ARCEUUS_CORPSE_IMP},
        {ItemID.ARCEUUS_CORPSE_MINOTAUR_INITIAL, ItemID.ARCEUUS_CORPSE_MINOTAUR},
        {ItemID.ARCEUUS_CORPSE_SCORPION_INITIAL, ItemID.ARCEUUS_CORPSE_SCORPION},
        {ItemID.ARCEUUS_CORPSE_BEAR_INITIAL, ItemID.ARCEUUS_CORPSE_BEAR},
        {ItemID.ARCEUUS_CORPSE_UNICORN_INITIAL, ItemID.ARCEUUS_CORPSE_UNICORN},
        {ItemID.ARCEUUS_CORPSE_DOG_INITIAL, ItemID.ARCEUUS_CORPSE_DOG},
        {ItemID.ARCEUUS_CORPSE_CHAOSDRUID_INITIAL, ItemID.ARCEUUS_CORPSE_CHAOSDRUID},
        {ItemID.ARCEUUS_CORPSE_GIANT_INITIAL, ItemID.ARCEUUS_CORPSE_GIANT},
        {ItemID.ARCEUUS_CORPSE_OGRE_INITIAL, ItemID.ARCEUUS_CORPSE_OGRE},
        {ItemID.ARCEUUS_CORPSE_ELF_INITIAL, ItemID.ARCEUUS_CORPSE_ELF},
        {ItemID.ARCEUUS_CORPSE_TROLL_INITIAL, ItemID.ARCEUUS_CORPSE_TROLL},
        {ItemID.ARCEUUS_CORPSE_HORROR_INITIAL, ItemID.ARCEUUS_CORPSE_HORROR},
        {ItemID.ARCEUUS_CORPSE_KALPHITE_INITIAL, ItemID.ARCEUUS_CORPSE_KALPHITE},
        {ItemID.ARCEUUS_CORPSE_DAGANNOTH_INITIAL, ItemID.ARCEUUS_CORPSE_DAGANNOTH},
        {ItemID.ARCEUUS_CORPSE_BLOODVELD_INITIAL, ItemID.ARCEUUS_CORPSE_BLOODVELD},
        {ItemID.ARCEUUS_CORPSE_TZHAAR_INITIAL, ItemID.ARCEUUS_CORPSE_TZHAAR},
        {ItemID.ARCEUUS_CORPSE_DEMON_INITIAL, ItemID.ARCEUUS_CORPSE_DEMON},
        {ItemID.ARCEUUS_CORPSE_AVIANSIE_INITIAL, ItemID.ARCEUUS_CORPSE_AVIANSIE},
        {ItemID.ARCEUUS_CORPSE_ABYSSAL_INITIAL, ItemID.ARCEUUS_CORPSE_ABYSSAL},
        {ItemID.ARCEUUS_CORPSE_DRAGON_INITIAL, ItemID.ARCEUUS_CORPSE_DRAGON},
        {ItemID.ARCEUUS_CORPSE_HELLHOUND_INITIAL, ItemID.ARCEUUS_CORPSE_HELLHOUND}
    };
}
