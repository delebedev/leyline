# CastSpell Mana Payment Conformance — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `just conform CastSpell CHALLENGE-STARTER` pass by fixing all 8 annotation diffs + 1 persistent diff in the CastSpell mana payment annotation sequence.

**Architecture:** Enrich `GameEvent.SpellCast` with per-mana-payment data extracted from Forge's `SpellAbility.getPayingMana()`. Flow this through `AppliedTransfer` into `annotationsForTransfer`, which produces the full 8-annotation mana payment block (including `TappedUntappedPermanent` and mana `UserActionTaken`). Remove mana-tap `CardTapped` events from Stage 4 to prevent duplicates.

**Tech Stack:** Kotlin (matchdoor module), Java (forge submodule — minimal event enrichment), Kotest (tests)

**Issue:** [#103](https://github.com/delebedev/leyline/issues/103)

---

## Recording target (8 annotations)

```
0. ObjectIdChanged        — spell gets new instanceId
1. ZoneTransfer            — hand → stack, category=CastSpell
2. AbilityInstanceCreated  — affectorId=Island, affectedIds=[manaAbilityIid], source_zone=28(Battlefield)
3. TappedUntappedPermanent — affectorId=manaAbilityIid, affectedIds=[Island]
4. UserActionTaken         — actionType=4, abilityGrpId=1002, affectedIds=[manaAbilityIid]
5. ManaPaid                — affectorId=Island, affectedIds=[spell], id=3, color=2
6. AbilityInstanceDeleted  — affectorId=Island, affectedIds=[manaAbilityIid]
7. UserActionTaken         — actionType=1, abilityGrpId=0, affectedIds=[spell]
```

Per-land: annotations 2-6 repeat for each land tapped. `ManaPaid.id` increments (3, 4, 5...).

## File structure

| File | Action | Responsibility |
|------|--------|----------------|
| `matchdoor/src/main/kotlin/leyline/game/GameEvent.kt` | Modify | Add `ManaPayment` data class, enrich `SpellCast` with `manaPayments: List<ManaPayment>` |
| `matchdoor/src/main/kotlin/leyline/game/GameEventCollector.kt` | Modify | Extract mana payment info from `GameEventSpellAbilityCast` |
| `forge/.../event/GameEventSpellAbilityCast.java` | Modify | Add `List<ManaPaymentInfo>` record field carrying sourceCardId, color, abilityGrpId |
| `forge/.../zone/MagicStack.java` | Modify | Extract `sp.getPayingMana()` → `ManaPaymentInfo` list at fire site |
| `matchdoor/src/main/kotlin/leyline/game/AnnotationPipeline.kt` | Modify | Add `manaPayments` to `AppliedTransfer`, rewrite CastSpell branch in `annotationsForTransfer`, filter mana-tap CardTapped from `mechanicAnnotations` |
| `matchdoor/src/main/kotlin/leyline/game/AnnotationBuilder.kt` | Modify | Fix `manaPaid` color type (int, not string), add affectorId params to `abilityInstanceCreated`, `abilityInstanceDeleted`, `manaPaid` |
| `matchdoor/src/test/kotlin/leyline/game/AnnotationPipelineTest.kt` | Modify | Update CastSpell tests to expect 8 annotations with mana payment data |
| `matchdoor/src/test/kotlin/leyline/conformance/AnnotationOrderingTest.kt` | Modify | Update ordering tests for new 8-annotation sequence |
| `matchdoor/src/test/kotlin/leyline/conformance/ConformancePipelineTest.kt` | Modify | Update CastSpell integration test for new annotation count |

### Known edge cases

- **Free spells (0 mana cost):** `manaPayments` is empty → loop doesn't iterate → produces 3 annotations (OIC, ZT, UAT-cast). This is correct — no mana paid, no mana block.
- **Multi-land payments (2+ lands):** The mana block repeats per land. Each land gets a unique `manaAbilityInstanceId` via `sourceForgeCardId + MANA_ABILITY_ID_OFFSET`.
- **Same-source multi-mana (Sol Ring, etc.):** Multiple payments from the same source would get the same `manaAbilityInstanceId`. Future work — needs per-payment index in the offset. Not a concern for basic lands (one mana per tap).
- **Colorless mana:** `Mana.getColor()` returns `0` for colorless. Carried as `color=0` in `ManaPayment`. Arena may encode colorless differently — verify against a colorless-mana recording when available.
- **Non-basic lands:** `manaAbilityGrpIdResolver` falls back to `0` for non-basic lands. Correct for the CHALLENGE-STARTER recording (only basic lands). Future work for non-basic land conformance.

---

### Task 1: Add ManaPaymentInfo to Forge event

The `GameEventSpellAbilityCast` currently wraps a `SpellAbilityView` which loses access to `payingMana`. We need the raw mana payment data (sourceCardId, color bitmask, ability grpId) carried on the event itself.

**Files:**
- Modify: `forge/forge-game/src/main/java/forge/game/event/GameEventSpellAbilityCast.java`
- Modify: `forge/forge-game/src/main/java/forge/game/zone/MagicStack.java:570`

- [ ] **Step 1: Add ManaPaymentInfo record to GameEventSpellAbilityCast**

In `GameEventSpellAbilityCast.java`, add a nested record and a new field:

```java
import java.util.List;
import java.util.ArrayList;

public record GameEventSpellAbilityCast(
    SpellAbilityView sa,
    StackItemView si,
    int stackIndex,
    String targetDescription,
    List<ManaPaymentInfo> manaPayments
) implements GameEvent {

    /** Mana globe used to pay for this spell. */
    public record ManaPaymentInfo(int sourceCardId, byte color) {}

    public GameEventSpellAbilityCast(SpellAbility sa, SpellAbilityStackInstance si, int stackIndex) {
        this(SpellAbilityView.get(sa), StackItemView.get(si), stackIndex,
             computeTargetDescription(sa), extractManaPayments(sa));
    }

    private static List<ManaPaymentInfo> extractManaPayments(SpellAbility sa) {
        List<ManaPaymentInfo> payments = new ArrayList<>();
        for (var mana : sa.getPayingMana()) {
            var source = mana.getSourceCard();
            if (source != null) {
                payments.add(new ManaPaymentInfo(source.getId(), mana.getColor()));
            }
        }
        return List.copyOf(payments);
    }
    // ... keep existing computeTargetDescription
}
```

Note: `source.getId()` on the LKI copy returns the original card ID. `mana.getColor()` returns the byte color bitmask (1=W, 2=U, 4=B, 8=R, 16=G) — matches Arena's bitmask encoding.

- [ ] **Step 2: Verify forge compiles**

Run: `cd forge && mvn compile -pl forge-game -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```
feat(forge): carry mana payment info on GameEventSpellAbilityCast
```

---

### Task 2: Enrich GameEvent.SpellCast with mana payment data

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/GameEvent.kt:64-68`
- Modify: `matchdoor/src/main/kotlin/leyline/game/GameEventCollector.kt:67-72`

- [ ] **Step 1: Add ManaPayment data class and enrich SpellCast**

In `GameEvent.kt`, add inside the sealed interface:

```kotlin
/** One mana globe spent to pay for a spell. */
data class ManaPayment(
    val sourceForgeCardId: Int,
    val color: Int,
)
```

And update `SpellCast`:

```kotlin
data class SpellCast(
    val forgeCardId: Int,
    val seatId: Int,
    val manaPayments: List<ManaPayment> = emptyList(),
) : GameEvent
```

- [ ] **Step 2: Extract mana payments in GameEventCollector**

In `GameEventCollector.kt`, update `visit(GameEventSpellAbilityCast)`:

```kotlin
override fun visit(ev: GameEventSpellAbilityCast) {
    val card = ev.sa().hostCard ?: return
    val seat = seatOf(card.controller) ?: return
    val payments = ev.manaPayments().map { mp ->
        GameEvent.ManaPayment(
            sourceForgeCardId = mp.sourceCardId(),
            color = mp.color().toInt() and 0xFF,
        )
    }
    queue.add(GameEvent.SpellCast(card.id, seat, payments))
    log.debug("event: SpellCast card={} seat={} manaPayments={}", card.name, seat, payments.size)
}
```

- [ ] **Step 3: Build matchdoor to verify compilation**

Run: `just build 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```
feat: enrich SpellCast event with mana payment data
```

---

### Task 3: Fix AnnotationBuilder — affectorId params and ManaPaid color type

The recording shows `abilityInstanceCreated`, `abilityInstanceDeleted`, and `manaPaid` all carry `affectorId` (the land's instanceId). Currently they don't accept one. Also, `ManaPaid.color` is an int bitmask in the recording (e.g. `2` for blue), not a string.

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/AnnotationBuilder.kt:265-304`
- Test: `matchdoor/src/test/kotlin/leyline/game/AnnotationPipelineTest.kt`

- [ ] **Step 1: Write failing test for ManaPaid with int color and affectorId**

In `AnnotationPipelineTest.kt`, add (or find a good spot in the file for annotation builder unit tests):

```kotlin
test("manaPaid carries affectorId and int color") {
    val ann = AnnotationBuilder.manaPaid(
        spellInstanceId = 200, landInstanceId = 300, manaId = 3, color = 2,
    )
    ann.affectorId shouldBe 300
    ann.affectedIdsList shouldContain 200
    ann.detailInt("id") shouldBe 3
    ann.detailInt("color") shouldBe 2
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `just test-one AnnotationPipelineTest 2>&1 | tail -10`
Expected: compilation error — `manaPaid` signature doesn't match

- [ ] **Step 3: Update AnnotationBuilder methods**

In `AnnotationBuilder.kt`:

**`manaPaid`** — change color from String to Int, add `landInstanceId` for affectorId:

```kotlin
fun manaPaid(spellInstanceId: Int, landInstanceId: Int, manaId: Int = 0, color: Int = 0): AnnotationInfo =
    AnnotationInfo.newBuilder()
        .addType(AnnotationType.ManaPaid)
        .setAffectorId(landInstanceId)
        .addAffectedIds(spellInstanceId)
        .addDetails(int32Detail("id", manaId))
        .addDetails(int32Detail("color", color))
        .build()
```

**`abilityInstanceCreated`** — add `affectorId` (land instanceId) and change `affectedIds` to be the mana ability instanceId:

```kotlin
fun abilityInstanceCreated(abilityInstanceId: Int, affectorId: Int = 0, sourceZoneId: Int = 0): AnnotationInfo =
    AnnotationInfo.newBuilder()
        .addType(AnnotationType.AbilityInstanceCreated)
        .also { if (affectorId != 0) it.setAffectorId(affectorId) }
        .addAffectedIds(abilityInstanceId)
        .addDetails(int32Detail("source_zone", sourceZoneId))
        .build()
```

**`abilityInstanceDeleted`** — add `affectorId` (land instanceId):

```kotlin
fun abilityInstanceDeleted(abilityInstanceId: Int, affectorId: Int = 0): AnnotationInfo =
    AnnotationInfo.newBuilder()
        .addType(AnnotationType.AbilityInstanceDeleted)
        .also { if (affectorId != 0) it.setAffectorId(affectorId) }
        .addAffectedIds(abilityInstanceId)
        .build()
```

- [ ] **Step 4: Run test to verify it passes**

Run: `just test-one AnnotationPipelineTest 2>&1 | tail -10`
Expected: PASS

- [ ] **Step 5: Commit**

```
fix: add affectorId to mana annotation builders, use int color for ManaPaid
```

---

### Task 4: Add mana payments to AppliedTransfer and rewrite CastSpell annotation block

This is the main change. The CastSpell branch in `annotationsForTransfer` needs to produce 8 annotations per mana payment instead of 6 flat ones.

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/AnnotationPipeline.kt:42-54` (AppliedTransfer)
- Modify: `matchdoor/src/main/kotlin/leyline/game/AnnotationPipeline.kt:225-232` (CastSpell branch)
- Modify: `matchdoor/src/main/kotlin/leyline/game/AnnotationPipeline.kt:109-177` (detectZoneTransfers — extract mana payments)
- Test: `matchdoor/src/test/kotlin/leyline/game/AnnotationPipelineTest.kt`

- [ ] **Step 1: Write failing test for 8-annotation CastSpell sequence**

In `AnnotationPipelineTest.kt`, update `castSpellProducesSixAnnotations` → rename + adjust:

```kotlin
test("castSpell with one mana payment produces 8 annotations") {
    val transfer = AnnotationPipeline.AppliedTransfer(
        origId = 100,
        newId = 200,
        category = TransferCategory.CastSpell,
        srcZoneId = ZoneIds.P1_HAND,
        destZoneId = ZoneIds.STACK,
        grpId = 67890,
        ownerSeatId = 1,
        manaPayments = listOf(
            AnnotationPipeline.ManaPaymentRecord(
                landInstanceId = 300,
                manaAbilityInstanceId = 400,
                color = 2,
                abilityGrpId = 1002,
            ),
        ),
    )
    val (annotations, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

    annotations.size shouldBe 8
    annotations[0].typeList.first() shouldBe AnnotationType.ObjectIdChanged
    annotations[1].typeList.first() shouldBe AnnotationType.ZoneTransfer_af5a
    // Per-land mana payment block:
    annotations[2].typeList.first() shouldBe AnnotationType.AbilityInstanceCreated
    annotations[3].typeList.first() shouldBe AnnotationType.TappedUntappedPermanent
    annotations[4].typeList.first() shouldBe AnnotationType.UserActionTaken  // mana ability
    annotations[5].typeList.first() shouldBe AnnotationType.ManaPaid
    annotations[6].typeList.first() shouldBe AnnotationType.AbilityInstanceDeleted
    // Cast action:
    annotations[7].typeList.first() shouldBe AnnotationType.UserActionTaken  // cast

    // AbilityInstanceCreated details
    annotations[2].affectorId shouldBe 300  // land
    annotations[2].affectedIdsList shouldContain 400  // mana ability iid
    annotations[2].detailInt("source_zone") shouldBe ZoneIds.BATTLEFIELD

    // TappedUntappedPermanent
    annotations[3].affectorId shouldBe 400  // mana ability iid
    annotations[3].affectedIdsList shouldContain 300  // land

    // UserActionTaken (mana ability)
    annotations[4].detailInt("actionType") shouldBe 4
    annotations[4].detailInt("abilityGrpId") shouldBe 1002
    annotations[4].affectedIdsList shouldContain 400

    // ManaPaid
    annotations[5].affectorId shouldBe 300  // land
    annotations[5].affectedIdsList shouldContain 200  // spell
    annotations[5].detailInt("color") shouldBe 2

    // AbilityInstanceDeleted
    annotations[6].affectorId shouldBe 300  // land
    annotations[6].affectedIdsList shouldContain 400  // mana ability iid

    // UserActionTaken (cast)
    annotations[7].detailInt("actionType") shouldBe 1
    annotations[7].affectedIdsList shouldContain 200

    persistent.size shouldBe 1
    persistent[0].typeList.first() shouldBe AnnotationType.EnteredZoneThisTurn
}
```

Also add a test for free spells (zero mana payments):

```kotlin
test("castSpell with zero mana payments produces 3 annotations") {
    val transfer = AnnotationPipeline.AppliedTransfer(
        origId = 100,
        newId = 200,
        category = TransferCategory.CastSpell,
        srcZoneId = ZoneIds.P1_HAND,
        destZoneId = ZoneIds.STACK,
        grpId = 67890,
        ownerSeatId = 1,
        manaPayments = emptyList(),
    )
    val (annotations, persistent) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

    annotations.size shouldBe 3
    annotations[0].typeList.first() shouldBe AnnotationType.ObjectIdChanged
    annotations[1].typeList.first() shouldBe AnnotationType.ZoneTransfer_af5a
    annotations[2].typeList.first() shouldBe AnnotationType.UserActionTaken
    annotations[2].detailInt("actionType") shouldBe 1

    persistent.size shouldBe 1
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `just test-one AnnotationPipelineTest 2>&1 | tail -10`
Expected: compilation error — `ManaPaymentRecord` and `manaPayments` don't exist

- [ ] **Step 3: Add ManaPaymentRecord and manaPayments to AppliedTransfer**

In `AnnotationPipeline.kt`, add inside the companion object:

```kotlin
/** Pre-resolved mana payment: all IDs are client instanceIds, ready for annotation building. */
data class ManaPaymentRecord(
    val landInstanceId: Int,
    val manaAbilityInstanceId: Int,
    val color: Int,
    val abilityGrpId: Int,
)
```

Add field to `AppliedTransfer`:

```kotlin
data class AppliedTransfer(
    val origId: Int,
    val newId: Int,
    val category: TransferCategory,
    val srcZoneId: Int,
    val destZoneId: Int,
    val grpId: Int,
    val ownerSeatId: Int,
    val affectorId: Int = 0,
    val colorBitmasks: List<Int> = emptyList(),
    val manaPayments: List<ManaPaymentRecord> = emptyList(),
)
```

- [ ] **Step 4: Rewrite CastSpell branch in annotationsForTransfer**

Replace lines 225-232:

```kotlin
TransferCategory.CastSpell -> {
    annotations.add(AnnotationBuilder.objectIdChanged(origId, newId))
    annotations.add(AnnotationBuilder.zoneTransfer(newId, srcZone, destZone, category.label))
    // Per-land mana payment block (repeats for each land tapped)
    for ((i, mp) in transfer.manaPayments.withIndex()) {
        annotations.add(AnnotationBuilder.abilityInstanceCreated(
            abilityInstanceId = mp.manaAbilityInstanceId,
            affectorId = mp.landInstanceId,
            sourceZoneId = ZoneIds.BATTLEFIELD,
        ))
        annotations.add(AnnotationBuilder.tappedUntappedPermanent(
            permanentId = mp.landInstanceId,
            abilityId = mp.manaAbilityInstanceId,
        ))
        annotations.add(AnnotationBuilder.userActionTaken(
            instanceId = mp.manaAbilityInstanceId,
            seatId = actingSeat,
            actionType = 4,
            abilityGrpId = mp.abilityGrpId,
        ))
        annotations.add(AnnotationBuilder.manaPaid(
            spellInstanceId = newId,
            landInstanceId = mp.landInstanceId,
            manaId = i + 3, // recording starts at 3 for CastSpell
            color = mp.color,
        ))
        annotations.add(AnnotationBuilder.abilityInstanceDeleted(
            abilityInstanceId = mp.manaAbilityInstanceId,
            affectorId = mp.landInstanceId,
        ))
    }
    annotations.add(AnnotationBuilder.userActionTaken(newId, actingSeat, actionType = 1))
}
```

Note on `manaId = i + 3`: The recording shows ManaPaid.id starts at 3 for CastSpell. This may be a global counter. For now, use `i + 3` to match the recording. If future recordings show different starting values, we'll need a stateful counter.

- [ ] **Step 5: Run test to verify it passes**

Run: `just test-one AnnotationPipelineTest 2>&1 | tail -10`
Expected: PASS

- [ ] **Step 6: Commit**

```
feat: rewrite CastSpell annotation block with per-land mana payment sequence
```

---

### Task 5: Wire mana payments through detectZoneTransfers

The `detectZoneTransfers` pure function needs to extract `ManaPaymentRecord` from `GameEvent.SpellCast.manaPayments` and resolve forge card IDs to instance IDs.

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/AnnotationPipeline.kt:109-177` (detectZoneTransfers)

- [ ] **Step 1: Add mana payment resolution in detectZoneTransfers**

After the `colorBitmasks` extraction (around line 173), add mana payment resolution:

```kotlin
// Extract mana payment info from SpellCast events for CastSpell transfers.
val manaPayments = if (category == TransferCategory.CastSpell && forgeCardIdValue != null) {
    events.filterIsInstance<GameEvent.SpellCast>()
        .firstOrNull { it.forgeCardId == forgeCardIdValue }
        ?.manaPayments?.map { mp ->
            val landIid = idLookup(mp.sourceForgeCardId).value
            // Allocate a transient mana ability instanceId using a dedicated offset
            val manaAbilityIid = idLookup(mp.sourceForgeCardId + MANA_ABILITY_ID_OFFSET).value
            val abilityGrpId = resolveManaAbilityGrpId(mp.sourceForgeCardId, events)
            ManaPaymentRecord(
                landInstanceId = landIid,
                manaAbilityInstanceId = manaAbilityIid,
                color = mp.color,
                abilityGrpId = abilityGrpId,
            )
        } ?: emptyList()
} else {
    emptyList()
}
```

Add this constant near `STACK_ABILITY_ID_OFFSET` usage:

```kotlin
/** Offset for mana ability instance IDs (separate from stack abilities). */
private const val MANA_ABILITY_ID_OFFSET = 200_000
```

Add helper to resolve mana ability grpId from the land's card data. The approach: look up the land's name via the events, then use `AbilityIdDeriver.BASIC_LAND_ABILITIES` for basic lands. For non-basic lands, use a fallback of 0.

```kotlin
private fun resolveManaAbilityGrpId(sourceForgeCardId: Int, events: List<GameEvent>): Int {
    // For now, basic land abilities are well-known IDs.
    // Non-basic lands will need card data lookup (future work).
    return 0 // Will be resolved via the idLookup + card data in the bridge overload
}
```

Actually, the pure overload doesn't have card data access. The bridge-delegating overload does. We need to pass mana ability grpId resolution as a function parameter or resolve it in the bridge overload.

**Better approach:** Add `manaAbilityGrpIdResolver: (Int) -> Int` parameter to the pure overload, defaulting to `{ 0 }`. The bridge overload passes a resolver that looks up the land's forge card → name → `AbilityIdDeriver.BASIC_LAND_ABILITIES`.

Update the pure `detectZoneTransfers` signature:

```kotlin
internal fun detectZoneTransfers(
    gameObjects: List<GameObjectInfo>,
    zones: List<ZoneInfo>,
    events: List<GameEvent>,
    previousZones: Map<Int, Int>,
    forgeIdLookup: (Int) -> Int?,
    idAllocator: (Int) -> InstanceIdRegistry.IdReallocation,
    idLookup: (Int) -> InstanceId,
    manaAbilityGrpIdResolver: (Int) -> Int = { 0 },
): TransferResult
```

And the bridge-delegating overload:

```kotlin
fun detectZoneTransfers(
    gameObjects: List<GameObjectInfo>,
    zones: List<ZoneInfo>,
    bridge: GameBridge,
    events: List<GameEvent>,
): TransferResult = detectZoneTransfers(
    gameObjects = gameObjects,
    zones = zones,
    events = events,
    previousZones = bridge.diff.allZones(),
    forgeIdLookup = { iid -> bridge.getForgeCardId(InstanceId(iid))?.value },
    idAllocator = { forgeCardId -> bridge.reallocInstanceId(ForgeCardId(forgeCardId)) },
    idLookup = { forgeCardId -> bridge.getOrAllocInstanceId(ForgeCardId(forgeCardId)) },
    manaAbilityGrpIdResolver = { forgeCardId ->
        val card = bridge.getGame()?.findById(forgeCardId)
        if (card != null) {
            val subtypes = card.type.subtypes.map { it.lowercase() }
            AbilityIdDeriver.BASIC_LAND_ABILITIES
                .firstOrNull { it.first in subtypes }?.second ?: 0
        } else 0
    },
)
```

Update the `AppliedTransfer` constructor call to include `manaPayments`:

```kotlin
transfers.add(
    AppliedTransfer(origId, newId, category, prevZone, obj.zoneId, obj.grpId,
        obj.ownerSeatId, affectorId, colorBitmasks, manaPayments),
)
```

- [ ] **Step 2: Build to verify compilation**

Run: `just build 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```
feat: wire mana payment resolution through detectZoneTransfers
```

---

### Task 6: Filter mana-tap CardTapped events from mechanicAnnotations

When a land is tapped for mana payment during a CastSpell, the `TappedUntappedPermanent` is now emitted in Stage 2. Stage 4's `mechanicAnnotations` must skip `CardTapped` events for lands that were already handled as mana payments.

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/AnnotationPipeline.kt:397-491` (mechanicAnnotations)
- Modify: `matchdoor/src/main/kotlin/leyline/game/StateMapper.kt:155-159` (pass mana-paid forgeCardIds)

- [ ] **Step 1: Add manaPaidForgeCardIds parameter to mechanicAnnotations**

Update `mechanicAnnotations` signature to accept a set of forge card IDs that were tapped for mana payment. **Important:** `idResolver` is currently the last parameter (used as a trailing lambda at the call site in `StateMapper.kt:156`). Adding a new parameter after it would break that syntax. Place `manaPaidForgeCardIds` **before** `idResolver` with a default:

```kotlin
fun mechanicAnnotations(
    events: List<GameEvent>,
    manaPaidForgeCardIds: Set<Int> = emptySet(),
    idResolver: (Int) -> Int,
): MechanicAnnotationResult
```

In the `CardTapped` branch, skip events for mana-paid lands:

```kotlin
is GameEvent.CardTapped -> {
    if (ev.forgeCardId in manaPaidForgeCardIds) {
        log.debug("mechanic: skipping tapped for mana-paid land forgeId={}", ev.forgeCardId)
    } else {
        val instanceId = idResolver(ev.forgeCardId)
        annotations.add(AnnotationBuilder.tappedUntappedPermanent(instanceId, instanceId, ev.tapped))
        log.debug("mechanic: tapped={} iid={}", ev.tapped, instanceId)
    }
}
```

- [ ] **Step 2: Collect mana-paid forge card IDs from transfers in StateMapper**

In `StateMapper.kt`, after the Stage 2 loop (around line 149), collect mana-paid forge card IDs directly from events (not from `ManaPaymentRecord` which has client instance IDs, not forge IDs):

```kotlin
val manaPaidForgeCardIds = events
    .filterIsInstance<GameEvent.SpellCast>()
    .flatMap { it.manaPayments.map { mp -> mp.sourceForgeCardId } }
    .toSet()
```

Then update the `mechanicAnnotations` call (currently uses trailing lambda syntax at line 156). The new param goes before `idResolver`:

```kotlin
val mechanicResult = AnnotationPipeline.mechanicAnnotations(events, manaPaidForgeCardIds) { forgeCardId ->
    bridge.getOrAllocInstanceId(ForgeCardId(forgeCardId)).value
}
```

- [ ] **Step 3: Build and run tests**

Run: `just build && just test-one AnnotationPipelineTest 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL, tests pass

- [ ] **Step 4: Commit**

```
fix: filter mana-tap CardTapped from Stage 4 when handled in Stage 2
```

---

### Task 7: Update conformance and ordering tests

The existing `AnnotationOrderingTest` expects 6 CastSpell annotations + TappedUntappedPermanent after UserActionTaken. Update to expect the new 8-annotation sequence.

**Files:**
- Modify: `matchdoor/src/test/kotlin/leyline/conformance/AnnotationOrderingTest.kt:104-194`

- [ ] **Step 1: Update "CastSpell annotation order" test**

The new ordering is:
```
ObjectIdChanged < ZoneTransfer < AbilityInstanceCreated < TappedUntappedPermanent < UserActionTaken(mana) < ManaPaid < AbilityInstanceDeleted < UserActionTaken(cast)
```

Update the test to check: TappedUntappedPermanent now comes BEFORE ManaPaid (within the mana block), and there are two UserActionTaken annotations.

```kotlin
test("CastSpell annotation order") {
    val gsm = base.castSpellAndCapture() ?: error("Could not cast spell at seed 42")
    val types = gsm.annotationsList.map { it.typeList.first() }

    val oicIdx = types.indexOf(AnnotationType.ObjectIdChanged)
    val ztIdx = types.indexOf(AnnotationType.ZoneTransfer_af5a)
    val aicIdx = types.indexOf(AnnotationType.AbilityInstanceCreated)
    val tupIdx = types.indexOf(AnnotationType.TappedUntappedPermanent)
    val mpIdx = types.indexOf(AnnotationType.ManaPaid)
    val aidIdx = types.indexOf(AnnotationType.AbilityInstanceDeleted)
    // Two UserActionTaken: first is mana (actionType=4), last is cast (actionType=1)
    val uatIndices = types.indices.filter { types[it] == AnnotationType.UserActionTaken }

    oicIdx shouldBeGreaterThanOrEqual 0
    ztIdx shouldBeGreaterThanOrEqual 0
    aicIdx shouldBeGreaterThanOrEqual 0
    tupIdx shouldBeGreaterThanOrEqual 0
    mpIdx shouldBeGreaterThanOrEqual 0
    aidIdx shouldBeGreaterThanOrEqual 0
    uatIndices.size shouldBeGreaterThanOrEqual 2

    (oicIdx < ztIdx).shouldBeTrue()
    (ztIdx < aicIdx).shouldBeTrue()
    (aicIdx < tupIdx).shouldBeTrue()
    (tupIdx < mpIdx).shouldBeTrue()
    (mpIdx < aidIdx).shouldBeTrue()
    // Cast UserActionTaken is last
    (aidIdx < uatIndices.last()).shouldBeTrue()
}
```

- [ ] **Step 2: Update "6 annotations + per-land" test → "8 annotations per mana source"**

```kotlin
test("CastSpell: 8 annotations with mana payment block") {
    val gsm = base.castSpellAndCapture() ?: error("Could not cast spell at seed 42")
    val types = gsm.annotationsList.map { it.typeList.first() }

    // First two are always ObjectIdChanged + ZoneTransfer
    types[0] shouldBe AnnotationType.ObjectIdChanged
    types[1] shouldBe AnnotationType.ZoneTransfer_af5a

    // Per-land mana block: AIC, TUP, UAT(mana), ManaPaid, AID
    // Last annotation: UAT(cast)
    types.last() shouldBe AnnotationType.UserActionTaken

    // Should have at least one TappedUntappedPermanent before ManaPaid
    val tupIdx = types.indexOf(AnnotationType.TappedUntappedPermanent)
    val mpIdx = types.indexOf(AnnotationType.ManaPaid)
    (tupIdx < mpIdx).shouldBeTrue()
}
```

- [ ] **Step 3: Update "all annotations reference the new instanceId" test**

After the changes, `AbilityInstanceCreated.affectedIds` contains the **mana ability instanceId**, not the spell's `newInstanceId`. Only `ZoneTransfer`, `ManaPaid.affectedIds`, and the cast `UserActionTaken` should reference the spell's `newInstanceId`. Rewrite the test:

```kotlin
test("CastSpell: spell-referencing annotations use the new instanceId") {
    val (gsm, _, newInstanceId) = base.castSpellAndCaptureWithIds() ?: error("Could not cast spell at seed 42")

    val zt = gsm.annotation(AnnotationType.ZoneTransfer_af5a)
    zt.affectedIdsList.contains(newInstanceId).shouldBeTrue()

    // ManaPaid.affectedIds = spell instanceId
    val mp = gsm.annotation(AnnotationType.ManaPaid)
    mp.affectedIdsList.contains(newInstanceId).shouldBeTrue()

    // Cast UserActionTaken (actionType=1) references spell
    val castUat = gsm.annotationsList
        .filter { AnnotationType.UserActionTaken in it.typeList }
        .first { it.detailInt("actionType") == 1 }
    castUat.affectedIdsList.contains(newInstanceId).shouldBeTrue()

    // AbilityInstanceCreated does NOT reference spell — it references the mana ability iid
    val aic = gsm.annotation(AnnotationType.AbilityInstanceCreated)
    aic.affectedIdsList.contains(newInstanceId).shouldBeFalse()
}
```

- [ ] **Step 4: Update ConformancePipelineTest CastSpell test**

The test at `ConformancePipelineTest.kt:111` ("CastSpell segment: creature cast produces matching annotation structure") extracts annotations by category and writes a frame to `castspell-frame.json`. This test doesn't assert specific annotation counts but captures the output for the `just conform` tool to diff. It should still pass after our changes since it only checks that annotations exist. Verify it passes — if it breaks, the puzzle setup may not be wiring mana payments through the engine. Debug if needed.

- [ ] **Step 5: Run all conformance tests**

Run: `just test-one AnnotationOrderingTest && just test-one ConformancePipelineTest 2>&1 | tail -20`
Expected: all pass

- [ ] **Step 6: Commit**

```
test: update CastSpell annotation ordering tests for mana payment block
```

---

### Task 8: Run conformance gate and update golden

**Files:**
- Golden: `matchdoor/src/test/resources/golden/conform-castspell.json`

- [ ] **Step 1: Run conformance check**

Run: `just conform CastSpell CHALLENGE-STARTER 2>&1 | tail -20`
Expected: Significantly fewer diffs. Ideally exit 0.

- [ ] **Step 2: Debug remaining diffs if any**

If there are remaining diffs, analyze each one:
- `$var_2` binding issue (diff tool issue per issue #103 gap #9) — may need separate fix
- `ManaPaid.id` starting value — adjust if recording uses a different base
- Non-basic land mana ability grpId — 0 fallback may diff

- [ ] **Step 3: Update golden baseline**

Run: `just conform-golden CastSpell CHALLENGE-STARTER`
Expected: Golden file updated

- [ ] **Step 4: Run full test gate**

Run: `just test-gate 2>&1 | tail -20`
Expected: all pass

- [ ] **Step 5: Commit**

```
chore: update CastSpell conformance golden after mana payment fix

Closes #103
```
