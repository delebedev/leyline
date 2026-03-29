# Angelic Destiny — Card Spec

## Identity

- **Name:** Angelic Destiny
- **grpId:** 93993
- **Set:** FDN
- **Type:** Enchantment — Aura
- **Cost:** {2}{W}{W}
- **Forge script:** `forge/forge-gui/res/cardsfolder/a/angelic_destiny.txt`

## Mechanics

| Mechanic | Forge DSL | Catalog status |
|----------|-----------|----------------|
| Aura — enchant creature | `K:Enchant:Creature` | wired |
| ETB targeting (SelectTargetsReq) | implicit in Aura | wired |
| +4/+4 continuous | `S:Mode$ Continuous … AddPower$ 4 AddToughness$ 4` | wired |
| Grant Flying + First Strike | `AddKeyword$ Flying & First Strike` | wired (per keyword heuristic) |
| Grant Angel type | `AddType$ Angel` | wired |
| 3× LayeredEffectCreated at resolve | one per static line | wired |
| AttachmentCreated + Attachment pAnn | `GameEventCardAttachment` | wired |
| Return-to-hand trigger on host death | `T:Mode$ ChangesZone … Destination$ Graveyard` | wired |
| SBA_UnattachedAura when host leaves BF by any non-death route | SBA check in engine | **missing** — #unattached-aura |

## What it does

1. **Cast ({2}{W}{W})**: targets a creature — SelectTargetsReq fires, player selects host.
2. **Resolves to Battlefield**: aura attaches to target. Three continuous effects activate simultaneously: +4/+4 (ModifiedPower/Toughness), keyword grant (Flying + First Strike), type grant (Angel). Three LayeredEffectCreated transients fire; three [ModifiedPower+ModifiedToughness+LayeredEffect], [AddAbility+LayeredEffect], and [ModifiedType+LayeredEffect] persistent annotations appear on the host.
3. **Host dies (BF → GY)**: return-to-hand trigger fires. Angelic Destiny moves to owner's hand (Graveyard → Hand, category="Return"). Trigger correctly uses `Destination$ Graveyard` so bounce does NOT trigger it.
4. **Host bounced or otherwise leaves BF without dying**: SBA_UnattachedAura fires. All three LayeredEffectDestroyed fire (one per effect_id), then ObjectIdChanged + ZoneTransfer(SBA_UnattachedAura) sends the aura to GY. The return-to-hand trigger does NOT fire.

## Trace (session 2026-03-29_16-45-39, seat 1)

Angelic Destiny (`iid 311 → 406`) was cast and attached to Hare Apparent (`iid 373`), then fell off when the host was bounced by Mischievous Pup's ETB trigger.

### Cast (gsId 344)

| Field | Value |
|-------|-------|
| instanceId | 406 (after ObjectIdChanged from 311) |
| ZoneTransfer | zone_src=31 (Hand) → zone_dest=27 (Stack), category=`CastSpell` |
| SelectTargetsReq | promptId=10, targetIdx=1, targetingAbilityGrpId=1027, sourceId=406, abilityGrpId=93993 |
| Legal targets offered | iids 373, 377, 378, 380, 388, 397 (creatures on BF) — 373 highlighted Hot |
| minTargets / maxTargets | 1 / 1 |

Note: `targetingAbilityGrpId=1027` is the Enchant keyword ability, not the card's grpId. `abilityGrpId=93993` on the req is the card grpId.

### Resolve and attach (gsId 348)

Mana payment bracket (gsIds 344–347): 1× Plains tapped (color=1/White), 1× Forest tapped (color=5/Green), then resolution.

All annotations fire in gsId 348:

**Transients (annotations{}):**

| id | type | affectorId | affectedIds | details |
|----|------|------------|-------------|---------|
| 784 | ResolutionStart | 406 | 406 | grpid=93993 |
| 785 | ResolutionComplete | 406 | 406 | grpid=93993 |
| 787 | LayeredEffectCreated | 406 | [7003] | (no detail keys) |
| 789 | LayeredEffectCreated | 406 | [7004] | (no detail keys) |
| 791 | LayeredEffectCreated | 406 | [7005] | (no detail keys) |
| 793 | ZoneTransfer | 1 | [406] | zone_src=27, zone_dest=28, category=`Resolve` |
| 795 | AttachmentCreated | 406 | [373] | (no detail keys) |

