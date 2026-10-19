package com.atlas;

import net.runelite.api.Item;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.WidgetUtil;

/** Resolve either item-on-container direction using item IDs, not the combined menu target text. */
final class ContainerUse
{
    static int inventoryItem(int component, int widgetItem, int slot, Item[] inventory)
    {
        int group = WidgetUtil.componentToInterface(component);
        if (group != InterfaceID.INVENTORY && group != InterfaceID.INVENTORY_NOOPS && group != InterfaceID.BANKSIDE)
        { return -1; } // Selected spells can also have an index; it is not an inventory slot.
        if (widgetItem > 0) { return widgetItem; }
        return inventory != null && slot >= 0 && slot < inventory.length ? inventory[slot].getId() : -1;
    }

    static int container(int selected, int target)
    {
        if (selected <= 0 || target <= 0 || isContainer(selected) == isContainer(target)) { return -1; }
        return isContainer(selected) ? selected : target;
    }

    static int storedItem(int selected, int target)
    {
        int container = container(selected, target);
        return container < 0 ? -1 : selected == container ? target : selected;
    }

    private static boolean isContainer(int id)
    {
        if (HerbSackTracker.isSack(id)) { return true; }
        for (BagTracker.Kind kind : BagTracker.Kind.values()) { if (kind.owns(id)) { return true; } }
        return false;
    }
}
