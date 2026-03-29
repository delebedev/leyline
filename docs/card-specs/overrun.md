# Overrun — Card Spec

## Identity

- **Name:** Overrun
- **grpId:** 93943
- **Set:** FDN
- **Type:** Sorcery
- **Cost:** {2}{G}{G}{G}
- **Forge script:** `forge/forge-gui/res/cardsfolder/o/overrun.txt`

## Mechanics

| Mechanic | Forge DSL | Catalog status |
|----------|-----------|----------------|
| Cast sorcery | `A:SP$ PumpAll` | wired |
| Mass P/T pump (+3/+3) | `NumAtt$ +3 \| NumDef$ +3` | wired (`layered-effect`) |
| Team keyword grant (trample) | `KW$ Trample` | partial (trample exists; keyword-grant wire shape now confirmed here) |
| EOT cleanup | engine LayeredEffect expiry | wired |

Relevant issue: **#31** (keyword-granting spells). This is the first traced team-wide keyword grant via `PumpAll`. The wire shape is fully documented here.

## What it does

Cast for {2}{G}{G}{G}: every creature you control gets +3/+3 and gains trample until end of turn. No targeting, no choices. Resolves immediately once priority is passed.

## Trace

### Session `2026-03-29_16-55-19`, seat 1, turn 18

Overrun cast from hand, 3 creatures controlled (iids 389, 425, 432).

| gsId | Event | Detail |
|------|-------|--------|
| 437 | Cast | ObjectIdChanged (180→435) + ZoneTransfer hand→stack (31→27), category=`CastSpell`. PayCostsReq follows in same bin (promptId=11, cost o2oGoGoG). |
| 438–439 | Mana payment | Standard PayCostsReq bracket (5 land taps). |
| 440 | Resolution | ResolutionStart → LayeredEffectCreated ×2 → PowerToughnessModCreated ×3 → ResolutionComplete → ObjectIdChanged (435→442) → ZoneTransfer stack→GY (27→33), category=`Resolve`. |
| 465 | EOT cleanup | LayeredEffectDestroyed ×2 (effect_ids 7009 and 7010). Fires in the same diff as `PhaseOrStepModified` step=10 (end step) and `NewTurnStarted`. |

### Session `2026-03-29_17-04-26`, seat 1, turn 16

Overrun cast from hand, 4 creatures controlled (iids 335, 346, 416, 422). Game ended during combat in the same turn (no EOT observed — opponent died). Resolution wire is identical.

## Annotations

### Cast frame (gsId 437, session 16-55-19)

Overrun on stack object has no special annotation beyond `EnteredZoneThisTurn` (pAnn id=4,
affectorId=27 = Stack zone, affectedIds=[435]). No `TargetSpec` — Overrun is untargeted.

```
uniqueAbilities { id: 328; grpId: 1033 }   # spell ability, present on stack object
```

### Resolution diff (gsId 440, session 16-55-19 — 3 creatures)

Sequence within the single diff:

```
ResolutionStart            affectorId=435  affectedIds=[435]  details.grpid=93943

LayeredEffectCreated       affectorId=435  affectedIds=[7009]   # P/T effect birth
  (transient, no details)

PowerToughnessModCreated   affectorId=435  affectedIds=[389]   details.power=3  details.toughness=3
PowerToughnessModCreated   affectorId=435  affectedIds=[425]   details.power=3  details.toughness=3
PowerToughnessModCreated   affectorId=435  affectedIds=[432]   details.power=3  details.toughness=3

LayeredEffectCreated       affectorId=435  affectedIds=[7010]   # keyword effect birth
  (transient, no details)

ResolutionComplete         affectorId=435  affectedIds=[435]  details.grpid=93943
ObjectIdChanged            affectedIds=[435]  orig_id=435  new_id=442
ZoneTransfer               affectedIds=[442]  zone_src=27  zone_dest=33  category="Resolve"
```

**Persistent annotations added in same diff:**

P/T effect:
```
id=1020  affectorId=435  affectedIds=[389, 425, 432]
  types: [ModifiedToughness, ModifiedPower, LayeredEffect]
  details.effect_id=7009
```

