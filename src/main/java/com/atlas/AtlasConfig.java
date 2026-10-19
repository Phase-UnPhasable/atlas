package com.atlas;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(WealthSync.GROUP)
public interface AtlasConfig extends Config
{
    @ConfigItem(keyName = "syncExperimental", name = "Cross-device sync (test)",
        description = "Share bank and completed-session history through your RuneLite account. Keeps local backups. Delivery and storage limits need testing.")
    default boolean syncExperimental() { return false; }
}
