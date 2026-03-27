# Kaito Shizuki — Card Spec

## Identity

- **Name:** Kaito Shizuki
- **grpId:** 79665 (also 79762, 79838 — alternate printings)
- **Set:** NEO
- **Type:** Legendary Planeswalker — Kaito
- **Cost:** {1}{U}{B}, Loyalty 3
- **Forge script:** `forge/forge-gui/res/cardsfolder/k/kaito_shizuki.txt`

## Mechanics

| Mechanic | Forge DSL | Forge event | Catalog status |
|----------|-----------|-------------|----------------|
| Cast planeswalker | `ManaCost:1 U B`, `Loyalty:3` | (standard cast) | wired |
| Loyalty counters | `AddCounter<1/LOYALTY>`, `SubCounter<2/LOYALTY>`, `SubCounter<7/LOYALTY>` | (counter events) | wired |
| ETB phaseout trigger | `T:Mode$ Phase \| Phase$ End of Turn \| IsPresent$ Card.Self+ThisTurnEntered` | `GameEventCardPhased` | **missing** |
| Phase out | `SVar:TrigPhase:DB$ Phases \| Defined$ Self` | `GameEventCardPhased` | **missing** |
| +1: Draw | `AB$ Draw \| NumCards$ 1` | `GameEventCardDraw` | wired |
| +1: Conditional discard (Raid) | `DB$ Discard \| ConditionCheckSVar$ RaidTest \| ConditionSVarCompare$ EQ0` | `GameEventDiscarded` | wired (draw/discard), **partial** (Raid condition) |
| -2: Token creation | `AB$ Token \| TokenScript$ u_1_1_ninja_unblockable` | `GameEventTokenCreated` | wired |
| -2: Unblockable keyword | (on token script) | — | wired |
| -7: Emblem (combat damage trigger) | `AB$ Effect \| Ultimate$ True` | — | **missing** (emblem zone not mapped) |
| -7: Library search to battlefield | `DB$ ChangeZone \| Origin$ Library \| Destination$ Battlefield` | `GameEventCardChangeZone` | wired (mechanic), **missing** (emblem source) |

**Unobserved:** -2 Ninja token creation and -7 emblem ultimate were not activated in this session. Both need dedicated recording or puzzle.

## What it does

1. **ETB phaseout trigger:** At the beginning of your end step, if Kaito entered the battlefield this turn, he phases out. This protects him from sorcery-speed removal on the turn he's cast.
2. **Phase in:** Kaito phases back in during your next untap step. Phasing in does NOT count as entering the battlefield (no re-trigger of the phaseout ability).
3. **+1 loyalty:** Draw a card. Then discard a card unless you attacked this turn (Raid check — if you declared attackers, skip the discard).
4. **-2 loyalty:** Create a 1/1 blue Ninja creature token with "This creature can't be blocked."
5. **-7 loyalty (ultimate):** Create an emblem: "Whenever a creature you control deals combat damage to a player, search your library for a blue or black creature card, put it onto the battlefield, then shuffle."

## Trace (session 2026-03-27_21-15-58, seat 1)

Kaito cast once by seat 1 (human) in game 3. UBR deck. One phaseout/phasein cycle observed. +1 loyalty activated once (draw + discard — Raid condition NOT met, so discard occurred). No -2 or -7 activations in this game.

### Cast and resolve

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 2 | 159 | Hand (31) | Opening — in hand |
| 197 | 159→320 | Hand→Stack (31→27) | Cast: ObjectIdChanged (159→320), ZoneTransfer category=CastSpell |
| 199 | 320 | Stack→Battlefield (27→28) | Resolve: enters BF with loyalty 3, CounterAdded (type=7, amount=3) |

Kaito enters with 4 unique abilities: 148075 (ETB phaseout trigger), 148076 (+1 draw/discard), 148078 (-2 Ninja token), 148080 (-7 emblem).

### +1 loyalty activation

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 215 | 320→331 (ability) | Battlefield→Stack | AbilityInstanceCreated (grpId=148076), CounterAdded (type=7, amount=1) — loyalty 3→4 |
| 216 | 332 | Library→Hand (32→31) | Draw: ZoneTransfer category=Draw, affectorId=331 |
| 218 | 319→333 | Hand→Graveyard (31→33) | Discard: ObjectIdChanged (319→333), ZoneTransfer category=Discard. Raid NOT met — discard happened |
| 218 | 331 | — | ResolutionComplete (grpId=148076), AbilityInstanceDeleted |

loyaltyUsed shows `value: 1` after activation — tracks that one activation was used this turn.