**Persistent annotations (persistentAnnotations{}):**

| id | types | affectorId | affectedIds | details |
|----|-------|------------|-------------|---------|
| 786 | [ModifiedToughness, ModifiedPower, LayeredEffect] | 406 | [373] | effect_id=7003 |
| 788 | [AddAbility, LayeredEffect] | 406 | [373] | originalAbilityObjectZcid=[406,406], UniqueAbilityId=[303,304], grpid=[8,6], effect_id=7004 |
| 790 | [ModifiedType, LayeredEffect] | 406 | [373] | effect_id=7005 |
| 794 | [Attachment] | 406 | [373] | (no detail keys) |

**AddAbility detail:** grpid=8 = Flying, grpid=6 = First Strike. Two keywords packed into a single [AddAbility+LayeredEffect] pAnn. UniqueAbilityIds 303 and 304 are the ability slot ids on the enchanted creature (373).

**Effect ID allocation:** effect_ids 7003/7004/7005 are assigned in static-layer order: P/T → keyword → type. They are consecutive in this recording (other effects from other permanents occupy the lower IDs).

**Hare Apparent after attach:** gameObject iid 373 at gsId 348 shows subtypes=[Rabbit, Noble, Angel], power=6, toughness=6, uniqueAbilities include grpId=8 (Flying) and grpId=6 (First Strike) — confirming all three static effects applied correctly.

### SBA_UnattachedAura on bounce (gsId 463)

Mischievous Pup (`iid 435`, grpId 93857) entered the battlefield with Flash. Its ETB trigger (`abilityGrpId=169458`, trigger iid=439) targeted Hare Apparent (iid=373). At gsId 463, the trigger resolved, bouncing the host to hand.

All annotations in gsId 463:

| id | type | affectorId | affectedIds | details |
|----|------|------------|-------------|---------|
| 1014 | ResolutionStart | 439 | 439 | grpid=169458 |
| 1015 | ObjectIdChanged | 439 | [373] | orig_id=373, new_id=440 |
| 1018 | ZoneTransfer | 439 | [440] | zone_src=28 → zone_dest=31, category=`Return` |
| 1019 | ResolutionComplete | 439 | 439 | grpid=169458 |
| 1020 | AbilityInstanceDeleted | 435 | [439] | — |
| **1021** | **LayeredEffectDestroyed** | **406** | **[7003]** | **(no details)** |
| **1022** | **LayeredEffectDestroyed** | **406** | **[7004]** | **(no details)** |
| **1023** | **LayeredEffectDestroyed** | **406** | **[7005]** | **(no details)** |
| **1024** | **ObjectIdChanged** | *(absent)* | **[406]** | **orig_id=406, new_id=442** |
| **1025** | **ZoneTransfer** | *(absent)* | **[442]** | **zone_src=28 → zone_dest=33, category=`SBA_UnattachedAura`** |

Key observations:
- **Return trigger does NOT fire.** No AbilityInstanceCreated for Angelic Destiny's return trigger. Forge script `Destination$ Graveyard` correctly gates it — bounce goes to Hand, not GY, so the trigger condition is never met.
- **Three LayeredEffectDestroyed, not one.** River's Favor (1 static effect) only produces 1 LayeredEffectDestroyed. Angelic Destiny's 3 static lines produce 3, one per effect_id (7003, 7004, 7005). Count = number of LayeredEffectCreated at attachment time.
- **affectorId absent** on ObjectIdChanged (id 1024) and ZoneTransfer (id 1025). Consistent with all SBA categories — the SBA itself is the agent, no ability instance.
- **Same diff as trigger resolution.** The SBA check fires atomically with the resolution of the bounce. The stack is already empty by the time ObjectIdChanged+ZoneTransfer appear.
- **Aura destination is always GY (zone 33)**, never hand, even though the return trigger would have sent it to hand on death.

### What the Hare Apparent looks like after detach (gsId 463)

gameObject iid=440 (bounced Hare Apparent, now in hand): power=2, toughness=2, subtypes=[Rabbit, Noble] — Angel subtype removed. No Flying or First Strike in uniqueAbilities. All three effects cleanly gone.

## Key findings

1. **Three-effect aura produces 3× LayeredEffectCreated / 3× LayeredEffectDestroyed.** Each static line in `S:Mode$ Continuous` maps to one effect_id and one LayeredEffectCreated transient. The count at detach always matches the count at attach.

