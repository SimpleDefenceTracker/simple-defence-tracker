package com.simpledefence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Defence drain rules.
 *
 * Default: any NPC can be drained to 0, and its drain stays above its head until it dies.
 *
 * Exceptions:
 *  - BOSSES: The Fractured Archive bosses with fixed starting Defence, lowest reachable
 *    Defence and Magic level (OSRS Wiki: "Maximum defence reduction" table + boss infoboxes).
 *  - MAX_REDUCTION: Tombs of Amascut bosses can only lose a fixed amount of Defence.
 *  - RESET_ON_PHASE: Ocellus, the Great Olm and Sotetseg - each phase starts with fresh Defence.
 *  - Theatre of Blood: Sotetseg can't go below 100 Defence; Verzik's Defence can't be lowered.
 */
final class DrainCaps
{
	/** Initial Defence, lowest reachable Defence, and Magic (Tonalztics drains 1/8 of Magic per hit). */
	static final class Limits
	{
		final int initial;
		final int lowest;
		final int magic;

		Limits(int initial, int lowest, int magic)
		{
			this.initial = initial;
			this.lowest = lowest;
			this.magic = magic;
		}
	}

	/** Normalised name prefix -> limits. Checked after the ID table. */
	private static final Map<String, Limits> BOSSES = new LinkedHashMap<>();

	/** Ocellus phases share a name, so they're keyed by NPC ID. */
	private static final Map<Integer, Limits> BOSSES_BY_ID = Map.of(
		16673, new Limits(215, 129, 350), // Ocellus P1
		16674, new Limits(215, 129, 350),
		16675, new Limits(250, 0, 220),   // The Blade (P2)
		16676, new Limits(250, 0, 220),
		16677, new Limits(250, 150, 300)  // Ocellus P3
	);

	static
	{
		// The Fractured Archive - name prefixes (lower case, letters and spaces only)
		BOSSES.put("archive guardian", new Limits(260, 156, 250));
		BOSSES.put("ezekiel", new Limits(200, 120, 200));
		BOSSES.put("bork", new Limits(300, 180, 90));
		BOSSES.put("the smorks", new Limits(220, 0, 185));
		BOSSES.put("moia", new Limits(195, 117, 350));
		BOSSES.put("dreadnoth", new Limits(125, 0, 220));
		BOSSES.put("dagonhai elder", new Limits(100, 0, 290)); // cap removed by bug fix
		BOSSES.put("dagonhai monk", new Limits(100, 0, 290));  // cap removed by bug fix
		BOSSES.put("xarth", new Limits(170, 102, 350));
		// Fallbacks if Ocellus ever appears under an unlisted ID
		BOSSES.put("the blade", new Limits(250, 0, 220));
	}

	/**
	 * Tombs of Amascut: maximum Defence that can be drained (not a floor). The floor is
	 * starting Defence minus this, so it stays right whatever the raid level.
	 * Exact normalised names, so e.g. "Akkha's Shadow" doesn't match "akkha".
	 */
	private static final Map<String, Integer> MAX_REDUCTION = Map.of(
		"zebak", 20,
		"kephri", 20,
		"akkha", 10,           // regenerates quickly - not modelled
		"baba", 20,            // Ba-Ba
		"obelisk", 40,         // Wardens P1
		"tumekens warden", 30, // P3 pre-enrage (60 once enraged)
		"elidinis warden", 30
	);

	/** Wardens P3 once enraged. */
	static final int WARDEN_ENRAGED_MAX_REDUCTION = 60;

	/** @return max Defence that can be drained for this NPC, or -1 if not limited this way */
	static int maxReduction(String npcName, boolean inInstance)
	{
		if (!inInstance)
		{
			return -1;
		}
		Integer r = MAX_REDUCTION.get(normalise(npcName));
		return r == null ? -1 : r;
	}

	static boolean isWarden(String npcName)
	{
		String name = normalise(npcName);
		return name.equals("tumekens warden") || name.equals("elidinis warden");
	}

	/**
	 * Each phase starts fresh (drain is dropped on any form change or despawn):
	 *  - Ocellus: every phase is a new entity.
	 *  - Great Olm (head, left claw, right claw - each its own entity): a new phase starts
	 *    when Olm burrows; once he burrows and the head is untargetable, stats reset.
	 */
	private static final Set<Integer> RESET_ON_PHASE_IDS = BOSSES_BY_ID.keySet();
	private static final String[] RESET_ON_PHASE_NAME_PREFIXES = {"ocellus", "the blade", "great olm",
		"sotetseg"}; // Sotetseg: drains reset each time players go to the shadow realm (maze)

	/**
	 * Theatre of Blood: lowest Defence a boss can be drained to (OSRS Wiki).
	 * Sotetseg: "His defence level can be lowered down to a minimum of 100".
	 */
	private static final Map<String, Integer> MIN_DEFENCE = Map.of(
		"sotetseg", 100
	);

