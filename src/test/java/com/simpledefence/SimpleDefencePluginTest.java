package com.simpledefence;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class SimpleDefencePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(SimpleDefencePlugin.class);
		RuneLite.main(args);
	}
}
