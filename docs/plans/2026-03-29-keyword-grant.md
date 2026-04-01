# Keyword Grant Infrastructure (#31)

## Context

Overrun resolves and creatures get +3/+3 and trample until end of turn. The P/T boost
already works via `EffectTracker`. The keyword grant is invisible: no `AddAbility` pAnn,
no `uniqueAbilities` update on creature gameObjects, no second `LayeredEffectCreated`.
The client shows the stat bump but not the trample badge.

This is horizontal infrastructure. Once the six-layer pipeline exists, every
keyword-granting card (Overrun, Angelic Destiny, equipment, lord effects) gains
correct wire output for free.

**Issue:** #31
**Branch:** `feat/keyword-grant`
**Card specs:** `docs/card-specs/overrun.md`
**Design spec:** `docs/specs/2026-03-29-overrun-keyword-grant-design.md`
**Sessions:** `2026-03-29_16-55-19` (3 creatures + EOT), `2026-03-29_17-04-26` (4 creatures)

## Premise Verification

All claims verified against two sessions and the card spec.

1. **Two `LayeredEffectCreated` transients per cast** — one for P/T (already working),
   one for the keyword layer (currently absent).
2. **One `AddAbility+LayeredEffect` pAnn** with `affectedIds` = all pumped creature iids
   (flat), one `UniqueAbilityId` int per creature, `grpid=14`, `originalAbilityObjectZcid`
   = spell instanceId. Not one pAnn per creature.
3. **Creature gameObjects gain `uniqueAbilities { id: N; grpId: 14 }`** in the same diff.
   `id` matches one of the `UniqueAbilityId` values in the pAnn.
4. **EOT cleanup:** both `LayeredEffectDestroyed` fire in one diff alongside
   `PhaseOrStepModified`. Creature gameObjects lose the `uniqueAbilities` entry.
5. **`grpId 14 = Trample`** confirmed across two sessions and Mossborn Hydra cross-ref.
6. **`addChangedCardKeywords()` in `Card.java`** at line 5062 — this is where to fire the
   new Forge event. It is called from `PumpAllEffect`, `PumpEffect`, and aura/equipment
   static ability application.

## Wire Shape (ground truth)

```
# Resolution diff (gsId 440, session 16-55-19 — 3 creatures iids 389, 425, 432)
LayeredEffectCreated       affectorId=435  affectedIds=[7009]   # P/T effect
PowerToughnessModCreated   affectorId=435  affectedIds=[389]    details.power=3  details.toughness=3
PowerToughnessModCreated   affectorId=435  affectedIds=[425]    details.power=3  details.toughness=3
PowerToughnessModCreated   affectorId=435  affectedIds=[432]    details.power=3  details.toughness=3
LayeredEffectCreated       affectorId=435  affectedIds=[7010]   # keyword effect

pAnn id=1025  affectorId=435  affectedIds=[432, 425, 389]
  types: [AddAbility, LayeredEffect]
  details.originalAbilityObjectZcid=435
  details.UniqueAbilityId=330, 331, 332
  details.grpid=14
  details.effect_id=7010

# Creature gameObject (one of three):
uniqueAbilities { id: 331; grpId: 14 }   # ADDED — client shows trample badge

# EOT cleanup diff (gsId 465):
LayeredEffectDestroyed     affectorId=435  affectedIds=[7009]
LayeredEffectDestroyed     affectorId=435  affectedIds=[7010]
# Creature gameObjects in same diff: uniqueAbilities { grpId: 14 } entry removed
```

## Architecture

Six layers, each independently testable, each adding a narrow vertical slice:

```
Layer 0  KeywordGrpIds.kt          keyword name → Arena grpId table (new file)
Layer 1  Forge Card.java           fire GameEventExtrinsicKeywordAdded per card
         GameEventExtrinsicKeywordAdded.java  new event class in forge fork
Layer 2  GameEvent.kt              add KeywordGranted sealed variant
         GameEventCollector.kt     subscribe → emit KeywordGranted
Layer 3  EffectTracker.kt          keyword tracking parallel to P/T boosts
         (KeywordEntry, diffKeywords)
Layer 4  AnnotationBuilder.kt      multi-creature addAbility() overload
         AnnotationPipeline.kt     effectAnnotations() keyword branch
Layer 5  CardProtoBuilder.kt       extrinsic uniqueAbilities on gameObject
```

EOT cleanup is free: `diffKeywords()` detects expiry the same way `diffBoosts()` does —
no extra work.

## Deliverables

- [ ] `game/KeywordGrpIds.kt` — keyword → grpId registry
- [ ] `forge/.../GameEventExtrinsicKeywordAdded.java` — new Forge event class
- [ ] `forge/.../IGameEventVisitor.java` — add `visit` overload
- [ ] `forge/.../Card.java` — fire event from `addChangedCardKeywords()`
- [ ] `game/GameEvent.kt` — `KeywordGranted` variant
- [ ] `game/GameEventCollector.kt` — subscribe to new Forge event
- [ ] `game/EffectTracker.kt` — `KeywordEntry`, `TrackedKeywordEffect`, `diffKeywords()`
- [ ] `game/AnnotationBuilder.kt` — `addAbilityMulti()` overload
- [ ] `game/AnnotationPipeline.kt` — `effectAnnotations()` keyword branch
- [ ] `game/CardProtoBuilder.kt` — extrinsic `uniqueAbilities` on gameObjects
- [ ] `game/KeywordQualifications.kt` — fill in known grpIds (trample=14, etc.)
- [ ] Puzzle: `puzzles/keyword-grant-overrun.pzl`
- [ ] Tests (see below)
- [ ] `docs/catalog.yaml` — update `keyword-granting-spells` to `wired`