2. **Return trigger is correctly restricted to host death.** `Destination$ Graveyard` in the Forge trigger means bounce (BF→Hand) never fires the trigger. Wire confirms: no AbilityInstanceCreated, no SelectTargetsReq. Engine behavior is correct.

3. **Keyword grant packs multiple keywords into one AddAbility pAnn.** Flying and First Strike share a single [AddAbility+LayeredEffect] pAnn, with two entries each in `grpid`, `UniqueAbilityId`, and `originalAbilityObjectZcid`. This is effect_id 7004, the middle of the three.

4. **Type grant is a ModifiedType pAnn.** The Angel subtype addition gets its own effect_id (7005) and its own [ModifiedType+LayeredEffect] pAnn, separate from the keyword grant.

5. **SelectTargetsReq for aura cast uses targetingAbilityGrpId=1027** (the Enchant keyword ability grpId), not the card's own grpId. The card's grpId appears in `abilityGrpId` on the req. This is consistent with the exile-aura spec (Stasis Snare).

6. **Relevant to #31 (keyword-granting auras).** This is the first spec with both keyword and type grants in a single aura. The wire shape for [AddAbility+LayeredEffect] is established: multi-keyword packs into one annotation. The type grant is a separate pAnn.

7. **affectorId=406 on LayeredEffectDestroyed** — the aura's iid is the affector on all three destroy events, even though the SBA is what triggers the detach. Compare: ZoneTransfer itself has no affectorId. This asymmetry is consistent with the River's Favor observation.

## Gaps for leyline

1. **SBA_UnattachedAura annotation not emitted** (`catalog: unattached-aura: missing`). When the enchanted creature leaves the battlefield by any route other than death, leyline must emit: `LayeredEffectDestroyed` (one per effect_id, affectorId=aura iid), then `ObjectIdChanged` + `ZoneTransfer(SBA_UnattachedAura)` to zone 33 (GY). Three effects = three destroys.
2. **3× LayeredEffectCreated at aura resolution** — if leyline currently emits only one LayeredEffectCreated for multi-static auras, this is a gap. Each static line needs its own effect_id and transient.
3. **ModifiedType pAnn for type grant** — the Angel-type addition must produce a [ModifiedType+LayeredEffect] persistent annotation separate from the keyword grant.

## Supporting evidence

- `recordings/2026-03-29_16-45-39/` — primary session; Angelic Destiny attach gsId=348, bounce/SBA gsId=463
- `recordings/2026-03-22_23-20-47/` — River's Favor SBA_UnattachedAura (1 effect, host stolen+killed)
- `docs/card-specs/exile-aura-wire.md` — Stasis Snare: SelectTargetsReq shape for aura cast (targetingAbilityGrpId consistency)
- `docs/catalog.yaml` `unattached-aura: missing`
- GitHub issue #31 (keyword-granting spells)

---

## Agent Feedback

### Tooling pain points

**`md-frames.jsonl` is useful for discovery, lossy for field values.** The JSONL tool dropped detail keys in several annotation objects (detail values appeared as empty dicts). Using it to find interesting gsIds, then verifying with `tape proto show`, is the right two-step workflow as the task instructions describe.

**`tape proto show` was essential and worked well.** The full proto text resolved all ambiguities: exact affectorId presence/absence on SBA annotations, the `orig_id`/`new_id` on ObjectIdChanged, the exact prompt parameters on SelectTargetsReq, and the AddAbility multi-value packing. Without it, the JSONL output for the [AddAbility+LayeredEffect] pAnn would have looked like an empty detail block.

**One friction point:** `tape proto show` dumps all messages in a file, not just the target gsId. The file for gsId 344 also contains gsIds 457/458/459 (all packed in bin 305). Having to grep for `gameStateId: 463` within the output is workable but a `--gsId` flag on the tool would save a step. Piping through grep recovered the right section in all cases.

**Effect ID interpretation from JSONL was reliable here.** The effect_ids (7003, 7004, 7005) matched between the JSONL `affectedIds` values and the `effect_id` detail keys in the proto. No discrepancy observed — unusual, since the task instructions warned about JSONL being lossy for field values. The annotation types themselves were accurate in JSONL; it was detail key/value pairs that JSONL dropped.
