# Annotation Fidelity Batch 1 — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix 4 annotation mismatches + add 18 new builder methods to reach 39/50 annotation types OK.

**Architecture:** All changes are in `AnnotationBuilder.kt` (builder methods) + test files. No pipeline wiring in this batch. Each builder method creates an `AnnotationInfo` proto with the correct `AnnotationType` enum and detail keys matching the real Arena server.

**Tech Stack:** Kotlin, protobuf (`Messages.AnnotationInfo`), TestNG

**Key files:**
- `forge-nexus/src/main/kotlin/forge/nexus/game/AnnotationBuilder.kt` — all builder methods
- `forge-nexus/src/test/kotlin/forge/nexus/game/AnnotationBuilderTest.kt` — per-field value/type tests
- `forge-nexus/src/test/kotlin/forge/nexus/game/AnnotationShapeConformanceTest.kt` — golden reference conformance

**Conventions:**
- Detail key helpers: `int32Detail(key, value)`, `uint32Detail(key, value)`, `typedStringDetail(key, value)` — all private in `AnnotationBuilder`
- Proto enum names have suffixes: `Counter_803b`, `AddAbility_af5a`, `DamageDealt_af5a`, etc.
- Tests use `detailKeys(ann)` helper → `Set<String>` of detail key names
- Test group: `@Test(groups = ["unit"])`

---

### Task 1: Fix ModifiedLife mismatch (rename delta → life)

**Files:**
- Modify: `forge-nexus/src/main/kotlin/forge/nexus/game/AnnotationBuilder.kt:261-266`
- Modify: `forge-nexus/src/test/kotlin/forge/nexus/game/AnnotationBuilderTest.kt:302-317`
- Modify: `forge-nexus/src/test/kotlin/forge/nexus/game/AnnotationShapeConformanceTest.kt:105-109,251-254`

**Step 1: Fix the builder**

In `AnnotationBuilder.kt`, method `modifiedLife` (~line 265): change `"delta"` to `"life"`.

```kotlin
fun modifiedLife(playerSeatId: Int, lifeDelta: Int): AnnotationInfo =
    AnnotationInfo.newBuilder()
        .addType(AnnotationType.ModifiedLife)
        .addAffectedIds(playerSeatId)
        .addDetails(int32Detail("life", lifeDelta))
        .build()
```

**Step 2: Update the unit test**

In `AnnotationBuilderTest.kt`, update the two `modifiedLife` tests to check for `"life"` instead of `"delta"`:

```kotlin
@Test
fun modifiedLifePositiveDelta() {
    val ann = AnnotationBuilder.modifiedLife(playerSeatId = 1, lifeDelta = 3)
    assertTrue(ann.typeList.contains(AnnotationType.ModifiedLife))
    assertTrue(ann.affectedIdsList.contains(1))
    val life = ann.detailsList.first { it.key == "life" }
    assertEquals(life.type, KeyValuePairValueType.Int32, "life uses Int32 (signed)")
    assertEquals(life.getValueInt32(0), 3)
}

@Test
fun modifiedLifeNegativeDelta() {
    val ann = AnnotationBuilder.modifiedLife(playerSeatId = 2, lifeDelta = -5)
    val life = ann.detailsList.first { it.key == "life" }
    assertEquals(life.getValueInt32(0), -5, "Negative life delta should be preserved")
}
```

**Step 3: Update shape conformance test**

In `AnnotationShapeConformanceTest.kt`:
- `modifiedLifeDetailKeyShape` (~line 108): change expected set from `setOf("delta")` to `setOf("life")`
- Remove `"ModifiedLife"` from `expectedMismatch` (~line 254)

**Step 4: Run tests**

Run: `cd forge-nexus && just test-unit`
Expected: PASS — no mismatches for ModifiedLife

**Step 5: Commit**

```
fix(nexus): rename ModifiedLife detail key delta → life
```

---

### Task 2: Fix ModifiedPower + ModifiedToughness mismatch (drop {value})

**Files:**
- Modify: `forge-nexus/src/main/kotlin/forge/nexus/game/AnnotationBuilder.kt:269-282`
- Modify: `forge-nexus/src/test/kotlin/forge/nexus/game/AnnotationBuilderTest.kt:394-418`
- Modify: `forge-nexus/src/test/kotlin/forge/nexus/game/AnnotationShapeConformanceTest.kt:111-121,256-261`

