## AddAbility — field note

**Status:** NOT WIRED (builder exists at `AnnotationBuilder.kt:540`, never called from pipeline)
**Instances:** 60 across 7 sessions (37 unique across all sessions after dedup)
**Proto type:** AnnotationType.AddAbility_af5a (type 9)
**Field:** persistentAnnotations only — never appears in transient `annotations`

### What it means in gameplay

Marks a continuous grant of a keyword or triggered ability to a permanent. The granting source can be an aura, a creature with a lord/anthem effect, a resolved instant/sorcery (affector stays as its graveyard instance), or even a land with a static ability. The client uses this annotation to display the keyword badge on the affected card and know which effect is responsible.

Co-typed with `LayeredEffect` in every observed instance. The companion `LayeredEffectCreated` transient appears in the same GSM, and the effect is retired via `LayeredEffectDestroyed` + deletion from `diffDeletedPersistentAnnotationIds` when it expires.

### Detail keys

| Key | Always/Sometimes | Values seen | Meaning |
|-----|-----------------|-------------|---------|
| `grpid` | Always | 1, 3, 6, 8, 9, 10, 12, 104, 141939, 142, 147951, 169817, 103667 | Ability grpId(s) being granted. Small values are keyword ability IDs (Deathtouch=1, Double Strike=3, First Strike=6, Flying=8, Haste=9, Hexproof=10, Lifelink=12, Indestructible=104, Menace=142). Large values are triggered/other ability grpIds. When multiple abilities are granted simultaneously, this is an array. |
| `effect_id` | Always | 7002–7030+ | Synthetic effect ID linking to the companion `LayeredEffect` / `LayeredEffectCreated` annotations. Same ID used in those annotations. |
| `UniqueAbilityId` | Always | sequential ints (157–249 range observed) | Per-ability-grant unique instance counter. Monotonically increasing per match. When multiple abilities are granted at once, this is an array (one ID per ability). When the same effect applies to a new creature, a new `UniqueAbilityId` is added to the array while the previous IDs remain. |
| `originalAbilityObjectZcid` | Always | equals `affectorId` | Instance ID of the source object. In all 37 unique instances observed, this exactly equals `affectorId`. When `grpid` is an array (multi-ability grant), this is an array of the same affectorId repeated once per ability. |
| `sourceAbilityGRPID` | Sometimes (14/37 = 38%) | triggered ability grpIds | Present when the grant originates from a triggered ability rather than a static one. Identifies the specific triggered ability that fired (e.g. 146835 = Halana and Alena "at beginning of combat, put counters and grant haste"). Absent for static-ability grants (auras, lord effects). |

### Shape

- **Type array:** always `["AddAbility", "LayeredEffect"]` (always multi-typed, never single)
- **affectorId:** the source permanent's instanceId (aura, creature with lord effect, resolved instant now in zone 30/Limbo, or land with static ability)
- **affectedIds:** always 1 element — the creature receiving the ability grant
  - Self-grant case: `affectedIds[0] == affectorId` (e.g. Mountain granting Deathtouch to itself via static ability)
  - Cross-grant case: `affectedIds[0] != affectorId` (normal — aura or lord granting to another creature)
- **details:** always contains `grpid`, `effect_id`, `UniqueAbilityId`, `originalAbilityObjectZcid`; optionally `sourceAbilityGRPID`

**Multi-ability grant:** when a single effect grants N abilities simultaneously, `grpid`, `UniqueAbilityId`, and `originalAbilityObjectZcid` become arrays of length N. `affectedIds` remains a single creature. Seen with 2 abilities (Flying+FirstStrike from Island land, Lifelink+Ward from Enchantment). `originalAbilityObjectZcid` is repeated N times (always equal to `affectorId`).

**Cumulative UniqueAbilityId growth:** for effects that re-apply each combat (e.g. Diamond Pick-Axe granting a triggered ability per attacker), the annotation's `UniqueAbilityId` array grows with each new creature being granted the ability. The snapshot shows only the most recently affected creature in `affectedIds`, but `UniqueAbilityId` accumulates across all creatures that have received the grant since effect creation.

### Triggers

- **Aura enters battlefield** (AttachmentCreated fires) → AddAbility appears in same GSM. affectorId = aura. Cards: Ethereal Armor (51307).
- **Triggered ability fires at beginning of combat** → AddAbility appears when combat begins. affectorId = granting creature. Cards: Halana and Alena Partners (94087), Courageous Goblin (93795), Angelic Guardian (93942).
- **Instant/sorcery resolves granting keywords** → AddAbility persists after spell resolves (affectorId is graveyard instance). Cards: resolved instants with "until end of turn" ability grants.
- **Land/permanent with static ability enters** → AddAbility appears when permanent enters battlefield. affectorId = land or permanent. Observed: non-basic Islands and Mountains with keyword-granting abilities.
- **Creature with lord effect applies** → AddAbility appears on affected creatures. affectorId = lord creature.

### Lifecycle

1. Source effect is created: `LayeredEffectCreated` (transient) fires in same GSM as `AddAbility` (persistent). Both share `effect_id`.
2. `AddAbility` persists across all subsequent GSMs while the grant is active.
3. When the effect ends (aura leaves, end-of-turn, creature dies): `LayeredEffectDestroyed` (transient) fires and the `AddAbility` annotation's ID appears in `diffDeletedPersistentAnnotationIds`.