Keyword grant:
```
id=1025  affectorId=435  affectedIds=[432, 425, 389]
  types: [AddAbility, LayeredEffect]
  details.originalAbilityObjectZcid=435
  details.UniqueAbilityId=330, 331, 332       # one per affected creature
  details.grpid=14                             # trample keyword
  details.effect_id=7010
```

### Resolution diff (gsId 399, session 17-04-26 — 4 creatures)

Shape is identical with 4 targets. Confirms the pattern scales linearly:

- `LayeredEffectCreated` count: always **2** (one for P/T layer, one for keyword layer), regardless of creature count.
- `PowerToughnessModCreated` count: one per affected creature (3 in s1, 4 in s2).
- `AddAbility+LayeredEffect` pAnn: single pAnn listing **all** affected creature iids in `affectedIds`; one `UniqueAbilityId` value per creature.

**Creature gameObjects in the resolution diff** — each pumped creature receives a new `uniqueAbilities` entry:

```
# Example from session 17-04-26 (instanceId 335, grpId 93965, Artifact Creature Wall)
uniqueAbilities { id: 247; grpId: 2 }       # pre-existing (Defender)
uniqueAbilities { id: 248; grpId: 116851 }   # pre-existing
uniqueAbilities { id: 336; grpId: 14 }       # ADDED by Overrun — trample
```

The newly added `uniqueAbilities.grpId=14` (trample) matches the `details.grpid=14` in the `AddAbility` pAnn. The `uniqueAbilities.id` matches one of the values in `details.UniqueAbilityId`. This is the client's signal that the creature now displays the trample badge.

**grpId 14 = Trample keyword.** Confirmed by cross-referencing with Mossborn Hydra (which has native trample via `K:Trample` in Forge DSL) and the `AddAbility` pAnn detail key `grpid=14` alongside `KW$ Trample` in Overrun's DSL.

### EOT cleanup (gsId 465, session 16-55-19)

Both effects are destroyed simultaneously in one diff, coinciding with the end step:

```
PhaseOrStepModified        affectedIds=[1]  phase=5  step=10   # end step
LayeredEffectDestroyed     affectorId=435   affectedIds=[7009]  # P/T effect
LayeredEffectDestroyed     affectorId=435   affectedIds=[7010]  # keyword effect
PhaseOrStepModified        affectedIds=[0]  phase=1  step=0    # next turn
NewTurnStarted             affectedIds=[2]
```

After cleanup: the `uniqueAbilities { grpId: 14 }` entries disappear from creature gameObjects (via diff — the server sends updated creature objects with the entries removed). The `ModifiedPower/ModifiedToughness/LayeredEffect` and `AddAbility/LayeredEffect` pAnns are deleted.

## Key findings

### Two LayeredEffects for one spell

Overrun creates exactly **two** `LayeredEffectCreated` transients per cast — one for the P/T modification (layer 7b) and one for the keyword grant (layer 6). Both share `affectorId = spell instanceId`. The persistent pAnns use `effect_id` to bind them:

| effect_id | Layer | pAnn types | Tracks |
|-----------|-------|-----------|--------|
| 7009 (s1) / 7004 (s2) | P/T mod | ModifiedToughness + ModifiedPower + LayeredEffect | +3/+3 on all creatures |
| 7010 (s1) / 7005 (s2) | Keyword | AddAbility + LayeredEffect | Trample grant on all creatures |

Effect IDs are globally monotonic (not per-card), so exact values differ between sessions.

### Keyword grant wire shape (issue #31)

The keyword grant is **not** a separate annotation per creature. It is one `AddAbility+LayeredEffect` pAnn with:
- All affected creature iids in `affectedIds` (flat list)
- One `UniqueAbilityId` value per creature (multi-value int field)
- A single `grpid` value for the keyword granted (14 = Trample)
- `originalAbilityObjectZcid` = the spell instanceId

This is the same `AddAbility+LayeredEffect` shape observed for single-target keyword grants (e.g., Haste from Claim the Firstborn in `controllerchanged-wire.md`) but with multiple `affectedIds` and `UniqueAbilityId` entries.

### Creature gameObject update

Each affected creature's gameObject in the resolution diff shows the added keyword as a new `uniqueAbilities { id: N; grpId: 14 }` entry appended to the existing list. The `id` matches one of the `UniqueAbilityId` values in the pAnn. This is the client-facing keyword badge signal. At EOT, the server sends updated gameObjects with those entries removed.