**Step 1: Fix both builders**

In `AnnotationBuilder.kt`, `modifiedPower` and `modifiedToughness` — drop the `{value}` detail. Server sends no always-present keys; P/T values come from `GameObjectInfo` fields on the game object, not annotation details.

```kotlin
/** Card's power changed. State parser — P/T values from gameObject fields, not annotation.
 *  Optional details (context needed): effect_id, counter_type, count, sourceAbilityGRPID
 *  (seen in session 09-33-05, grp:93848 with aura/counter effects). */
fun modifiedPower(instanceId: Int): AnnotationInfo =
    AnnotationInfo.newBuilder()
        .addType(AnnotationType.ModifiedPower)
        .addAffectedIds(instanceId)
        .build()

/** Card's toughness changed. State parser — P/T values from gameObject fields, not annotation.
 *  Optional details (context needed): effect_id, counter_type, count, sourceAbilityGRPID. */
fun modifiedToughness(instanceId: Int): AnnotationInfo =
    AnnotationInfo.newBuilder()
        .addType(AnnotationType.ModifiedToughness)
        .addAffectedIds(instanceId)
        .build()
```

**Step 2: Fix all call sites**

Search for `modifiedPower(` and `modifiedToughness(` across forge-nexus to update callers (they previously passed a `value` param — drop it).

Run: grep for `modifiedPower\(` and `modifiedToughness\(` in `forge-nexus/src/`

**Step 3: Update unit tests**

In `AnnotationBuilderTest.kt`, replace the `modifiedPowerFields` and `modifiedToughnessFields` tests:

```kotlin
@Test
fun modifiedPowerFields() {
    val ann = AnnotationBuilder.modifiedPower(instanceId = 1200)
    assertTrue(ann.typeList.contains(AnnotationType.ModifiedPower))
    assertTrue(ann.affectedIdsList.contains(1200))
    assertEquals(ann.affectorId, 0, "ModifiedPower has no affectorId")
    assertEquals(ann.detailsCount, 0, "ModifiedPower has no required detail keys")
}

@Test
fun modifiedToughnessFields() {
    val ann = AnnotationBuilder.modifiedToughness(instanceId = 1300)
    assertTrue(ann.typeList.contains(AnnotationType.ModifiedToughness))
    assertTrue(ann.affectedIdsList.contains(1300))
    assertEquals(ann.detailsCount, 0, "ModifiedToughness has no required detail keys")
}
```

**Step 4: Update shape conformance test**

In `AnnotationShapeConformanceTest.kt`:
- `modifiedPowerDetailKeyShape` / `modifiedToughnessDetailKeyShape`: change expected to `emptySet()`
- Update `ourBuilderKeys` entries: `detailKeys(AnnotationBuilder.modifiedPower(1))` and `detailKeys(AnnotationBuilder.modifiedToughness(1))`
- Remove `"ModifiedPower"` and `"ModifiedToughness"` from `expectedMismatch`

**Step 5: Run tests**

Run: `cd forge-nexus && just test-unit`
Expected: PASS

**Step 6: Commit**

```
fix(nexus): drop {value} from ModifiedPower/Toughness annotations
```

---

### Task 3: Fix SyntheticEvent mismatch (add {type} detail)

**Files:**
- Modify: `forge-nexus/src/main/kotlin/forge/nexus/game/AnnotationBuilder.kt:297-300`
- Modify: `forge-nexus/src/test/kotlin/forge/nexus/game/AnnotationBuilderTest.kt:322-327`
- Modify: `forge-nexus/src/test/kotlin/forge/nexus/game/AnnotationShapeConformanceTest.kt:147-161,264`

**Step 1: Fix the builder**

```kotlin
/** Generic combat result marker. Client dispatches synthetic GameRulesEvent based on type. */
fun syntheticEvent(seatId: Int): AnnotationInfo =
    AnnotationInfo.newBuilder()
        .addType(AnnotationType.SyntheticEvent)
        .addAffectedIds(seatId)
        .addDetails(uint32Detail("type", 1))
        .build()
```

