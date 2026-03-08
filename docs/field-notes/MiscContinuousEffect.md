## MiscContinuousEffect — field note

**Status:** NOT IMPLEMENTED
**Instances:** 4 across 2 sessions
**Proto type:** AnnotationType.MiscContinuousEffect = 52
**Field:** persistentAnnotations (both cases)

### What it means in gameplay

A catch-all persistent annotation for continuous effects that don't fit other specific annotation types. Observed uses: unlimited hand size (from Proft's Eidetic Memory) and pending extra combat phase (from Aurelia, the Warleader). The `affectedIds` is always the player seat that benefits.

### Detail keys

| Key | Always/Sometimes | Values seen | Meaning |
|-----|-----------------|-------------|---------|
| MaxHandSize | Sometimes (50%) | 2147483647 | INT32_MAX = "no maximum hand size" |
| effect_id | Sometimes (50%) | 7002 | Synthetic layered effect ID (same namespace as LayeredEffect annotations) |
| extra_phases | Sometimes (50%) | 3 | Phase enum value: 3 = Combat — an additional combat phase is pending |
| grpid | Sometimes (50%) | 100287 | The ability grpId that created the effect (Aurelia's trigger) |

The two observed variants are **mutually exclusive** — no instance has both `MaxHandSize` and `extra_phases`.

**Note:** The variant report shows no "always" present keys, and 4 instances across 2 sessions. The old field notes (from `annotation-field-notes.md`) accurately described the 2-variant pattern.

### Cards observed

| Card name | grpId | Scenario | Session |
|-----------|-------|----------|---------|
| Proft's Eidetic Memory | 88986 | Enters battlefield; "You have no maximum hand size" | 14-15-29 gsId=46 T3 Main1 |
| Aurelia, the Warleader | 94079 | Triggered ability resolves; "additional combat phase" | 2026-03-01_00-18-46 gsId=320 T13 Combat |

### Lifecycle

**MaxHandSize variant:**
- Created when Proft's Eidetic Memory enters the battlefield (T3 Main1, gsId=46).
- `affectedIds=[1]` (player seat 1 — the enchantment's controller).
- Dual-type annotation: the same proto message has **both** `MiscContinuousEffect` and `LayeredEffect` as its `types`. Shares the LayeredEffect synthetic ID namespace (`effect_id=7002`).
- Expected to be deleted if the enchantment leaves the battlefield (not observed in this session).

**Extra phases variant:**
- Created when Aurelia's triggered ability resolves (T13 Combat, gsId=320).
- `affectedIds=[1]`, no `effect_id` (not tied to the LayeredEffect system).
- Session ends during the extra combat (opponent conceded), so deletion not observed.
- Expected to be deleted at the end of the extra phase or at turn boundary.

### Related annotations

- `LayeredEffect` (type 18) — the MaxHandSize variant IS also a LayeredEffect (dual-type). The `effect_id` on MiscContinuousEffect refers to the same synthetic layered effect ID.
- `LayeredEffectCreated` — fires in the same GSM as the MaxHandSize variant (gsId=46 creates effect 7002).
- `AbilityExhausted` — for Aurelia specifically, the extra-phases MiscContinuousEffect appears after the `AbilityExhausted` annotation for abilityGrpId=100287.

### Our code status

- Builder: missing — no `miscContinuousEffect` method in AnnotationBuilder.kt
- GameEvent: missing for both variants
- Emitted in pipeline: no

### Wiring assessment

**Extra phases (Aurelia) — Difficulty: Medium**
Forge fires a phase-manipulation event when an extra combat phase is added. If that event is exposed (e.g. via `GameEventAddPhase` or similar), wire it to emit a MiscContinuousEffect with `extra_phases=3` and `grpid` of the triggering ability. Main gap: identifying the trigger source (`grpid`) at the event site.

**MaxHandSize — Difficulty: Hard**
This is a dual-type annotation coupling `MiscContinuousEffect` + `LayeredEffect`. Requires the same synthetic effect ID infrastructure as LayeredEffect wiring. Scan permanents for "no maximum hand size" static abilities each GSM and emit when the annotation isn't already present. The `effect_id` must be allocated from the same 7000+ synthetic counter.

### Open questions

- Are there other MiscContinuousEffect subtypes beyond MaxHandSize and extra_phases? (e.g. extra main phases, extra draw steps)
- For extra phases: is the annotation deleted in the same GSM where the extra phase ends, or in the cleanup step? No recording covers this.
