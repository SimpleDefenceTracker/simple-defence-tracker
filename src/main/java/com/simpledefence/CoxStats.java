package com.simpledefence;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Chambers of Xeric stats. The game data only has placeholder stats (1 Defence) for
 * raid NPCs - the server sets the real ones - so they come from this table instead.
 *
 * Normal-mode values: OSRS Wiki boss pages (scaled for a maxed player).
 * Challenge Mode: every combat stat x1.5 (OSRS Wiki), except Tekton (246 Defence/Magic).
 * CM itself is detected by the plugin from a game value (varp 1432), not NPC IDs -
 * Tekton has the same ID in both modes.
 * Only used while inside the Chambers, so same-named NPCs elsewhere aren't affected.
 */
final class CoxStats
{
	// Order of each entry: {defence, magic, attack, strength, ranged}
	static final int DEF = 0;
	static final int MAG = 1;
	static final int ATT = 2;
	static final int STR = 3;
	static final int RNG = 4;

	/** Normalised name prefix -> normal stats. Claws before head; most specific first. */
	private static final Map<String, int[]> BY_NAME = new LinkedHashMap<>();

	/** NPCs told apart by ID (Muttadile sizes share a name). */
	private static final Map<Integer, int[]> BY_ID = Map.of(
		7561, new int[]{220, 1, 250, 250, 250}, // Muttadile (large)
		7563, new int[]{220, 1, 250, 250, 250}, // Muttadile (large)
		7562, new int[]{138, 1, 150, 150, 150}  // Muttadile (small)
	);

	static
	{
		BY_NAME.put("great olm left claw", new int[]{175, 175, 250, 250, 250});
		BY_NAME.put("great olm right claw", new int[]{175, 87, 250, 250, 250});
		BY_NAME.put("great olm", new int[]{150, 250, 250, 250, 250});
		BY_NAME.put("tekton", new int[]{205, 205, 390, 390, 1});
		BY_NAME.put("vanguard", new int[]{160, 150, 150, 150, 150});
		BY_NAME.put("vasa nistirio", new int[]{175, 230, 1, 1, 230});
		BY_NAME.put("vespula", new int[]{88, 88, 150, 150, 150});
		BY_NAME.put("muttadile", new int[]{138, 1, 150, 150, 150}); // fallback: small
		BY_NAME.put("ice demon", new int[]{160, 390, 1, 1, 390});
		BY_NAME.put("lizardman shaman", new int[]{210, 130, 130, 130, 130});
		BY_NAME.put("skeletal mystic", new int[]{187, 140, 140, 140, 1});
		BY_NAME.put("guardian", new int[]{100, 1, 140, 140, 1});
	}

	/** Tekton's Challenge Mode Defence and Magic (not x1.5). */
	private static final int TEKTON_CM_DEFENCE = 246;

	private CoxStats()
	{
	}

	/**
	 * @param cm true in a Challenge Mode raid
	 * @return {defence, magic, attack, strength, ranged}, or null if not a known CoX NPC
	 */
	static int[] lookup(int npcId, String npcName, boolean cm)
	{
		int[] normal = BY_ID.get(npcId);
		String name = normalise(npcName);
		if (normal == null)
		{
			for (Map.Entry<String, int[]> e : BY_NAME.entrySet())
			{
				if (name.startsWith(e.getKey()))
				{
					normal = e.getValue();
					break;
				}
			}
		}
		if (normal == null)
		{
			return null;
		}

		if (!cm)
		{
			return normal.clone();
		}

		int[] out = new int[5];
		for (int i = 0; i < 5; i++)
		{
			out[i] = normal[i] * 3 / 2;
		}
		if (name.startsWith("tekton"))
		{
			out[DEF] = TEKTON_CM_DEFENCE;
			out[MAG] = TEKTON_CM_DEFENCE;
		}
		return out;
	}

	private static String normalise(String npcName)
	{
		if (npcName == null)
		{
			return "";
		}
		StringBuilder sb = new StringBuilder(npcName.length());
		for (char c : npcName.toLowerCase().toCharArray())
		{
			if ((c >= 'a' && c <= 'z') || c == ' ')
			{
				sb.append(c);
			}
		}
		return sb.toString().trim();
	}
}