**Step 2: Fix call sites**

Search for `syntheticEvent()` calls — they now need a `seatId` argument. Check `StateMapper.kt` and `AnnotationPipeline.kt`.

**Step 3: Update unit test**

```kotlin
@Test
fun syntheticEventFields() {
    val ann = AnnotationBuilder.syntheticEvent(seatId = 1)
    assertTrue(ann.typeList.contains(AnnotationType.SyntheticEvent))
    assertTrue(ann.affectedIdsList.contains(1))
    val type = ann.detailsList.first { it.key == "type" }
    assertEquals(type.type, KeyValuePairValueType.Uint32)
    assertEquals(type.getValueUint32(0), 1)
}
```

**Step 4: Update shape conformance test**

- `noDetailAnnotationShapes`: remove the `SyntheticEvent` line
- Add new shape test: `syntheticEventDetailKeyShape` asserting `setOf("type")`
- Update `ourBuilderKeys`: `"SyntheticEvent" to detailKeys(AnnotationBuilder.syntheticEvent(1))`
- Remove `"SyntheticEvent"` from `expectedMismatch`

**Step 5: Run tests**

Run: `cd forge-nexus && just test-gate`
Expected: PASS — `expectedMismatch` should now be empty

**Step 6: Commit**

```
fix(nexus): add {type} detail to SyntheticEvent annotation
```

---

### Task 4: Add 5 detail-less Tier 2 builders

Add builders for types with no detail keys: LayeredEffectDestroyed, PlayerSelectingTargets, PlayerSubmittedTargets, DamagedThisTurn, InstanceRevealedToOpponent.

**Files:**
- Modify: `forge-nexus/src/main/kotlin/forge/nexus/game/AnnotationBuilder.kt` — add 5 methods
- Modify: `forge-nexus/src/test/kotlin/forge/nexus/game/AnnotationBuilderTest.kt` — add tests
- Modify: `forge-nexus/src/test/kotlin/forge/nexus/game/AnnotationShapeConformanceTest.kt` — add golden entries

**Step 1: Add builders to `AnnotationBuilder.kt`**

```kotlin
/** Layered effect ended (continuous effect removed). Arena type 19. */
fun layeredEffectDestroyed(effectId: Int): AnnotationInfo =
    AnnotationInfo.newBuilder()
        .addType(AnnotationType.LayeredEffectDestroyed)
        .addAffectedIds(effectId)
        .build()

/** Player is selecting targets for a spell/ability. Arena type 92. */
fun playerSelectingTargets(instanceId: Int): AnnotationInfo =
    AnnotationInfo.newBuilder()
        .addType(AnnotationType.PlayerSelectingTargets)
        .addAffectedIds(instanceId)
        .build()

/** Player submitted target selections. Arena type 93. */
fun playerSubmittedTargets(instanceId: Int): AnnotationInfo =
    AnnotationInfo.newBuilder()
        .addType(AnnotationType.PlayerSubmittedTargets)
        .addAffectedIds(instanceId)
        .build()

/** Creature was dealt damage this turn. Persistent state badge. Arena type 90. */
fun damagedThisTurn(instanceId: Int): AnnotationInfo =
    AnnotationInfo.newBuilder()
        .addType(AnnotationType.DamagedThisTurn)
        .addAffectedIds(instanceId)
        .build()

/** Card in hidden zone revealed to opponent. Persistent badge. Arena type 75. */
fun instanceRevealedToOpponent(instanceId: Int): AnnotationInfo =
    AnnotationInfo.newBuilder()
        .addType(AnnotationType.InstanceRevealedToOpponent)
        .addAffectedIds(instanceId)
        .build()
```

**Step 2: Add unit tests in `AnnotationBuilderTest.kt`**

One test per builder — verify type enum + affected IDs + zero details. Follow `tokenCreatedFields` pattern.

**Step 3: Add golden entries in `AnnotationShapeConformanceTest.kt`**

Add to `goldenAlwaysKeys`:
```kotlin
"LayeredEffectDestroyed" to emptySet(),
"PlayerSelectingTargets" to emptySet(),
"PlayerSubmittedTargets" to emptySet(),
"DamagedThisTurn" to emptySet(),
"InstanceRevealedToOpponent" to emptySet(),
```

