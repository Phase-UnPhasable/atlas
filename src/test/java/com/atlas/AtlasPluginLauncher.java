package com.atlas;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class AtlasPluginLauncher
{
    public static void main(String[] args) throws Exception
    {
        if (args.length == 1 && args[0].equals("--atlas-self-test"))
        {
            System.out.println("Atlas development package launcher OK. Java " + System.getProperty("java.version"));
            System.out.println("No connection to RuneLite, RuneScape or cloud services was made.");
            return;
        }
        ExternalPluginManager.loadBuiltin(AtlasPlugin.class);
        RuneLite.main(args);
    }
}
