package com.simpledefence;

import lombok.Getter;
import lombok.Setter;

/**
 * Tiny per-NPC record. Only created for NPCs that have actually been drained,
 * so memory use stays proportional to the fight, not the area.
 *
 * Defence is what's displayed; Attack/Strength/Magic/Ranged are tracked so
 * BGS overflow, Arclight/Emberlight, the Barrelchest anchor and Seercull stay correct.
 */
@Getter
class NpcDefenceState
{
	@Setter
	private int npcId;
	private final String npcName;

	private final int baseDefence;
	@Setter
	private int minDefence;
	private final int baseAttack;
	private final int baseStrength;
	private final int baseMagic;
	private final int baseRanged;

	@Setter
	private int defence;
	@Setter
	private int attack;
	@Setter
	private int strength;
	@Setter
	private int magic;
	@Setter
	private int ranged;
	/** Eye of Ayak: total Magic Defence bonus removed (not a level). Only ever grows. */
	@Setter
	private int magicDefenceDrained;

	// Stats someone has tried to drain on this NPC (hit, miss or capped)
	static final int ATTEMPT_ATTACK = 1;
	static final int ATTEMPT_STRENGTH = 1 << 1;
	static final int ATTEMPT_DEFENCE = 1 << 2;
	static final int ATTEMPT_RANGED = 1 << 3;
	static final int ATTEMPT_MAGIC = 1 << 4;
	static final int ATTEMPT_MAGIC_DEFENCE = 1 << 5;

	private int attempted;

	/**
	 * Sotetseg: how many maze thresholds (66.6%, 33.3% HP) have already reset the drain.
	 * -1 until his health is first seen, so tracking that starts mid-fight doesn't reset.
	 */
	@Setter
	private int mazesSeen = -1;

	void markAttempted(int stats)
	{
		attempted |= stats;
	}

	boolean wasAttempted(int stat)
	{
		return (attempted & stat) != 0;
	}

	NpcDefenceState(int npcId, String npcName, int baseDefence, int minDefence,
		int baseAttack, int baseStrength, int baseMagic, int baseRanged)
	{
		this.npcId = npcId;
		this.npcName = npcName;
		this.baseDefence = baseDefence;
		this.minDefence = minDefence;
		this.baseAttack = baseAttack;
		this.baseStrength = baseStrength;
		this.baseMagic = baseMagic;
		this.baseRanged = baseRanged;
		this.defence = baseDefence;
		this.attack = baseAttack;
		this.strength = baseStrength;
		this.magic = baseMagic;
		this.ranged = baseRanged;
	}

	int getMaxDrainable()
	{
		return Math.max(0, baseDefence - minDefence);
	}

	int getDrainable()
	{
		return Math.max(0, defence - minDefence);
	}

	/** How far each stat has been drained below its base (0 = untouched). */
	int getMagicDrained()
	{
		return Math.max(0, baseMagic - magic);
	}

	int getAttackDrained()
	{
		return Math.max(0, baseAttack - attack);
	}

	int getStrengthDrained()
	{
		return Math.max(0, baseStrength - strength);
	}

	int getRangedDrained()
	{
		return Math.max(0, baseRanged - ranged);
	}
}