Add to `ourBuilderKeys`:
```kotlin
"LayeredEffectDestroyed" to detailKeys(AnnotationBuilder.layeredEffectDestroyed(1)),
"PlayerSelectingTargets" to detailKeys(AnnotationBuilder.playerSelectingTargets(1)),
"PlayerSubmittedTargets" to detailKeys(AnnotationBuilder.playerSubmittedTargets(1)),
"DamagedThisTurn" to detailKeys(AnnotationBuilder.damagedThisTurn(1)),
"InstanceRevealedToOpponent" to detailKeys(AnnotationBuilder.instanceRevealedToOpponent(1)),
```

Add to `noDetailAnnotationShapes`:
```kotlin
assertEquals(detailKeys(AnnotationBuilder.layeredEffectDestroyed(1)), emptySet<String>(), "LayeredEffectDestroyed")
assertEquals(detailKeys(AnnotationBuilder.playerSelectingTargets(1)), emptySet<String>(), "PlayerSelectingTargets")
assertEquals(detailKeys(AnnotationBuilder.playerSubmittedTargets(1)), emptySet<String>(), "PlayerSubmittedTargets")
assertEquals(detailKeys(AnnotationBuilder.damagedThisTurn(1)), emptySet<String>(), "DamagedThisTurn")
assertEquals(detailKeys(AnnotationBuilder.instanceRevealedToOpponent(1)), emptySet<String>(), "InstanceRevealedToOpponent")
```

**Step 4: Run tests**

Run: `cd forge-nexus && just test-unit`
Expected: PASS

**Step 5: Commit**

```
feat(nexus): add 5 detail-less annotation builders (Tier 2)
```

---

### Task 5: Add Counter state annotation builder (Tier 1)

Counter (type 14) is the state annotation — sets final counter counts. Complements existing CounterAdded (type 16) / CounterRemoved (type 17) event annotations.

**Files:**
- Modify: `forge-nexus/src/main/kotlin/forge/nexus/game/AnnotationBuilder.kt`
- Modify: `forge-nexus/src/test/kotlin/forge/nexus/game/AnnotationBuilderTest.kt`
- Modify: `forge-nexus/src/test/kotlin/forge/nexus/game/AnnotationShapeConformanceTest.kt`

**Step 1: Add builder**

```kotlin
/** Counter state: authoritative counter count on a permanent. Arena type 14 (Counter_803b).
 *  Three-parser pattern: type 14 (this, state) + 16 (CounterAdded, event) + 17 (CounterRemoved, event).
 *  [counterType] = numeric counter type (1 = +1/+1).
 *  Real card: grp:93848 with +1/+1 counter (session 09-33-05). */
fun counter(instanceId: Int, counterType: Int, count: Int): AnnotationInfo =
    AnnotationInfo.newBuilder()
        .addType(AnnotationType.Counter_803b)
        .addAffectedIds(instanceId)
        .addDetails(int32Detail("count", count))
        .addDetails(int32Detail("counter_type", counterType))
        .build()
```

**Step 2: Add unit test**

```kotlin
@Test
fun counterStateFields() {
    val ann = AnnotationBuilder.counter(instanceId = 100, counterType = 1, count = 1)
    assertTrue(ann.typeList.contains(AnnotationType.Counter_803b))
    assertTrue(ann.affectedIdsList.contains(100))
    val count = ann.detailsList.first { it.key == "count" }
    assertEquals(count.type, KeyValuePairValueType.Int32)
    assertEquals(count.getValueInt32(0), 1)
    val type = ann.detailsList.first { it.key == "counter_type" }
    assertEquals(type.getValueInt32(0), 1, "counter_type=1 for +1/+1")
}
```

**Step 3: Add golden entries**

`goldenAlwaysKeys`: `"Counter" to setOf("count", "counter_type")`
`ourBuilderKeys`: `"Counter" to detailKeys(AnnotationBuilder.counter(1, 1, 1))`

Add shape test:
```kotlin
@Test(description = "Counter shape: {count, counter_type}")
fun counterDetailKeyShape() {
    val ann = AnnotationBuilder.counter(1, 1, 1)
    assertEquals(detailKeys(ann), setOf("count", "counter_type"))
}
```

