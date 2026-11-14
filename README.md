# Simple Defence Tracker

A [RuneLite](https://runelite.net) plugin that tracks NPC stat drains from special attacks
and shares them with your party in real time.

Know exactly how much Defence a boss has left, how many more specs it takes to finish the
drain, and when it's fully drained - for you and everyone in your party.

> **Beta (v0.1.0-beta):** Simple Defence Tracker is in active development and is improved regularly.
> Some boss rules and stats may still be refined. Feedback, bug reports and suggestions are
> always appreciated - please open an issue on GitHub.

---

## Features

- **Live stat display above each NPC** - remaining / total drain for every affected stat,
  colour-coded from red (untouched) to green (fully drained).
- **Spec planner panel** - a movable panel listing every drained NPC and how many more specs,
  hitsplats or damage each weapon needs to finish the drain.
- **Party sync** - drains are shared through RuneLite's built-in party, so everyone sees the
  same numbers, even for NPCs that aren't in view yet.
- **Raid-aware** - built-in rules for The Fractured Archive, Tombs of Amascut, the Theatre of
  Blood and the Chambers of Xeric (including Challenge Mode), with per-boss drain limits and
  phase handling.
- **Accurate spec detection** - a spec only counts when the spec bar drops *and* combat
  experience is gained on the same game tick.
- **Lightweight** - event-driven, no web requests, and minimal network traffic.

## Display

**Above the NPC** - one line, shown only for stats that have been drained or targeted:

```
[Att] 189/200   [Str] 189/200   [Def] 42/104   [Rng] 196/200   [Mag] 227/250   [Mag def] -30 mdef
```

**Simple Defence Tracker panel** - hold <kbd>Alt</kbd> and drag to move:

```
Simple Defence Tracker
Bork
[Elder maul]  : 2
[DWH]         : 3
[Tonalztics]  : 9
```

| Weapon type | Panel shows |
|---|---|
| Elder maul, Dragon warhammer, Arclight, Emberlight | Specs remaining |
| Tonalztics of Ralos | Hitsplats remaining (2 per spec) |
| Bandos godsword, Bone dagger, Barrelchest anchor | Damage remaining |
| Accursed sceptre, Seercull | `1` while a spec would still have an effect |

An NPC's section shows **Fully drained** once nothing is left, and is removed when it dies.

## Supported weapons

| Weapon | Effect on a successful hit |
|---|---|
| Elder maul / Elder maul (or) | Defence reduced by 35% of current |
| Dragon warhammer | Defence reduced by 30% of current |
| Tonalztics of Ralos (charged) | Each of two hits reduces Defence by 1/8 of the target's Magic level |
| Bandos godsword | Reduces Defence by damage dealt, then Strength, Attack, Magic and Ranged |
| Arclight / Emberlight | Reduces Defence, Attack and Strength by 5% of base + 1 |
| Bone dagger | Reduces Defence by damage dealt, if Defence is not already lowered |
| Barrelchest anchor | Reduces Defence (then Attack, Ranged, Magic) by 10% of damage dealt |
| Seercull | Reduces Magic by damage dealt, if Magic is not already lowered |
| Accursed sceptre (charged) | Reduces Defence and Magic by 15%, up to 15% total |
| Eye of Ayak (charged) | Reduces the Magic Defence bonus by damage dealt |

## Raid and boss support

| Content | Behaviour |
|---|---|
| **The Fractured Archive** | Per-boss starting and minimum Defence. Ocellus phases are tracked as separate encounters. |
| **Tombs of Amascut** | Maximum drain per boss: Zebak 20, Kephri 20, Akkha 10, Ba-Ba 20, Obelisk 40, Wardens P3 30 (60 once enraged). |
| **Theatre of Blood** | Sotetseg can be drained to a minimum of 100, and his drain resets each time the party enters the shadow realm. Verzik Vitur's Defence cannot be lowered, so she is not tracked. |
| **Chambers of Xeric** | Built-in boss stats, with Challenge Mode detected automatically. The Great Olm's head and claws are tracked separately and reset each phase. Tekton's special drain rules are applied. |

### Drain persistence

- Drains persist until the NPC dies - through form changes, despawns and loading screens.
- NPCs that leave view are kept for a configurable time (default 5 minutes).
- All drains are cleared when a raid room is completed, when you leave a raid, or on
  logout and world hop.

## Configuration

| Setting | Default | Description |
|---|---|---|
| Sync with party | On | Share drains with your RuneLite party |
| Height offset | 40 | Height of the display above the NPC |
| Out of sight (minutes) | 5 | How long an out-of-view NPC is kept (1–25) |
| **Stats above head** | | |
| Attack / Strength / Ranged | Off | Show these stats when drained |
| Defence / Magic | On | Show these stats when drained |
| Magic Defence (Eye of Ayak) | Off | Show Magic Defence bonus reduction |
| **Specs to drain** | | |
| Elder maul / Dragon warhammer / Tonalztics | On | Show in the panel |
| BGS / Arclight / Emberlight / Bone dagger / Barrelchest anchor / Accursed sceptre / Seercull | Off | Show in the panel |

## Party sync

Simple Defence Tracker uses RuneLite's built-in party system - the same party used by
plugins such as Party Panel. Every party member running the plugin shares drains automatically.

- A message is sent only when a drain actually changes a stat.
- Each message carries the resulting values, so duplicate or out-of-order messages can
  never double-count a drain.
- Incoming data is validated, and bursts of messages are merged into a single update.

## Performance and privacy

- Event-driven - no work is done while nothing is being drained.
- No external web requests; all data comes from the game client and built-in tables.
- Party messages contain only NPC and stat values - no player names, locations or account data.

## Building from source

Requires Java 11 or newer.

1. Open the project in IntelliJ IDEA (it imports as a Gradle project).
2. From the Gradle panel, run **runelite > runSimpleDefenceTracker** to launch RuneLite
   with the plugin loaded.

## Feedback

Found a bug, an incorrect drain, or a boss that isn't handled correctly? Please open an
issue on GitHub with the boss name, the weapon used and, if possible, a screenshot.
Suggestions for new features and content are welcome too.

## Credits

- **Lily** - Tester
