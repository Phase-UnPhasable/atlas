package com.atlas;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntToLongFunction;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.ScriptID;
import net.runelite.api.gameval.VarbitID;

/** Potion storage is measured in doses, independent of the selected withdrawal bottle size. */
final class PotionStorageTracker
{
    static final String STORAGE_KEY = "POTION_STORAGE";
    private final Map<Integer, Variant> variants = new LinkedHashMap<>();
    private Map<Integer, Long> contents = new LinkedHashMap<>();
    private boolean unlocked, observed, reconcile;

    private static final class Variant
    {
        final int base, doses, priceItem, priceDoses;
        Variant(int base, int doses, int priceItem, int priceDoses)
        { this.base = base; this.doses = doses; this.priceItem = priceItem; this.priceDoses = priceDoses; }
    }

    void reset() { variants.clear(); contents.clear(); unlocked = false; observed = false; reconcile = false; }
    void restore(Map<Integer, Long> saved)
    { if (!observed && saved != null) { contents = new LinkedHashMap<>(saved); unlocked = true; } }
    boolean unlocked() { return unlocked; }
    Map<Integer, Long> contents() { return Collections.unmodifiableMap(contents); }
    boolean takeReconciliation() { boolean result = reconcile; reconcile = false; return result; }

    void register(int[] items)
    {
        if (items.length == 0 || items[0] <= 0) { return; }
        int maximum = items.length;
        while (maximum > 1 && items[maximum - 1] <= 0) { maximum--; }
        for (int i = 0; i < maximum; i++)
        {
            if (items[i] > 0)
            { variants.put(items[i], new Variant(items[0], i + 1, items[maximum - 1], maximum)); }
        }
    }

    boolean observe(boolean available, Map<Integer, Long> doses)
    {
        boolean changed = unlocked != available || !contents.equals(doses);
        reconcile |= !observed || unlocked != available;
        observed = true; unlocked = available;
        contents = available ? new LinkedHashMap<>(doses) : new LinkedHashMap<>();
        return changed;
    }

    /** Call only outside the script VM, after login/container updates settle. These scripts only read counts. */
    boolean read(Client client)
    {
        if (client.getVarbitValue(VarbitID.MM_POTION_STORAGE_UNLOCKED) == 0)
        { return observe(false, Collections.emptyMap()); }
        Map<Integer, Long> next = new LinkedHashMap<>();
        for (int enumId : new int[]{EnumID.POTIONSTORE_POTIONS, EnumID.POTIONSTORE_UNFINISHED_POTIONS})
        {
            EnumComposition catalog = client.getEnum(enumId);
            if (catalog == null || catalog.size() == 0) { return false; }
            for (int potionEnumId : catalog.getIntVals())
            {
                EnumComposition potion = client.getEnum(potionEnumId);
                int[] items = new int[enumId == EnumID.POTIONSTORE_UNFINISHED_POTIONS ? 1 : 4];
                for (int i = 0; i < items.length; i++) { items[i] = potion.getIntValue(i + 1); }
                register(items);
                if (items[0] <= 0) { continue; }
                // Same read-only query used by RuneLite's Bank Tags PotionStorage implementation.
                client.runScript(ScriptID.POTIONSTORE_DOSES, potionEnumId);
                int quantity = client.getIntStack()[0];
                if (quantity > 0) { next.put(items[0], (long) quantity); }
            }
        }
        return observe(true, next);
    }

    Map<Integer, Long> include(Map<Integer, long[]> holdings)
    {
        if (!unlocked) { return Collections.emptyMap(); }
        contents.forEach((id, quantity) -> holdings.computeIfAbsent(id, ignored -> new long[3])[0] += quantity);
        return contents();
    }

    long price(int id, IntToLongFunction quote)
    {
        Variant variant = unlocked ? variants.get(id) : null;
        if (variant == null) { return quote.applyAsLong(id); }
        long full = quote.applyAsLong(variant.priceItem);
        long unit = full > 0 ? Math.max(1, Math.round(full / (double) variant.priceDoses))
            : quote.applyAsLong(variant.base);
        return unit * variant.doses;
    }

    /** Fixed session prices must use one identity per potion, so decanting cannot invent gains/losses. */
    Wealth.Snapshot forSession(Wealth.Snapshot snapshot)
    {
        if (!unlocked) { return snapshot; }
        Map<Integer, Wealth.Entry> items = new LinkedHashMap<>();
        for (Wealth.Entry item : snapshot.items.values())
        {
            Variant variant = variants.get(item.id);
            if (variant == null) { items.put(item.id, item); continue; }
            Wealth.Entry old = items.get(variant.base);
            long bank = item.bank * variant.doses + (old == null ? 0 : old.bank);
            long inventory = item.inventory * variant.doses + (old == null ? 0 : old.inventory);
            long equipment = item.equipment * variant.doses + (old == null ? 0 : old.equipment);
            items.put(variant.base, new Wealth.Entry(variant.base, item.name, bank, inventory, equipment,
                item.price / variant.doses, 0, item.members, item.tradeable, item.unpriced));
        }
        return new Wealth.Snapshot(snapshot.time, snapshot.bankTime, items);
    }
}
