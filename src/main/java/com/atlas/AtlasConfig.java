package com.atlas;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(WealthSync.GROUP)
public interface AtlasConfig extends Config
{
    @ConfigItem(keyName = "syncExperimental", name = "Cross-device sync",
        description = "Share bank and completed-session history through your RuneLite account. Keeps local backups.")
    default boolean syncExperimental() { return false; }

    @ConfigItem(keyName = "showSessionValue", name = "Show session value",
        description = "Show session gains/losses above the inventory tab. Tracking continues when hidden.")
    default boolean showSessionValue() { return true; }
}