## Tests

| Test | Tier | What it validates |
|------|------|------------------|
| `KeywordGrpIdsTest` | Unit (0.01s) | Registry lookup: trample→14, unknown→null |
| `AnnotationBuilderKeywordGrantTest` | Unit (0.01s) | `addAbilityMulti()` proto shape: types=[AddAbility,LayeredEffect], affectedIds flat list, UniqueAbilityId repeated field, grpid, effect_id, originalAbilityObjectZcid |
| `EffectTrackerKeywordTest` | Unit (0.01s) | `diffKeywords()`: created on first call, empty on repeat, destroyed on removal |
| `CardProtoBuilderExtrinsicTest` | Unit (0.01s) | `buildObjectInfo()` appends extrinsic grpIds in `uniqueAbilities` beyond static entries |
| `KeywordGrantAnnotationTest` | Conformance (0.01s) | `effectAnnotations()` with keyword diff: two `LayeredEffectCreated`, one `AddAbility+LayeredEffect` pAnn, correct affectedIds count |
| `OverrunPuzzleTest` | Integration (0.09s) | Puzzle: cast Overrun → `AddAbility` pAnn present + 3 creature iids + `uniqueAbilities{grpId:14}` on each + `LayeredEffectCreated` count=2; advance to EOT → `LayeredEffectDestroyed` count=2 + `uniqueAbilities` removed |
| `OverrunMatchFlowTest` | Integration (0.7s) | MatchFlowHarness: cast Overrun → assert pAnn, creature gameObjects, EOT cleanup |

## Implementation

### Phase A: Data Registry (Layer 0)

**New file: `matchdoor/src/main/kotlin/leyline/game/KeywordGrpIds.kt`**

```kotlin
package leyline.game

/**
 * Maps keyword names (as Forge reports them) to Arena grpId values.
 *
 * Separate from [KeywordQualifications] (which is Qualification annotation-specific).
 * Used for [AnnotationBuilder.addAbilityMulti] and [CardProtoBuilder] extrinsic ability IDs.
 *
 * grpIds confirmed from real server recordings. Add entries as new keywords are traced.
 */
object KeywordGrpIds {
    private val table = mapOf(
        "Flying"       to 8,
        "First Strike" to 6,
        "Trample"      to 14,
        "Vigilance"    to 15,
        "Lifelink"     to 12,
        "Reach"        to 13,
        "Menace"       to 142,
        // Haste=9, Deathtouch=unknown-yet, Double Strike=unknown-yet
        // Add entries as sessions are traced.
    )

    /** Returns the Arena keyword grpId for a keyword name, or null if unregistered. */
    fun forKeyword(keyword: String): Int? = table[keyword]
}
```

Also update `KeywordQualifications.kt` — fill in now-confirmed grpIds:

```kotlin
private val table: Map<String, QualInfo> = mapOf(
    "Menace" to QualInfo(grpId = 142, qualificationType = 40),
    // Trample grpId confirmed: 14 (sessions 2026-03-29_16-55-19, _17-04-26)
    // qualificationType for trample not yet observed — add when traced
)
```

**Test: `KeywordGrpIdsTest`**

```kotlin
class KeywordGrpIdsTest : FunSpec({
    test("trample resolves to 14") { KeywordGrpIds.forKeyword("Trample") shouldBe 14 }
    test("flying resolves to 8") { KeywordGrpIds.forKeyword("Flying") shouldBe 8 }
    test("unknown keyword returns null") { KeywordGrpIds.forKeyword("Hexproof") shouldBe null }
})
```

### Phase B: Forge Event (Layer 1)

**New file: `forge/forge-game/src/main/java/forge/game/event/GameEventExtrinsicKeywordAdded.java`**

Model on `GameEventCardSurveiled.java`:

```java
package forge.game.event;

import forge.game.card.Card;

/**
 * Fired for each keyword added to a card via an extrinsic (layer 6) static ability.
 * Complements GameEventCardStatsChanged with per-keyword, per-card granularity so
 * the bridge can emit AddAbility annotations without state-diffing.
 *
 * @param card     the card receiving the keyword
 * @param keyword  the keyword name as a String (e.g. "Trample")
 * @param timestamp the Forge timestamp of the KeywordsChange (for effect fingerprinting)
 * @param staticId  the StaticAbility ID (st.getId()), 0 if none
 */
public record GameEventExtrinsicKeywordAdded(Card card, String keyword, long timestamp, long staticId)
        implements GameEvent {

    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String toString() {
        return card.getName() + " gained extrinsic keyword " + keyword;
    }
}
```

**Update `IGameEventVisitor.java`** — add to the interface and the `Base` inner class:

```java
// in the interface:
T visit(GameEventExtrinsicKeywordAdded event);

// in Base:
public T visit(GameEventExtrinsicKeywordAdded event) { return null; }
```

**Update `Card.java` — fire from `addChangedCardKeywords()` (line 5076–5081 inner loop):**

The right place is inside the `if (canHave)` block, after `kws.add(...)`, so we only fire
for keywords that actually make it onto the card (CantHave filter already applied):

