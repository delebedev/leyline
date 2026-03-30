# Loot, Exuberant Explorer — Card Spec

## Identity

- **Name:** Loot, Exuberant Explorer
- **grpId:** 93819 (FDN)
- **Set:** FDN
- **Type:** Legendary Creature — Beast Noble
- **Cost:** {2}{G}
- **P/T:** 1/4
- **Color:** Green
- **Forge script:** `forge/forge-gui/res/cardsfolder/l/loot_exuberant_explorer.txt`
- **Ability grpIds:**
  - 13760 — static ability (additional land play)
  - 175871 — activated ability (Dig)
  - 14 — (common/internal)

## Mechanics

| Mechanic | Forge DSL | Protocol signal | Catalog status |
|----------|-----------|-----------------|----------------|
| Legendary supertype | `Types:Legendary Creature` | `ShouldntPlay(Legendary)` annotation | wired |
| Additional land play (static) | `S:Mode$ Continuous \| AdjustLandPlays$ 1` | Extra `ActionType_Play` in actions array | **not implemented** |
| Activated ability — Dig | `A:AB$ Dig \| Cost$ 4 G G T \| DigNum$ 6 \| ChangeNum$ 1` | `AbilityInstanceCreated` → tap → resolution → `ZoneTransfer(Put)` + `Shuffle` | **not implemented** |
| CMC check (lands you control) | `ChangeValid$ Creature.cmcLEX` + `SVar:X:Count$Valid Land.YouCtrl` | Server resolves internally; no explicit annotation for the check | — |

## What it does

1. **Static ability:** You may play an additional land on each of your turns. (Continuous effect, `AdjustLandPlays$ 1`.)
2. **Activated ability** ({4}{G}{G}, {T}): Look at the top six cards of your library. You may reveal a creature card with mana value less than or equal to the number of lands you control from among them and put it onto the battlefield. Put the rest on the bottom in a random order.

## Trace (session 2026-03-30_20-33, seat 1)

Four instanceIds: 162 (in library), 294 (drawn copy, stayed in hand), 307 (cast copy, main actor), 430 (second drawn copy, hand).

### Cast — T6 Main1

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 113 | 162→307 | Hand→Stack | CastSpell, paid {2}{G} (2 generic from Mountain+Rugged Highlands, 1 green from Forest) |
| 115 | 307 | Stack→Battlefield | ResolutionStart + ResolutionComplete (grpid=93819) |
| 115 | 307→294 | — | `ShouldntPlay(Legendary)` — second copy in hand marked unplayable |

### Legendary annotation pattern

`ShouldntPlay` with `Reason=Legendary` emitted from iid 307 (battlefield copy) → iid 294 (hand copy) on every GSM where 294 is in hand. After a second copy (430) was drawn at gs=482, both hand copies are affected: `affectedIds=[294, 430]`.

**71 total ShouldntPlay annotations** across 57 GSMs — the annotation is persistent and re-emitted on every state update, not just once.

### Combat — T11 DeclareBlock

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 254 | 307 | Battlefield | PowerToughnessModCreated from iid 335 (Bulk Up aura): power=1, toughness=0. Loot becomes 2/4 |
| 256 | 307 | Battlefield | Took 3 combat damage from Loxodon Line Breaker (iid 301). Dealt 2 back. Survived (4 toughness > 3 damage) |

### Activation 1 — T20 Main1 (8 lands in play)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 472 | — | — | `ActionType_Activate` offered: instanceId=307, abilityGrpId=175871, manaCost=[{Generic:4}, {Green:2}] |
| 473 | 307→413 | Battlefield | `AbilityInstanceCreated` (grp=175871, source_zone=28). `TappedUntappedPermanent` tapped=1 |
| 473 | — | — | ManaPaid: 3 green (color=5) + 3 generic (color=4) from 6 lands. UserActionTaken actionType=2, abilityGrpId=175871 |
| 475 | 413 | Stack | `ResolutionStart` grpid=175871 |
| 476 | 413→421 | Library→Battlefield | `ObjectIdChanged` 190→421. `ZoneTransfer` zone_src=32 (Library), zone_dest=28 (Battlefield), category=**Put** |
| 476 | — | — | `Shuffle`: OldIds=[188,189,191,192,193] → NewIds=[424..428]. Library remapped |
| 476 | 413 | — | `ResolutionComplete` grpid=175871 + `AbilityInstanceDeleted` |

**Result:** Apothecary Stomper (grp=93812, 4/4 Elephant) put onto battlefield. CMC 6 <= 8 lands.

