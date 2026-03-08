## CopiedObject — field note

**Status:** NOT IMPLEMENTED
**Instances:** 1 across 1 session
**Proto type:** `AnnotationType.CopiedObject` (enum 22)
**Field:** `persistentAnnotations` (dual-type with `LayeredEffect`)

### What it means in gameplay

A permanent has entered the battlefield as a copy of another card. The client uses this to render the permanent's visual identity (art, card text, P/T) as the copied card while knowing the actual printed card is different.

### Detail keys

| Key | Always/Sometimes | Values seen | Meaning |
|-----|-----------------|-------------|---------|
| `LayeredEffectType` | Always | `CopyObject` | String enum. Identifies this layered effect as a copy-object effect. |
| `copyFromGrpid` | Always | `97444` | grpId of the card being copied. Badgermole Cub in this instance. |
| `effect_id` | Always | `7022` | Synthetic layered effect ID (7000+ range, same system as `LayeredEffectCreated`). |

### Cards observed

| Card name | grpId | Scenario | Session |
|-----------|-------|----------|---------|
| Mockingbird (the copier) | 91597 | Enters as copy of Badgermole Cub (97444); gains Badger/Mole subtypes + Bird + flying | `2026-03-06_22-37-41` |
| Badgermole Cub (the source) | 97444 | Creature being copied; 2/2 Badger Mole with "earthbend 1" ETB trigger | `2026-03-06_22-37-41` |

### Scenario reconstruction

Mockingbird (grpId=91597) has a copy replacement effect (ability 173905): "You may have CARDNAME enter as a copy of any creature on the battlefield with mana value ≤ mana spent to cast it, except it's a Bird in addition to its other types and it has flying."

- **gsId=208:** Player casts Mockingbird, mana paid (2 generic + 1 green).
- **gsId=210:** `ResolutionStart` for Mockingbird (grpId=91597). Persistent `ReplacementEffect` (affectorId=9010, grpid=173905) fires — player chooses to copy Badgermole Cub.
- **gsId=211:** Mockingbird resolves and enters battlefield (ZoneTransfer Stack→BF, category=Resolve):
  - Transient `LayeredEffectCreated` → effect 7022
  - Persistent **`CopiedObject` + `LayeredEffect`** (dual-type, id=586) on iid=388: `copyFromGrpid=97444, LayeredEffectType=CopyObject, effect_id=7022`
  - Mockingbird's own ETB trigger (ability 192752: "When CARDNAME enters, earthbend 1") fires as `AbilityInstanceCreated` — it inherited this from Badgermole Cub's copy.
  - `PlayerSelectingTargets` for the earthbend ability.

Mockingbird (iid=388) appears in objects as: `grpId=91597, cardTypes=Creature, subtypes=[Badger, Mole, Bird], power=2, toughness=2` — the actual game object retains its original grpId but the `CopiedObject` annotation tells the client to render it as the copied card (Badgermole Cub).

### Proto structure

```
persistentAnnotation {
  id: 586
  types: [CopiedObject, LayeredEffect]   // dual-type
  affectorId: -3                          // system-generated (-3 = no specific source)
  affectedIds: [388]                      // Mockingbird's instanceId
  details:
    copyFromGrpid: 97444                  // grpId of the copied card
    LayeredEffectType: "CopyObject"       // string
    effect_id: 7022                       // synthetic layered effect ID
}
```

This is a **dual-type persistent annotation**: both `CopiedObject` (22) and `LayeredEffect` (8) types on the same proto message. The client dispatches to both parsers. The `LayeredEffect` component links this to the broader layered-effect system (same synthetic ID used in `LayeredEffectCreated`).

`affectorId=-3` is the system/engine identifier for effects without a specific game object source.

### Lifecycle

Persistent annotation. Created when the copying permanent enters the battlefield (gsId=211). Persists as long as the copy effect is active — for a permanent copy effect (Mockingbird chose at ETB), this should last until the permanent leaves the battlefield.

Session ends before Mockingbird leaves, so deletion is not directly observed. Expected to be cleaned up via `diffDeletedPersistentAnnotationIds` on a ZoneTransfer that removes Mockingbird.

The companion `LayeredEffectCreated` (transient, type 9) fires in the same GSM for the same effect_id.

### Related annotations

- `LayeredEffectCreated` (9) — transient, fires in same GSM for effect_id=7022
- `LayeredEffect` (8) — this annotation doubles as LayeredEffect (dual-type)
- `ReplacementEffect` — fires one GSM earlier when the copy choice is made
- `TriggeringObject` — fires in same GSM for Mockingbird's inherited ETB trigger

### Our code status

- Builder: missing — no `CopiedObject` method in `AnnotationBuilder.kt`
- GameEvent: missing — no copy/clone event in `GameEvent.kt`
- Emitted in pipeline: no
- `LayeredEffectCreated` companion: also not wired (see `LayeredEffectDestroyed.md` in `annotation-field-notes.md`)

### Wiring assessment

**Difficulty:** Hard

Copy effects require the full layered effect infrastructure (synthetic effect IDs) that is not yet built. The dual-type nature means this annotation also touches the `LayeredEffect` system.

Forge gap: `forge-core` has `GameEventCopied` or similar events for copy-on-enter effects, but the bridge doesn't collect them. Even if the event existed, we would need:
1. Synthetic effect ID allocation (7000+ counter, same as for Prowess/P/T buffs)
2. The `grpId` of the copied card (accessible via the Forge card state after the copy resolves)
3. The ability instanceId of the copy replacement effect
4. The dual-type annotation builder for `CopiedObject` + `LayeredEffect`

Low priority for mechanical correctness — Forge correctly resolves copy semantics (the card functions as the copy). Missing annotation only affects client visual rendering (Mockingbird would display as its own art instead of the copied card's art).

### Open questions

- Is `affectorId=-3` always used for copy effects, or only when there is no specific source object? Need more copy-effect recordings to confirm.
- For temporary copy effects (e.g., "until end of turn, target creature becomes a copy of..."), is `CopiedObject` deleted at turn end via `LayeredEffectDestroyed`? This session only shows a permanent copy.
- Does Forge expose the copied card's grpId at the right moment (before the first GSM after the copy resolves)?