**Step 4: Run tests**

Run: `cd forge-nexus && just test-unit`
Expected: PASS

**Step 5: Commit**

```
feat(nexus): add Counter state annotation builder (type 14)
```

---

### Task 6: Add AddAbility + RemoveAbility builders (Tier 1)

**Files:** Same three files.

**Step 1: Add builders**

```kotlin
/** Granted ability state. Arena type 9 (AddAbility_af5a).
 *  [grpId] = ability's card grpId, [effectId] = layered effect ID,
 *  [uniqueAbilityId] = unique ability identifier, [originalAbilityObjectZcid] = source object.
 *  Real card: grp:92081 via effect 7005 (session 14-15-29). */
fun addAbility(
    instanceId: Int,
    grpId: Int,
    effectId: Int,
    uniqueAbilityId: Int,
    originalAbilityObjectZcid: Int,
): AnnotationInfo =
    AnnotationInfo.newBuilder()
        .addType(AnnotationType.AddAbility_af5a)
        .addAffectedIds(instanceId)
        .addDetails(int32Detail("grpid", grpId))
        .addDetails(int32Detail("effect_id", effectId))
        .addDetails(int32Detail("UniqueAbilityId", uniqueAbilityId))
        .addDetails(int32Detail("originalAbilityObjectZcid", originalAbilityObjectZcid))
        .build()

/** Ability removed by effect. Arena type 23 (RemoveAbility).
 *  [effectId] = layered effect ID that caused the removal.
 *  Real card: effect cleanup (session 2026-03-01, grp:92196). */
fun removeAbility(instanceId: Int, effectId: Int): AnnotationInfo =
    AnnotationInfo.newBuilder()
        .addType(AnnotationType.RemoveAbility)
        .addAffectedIds(instanceId)
        .addDetails(int32Detail("effect_id", effectId))
        .build()
```

**Step 2: Add unit tests**

```kotlin
@Test
fun addAbilityFields() {
    val ann = AnnotationBuilder.addAbility(
        instanceId = 100, grpId = 6, effectId = 7005,
        uniqueAbilityId = 217, originalAbilityObjectZcid = 372,
    )
    assertTrue(ann.typeList.contains(AnnotationType.AddAbility_af5a))
    assertTrue(ann.affectedIdsList.contains(100))
    assertEquals(ann.detailsList.first { it.key == "grpid" }.getValueInt32(0), 6)
    assertEquals(ann.detailsList.first { it.key == "effect_id" }.getValueInt32(0), 7005)
    assertEquals(ann.detailsList.first { it.key == "UniqueAbilityId" }.getValueInt32(0), 217)
    assertEquals(ann.detailsList.first { it.key == "originalAbilityObjectZcid" }.getValueInt32(0), 372)
}

@Test
fun removeAbilityFields() {
    val ann = AnnotationBuilder.removeAbility(instanceId = 200, effectId = 7003)
    assertTrue(ann.typeList.contains(AnnotationType.RemoveAbility))
    assertTrue(ann.affectedIdsList.contains(200))
    assertEquals(ann.detailsList.first { it.key == "effect_id" }.getValueInt32(0), 7003)
    assertEquals(ann.detailsCount, 1)
}
```

**Step 3: Add golden entries**

`goldenAlwaysKeys`:
```kotlin
"AddAbility" to setOf("grpid", "effect_id", "UniqueAbilityId", "originalAbilityObjectZcid"),
"RemoveAbility" to setOf("effect_id"),
```

`ourBuilderKeys`:
```kotlin
"AddAbility" to detailKeys(AnnotationBuilder.addAbility(1, 1, 1, 1, 1)),
"RemoveAbility" to detailKeys(AnnotationBuilder.removeAbility(1, 1)),
```

Add shape tests for each.

**Step 4: Run tests, commit**

```
feat(nexus): add AddAbility + RemoveAbility annotation builders
```

---

### Task 7: Add AbilityExhausted + GainDesignation + Designation builders (Tier 1)

**Files:** Same three files.

**Step 1: Add builders**

