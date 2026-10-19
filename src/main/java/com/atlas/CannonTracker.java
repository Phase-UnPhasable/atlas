package com.atlas;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import net.runelite.api.Item;
import net.runelite.api.gameval.ItemID;

/** The player's cannon assembly varp represents owned pieces outside the inventory. */
final class CannonTracker
{
    static final String STORAGE_KEY = "deployedCannon";
    private static final int[][] PARTS = {
        {ItemID.TWPART1, ItemID.LEAGUE_3_MULTICANNON_BASE},
        {ItemID.TWPART2, ItemID.LEAGUE_3_MULTICANNON_STAND},
        {ItemID.TWPART3, ItemID.LEAGUE_3_MULTICANNON_BARRELS},
        {ItemID.TWPART4, ItemID.LEAGUE_3_MULTICANNON_FURNACE}
    };
    private Map<Integer, Long> deployed = new LinkedHashMap<>();
    private Map<Integer, Long> previousInventory = new LinkedHashMap<>();
    private boolean estimated, reconcile;

    void reset()
    { deployed.clear(); previousInventory.clear(); estimated = false; reconcile = false; }

    void restore(Map<Integer, Long> saved)
    {
        if (saved == null || !deployed.isEmpty()) { return; }
        saved.forEach((id, quantity) -> { if (isPart(id) && quantity == 1) { deployed.put(id, 1L); } });
        estimated = !deployed.isEmpty();
    }

    Map<Integer, Long> deployed() { return Collections.unmodifiableMap(deployed); }
    boolean estimated() { return estimated; }
    boolean takeReconciliation() { boolean result = reconcile; reconcile = false; return result; }

    void observe(int assembly, boolean decayed, Item[] inventory, IntUnaryOperator canonicalize)
    {
        if (inventory == null || assembly < 0 || assembly > PARTS.length) { return; }
        Map<Integer, Long> currentInventory = new LinkedHashMap<>();
        for (Item item : inventory)
        {
            if (item.getId() < 0 || item.getQuantity() <= 0) { continue; }
            int id = canonicalize.applyAsInt(item.getId());
            if (isPart(id)) { currentInventory.merge(id, (long) item.getQuantity(), Long::sum); }
        }
        // A decayed cannon remains reclaimable; despawning from the scene is not a sale/loss.
        int count = decayed ? PARTS.length : assembly;
        Map<Integer, Long> next = new LinkedHashMap<>();
        boolean unknown = false;
        for (int component = 0; component < count; component++)
        {
            int chosen = -1;
            for (int variant : PARTS[component])
            { if (deployed.containsKey(variant)) { chosen = variant; break; } }
            if (chosen < 0)
            {
                for (int variant : PARTS[component])
                {
                    if (previousInventory.getOrDefault(variant, 0L) > currentInventory.getOrDefault(variant, 0L))
                    { chosen = variant; break; }
                }
            }
            if (chosen < 0)
            {
                // Login with a deployed cannon and no saved skin: use the ordinary piece as an estimate.
                chosen = PARTS[component][0]; unknown = true;
            }
            next.put(chosen, 1L);
        }
        if (estimated && count < deployed.size())
        {
            // Discovering a previously unknown skin on pickup is a correction, not earned GP.
            reconcile = currentInventory.entrySet().stream().anyMatch(item ->
                item.getValue() > previousInventory.getOrDefault(item.getKey(), 0L) && !deployed.containsKey(item.getKey()));
        }
        estimated = count > 0 && (unknown || estimated);
        deployed = next;
        previousInventory = currentInventory;
    }

    static boolean isPart(int id)
    {
        for (int[] variants : PARTS) { for (int variant : variants) { if (id == variant) { return true; } } }
        return false;
    }
}
