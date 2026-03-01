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

## AbilityWordActive (type unknown — not in our proto enum)

**Source:** session `09-33-05`, gsId=85/144/151/159/168 (6 instances, 1 session).

### What the variance report shows

```
AbilityWordActive (6 instances, 1 sessions)
  Always:    {AbilityGrpId, AbilityWordName, threshold, value}
  Samples:   AbilityGrpId=[192590]
             AbilityWordName=[NumberOfLessonCardsInYourGraveyard]
             threshold=[3], value=[1, 2, 3]
```

### Card: Accumulate Wisdom (grp:97318)

Foundations instant (Lesson subtype): "Look at the top three cards of your library.
Put one into your hand… Put each of those cards into your hand instead if there are
three or more Lesson cards in your graveyard."

Forge DSL: `SVar:X:Count$Compare Y GE3.3.1` / `SVar:Y:Count$ValidGraveyard Lesson.YouOwn`
— conditional dig count based on graveyard Lesson count.

Ability 192590 = the card's main spell ability.

### Arena annotation lifecycle

**Persistent annotation tracking a conditional threshold.**

All instances have `affectorId` = `affectedIds` = the card's instanceId.
The annotation **persists across GSMs** and updates as the underlying value changes:

| gsId | instanceId | value | threshold | Notes |
|---|---|---|---|---|
| 85 | 299 | 1 | 3 | 1 Lesson in graveyard. Threshold not met. |
| 144 | 299 | 2 | 3 | 2 Lessons. Still not met. |
| 151 | 299 | 3 | 3 | 3 Lessons — **threshold met**. Enhanced mode unlocked. |
| 159 | 336 | 3 | 3 | Same card, new instanceId (zone transfer). Still met. |
| 168 | 299 | 4 | 3 | Back to original instanceId. Value exceeds threshold. |
| 168 | 345 | 4 | 3 | Second copy in hand also gets the annotation. |

**Key detail:** the annotation ID (297) stays constant for the same instanceId —
it's updated in place, not replaced. When the card moves zones and gets a new
instanceId (336), a new annotation (id changes) is created.

### Proto structure

```proto
id: 297
affectorId: 299
affectedIds: 299
type: AbilityWordActive
details:
  threshold: 3                                      # int32 — the number to meet
  value: 1                                          # int32 — current count (updates)
  AbilityGrpId: 192590                              # the ability being tracked
  AbilityWordName: "NumberOfLessonCardsInYourGraveyard"  # string — condition name
```

### What the client does with this

The `AbilityWordName` string is a **named condition** the client resolves into a UI badge.
When `value >= threshold`, the client highlights the card to indicate its enhanced mode
is active — the "ability word" glow effect (similar to how Delirium or Revolt cards
glow when their condition is met).

### Forge model gap

Forge evaluates the condition at resolution time (`Count$Compare Y GE3.3.1`), not as
persistent state. The `SVar` system computes it dynamically — there's no tracked
"current Lesson count" that updates across GSMs.

**Wiring options:**
1. Per-GSM scan of cards in hand/zones with conditional abilities, evaluate their SVars,
   emit AbilityWordActive when conditions change
2. New NexusGameEvent when graveyard/battlefield state changes affect ability word conditions
3. Skip — purely cosmetic (glow effect), no gameplay impact

Low priority. The complexity is in identifying which cards have ability word conditions
and mapping their SVars to the `AbilityWordName` + `threshold` + `value` triple.

---

## Qualification (type 42)

**Source:** sessions `11-50-40` (2 instances), `14-15-29` (1 instance).

### What the variance report shows

```
Qualification (6 instances, 2 sessions)
  Always:    {QualificationSubtype, QualificationType, SourceParent, grpid}
  Samples:   QualificationSubtype=[0]
             QualificationType=[1, 32]
             SourceParent=[293, 331, 360]
             grpid=[20230, 62969]
```

### Card 1: Warden of Evos Isle (grp:75479) — QualificationType=1

2/2 Flying Bird Wizard: "Creature spells with flying you cast cost {1} less to cast."

Forge DSL: `S:Mode$ ReduceCost | ValidCard$ Creature.withFlying | Type$ Spell | Amount$ 1`

`grpid=20230` = abilityId for the cost reduction ability.

### Card 2: Silent Hallcreeper (grp:92142) — QualificationType=32

1/1 Enchantment Creature Horror: "Silent Hallcreeper can't be blocked."
Also has a `ChoiceRestriction$ ThisGame` charm on combat damage.

Forge DSL: `S:Mode$ CantBlockBy | ValidAttacker$ Creature.Self`

`grpid=62969` = abilityId for "can't be blocked."

### Arena annotation structure

**Two different QualificationTypes with different semantics:**

**QualificationType=1 (cost reduction):**
```proto
id: 197
affectorId: 293      # Warden of Evos Isle (the source)
affectedIds: 2       # player seat 2 (the beneficiary)
type: Qualification
details:
  SourceParent: 293   # same as affectorId
  grpid: 20230        # the cost reduction ability
  QualificationSubtype: 0
  QualificationType: 1
```
The `affectedIds` is **player seat**, not a card. The client uses this to show
which player benefits from cost reduction and from which source.

**QualificationType=32 (evasion — can't be blocked):**
```proto
id: 199
affectorId: 360      # Silent Hallcreeper (the source)
affectedIds: 360     # same card (self-referencing)
type: Qualification
details:
  SourceParent: 360
  grpid: 62969        # the "can't be blocked" ability
  QualificationSubtype: 0
  QualificationType: 32
```
Here `affectedIds` = `affectorId` = **the creature itself**. Self-referencing — the
card qualifies itself as having the evasion property.

### Lifecycle

Persistent annotation. Appears when the source permanent is on the battlefield.
Second Warden (iid=331, T10) gets the same annotation — it persists as long as
the permanent exists.

### What the client does with this

Qualifications are the client's way of tracking **which static abilities apply to whom**.
The client parses `QualificationType` to determine the category:
- Type 1 = cost modification (show discount indicator)
- Type 32 = combat evasion (show unblockable indicator)

The `grpid` links back to the specific ability granting the qualification.

### Forge model gap

Forge handles cost reduction (`ReduceCost`) and evasion (`CantBlockBy`) through its
static ability layer. These are computed dynamically during gameplay — no explicit
"qualification" data structure.

**Wiring assessment:** Hard. Would require scanning all permanents with static abilities
each GSM, determining which qualifications they produce, and emitting the right
QualificationType enum values. The QualificationType values (1, 32, etc.) are Arena-specific
and not documented in our proto — would need more recordings to build a complete mapping.

Low priority — purely visual (cost/evasion badges). The mechanics already work correctly.

---

## Cross-references

| Annotation | Cards seen | Sessions | Instances |
|---|---|---|---|
| GainDesignation | Unholy Annex // Ritual Chamber | `00-18-46` | 8 |
| Designation | Unholy Annex // Ritual Chamber | `00-18-46` | 8 |
| AbilityExhausted | Monument to Endurance, Aurelia the Warleader | `09-33-05`, `00-18-46` | 7 |
| AbilityWordActive | Accumulate Wisdom | `09-33-05` | 6 |
| Qualification | Warden of Evos Isle, Silent Hallcreeper | `11-50-40`, `14-15-29` | 6 |

### Related annotation types not yet investigated

| Type | Likely related mechanic | Cards to check |
|---|---|---|
| LoseDesignation (type 103) | Room door de-unlock? Monarch lost? | Not in recordings yet |
| MiscContinuousEffect (type 52) | Extra phases/turns, max hand size | grp:100287 (Aurelia) in `14-15-29` |
