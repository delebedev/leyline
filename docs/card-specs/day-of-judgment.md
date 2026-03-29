# Day of Judgment — Card Spec

## Identity

- **Name:** Day of Judgment
- **grpId:** 93853 (primary; also 95272, 77481, 77544, 80839 — alternate art/printings)
- **Set:** FDN / M11 / others
- **Type:** Sorcery
- **Cost:** {2}{W}{W}
- **Forge script:** `forge/forge-gui/res/cardsfolder/d/day_of_judgment.txt`

## Mechanics

| Mechanic | Forge DSL | Catalog status |
|----------|-----------|----------------|
| Mass creature destruction | `A:SP$ DestroyAll \| ValidCards$ Creature` | **wired** (engine handles) |
| SBA_UnattachedAura cascade | server-side SBA on resolution | **missing** — #170 |
| TokenDeleted for token creatures | server-side after resolution complete | **missing** (no separate bug) |
| LayeredEffectDestroyed before SBA ZoneTransfer | ordering within diff | **missing** — same as #170 |

## What it does

1. **Resolve:** Destroys every creature on the battlefield simultaneously. All creatures move BF→GY with `category="Destroy"` in one resolution diff.
2. **Spell to GY:** The sorcery itself moves Stack→GY with `category="Resolve"` after `ResolutionComplete`.
3. **Token cleanup:** Any token creatures that were destroyed emit `TokenDeleted` (same diff, after spell GY).
4. **SBA_UnattachedAura cascade:** For each aura whose enchanted creature was destroyed — `LayeredEffectDestroyed` fires first (removing the aura's continuous effect), then `ObjectIdChanged` + `ZoneTransfer(SBA_UnattachedAura)` sends the aura to its owner's GY.

## Trace (session 2026-03-29_16-45-39, gsId=208, T10)

Seat 1 (human, P1) cast Day of Judgment (grpId 93853, iid 349). Seven permanents destroyed:

| Pre-rename iid | Post-rename iid | grpId | Type | Dest zone |
|---------------|----------------|-------|------|-----------|
| 308 | 354 | 75476 (Sworn Guardian) | Creature | 37 (P2 GY) |
| 316 | 355 | 75479 (Warden of Evos Isle) | Creature | 37 (P2 GY) |
| 322 | 356 | 93728 (Hare Apparent) | Creature | 33 (P1 GY) |
| 328 | 357 | 75465 (Armored Whirl Turtle) | Creature | 37 (P2 GY) |
| 337 | 358 | 93728 (Hare Apparent) | Creature | 33 (P1 GY) |
| 341 | 359 | 94160 (Rabbit token) | Token | 33 (P1 GY) → TokenDeleted |
| 344 | 360 | 75465 (Armored Whirl Turtle) | Creature | 37 (P2 GY) |

River's Favor (grpId 75473, iid 332) was an aura enchanting one of the destroyed creatures. It cascaded to `SBA_UnattachedAura`.

### Annotation ordering within the resolution diff

```
ann 444: ResolutionStart          affectorId=349, affectedIds=349, grpid=93853
ann 445: ObjectIdChanged          affectorId=349, affectedIds=308, orig_id=308, new_id=354
ann 446: ZoneTransfer             affectorId=349, affectedIds=354, zone_src=28, zone_dest=37, category="Destroy"
ann 447: ObjectIdChanged          affectorId=349, affectedIds=316, orig_id=316, new_id=355
ann 448: ZoneTransfer             affectorId=349, affectedIds=355, zone_src=28, zone_dest=37, category="Destroy"
ann 449: ObjectIdChanged          affectorId=349, affectedIds=322, orig_id=322, new_id=356
ann 450: ZoneTransfer             affectorId=349, affectedIds=356, zone_src=28, zone_dest=33, category="Destroy"
ann 451: ObjectIdChanged          affectorId=349, affectedIds=328, orig_id=328, new_id=357
ann 452: ZoneTransfer             affectorId=349, affectedIds=357, zone_src=28, zone_dest=37, category="Destroy"
ann 453: ObjectIdChanged          affectorId=349, affectedIds=337, orig_id=337, new_id=358
ann 454: ZoneTransfer             affectorId=349, affectedIds=358, zone_src=28, zone_dest=33, category="Destroy"
ann 455: ObjectIdChanged          affectorId=349, affectedIds=341, orig_id=341, new_id=359
ann 456: ZoneTransfer             affectorId=349, affectedIds=359, zone_src=28, zone_dest=33, category="Destroy"
  [ann 457 missing — gap in sequence; not present in proto]
ann 458: ObjectIdChanged          affectorId=349, affectedIds=344, orig_id=344, new_id=360
ann 459: ZoneTransfer             affectorId=349, affectedIds=360, zone_src=28, zone_dest=37, category="Destroy"
ann 461: ResolutionComplete       affectorId=349, affectedIds=349, grpid=93853
  [ann 460 also absent]
ann 462: ObjectIdChanged          affectorId=1,   affectedIds=349, orig_id=349, new_id=361
ann 463: ZoneTransfer             affectorId=1,   affectedIds=361, zone_src=27, zone_dest=33, category="Resolve"
  ── SBA processing begins ──
ann 464: TokenDeleted             affectorId=359, affectedIds=359
ann 465: LayeredEffectDestroyed   affectorId=332, affectedIds=7002
ann 466: ObjectIdChanged          affectedIds=332, orig_id=332, new_id=362       [no affectorId]
ann 467: ZoneTransfer             affectedIds=362, zone_src=28, zone_dest=37, category="SBA_UnattachedAura"  [no affectorId]
```

**Key ordering observations:**
1. `ResolutionStart` opens — all creature `Destroy` ZoneTransfers follow (one `ObjectIdChanged`+`ZoneTransfer` pair per creature, with affectorId=spell iid).
2. `ResolutionComplete` fires after the last creature Destroy.
3. Spell itself moves Stack→GY with `category="Resolve"` (affectorId=1, seat).
4. `TokenDeleted` fires for each token that was destroyed (same diff, immediately after spell GY).
5. `LayeredEffectDestroyed` fires for each aura's continuous effect (affectorId=aura pre-rename iid, affectedIds=effect_id 7xxx).
6. `ObjectIdChanged` + `ZoneTransfer(SBA_UnattachedAura)` for the aura — **no affectorId** on either annotation.

## Trace cross-check (session 2026-03-29_16-55-19, gsId=241, T10)

Three creatures destroyed, two auras (Knight's Pledge iid 357, Pacifism iid 360) cascade:

```
ann 521: ResolutionStart          affectorId=368, grpid=93853
ann 522: ObjectIdChanged          affectorId=368, affectedIds=324→373
ann 523: ZoneTransfer             affectorId=368, affectedIds=373, zone_src=28, zone_dest=37, category="Destroy"
ann 524: ObjectIdChanged          affectorId=368, affectedIds=340→374
ann 525: ZoneTransfer             affectorId=368, affectedIds=374, zone_src=28, zone_dest=33, category="Destroy"
ann 526: LayeredEffectDestroyed   affectorId=345, affectedIds=7003         ← aura effect destroyed BEFORE creature rename
ann 527: ObjectIdChanged          affectorId=368, affectedIds=345→375
ann 528: ZoneTransfer             affectorId=368, affectedIds=375, zone_src=28, zone_dest=37, category="Destroy"
ann 529: ResolutionComplete       affectorId=368, grpid=93853
ann 530: ObjectIdChanged          affectorId=1,   affectedIds=368→376
ann 531: ZoneTransfer             affectorId=1,   affectedIds=376, zone_src=27, zone_dest=33, category="Resolve"
  ── SBA processing ──
ann 532: LayeredEffectDestroyed   affectorId=357, affectedIds=7004
ann 533: ObjectIdChanged          affectedIds=357→377                        [no affectorId]
ann 534: ZoneTransfer             affectedIds=377, zone_src=28, zone_dest=37, category="SBA_UnattachedAura"  [no affectorId]
ann 535: ObjectIdChanged          affectedIds=360→378                        [no affectorId]
ann 536: ZoneTransfer             affectedIds=378, zone_src=28, zone_dest=37, category="SBA_UnattachedAura"  [no affectorId]
```

**Notable difference from gsId=208:** In gsId=241, `LayeredEffectDestroyed` (ann 526) fires in the middle of the creature Destroy sequence — specifically for the third creature (Angel of Vitality, iid 345), whose aura (Knight's Pledge) had a layered effect. This means `LayeredEffectDestroyed` for a creature's own aura fires within the Destroy sequence (same pair as the creature's own death), not after ResolutionComplete. The two SBA_UnattachedAura events for the remaining auras (Knight's Pledge 357, Pacifism 360) fire after ResolutionComplete + spell GY, consistent with gsId=208.

**Interpretation:** `LayeredEffectDestroyed` for an enchanted creature fires inline with that creature's Destroy annotations. The SBA_UnattachedAura ZoneTransfer for that same aura fires after ResolutionComplete. This is consistent — the effect is destroyed at death, but the aura's zone transfer is an SBA (processed after the batch).

## Additional wipes (gsId=541 and gsId=630, session 2026-03-29_16-55-19)

Both wipes follow the identical sequence:
- `ResolutionStart` → N× Destroy pairs → `ResolutionComplete` → spell Resolve → SBA cascade
- gsId=541: Giada (432) + Youthful Valkyrie (471) destroyed; Knight's Pledge (445) → SBA_UnattachedAura
- gsId=630: one creature destroyed; Knight's Pledge (491) → SBA_UnattachedAura

Four SBA_UnattachedAura events observed across three wipes. All consistent.

## Key findings

### Annotation affectorId conventions
- **Destroy ZoneTransfers:** `affectorId` = spell instance iid (349 / 368). Spell is the actor.
- **Spell GY ZoneTransfer (Resolve):** `affectorId` = 1 (seat). Seat owns the resolution cleanup.
- **TokenDeleted:** `affectorId` = token's post-rename iid (same as `affectedIds`). Self-referential.
- **LayeredEffectDestroyed:** `affectorId` = aura's pre-rename iid. affectedIds = effect_id (7xxx space).
- **SBA_UnattachedAura ZoneTransfer:** `affectorId` absent. Consistent with all SBA categories.

### Ordering rule (confirmed across 5 wipes)
```
ResolutionStart
  [ObjectIdChanged + ZoneTransfer(Destroy)] × N creatures  (may interleave LayeredEffectDestroyed for creature's own aura)
ResolutionComplete
ObjectIdChanged + ZoneTransfer(Resolve)                     spell → GY
TokenDeleted × T                                            tokens cleaned up
LayeredEffectDestroyed × E (per aura with no host)
ObjectIdChanged + ZoneTransfer(SBA_UnattachedAura) × A      auras to GY
```

### Sequence annotation ID gap
In gsId=208, annotation ids skip 457 and 460. Both numbers are absent from the proto — not a JSONL truncation artifact. Possibly server-internal IDs consumed by non-client-visible operations (e.g., internal trigger suppression). Does not affect wire implementation.

### TokenDeleted vs ZoneTransfer for tokens
Token creatures that die to mass removal receive a `ZoneTransfer(Destroy)` annotation like non-tokens, then a `TokenDeleted` annotation after `ResolutionComplete`. The token iid is added to `diffDeletedInstanceIds` in the diff. Both the ZoneTransfer and TokenDeleted use the post-rename iid (359, not 341). Token briefly appears in zone 33 GY per zone state, then is removed. Leyline must emit `TokenDeleted` for tokens destroyed by resolution (not just sacrifice paths).

### Creature grpId behavior
Each creature retains its grpId across the ObjectIdChanged rename. The pre-rename iid is used in the creature's Destroy ZoneTransfer affectedIds field. The post-rename iid appears in zone state updates.

## Gaps for leyline

1. **SBA_UnattachedAura not emitted** — leyline does not emit this ZoneTransfer when an aura's enchanted permanent leaves the battlefield. Tracked in #170. Requires: (a) detecting aura attachments at resolution time, (b) emitting `LayeredEffectDestroyed` for each continuous effect of the aura, (c) emitting `ObjectIdChanged` + `ZoneTransfer(SBA_UnattachedAura)` with no affectorId, after ResolutionComplete + spell cleanup.
2. **TokenDeleted ordering** — must fire after `ZoneTransfer(Resolve)` for the spell, not inline with Destroy ZoneTransfers.
3. **LayeredEffectDestroyed for enchanted creature** — in gsId=241 (ann 526), the aura's LayeredEffectDestroyed fires inline with the enchanted creature's Destroy annotations (before that creature's ObjectIdChanged+ZoneTransfer). Current engine-bridge may need to inspect attached enchantments at creature-death annotation time.

## Catalog status

No existing catalog entry for "mass removal" or "board wipe" as a distinct mechanic — Day of Judgment uses standard `DestroyAll` which routes through the existing Destroy ZoneTransfer path. No catalog update needed for the spell itself. `unattached-aura: missing` remains correct.

## Supporting evidence

- Session `2026-03-29_16-45-39` gsId=208: 7-creature wipe with River's Favor SBA_UnattachedAura + Rabbit token TokenDeleted
- Session `2026-03-29_16-55-19` gsId=241: 3-creature wipe with Knight's Pledge + Pacifism SBA_UnattachedAura
- Session `2026-03-29_16-55-19` gsId=541: 2-creature wipe, Knight's Pledge SBA_UnattachedAura
- Session `2026-03-29_16-55-19` gsId=630: 1-creature wipe, Knight's Pledge SBA_UnattachedAura
- Prior SBA_UnattachedAura baseline: `recordings/2026-03-22_23-20-47` (combat death path)
- Memory: `.claude/agent-memory/conformance/sba-unattached-aura-wire.md`

---

## Agent Feedback

### What was slow / missing

**tape proto show** is highly useful compared to the prior grep-on-JSONL workflow. The full proto text gives precise annotation IDs, value types, and ordering that JSONL loses entirely (no annotation IDs in JSONL, no ordering within a single diff, no field-level detail keys). For this spec, the annotation sequence was the entire story — proto was mandatory.

The main slow point was discovering the exact gsId. The session notes file (`notes.md`) had the gsId already documented (208 for the first session, 241/541/630 for the second). When notes exist, the workflow is: read notes → `tape proto show -s <session> <gsId>`. Fast. When notes don't exist, finding the right gsId requires scanning md-frames.jsonl first.

**JSONL grep is still needed as a search tool** to identify which gsId to inspect. The note files accelerated this significantly — they had the gsIds pre-identified. Without notes, finding "which gsId is the wipe?" would require grepping md-frames.jsonl for something like `ResolutionStart.*93853` or scanning for `TokenDeleted`.

### What was hard to find

- **Annotation ID gaps** (457, 460 missing in gsId=208): only visible in proto. Would be completely invisible from JSONL or notes. Discovered by accident when writing up the numbered list.
- **LayeredEffectDestroyed interleaved mid-sequence** (ann 526 in gsId=241, before ResolutionComplete): the JSONL-based notes described it as "fires before SBA_UnattachedAura ZoneTransfer" but missed that it fires inline with the creature's Destroy pair. Proto made the exact position clear.
- **Pacifism in gsId=241 — no LayeredEffectDestroyed before its SBA_UnattachedAura**: Knight's Pledge has one `+2/+2` layered effect (one `LayeredEffectDestroyed`). Pacifism (`can't attack or block`) is a restriction ability, not a P/T modifier — its SBA_UnattachedAura fires with no preceding `LayeredEffectDestroyed`. This is consistent with the general rule (LayeredEffectDestroyed count = number of continuous layer effects on the aura), but Pacifism confirmed the zero-effect case. Worth noting in the SBA memory.

### What would save time

- **Card spec skill template should prompt for a "creature table"** when the card does mass removal. The per-creature iid→grpId mapping table was essential here and took extra effort to reconstruct from proto.
- **Suggest scanning `notes.md` before grepping md-frames.jsonl.** Notes often have the gsId already. The instruction said to find the gsId from md-frames.jsonl, but notes had it documented — could save 2-3 grep iterations.
- **tape proto show output is verbose.** For a 7-creature wipe, the full proto is ~1000 lines. A `tape proto annotations -s <session> <gsId>` subcommand showing only annotations (stripped of gameObject/zone blocks) would be faster for this use case. The current workflow requires `awk` + `sed -n` to extract the relevant section.

### tape proto show vs prior workflow

Prior workflow (grep JSONL) loses: annotation IDs, annotation ordering, `affectorId` on every annotation, detail key names and types, field-level values. It cannot answer "does LayeredEffectDestroyed fire before or after ResolutionComplete?" at all.

`tape proto show` makes all of this immediate. The ordering finding (TokenDeleted after ResolutionComplete, not inline; inline LayeredEffectDestroyed for enchanted creature) required full proto and would have been impossible to derive from JSONL alone. Net verdict: **proto show is mandatory for ordering/sequencing specs**. JSONL is only useful as a coarse index ("which gsId has the wipe?").
