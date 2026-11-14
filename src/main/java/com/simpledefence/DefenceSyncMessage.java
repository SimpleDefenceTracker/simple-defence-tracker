package com.simpledefence;

import lombok.EqualsAndHashCode;
import lombok.Value;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Sent to the party after a successful drain. Carries the resulting stats
 * (not the event), so receivers just keep the lower value of each - this is
 * idempotent and can't double-drain if a message is duplicated.
 */
@Value
@EqualsAndHashCode(callSuper = true)
public class DefenceSyncMessage extends PartyMemberMessage
{
	int world;
	int npcIndex;
	int npcId;
	int defence;
	int attack;
	int strength;
	int magic;
	int ranged;
	int magicDefenceDrained;

	static DefenceSyncMessage of(int world, int npcIndex, int npcId, NpcDefenceState s)
	{
		return new DefenceSyncMessage(world, npcIndex, npcId,
			s.getDefence(), s.getAttack(), s.getStrength(), s.getMagic(), s.getRanged(), s.getMagicDefenceDrained());
	}

	boolean isValid()
	{
		return npcIndex >= 0 && npcIndex <= 65535 && npcId >= 0 && npcId <= 65535
			&& defence >= 0 && attack >= 0 && strength >= 0 && magic >= 0 && ranged >= 0
			&& magicDefenceDrained >= 0 && magicDefenceDrained <= 10_000;
	}

	/** Lowest of each stat (highest Magic Defence drain) across two messages for the same NPC. */
	DefenceSyncMessage lowest(DefenceSyncMessage o)
	{
		return new DefenceSyncMessage(world, npcIndex, npcId,
			Math.min(defence, o.defence), Math.min(attack, o.attack), Math.min(strength, o.strength),
			Math.min(magic, o.magic), Math.min(ranged, o.ranged),
			Math.max(magicDefenceDrained, o.magicDefenceDrained));
	}
}
