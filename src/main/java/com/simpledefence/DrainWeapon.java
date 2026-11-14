package com.simpledefence;

import java.util.HashMap;
import java.util.Map;

/**
 * Weapons whose special attack drains NPC stats. Item IDs are hard-coded so the
 * plugin doesn't depend on gameval naming changes. Rules from the OSRS Wiki.
 * Every effect below only happens on a hit above 0.
 */
enum DrainWeapon
{
	// 35% of CURRENT Defence
	ELDER_MAUL(1, false, false, 21003, 27100),
	// 30% of CURRENT Defence
	DRAGON_WARHAMMER(1, false, false, 13576),
	// Each of 2 hits: Defence -1/8 of the NPC's base Magic
	TONALZTICS_OF_RALOS(2, true, false, 28922),
	// Drains = damage: Defence, then Strength, Attack, Magic, Ranged (Prayer skipped for NPCs)
	BANDOS_GODSWORD(1, false, true, 11804, 20368),
	// Defence, Attack, Strength each -(5% of base + 1)
	ARCLIGHT(1, false, false, 19675),
	// Defence, Attack, Strength each -(5% of base + 1)
	EMBERLIGHT(1, false, false, 29589),
	// Defence = damage, only if Defence isn't already lowered
	BONE_DAGGER(1, false, true, 8872, 8874, 8876, 8878),
	// 10% of damage: Defence, else Attack, else Ranged, else Magic
	BARRELCHEST_ANCHOR(1, false, true, 10887),
	// Magic = damage, only if Magic isn't already lowered (doesn't stack)
	SEERCULL(1, true, true, 6724),
	// Charged only. Defence and Magic -15%, capped at a total 15% reduction
	ACCURSED_SCEPTRE(1, true, false, 27665),
	// Charged only. Magic Defence BONUS -damage (not a level). Some bosses are immune.
	EYE_OF_AYAK(1, true, true, 31113);

	final int hitsplats;
	/** Thrown/projectile spec: its hits can never land on the tick it's used. */
	final boolean projectile;
	/** Drain depends on the hit's damage (so the spec waits for its hitsplat). */
	final boolean damageBased;
	private final int[] itemIds;

	private static final Map<Integer, DrainWeapon> BY_ITEM = new HashMap<>();

	static
	{
		for (DrainWeapon w : values())
		{
			for (int id : w.itemIds)
			{
				BY_ITEM.put(id, w);
			}
		}
	}

	DrainWeapon(int hitsplats, boolean projectile, boolean damageBased, int... itemIds)
	{
		this.hitsplats = hitsplats;
		this.projectile = projectile;
		this.damageBased = damageBased;
		this.itemIds = itemIds;
	}

	static DrainWeapon fromItemId(int itemId)
	{
		return BY_ITEM.get(itemId);
	}

	/** Panel shows damage needed (": 42 dmg") rather than a count. */
	boolean panelShowsDamage()
	{
		return damageBased && this != SEERCULL && this != EYE_OF_AYAK;
	}

	/** Item used for the overlay icon. */
	int iconItemId()
	{
		return itemIds[0];
	}

	/** Which stats a spec from this weapon tries to drain (shown above the head even on a miss). */
	int attemptedStats()
	{
		switch (this)
		{
			case ARCLIGHT:
			case EMBERLIGHT:
				return NpcDefenceState.ATTEMPT_DEFENCE | NpcDefenceState.ATTEMPT_ATTACK | NpcDefenceState.ATTEMPT_STRENGTH;
			case ACCURSED_SCEPTRE:
				return NpcDefenceState.ATTEMPT_DEFENCE | NpcDefenceState.ATTEMPT_MAGIC;
			case SEERCULL:
				return NpcDefenceState.ATTEMPT_MAGIC;
			case EYE_OF_AYAK:
				return NpcDefenceState.ATTEMPT_MAGIC_DEFENCE;
			default:
				// DWH, Elder maul, Tonalztics, BGS, Bone dagger, Anchor start on Defence
				// (any BGS / anchor overflow shows up because it actually drained)
				return NpcDefenceState.ATTEMPT_DEFENCE;
		}
	}

	/** Applies one successful (above 0) hitsplat to the NPC's stats. */
	void apply(NpcDefenceState s, int damage)
	{
		switch (this)
		{
			case ELDER_MAUL:
				drainDefence(s, (int) (s.getDefence() * 0.35));
				break;
			case DRAGON_WARHAMMER:
				drainDefence(s, (int) (s.getDefence() * 0.30));
				break;
			case TONALZTICS_OF_RALOS:
				drainDefence(s, s.getBaseMagic() / 8);
				break;
			case BANDOS_GODSWORD:
				// Tekton: BGS always drains at least 10
				bandosGodsword(s, DrainCaps.isTekton(s.getNpcName()) ? Math.max(10, damage) : damage);
				break;
			case ARCLIGHT:
			case EMBERLIGHT:
				drainDefence(s, weakenAmount(s.getBaseDefence()));
				s.setAttack(Math.max(0, s.getAttack() - weakenAmount(s.getBaseAttack())));
				s.setStrength(Math.max(0, s.getStrength() - weakenAmount(s.getBaseStrength())));
				break;
			case BONE_DAGGER:
				if (s.getDefence() >= s.getBaseDefence())
				{
					drainDefence(s, damage);
				}
				break;
			case BARRELCHEST_ANCHOR:
				barrelchestAnchor(s, damage / 10);
				break;
			case SEERCULL:
				if (s.getMagic() >= s.getBaseMagic())
				{
					s.setMagic(Math.max(0, s.getMagic() - damage));
				}
				break;
			case ACCURSED_SCEPTRE:
				condemn(s);
				break;
			case EYE_OF_AYAK:
				if (!DrainCaps.immuneToMagicDefenceDrain(s.getNpcId(), s.getNpcName()))
				{
					s.setMagicDefenceDrained(s.getMagicDefenceDrained() + damage);
				}
				break;
		}
	}

