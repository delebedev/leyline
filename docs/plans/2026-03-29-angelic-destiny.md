# Angelic Destiny — Aura Keyword Grant + SBA_UnattachedAura (#31 / #unattached-aura)

## Context

**Depends on:** `feat/keyword-grant` plan (already landed). All Layer 0–5 pieces from
that plan (`KeywordGrpIds`, `GameEventExtrinsicKeywordAdded`, `KeywordGranted` event,
`EffectTracker.diffKeywords()`, `addAbilityMulti()`, `effectAnnotations()` keyword branch,
`CardProtoBuilder` extrinsic uniqueAbilities) are assumed to exist. This plan does not
re-implement them.

**Issue:** #31 (keyword-granting auras), #unattached-aura
**Branch:** `feat/angelic-destiny`
**Card specs:** `docs/card-specs/angelic-destiny.md`
**Design spec:** `docs/specs/2026-03-29-angelic-destiny-aura-design.md`
**Sessions:** `2026-03-29_16-45-39` (attach gsId=348, SBA_UnattachedAura gsId=463),
`2026-03-22_23-20-47` (River's Favor SBA_UnattachedAura, 1-effect baseline)

---

## Premise Verification

All claims verified against session `2026-03-29_16-45-39` via `just tape proto show`.

1. **3× LayeredEffectCreated at resolve** — gsId 348 contains transients 787/789/791,
   each with no detail keys, affectorId=406 (aura iid), affectedIds=[7003/7004/7005].
2. **Three pAnns at resolve:**
   - `[ModifiedToughness, ModifiedPower, LayeredEffect]` affectorId=406 affectedIds=[373] effect_id=7003
   - `[AddAbility, LayeredEffect]` affectorId=406 affectedIds=[373] grpid=[8,6] UniqueAbilityId=[303,304] effect_id=7004
   - `[ModifiedType, LayeredEffect]` affectorId=406 affectedIds=[373] effect_id=7005
3. **SBA_UnattachedAura gsId=463** — three `LayeredEffectDestroyed` (affectorId=406,
   affectedIds=[7003/7004/7005]), then `ObjectIdChanged` (no affectorId, 406→442),
   then `ZoneTransfer` (no affectorId, category=`SBA_UnattachedAura`, zone 28→33).
4. **Return trigger does NOT fire on bounce.** No AbilityInstanceCreated at gsId=463.
   Confirmed: Forge's `Destination$ Graveyard` gate correctly excludes bounce (BF→Hand).
5. **AttachmentCreated + Attachment pAnn** already fire from `GameEvent.CardAttached` in
   `AnnotationPipeline.mechanicAnnotations()`. No gap here.
6. **`SBA_UnattachedAura`** absent from `TransferCategory`. Must be added.
7. **`layeredEffectDestroyed(effectId)` has no `affectorId` param.** The SBA case needs
   affectorId = aura iid on all three destroy events. The builder needs a new overload.
8. **`modifiedTypeLayeredEffect()` has no `affectorId` param.** The aura wire has
   affectorId=aura iid on this pAnn. Need to add the param.

---

## What Overrun Plan Already Gives Us

- `KeywordGrpIds` (flying=8, first_strike=6)
- `EffectTracker.diffKeywords()` + `KeywordEntry` / `TrackedKeywordEffect`
- `AnnotationBuilder.addAbilityMulti()` — multi-creature AddAbility+LayeredEffect pAnn
- `AnnotationPipeline.effectAnnotations()` keyword branch — emits LayeredEffectCreated +
  AddAbility pAnn per keyword static ability
- `CardProtoBuilder` extrinsic uniqueAbilities — creature gameObjects gain the badge
- `StateMapper` keyword snapshot + `diffKeywords()` call

The Overrun plan handles **one keyword per grant** (`grpid=[14]`). Angelic Destiny needs
**two keywords in one pAnn** (`grpid=[8,6]`). This is the first new piece.

---

## New Work

### A. Multi-keyword AddAbility pAnn packing

Overrun's `addAbilityMulti()` takes a single `grpId: Int`. Angelic Destiny packs two
keywords (flying=8, first strike=6) into one `[AddAbility+LayeredEffect]` pAnn with
`grpid=[8,6]`, two `UniqueAbilityId` entries, two `originalAbilityObjectZcid` entries —
all under the same effect_id (7004).

Wire: one pAnn covers both keywords for one creature (not one pAnn per keyword).

**Why one pAnn, not two?** Both keywords come from a single static line in the Forge
script (`AddKeyword$ Flying & First Strike`). They share an effect_id and a static ability
ID. The real server aggregates them — follow suit.

### B. ModifiedType pAnn with affectorId

`modifiedTypeLayeredEffect()` currently lacks `affectorId`. For the aura case the
affectorId = aura iid (406). The crew case was silently setting no affectorId — that's
fine for crew. Adding an optional param is backward-compatible.

### C. layeredEffectDestroyed with affectorId

`layeredEffectDestroyed(effectId)` emits no affectorId. For SBA_UnattachedAura, all three
destroy events carry affectorId = aura iid. For EOT expiry (Overrun) there is no affectorId.
Add an optional `affectorId: Int = 0` param.

### D. TransferCategory.SbaUnattachedAura

Missing from enum. `ZoneTransfer` annotation must carry label `"SBA_UnattachedAura"`.
The aura destination is GY (zone 33) regardless of how the host left. `keepsSameInstanceId`
= false (ObjectIdChanged fires before ZoneTransfer).

### E. SBA_UnattachedAura detection and emission

The SBA check runs **after** the main transfer detection loop, at the end of
`StateMapper.buildDiffFromGame()`. Detection strategy: snapshot which auras are attached
to which creature iid, compare against the prior snapshot. When an aura was previously
attached but its host has left BF (or the aura itself has left BF via this diff), emit
the SBA sequence.

**Data source for attachment state:** `GameBridge.snapshotAuraAttachments()` — mirrors
`snapshotBoosts()`. Iterates BF cards, checks `card.isEnchanting()` / `card.getEnchanting()`.
Returns `Map<ForgeCardId /* aura */, ForgeCardId /* host */>`.

**Detection logic:**
1. Previous attachment snapshot (from last diff) stored in `GameBridge.lastAuraAttachments`.
2. Current snapshot from `snapshotAuraAttachments()`.
3. Auras in `lastAuraAttachments` whose host is no longer on BF in the current state →
   candidate SBA. Cross-check: is the aura itself also gone from BF (zone-transfer loop
   already handled it)? If the zone-transfer loop classified the aura as `Resolve`
   (Stack→BF), skip. Otherwise: SBA.
4. For each SBA aura: emit `LayeredEffectDestroyed` × N (one per effect_id tracked for
   that aura iid), then `ObjectIdChanged` (no affectorId), then
   `ZoneTransfer(SBA_UnattachedAura)` (no affectorId, BF→GY).

**EffectTracker aura binding:** The effect_ids for a given aura iid are already in
`EffectTracker.activeEffects` (the P/T boost) and `EffectTracker.activeKeywordEffects`
(the keywords). We need a third: `activeTypeEffects` for the ModifiedType layer. All three
must be consulted when destroying by aura iid.

Add `destroyByAuraIid(auraIid: Int): List<Int>` to `EffectTracker` — removes all tracked
effects (boost + keyword + type) whose fingerprint resolves to this aura iid and returns
their syntheticIds.

**Ordering constraint:** SBA annotations appear AFTER the trigger resolution that caused
the host to leave BF (the bounce resolution completes first at gsId=463). In the
snapshot-compare model, the SBA check runs at the end of `buildDiffFromGame()` after the
main annotation pipeline has already assembled the bounce annotations. Correct naturally.

### F. Return-to-hand trigger (host dies)

No leyline work required. Forge's `T:Mode$ ChangesZone | Origin$ Battlefield |
Destination$ Graveyard` trigger fires when the host goes to GY. The existing triggered
ability infrastructure handles AbilityInstanceCreated, SelectTargetsReq, and the
subsequent GY→Hand ZoneTransfer (category=`Return`). The trigger correctly does NOT fire
on bounce (host goes to Hand, not GY).

**Verification:** cast Angelic Destiny, kill host with removal, verify aura goes GY→Hand
with category=`Return`. This is a puzzle scenario (Puzzle 2 below).

---

## Files Touched

| File | Change |
|------|--------|
| `game/TransferCategory.kt` | Add `SbaUnattachedAura("SBA_UnattachedAura")` |
| `game/AnnotationBuilder.kt` | `layeredEffectDestroyed(effectId, affectorId)`, `modifiedTypeLayeredEffect(affectorId)`, `addAbilityPacked()` multi-keyword variant |
| `game/EffectTracker.kt` | `TypeGrantEntry` / `TrackedTypeEffect`, `diffTypeGrants()`, `destroyByAuraIid()`, `activeTypeEffects` map, `resetAll()` update |
| `game/GameBridge.kt` | `snapshotAuraAttachments()`, `lastAuraAttachments` field, `snapshotTypeGrants()` |
| `game/AnnotationPipeline.kt` | `effectAnnotations()` type-grant branch; `sbaUnattachedAura()` helper |
| `game/StateMapper.kt` | GATHER: `snapshotTypeGrants()` + `diffTypeGrants()`; POST-TRANSFER: `detectSbaUnattachedAura()` call |
| Puzzles | `puzzles/aura-attach-angelic-destiny.pzl`, `puzzles/aura-host-dies-angelic-destiny.pzl`, `puzzles/aura-host-bounced-angelic-destiny.pzl` |
| `docs/catalog.yaml` | `unattached-aura: wired`, `keyword-granting-auras: wired`, `type-granting-auras: wired` |

---

## Implementation

### Phase A: TransferCategory + layeredEffectDestroyed affectorId (data-layer fixes)

**`game/TransferCategory.kt`** — add after `SbaLegendRule`:

```kotlin
SbaUnattachedAura("SBA_UnattachedAura"),
```

**`game/AnnotationBuilder.kt`** — extend `layeredEffectDestroyed()`:

```kotlin
/** Layered effect ended. [affectorId] = source of the destruction (e.g. aura iid for
 *  SBA_UnattachedAura; 0 for EOT expiry which has no affector). Arena type 19. */
fun layeredEffectDestroyed(effectId: Int, affectorId: Int = 0): AnnotationInfo =
    AnnotationInfo.newBuilder()
        .addType(AnnotationType.LayeredEffectDestroyed)
        .apply { if (affectorId != 0) setAffectorId(affectorId) }
        .addAffectedIds(effectId)
        .build()
```

**`game/AnnotationBuilder.kt`** — add `affectorId` param to `modifiedTypeLayeredEffect()`:

```kotlin
/**
 * Persistent: type grant continuous effect. Types: [ModifiedType, LayeredEffect].
 * Wire shape: affectedIds=[hostIid], effect_id. affectorId = source iid (aura or crew ability).
 * - Aura case: affectorId = aura iid (confirmed Angelic Destiny gsId 348).
 * - Crew case: no affectorId (prior behavior preserved via default=0).
 */
fun modifiedTypeLayeredEffect(
    instanceId: Int,
    effectId: Int,
    affectorId: Int = 0,
    sourceAbilityGrpId: Int? = null,
): AnnotationInfo {
    val builder = AnnotationInfo.newBuilder()
        .addType(AnnotationType.ModifiedType)
        .addType(AnnotationType.LayeredEffect)
        .addAffectedIds(instanceId)
        .addDetails(int32Detail(DetailKeys.EFFECT_ID, effectId))
    if (affectorId != 0) builder.setAffectorId(affectorId)
    if (sourceAbilityGrpId != null) builder.addDetails(int32Detail(DetailKeys.SOURCE_ABILITY_GRPID, sourceAbilityGrpId))
    return builder.build()
}
```

**Test: `AnnotationBuilderSbaTest`**

```kotlin
class AnnotationBuilderSbaTest : FunSpec({
    test("layeredEffectDestroyed with affectorId") {
        val ann = AnnotationBuilder.layeredEffectDestroyed(7003, affectorId = 406)
        ann.affectorId shouldBe 406
        ann.affectedIdsList shouldContainExactly listOf(7003)
        ann.typeList shouldContain AnnotationType.LayeredEffectDestroyed
    }

    test("layeredEffectDestroyed without affectorId omits affector") {
        val ann = AnnotationBuilder.layeredEffectDestroyed(7009)
        ann.hasAffectorId() shouldBe false
    }

    test("modifiedTypeLayeredEffect with affectorId") {
        val ann = AnnotationBuilder.modifiedTypeLayeredEffect(373, 7005, affectorId = 406)
        ann.affectorId shouldBe 406
        ann.affectedIdsList shouldContainExactly listOf(373)
        ann.typeList shouldContain AnnotationType.ModifiedType
        ann.typeList shouldContain AnnotationType.LayeredEffect
        ann.detailsList.find { it.key == "effect_id" }?.valueInt32 shouldBe 7005
    }
})
```

### Phase B: Multi-keyword AddAbility pAnn packing

The Overrun plan's `addAbilityMulti()` takes `grpId: Int` (one keyword). Angelic Destiny
packs two keywords (`grpid=[8,6]`) into one pAnn under a shared effect_id. Add a variant:

**`game/AnnotationBuilder.kt`** — add after `addAbilityMulti()`:

```kotlin
/**
 * Keyword grant — multi-keyword variant for auras (e.g. Flying + First Strike).
 *
 * When a single static ability grants multiple keywords, the real server packs
 * all of them into ONE [AddAbility+LayeredEffect] pAnn with repeated grpid and
 * UniqueAbilityId entries. Confirmed: Angelic Destiny gsId 348, effect_id=7004,
 * grpid=[8,6], UniqueAbilityId=[303,304].
 *
 * [affectedId] = host creature iid (single target for aura).
 * [grpIds] = keyword grpIds in grant order (e.g. [8, 6] for Flying+FirstStrike).
 * [uniqueAbilityIds] = one slot id per keyword (same count as grpIds).
 * [originalAbilityObjectZcids] = one per keyword; all = aura iid for aura grants.
 * [affectorId] = aura iid.
 */
fun addAbilityPacked(
    affectedId: Int,
    grpIds: List<Int>,
    effectId: Int,
    uniqueAbilityIds: List<Int>,
    originalAbilityObjectZcids: List<Int>,
    affectorId: Int,
): AnnotationInfo {
    require(grpIds.size == uniqueAbilityIds.size) { "grpIds and uniqueAbilityIds must have same length" }
    require(grpIds.size == originalAbilityObjectZcids.size) { "grpIds and originalAbilityObjectZcids must have same length" }
    val builder = AnnotationInfo.newBuilder()
        .addType(AnnotationType.AddAbility_af5a)
        .addType(AnnotationType.LayeredEffect)
        .setAffectorId(affectorId)
        .addAffectedIds(affectedId)
        .addDetails(int32Detail(DetailKeys.EFFECT_ID, effectId))
    grpIds.forEach { builder.addDetails(uint32Detail(DetailKeys.GRPID, it)) }
    uniqueAbilityIds.forEach { builder.addDetails(int32Detail(DetailKeys.UNIQUE_ABILITY_ID, it)) }
    originalAbilityObjectZcids.forEach {
        builder.addDetails(int32Detail(DetailKeys.ORIGINAL_ABILITY_OBJECT_ZCID, it))
    }
    return builder.build()
}
```

**Test: `AnnotationBuilderPackedKeywordTest`**

```kotlin
class AnnotationBuilderPackedKeywordTest : FunSpec({
    test("addAbilityPacked shape matches Angelic Destiny wire spec") {
        val ann = AnnotationBuilder.addAbilityPacked(
            affectedId = 373,
            grpIds = listOf(8, 6),
            effectId = 7004,
            uniqueAbilityIds = listOf(303, 304),
            originalAbilityObjectZcids = listOf(406, 406),
            affectorId = 406,
        )
        ann.typeList shouldContain AnnotationType.AddAbility_af5a
        ann.typeList shouldContain AnnotationType.LayeredEffect
        ann.affectorId shouldBe 406
        ann.affectedIdsList shouldContainExactly listOf(373)
        ann.detailsList.filter { it.key == "grpid" }.map { it.valueUInt32 } shouldContainExactly listOf(8, 6)
        ann.detailsList.filter { it.key == "UniqueAbilityId" }.map { it.valueInt32 } shouldContainExactly listOf(303, 304)
        ann.detailsList.filter { it.key == "originalAbilityObjectZcid" }.map { it.valueInt32 } shouldContainExactly listOf(406, 406)
        ann.detailsList.find { it.key == "effect_id" }?.valueInt32 shouldBe 7004
    }

    test("addAbilityPacked requires same-length lists") {
        shouldThrow<IllegalArgumentException> {
            AnnotationBuilder.addAbilityPacked(
                affectedId = 1, grpIds = listOf(8), effectId = 7004,
                uniqueAbilityIds = listOf(1, 2), originalAbilityObjectZcids = listOf(406),
                affectorId = 406,
            )
        }
    }
})
```

**Integration note:** `effectAnnotations()` keyword branch (from Overrun plan) must detect
when multiple keywords share the same static ability fingerprint (same timestamp + staticId)
and route to `addAbilityPacked()` instead of `addAbilityMulti()`. Update the grouping
logic:

```kotlin
// In effectAnnotations() keyword group loop:
val (keyword, timestamp, staticId) = key
// NEW: when multiple distinct keywords share the same (timestamp, staticId), they come
// from the same "packed" static ability — emit one pAnn with all keyword grpIds.
// This happens for auras like Angelic Destiny (Flying + First Strike = one static line).
//
// The existing addAbilityMulti() handles the Overrun case (one keyword, many creatures).
// The addAbilityPacked() handles the aura case (multiple keywords, one creature).
// Mixed case (multiple keywords, multiple creatures) is not yet observed; punt until needed.
```

Grouping key must be `(timestamp, staticId)` only — not `keyword` — so that
Flying+FirstStrike issued by the same static ability land in one group. The keyword list
is then collected from the group members:

```kotlin
// Group by (timestamp, staticId) — not by keyword — to detect packed multi-keyword grants
val groups = keywordDiff.created
    .groupBy { it.fingerprint.timestamp to it.fingerprint.staticId }

for ((_, effects) in groups) {
    val keywords = effects.map { it.keyword }.distinct()
    val grpIds = keywords.mapNotNull { KeywordGrpIds.forKeyword(it) }
    if (grpIds.isEmpty()) continue  // all unknown keywords — skip

    val effectId = effects.first().syntheticId
    val affectorId = keywordAffectorResolver?.invoke(effects.first().keyword, ...) ?: 0

    if (effects.all { it.cardInstanceId == effects.first().cardInstanceId }) {
        // All affected cards are the same → aura single-target packed case
        val hostIid = effects.first().cardInstanceId
        val uniqueAbilityIds = grpIds.map { uniqueAbilityIdAllocator() }
        val zcids = grpIds.map { affectorId }   // all point to aura iid
        persistent.add(AnnotationBuilder.addAbilityPacked(hostIid, grpIds, effectId, uniqueAbilityIds, zcids, affectorId))
    } else {
        // Different cards → spell/lord multi-creature case (single keyword per group guaranteed
        // by the groupBy key, so use addAbilityMulti)
        val allIids = effects.map { it.cardInstanceId }.distinct()
        val uniqueAbilityIds = allIids.map { uniqueAbilityIdAllocator() }
        persistent.add(AnnotationBuilder.addAbilityMulti(allIids, grpIds.first(), effectId, uniqueAbilityIds, affectorId, affectorId))
    }

    transient.add(AnnotationBuilder.layeredEffectCreated(effectId, if (affectorId != 0) affectorId else null))
}
```

### Phase C: EffectTracker Type Grant Tracking

Parallel to `diffKeywords()`, add type grant tracking for the `[ModifiedType+LayeredEffect]`
pAnn lifecycle. The Forge model: `card.getChangedCardTypes()` returns a table of
(timestamp, staticId) → type additions. Only the existence matters (not the type name —
the type itself comes from the card's current `type` field).

**`game/EffectTracker.kt`** — add inside the class:

```kotlin
data class TypeGrantEntry(val timestamp: Long, val staticId: Long)

data class TypeGrantFingerprint(val cardInstanceId: Int, val timestamp: Long, val staticId: Long)

data class TrackedTypeEffect(
    val syntheticId: Int,
    val fingerprint: TypeGrantFingerprint,
) {
    val cardInstanceId: Int get() = fingerprint.cardInstanceId
}

data class TypeGrantDiffResult(
    val created: List<TrackedTypeEffect>,
    val destroyed: List<TrackedTypeEffect>,
)

private val activeTypeEffects = mutableMapOf<TypeGrantFingerprint, TrackedTypeEffect>()

/**
 * Diff current type grants against tracked state.
 * [currentGrants]: cardInstanceId → list of active TypeGrantEntry values.
 * Parallels [diffKeywords].
 */
fun diffTypeGrants(currentGrants: Map<Int, List<TypeGrantEntry>>): TypeGrantDiffResult {
    val currentFps = mutableMapOf<TypeGrantFingerprint, TypeGrantEntry>()
    for ((cardIid, entries) in currentGrants) {
        for (entry in entries) {
            currentFps[TypeGrantFingerprint(cardIid, entry.timestamp, entry.staticId)] = entry
        }
    }

    val destroyed = mutableListOf<TrackedTypeEffect>()
    val toRemove = mutableListOf<TypeGrantFingerprint>()
    for ((fp, tracked) in activeTypeEffects) {
        if (fp !in currentFps) {
            destroyed.add(tracked)
            toRemove.add(fp)
        }
    }
    for (fp in toRemove) activeTypeEffects.remove(fp)

    val created = mutableListOf<TrackedTypeEffect>()
    for ((fp, _) in currentFps) {
        if (fp !in activeTypeEffects) {
            val tracked = TrackedTypeEffect(nextEffectId(), fp)
            activeTypeEffects[fp] = tracked
            created.add(tracked)
        }
    }

    return TypeGrantDiffResult(created, destroyed)
}

/**
 * Destroy all tracked effects (boost + keyword + type) associated with [auraIid].
 * Used by SBA_UnattachedAura to emit LayeredEffectDestroyed for each effect.
 * Returns the syntheticIds that were destroyed, in deterministic order:
 * boost effects first (lowest syntheticId), then keyword, then type.
 *
 * [auraIid] = the aura's current instanceId. The fingerprint mapping is:
 * boost effects: EffectFingerprint.cardInstanceId = host iid (the creature being boosted)
 * but the *affector* (aura) is different. We can't look up by aura iid in the boost table
 * directly — the fingerprint key is the HOST.
 *
 * Alternative: caller supplies the set of syntheticIds to destroy (pre-resolved from
 * the active effect maps keyed by host iid + fingerprint). See snapshotAuraEffects() in GameBridge.
 */
fun destroyEffectIds(syntheticIds: List<Int>): List<Int> {
    // Remove from all three maps
    activeEffects.entries.removeIf { it.value.syntheticId in syntheticIds }
    activeKeywordEffects.entries.removeIf { it.value.syntheticId in syntheticIds }
    activeTypeEffects.entries.removeIf { it.value.syntheticId in syntheticIds }
    return syntheticIds
}
```

Update `resetAll()`:

```kotlin
fun resetAll() {
    nextId = INITIAL_EFFECT_ID
    activeEffects.clear()
    activeKeywordEffects.clear()
    activeTypeEffects.clear()
    initEmitted = false
}
```

**Test: `EffectTrackerTypeGrantTest`**

```kotlin
class EffectTrackerTypeGrantTest : FunSpec({
    lateinit var tracker: EffectTracker
    beforeEach { tracker = EffectTracker() }

    test("diffTypeGrants creates entry on first call") {
        val input = mapOf(373 to listOf(EffectTracker.TypeGrantEntry(1L, 5L)))
        val diff = tracker.diffTypeGrants(input)
        diff.created.size shouldBe 1
        diff.created[0].cardInstanceId shouldBe 373
        diff.destroyed shouldBe emptyList()
    }

    test("diffTypeGrants stable on repeat call") {
        val input = mapOf(373 to listOf(EffectTracker.TypeGrantEntry(1L, 5L)))
        tracker.diffTypeGrants(input)
        val diff2 = tracker.diffTypeGrants(input)
        diff2.created shouldBe emptyList()
        diff2.destroyed shouldBe emptyList()
    }

    test("diffTypeGrants destroys entry when grant removed") {
        val input = mapOf(373 to listOf(EffectTracker.TypeGrantEntry(1L, 5L)))
        tracker.diffTypeGrants(input)
        val diff2 = tracker.diffTypeGrants(emptyMap())
        diff2.destroyed.size shouldBe 1
    }

    test("destroyEffectIds removes from all three maps") {
        // Create one effect of each type
        tracker.diffBoosts(mapOf(373 to listOf(EffectTracker.BoostEntry(1L, 5L, 4, 4))))
        tracker.diffKeywords(mapOf(373 to listOf(EffectTracker.KeywordEntry(1L, 5L, "Flying"))))
        tracker.diffTypeGrants(mapOf(373 to listOf(EffectTracker.TypeGrantEntry(1L, 5L))))
        // IDs are 7002 (init skips), first actual = depends on whether init ran
        // Just verify they're tracked at all and destroy works
        val ids = (7002..7010).toList()
        tracker.destroyEffectIds(ids)  // overshoots; that's fine — removes what matches
        // After destroy, calling diff with the same inputs creates fresh entries
        val diff = tracker.diffBoosts(mapOf(373 to listOf(EffectTracker.BoostEntry(1L, 5L, 4, 4))))
        diff.created.size shouldBe 1   // was removed, now re-created
    }
})
```

### Phase D: GameBridge — Attachment and Type Grant Snapshots

**`game/GameBridge.kt`** — add three new snapshot methods after `snapshotBoosts()`:

```kotlin
/**
 * Snapshot current aura attachments: aura forge card ID → host forge card ID.
 * Used for SBA_UnattachedAura detection: if an aura was attached at the last diff
 * but its host is no longer on BF, emit the SBA sequence.
 *
 * Only covers auras (Enchantment — Aura). Equipment uses different SBA rules.
 */
fun snapshotAuraAttachments(): Map<ForgeCardId, ForgeCardId> {
    val game = game ?: return emptyMap()
    val result = mutableMapOf<ForgeCardId, ForgeCardId>()
    for (player in game.players) {
        for (card in player.getZone(ZoneType.Battlefield).cards) {
            if (card.isEnchanting) {
                val host = card.enchanting ?: continue
                result[ForgeCardId(card.id)] = ForgeCardId(host.id)
            }
        }
    }
    return result
}

/**
 * Snapshot all syntheticIds associated with an aura's effects.
 * Collects boost + keyword + type effect IDs whose fingerprints correspond to
 * the enchanted host at the time of attachment.
 *
 * [auraForgeId] = aura card's Forge ID.
 * [hostIid] = enchanted creature's current instanceId (for fingerprint lookup).
 *
 * Returns the list of syntheticIds to destroy when SBA fires.
 */
fun snapshotAuraEffectIds(auraForgeId: ForgeCardId, hostIid: Int): List<Int> {
    // The boost and type effects are keyed by (hostIid, timestamp, staticId).
    // We resolve by looking up the static abilities of the aura card.
    val auraCard = game?.let { findCard(it, auraForgeId) } ?: return emptyList()
    val result = mutableListOf<Int>()

    // Boost effects: EffectFingerprint(hostIid, timestamp, staticId)
    for (tracked in effects.activeEffectsForHost(hostIid)) {
        if (trackedEffectIsFromAura(tracked, auraCard)) result.add(tracked.syntheticId)
    }
    // Keyword effects: KeywordFingerprint(hostIid, timestamp, staticId)
    for (tracked in effects.activeKeywordEffectsForHost(hostIid)) {
        if (trackedEffectIsFromAura(tracked.fingerprint.timestamp, tracked.fingerprint.staticId, auraCard)) result.add(tracked.syntheticId)
    }
    // Type effects: TypeGrantFingerprint(hostIid, timestamp, staticId)
    for (tracked in effects.activeTypeEffectsForHost(hostIid)) {
        if (trackedEffectIsFromAura(tracked.fingerprint.timestamp, tracked.fingerprint.staticId, auraCard)) result.add(tracked.syntheticId)
    }
    return result.sorted()  // ascending = layer order (P/T < keyword < type matches real server 7003<7004<7005)
}
```

> **Note:** `activeEffectsForHost()`, `activeKeywordEffectsForHost()`, `activeTypeEffectsForHost()` are new accessor methods on `EffectTracker` (iterate the respective maps, filter by `cardInstanceId == hostIid`). `trackedEffectIsFromAura()` is a private helper that cross-checks the Forge static ability's source card ID against the aura card — details in Phase D unit test.

**Alternative simpler approach (if the aura-to-static-id link is hard to resolve):**
Store `Map<ForgeCardId /* aura */, List<Int> /* syntheticIds */>` directly in `GameBridge`
at resolution time (when `AttachmentCreated` fires, snapshot the currently-created
effect IDs and pin them to the aura). This is more explicit but requires a hook at
resolution. Use this fallback if the Forge static-ability fingerprint route proves brittle.

**`game/GameBridge.kt`** — add mutable field for last attachment snapshot:

```kotlin
/** Last-known aura→host mapping. Updated at the end of each diff build. */
private var lastAuraAttachments: Map<ForgeCardId, ForgeCardId> = emptyMap()

fun updateAuraAttachments(current: Map<ForgeCardId, ForgeCardId>) {
    lastAuraAttachments = current
}

fun lastAuraAttachments(): Map<ForgeCardId, ForgeCardId> = lastAuraAttachments
```

**Snapshot type grants** — parallel to `snapshotBoosts()`:

```kotlin
/**
 * Snapshot current type grants for all battlefield cards.
 * Returns map of cardInstanceId → TypeGrantEntry list from Forge's changedCardTypes table.
 */
fun snapshotTypeGrants(): Map<Int, List<EffectTracker.TypeGrantEntry>> {
    val game = game ?: return emptyMap()
    val result = mutableMapOf<Int, List<EffectTracker.TypeGrantEntry>>()
    for (player in game.players) {
        for (card in player.getZone(ZoneType.Battlefield).cards) {
            val table = card.changedCardTypesTable   // Guava Table<Long, Long, CardChangedType>
            if (table.isEmpty) continue
            val instanceId = ids.getOrAlloc(ForgeCardId(card.id))
            val entries = table.cellSet().map { cell ->
                EffectTracker.TypeGrantEntry(
                    timestamp = cell.rowKey,
                    staticId = cell.columnKey,
                )
            }
            result[instanceId.value] = entries
        }
    }
    return result
}
```

> **Forge API check needed:** Verify `card.changedCardTypesTable` is the right field. May
> be `card.getChangedCardTypes()` returning a different structure. Check
> `forge/forge-game/src/main/java/forge/game/card/Card.java` before implementing. Flag
> for engine-bridge agent if the API is wrong.

### Phase E: AnnotationPipeline — type grant branch + SBA helper

**`game/AnnotationPipeline.kt`** — extend `effectAnnotations()` to accept
`EffectTracker.TypeGrantDiffResult`:

```kotlin
fun effectAnnotations(
    diff: EffectTracker.DiffResult,
    sourceAbilityResolver: ((InstanceId, Long) -> Int?)? = null,
    keywordDiff: EffectTracker.KeywordDiffResult = EffectTracker.KeywordDiffResult(emptyList(), emptyList()),
    idResolver: ((ForgeCardId) -> InstanceId)? = null,
    keywordAffectorResolver: ((String, Long, Long) -> Int)? = null,
    uniqueAbilityIdAllocator: (() -> Int)? = null,
    // NEW:
    typeGrantDiff: EffectTracker.TypeGrantDiffResult = EffectTracker.TypeGrantDiffResult(emptyList(), emptyList()),
    typeGrantAffectorResolver: ((Int, Long, Long) -> Int)? = null,  // (hostIid, ts, staticId) → aura iid
): Pair<List<AnnotationInfo>, List<AnnotationInfo>>
```

Type grant section (add after keyword section):

```kotlin
// ── Type grants ──────────────────────────────────────────────────────────
for (effect in typeGrantDiff.created) {
    val affectorId = typeGrantAffectorResolver?.invoke(effect.cardInstanceId, effect.fingerprint.timestamp, effect.fingerprint.staticId) ?: 0
    transient.add(AnnotationBuilder.layeredEffectCreated(effect.syntheticId, if (affectorId != 0) affectorId else null))
    persistent.add(AnnotationBuilder.modifiedTypeLayeredEffect(effect.cardInstanceId, effect.syntheticId, affectorId = affectorId))
    log.debug("effectAnnotations: type grant effectId={} host={} affector={}", effect.syntheticId, effect.cardInstanceId, affectorId)
}
for (effect in typeGrantDiff.destroyed) {
    transient.add(AnnotationBuilder.layeredEffectDestroyed(effect.syntheticId))
}
```

**SBA helper:**

```kotlin
/**
 * Build the SBA_UnattachedAura annotation block for one aura.
 *
 * Wire shape (gsId 463 from session 2026-03-29_16-45-39):
 *   LayeredEffectDestroyed  affectorId=auraIid  affectedIds=[effectId]  × N
 *   ObjectIdChanged         no affectorId        affectedIds=[auraOldIid]  orig=old new=new
 *   ZoneTransfer            no affectorId        affectedIds=[auraNewIid]  BF→GY  category=SBA_UnattachedAura
 *
 * [auraOldIid] = aura's instanceId before the ID change.
 * [auraNewIid] = aura's instanceId after reallocation (auraOldIid if no reallocation needed).
 * [effectIds] = syntheticIds to destroy (sorted, ascending).
 * Returns (transient, persistent) — persistent is always empty for SBA.
 */
fun sbaUnattachedAuraAnnotations(
    auraOldIid: Int,
    auraNewIid: Int,
    auraInstanceId: Int,    // the iid that goes on affectorId of LayeredEffectDestroyed = auraOldIid at the time
    effectIds: List<Int>,
    bfZoneId: Int,
    gyZoneId: Int,
): Pair<List<AnnotationInfo>, List<AnnotationInfo>> {
    val annotations = mutableListOf<AnnotationInfo>()
    for (effectId in effectIds) {
        annotations.add(AnnotationBuilder.layeredEffectDestroyed(effectId, affectorId = auraInstanceId))
    }
    annotations.add(AnnotationBuilder.objectIdChanged(auraOldIid, auraNewIid))   // no affectorId
    annotations.add(AnnotationBuilder.zoneTransfer(auraNewIid, bfZoneId, gyZoneId, TransferCategory.SbaUnattachedAura.label))
    return annotations to emptyList()
}
```

**Test: `SbaUnattachedAuraAnnotationTest`**

```kotlin
class SbaUnattachedAuraAnnotationTest : FunSpec({
    test("sbaUnattachedAuraAnnotations matches wire shape for 3-effect aura") {
        val (transient, persistent) = AnnotationPipeline.sbaUnattachedAuraAnnotations(
            auraOldIid = 406, auraNewIid = 442, auraInstanceId = 406,
            effectIds = listOf(7003, 7004, 7005),
            bfZoneId = 28, gyZoneId = 33,
        )
        persistent shouldBe emptyList()
        // 3 LayeredEffectDestroyed + ObjectIdChanged + ZoneTransfer = 5 annotations
        transient shouldHaveSize 5

        val destroys = transient.filter { it.typeList.contains(AnnotationType.LayeredEffectDestroyed) }
        destroys shouldHaveSize 3
        destroys.all { it.affectorId == 406 } shouldBe true
        destroys.map { it.affectedIdsList.first() } shouldContainExactly listOf(7003, 7004, 7005)

        val oidc = transient.first { it.typeList.contains(AnnotationType.ObjectIdChanged) }
        oidc.hasAffectorId() shouldBe false
        oidc.detailsList.find { it.key == "orig_id" }?.valueInt32 shouldBe 406
        oidc.detailsList.find { it.key == "new_id" }?.valueInt32 shouldBe 442

        val zt = transient.first { it.typeList.contains(AnnotationType.ZoneTransfer_af5a) }
        zt.hasAffectorId() shouldBe false
        zt.detailsList.find { it.key == "category" }?.valueString shouldBe "SBA_UnattachedAura"
        zt.affectedIdsList shouldContainExactly listOf(442)
    }

    test("sbaUnattachedAuraAnnotations with single effect (River's Favor baseline)") {
        val (transient, _) = AnnotationPipeline.sbaUnattachedAuraAnnotations(
            auraOldIid = 300, auraNewIid = 301, auraInstanceId = 300,
            effectIds = listOf(7003),
            bfZoneId = 28, gyZoneId = 33,
        )
        transient.filter { it.typeList.contains(AnnotationType.LayeredEffectDestroyed) } shouldHaveSize 1
    }
})
```

### Phase F: StateMapper Integration

**`game/StateMapper.kt`** — GATHER phase additions (after existing keyword diff):

```kotlin
// Type grant diff
val typeGrantSnapshot = bridge.snapshotTypeGrants()
val typeGrantDiff = bridge.effects.diffTypeGrants(typeGrantSnapshot)

// Aura attachment snapshot for SBA detection
val currentAuraAttachments = bridge.snapshotAuraAttachments()
val prevAuraAttachments = bridge.lastAuraAttachments()
```

**COMPUTE phase** — extend `effectAnnotations()` call:

```kotlin
val (effectTransient, effectPersistent) = AnnotationPipeline.effectAnnotations(
    diff = effectDiff,
    sourceAbilityResolver = sourceAbilityResolver,
    keywordDiff = keywordDiff,
    idResolver = { fid -> bridge.getOrAllocInstanceId(fid) },
    keywordAffectorResolver = buildKeywordAffectorResolver(bridge),
    uniqueAbilityIdAllocator = { bridge.allocUniqueAbilityId() },
    // NEW:
    typeGrantDiff = typeGrantDiff,
    typeGrantAffectorResolver = { hostIid, ts, staticId ->
        // Look up which aura is responsible for this type grant
        // by finding the aura attached to hostIid whose static ability has (ts, staticId)
        bridge.resolveTypeGrantAffector(hostIid, ts, staticId)
    },
)
```

**POST-TRANSFER phase** — SBA detection after all other annotations assembled:

```kotlin
// SBA_UnattachedAura: auras that were attached in previous diff but whose host left BF
val sbaAnnotations = detectSbaUnattachedAura(
    prevAuraAttachments = prevAuraAttachments,
    currentAuraAttachments = currentAuraAttachments,
    bridge = bridge,
    bfZoneId = ZONE_BATTLEFIELD,
    gyZoneId = /* owner-appropriate GY zone id */ ...,
)
annotations.addAll(sbaAnnotations)

// Update attachment snapshot for next diff
bridge.updateAuraAttachments(currentAuraAttachments)
```

**`detectSbaUnattachedAura()` private helper:**

```kotlin
private fun detectSbaUnattachedAura(
    prevAuraAttachments: Map<ForgeCardId, ForgeCardId>,
    currentAuraAttachments: Map<ForgeCardId, ForgeCardId>,
    bridge: GameBridge,
    bfZoneId: Int,
    gyZoneId: Int,
): List<AnnotationInfo> {
    val result = mutableListOf<AnnotationInfo>()

    for ((auraForgeId, hostForgeId) in prevAuraAttachments) {
        if (auraForgeId in currentAuraAttachments) continue  // still attached — no SBA

        // Host left BF (or aura left BF via another route).
        // Determine if this is actually an SBA vs. the aura intentionally resolving/leaving.
        // Zone transfer loop already handles: Stack→BF (Resolve), BF→GY (Destroy), BF→Exile.
        // If the aura was already processed by the transfer loop this diff, skip it.
        // Check: is the aura still on BF? If not and not in currentAuraAttachments,
        // and its zone transfer hasn't been handled above → SBA.
        val auraCurrentZone = bridge.getCurrentZone(auraForgeId)
        if (auraCurrentZone != bfZoneId) continue  // aura already moved, handled by transfer loop

        // Aura is still on BF but unattached — SBA fires now
        val hostIid = bridge.getOrAllocInstanceId(hostForgeId).value
        val effectIds = bridge.snapshotAuraEffectIds(auraForgeId, hostIid)
        val auraOldIid = bridge.getOrAllocInstanceId(auraForgeId).value
        val realloc = bridge.reallocInstanceId(auraForgeId)
        val auraNewIid = realloc.new.value

        val gyForAura = bridge.getOwnerGraveyardZoneId(auraForgeId)
        bridge.effects.destroyEffectIds(effectIds)

        val (sbaTransient, _) = AnnotationPipeline.sbaUnattachedAuraAnnotations(
            auraOldIid = auraOldIid, auraNewIid = auraNewIid, auraInstanceId = auraOldIid,
            effectIds = effectIds, bfZoneId = bfZoneId, gyZoneId = gyForAura,
        )
        result.addAll(sbaTransient)
        log.debug("SBA_UnattachedAura: aura={} host={} effectIds={}", auraOldIid, hostIid, effectIds)
    }
    return result
}
```

### Phase G: Puzzles

#### Puzzle 1 — Attach (verify 3× LayeredEffectCreated, packed keyword pAnn, type grant)

```
# puzzles/aura-attach-angelic-destiny.pzl
# Verify: casting Angelic Destiny on a creature emits 3 LayeredEffectCreated,
#         an AddAbility pAnn with grpid=[8,6], and a ModifiedType pAnn.
# Win: cast Angelic Destiny targeting opponent creature — can't immediately win
#      but test checks annotation shape. Use an attack plan: P1 creature enchanted,
#      opponent has 4 life, attack for lethal.

name: aura-attach-angelic-destiny
seat: 1
turn: 1
phase: MAIN1

player1:
  life: 20
  mana: [W, W, W, W]
  hand:
    - grpId: 93993    # Angelic Destiny
  battlefield:
    - grpId: 93738    # Sun-Blessed Healer (2/2 creature — cheap enchant target)
      iid: 101

player2:
  life: 8
  battlefield: []

# Win path: cast Angelic Destiny on Sun-Blessed Healer → 6/6 Flying First Strike Angel →
#           attack for 6, opponent at 2 life → next turn lethal.
# Puzzle check: assert annotations in resolution diff contain exactly:
#   - 3 LayeredEffectCreated transients (one per effect: P/T, keyword, type)
#   - 1 [AddAbility+LayeredEffect] pAnn with grpid=[8,6]
#   - 1 [ModifiedType+LayeredEffect] pAnn
#   - 1 [ModifiedToughness+ModifiedPower+LayeredEffect] pAnn
#   - 1 AttachmentCreated transient + 1 Attachment pAnn
```

#### Puzzle 2 — Host dies (SBA + return trigger fires)

```
# puzzles/aura-host-dies-angelic-destiny.pzl
# Verify: when enchanted creature dies, SBA_UnattachedAura fires, then return trigger
#         returns Angelic Destiny to hand. Category = Return.

name: aura-host-dies-angelic-destiny
seat: 2
turn: 3
phase: MAIN2

player1:
  life: 8
  battlefield:
    - grpId: 93738    # Sun-Blessed Healer, enchanted
      iid: 101
      enchantedBy: [201]
    - grpId: 93993    # Angelic Destiny attached to 101
      iid: 201
      attachedTo: 101

player2:
  life: 20
  hand:
    - grpId: 67048    # Murder (destroys target creature)
  mana: [B, B, B]

# Win path: cast Murder targeting Sun-Blessed Healer.
# Assertions:
#   - 3 LayeredEffectDestroyed (affectorId=201) in SBA diff
#   - ObjectIdChanged (no affectorId) on aura iid
#   - ZoneTransfer(SBA_UnattachedAura) BF→GY
#   - AbilityInstanceCreated for return trigger
#   - ZoneTransfer(Return) GY→Hand for Angelic Destiny
```

#### Puzzle 3 — Host bounced (SBA fires, return trigger does NOT fire)

```
# puzzles/aura-host-bounced-angelic-destiny.pzl
# Verify: when enchanted creature is bounced (BF→Hand), SBA_UnattachedAura fires
#         but the return-to-hand trigger does NOT (Destination$ Graveyard gate).

name: aura-host-bounced-angelic-destiny
seat: 2
turn: 3
phase: MAIN2

player1:
  life: 8
  battlefield:
    - grpId: 93738    # Sun-Blessed Healer, enchanted
      iid: 101
      enchantedBy: [201]
    - grpId: 93993    # Angelic Destiny attached to 101
      iid: 201
      attachedTo: 101

player2:
  life: 20
  hand:
    - grpId: 68740    # Unsummon (bounce target creature)
  mana: [U]

# Win path: cast Unsummon targeting Sun-Blessed Healer.
# Assertions:
#   - ZoneTransfer(Return) for Sun-Blessed Healer BF→Hand
#   - 3 LayeredEffectDestroyed (affectorId=201) same diff
#   - ZoneTransfer(SBA_UnattachedAura) for Angelic Destiny BF→GY
#   - NO AbilityInstanceCreated for the return trigger (must be absent)
```

---

## Tests Summary

| Test class | Tier | Validates |
|---|---|---|
| `AnnotationBuilderSbaTest` | Unit (0.01s) | `layeredEffectDestroyed(affectorId)`, `modifiedTypeLayeredEffect(affectorId)` |
| `AnnotationBuilderPackedKeywordTest` | Unit (0.01s) | `addAbilityPacked()` — multi-keyword grpid packing, list sizes |
| `EffectTrackerTypeGrantTest` | Unit (0.01s) | `diffTypeGrants()` lifecycle, `destroyEffectIds()` cross-map removal |
| `SbaUnattachedAuraAnnotationTest` | Unit (0.01s) | `sbaUnattachedAuraAnnotations()` — annotation count, affectorId presence/absence, category string |
| `EffectAnnotationsTypeGrantTest` | Conformance (0.01s) | `effectAnnotations()` type-grant branch: LayeredEffectCreated + ModifiedType pAnn shape |
| `AngelicDestinyAttachPuzzleTest` | Integration (0.09s) | Puzzle 1 — 3× LayeredEffectCreated, packed AddAbility pAnn, ModifiedType pAnn, creature badge |
| `AngelicDestinyHostDiesPuzzleTest` | Integration (0.09s) | Puzzle 2 — SBA_UnattachedAura sequence + return trigger fires |
| `AngelicDestinyHostBouncedPuzzleTest` | Integration (0.09s) | Puzzle 3 — SBA_UnattachedAura fires, no return trigger |

---

## Deliverables Checklist

- [ ] `game/TransferCategory.kt` — add `SbaUnattachedAura`
- [ ] `game/AnnotationBuilder.kt` — `layeredEffectDestroyed(affectorId)`, `modifiedTypeLayeredEffect(affectorId)`, `addAbilityPacked()`
- [ ] `game/EffectTracker.kt` — `TypeGrantEntry`, `TrackedTypeEffect`, `TypeGrantDiffResult`, `diffTypeGrants()`, `destroyEffectIds()`, `activeTypeEffects`, `resetAll()` update
- [ ] `game/EffectTracker.kt` — `activeEffectsForHost()`, `activeKeywordEffectsForHost()`, `activeTypeEffectsForHost()` accessors
- [ ] `game/GameBridge.kt` — `snapshotAuraAttachments()`, `snapshotTypeGrants()`, `snapshotAuraEffectIds()`, `lastAuraAttachments` + `updateAuraAttachments()`, `resolveTypeGrantAffector()`, `getOwnerGraveyardZoneId()`
- [ ] `game/AnnotationPipeline.kt` — `effectAnnotations()` type-grant param + branch, `sbaUnattachedAuraAnnotations()`
- [ ] `game/AnnotationPipeline.kt` — `effectAnnotations()` keyword grouping: fix group key to `(timestamp, staticId)` only; route single-target packed grants to `addAbilityPacked()`
- [ ] `game/StateMapper.kt` — type grant diff in GATHER; `detectSbaUnattachedAura()` in POST-TRANSFER; update `effectAnnotations()` call; `bridge.updateAuraAttachments()` at end
- [ ] Tests (see table above)
- [ ] `puzzles/aura-attach-angelic-destiny.pzl`
- [ ] `puzzles/aura-host-dies-angelic-destiny.pzl`
- [ ] `puzzles/aura-host-bounced-angelic-destiny.pzl`
- [ ] `docs/catalog.yaml` — `unattached-aura: wired`, `keyword-granting-auras: wired`, `type-granting-auras: wired`

---

## Unknowns / Risks

**Risk 1 — Forge API for type grant table.**
`card.changedCardTypesTable` is an assumption based on the pattern from `ptBoostTable`.
The actual Forge API may be named differently or return a different structure. Check
`Card.java` before implementing `snapshotTypeGrants()`. Flag for engine-bridge agent if
inaccessible.

**Risk 2 — Static ability ID fingerprinting across attach/detach.**
The (timestamp, staticId) fingerprint from Forge's boost table works because the timestamp
changes whenever a layer effect is added/removed. For aura type grants, the same mechanism
should apply, but if Forge uses a different table structure for type additions (`changedCardTypes`
vs. `ptBoostTable`) the fingerprint shape may differ. Verify against a real Forge trace
before building `diffTypeGrants()`.

**Risk 3 — Affector resolution for keyword/type grants.**
`typeGrantAffectorResolver` needs to map `(hostIid, timestamp, staticId)` → aura iid. This
requires knowing which static ability (by staticId) belongs to which aura card. Forge's
`StaticAbility.getId()` is available on the aura's SA list. If the link is intact (the
aura is still on BF when `effectAnnotations()` runs), this resolves cleanly. If the aura
has already left BF before the diff runs, the SA is gone and the resolver returns 0.
In practice the aura leaves BF in the same diff as the SBA — run `effectAnnotations()`
BEFORE `detectSbaUnattachedAura()` to ensure the aura is still present.

**Risk 4 — `effectAnnotations()` keyword grouping change is a breaking change for Overrun.**
Changing the group key from `(keyword, timestamp, staticId)` to `(timestamp, staticId)`
changes behavior for the Overrun case. Verify: Overrun grants trample to three creatures
via one static ability → one group with three `TrackedKeywordEffect` entries (each with
the same timestamp+staticId but different cardInstanceId). Under the new key, they still
land in one group → the multi-creature path is taken → `addAbilityMulti()`. Correct.

**Risk 5 — SBA timing in mass-removal diffs.**
Day of Judgment kills all creatures simultaneously. Multiple auras can trigger SBA in the
same diff. The `detectSbaUnattachedAura()` loop processes each unattached aura independently.
Verify: all three LayeredEffectDestroyed blocks appear before any ZoneTransfer(SBA_UnattachedAura)
blocks — or interleaved per aura? The spec (session 463) shows single-aura ordering
(destroys, then OIDC, then ZT). For multi-aura: unverified. Process aura-by-aura (all 5
annotations per aura before moving to the next). Add a `TODO` comment in the loop with
this uncertainty.

---

## Leverage Assessment

High. `SBA_UnattachedAura` is a system-level fix that unblocks **every aura** — not just
Angelic Destiny. River's Favor, Stasis Snare, Pacifism, Knight's Pledge all benefit.
The type-grant pAnn and packed-keyword pAnn close the remaining annotation gaps for
keyword-granting auras. Together with the Overrun keyword-grant plan, this lands the full
continuous-effect annotation pipeline (P/T + keywords + type grant) covering the majority
of FDN static-effect cards.

The `EffectTracker.destroyByAuraIid()` / `detectSbaUnattachedAura()` pattern is reusable
for equipment fall-off SBA (if/when that is traced).
