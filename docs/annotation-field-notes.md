# Annotation Field Notes

Findings from investigating real Arena server annotations against Forge's engine.
Each section traces one annotation type from variance report → raw proto → Forge mechanics.

These are research notes, not implementation specs. They capture *how Arena models things*
and *where Forge's model differs* — the gap analysis needed before wiring.

---

## GainDesignation / Designation (types 46, 45)

**Source:** session `2026-03-01_00-18-46`, gsId=100 and gsId=261.

### What the variance report shows

```
GainDesignation (8 instances, 2 sessions)
  Always:    {DesignationType}
  Samples:   DesignationType=[19, 20]
  affectedIds=[310 -> grp:92196]  ← Unholy Annex // Ritual Chamber
```

### Card: Unholy Annex // Ritual Chamber (grp:92196)

Duskmourn Room enchantment. Two halves:
- **Left room** (Unholy Annex, grp:92197): end-step draw + conditional life drain
- **Right room** (Ritual Chamber, grp:92198): unlock trigger creates 6/6 Demon token

Forge DSL: `AlternateMode:Split` with `UnlockDoor` triggers.

### What Arena actually does

Arena models **Room door unlocks as Designations** on the enchantment.

**gsId=100 — left door unlocked (Unholy Annex cast and resolved):**
- Transient: `GainDesignation` with `DesignationType=19`, `affectedIds=[310]`
- Persistent: `Designation` with `DesignationType=19`, `affectorId=310`, `affectedIds=[310]`
- Sibling annotations: `ResolutionStart`/`Complete` for grp:92197, `LayeredEffectCreated` ×2, `ZoneTransfer` (Resolve)

**gsId=261 — right door unlocked (5 mana activated ability):**
- Transient: `GainDesignation` with `DesignationType=20`, `affectorId=350` (ability 174405 = unlock trigger)
- Persistent: `Designation` with `DesignationType=20` — **replaces** old (id=242 in `diffDeletedPersistentAnnotationIds`)
- Triggers ability 174405: "When you unlock this door, create a 6/6 black Demon creature token with flying"

### Key insight: DesignationType is NOT Monarch/Initiative

`DesignationType=19,20` are **Room door unlock states**, not player-wide designations.
The `affectedIds` is the Room enchantment instanceId, not a player seat.