	/**
	 * A spec that missed (or hit 0). Normally drains nothing, except on Tekton:
	 * Elder maul / DWH still drain 5% of his Defence, and BGS still drains 10.
	 * (Tekton's guaranteed first maul/DWH hit needs nothing special - it lands and gives XP.)
	 */
	void applyMiss(NpcDefenceState s)
	{
		if (!DrainCaps.isTekton(s.getNpcName()))
		{
			return;
		}
		switch (this)
		{
			case ELDER_MAUL:
			case DRAGON_WARHAMMER:
				drainDefence(s, s.getDefence() * 5 / 100);
				break;
			case BANDOS_GODSWORD:
				bandosGodsword(s, 10);
				break;
			default:
				break;
		}
	}

	/**
	 * Panel number: specs (or hitsplats for Tonalztics, or damage for damage-based
	 * weapons) needed to fully drain Defence. <= 0 means hide the line.
	 */
	int panelCount(NpcDefenceState s)
	{
		if (this == SEERCULL)
		{
			return s.getMagic() >= s.getBaseMagic() ? 1 : -1;
		}

		int drainable = s.getDrainable();
		if (drainable <= 0)
		{
			return 0;
		}

		switch (this)
		{
			case ELDER_MAUL:
				return simulatePercent(s, 0.35);
			case DRAGON_WARHAMMER:
				return simulatePercent(s, 0.30);
			case TONALZTICS_OF_RALOS:
				return divideUp(drainable, s.getBaseMagic() / 8);
			case ARCLIGHT:
			case EMBERLIGHT:
				return divideUp(drainable, weakenAmount(s.getBaseDefence()));
			case BANDOS_GODSWORD:
				return drainable;
			case BONE_DAGGER:
				return s.getDefence() >= s.getBaseDefence() ? drainable : -1;
			case BARRELCHEST_ANCHOR:
				return drainable * 10;
			case ACCURSED_SCEPTRE:
				// Can only ever take 15% off; 1 spec reaches that, then it does nothing
				return s.getDefence() > condemnFloor(s.getBaseDefence(), s.getMinDefence()) ? 1 : -1;
			default:
				return -1; // Eye of Ayak doesn't drain the Defence level
		}
	}

	// ------------------------------------------------------------------

	private static void drainDefence(NpcDefenceState s, int amount)
	{
		if (amount > 0)
		{
			s.setDefence(Math.max(s.getMinDefence(), s.getDefence() - amount));
		}
	}

	/** Arclight / Emberlight: 5% of base + 1 (demon bonus not applied). */
	private static int weakenAmount(int base)
	{
		return base * 5 / 100 + 1;
	}

	private static void bandosGodsword(NpcDefenceState s, int damage)
	{
		int before = s.getDefence();
		drainDefence(s, damage);
		int left = damage - (before - s.getDefence());

		// Overflow only once Defence is truly at 0 (a drain cap swallows the rest)
		if (left <= 0 || s.getDefence() > 0)
		{
			return;
		}
		int d = Math.min(left, s.getStrength());
		s.setStrength(s.getStrength() - d);
		left -= d;
		d = Math.min(left, s.getAttack());
		s.setAttack(s.getAttack() - d);
		left -= d;
		d = Math.min(left, s.getMagic());
		s.setMagic(s.getMagic() - d);
		left -= d;
		s.setRanged(Math.max(0, s.getRanged() - left));
	}

	private static void barrelchestAnchor(NpcDefenceState s, int amount)
	{
		if (amount <= 0)
		{
			return;
		}
		if (s.getDefence() > Math.max(1, s.getMinDefence()))
		{
			drainDefence(s, amount);
		}
		else if (s.getAttack() > 1)
		{
			s.setAttack(Math.max(1, s.getAttack() - amount));
		}
		else if (s.getRanged() > 1)
		{
			s.setRanged(Math.max(1, s.getRanged() - amount));
		}
		else
		{
			s.setMagic(Math.max(1, s.getMagic() - amount));
		}
	}

	/** Accursed sceptre: -15% of current, but never more than 15% below base in total. */
	private static void condemn(NpcDefenceState s)
	{
		int defFloor = condemnFloor(s.getBaseDefence(), s.getMinDefence());
		if (s.getDefence() > defFloor)
		{
			s.setDefence(Math.max(defFloor, s.getDefence() - s.getDefence() * 15 / 100));
		}
		int magFloor = condemnFloor(s.getBaseMagic(), 0);
		if (s.getMagic() > magFloor)
		{
			s.setMagic(Math.max(magFloor, s.getMagic() - s.getMagic() * 15 / 100));
		}
	}

	private static int condemnFloor(int base, int min)
	{
		return Math.max(min, base - base * 15 / 100);
	}

	private static int simulatePercent(NpcDefenceState s, double percent)
	{
		int def = s.getDefence();
		int min = s.getMinDefence();
		int specs = 0;
		while (def > min && specs < 99)
		{
			int drain = (int) (def * percent);
			if (drain <= 0)
			{
				break;
			}
			def = Math.max(min, def - drain);
			specs++;
		}
		return specs == 0 ? -1 : specs;
	}

	private static int divideUp(int total, int per)
	{
		return per <= 0 ? -1 : (total + per - 1) / per;
	}
}
