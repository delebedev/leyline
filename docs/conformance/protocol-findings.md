# Protocol Findings

Durable structural discoveries from annotation analysis across 59+ proxy recording sessions. These are protocol-level facts about how the real Arena server constructs annotations — not implementation status (use `just proto-annotation-variance` for current status).

## Multi-type annotations

Many annotations carry multiple types in a single proto message. The client dispatches to ALL parsers in the type list — these are not independent types.

| Pattern | Types in single annotation |
|---|---|
| P/T buff from ability | `[ModifiedType, LayeredEffect]` |
| Ability + type change | `[RemoveAbility, ModifiedType, LayeredEffect]` |
| Room door-unlock effect | `[ModifiedCost, TextChange, ModifiedName, RemoveAbility, LayeredEffect]` |
| Clone/copy | `[CopiedObject, LayeredEffect]` |
| Max hand size | `[MiscContinuousEffect, LayeredEffect]` |

ModifiedCost, TextChange, and ModifiedName are NOT three separate annotation types — they're one annotation with 5 co-types.

## Synthetic ID schemes

### 7000+ range — layered effect IDs

Allocated per-game for buff/debuff/ability-gain effects. The client tracks effects by these IDs and expects a `Created → Persistent → Destroyed` lifecycle.

Types gated on this infrastructure:
- LayeredEffectCreated — buff/debuff/ability-gain animations
- LayeredEffectDestroyed — VFX teardown
- ModifiedType — card type change rendering
- CopiedObject — clone identity
- AddAbility — ability-gain rendering
- ModifiedCost / TextChange / ModifiedName — Room door-unlock bundle

### 9000+ range — replacement effect IDs

ReplacementEffect uses `affectorId` values in 9000+ range. Appear to be zone-change IDs, not effect IDs. Unrelated to the 7000+ scheme. Only 4 instances observed — needs more recordings.

## Triplet pattern (temporary exile-and-return)

TemporaryPermanent + DelayedTriggerAffectees + DisplayCardUnderCard always appear together on the same card in the same GSM. Represent temporary exile-and-return effects (Warp, Getaway Glamer):

- **TemporaryPermanent** — marks card as temporary (IsTemporary flag)
- **DelayedTriggerAffectees** — tracks pending return trigger
- **DisplayCardUnderCard** — visual: show exiled card under the source

All three share a TriggerHolder object (grp:5) as affectorId.

## Client parser architecture

From decompiled client code:

**State parsers** mutate `MtgCardInstance` / `MtgEntity` / `MtgGameState` fields.
**Event parsers** produce `GameRulesEvent` for animation/visual layer.
Some types have BOTH (e.g. ResolutionStart, Shuffle, LinkInfo).

### LayeredEffectCreated sub-handlers

| LayeredEffectType detail | Sub-Generator | Visual effect |
|---|---|---|
| `Effect_ModifiedPower` | GenerateModifiedPowerEvents | Power buff/debuff glow |
| `Effect_ModifiedToughness` | GenerateModifiedToughnessEvents | Toughness buff/debuff glow |
| `Effect_ModifiedPowerAndToughness` | GeneratePowerToughnessModifiedEvents | P/T combined buff animation |
| `Effect_AddedAbility` | GenerateAddedAbilityEvents | Ability gain animation |
| `Effect_ModifiedType` | GenerateTypeModificationEvents | Card type change animation |
| `Effect_ModifiedColor` | GenerateColorModifiedEvents | Color change animation |
| `Effect_ControllerChanged` | GenerateControllerChangedEvents | Control swap animation |

### Counter system — three-parser pattern

Server must send all three for correct display:
1. **Counter** (type 14, state) — sets final counter count in `Counters` dict
2. **CounterAdded** (type 16, event) — yields add animation
3. **CounterRemoved** (type 17, event) — yields remove animation

### Dual-interface types (both state + event parsers)

| Type | State effect | Event effect |
|---|---|---|
| ResolutionStart/Complete | Resolution tracking | Resolution animation |
| Shuffle | ID remapping | Shuffle animation |
| LinkInfo | LinkedInfoText display | CardNamedEvent |
| DieRoll | DieRollResults | DieRollEvent |
| ReplacementEffectApplied | Applied tracking | ReplacementEffectAppliedEvent |

## Adventure mechanic — protocol shape (inferred, unconfirmed)

From rosetta + Forge source analysis (2026-03-21). No adventure cast observed in proxy recordings yet.

**Object type for adventure cards:**
- On stack (adventure half): `type = Adventure (10)`, grpId = adventure grpId (e.g. 86969)
- In exile after resolution: `type = Adventure (10)`, grpId = adventure grpId (still secondary face)
- On stack (creature cast from exile): `type = Card (0)`, grpId = creature grpId (e.g. 86968)

**Action types:**
- Hand → cast adventure half: `CastAdventure (16)`, grpId = adventure grpId
- Exile → cast creature half: `Cast (11)` (standard), grpId = creature grpId

**Zone transfer categories — open questions:**
- Hand → stack (adventure cast): category label unknown — likely `"CastAdventure"` but may reuse `"CastSpell"`
- Stack → exile (adventure resolves): `"Resolve"` (standard resolution path)
- Exile → stack (creature cast): currently falls to `"ZoneTransfer"` — should be `"CastSpell"`

**Annotations specific to adventure:**
- `DisplayCardUnderCard (38)` fires when card exiles as adventure — tells client to render card thumbnail under exile pile. Details: `Disable: 0`, `TemporaryZoneTransfer: 1`. Not yet wired.

**Needs live recording to confirm:** category label strings, exact ObjectType values, whether `DisplayCardUnderCard` fires on adventure exile vs other exiles.

## Source

Distilled from `docs/archive/2026-03-08-annotation-variance-report.md` (59 sessions, 67 types, 12420 annotation instances). Run `just proto-annotation-variance` for current per-type status.