The `effect_id` is the shared link across all three lifecycle events.

### Cards observed

| Card | grpId | Role | Ability granted (grpid) | Session |
|------|-------|------|------------------------|---------|
| Ethereal Armor | 51307 | affector (Aura) | 6 (First Strike), sometimes 3 (Double Strike via sourceAbilityGRPID=172172) | 2026-03-10_08-23-48, 2026-03-11_16-13-23 |
| Halana and Alena, Partners | 94087 | affector (Creature) | 9 (Haste) | 2026-03-11_16-13-23 |
| Courageous Goblin | 93795 | affector (Creature) | 142 (Menace) | 2026-03-11_16-13-23 |
| Angelic Guardian | 93942 | affector (Creature/lord) | 104 (Indestructible) | 2026-03-11_16-13-23 |
| Diamond Pick-Axe | 87292 | affector (Equipment/Creature) | 147951 (triggered ability) | 2026-03-17_20-34-31 |
| Gimli-related Enchantment | 69108 | affector (Enchantment) | 8 (Flying) | 2026-03-08_18-45-37-TUTORIAL |
| Non-basic Mountain | 95085 | affector (Land, self-grant) | 1 (Deathtouch) | 2026-03-11_16-13-23 |
| Non-basic Island | 75554 | affector (Land) | [8, 6] (Flying + First Strike) | 2026-03-11_16-13-23 |

### Our code status

- Builder: exists — `AnnotationBuilder.addAbility(instanceId, grpId, effectId, uniqueAbilityId, originalAbilityObjectZcid)` at line 540.
- Missing from builder: `affectorId` param (not set in current implementation — proto builder never calls `setAffectorId`).
- Missing from builder: multi-value support for `grpid`, `UniqueAbilityId`, `originalAbilityObjectZcid` arrays (multi-ability grants need list-typed details).
- Missing from builder: `sourceAbilityGRPID` param (needed for triggered-ability grant cases, 38% of instances).
- Co-type: builder only adds `AddAbility_af5a` — it does not add `LayeredEffect` type. Real server always sends both types.
- Emitted in pipeline: no — builder is never called from `StateMapper`, `AnnotationPipeline`, or any production code path.
- Test coverage: `AnnotationBuilderTest:514` verifies the detail keys exist; `AnnotationShapeConformanceTest:131` verifies the key set.

### Dependencies

- **`LayeredEffect`** (persistent, type 51) — always co-typed in the same annotation object. Current `layeredEffect()` builder emits only `[ModifiedToughness, ModifiedPower, LayeredEffect]`; ability grants need `[AddAbility, LayeredEffect]` instead. The two builder methods should merge or a variant added.
- **`LayeredEffectCreated`** (transient, type 18) — fires in same GSM. Already implemented.
- **`LayeredEffectDestroyed`** (transient, type 19) — fires when effect ends. Builder exists but not wired.
- **`EffectTracker`** — currently tracks only P/T boost effects via `BoostEntry`. Ability grants require a parallel tracking path: `(affectorInstanceId, affectedInstanceId, abilityGrpId)` fingerprint with the same synthetic ID lifecycle.
- **`UniqueAbilityId` counter** — a per-match monotonically increasing counter separate from synthetic effect IDs. Not currently tracked.

### Wiring assessment

**Difficulty:** Hard

The current `EffectTracker` handles only Forge boost tables (P/T deltas). Ability grants come from a different Forge subsystem — static ability tables and triggered ability registrations. Wiring requires:

1. A new `AbilityGrantTracker` alongside `EffectTracker`, tracking active `(affectorId, affectedId, abilityGrpId)` grants with synthetic IDs.
2. A `UniqueAbilityId` counter on the tracker (monotonic per match, separate from `effect_id`).
3. Reading Forge's static ability / continuous effect layers to detect active grants — the mechanism for enumerating active keyword grants on permanents is not currently used.
4. Updating `AnnotationPipeline.effectAnnotations()` to emit `AddAbility+LayeredEffect` persistent annotations for ability-grant effects (in addition to or separately from the P/T buff path).
5. Updating `AnnotationBuilder.addAbility()` to: (a) set `affectorId`, (b) add `LayeredEffect` co-type, (c) support list-valued details for multi-ability grants, (d) accept optional `sourceAbilityGRPID`.

The self-grant case (land or permanent granting itself keywords) is a degenerate case of the same model — `affectorId == affectedIds[0]`.

### Open questions

- What Forge API exposes active static-ability keyword grants per permanent? (`Game.getStaticAbilities()`? `GameEntity.getKeywords()`?)
- Is `UniqueAbilityId` a match-global counter shared with any other annotation type, or AddAbility-private?
- When a creature gains the same ability from two different sources simultaneously (two auras both granting First Strike), are there two separate `AddAbility` annotations with different `effect_id` values, or one merged annotation? Not observed in current recordings.
- Does `originalAbilityObjectZcid` ever differ from `affectorId`? 0/37 cases observed — appears invariant but not proven.