This means the Designation annotation system carries at least two distinct uses:
1. **Room door unlocks** — per-card state, `affectedIds` = enchantment
2. **Classic designations** (Monarch, Initiative, City's Blessing) — per-player state, `affectedIds` = player seat (not yet observed in recordings)

### Forge model gap

Forge uses `UnlockDoor` trigger mode + `AlternateMode:Split` for Rooms.
No `GameEventPlayerDesignation` fires for door unlocks — those are for Monarch etc.
Room unlock is implicit in the `AlternateMode` resolution path.

To wire: need to detect Room door resolution events (left vs right half resolving)
and emit GainDesignation/Designation with appropriate type values.
DesignationType enum values (19, 20) appear to be per-card, not a global enum —
need more recordings with different Room cards to confirm.

---

## AbilityExhausted (type 82)

**Source:** sessions `09-33-05` (3 instances), `2026-03-01_00-18-46` (1 instance).

### What the variance report shows

```
AbilityExhausted (7 instances, 3 sessions)
  Always:    {AbilityGrpId, UniqueAbilityId, UsesRemaining}
  Samples:   AbilityGrpId=[137955, 137955,138314, 137955,138314,176655]
             UniqueAbilityId=[205, 215]
             UsesRemaining=[0]
```

### Card 1: Monument to Endurance (grp:95039)

Foundations artifact: "Whenever you discard a card, choose one that hasn't been chosen this turn —
Draw a card / Create a Treasure token / Each opponent loses 3 life."

Forge DSL: `DB$ Charm | ChoiceRestriction$ ThisTurn` — three modes, each once-per-turn.

**Abilities (from CardDatabase):**
| AbilityGrpId | Text |
|---|---|
| 137955 | Draw a card. |
| 138314 | Create a Treasure token. |
| 176655 | Each opponent loses 3 life. |

### Card 2: Aurelia, the Warleader (grp:94079)

"Whenever Aurelia attacks for the first time each turn, untap all creatures you control.
After this phase, there is an additional combat phase."

Single once-per-turn triggered ability (abilityGrpId=100287).

### Arena annotation lifecycle

**Persistent annotation with accumulating repeated field.**

gsId=139 (first mode chosen — "Draw a card"):
```proto
id: 408
affectorId: 294     # Monument to Endurance
affectedIds: 294    # same card
type: AbilityExhausted
details:
  UniqueAbilityId: 205     # exhaustion scope identifier
  UsesRemaining: 0         # always 0 (mode fully used)
  AbilityGrpId: [137955]   # repeated int32 — one ability exhausted
```

gsId=145 (second mode chosen — "Create a Treasure token"):
```proto
id: 408                    # same annotation ID — replaced, not new
AbilityGrpId: [137955, 138314]   # list grows
```

gsId=152 (third mode chosen — "Each opponent loses 3 life"):
```proto
id: 408
AbilityGrpId: [137955, 138314, 176655]   # all three exhausted
```

**Turn boundary:** annotation deleted (not present in next turn's GSMs).
`diffDeletedPersistentAnnotationIds` removes it during turn cleanup.

**Aurelia (single ability):** same pattern but no accumulation — just
`AbilityGrpId: [100287]` once per turn, deleted at turn boundary.

### Proto structure detail

`AbilityGrpId` is a **repeated int32** in `KeyValuePairInfo`, not comma-separated.
The variance report's `137955,138314` notation reflects multiple `valueInt32` entries
in the same detail key. This is the standard proto repeated field pattern:

```proto
details {
  key: "AbilityGrpId"
  type: Int32
  valueInt32: 137955
  valueInt32: 138314
  valueInt32: 176655
}
```

### Forge model gap

**Monument to Endurance:** `ChoiceRestriction$ ThisTurn` on Charm tracks which modes
were chosen this turn. The restriction is enforced internally — Forge won't offer
an already-chosen mode. But there's no `GameEvent` when a mode is exhausted.

**Aurelia:** `FirstTimeTurn` mode on triggered ability. Forge tracks activation
count internally but doesn't fire an event when the ability is "used up."

**Ability identity bridge:** the core challenge. Forge's `SpellAbility` / SVar system
doesn't map to Arena's `abilityGrpId` numbering. Monument's three Charm modes are
SVars (`DBDraw`, `DBToken`, `DBLoseLife`) — they don't have stable numeric IDs.
Wiring AbilityExhausted requires either:
1. A per-GSM scan of cards with once-per-turn restrictions, deriving exhausted abilities
2. A new NexusGameEvent emitted when `ChoiceRestriction$ ThisTurn` narrows available modes
3. An ability identity mapping layer (Forge SVar → grpId) — useful beyond just this annotation

Option 2 is most aligned with existing patterns. Option 3 is the most reusable but
also the largest investment.

---

## Cross-references

| Annotation | Cards seen | Sessions | Instances |
|---|---|---|---|
| GainDesignation | Unholy Annex // Ritual Chamber | `00-18-46` | 8 |
| Designation | Unholy Annex // Ritual Chamber | `00-18-46` | 8 |
| AbilityExhausted | Monument to Endurance, Aurelia the Warleader | `09-33-05`, `00-18-46` | 7 |

### Related annotation types not yet investigated

| Type | Likely related mechanic | Cards to check |
|---|---|---|
| LoseDesignation (type 103) | Room door de-unlock? Monarch lost? | Not in recordings yet |
| AbilityWordActive (type ?) | Threshold/conditional ability highlight | grp:97318 in `09-33-05` |
| Qualification (type 42) | Party/condition tracking | grp:75479 in `11-50-40` |
