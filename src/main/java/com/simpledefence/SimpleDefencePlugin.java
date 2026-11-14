package com.simpledefence;

import com.google.inject.Provides;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Value;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.WorldView;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.WSClient;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

/**
 * Tracks stat drains from special attacks on NPCs and shares them with the RuneLite party.
 *
 * Performance notes (this runs on a lot of clients):
 *  - Event driven. The only per-tick handler returns immediately unless a spec is
 *    waiting for its XP confirmation (one boolean check).
 *  - Work only happens when the local special-attack varp drops, or when one of
 *    YOUR hitsplats lands while a drain spec is pending.
 *  - Stats come from the game data, plus small built-in tables for raid bosses whose
 *    game data is incomplete (no web lookups at runtime).
 *  - Only drained NPCs are stored; at most 32 out-of-sight NPCs are held.
 *  - Party traffic is one tiny message per drain that actually changes a stat.
 */
@PluginDescriptor(
	name = "Simple Defence Tracker",
	description = "Tracks NPC stat drains from special attacks (DWH, Elder maul, Tonalztics, BGS and more) and syncs them with your party",
	tags = {"defence", "defense", "drain", "spec", "dwh", "elder maul", "tonalztics", "ralos", "bgs", "bandos godsword", "arclight", "emberlight", "party", "pvm", "raids"}
)
public class SimpleDefencePlugin extends Plugin
{
	/** VarPlayer 300: special attack energy (0-1000). */
	private static final int VARP_SPECIAL_ATTACK = 300;
	/** Equipment item container id. */
	private static final int EQUIPMENT_CONTAINER = 94;
	/** Ticks to wait for the spec hitsplat(s) before giving up. */
	private static final int SPEC_TIMEOUT_TICKS = 6;

	// Cache stats order: [attack, defence, strength, hitpoints, ranged, magic]
	private static final int STAT_ATTACK = 0;
	private static final int STAT_DEFENCE = 1;
	private static final int STAT_STRENGTH = 2;
	private static final int STAT_RANGED = 4;
	private static final int STAT_MAGIC = 5;

	/** Varbit 5432: 1 while inside the Chambers of Xeric. */
	private static final int VARBIT_IN_COX = 5432;

	/**
	 * Varp 1432 has bit 12 (value 4096) set while in a Challenge Mode Chambers of Xeric,
	 * from entering the Chambers onward (confirmed by comparing normal vs CM snapshots).
	 */
	private static final int VARP_COX_FLAGS = 1432;
	private static final int COX_CM_BIT = 1 << 12;

	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private OverlayManager overlayManager;
	@Inject private SimpleDefenceOverlay overlay;
	@Inject private SpecsPanelOverlay specsPanel;
	@Inject private SimpleDefenceConfig config;
	@Inject private PartyService partyService;
	@Inject private WSClient wsClient;

	/** NPC index -> state. Only NPCs that have been drained. */
	@Getter(AccessLevel.PACKAGE)
	private final Map<Integer, NpcDefenceState> tracked = new LinkedHashMap<>(); // keeps first-drained order for the panel

	private int lastSpecEnergy = -1;

	/** NPC indexes that died this fight (cleared when they despawn). */
	private final Set<Integer> deadIndexes = new HashSet<>();

	/** Whether the last loaded area was an instance (raids are instances). */
	private boolean wasInInstance;

	/** Game ticks per minute (1 tick = 0.6s). */
	private static final int TICKS_PER_MINUTE = 100;

	/**
	 * Whether a drain held for an out-of-sight NPC has run out. It's kept for the
	 * "Out of sight" setting (default 5 minutes) for every NPC. Drains also clear
	 * on death, logout or world hop.
	 */
	boolean isHeldExpired(HeldDrain h)
	{
		return client.getTickCount() - h.getTick() > config.outOfSightMinutes() * TICKS_PER_MINUTE;
	}

	/**
	 * Drains held for keep-until-death NPCs that despawned without dying, by lower-case
	 * name. A queue per name in case several NPCs ever share a name.
	 */
	@Getter(AccessLevel.PACKAGE)
	private final Map<String, ArrayDeque<HeldDrain>> heldDrains = new HashMap<>();