```java
// existing line 5076-5080:
if (canHave) {
    kws.add(getKeywordForStaticAbility(kw, st, idx));
    // NEW: notify bridge that this keyword was granted extrinsically
    game.fireEvent(new GameEventExtrinsicKeywordAdded(this, kw, timestamp, st == null ? 0L : st.getId()));
}
```

`game` is accessible as `this.game` (Card already holds a reference). This fires on the
engine thread, synchronously, same as all other event fires — no threading concern.

**Risk note:** `addChangedCardKeywords()` is called for both adding and removing keywords
(the `removeKeywords` param handles removals). We only fire the event inside the keywords
loop (additions). Removals are handled by `diffKeywords()` via fingerprint disappearance —
no event needed for removal.

### Phase C: GameEvent + Collector (Layer 2)

**`game/GameEvent.kt`** — add after `ControllerChanged`:

```kotlin
/** An extrinsic keyword was added to a card via a layer-6 static ability.
 *  Wired from [GameEventExtrinsicKeywordAdded] in the Forge fork.
 *  [timestamp] + [staticId] form the effect fingerprint — same as EffectTracker.EffectFingerprint. */
data class KeywordGranted(
    val cardId: ForgeCardId,
    val keyword: String,
    val timestamp: Long,
    val staticId: Long,
) : GameEvent
```

**`game/GameEventCollector.kt`** — add a new `visit` override after the existing Group B
handlers (e.g. after `visit(ev: GameEventCardStatsChanged)`):

```kotlin
override fun visit(ev: GameEventExtrinsicKeywordAdded) {
    val card = ev.card()
    queue.add(
        GameEvent.KeywordGranted(
            cardId = ForgeCardId(card.id),
            keyword = ev.keyword(),
            timestamp = ev.timestamp(),
            staticId = ev.staticId(),
        )
    )
    log.debug("event: KeywordGranted card={} keyword={} ts={}", card.name, ev.keyword(), ev.timestamp())
}
```

No seat lookup needed — keyword grants have no seat-specific semantics.

### Phase D: EffectTracker Keyword Support (Layer 3)

Extend `EffectTracker.kt` with parallel keyword tracking. Keep the boost tracking
unchanged — keyword effects share only the `nextEffectId()` counter.

```kotlin
// New data classes inside EffectTracker:

data class KeywordEntry(val timestamp: Long, val staticId: Long, val keyword: String)

data class KeywordFingerprint(val cardInstanceId: Int, val timestamp: Long, val staticId: Long)

data class TrackedKeywordEffect(
    val syntheticId: Int,
    val fingerprint: KeywordFingerprint,
    val keyword: String,
) {
    val cardInstanceId: Int get() = fingerprint.cardInstanceId
}

data class KeywordDiffResult(
    val created: List<TrackedKeywordEffect>,
    val destroyed: List<TrackedKeywordEffect>,
)
```

New method `diffKeywords()` — exact parallel to `diffBoosts()`:

```kotlin
/**
 * Diff current keyword grants against previously tracked state.
 * Returns created/destroyed keyword effects for LayeredEffectCreated /
 * LayeredEffectDestroyed + AddAbility pAnn emission.
 *
 * [currentKeywords]: cardInstanceId → list of active KeywordEntry values.
 * Caller builds this from drained [GameEvent.KeywordGranted] events.
 */
fun diffKeywords(currentKeywords: Map<Int, List<KeywordEntry>>): KeywordDiffResult {
    val currentFps = mutableMapOf<KeywordFingerprint, KeywordEntry>()
    for ((cardIid, entries) in currentKeywords) {
        for (entry in entries) {
            currentFps[KeywordFingerprint(cardIid, entry.timestamp, entry.staticId)] = entry
        }
    }

    val destroyed = mutableListOf<TrackedKeywordEffect>()
    val toRemove = mutableListOf<KeywordFingerprint>()
    for ((fp, tracked) in activeKeywordEffects) {
        if (fp !in currentFps) {
            destroyed.add(tracked)
            toRemove.add(fp)
        }
    }
    for (fp in toRemove) activeKeywordEffects.remove(fp)

    val created = mutableListOf<TrackedKeywordEffect>()
    for ((fp, entry) in currentFps) {
        if (fp !in activeKeywordEffects) {
            val tracked = TrackedKeywordEffect(nextEffectId(), fp, entry.keyword)
            activeKeywordEffects[fp] = tracked
            created.add(tracked)
        }
    }

    return KeywordDiffResult(created, destroyed)
}
```

Add the backing field at the top of the class (after `activeEffects`):

```kotlin
private val activeKeywordEffects = mutableMapOf<KeywordFingerprint, TrackedKeywordEffect>()
```

Update `resetAll()`:

```kotlin
fun resetAll() {
    nextId = INITIAL_EFFECT_ID
    activeEffects.clear()
    activeKeywordEffects.clear()
    initEmitted = false
}
```

**Test: `EffectTrackerKeywordTest`**

