package com.tytoowns.osrstrackerbridge;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class ScreenOfKnowledgeBridgePluginTest
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(ScreenOfKnowledgeBridgePlugin.class);
        RuneLite.main(args);
    }
}