```kotlin
/** Per-ability use tracking. Arena type 82 (AbilityExhausted).
 *  Real card: grp:95039 activated ability exhausted (session 09-33-05). */
fun abilityExhausted(
    instanceId: Int,
    abilityGrpId: Int,
    usesRemaining: Int,
    uniqueAbilityId: Int,
): AnnotationInfo =
    AnnotationInfo.newBuilder()
        .addType(AnnotationType.AbilityExhausted)
        .addAffectedIds(instanceId)
        .addDetails(int32Detail("AbilityGrpId", abilityGrpId))
        .addDetails(int32Detail("UsesRemaining", usesRemaining))
        .addDetails(int32Detail("UniqueAbilityId", uniqueAbilityId))
        .build()

/** Designation gained (Monarch, City's Blessing, Initiative). Arena type 46 (GainDesignation).
 *  Event parser — emits DesignationCreatedEvent.
 *  Real card: grp:92196, DesignationType=19 (session 2026-03-01). */
fun gainDesignation(seatId: Int, designationType: Int): AnnotationInfo =
    AnnotationInfo.newBuilder()
        .addType(AnnotationType.GainDesignation)
        .addAffectedIds(seatId)
        .addDetails(int32Detail("DesignationType", designationType))
        .build()

/** Designation state (persistent). Arena type 45 (Designation).
 *  Stub — always-present key only. Full version needs PromptMessage, CostIncrease, grpid, etc. (context needed).
 *  Real card: grp:92196 (session 2026-03-01). */
fun designation(seatId: Int, designationType: Int): AnnotationInfo =
    AnnotationInfo.newBuilder()
        .addType(AnnotationType.Designation)
        .addAffectedIds(seatId)
        .addDetails(int32Detail("DesignationType", designationType))
        .build()
```

**Step 2: Add unit tests** — one per builder, verify type + keys + values.

**Step 3: Add golden entries**

`goldenAlwaysKeys`:
```kotlin
"AbilityExhausted" to setOf("AbilityGrpId", "UsesRemaining", "UniqueAbilityId"),
"GainDesignation" to setOf("DesignationType"),
"Designation" to setOf("DesignationType"),
```

**Step 4: Run tests, commit**

```
feat(nexus): add AbilityExhausted, GainDesignation, Designation builders
```

---

### Task 8: Add LayeredEffect stub builder (Tier 1)

**Files:** Same three files.

**Step 1: Add builder**

```kotlin
/** Layered effect state (continuous effects). Arena type 51 (LayeredEffect).
 *  Stub — emits always-present key only.
 *  Optional details (context needed): grpid, UniqueAbilityId, sourceAbilityGRPID,
 *  originalAbilityObjectZcid, MaxHandSize (seen in sessions 09-33-05, 14-15-29).
 *  Full ProcessMutate/ProcessNormal split needs more context.
 *  Real card: grp:93848, effect_id=7004 (session 09-33-05). */
fun layeredEffect(instanceId: Int, effectId: Int): AnnotationInfo =
    AnnotationInfo.newBuilder()
        .addType(AnnotationType.LayeredEffect)
        .addAffectedIds(instanceId)
        .addDetails(int32Detail("effect_id", effectId))
        .build()
```

**Step 2: Add unit test + golden entries**

`goldenAlwaysKeys`: `"LayeredEffect" to setOf("effect_id")`

**Step 3: Run tests, commit**

```
feat(nexus): add LayeredEffect stub annotation builder
```

---

### Task 9: Add 6 detail-carrying Tier 2 builders

ColorProduction, TriggeringObject, TargetSpec, PowerToughnessModCreated, DisplayCardUnderCard, PredictedDirectDamage.

**Files:** Same three files.

**Step 1: Add builders**

