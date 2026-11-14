package com.simpledefence;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(SimpleDefenceConfig.GROUP)
public interface SimpleDefenceConfig extends Config
{
	String GROUP = "simpledefence";

	@ConfigItem(
		keyName = "syncParty",
		name = "Sync with party",
		description = "Share defence drains with your RuneLite party (the same party used by Party Panel)",
		position = 0
	)
	default boolean syncParty()
	{
		return true;
	}

	@Range(min = 0, max = 200)
	@ConfigItem(
		keyName = "heightOffset",
		name = "Height offset",
		description = "How far above the NPC's head the overlay is drawn",
		position = 3
	)
	default int heightOffset()
	{
		return 40;
	}

	@Range(min = 1, max = 25)
	@ConfigItem(
		keyName = "outOfSightMinutes",
		name = "Out of sight (minutes)",
		description = "How long a drained NPC stays in the Simple Defence Tracker panel (and keeps its drain) after leaving view",
		position = 4
	)
	default int outOfSightMinutes()
	{
		return 5;
	}

	@ConfigSection(
		name = "Specs to drain",
		description = "Simple Defence Tracker panel (Alt + drag to move): specs (DWH / Elder maul) or hitsplats (Tonalztics) needed to fully drain each NPC",
		position = 10
	)
	String specSection = "specSection";

	@ConfigItem(
		keyName = "showElderMaul",
		name = "Elder maul",
		description = "Show specs needed with the Elder maul",
		section = specSection,
		position = 11
	)
	default boolean showElderMaul()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showDwh",
		name = "Dragon warhammer",
		description = "Show specs needed with the Dragon warhammer",
		section = specSection,
		position = 12
	)
	default boolean showDwh()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showTonalztics",
		name = "Tonalztics of Ralos",
		description = "Show hitsplats needed with Tonalztics of Ralos (2 per spec)",
		section = specSection,
		position = 13
	)
	default boolean showTonalztics()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showBgs",
		name = "Bandos godsword",
		description = "Show damage needed with the Bandos godsword (drain = damage dealt)",
		section = specSection,
		position = 14
	)
	default boolean showBgs()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showArclight",
		name = "Arclight",
		description = "Show specs needed with Arclight (5% of base Defence + 1; demon bonus not counted)",
		section = specSection,
		position = 15
	)
	default boolean showArclight()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showEmberlight",
		name = "Emberlight",
		description = "Show specs needed with Emberlight (5% of base Defence + 1; demon bonus not counted)",
		section = specSection,
		position = 16
	)
	default boolean showEmberlight()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showBoneDagger",
		name = "Bone dagger",
		description = "Show damage needed with the Bone dagger (only while Defence is undrained)",
		section = specSection,
		position = 17
	)
	default boolean showBoneDagger()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showAnchor",
		name = "Barrelchest anchor",
		description = "Show damage needed with the Barrelchest anchor (drain = 10% of damage)",
		section = specSection,
		position = 18
	)
	default boolean showAnchor()
	{
		return false;
	}

	@ConfigSection(
		name = "Stats above head",
		description = "Which drained stats are shown above the NPC's head",
		position = 5
	)
	String statsSection = "statsSection";

	@ConfigItem(
		keyName = "headDefence",
		name = "Defence",
		description = "Show remaining/total Defence drain (red -> green) once a drain has been attempted",
		section = statsSection,
		position = 8
	)
	default boolean headDefence()
	{
		return true;
	}

	@ConfigItem(
		keyName = "headMagic",
		name = "Magic",
		description = "Show remaining/total Magic (red -> green) once it has been drained",
		section = statsSection,
		position = 10
	)
	default boolean headMagic()
	{
		return true;
	}

	@ConfigItem(
		keyName = "headAttack",
		name = "Attack",
		description = "Show remaining/total Attack (red -> green) once it has been drained",
		section = statsSection,
		position = 6
	)
	default boolean headAttack()
	{
		return false;
	}

	@ConfigItem(
		keyName = "headStrength",
		name = "Strength",
		description = "Show remaining/total Strength (red -> green) once it has been drained",
		section = statsSection,
		position = 7
	)
	default boolean headStrength()
	{
		return false;
	}

	@ConfigItem(
		keyName = "headRanged",
		name = "Ranged",
		description = "Show remaining/total Ranged (red -> green) once it has been drained",
		section = statsSection,
		position = 9
	)
	default boolean headRanged()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showAccursed",
		name = "Accursed sceptre",
		description = "Charged only. Shows 1 while a Condemn spec can still lower Defence (it can only take 15% in total)",
		section = specSection,
		position = 19
	)
	default boolean showAccursed()
	{
		return false;
	}

	@ConfigItem(
		keyName = "headMagicDefence",
		name = "Magic Defence (Eye of Ayak)",
		description = "Charged only. Show -N mdef once the Eye of Ayak has lowered the NPC's Magic Defence bonus",
		section = statsSection,
		position = 11
	)
	default boolean headMagicDefence()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showSeercull",
		name = "Seercull",
		description = "Shows 1 while a Soulshot can still lower Magic (it doesn't stack; only works while Magic is undrained)",
		section = specSection,
		position = 20
	)
	default boolean showSeercull()
	{
		return false;
	}
}