### Activation 2 — T24 Main1 (9 lands in play)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 568 | 307→453 | Battlefield | `AbilityInstanceCreated` (grp=175871). Tapped. ManaPaid: 3 green + 3 generic (6 total) |
| 571 | 453→461 | Library→Battlefield | `ZoneTransfer` Library→Battlefield, category=**Put**. `Shuffle` |
| 571 | 453 | — | `ResolutionComplete` + `AbilityInstanceDeleted` |

**Result:** Sire of Seven Deaths (grp=93714, 7/7 Eldrazi) put onto battlefield. CMC 7 <= 9 lands.

### Extra land play — T26 Main1

After Loot's static ability granted +1 land, the player had two land plays this turn:

| gsId | What happened |
|------|---------------|
| 611 | `ActionType_Play` for Forest (iid 473) offered in actions |
| 619 | First land played: Forest (grp=58453) Hand→Battlefield, category=PlayLand |
| 624 | **`ActionType_Play` still offered** — Mountain (iid 490) playable |
| 627 | Second land played: Mountain (grp=58449) Hand→Battlefield, category=PlayLand |
| 632 | **`ActionType_Play` still offered** — Gruul Guildgate (iid 496). Third land play available! |

The third land play at gs=632 is unexpected for a single `AdjustLandPlays$ 1`. Possible sources: another "additional land" effect on the battlefield (Primeval Bounty was in play but doesn't grant extra lands), or the game captures a turn where multiple effects stacked. **Needs investigation** — could be Rampaging Baloths landfall trigger interaction or a second source not visible in this trace.

## Trace (session 2026-03-30_20-06, seat 1)

Loot cast late (T16), no activation before game ended.

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 365 | 174→362 | Library→Hand | Drawn |
| 368 | 362→363 | Hand→Stack | CastSpell, paid {2}{G} (1 generic + 2... color=4 + 2×color=5) |
| 370 | 363 | Stack→Battlefield | ResolutionStart + ResolutionComplete. Entered with summoning sickness |

No Legendary conflict in this game — only one copy.

## Key Protocol Findings

### Extra land play mechanism

The server does **not** send any explicit "you have N land plays this turn" field or LayeredEffect for `AdjustLandPlays`. Instead, the extra land is communicated implicitly: the actions array continues to include `ActionType_Play` entries for lands in hand after the first land has been played. The client can play a land as long as the server offers it.

This means leyline must:
1. Track land-play-count adjustments per player per turn
2. After each PlayLand resolves, check remaining land plays before deciding whether to offer another `ActionType_Play`

### Dig ability resolution

The "look at top 6" is server-internal — no cards are revealed to the opponent, no annotations expose the looked-at set. The resolution emits only:
- `ZoneTransfer` (Library→Battlefield, category=`Put`) for the chosen creature
- `Shuffle` for the remaining cards (bottom in random order = shuffle-equivalent)
- No annotation for "put on bottom" — the rest are silently repositioned and then the library is shuffled

### ShouldntPlay scaling

The `ShouldntPlay(Legendary)` annotation scales with copies in hand: `affectedIds` grows from `[294]` to `[294, 430]` when a second copy enters hand. This is re-emitted on **every GSM**, not just transitions.

### Tap cost

The tap is part of the activated ability cost (not a separate annotation). It appears as `TappedUntappedPermanent` with affectorId = the ability instance (413/453), not Loot itself. The ability instance taps its parent.

## Forge Script Decomposition

```
Name:Loot, Exuberant Explorer
ManaCost:2 G
Types:Legendary Creature Beast Noble
PT:1/4
```

**Static ability (line 5):**
```
S:Mode$ Continuous | Affected$ You | AdjustLandPlays$ 1
```
- Continuous static effect targeting the controller
- `AdjustLandPlays$ 1` — adds 1 to the player's land-play-per-turn limit
- No condition — always active while Loot is on the battlefield

**Activated ability (line 6):**
```
A:AB$ Dig | Cost$ 4 G G T | DigNum$ 6 | ChangeNum$ 1 | Optional$ True
  | ChangeValid$ Creature.cmcLEX | DestinationZone$ Battlefield
  | RestRandomOrder$ True
```
- Cost: {4}{G}{G}, tap
- `Dig` — look at top N cards, optionally choose some to move
- `DigNum$ 6` — look at top 6
- `ChangeNum$ 1` — choose up to 1
- `Optional$ True` — you *may* put it (can decline all 6)
- `ChangeValid$ Creature.cmcLEX` — must be a creature with CMC <= X
- `SVar:X:Count$Valid Land.YouCtrl` — X = number of lands you control
- `DestinationZone$ Battlefield` — chosen card goes to battlefield
- `RestRandomOrder$ True` — remaining cards go to bottom in random order

## Gaps for leyline

1. **`AdjustLandPlays` static effect (NEW MECHANIC).** No existing support for modifying per-turn land play count. Need to:
   - Track base land plays (1) + adjustments per player per turn
   - After each PlayLand, decrement remaining count
   - Only offer `ActionType_Play` for lands when remaining count > 0
   - Reset at turn start

2. **Dig ability (NEW MECHANIC).** The `AB$ Dig` pattern — look at top N, choose up to M matching a filter, put to a zone, rest to bottom. Components needed:
   - Library peek (top N)
   - Filter by `ChangeValid` (creature type + CMC comparison)
   - Optional selection (0 or 1)
   - Zone transfer with category=`Put` (not CastSpell)
   - Bottom-of-library placement + shuffle (`RestRandomOrder`)

3. **Dynamic CMC filter.** The filter references `SVar:X:Count$Valid Land.YouCtrl` — needs real-time evaluation of lands-you-control at resolution time, not cast time.

4. **ShouldntPlay(Legendary) scaling.** Already implemented for single copies; verify it handles growing `affectedIds` when multiple copies enter hand.

## Supporting evidence needed

- [ ] Confirm the third land play at gs=632 — identify the source (another effect or protocol artifact)
- [ ] Test `Optional$ True` on Dig — game where player declines all 6 cards (no legal target or player chose not to)
- [ ] Other `AdjustLandPlays` cards (e.g., Oracle of Mul Daya, Dryad of the Ilysian Grove) for protocol comparison
- [ ] Puzzle: Loot activation with exactly N lands and a creature with CMC=N to verify boundary condition

---

## Tooling Feedback

### What worked well

- **`just scry-ts trace "Loot" --game`** — excellent overview. 71 annotations across 57 GSMs instantly organized by turn/phase. The ShouldntPlay flood was immediately visible as a pattern. This is the entry point for any card investigation.
- **`just scry-ts gsm show N --game --json | jq`** — essential for drilling into specific GSMs. The ability instance structure (grpId, parentId, objectSourceGrpId) was clear in the JSON. Worked reliably with every gsid tested.
- **`just scry-ts board --game --gsid`** — excellent for context. Showed Loot as 2/4 in combat (Bulk Up buff), confirmed what creature was put onto battlefield, revealed hand contents for ShouldntPlay analysis. The "(T)" tapped and "(sick)" summoning sickness markers are very helpful.
- **`just scry-ts gsm list --game --view actions`** — the actions view was the single most useful tool for understanding the extra land play mechanism. Seeing `PlayLand` + `Put` in chronological order made the ability's effect obvious.
- **`just scry-ts game notes`** — having the notes from the play session gave me exact gsids to start from. Without the "gs=499 Loot activated ability" note, I would have had to scan the entire trace.
- **`--game` substring matching** — convenient and reliable.

### What was missing or awkward

- **No card names in trace annotations.** The trace shows `from=335 → [307]` for PowerToughnessModCreated but doesn't name instance 335. Had to `gsm show --json | jq` to discover it was Bulk Up. Same for combat damage — `from=301 → [307]` required a separate lookup to identify Loxodon Line Breaker. **Upvote: card name resolution in trace affector/affected IDs would eliminate 50%+ of my gsm show round-trips.**
- **No abilityGrpId → ability text lookup.** The trace shows `abilityGrpId=175871` but I had to find the Forge script to know what that ability does. An inline label like `175871 (Dig: look at top 6...)` would be a major time saver. **Upvote: abilityGrpId → ability text lookup.**
- **Opponent zone labeling in trace.** The trace says `zone_src=Library (seat 1)` which is fine, but when the spec talks about zones I had to manually verify zone 32=Library(seat1), zone 28=Battlefield etc. The board command handles this well; the trace could too. **Upvote: ours/theirs labeling.**
- **No way to see "what cards were looked at" during Dig resolution.** The server resolves it internally — no annotation for the 6 cards peeked. This is a protocol limitation, not a tooling issue, but a note in the trace like "⚠ server-internal resolution, no peek data" would set expectations.
- **`trace --json`** not supported. I used `gsm show --json` per-GSM instead, which works but is slower for scripted analysis. The human-readable trace is great for reading but hard to pipe.
- **Actions view doesn't show the "Put" card name.** The gsm list actions view says `Put Apothecary Stomper` which is actually great — but it's resolving the grpId to a name already. The trace should do the same for its annotations.

### What I'd add

- **`gsm diff N M --game`** — compare two GSMs side by side. Would have been useful to compare activation 1 vs activation 2 resolution patterns.
- **`trace --filter type=ManaPaid,ZoneTransfer`** — filter by annotation type in trace output to focus on specific mechanics.