```kotlin
class EffectTrackerKeywordTest : FunSpec({
    lateinit var tracker: EffectTracker
    beforeEach { tracker = EffectTracker() }

    test("diffKeywords creates entry on first call") {
        val input = mapOf(100 to listOf(EffectTracker.KeywordEntry(1L, 0L, "Trample")))
        val diff = tracker.diffKeywords(input)
        diff.created.size shouldBe 1
        diff.created[0].keyword shouldBe "Trample"
        diff.created[0].cardInstanceId shouldBe 100
        diff.destroyed shouldBe emptyList()
    }

    test("diffKeywords returns empty when keyword persists") {
        val input = mapOf(100 to listOf(EffectTracker.KeywordEntry(1L, 0L, "Trample")))
        tracker.diffKeywords(input)
        val diff2 = tracker.diffKeywords(input)
        diff2.created shouldBe emptyList()
        diff2.destroyed shouldBe emptyList()
    }

    test("diffKeywords destroys entry when keyword removed") {
        val input = mapOf(100 to listOf(EffectTracker.KeywordEntry(1L, 0L, "Trample")))
        tracker.diffKeywords(input)
        val diff2 = tracker.diffKeywords(emptyMap())
        diff2.destroyed.size shouldBe 1
        diff2.created shouldBe emptyList()
    }

    test("keyword and boost effects share the same ID counter") {
        // Boost allocated first, keyword should get next ID
        tracker.nextEffectId() // simulate a boost taking ID N
        val input = mapOf(100 to listOf(EffectTracker.KeywordEntry(1L, 0L, "Trample")))
        val diff = tracker.diffKeywords(input)
        diff.created[0].syntheticId shouldBeGreaterThan EffectTracker.INITIAL_EFFECT_ID
    }
})
```

### Phase E: AnnotationBuilder + Pipeline (Layer 4)

#### AnnotationBuilder.kt — `addAbilityMulti()` overload

The existing `addAbility()` takes a single `instanceId`. The wire shape for keyword grants
uses a flat `affectedIds` list (all creatures) with a repeated `UniqueAbilityId` field.
Add a new overload rather than changing the existing one — the single-target form remains
valid for Haste grants etc.

```kotlin
/**
 * Keyword grant via layered effect — multi-creature form.
 * Arena type 9 (AddAbility_af5a) + 51 (LayeredEffect) as co-types.
 *
 * Wire shape: one pAnn covers all affected creatures in [affectedIds],
 * with one [uniqueAbilityIds] entry per creature (same count, same order).
 * Matches the Overrun multi-creature keyword grant observed in sessions
 * 2026-03-29_16-55-19 and 2026-03-29_17-04-26.
 *
 * [affectorId] = spell/ability instanceId that created the effect.
 * [grpId] = keyword grpId (14 for Trample, 8 for Flying, etc.)
 * [originalAbilityObjectZcid] = spell instanceId on stack at resolution time.
 */
fun addAbilityMulti(
    affectedIds: List<Int>,
    grpId: Int,
    effectId: Int,
    uniqueAbilityIds: List<Int>,
    originalAbilityObjectZcid: Int,
    affectorId: Int,
): AnnotationInfo {
    val builder = AnnotationInfo.newBuilder()
        .addType(AnnotationType.AddAbility_af5a)
        .addType(AnnotationType.LayeredEffect)
        .setAffectorId(affectorId)
        .addDetails(uint32Detail(DetailKeys.GRPID, grpId))
        .addDetails(int32Detail(DetailKeys.EFFECT_ID, effectId))
        .addDetails(int32Detail(DetailKeys.ORIGINAL_ABILITY_OBJECT_ZCID, originalAbilityObjectZcid))
    affectedIds.forEach { builder.addAffectedIds(it) }
    uniqueAbilityIds.forEach { builder.addDetails(int32Detail(DetailKeys.UNIQUE_ABILITY_ID, it)) }
    return builder.build()
}
```

**Test: `AnnotationBuilderKeywordGrantTest`**

```kotlin
class AnnotationBuilderKeywordGrantTest : FunSpec({
    test("addAbilityMulti shape matches wire spec") {
        val ann = AnnotationBuilder.addAbilityMulti(
            affectedIds = listOf(389, 425, 432),
            grpId = 14,
            effectId = 7010,
            uniqueAbilityIds = listOf(330, 331, 332),
            originalAbilityObjectZcid = 435,
            affectorId = 435,
        )
        ann.typeList shouldContain AnnotationType.AddAbility_af5a
        ann.typeList shouldContain AnnotationType.LayeredEffect
        ann.affectorId shouldBe 435
        ann.affectedIdsList shouldContainExactly listOf(389, 425, 432)
        ann.detailsList.find { it.key == "grpid" }?.valueUInt32 shouldBe 14
        ann.detailsList.find { it.key == "effect_id" }?.valueInt32 shouldBe 7010
        ann.detailsList.find { it.key == "originalAbilityObjectZcid" }?.valueInt32 shouldBe 435
        // Three UniqueAbilityId detail entries (one per creature)
        ann.detailsList.filter { it.key == "UniqueAbilityId" }.map { it.valueInt32 } shouldContainExactly
            listOf(330, 331, 332)
    }
})
```

#### AnnotationPipeline.kt — `effectAnnotations()` keyword branch

The `effectAnnotations()` function currently takes only `EffectTracker.DiffResult` (P/T).
Extend it to also accept `EffectTracker.KeywordDiffResult` and an `idResolver` for
resolving creature instance IDs.

Extend the signature (pure function — no bridge access):