```kotlin
/** Land color production for card frame rendering. Arena type 110 (ColorProduction).
 *  [colors] = bitmask (1=W, 2=U, 4=B, 8=R, 16=G).
 *  Real card: grp:96188, colors=4 (session 09-33-05). */
fun colorProduction(instanceId: Int, colors: Int): AnnotationInfo =
    AnnotationInfo.newBuilder()
        .addType(AnnotationType.ColorProduction)
        .addAffectedIds(instanceId)
        .addDetails(int32Detail("colors", colors))
        .build()

/** Which object triggered an ability + source zone. Arena type 32 (TriggeringObject).
 *  Real card: grp:95039, zone=27 (session 09-33-05). */
fun triggeringObject(instanceId: Int, sourceZone: Int): AnnotationInfo =
    AnnotationInfo.newBuilder()
        .addType(AnnotationType.TriggeringObject)
        .addAffectedIds(instanceId)
        .addDetails(int32Detail("source_zone", sourceZone))
        .build()

/** Target specification for spells/abilities. Arena type 26 (TargetSpec).
 *  Real card: grp:75479, promptId=1330 (session 11-50-40). */
fun targetSpec(
    instanceId: Int,
    abilityGrpId: Int,
    index: Int,
    promptId: Int,
    promptParameters: Int,
): AnnotationInfo =
    AnnotationInfo.newBuilder()
        .addType(AnnotationType.TargetSpec)
        .addAffectedIds(instanceId)
        .addDetails(int32Detail("abilityGrpId", abilityGrpId))
        .addDetails(int32Detail("index", index))
        .addDetails(int32Detail("promptId", promptId))
        .addDetails(int32Detail("promptParameters", promptParameters))
        .build()

/** P/T modification event (buff animation). Arena type 71 (PowerToughnessModCreated).
 *  Real card: grp:91865, +1/+1 (session 09-33-05). */
fun powerToughnessModCreated(instanceId: Int, power: Int, toughness: Int): AnnotationInfo =
    AnnotationInfo.newBuilder()
        .addType(AnnotationType.PowerToughnessModCreated)
        .addAffectedIds(instanceId)
        .addDetails(int32Detail("power", power))
        .addDetails(int32Detail("toughness", toughness))
        .build()

/** Card displayed under another card (imprint, adventure exile). Arena type 38 (DisplayCardUnderCard).
 *  Real card: grp:75479 (session 11-50-40). */
fun displayCardUnderCard(instanceId: Int, disable: Int = 0, temporaryZoneTransfer: Int = 1): AnnotationInfo =
    AnnotationInfo.newBuilder()
        .addType(AnnotationType.DisplayCardUnderCard)
        .addAffectedIds(instanceId)
        .addDetails(int32Detail("Disable", disable))
        .addDetails(int32Detail("TemporaryZoneTransfer", temporaryZoneTransfer))
        .build()

/** Predicted direct damage preview text. Arena type 66 (PredictedDirectDamage).
 *  Real card: grp:58445, value=2 (session 2026-03-01). */
fun predictedDirectDamage(instanceId: Int, value: Int): AnnotationInfo =
    AnnotationInfo.newBuilder()
        .addType(AnnotationType.PredictedDirectDamage)
        .addAffectedIds(instanceId)
        .addDetails(int32Detail("value", value))
        .build()
```

**Step 2: Add unit tests** — one per builder.

**Step 3: Add golden entries**

`goldenAlwaysKeys`:
```kotlin
"ColorProduction" to setOf("colors"),
"TriggeringObject" to setOf("source_zone"),
"TargetSpec" to setOf("abilityGrpId", "index", "promptId", "promptParameters"),
"PowerToughnessModCreated" to setOf("power", "toughness"),
"DisplayCardUnderCard" to setOf("Disable", "TemporaryZoneTransfer"),
"PredictedDirectDamage" to setOf("value"),
```

**Step 4: Run tests**

Run: `cd forge-nexus && just test-gate`
Expected: PASS — all golden reference entries match

**Step 5: Commit**

```
feat(nexus): add 6 detail-carrying Tier 2 annotation builders
```

---

### Task 10: Final verification + format

**Step 1: Run full test gate**

Run: `cd forge-nexus && just test-gate`
Expected: PASS

**Step 2: Format**

Run: `cd forge-nexus && just fmt`

**Step 3: Run test gate again after fmt**

Run: `cd forge-nexus && just test-gate`
Expected: PASS

**Step 4: Verify variance report (if recordings available)**

Run: `cd forge-nexus && just proto-annotation-variance --summary 2>/dev/null | head -5`

Check that MISMATCH count is 0 and NOT IMPLEMENTED count dropped by ~18.

**Step 5: Commit if fmt changed anything**

```
style(nexus): format annotation builder changes
```