	@Value
	static class HeldDrain
	{
		NpcDefenceState state;
		int tick;
	}

	/** Incoming party drains waiting for the client thread, latest per NPC index. */
	private final Map<Integer, DefenceSyncMessage> pendingRemote = new ConcurrentHashMap<>();
	private final AtomicBoolean remoteFlushQueued = new AtomicBoolean();

	// Pending spec (only one at a time - you can only spec once per attack)
	private DrainWeapon pendingWeapon;
	private NPC pendingTarget;
	private int pendingHitsLeft;
	private int pendingTick;

	// Single-hit specs are confirmed by "spec bar used AND combat XP gained on the same tick".
	// This doesn't depend on whether the hitsplat or the spec-bar update arrives first.
	private boolean awaitingXp;   // spec seen, waiting for Hitpoints XP on the same tick
	private boolean xpConfirmed;  // XP proved the spec landed (above 0)
	private int lastHpXp = -1;
	private int hpXpTick = -1;

	// Last hitsplat of yours, in case it arrives before the spec-bar update in the same tick
	private NPC lastHitNpc;
	private int lastHitTick = -1;
	private int lastHitAmount;

	@Provides
	SimpleDefenceConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(SimpleDefenceConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		overlayManager.add(specsPanel);
		wsClient.registerMessage(DefenceSyncMessage.class);
		lastSpecEnergy = -1;
		lastHpXp = -1;
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		overlayManager.remove(specsPanel);
		wsClient.unregisterMessage(DefenceSyncMessage.class);
		tracked.clear();
		heldDrains.clear();
		pendingRemote.clear();
		deadIndexes.clear();
		clearPending();
	}

	// ------------------------------------------------------------------
	// Local spec detection
	// ------------------------------------------------------------------

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (event.getVarpId() != VARP_SPECIAL_ATTACK)
		{
			return;
		}