```kotlin
fun effectAnnotations(
    diff: EffectTracker.DiffResult,
    sourceAbilityResolver: ((InstanceId, Long) -> Int?)? = null,
    keywordDiff: EffectTracker.KeywordDiffResult = EffectTracker.KeywordDiffResult(emptyList(), emptyList()),
    idResolver: ((ForgeCardId) -> InstanceId)? = null,
    // For keyword grants: group by (keyword, timestamp, staticId) to find the spell affectorId.
    // Maps (keyword, timestamp, staticId) → spell instanceId (the affectorId for the pAnn).
    keywordAffectorResolver: ((String, Long, Long) -> Int)? = null,
    // For UniqueAbilityId allocation — must be monotonic across the GSM.
    uniqueAbilityIdAllocator: (() -> Int)? = null,
): Pair<List<AnnotationInfo>, List<AnnotationInfo>>
```

Add the keyword section after the existing P/T block, inside `effectAnnotations()`:

```kotlin
// ── Keyword grants ──────────────────────────────────────────────────────
// Group created keyword effects by (keyword, timestamp, staticId) so that
// all creatures affected by the same static ability get one shared pAnn.
if (keywordDiff.created.isNotEmpty() && idResolver != null && uniqueAbilityIdAllocator != null) {
    data class KeywordGroup(val keyword: String, val timestamp: Long, val staticId: Long, val effectId: Int)

    // Group effects by (keyword, timestamp, staticId) — one group = one pAnn
    val groups = keywordDiff.created
        .groupBy { Triple(it.keyword, it.fingerprint.timestamp, it.fingerprint.staticId) }

    for ((key, effects) in groups) {
        val (keyword, timestamp, staticId) = key
        val grpId = KeywordGrpIds.forKeyword(keyword) ?: continue  // skip unknown keywords
        // Pick an effectId — all share the same effect (it's one LayeredEffect for the whole group)
        val effectId = effects.first().syntheticId

        // Transient: LayeredEffectCreated for this keyword's LayeredEffect
        // affectorId: spell instanceId that created this effect
        val affectorId = keywordAffectorResolver?.invoke(keyword, timestamp, staticId) ?: 0
        transient.add(AnnotationBuilder.layeredEffectCreated(effectId, if (affectorId != 0) affectorId else null))

        // Collect creature instanceIds and allocate UniqueAbilityIds
        val creatureIids = effects.map { it.cardInstanceId }
        val uniqueAbilityIds = creatureIids.map { uniqueAbilityIdAllocator() }

        // Persistent: AddAbility+LayeredEffect pAnn (one per keyword group, all creatures)
        persistent.add(
            AnnotationBuilder.addAbilityMulti(
                affectedIds = creatureIids,
                grpId = grpId,
                effectId = effectId,
                uniqueAbilityIds = uniqueAbilityIds,
                originalAbilityObjectZcid = affectorId,
                affectorId = affectorId,
            )
        )

        log.debug(
            "effectAnnotations: keyword grant {} grpId={} effectId={} creatures={}",
            keyword, grpId, effectId, creatureIids.size,
        )
    }
}

// Keyword destroys
for (effect in keywordDiff.destroyed) {
    transient.add(AnnotationBuilder.layeredEffectDestroyed(effect.syntheticId))
}
```

**Test: `KeywordGrantAnnotationTest`**

```kotlin
class KeywordGrantAnnotationTest : FunSpec({
    test("effectAnnotations emits LayeredEffectCreated + AddAbility pAnn for keyword grant") {
        val boostDiff = EffectTracker.DiffResult(emptyList(), emptyList())
        val kwDiff = EffectTracker.KeywordDiffResult(
            created = listOf(
                EffectTracker.TrackedKeywordEffect(7010, EffectTracker.KeywordFingerprint(389, 1L, 5L), "Trample"),
                EffectTracker.TrackedKeywordEffect(7010, EffectTracker.KeywordFingerprint(425, 1L, 5L), "Trample"),
                EffectTracker.TrackedKeywordEffect(7010, EffectTracker.KeywordFingerprint(432, 1L, 5L), "Trample"),
            ),
            destroyed = emptyList(),
        )
        var uniqueId = 330
        val (transient, persistent) = AnnotationPipeline.effectAnnotations(
            diff = boostDiff,
            keywordDiff = kwDiff,
            idResolver = { fid -> InstanceId(fid.value) },
            keywordAffectorResolver = { _, _, _ -> 435 },
            uniqueAbilityIdAllocator = { uniqueId++ },
        )

        // One LayeredEffectCreated for the keyword effect
        transient.filter { it.typeList.contains(AnnotationType.LayeredEffectCreated) } shouldHaveSize 1

        // One AddAbility+LayeredEffect pAnn
        val pAnn = persistent.first { it.typeList.contains(AnnotationType.AddAbility_af5a) }
        pAnn.affectedIdsList shouldHaveSize 3
        pAnn.detailsList.filter { it.key == "UniqueAbilityId" } shouldHaveSize 3
        pAnn.detailsList.find { it.key == "grpid" }?.valueUInt32 shouldBe 14
    }

    test("effectAnnotations emits LayeredEffectDestroyed for expired keyword") {
        val kwDiff = EffectTracker.KeywordDiffResult(
            created = emptyList(),
            destroyed = listOf(
                EffectTracker.TrackedKeywordEffect(7010, EffectTracker.KeywordFingerprint(389, 1L, 5L), "Trample"),
            ),
        )
        val (transient, _) = AnnotationPipeline.effectAnnotations(
            diff = EffectTracker.DiffResult(emptyList(), emptyList()),
            keywordDiff = kwDiff,
        )
        transient.filter { it.typeList.contains(AnnotationType.LayeredEffectDestroyed) } shouldHaveSize 1
    }

    test("effectAnnotations skips unknown keyword grpIds") {
        val kwDiff = EffectTracker.KeywordDiffResult(
            created = listOf(
                EffectTracker.TrackedKeywordEffect(7010, EffectTracker.KeywordFingerprint(389, 1L, 5L), "Hexproof"),
            ),
            destroyed = emptyList(),
        )
        val (_, persistent) = AnnotationPipeline.effectAnnotations(
            diff = EffectTracker.DiffResult(emptyList(), emptyList()),
            keywordDiff = kwDiff,
            uniqueAbilityIdAllocator = { 1 },
        )
        persistent shouldBe emptyList()
    }
})
```