### Phaseout (ETB trigger)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 220 | — | — | Turn phase transitions to Ending/End step (turn 9) |
| 222 | 320 | Battlefield→zone12 | ETB trigger fires: AbilityInstanceCreated for triggered ability (334, grpId=148075), **PhasedOut** annotation (id=494, affectorId=334, affectedIds=[320]), ResolutionComplete (grpId=148075). Kaito moves to zoneId 12. loyalty=4, loyaltyUsed={value:1} |

### Phase in

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 252 | 320 | zone12→Battlefield | **PhasedIn** annotation (id=566, affectedIds=[320]). No affectorId (system action). Occurs at Beginning/Upkeep of turn 11. No ETB re-trigger. |

### Annotations

**Phaseout (gsId 222):**
- `PhasedOut_af5a` (type 95) — affectorId=334 (the trigger ability instance), affectedIds=[320]. No detail keys. Minimal annotation — just the type and IDs.
- Trigger ability 334 (grpId=148075) created, resolves, deleted in same GSM batch.

**Phasein (gsId 252):**
- `PhasedIn` (type 96) — affectedIds=[320]. No affectorId, no details. Even simpler than PhasedOut.
- Fires alongside TappedUntappedPermanent (untap step) for other permanents.

**Loyalty counters (gsId 199):**
- `CounterAdded` (type 16) — counter_type=7 (Loyalty), transaction_amount=3. Initial loyalty on ETB.

**+1 cost (gsId 215):**
- `CounterAdded` (type 16) — counter_type=7, transaction_amount=1. Loyalty +1 cost payment.

### Key findings

- **Zone 12 is the phaseout zone.** Not a standard zone type — Kaito's gameObject reports `zoneId: 12` while phased out. The zone itself doesn't appear in the zones array with a named type.
- **PhasedOut/PhasedIn are annotation-only.** No ZoneTransfer annotation accompanies phasing — the zone change is implicit in the gameObject diff. This is different from all other zone changes (cast, resolve, die, exile) which emit ZoneTransfer annotations.
- **PhasedIn has no affectorId.** PhasedOut references the trigger ability instance; PhasedIn is system-driven with no source attribution.
- **Loyalty activation tracking:** `loyaltyUsed` field on the gameObject tracks how many activations were used this turn (not which ability). Resets presumably at turn start.
- **Raid condition is server-side.** The discard happens as a normal ZoneTransfer; the server decides whether to fire it based on attack history. No client-visible annotation distinguishes "Raid met" vs "Raid not met" — the discard simply doesn't appear if Raid is satisfied.

## Gaps for leyline

1. **PhasedOut/PhasedIn annotations (types 95/96) not implemented.** Catalog confirms missing. Need: annotation collector for both types, zone 12 handling in zone tracker, visual state for phased-out permanents.
2. **GameEventCardPhased not wired to nexus.** Forge fires `GameEventCardPhased(card, phaseState)` but leyline's EventBus collector doesn't handle it. Wire it to emit PhasedOut/PhasedIn annotations.
3. **Zone 12 (phaseout zone) not mapped.** Rosetta table and zone tracker need a zone12 entry. Phased-out permanents should be invisible to the client but retain their state.
4. **No ZoneTransfer for phasing.** Unlike all other zone changes, phasing uses annotation-only signaling. The zone change must be communicated via gameObject diff (zoneId field change) rather than ZoneTransfer annotation.
5. **Raid / turn-scoped ability words.** Catalog notes "Raid, Morbid, Descended need event-driven detection." The +1 conditional discard works because Forge handles Raid server-side, but leyline needs to track attack history to correctly skip the discard.
6. **LoyaltyActivationsRemaining (type 97) not implemented.** Catalog lists it as unwired. The `loyaltyUsed` field on gameObject is the source data.
7. **Emblem zone not mapped.** The -7 ultimate creates an emblem in the Command zone. Catalog notes "Emblem zone not mapped" under cast-planeswalker. Unobserved — needs a recording or puzzle where the ultimate fires.

## Supporting evidence needed

- [ ] Recording with -2 activation (Ninja token creation) — confirms TokenCreated annotation shape for PW-sourced tokens
- [ ] Recording with -7 activation (emblem) — confirms emblem zone handling, combat damage trigger from emblem
- [ ] Cross-reference: other phasing cards (e.g. Teferi's Protection, Guardian of Ghirapur) for PhasedOut/PhasedIn annotation consistency
- [ ] Puzzle: Kaito +1 with Raid met (attacked this turn) — confirms discard is skipped (no ZoneTransfer)