### No SelectTargetsReq / SelectNReq

Overrun is fully automatic — no targeting UI. The diff has no `ActionsAvailableReq` between cast and resolution (the spell needs no modes or targets). This matches the Forge DSL: `PumpAll | ValidCards$ Creature.YouCtrl` (server-side filter, no player input).

### Resolve to GY

Overrun resolves to GY (zone 33), category=`Resolve` — standard sorcery path, not exile. Consistent with: not flashback, not an adventure.

## Gaps for leyline

1. **Issue #31 — Keyword-granting spells.** The `AddAbility+LayeredEffect` pAnn multi-creature shape is now fully documented. leyline must emit one pAnn (not one-per-creature) with:
   - All pumped creature iids in `affectedIds`
   - One `UniqueAbilityId` per creature in a repeated int field
   - `grpid` = keyword grpId (14 for Trample)
   - `effect_id` linking to the second `LayeredEffectCreated` transient
   - `originalAbilityObjectZcid` = spell instanceId

2. **Keyword grpId table incomplete.** `KeywordQualifications.kt` has `"Trample"` commented out with `grpId = ?`. Now confirmed: **trample grpId = 14**. Needs registration.

3. **Creature gameObject uniqueAbilities update.** When a keyword is added via layered effect, the creature's `gameObject` in the next diff must include the new `uniqueAbilities { id: N; grpId: keyword_grpId }` entry. Removed at EOT when the layered effect is destroyed.

4. **EOT cleanup ordering.** Both `LayeredEffectDestroyed` annotations (P/T and keyword) fire in the same diff, coincident with the end step `PhaseOrStepModified`. The creature gameObjects in that same diff show the removed entries. Both effects have `affectorId = spell instanceId` (not a system affectorId).

## Supporting evidence

- Session `2026-03-29_16-55-19`: Overrun gsId 437 (cast), 440 (resolve), 465 (EOT cleanup). 3 creatures. Full EOT observed.
- Session `2026-03-29_17-04-26`: Overrun gsId 399 (resolve). 4 creatures. Confirms scaling. Game ends in combat; no EOT (opponent died).
- Cross-ref: `docs/card-specs/controllerchanged-wire.md` — `AddAbility+LayeredEffect` single-target shape (Haste, grpId=9).
- Cross-ref: `docs/card-specs/mossborn-hydra.md` — trample in combat, AssignDamageReq with player slot = seatId.

## Agent Feedback

**Tooling that worked well:**
- `just tape proto show -s <session> <gsId>` is indispensable. The JSONL `md-frames.jsonl` only gave truncated annotation strings; the actual `UniqueAbilityId` values, the `originalAbilityObjectZcid` detail key, and the creature `uniqueAbilities` entries in gameObjects were all invisible until `tape proto show` was used. JSONL is genuinely lossy for anything involving repeated fields or multi-value details.
- The two-step workflow (JSONL scan for interesting gsIds, then proto show for ground truth) worked cleanly. Finding both sessions' Overrun gsIds took one grep each.

**Pain points:**
- Output files from `tape proto show` are large (~40KB per bin file which can span 8+ GSMs). `Read` with `limit/offset` was needed to navigate. A `tape proto show --gsId <n>` flag that emitted only the matching GSM would reduce friction significantly.
- `grep` on the saved tool-result files requires workarounds (no `file_path` param on Grep tool — must use `path`). Minor but worth noting.
- The EOT cleanup for session 17-04-26 was absent (game ended in combat). Without session 16-55-19 covering EOT, that shape would have been unconfirmed. Sessions that end in combat are only useful for resolution traces, not for cleanup timing.
- `grpId 14 = Trample` was not documented anywhere in the codebase (commented out in `KeywordQualifications.kt` with `grpId = ?`). It took cross-referencing the Forge DSL (`KW$ Trample` in overrun.txt), the wire (`details.grpid=14` in `AddAbility` pAnn), and the creature gameObject (appended `uniqueAbilities { grpId: 14 }`) triangulated across two sessions to be certain. A keyword grpId reference table would prevent this re-discovery in every new spec.