### Phase F: CardProtoBuilder Extrinsic uniqueAbilities (Layer 5)

`CardProtoBuilder.buildObjectInfo()` currently reads only static card DB abilities. When
a creature has extrinsic keywords (active `changedCardKeywords` entries), those must also
appear in `uniqueAbilities` so the client shows the badge.

Forge's `card.getKeywords()` returns all currently active keywords (static + extrinsic).
`card.getIntrinsicKeywords()` returns only the intrinsic (printed) ones. The difference
is the extrinsic set.

The caller already holds the `forge.game.card.Card` object when building battlefield
objects. We need to pass it to `CardProtoBuilder` alongside the `grpId`.

**Decision:** Add an optional `extrinsicKeywordGrpIds: List<Int>` parameter to
`buildObjectInfo()` rather than taking a full `Card` reference — keeps the builder decoupled
from Forge's model.

Extend `buildObjectInfo(grpId, template)` (the diff-path overload):

```kotlin
fun buildObjectInfo(
    grpId: Int,
    template: GameObjectInfo,
    extrinsicKeywordGrpIds: List<Int> = emptyList(),
): GameObjectInfo {
    // ... existing logic unchanged ...

    builder.clearUniqueAbilities()
    var abilitySeqId = template.uniqueAbilitiesList.firstOrNull()?.id ?: 50
    val abilities = card.abilityIds.ifEmpty {
        basicLandAbility(card.subtypes)?.let { listOf(it to 0) } ?: emptyList()
    }
    abilities.forEach { (abilityGrpId, _) ->
        builder.addUniqueAbilities(
            UniqueAbilityInfo.newBuilder().setId(abilitySeqId++).setGrpId(abilityGrpId),
        )
    }
    // NEW: append extrinsic keywords with IDs starting at EXTRINSIC_ABILITY_ID_BASE
    // Coordinate the starting ID with UniqueAbilityId values in the AddAbility pAnn.
    // Use a high base (1000+) to avoid colliding with static card abilities (which are
    // typically in the 50–500 range based on observed data).
    for (kwGrpId in extrinsicKeywordGrpIds) {
        builder.addUniqueAbilities(
            UniqueAbilityInfo.newBuilder()
                .setId(abilitySeqId++)
                .setGrpId(kwGrpId),
        )
    }

    return builder.build()
}
```

Also extend the non-template overload `buildObjectInfo(grpId)` the same way.

**Caller integration (ObjectMapper / ZoneMapper):** The call site that builds creature
game objects must supply `extrinsicKeywordGrpIds`. This is in `ObjectMapper.buildGameObject()`
(or equivalent). The caller has access to the Forge `Card` object there; compute the
extrinsic set via:

```kotlin
fun extrinsicKeywordGrpIds(card: forge.game.card.Card): List<Int> {
    val intrinsic = card.intrinsicKeywords.map { it.keyword }.toSet()
    return card.keywords
        .filter { it.keyword !in intrinsic }
        .mapNotNull { KeywordGrpIds.forKeyword(it.keyword) }
        .distinct()
}
```

This is a pure computation; add it as a free function or companion helper.

**Test: `CardProtoBuilderExtrinsicTest`**

```kotlin
class CardProtoBuilderExtrinsicTest : FunSpec({
    val cards = // minimal CardRepository with a test card, grpId=99, one ability grpId=1001
    val builder = CardProtoBuilder(cards)

    test("buildObjectInfo appends extrinsic uniqueAbilities after static") {
        val template = GameObjectInfo.getDefaultInstance()
        val obj = builder.buildObjectInfo(99, template, extrinsicKeywordGrpIds = listOf(14))
        val abilities = obj.uniqueAbilitiesList
        abilities.last().grpId shouldBe 14
    }

    test("buildObjectInfo with empty extrinsic returns only static abilities") {
        val template = GameObjectInfo.getDefaultInstance()
        val obj = builder.buildObjectInfo(99, template, extrinsicKeywordGrpIds = emptyList())
        obj.uniqueAbilitiesList.none { it.grpId == 14 } shouldBe true
    }
})
```

### Phase G: StateMapper Integration

**`StateMapper.kt` — GATHER phase (around line 76–77):**

After `diffBoosts()`, add keyword diffing:

```kotlin
// Existing:
val boostSnapshot = bridge.snapshotBoosts()
val effectDiff = bridge.effects.diffBoosts(boostSnapshot)

// New: build keyword snapshot from drained KeywordGranted events
val keywordSnapshot = buildKeywordSnapshot(events)
val keywordDiff = bridge.effects.diffKeywords(keywordSnapshot)
```

