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

## MiscContinuousEffect (type 52)

**Source:** sessions `14-15-29` (1 instance), `2026-03-01_00-18-46` (1 instance).

### What the variance report shows

```
MiscContinuousEffect (6 instances, 3 sessions)
  Always:    (no detail keys)
  Sometimes: extra_phases (66%), grpid (66%), MaxHandSize (33%), effect_id (33%)
  Samples:   MaxHandSize=[2147483647], effect_id=[7002], extra_phases=[3], grpid=[100287]
```

### Card 1: Proft's Eidetic Memory (grp:88986) — MaxHandSize

Legendary Enchantment: "You have no maximum hand size."

Forge DSL: `S:Mode$ Continuous | Affected$ You | SetMaxHandSize$ Unlimited`

### Card 2: Aurelia, the Warleader (grp:94079) — extra_phases

Already investigated in AbilityExhausted section.
Ability 100287: "untap all creatures, additional combat phase."

### Arena annotation structure

**Two distinct uses of MiscContinuousEffect:**

**MaxHandSize (Proft's Eidetic Memory):**
```proto
id: 115
affectorId: 347      # Proft's Eidetic Memory
affectedIds: 1       # player seat 1
type: MiscContinuousEffect
type: LayeredEffect   # dual-type annotation!
details:
  MaxHandSize: 2147483647   # int32 max = "unlimited"
  effect_id: 7002           # layered effect ID (synthetic, starts at 7000+)
```

Notable: this is a **dual-type annotation** — both `MiscContinuousEffect` and
`LayeredEffect` on the same proto message. The client dispatches to both parsers.
`MaxHandSize: 2147483647` (INT32_MAX) = "no maximum hand size."

**Extra combat phase (Aurelia):**
```proto
id: 794
affectorId: 383      # Aurelia's triggered ability (on stack, grp:100287)
affectedIds: 1       # player seat 1
type: MiscContinuousEffect
details:
  extra_phases: 3    # Phase enum: 3 = Combat
  grpid: 100287      # the triggering ability
```

`extra_phases: 3` uses the Phase proto enum (`Combat_a549 = 3`).
This tells the client an additional combat phase is pending.

### Lifecycle

**MaxHandSize:** persistent, created when enchantment enters battlefield (gsId=46, T3).
Persists for the rest of the game (never deleted in this session — enchantment stayed
on battlefield). Would be deleted if the enchantment left.

**Extra phases:** persistent, created when Aurelia's trigger resolves (gsId=320, T13 Combat).
Session ends during the extra combat (opponent conceded/disconnected at gsId=330),
so we don't observe cleanup. Expected to be deleted when the extra phase completes
or at turn boundary.

### Forge model gap

**MaxHandSize:** Forge tracks this via `SetMaxHandSize$ Unlimited` on the static ability.
The value is computed dynamically. No event fires — it's a continuous effect.
Would need to detect "unlimited hand size" permanents on BF and emit the annotation
with `effect_id` (synthetic layered effect ID, same as used by LayeredEffect annotations).

**Extra phases:** Forge handles extra combat phases through `GameEventAddPhase` or
similar phase-manipulation events. This is more tractable — the event exists,
we just need to wire it to produce the MiscContinuousEffect annotation.

**Wiring assessment:**
- Extra phases: **Medium** — if Forge fires an event for added phases, wire it.
  The annotation is simple (phase enum + grpid). Main gap: capturing which ability
  caused the extra phase.
- MaxHandSize: **Hard** — dual-type annotation coupling with LayeredEffect system.
  Requires synthetic effect IDs. Same challenge as LayeredEffect wiring.

---

## LayeredEffectDestroyed (type 19)

**Source:** 4 sessions, 15 instances total. Key sessions: `09-33-05` (Prowess), `00-11-05` (Twinblade Paladin), `00-18-46` (game-start noise).

### What the variance report shows

```
LayeredEffectDestroyed (15 instances, 4 sessions)
  Always:    (no detail keys)
  affectedIds always 7000+ range (synthetic layered effect IDs)
```

### Lifecycle: creation → persistence → destruction

LayeredEffectDestroyed is the **teardown event** for transient layered effects. Full lifecycle:

1. **LayeredEffectCreated** — transient annotation, `affectedIds=[7xxx]` (synthetic effect ID)
2. **LayeredEffect** (persistent) — state annotation on the affected card:
   - `affectedIds` = actual card instanceId(s)
   - `effect_id` = the 7xxx synthetic ID
   - May carry: `sourceAbilityGRPID`, `grpid`, types like `ModifiedPower`/`ModifiedToughness`/`AddAbility`
3. **LayeredEffectDestroyed** — transient annotation when the effect expires, `affectedIds=[7xxx]`

The persistent `LayeredEffect` annotation is deleted via `diffDeletedPersistentAnnotationIds` in the same GSM as the `LayeredEffectDestroyed` event.

### Observed patterns

**Pattern 1: Prowess (turn-scoped P/T buff)**
Session `09-33-05`: Otter token (grp:91865, iid=335) with Prowess (abilityGrpId=137).
- gsId=163: `LayeredEffectCreated` → effect 7007. Persistent `LayeredEffect` with `sourceAbilityGRPID: 137, effect_id: 7007` on iid=335, types=[ModifiedToughness, ModifiedPower].
- gsId=180 (T8 Beginning): `LayeredEffectDestroyed` → effect 7007. Prowess buff expires at turn boundary.

**Pattern 2: Twinblade Paladin conditional buff**
Session `00-11-05`: Twinblade Paladin (grp:93652, iid=310).
- gsId=138: `LayeredEffectCreated` → effect 7002 (AddAbility, grpid=3, UniqueAbilityId=183). Paladin gains an ability via layered effect.
- gsId=196: `LayeredEffectCreated` → effect 7003 (ModifiedPower+Toughness on iid=310). P/T modifier from the gained ability.
- gsId=213 (T10 Beginning): `LayeredEffectDestroyed` → effect 7003. P/T buff expires at turn boundary. The AddAbility effect (7002) persists — it's permanent.

**Pattern 3: Game-start noise**
Session `00-18-46`, gsId=1: three effects (7002, 7003, 7004) created AND destroyed in the same GSM. No objects present. No persistent annotations. This appears to be game-initialization bookkeeping — likely default effects that are immediately superseded or cancelled.

### What the client does with this

LayeredEffectDestroyed tells the client to:
1. Remove any VFX associated with the effect (P/T buff glow, ability badge)
2. Stop rendering the layered effect in the card's stat overlay
3. Clean up internal effect tracking for the synthetic ID

### Forge model gap

Forge has layered effects via its static/continuous ability layer but doesn't track synthetic effect IDs. Effects are computed from scratch each time the game state is evaluated — there's no "effect 7007 was created / destroyed" lifecycle.

**Wiring assessment: Hard.** Would require:
1. Synthetic effect ID allocation (a counter starting at 7000+, matching Arena's convention)
2. Tracking which effects exist across GSMs to detect creation/destruction
3. Associating Forge's continuous effects with the synthetic IDs

This is the same fundamental challenge as wiring LayeredEffectCreated — the two are a matched pair. They depend on the same synthetic effect ID infrastructure.

Currently, we emit `powerToughnessModCreated` (which covers the Prowess-like P/T change visible to the player) but NOT the full layered effect lifecycle. The missing piece is cosmetic: VFX teardown when a temporary buff expires.

---

## Cross-references

| Annotation | Cards seen | Sessions | Instances |
|---|---|---|---|
| GainDesignation | Unholy Annex // Ritual Chamber | `00-18-46` | 8 |
| Designation | Unholy Annex // Ritual Chamber | `00-18-46` | 8 |
| AbilityExhausted | Monument to Endurance, Aurelia the Warleader | `09-33-05`, `00-18-46` | 7 |
| AbilityWordActive | Accumulate Wisdom | `09-33-05` | 6 |
| Qualification | Warden of Evos Isle, Silent Hallcreeper | `11-50-40`, `14-15-29` | 6 |
| MiscContinuousEffect | Proft's Eidetic Memory, Aurelia the Warleader | `14-15-29`, `00-18-46` | 6 |
| LayeredEffectDestroyed | Otter token (Prowess), Twinblade Paladin | `09-33-05`, `00-11-05`, `00-18-46` | 15 |

### Related annotation types not yet investigated

| Type | Likely related mechanic | Cards to check |
|---|---|---|
| LoseDesignation (type 103) | Room door de-unlock? Monarch lost? | Not in recordings yet |
| LayeredEffectCreated | Matched pair with Destroyed — same synthetic ID infra | Same sessions |