		int energy = event.getValue();
		if (lastSpecEnergy != -1 && energy < lastSpecEnergy)
		{
			onSpecUsed();
		}
		lastSpecEnergy = energy;
	}

	private void onSpecUsed()
	{
		ItemContainer equipment = client.getItemContainer(EQUIPMENT_CONTAINER);
		if (equipment == null)
		{
			return;
		}

		Item weapon = equipment.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
		if (weapon == null)
		{
			return;
		}

		DrainWeapon drainWeapon = DrainWeapon.fromItemId(weapon.getId());
		if (drainWeapon == null)
		{
			return;
		}

		Player local = client.getLocalPlayer();
		Actor target = local == null ? null : local.getInteracting();

		pendingWeapon = drainWeapon;
		pendingTarget = target instanceof NPC ? (NPC) target : null;
		pendingHitsLeft = drainWeapon.hitsplats;
		pendingTick = client.getTickCount();
		awaitingXp = drainWeapon.hitsplats == 1;
		xpConfirmed = false;
		if (awaitingXp)
		{
			tryConfirmByXp(); // XP may already have arrived this tick
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		Actor actor = event.getActor();
		if (!(actor instanceof NPC))
		{
			return;
		}

		Hitsplat hitsplat = event.getHitsplat();
		if (!tracked.isEmpty())
		{
			checkSotetsegMaze((NPC) actor);
		}
		if (!hitsplat.isMine())
		{
			return;
		}

		NPC npc = (NPC) actor;
		int tick = client.getTickCount();
		lastHitNpc = npc;
		lastHitTick = tick;
		lastHitAmount = hitsplat.getAmount();

		if (pendingWeapon == null)
		{
			return;
		}

		if (tick - pendingTick > SPEC_TIMEOUT_TICKS)
		{
			clearPending();
			return;
		}

		if (pendingWeapon.hitsplats == 1)
		{
			onSingleHitSplat(npc, hitsplat.getAmount(), tick);
			return;
		}

		// --- Multi-hit specs (Tonalztics): count each hitsplat ---

		// A thrown spec can't land on the tick it's used. Hitsplats on that tick belong
		// to the PREVIOUS normal throw - ignore them.
		if (pendingWeapon.projectile && tick == pendingTick)
		{
			return;
		}

		if (pendingTarget != null && npc != pendingTarget)
		{
			return;
		}

		// "Successful" = hit above 0
		if (hitsplat.getAmount() > 0)
		{
			applyLocalDrain(npc, pendingWeapon, hitsplat.getAmount());
		}
		else
		{
			markAttempt(npc, pendingWeapon);
		}

		if (--pendingHitsLeft <= 0)
		{
			clearPending();
		}
	}

	/**
	 * Sotetseg sends the party to the shadow realm (maze) at 66.6% and 33.3% HP, which resets
	 * any Defence drain. Backup for the form-change reset: once his health crosses each
	 * threshold, put his Defence back to full (once per threshold).
	 */
	private void checkSotetsegMaze(NPC npc)
	{
		NpcDefenceState s = tracked.get(npc.getIndex());
		if (s == null || !DrainCaps.isSotetseg(s.getNpcName()))
		{
			return;
		}
		int ratio = npc.getHealthRatio();
		int scale = npc.getHealthScale();
		if (ratio < 0 || scale <= 0)
		{
			return; // health bar not visible
		}
		float hp = (float) ratio / scale;
		int thresholdsPassed = hp <= 1f / 3f ? 2 : hp <= 2f / 3f ? 1 : 0;
		if (s.getMazesSeen() < 0)
		{
			s.setMazesSeen(thresholdsPassed); // first look: just note where the fight is
			return;
		}
		if (thresholdsPassed > s.getMazesSeen())
		{
			s.setMazesSeen(thresholdsPassed);
			s.setDefence(s.getBaseDefence());
		}
	}

	/** Single-hit spec: only acts once XP has confirmed it landed. */
	private void onSingleHitSplat(NPC npc, int amount, int tick)
	{
		if (awaitingXp)
		{
			if (tick > pendingTick)
			{
				onSingleHitMiss(); // no combat XP on the spec tick -> it missed (0)
			}
			return; // same tick: XP may still arrive; this hit is buffered in lastHit*
		}

		if (!xpConfirmed || amount <= 0)
		{
			return;
		}
		if (pendingTarget != null && npc != pendingTarget)
		{
			return;
		}
		if (pendingWeapon.projectile && tick == pendingTick)
		{
			return; // previous throw/cast, not the spec
		}

		// Confirmed spec: this hitsplat supplies the target (and damage, for BGS etc.)
		applyLocalDrain(npc, pendingWeapon, amount);
		clearPending();
	}

	/**
	 * End of a tick: if a single-hit spec is still waiting for XP, none came on its tick,
	 * so it missed. Only does work while a spec is pending.
	 */
	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (awaitingXp)
		{
			onSingleHitMiss();
		}
	}

	private void onSingleHitMiss()
	{
		NPC npc = pendingTarget;
		if (npc == null && lastHitTick == pendingTick)
		{
			npc = lastHitNpc;
		}
		markAttempt(npc, pendingWeapon);
		clearPending();
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		// Every damaging attack gives Hitpoints XP, so it's a reliable "hit above 0" signal
		if (event.getSkill() != Skill.HITPOINTS)
		{
			return;
		}

		int xp = event.getXp();
		if (lastHpXp >= 0 && xp > lastHpXp)
		{
			hpXpTick = client.getTickCount();
			tryConfirmByXp();
		}
		lastHpXp = xp;
	}

	/** Spec bar used AND combat XP gained on the same tick -> the spec landed. */
	private void tryConfirmByXp()
	{
		if (!awaitingXp || pendingWeapon == null || hpXpTick != pendingTick)
		{
			return;
		}
		awaitingXp = false;
		xpConfirmed = true;

		NPC npc = pendingTarget;
		if (npc == null && lastHitTick == pendingTick)
		{
			npc = lastHitNpc;
		}
		if (npc == null)
		{
			return; // don't know the target yet - the next hitsplat of yours will tell us
		}

		if (!pendingWeapon.damageBased)
		{
			// DWH, Elder maul, Arclight, Emberlight, Accursed sceptre: no damage needed
			applyLocalDrain(npc, pendingWeapon, 0);
			clearPending();
			return;
		}

		// BGS, Bone dagger, Anchor, Eye of Ayak: need the damage. Melee hits can already be here.
		if (!pendingWeapon.projectile && lastHitTick == pendingTick && lastHitNpc == npc && lastHitAmount > 0)
		{
			applyLocalDrain(npc, pendingWeapon, lastHitAmount);
			clearPending();
		}
		// otherwise wait for the hitsplat (onSingleHitSplat)
	}

	private void applyLocalDrain(NPC npc, DrainWeapon weapon, int damage)
	{
		NpcDefenceState state = getOrCreateState(npc);
		if (state == null)
		{
			return;
		}

		state.markAttempted(weapon.attemptedStats());
		long before = snapshot(state);
		weapon.apply(state, damage);

		// Network: one small message, only when the drain actually changed something
		// (a hit at a drain cap, a 2nd Seercull, etc. send nothing). No timers, no re-sends.
		if (snapshot(state) != before && config.syncParty() && partyService.isInParty())
		{
			partyService.send(DefenceSyncMessage.of(client.getWorld(), npc.getIndex(), npc.getId(), state));
		}
	}

	/**
	 * A spec on this NPC missed (or hit 0): show the stats it tried to drain, and apply
	 * any drain that happens even on a miss (Tekton). Syncs only if something changed.
	 */
	private void markAttempt(NPC npc, DrainWeapon weapon)
	{
		if (npc == null)
		{
			return;
		}
		NpcDefenceState state = getOrCreateState(npc);
		if (state == null)
		{
			return;
		}
		state.markAttempted(weapon.attemptedStats());

		long before = snapshot(state);
		weapon.applyMiss(state);
		if (snapshot(state) != before && config.syncParty() && partyService.isInParty())
		{
			partyService.send(DefenceSyncMessage.of(client.getWorld(), npc.getIndex(), npc.getId(), state));
		}
	}

	/** Cheap fingerprint of every tracked stat, to detect whether a drain changed anything. */
	private static long snapshot(NpcDefenceState s)
	{
		long h = s.getDefence();
		h = h * 31 + s.getAttack();
		h = h * 31 + s.getStrength();
		h = h * 31 + s.getMagic();
		h = h * 31 + s.getRanged();
		h = h * 31 + s.getMagicDefenceDrained();
		return h;
	}

	/** Forgets every drain: tracked NPCs, held out-of-sight drains and any pending spec. */
	private void clearAllDrains()
	{
		tracked.clear();
		heldDrains.clear();
		pendingRemote.clear();
		deadIndexes.clear();
		clearPending();
	}

	private void clearPending()
	{
		pendingWeapon = null;
		pendingTarget = null;
		pendingHitsLeft = 0;
		awaitingXp = false;
		xpConfirmed = false;
	}

	// ------------------------------------------------------------------
	// Party sync
	// ------------------------------------------------------------------

	@Subscribe
	public void onDefenceSyncMessage(DefenceSyncMessage msg)
	{
		if (!config.syncParty())
		{
			return;
		}

		PartyMember local = partyService.getLocalMember();
		if (local != null && local.getMemberId() == msg.getMemberId())
		{
			return; // our own echo
		}

		// Basic validation - party data comes from other players, never trust it
		if (!msg.isValid())
		{
			return;
		}

		// Party messages arrive off the client thread. Coalesce them so a flood of
		// messages (buggy or malicious) can only ever queue one task, and the pending
		// map is bounded by the number of NPC indexes.
		// Same NPC: keep the lowest of each stat. Different NPC on that index: keep the newest.
		pendingRemote.merge(msg.getNpcIndex(), msg,
			(old, neu) -> old.getNpcId() == neu.getNpcId() ? old.lowest(neu) : neu);
		if (remoteFlushQueued.compareAndSet(false, true))
		{
			clientThread.invokeLater(this::flushRemoteDrains);
		}
	}

	private void flushRemoteDrains()
	{
		remoteFlushQueued.set(false);
		for (Integer index : pendingRemote.keySet())
		{
			DefenceSyncMessage m = pendingRemote.remove(index);
			if (m != null)
			{
				applyRemoteDrain(m);
			}
		}
	}

	private void applyRemoteDrain(DefenceSyncMessage msg)
	{
		if (msg.getWorld() != client.getWorld())
		{
			return;
		}

		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return;
		}

		NPC npc = wv.npcs().byIndex(msg.getNpcIndex());
		if (npc == null || npc.getId() != msg.getNpcId())
		{
			// Not in view: keep it ready for when the NPC comes back into view
			applyRemoteDrainOutOfSight(msg);
			return;
		}

		NpcDefenceState state = getOrCreateState(npc);
		if (state == null)
		{
			return;
		}

		mergeSynced(state, msg);
	}

	/**
	 * A party member drained an NPC we can't currently see: update our held copy, or start
	 * one from the NPC's definition so the drain is already there when it comes into view.
	 */
	private void applyRemoteDrainOutOfSight(DefenceSyncMessage msg)
	{
		for (ArrayDeque<HeldDrain> queue : heldDrains.values())
		{
			for (HeldDrain held : queue)
			{
				if (held.getState().getNpcId() == msg.getNpcId())
				{
					mergeSynced(held.getState(), msg);
					return;
				}
			}
		}

		NPCComposition comp = client.getNpcDefinition(msg.getNpcId());
		if (comp == null || comp.getName() == null)
		{
			return;
		}
		int[] stats = comp.getStats();
		if (stats == null || stats.length <= STAT_MAGIC)
		{
			return;
		}

		NpcDefenceState state = createState(msg.getNpcId(), comp, stats);
		if (state == null)
		{
			return;
		}
		mergeSynced(state, msg);
		holdDrain(state);
	}

	/** Takes the lower value of each stat: idempotent, order-independent. Returns true if anything changed. */
	private static boolean mergeSynced(NpcDefenceState state, DefenceSyncMessage msg)
	{
		int def = Math.max(state.getMinDefence(), msg.getDefence());
		boolean changed = def < state.getDefence()
			|| msg.getAttack() < state.getAttack() || msg.getStrength() < state.getStrength()
			|| msg.getMagic() < state.getMagic() || msg.getRanged() < state.getRanged()
			|| msg.getMagicDefenceDrained() > state.getMagicDefenceDrained();
		state.setDefence(Math.min(state.getDefence(), def));
		state.setAttack(Math.min(state.getAttack(), msg.getAttack()));
		state.setStrength(Math.min(state.getStrength(), msg.getStrength()));
		state.setMagic(Math.min(state.getMagic(), msg.getMagic()));
		state.setRanged(Math.min(state.getRanged(), msg.getRanged()));
		state.setMagicDefenceDrained(Math.max(state.getMagicDefenceDrained(), msg.getMagicDefenceDrained()));
		return changed;
	}

	// ------------------------------------------------------------------
	// State helpers / cleanup
	// ------------------------------------------------------------------

	private NpcDefenceState getOrCreateState(NPC npc)
	{
		int index = npc.getIndex();
		NpcDefenceState state = tracked.get(index);
		if (state != null && state.getNpcId() == npc.getId())
		{
			return state;
		}
		if (state != null && DrainCaps.keepsDrainUntilDeath(state.getNpcId(), state.getNpcName()))
		{
			state.setNpcId(npc.getId()); // same boss, different form
			return state;
		}

		NPCComposition comp = npc.getTransformedComposition();
		if (comp == null)
		{
			comp = npc.getComposition();
		}
		if (comp == null)
		{
			return null;
		}

		// Cache stats: [attack, defence, strength, hitpoints, ranged, magic]
		int[] stats = comp.getStats();
		if (stats == null || stats.length <= STAT_MAGIC)
		{
			return null;
		}

		NpcDefenceState held = takeHeldDrain(comp.getName());
		if (held != null)
		{
			held.setNpcId(npc.getId());
			tracked.put(index, held);
			return held;
		}

		state = createState(npc.getId(), comp, stats);
		if (state == null)
		{
			return null;
		}
		tracked.put(index, state);
		checkSotetsegMaze(npc); // Sotetseg: note how far into the fight we are (no reset)
		return state;
	}

	/** Builds a fresh state from the NPC's definition (wiki tables first, then game data). */
	private NpcDefenceState createState(int npcId, NPCComposition comp, int[] stats)
	{
		// Chambers of Xeric: the game data only has placeholder stats (1 Defence)
		if (client.getVarbitValue(VARBIT_IN_COX) == 1)
		{
			boolean cm = (client.getVarpValue(VARP_COX_FLAGS) & COX_CM_BIT) != 0;
			int[] cox = CoxStats.lookup(npcId, comp.getName(), cm);
			if (cox == null)
			{
				if (stats[STAT_DEFENCE] <= 1)
				{
					return null; // unknown raid NPC - don't show wrong numbers
				}
			}
			else
			{
				// Rebuild in game-data order: [attack, defence, strength, hitpoints, ranged, magic]
				stats = new int[]{cox[CoxStats.ATT], cox[CoxStats.DEF], cox[CoxStats.STR], 0,
					cox[CoxStats.RNG], cox[CoxStats.MAG]};
			}
		}

		// Known bosses use the wiki's fixed starting/lowest Defence; everything else
		// uses the cache Defence and can be drained to 0.
		WorldView wv = client.getTopLevelWorldView();
		boolean inInstance = wv != null && wv.isInstance();

		// Verzik Vitur's Defence can't be lowered - nothing to track
		if (DrainCaps.cannotBeDrained(comp.getName(), inInstance))
		{
			return null;
		}

		// Theatre of Blood: only fall back to wiki values if the game data is a placeholder
		if (inInstance && stats[STAT_DEFENCE] <= 1)
		{
			int[] tob = DrainCaps.tobFallback(npcId, comp.getName());
			if (tob != null)
			{
				stats = stats.clone();
				stats[STAT_DEFENCE] = tob[0];
				stats[STAT_MAGIC] = tob[1];
			}
		}

		DrainCaps.Limits limits = DrainCaps.lookup(npcId, comp.getName(), inInstance);
		int baseDefence = limits != null ? limits.initial : stats[STAT_DEFENCE];
		int minDefence = limits != null ? limits.lowest : 0;
		if (limits == null)
		{
			// Tombs of Amascut: only a fixed amount can be drained
			int maxReduction = DrainCaps.maxReduction(comp.getName(), inInstance);
			if (maxReduction >= 0)
			{
				minDefence = Math.max(0, baseDefence - maxReduction);
			}

			// Theatre of Blood: fixed lowest Defence (Sotetseg 100)
			int fixedMin = DrainCaps.minDefence(comp.getName(), inInstance);
			if (fixedMin >= 0)
			{
				minDefence = Math.min(baseDefence, fixedMin);
			}
		}
		int magic = limits != null ? limits.magic : stats[STAT_MAGIC];
		return new NpcDefenceState(npcId, comp.getName(), baseDefence, minDefence,
			stats[STAT_ATTACK], stats[STAT_STRENGTH], magic, stats[STAT_RANGED]);
	}

	/** Most drains held at once; old non-persistent ones are dropped first. */
	private static final int MAX_HELD_DRAINS = 32;

	private void holdDrain(NpcDefenceState state)
	{
		int tick = client.getTickCount();
		int count = 0;
		for (ArrayDeque<HeldDrain> q : heldDrains.values())
		{
			count += q.size();
		}
		if (count >= MAX_HELD_DRAINS)
		{
			// Drop expired ones first
			for (ArrayDeque<HeldDrain> q : heldDrains.values())
			{
				q.removeIf(this::isHeldExpired);
			}
			heldDrains.values().removeIf(ArrayDeque::isEmpty);
			count = 0;
			for (ArrayDeque<HeldDrain> q : heldDrains.values())
			{
				count += q.size();
			}
			if (count >= MAX_HELD_DRAINS)
			{
				return;
			}
		}
		heldDrains.computeIfAbsent(state.getNpcName().toLowerCase(), k -> new ArrayDeque<>())
			.addLast(new HeldDrain(state, tick));
	}

	/** Returns and removes a held drain for this NPC name, if it hasn't expired. */
	private NpcDefenceState takeHeldDrain(String npcName)
	{
		if (heldDrains.isEmpty() || npcName == null)
		{
			return null;
		}
		String key = npcName.toLowerCase();
		ArrayDeque<HeldDrain> queue = heldDrains.get(key);
		if (queue == null)
		{
			return null;
		}

		NpcDefenceState result = null;
		while (!queue.isEmpty())
		{
			HeldDrain h = queue.pollFirst();
			if (!isHeldExpired(h))
			{
				result = h.getState();
				break;
			}
			// expired - drop and keep looking
		}
		if (queue.isEmpty())
		{
			heldDrains.remove(key);
		}
		return result;
	}

	/**
	 * An NPC transformed (new phase / new ID): treat it as a new entity, so its
	 * Defence starts fresh and must be drained again.
	 */
	@Subscribe
	public void onNpcChanged(NpcChanged event)
	{
		NPC npc = event.getNpc();
		NpcDefenceState state = tracked.get(npc.getIndex());
		if (state == null || state.getNpcId() == npc.getId())
		{
			return;
		}
		if (DrainCaps.keepsDrainUntilDeath(state.getNpcId(), state.getNpcName()))
		{
			state.setNpcId(npc.getId()); // e.g. Archive Guardian flying <-> grounded

			// A Warden changing form after it's been drained = P3 enrage: 60 can now be drained
			if (DrainCaps.isWarden(state.getNpcName()))
			{
				state.setMinDefence(Math.max(0,
					state.getBaseDefence() - DrainCaps.WARDEN_ENRAGED_MAX_REDUCTION));
			}
		}
		else
		{
			tracked.remove(npc.getIndex());
		}
	}

	/** Re-attach a held drain as soon as the new form appears (no-op unless one is held). */
	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		if (heldDrains.isEmpty())
		{
			return;
		}
		NPC npc = event.getNpc();
		NpcDefenceState held = takeHeldDrain(npc.getName());
		if (held != null)
		{
			held.setNpcId(npc.getId());
			tracked.put(npc.getIndex(), held);
		}
	}

	/**
	 * "Challenge complete: Ezekiel. Duration: ..." - the game's message when a raid room's
	 * fight ends. Everything in that room is dead, so clear all drains (covers rooms with
	 * several bosses, e.g. Bork and the Smorks, without needing to match names).
	 */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() == ChatMessageType.GAMEMESSAGE
			&& Text.removeTags(event.getMessage()).startsWith("Challenge complete:"))
		{
			clearAllDrains();
		}
	}

	/** RuneLite's own death event: drop the NPC's drain (and panel section) straight away. */
	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (!(event.getActor() instanceof NPC))
		{
			return;
		}
		int index = ((NPC) event.getActor()).getIndex();
		if (tracked.remove(index) != null)
		{
			// Remember it died, in case it despawns later without looking dead
			deadIndexes.add(index);
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		NPC npc = event.getNpc();
		NpcDefenceState state = tracked.remove(npc.getIndex());

		// Despawned alive (form swap, out of view) -> hold the drain so it can come back.
		// Several death signals are checked because not every boss sets all of them.
		boolean diedEarlier = deadIndexes.remove(npc.getIndex()); // always clear the index
		boolean died = diedEarlier || npc.isDead() || npc.getHealthRatio() == 0;
		if (state != null && !died && DrainCaps.keepsDrainUntilDeath(state.getNpcId(), state.getNpcName()))
		{
			holdDrain(state);
		}

		if (npc == pendingTarget)
		{
			clearPending();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();

		// Left a raid (or any instance) back into the normal world: clear all drains
		if (state == GameState.LOGGED_IN)
		{
			WorldView wv = client.getTopLevelWorldView();
			boolean inInstance = wv != null && wv.isInstance();
			if (wasInInstance && !inInstance)
			{
				clearAllDrains();
			}
			wasInInstance = inInstance;
		}

		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
		{
			tracked.clear();
			heldDrains.clear();
			deadIndexes.clear();
			clearPending();
			lastSpecEnergy = -1;
			lastHpXp = -1;
			lastHitNpc = null;
		}
	}
}