Add `buildKeywordSnapshot()` as a private helper:

```kotlin
/**
 * Build the current-keyword snapshot from drained events.
 *
 * Groups [GameEvent.KeywordGranted] events by cardInstanceId. Called once per GSM
 * in the GATHER phase, before the keyword diff runs.
 *
 * Note: We accumulate ALL keyword grants from events — not just new ones —
 * because EffectTracker.diffKeywords() handles the created/destroyed delta.
 * If no KeywordGranted events fired this GSM, the map is empty and
 * diffKeywords() detects all previously tracked grants as expired.
 *
 * TODO: This fires too eagerly. KeywordGranted events only accumulate *while
 * the effect is active*. We need to store the full active-grant snapshot in
 * GameBridge (similar to boostSnapshot) rather than rebuilding it from the
 * event drain, which only has *this-GSM* events. See Risk #1 below.
 */
private fun buildKeywordSnapshot(events: List<GameEvent>): Map<Int, List<EffectTracker.KeywordEntry>> {
    val map = mutableMapOf<Int, MutableList<EffectTracker.KeywordEntry>>()
    for (ev in events.filterIsInstance<GameEvent.KeywordGranted>()) {
        val iid = bridge.getOrAllocInstanceId(ev.cardId).value
        map.getOrPut(iid) { mutableListOf() }
            .add(EffectTracker.KeywordEntry(ev.timestamp, ev.staticId, ev.keyword))
    }
    return map
}
```

**Risk #1 — keyword snapshot persistence:** Unlike P/T boosts (which Forge snapshots on
demand via `snapshotBoosts()`), keyword events only fire when the keyword is *first granted*,
not on every GSM tick. The snapshot must be stored persistently in `GameBridge` across GSMs.

The fix: add `activeKeywordGrants: MutableMap<Int, MutableList<EffectTracker.KeywordEntry>>`
to `GameBridge`. `buildKeywordSnapshot()` in StateMapper *merges* new events into this map
(adds on `KeywordGranted` events) and Forge's `GameEventCardChangeZone` (BF departure) purges
entries for cards that left the battlefield. Then `snapshotKeywords()` on GameBridge returns
the current state — direct parallel to `snapshotBoosts()`.

This is slightly more involved than the initial sketch above; the actual implementation
should model `activeKeywordGrants` on `GameBridge.activeBoosts` exactly.

**Pass keyword diff into `effectAnnotations()`:**

```kotlin
val (effectTransient, effectPersistent) = AnnotationPipeline.effectAnnotations(
    diff = effectDiff,
    sourceAbilityResolver = sourceAbilityResolver,
    keywordDiff = keywordDiff,
    idResolver = { fid -> bridge.getOrAllocInstanceId(fid) },
    keywordAffectorResolver = { keyword, timestamp, staticId ->
        // Walk events to find the SpellResolved that matches this timestamp.
        // Fall back to the most recent SpellResolved if no direct match.
        events.filterIsInstance<GameEvent.SpellResolved>()
            .lastOrNull()
            ?.let { bridge.getOrAllocInstanceId(it.cardId).value }
            ?: 0
    },
    uniqueAbilityIdAllocator = { bridge.effects.nextEffectId() },
)
```

**Pass extrinsic keywords into CardProtoBuilder:**

In `ObjectMapper.buildGameObject()` (wherever creature `GameObjectInfo` is assembled for
BF objects), compute and pass the extrinsic set:

```kotlin
val extrinsicGrpIds = extrinsicKeywordGrpIds(card)  // helper from Phase F
val obj = cards.buildObjectInfo(grpId, template, extrinsicKeywordGrpIds = extrinsicGrpIds)
```

### Phase H: Puzzle

**New file: `puzzles/keyword-grant-overrun.pzl`**

```yaml
name: keyword-grant-overrun
description: Overrun grants trample to all creatures; both LayeredEffects created and destroyed at EOT.
seat: 1

board:
  p1_life: 20
  p2_life: 20
  p1_hand:
    - { grpId: 93943 }   # Overrun
  p1_battlefield:
    - { grpId: 93965 }   # Wall (Artifact Creature — Wall, grpId from session)
    - { grpId: 94078 }   # any 2/2 green creature
    - { grpId: 94078 }   # another 2/2 green creature
  p1_mana: "2GGG"        # exact cost for Overrun

steps:
  - cast: { grpId: 93943 }
  - pass_priority
  - assert:
      annotations_contain:
        - type: LayeredEffectCreated
          count: 2
      annotations_contain:
        - type: AddAbility
          details:
            grpid: 14
            UniqueAbilityId_count: 3
      gameObjects_condition:
        zone: Battlefield
        has_unique_ability:
          grpId: 14
          count: 3
  - advance_to_step: end_step
  - assert:
      annotations_contain:
        - type: LayeredEffectDestroyed
          count: 2
      gameObjects_condition:
        zone: Battlefield
        lacks_unique_ability:
          grpId: 14
```

### Phase I: catalog.yaml Update