	/** @return fixed lowest Defence for this NPC, or -1 if none */
	static int minDefence(String npcName, boolean inInstance)
	{
		if (!inInstance)
		{
			return -1;
		}
		Integer m = MIN_DEFENCE.get(normalise(npcName));
		return m == null ? -1 : m;
	}

	/** NPCs whose Defence can't be lowered at all - not tracked (Verzik Vitur, every phase). */
	static boolean cannotBeDrained(String npcName, boolean inInstance)
	{
		return inInstance && normalise(npcName).startsWith("verzik vitur");
	}

	static boolean isSotetseg(String npcName)
	{
		return normalise(npcName).startsWith("sotetseg");
	}

	/**
	 * Theatre of Blood backup stats {defence, magic}, by NPC ID - only used if the game data
	 * has placeholder stats. Each mode has its own IDs (OSRS Wiki).
	 */
	private static final Map<Integer, int[]> TOB_FALLBACK = Map.ofEntries(
		// Pestilent Bloat: Entry / Normal / Hard
		Map.entry(10812, new int[]{80, 120}),
		Map.entry(8359, new int[]{100, 150}),
		Map.entry(10813, new int[]{100, 150}),
		// Sotetseg: Entry / Normal / Hard (two forms each)
		Map.entry(10864, new int[]{150, 150}), Map.entry(10865, new int[]{150, 150}),
		Map.entry(8387, new int[]{200, 250}), Map.entry(8388, new int[]{200, 250}),
		Map.entry(10867, new int[]{200, 250}), Map.entry(10868, new int[]{200, 250}),
		// Xarpus: Entry / Normal / Hard (phase 1, phases 2-3)
		Map.entry(10766, new int[]{100, 80}), Map.entry(10767, new int[]{100, 80}), Map.entry(10768, new int[]{100, 80}),
		Map.entry(8338, new int[]{250, 220}), Map.entry(8339, new int[]{250, 220}), Map.entry(8340, new int[]{250, 220}),
		Map.entry(10770, new int[]{200, 220}), Map.entry(10771, new int[]{200, 220}), Map.entry(10772, new int[]{200, 220})
	);

	/** Name fallbacks where the wiki only lists one value: {defence, magic}. */
	private static final Map<String, int[]> TOB_FALLBACK_BY_NAME = Map.of(
		"the maiden of sugadinti", new int[]{80, 140},
		"nylocas vasilias", new int[]{50, 20}
	);

	/** @return {defence, magic} backup for a Theatre of Blood boss, or null */
	static int[] tobFallback(int npcId, String npcName)
	{
		int[] byId = TOB_FALLBACK.get(npcId);
		return byId != null ? byId : TOB_FALLBACK_BY_NAME.get(normalise(npcName));
	}

	static boolean isTekton(String npcName)
	{
		return normalise(npcName).startsWith("tekton");
	}

	/** Immune to the Eye of Ayak's Magic Defence drain (OSRS Wiki). Normalised name prefixes. */
	private static final String[] MAGIC_DEFENCE_DRAIN_IMMUNE = {"archive guardian"};

	static boolean immuneToMagicDefenceDrain(int npcId, String npcName)
	{
		String name = normalise(npcName);
		for (String prefix : MAGIC_DEFENCE_DRAIN_IMMUNE)
		{
			if (name.startsWith(prefix))
			{
				return true;
			}
		}
		return false;
	}

	private DrainCaps()
	{
	}

	/**
	 * @return fixed limits for a known boss, or null (use cache Defence, drain to 0)
	 */
	static Limits lookup(int npcId, String npcName, boolean inInstance)
	{
		Limits byId = BOSSES_BY_ID.get(npcId);
		if (byId != null)
		{
			return byId;
		}
		if (!inInstance)
		{
			return null; // raid bosses are instanced; same-named NPCs elsewhere (e.g. Bork) aren't
		}

		String name = normalise(npcName);
		if (name.isEmpty())
		{
			return null;
		}
		for (Map.Entry<String, Limits> e : BOSSES.entrySet())
		{
			if (name.startsWith(e.getKey()))
			{
				return e.getValue();
			}
		}
		return null;
	}

	static boolean keepsDrainUntilDeath(int npcId, String npcName)
	{
		if (RESET_ON_PHASE_IDS.contains(npcId))
		{
			return false;
		}
		String name = normalise(npcName);
		for (String prefix : RESET_ON_PHASE_NAME_PREFIXES)
		{
			if (name.startsWith(prefix))
			{
				return false;
			}
		}
		return true;
	}

	/** "Dagon'hai Elder" -> "dagonhai elder" (drops apostrophes, tags, symbols). */
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
