package com.atlas;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;

/** Read the account's shared pouch contents once, even if multiple pouch variants are owned. */
final class RunePouchContents
{
    private static final int[] TYPES = {
        VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_TYPE_2, VarbitID.RUNE_POUCH_TYPE_3,
        VarbitID.RUNE_POUCH_TYPE_4, VarbitID.RUNE_POUCH_TYPE_5, VarbitID.RUNE_POUCH_TYPE_6
    };
    private static final int[] AMOUNTS = {
        VarbitID.RUNE_POUCH_QUANTITY_1, VarbitID.RUNE_POUCH_QUANTITY_2, VarbitID.RUNE_POUCH_QUANTITY_3,
        VarbitID.RUNE_POUCH_QUANTITY_4, VarbitID.RUNE_POUCH_QUANTITY_5, VarbitID.RUNE_POUCH_QUANTITY_6
    };
    private static final int[] POUCHES = {
        ItemID.BH_RUNE_POUCH, ItemID.BH_RUNE_POUCH_TROUVER,
        ItemID.DIVINE_RUNE_POUCH, ItemID.DIVINE_RUNE_POUCH_TROUVER
    };

    static Map<Integer, Long> read(IntUnaryOperator varbit, IntUnaryOperator runeItem)
    {
        Map<Integer, Long> result = new LinkedHashMap<>();
        for (int slot = 0; slot < TYPES.length; slot++)
        {
            int type = varbit.applyAsInt(TYPES[slot]);
            int amount = varbit.applyAsInt(AMOUNTS[slot]);
            if (type <= 0 || amount <= 0) { continue; }
            int id = runeItem.applyAsInt(type);
            if (id > 0) { result.merge(id, (long) amount, Long::sum); }
        }
        return result;
    }

    /** Add to the pouch's location. Return its bank contribution so disk checkpoints stay raw. */
    static Map<Integer, Long> include(Map<Integer, long[]> holdings, Map<Integer, Long> contents)
    {
        int location = -1;
        for (int id : POUCHES)
        {
            long[] quantity = holdings.get(id);
            if (quantity == null) { continue; }
            if (quantity[1] > 0) { location = 1; break; }
            if (quantity[0] > 0) { location = 0; }
        }
        // Varbits can retain contents after losing a pouch. Never count an unowned pouch.
        if (location < 0) { return Collections.emptyMap(); }
        for (Map.Entry<Integer, Long> rune : contents.entrySet())
        {
            holdings.computeIfAbsent(rune.getKey(), ignored -> new long[3])[location] += rune.getValue();
        }
        return location == 0 ? contents : Collections.emptyMap();
    }
}