Update `docs/catalog.yaml` — find the `keyword-granting-spells` entry (issue #31) and
change status from `partial` to `wired` once all tests pass:

```yaml
keyword-granting-spells:
  status: wired
  notes: >
    AddAbility+LayeredEffect pAnn (multi-creature flat affectedIds), extrinsic
    uniqueAbilities on creature gameObjects, EOT LayeredEffectDestroyed.
    Confirmed: Overrun (grpId 93943) sessions 2026-03-29_16-55-19 + _17-04-26.
    keyword grpIds: Trample=14, Flying=8, First Strike=6, Vigilance=15, Lifelink=12,
    Reach=13, Menace=142.
  issues: [31]
```

## Risks and Mitigations

### Risk 1: Keyword snapshot persistence across GSMs (HIGH)

`KeywordGranted` events only fire when a keyword is first added. On subsequent GSMs
(before the effect expires), no `KeywordGranted` fires — so `buildKeywordSnapshot()` built
from the drained event queue returns an empty map and `diffKeywords()` would falsely destroy
all tracked keyword effects on the second tick.

**Mitigation:** Add `activeKeywordGrants: ConcurrentHashMap<KeywordFingerprint, KeywordEntry>`
to `GameBridge` (parallel to `activeBoosts` / `snapshotBoosts()`). `StateMapper` merges
each `KeywordGranted` event into this map in the GATHER phase. `snapshotKeywords()` on
`GameBridge` returns a snapshot of the map (same pattern as `snapshotBoosts()`). The map
is purged for cards that leave the battlefield via the existing `CardDestroyed` /
`CardExiled` / `ZoneChanged` events already in the drain.

This is the biggest structural piece — it is NOT optional. Without it, the keyword effect
flickers destroyed on the second GSM tick.

### Risk 2: `addChangedCardKeywords` call sites (MEDIUM)

Forge calls `addChangedCardKeywords()` from many places beyond `PumpAllEffect`:
aura/equipment static ability application, `Effect.addAbilityFactoryAbility()`, etc.
The event fires from all of them automatically. This is correct behavior — we want
keyword grant events from every source.

But it also fires during game initialization (e.g., cards with printed static abilities
setting up their own keywords). These will produce `KeywordGranted` events at game start.
The snapshot approach in Risk 1 handles this correctly: the initial keywords get tracked
and persist, then on the first `diffKeywords()` they appear as created. This matches the
real server's behavior (which emits the init `LayeredEffectCreated` batch at game start
for persistent effects).

Watch for double-emission: `addChangedCardKeywords()` calls `updateKeywords()` → possible
re-fire. Add a guard if needed (e.g., track `(card.id, keyword, timestamp)` in a seen-set
within the same engine tick).

### Risk 3: `UniqueAbilityId` coordination (LOW)

The `uniqueAbilityIds` values in the `AddAbility` pAnn must match the `id` values in
the `uniqueAbilities` entries on the creature `GameObjectInfo`. Since `CardProtoBuilder`
computes sequential IDs starting from the static ability count, and the annotation pipeline
allocates IDs independently, they could diverge.

**Mitigation:** Use a shared per-GSM allocator. The `uniqueAbilityIdAllocator` lambda
passed to `effectAnnotations()` draws from the same counter as the sequence IDs assigned
in `CardProtoBuilder`. Pass the same `AtomicInteger` or next-ID lambda to both. The ID
base for extrinsic abilities must be higher than any static ability ID on the card — use
`max(existing_uniqueAbilities.maxId) + 1` as the starting point in `CardProtoBuilder`,
and pass that starting value to the annotation pipeline.

### Risk 4: Forge event ordering vs P/T diff (LOW)

`GameEventExtrinsicKeywordAdded` fires inside `addChangedCardKeywords()`. `GameEventCardStatsChanged`
fires after. The drain collects both; the annotation pipeline processes them in order.
The wire shows `LayeredEffectCreated` (P/T) before `LayeredEffectCreated` (keyword).

Verify with a logging test: drain events after Overrun resolves; confirm
`KeywordGranted` events follow `PowerToughnessChanged` events in the queue.
If ordering is reversed, swap keyword `LayeredEffectCreated` to appear after P/T in the
`effectAnnotations()` output (annotation ordering is local to the function).

## Verification

1. `just test-one KeywordGrpIdsTest` — registry lookup
2. `just test-one AnnotationBuilderKeywordGrantTest` — proto shape
3. `just test-one EffectTrackerKeywordTest` — diff lifecycle
4. `just test-one KeywordGrantAnnotationTest` — pipeline output
5. `just test-one CardProtoBuilderExtrinsicTest` — gameObject uniqueAbilities
6. `just puzzle-check puzzles/keyword-grant-overrun.pzl` — end-to-end puzzle
7. `just test-one OverrunMatchFlowTest` — production path
8. `./gradlew :matchdoor:testGate` — no regressions
9. Arena playtest: cast Overrun against bot — verify trample badge appears on all creatures,
   disappears after end step. Screenshot both states.
10. Arena playtest: Angelic Destiny on a creature — flying + first strike badges both appear.

## Leverage

| Card family | Unblocked |
|-------------|-----------|
| Overrun and mass keyword grants | Full wire (issue #31) |
| Angelic Destiny | Flying + First Strike from aura static (issue #31) |
| Sire of Seven Deaths | `uniqueAbilities` display for all 7 static keywords |
| Equipment keyword grants | Haste from Swiftfoot Boots, etc. |
| Lord effects (anthem + keyword) | Any lord that says "get +1/+1 and [keyword]" |

The same six-layer pipeline handles all of them without any per-card work once
`KeywordGrpIds` has the needed entries.
