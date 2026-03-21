# Treasure Sacrifice Annotation Conformance

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Emit the full mana-ability annotation bracket (AbilityInstanceCreated, TappedUntapped, UserActionTaken, ManaPaid, AbilityInstanceDeleted) around Treasure sacrifice zone transfers, matching real server behavior from recording `2026-02-28_09-33-05` frame 315.

**Architecture:** Three layers — (1) Add `GameEventManaAbilityActivated` to Forge (~10 lines, 3 files) so the pipeline has a direct signal rather than correlating events after the fact. (2) Wire through GameEvent → GameEventCollector → AnnotationPipeline, attaching mana payment info to Sacrifice transfers when the sacrificed card activated a mana ability. (3) Extend the Sacrifice branch of `annotationsForTransfer` to emit the full bracket. Conformance-first: establish golden baseline before any code changes.

**Tech Stack:** Kotlin (matchdoor), Java (forge), Python (tape tooling), Kotest, protobuf

**References:**
- Issue: #119
- Recording: `2026-02-28_09-33-05`, frames 275 (TokenCreated) / 315 (Sacrifice)
- Real server annotation sequence: AbilityInstanceCreated → TappedUntapped → ObjectIdChanged → ZoneTransfer(Sacrifice) → UserActionTaken(4) → ManaPaid → AbilityInstanceDeleted → TokenDeleted

---

## File Map

### Forge (new event)
- Create: `forge/forge-game/src/main/java/forge/game/event/GameEventManaAbilityActivated.java`
- Modify: `forge/forge-game/src/main/java/forge/game/event/IGameEventVisitor.java` (interface + Base)
- Modify: `forge/forge-game/src/main/java/forge/game/spellability/AbilityManaPart.java:196` (fire event)

### matchdoor (event wiring + pipeline)
- Modify: `matchdoor/src/main/kotlin/leyline/game/GameEvent.kt` (new sealed variant)
- Modify: `matchdoor/src/main/kotlin/leyline/game/GameEventCollector.kt` (visitor)
- Modify: `matchdoor/src/main/kotlin/leyline/game/AnnotationPipeline.kt` (detect + annotate)
- Modify: `matchdoor/src/main/kotlin/leyline/game/StateMapper.kt` (manaPaidForgeCardIds)

### Tests
- Modify: `matchdoor/src/test/kotlin/leyline/conformance/TreasureTokenTest.kt` (annotation assertions)
- Modify: `matchdoor/src/test/kotlin/leyline/conformance/ConformancePipelineTest.kt` (Sacrifice segment)
- Create: `matchdoor/src/test/resources/golden/conform-sacrifice.json` (golden baseline)
- Modify: `matchdoor/src/test/kotlin/leyline/game/AnnotationPipelineTest.kt` (unit test for Sacrifice+mana)

---

## Task 1: Establish conformance baseline (before any code changes)

Capture what the engine currently produces for Treasure sacrifice so we can measure improvement.

**Files:**
- Modify: `matchdoor/src/test/kotlin/leyline/conformance/ConformancePipelineTest.kt`
- Create: `matchdoor/src/test/resources/golden/conform-sacrifice.json`

- [ ] **Step 1: Add Sacrifice segment test to ConformancePipelineTest**

Add a test that casts a spell using Treasure mana (reuse the TreasureTokenTest puzzle pattern), captures the Sacrifice frame, and writes it to `build/conformance/sacrifice-frame.json`.

```kotlin
test("Sacrifice segment: Treasure sacrifice produces annotation structure") {
    val h = MatchFlowHarness(seed = 42L, validating = false)
    harness = h

    // Need Treasure on battlefield + a spell to cast with it.
    // Prosperous Innkeeper ETBs a Treasure. Cast Innkeeper first,
    // then cast Lightning Bolt (R) using Treasure mana.
    h.connectAndKeepPuzzleText(
        """
        [metadata]
        Name:Conformance Sacrifice
        Goal:Win
        Turns:3
        Difficulty:Tutorial
        Description:Pipeline test — Treasure sacrifice for mana

        [state]
        ActivePlayer=Human
        ActivePhase=Main1
        HumanLife=20
        AILife=3

        humanhand=Prosperous Innkeeper;Lightning Bolt
        humanbattlefield=Forest;Forest
        humanlibrary=Forest;Forest;Forest
        aibattlefield=Centaur Courser
        ailibrary=Mountain;Mountain;Mountain
        """.trimIndent(),
    )

    // Cast Innkeeper → ETB creates Treasure
    h.castSpellByName("Prosperous Innkeeper").shouldBeTrue()
    repeat(10) {
        val bf = h.bridge.getPlayer(SeatId(1))!!
            .getZone(ZoneType.Battlefield).cards.map { it.name }
        if ("Treasure Token" in bf) return@repeat
        h.passPriority()
    }

    // Now cast Lightning Bolt — Treasure sacrifices to provide R
    val snap = h.messageSnapshot()
    h.castSpellByName("Lightning Bolt").shouldBeTrue()
    h.selectTargets(listOf(2))
    val msgs = h.messagesSince(snap)

    // Extract frame with Sacrifice category
    val frame = AnnotationSerializer.extractByCategory(msgs, "Sacrifice")
    frame.shouldNotBeNull()

    File(outputDir, "sacrifice-frame.json").writeText(
        buildString {
            append("{\n")
            val entries = frame.entries.toList()
            for ((i, entry) in entries.withIndex()) {
                append("  \"${entry.key}\": ")
                append(serializeValue(entry.value))
                if (i < entries.size - 1) append(",")
                append("\n")
            }
            append("}")
        },
    )
}
```

Note: needs `import leyline.bridge.SeatId` and `import forge.game.zone.ZoneType` added to imports. Also needs `TestCardRegistry.ensureCardRegistered` calls in a `beforeSpec` block — check TreasureTokenTest for the exact card registration pattern (Prosperous Innkeeper, Lightning Bolt, Centaur Courser, Treasure Token grpId mapping).

- [ ] **Step 2: Run the test to verify it produces output**

```bash
cd /Users/denislebedev/src/leyline
./gradlew :matchdoor:testIntegration -Pkotest.filter.specs=".*ConformancePipelineTest" -q
ls -la matchdoor/build/conformance/sacrifice-frame.json
```

Expected: test passes, `sacrifice-frame.json` exists. Inspect it — should have ObjectIdChanged + ZoneTransfer(Sacrifice) + TokenDeleted but NO AbilityInstanceCreated/ManaPaid/UserActionTaken/TappedUntapped.

- [ ] **Step 3: Capture golden baseline**

```bash
just conform-golden Sacrifice 2026-02-28_09-33-05
```

If `just conform Sacrifice` fails because there's no recording template for "Sacrifice" category, first check:
```bash
just tape segment list 2026-02-28_09-33-05
```

If "Sacrifice" isn't a listed category, use the recording frame directly to build the template manually — or note this as a known gap and create the golden from the engine-only output for now.

- [ ] **Step 4: Commit baseline**

```bash
git checkout -b fix/treasure-sacrifice-conformance
git add matchdoor/src/test/kotlin/leyline/conformance/ConformancePipelineTest.kt
git add matchdoor/src/test/resources/golden/conform-sacrifice.json
git commit -m "test(conform): add Sacrifice segment baseline for Treasure mana annotations

Captures current engine output for Treasure sacrifice flow.
Missing vs real server: AbilityInstanceCreated/Deleted, TappedUntapped,
ManaPaid, UserActionTaken(actionType=4).

Refs #119"
```

---

## Task 2: Add GameEventManaAbilityActivated to Forge

**Files:**
- Create: `forge/forge-game/src/main/java/forge/game/event/GameEventManaAbilityActivated.java`
- Modify: `forge/forge-game/src/main/java/forge/game/event/IGameEventVisitor.java`
- Modify: `forge/forge-game/src/main/java/forge/game/spellability/AbilityManaPart.java`

- [ ] **Step 1: Create the event record**

```java
// forge/forge-game/src/main/java/forge/game/event/GameEventManaAbilityActivated.java
package forge.game.event;

import forge.game.card.CardView;

/**
 * Fired when a mana ability finishes producing mana.
 * Enables downstream annotation of mana-sacrifice sequences (e.g. Treasure tokens).
 */
public record GameEventManaAbilityActivated(CardView source, String produced) implements GameEvent {
    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String toString() {
        return source + " produced " + produced;
    }
}
```

- [ ] **Step 2: Add visitor methods to IGameEventVisitor**

In `IGameEventVisitor.java`:
- Add to the interface (near line 13, alongside other visit methods):
  ```java
  T visit(GameEventManaAbilityActivated event);
  ```
- Add to the `Base` inner class (near line 75, alongside other default implementations):
  ```java
  public T visit(GameEventManaAbilityActivated event) { return null; }
  ```

- [ ] **Step 3: Fire the event in AbilityManaPart.produceMana()**

In `AbilityManaPart.java`, after line 196 (`manaPool.add(this.lastManaProduced);`), before the trigger handler:

```java
game.fireEvent(new GameEventManaAbilityActivated(CardView.get(source), afterReplace));
```

Add import at top: `import forge.game.event.GameEventManaAbilityActivated;`

- [ ] **Step 4: Build Forge to verify compilation**

```bash
cd /Users/denislebedev/src/leyline && just install-forge
```

Expected: clean build, no errors.

- [ ] **Step 5: Commit**

```bash
git add forge/forge-game/src/main/java/forge/game/event/GameEventManaAbilityActivated.java
git add forge/forge-game/src/main/java/forge/game/event/IGameEventVisitor.java
git add forge/forge-game/src/main/java/forge/game/spellability/AbilityManaPart.java
git commit -m "feat(forge): add GameEventManaAbilityActivated for mana-sacrifice annotation

Fires from AbilityManaPart.produceMana() after mana is added to pool.
Carries source CardView + produced color string.
~10 lines across 3 files, mechanical visitor-pattern addition.

Refs #119"
```

---

## Task 3: Wire GameEvent.ManaAbilityActivated through collector

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/GameEvent.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/game/GameEventCollector.kt`

- [ ] **Step 1: Add sealed variant to GameEvent.kt**

Add to the `// -- Group B: annotation-producing events --` section (after CardTapped, before CountersChanged):

```kotlin
/** A mana ability was activated and produced mana.
 *  Wired from GameEventManaAbilityActivated (fires in AbilityManaPart.produceMana).
 *  Used to attach mana-ability annotations to Sacrifice zone transfers (Treasure tokens). */
data class ManaAbilityActivated(
    val forgeCardId: Int,
    val seatId: Int,
    val produced: String,
) : GameEvent
```

- [ ] **Step 2: Add visitor in GameEventCollector.kt**

Add visit override (near the existing `visit(GameEventCardTapped)` handler):

```kotlin
override fun visit(ev: GameEventManaAbilityActivated) {
    val card = ev.source()
    val seat = seatOf(card.controller) ?: return
    queue.add(GameEvent.ManaAbilityActivated(card.id, seat, ev.produced()))
    log.debug("event: ManaAbilityActivated card={} seat={} produced={}", card.name, seat, ev.produced())
}
```

Add import: `import forge.game.event.GameEventManaAbilityActivated`

- [ ] **Step 3: Build to verify compilation**

```bash
just build
```

Expected: clean build. The event is captured but not yet consumed by the pipeline.

- [ ] **Step 4: Commit**

```bash
git add matchdoor/src/main/kotlin/leyline/game/GameEvent.kt
git add matchdoor/src/main/kotlin/leyline/game/GameEventCollector.kt
git commit -m "feat(matchdoor): wire GameEvent.ManaAbilityActivated from Forge

New sealed variant + collector visitor. Event captured but not yet
consumed by annotation pipeline — next step.

Refs #119"
```

---

## Task 4: Detect disappeared token sacrifices + emit mana bracket

**Critical discovery from Task 1:** Token sacrifices are invisible to `detectZoneTransfers` because Forge cleans up tokens that leave the battlefield BEFORE the state snapshot. The Treasure token simply vanishes from `gameObjects` — no zone change is detected, so no ZoneTransfer annotation fires.

**Revised approach:** Add "disappeared object" detection to `detectZoneTransfers`. After the main loop, compare `previousZones` against current `gameObjects` to find objects that were on the battlefield but are now missing. Cross-reference with `CardSacrificed` + `ManaAbilityActivated` events to synthesize `AppliedTransfer` records for them. Stage 2 then processes them normally.

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/AnnotationPipeline.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/game/StateMapper.kt`

- [ ] **Step 1: Add spellInstanceId to ManaPaymentRecord**

```kotlin
data class ManaPaymentRecord(
    val landInstanceId: Int,
    val manaAbilityInstanceId: Int,
    val color: Int,
    val abilityGrpId: Int,
    /** InstanceId of the spell/ability this mana pays for (ManaPaid.affectedIds). */
    val spellInstanceId: Int = 0,
)
```

- [ ] **Step 2: Populate spellInstanceId for CastSpell mana payments**

In `detectZoneTransfers` (pure overload), inside the CastSpell mana payment extraction (around line 209-222), `newId` is the spell's new instanceId. Pass it through:

```kotlin
ManaPaymentRecord(
    landInstanceId = landIid,
    manaAbilityInstanceId = manaAbilityIid,
    color = mp.color,
    abilityGrpId = abilityGrpId,
    spellInstanceId = newId,
)
```

- [ ] **Step 3: Detect disappeared objects — synthesize transfers for sacrificed tokens**

After the main transfer detection loop in `detectZoneTransfers` (before the "record zones for instanceIds" loop at ~line 239), add a post-pass that finds objects that were on the battlefield in `previousZones` but are no longer in `gameObjects`:

```kotlin
// Post-pass: detect objects that disappeared from the snapshot (token sacrifices).
// Tokens sacrificed for mana costs are cleaned up by SBAs before we snapshot,
// so detectZoneTransfers never sees their zone change. We detect them by comparing
// previousZones against the current gameObjects set.
val currentInstanceIds = patchedObjects.map { it.instanceId }.toSet()
val manaActivations = events.filterIsInstance<GameEvent.ManaAbilityActivated>()
    .associateBy { it.forgeCardId }
val sacrificeEvents = events.filterIsInstance<GameEvent.CardSacrificed>()
    .associateBy { it.forgeCardId }
val spellCasts = events.filterIsInstance<GameEvent.SpellCast>()

for ((instanceId, prevZone) in previousZones) {
    if (instanceId in currentInstanceIds) continue  // still present — handled above
    if (prevZone != ZONE_BATTLEFIELD) continue       // only care about BF disappearances
    val forgeCardIdValue = forgeIdLookup(instanceId) ?: continue
    val sacrificeEv = sacrificeEvents[forgeCardIdValue] ?: continue

    // This object was sacrificed and disappeared (token cleanup).
    // Determine graveyard zone from seat (P1=33, P2=37).
    val destZone = if (sacrificeEv.seatId == 1) ZONE_P1_GRAVEYARD else ZONE_P2_GRAVEYARD

    // Allocate new instanceId for the zone transfer (same as visible transfers)
    val realloc = idAllocator(forgeCardIdValue)
    val origId = realloc.old.value
    val newId = realloc.new.value

    if (newId != origId) {
        retiredIds.add(origId)
        appendToZone(patchedZones, ZONE_LIMBO, origId)
    }

    // Build mana payment record if this was a mana sacrifice (Treasure)
    val manaActivation = manaActivations[forgeCardIdValue]
    val manaPayments = if (manaActivation != null) {
        val spell = spellCasts.firstOrNull { sc ->
            sc.manaPayments.any { it.sourceForgeCardId == forgeCardIdValue }
        }
        val payment = spell?.manaPayments?.firstOrNull { it.sourceForgeCardId == forgeCardIdValue }
        if (spell != null && payment != null) {
            val manaAbilityIid = idLookup(forgeCardIdValue + MANA_ABILITY_ID_OFFSET).value
            val abilityGrpId = manaAbilityGrpIdResolver(forgeCardIdValue)
            val spellIid = idLookup(spell.forgeCardId).value
            listOf(
                ManaPaymentRecord(
                    landInstanceId = origId,
                    manaAbilityInstanceId = manaAbilityIid,
                    color = payment.color,
                    abilityGrpId = abilityGrpId,
                    spellInstanceId = spellIid,
                ),
            )
        } else emptyList()
    } else emptyList()

    // Remove this mana source from any CastSpell transfer to avoid duplication.
    // The mana annotations will fire on the Sacrifice transfer instead.
    if (manaPayments.isNotEmpty()) {
        for (i in transfers.indices) {
            val t = transfers[i]
            if (t.category != TransferCategory.CastSpell || t.manaPayments.isEmpty()) continue
            val filtered = t.manaPayments.filter { mp ->
                val fid = forgeIdLookup(mp.landInstanceId)
                fid == null || fid != forgeCardIdValue
            }
            if (filtered.size != t.manaPayments.size) {
                transfers[i] = t.copy(manaPayments = filtered)
            }
        }
    }

    transfers.add(
        AppliedTransfer(
            origId = origId,
            newId = newId,
            category = TransferCategory.Sacrifice,
            srcZoneId = prevZone,
            destZoneId = destZone,
            grpId = 0,  // token is gone, grpId unavailable from snapshot
            ownerSeatId = sacrificeEv.seatId,
            manaPayments = manaPayments,
        ),
    )
    zoneRecordings.add(newId to destZone)
    log.debug("disappeared object: iid {} (forgeId={}) sacrificed, synthetic transfer BF→GY", origId, forgeCardIdValue)
}
```

- [ ] **Step 4: Emit mana bracket in Sacrifice branch of annotationsForTransfer**

Add a new `TransferCategory.Sacrifice` branch to the `when` block. Currently Sacrifice falls through to the catch-all (lines 325-335). Give it its own branch:

```kotlin
TransferCategory.Sacrifice -> {
    if (transfer.manaPayments.isNotEmpty()) {
        // Mana-sacrifice: Treasure-style tap-and-sacrifice for mana
        val mp = transfer.manaPayments.first()
        annotations.add(
            AnnotationBuilder.abilityInstanceCreated(
                abilityInstanceId = mp.manaAbilityInstanceId,
                affectorId = origId,
                sourceZoneId = srcZone,
            ),
        )
        annotations.add(
            AnnotationBuilder.tappedUntappedPermanent(
                permanentId = origId,
                abilityId = mp.manaAbilityInstanceId,
            ),
        )
        if (origId != newId) {
            annotations.add(AnnotationBuilder.objectIdChanged(origId, newId, affectorId))
        }
        annotations.add(AnnotationBuilder.zoneTransfer(newId, srcZone, destZone, category.label, affectorId = affectorId))
        annotations.add(
            AnnotationBuilder.userActionTaken(
                instanceId = mp.manaAbilityInstanceId,
                seatId = actingSeat,
                actionType = 4,
                abilityGrpId = mp.abilityGrpId,
            ),
        )
        annotations.add(
            AnnotationBuilder.manaPaid(
                spellInstanceId = mp.spellInstanceId,
                landInstanceId = origId,
                manaId = MANA_ID_BASE,
                color = mp.color,
            ),
        )
        annotations.add(
            AnnotationBuilder.abilityInstanceDeleted(
                abilityInstanceId = mp.manaAbilityInstanceId,
                affectorId = origId,
            ),
        )
    } else {
        // Regular sacrifice (no mana ability)
        if (origId != newId) {
            annotations.add(AnnotationBuilder.objectIdChanged(origId, newId, affectorId))
        }
        annotations.add(AnnotationBuilder.zoneTransfer(newId, srcZone, destZone, category.label, affectorId = affectorId))
    }
}
```

Remove `TransferCategory.Sacrifice` from the catch-all `when` arm (lines 325-329).

- [ ] **Step 5: Update manaPaidForgeCardIds in StateMapper**

In `StateMapper.kt` (~line 164), suppress CardTapped for sacrificed mana sources too:

```kotlin
val castSpellManaForgeIds = events
    .filterIsInstance<GameEvent.SpellCast>()
    .flatMap { it.manaPayments.map { mp -> mp.sourceForgeCardId } }
    .toSet()
val sacrificedManaForgeIds = events.filterIsInstance<GameEvent.ManaAbilityActivated>()
    .filter { ma -> events.any { it is GameEvent.CardSacrificed && it.forgeCardId == ma.forgeCardId } }
    .map { it.forgeCardId }
    .toSet()
val manaPaidForgeCardIds = castSpellManaForgeIds + sacrificedManaForgeIds
```

(Rename the existing `val manaPaidForgeCardIds` to `val castSpellManaForgeIds` to avoid shadowing.)

- [ ] **Step 6: Build and run existing tests**

```bash
just build && just test-one TreasureTokenTest
```

Expected: existing Treasure test still passes (no regression).

- [ ] **Step 7: Commit**

```bash
git add matchdoor/src/main/kotlin/leyline/game/AnnotationPipeline.kt
git add matchdoor/src/main/kotlin/leyline/game/StateMapper.kt
git commit -m "feat(matchdoor): detect disappeared token sacrifices + emit mana bracket

Tokens sacrificed for mana (Treasure) are cleaned up by SBAs before
the state snapshot, making them invisible to zone transfer detection.

New: detectZoneTransfers post-pass compares previousZones against
current gameObjects to find disappeared objects. For sacrificed tokens
with ManaAbilityActivated events, synthesizes AppliedTransfer with
full mana payment records.

Sacrifice branch in annotationsForTransfer now emits:
AbilityInstanceCreated -> TappedUntapped -> ObjectIdChanged ->
ZoneTransfer(Sacrifice) -> UserActionTaken(4) -> ManaPaid ->
AbilityInstanceDeleted.

Refs #119"
```

---

## Task 5: Unit test the new Sacrifice+mana annotation path

**Files:**
- Modify: `matchdoor/src/test/kotlin/leyline/game/AnnotationPipelineTest.kt` (or create if missing)

- [ ] **Step 1: Find or create the pipeline test file**

```bash
ls matchdoor/src/test/kotlin/leyline/game/AnnotationPipelineTest.kt 2>/dev/null || echo "needs creation"
```

If it exists, read it first. If not, create it following the existing `PurePipelineTest` or `CategoryFromEventsTest` pattern.

- [ ] **Step 2: Write a unit test for Sacrifice with mana payment**

Test `annotationsForTransfer` directly with a constructed `AppliedTransfer` that has `category=Sacrifice` and a non-empty `manaPayments`:

```kotlin
test("Sacrifice with mana payment emits full mana-ability bracket") {
    val transfer = AnnotationPipeline.AppliedTransfer(
        origId = 100,
        newId = 200,
        category = TransferCategory.Sacrifice,
        srcZoneId = ZoneIds.BATTLEFIELD,
        destZoneId = ZoneIds.P1_GRAVEYARD,
        grpId = 95104,  // Treasure token
        ownerSeatId = 1,
        affectorId = 0,
        manaPayments = listOf(
            AnnotationPipeline.ManaPaymentRecord(
                landInstanceId = 100,
                manaAbilityInstanceId = 200100,
                color = 8,  // Red
                abilityGrpId = 183,
                spellInstanceId = 300,
            ),
        ),
    )

    val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)

    // Verify annotation types in order
    val types = annotations.map { it.typeList.first() }
    types shouldContainExactly listOf(
        AnnotationType.AbilityInstanceCreated,
        AnnotationType.TappedUntappedPermanent,
        AnnotationType.ObjectIdChanged,
        AnnotationType.ZoneTransfer_af5a,
        AnnotationType.UserActionTaken,
        AnnotationType.ManaPaid,
        AnnotationType.AbilityInstanceDeleted,
    )

    // Verify ManaPaid references the spell
    val manaPaid = annotations.first { AnnotationType.ManaPaid in it.typeList }
    manaPaid.affectorId shouldBe 100        // Treasure (origId)
    manaPaid.affectedIdsList shouldContain 300  // spell instanceId
}
```

- [ ] **Step 3: Write a unit test for regular Sacrifice (no mana)**

```kotlin
test("Sacrifice without mana payment emits standard annotations") {
    val transfer = AnnotationPipeline.AppliedTransfer(
        origId = 100,
        newId = 200,
        category = TransferCategory.Sacrifice,
        srcZoneId = ZoneIds.BATTLEFIELD,
        destZoneId = ZoneIds.P1_GRAVEYARD,
        grpId = 12345,
        ownerSeatId = 1,
    )

    val (annotations, _) = AnnotationPipeline.annotationsForTransfer(transfer, actingSeat = 1)
    val types = annotations.map { it.typeList.first() }
    types shouldContainExactly listOf(
        AnnotationType.ObjectIdChanged,
        AnnotationType.ZoneTransfer_af5a,
    )
}
```

- [ ] **Step 4: Run the tests**

```bash
just test-one AnnotationPipelineTest
```

Expected: both tests pass.

- [ ] **Step 5: Write unit test for detectZoneTransfers post-pass**

Test the pure `detectZoneTransfers` overload directly with constructed events. Verify that when `ManaAbilityActivated` + `CardSacrificed` + `SpellCast(manaPayments=[treasure])` all fire, the Sacrifice transfer gets `manaPayments` and the CastSpell transfer has the Treasure removed from its `manaPayments`.

```kotlin
test("detectZoneTransfers attaches mana payment to Sacrifice and strips from CastSpell") {
    // Setup: Treasure (forgeId=10) on battlefield sacrificed, spell (forgeId=20) cast
    // Treasure was at instanceId=100 (zoneId=28), spell was at instanceId=200 (zoneId=31)
    val treasureObj = GameObjectInfo.newBuilder()
        .setInstanceId(100).setGrpId(95104).setZoneId(37) // now in graveyard
        .setOwnerSeatId(1).build()
    val spellObj = GameObjectInfo.newBuilder()
        .setInstanceId(200).setGrpId(12345).setZoneId(29) // now on stack
        .setOwnerSeatId(1).build()
    val zones = listOf(/* battlefield, stack, graveyard zone protos */)
    val previousZones = mapOf(100 to 28, 200 to 31) // BF, Hand

    val events = listOf(
        GameEvent.ManaAbilityActivated(forgeCardId = 10, seatId = 1, produced = "R"),
        GameEvent.CardSacrificed(forgeCardId = 10, seatId = 1),
        GameEvent.CardTapped(forgeCardId = 10, tapped = true),
        GameEvent.SpellCast(
            forgeCardId = 20, seatId = 1,
            manaPayments = listOf(GameEvent.ManaPayment(sourceForgeCardId = 10, color = 8)),
        ),
    )

    val result = AnnotationPipeline.detectZoneTransfers(
        gameObjects = listOf(treasureObj, spellObj),
        zones = zones,
        events = events,
        previousZones = previousZones,
        forgeIdLookup = { iid -> if (iid == 100) 10 else if (iid == 200) 20 else null },
        idAllocator = { forgeId ->
            InstanceIdRegistry.IdReallocation(InstanceId(forgeId * 10), InstanceId(forgeId * 10 + 1))
        },
        idLookup = { forgeId -> InstanceId(forgeId * 10) },
    )

    val sacrifice = result.transfers.first { it.category == TransferCategory.Sacrifice }
    sacrifice.manaPayments.shouldHaveSize(1)
    sacrifice.manaPayments.first().color shouldBe 8

    val castSpell = result.transfers.first { it.category == TransferCategory.CastSpell }
    castSpell.manaPayments.shouldBeEmpty() // Treasure removed — handled by Sacrifice
}
```

Note: you'll need to construct minimal `ZoneInfo` protos for battlefield/stack/graveyard. Follow existing pure pipeline test patterns.

- [ ] **Step 6: Run all tests**

```bash
just test-one AnnotationPipelineTest
```

Expected: all 3 tests pass.

- [ ] **Step 7: Commit**

```bash
git add matchdoor/src/test/kotlin/leyline/game/AnnotationPipelineTest.kt
git commit -m "test(matchdoor): unit tests for Sacrifice mana-ability annotations

Tests annotationsForTransfer directly:
- Sacrifice+mana: full 7-annotation bracket in correct order
- Sacrifice (no mana): standard ObjectIdChanged + ZoneTransfer
Tests detectZoneTransfers post-pass:
- Mana payment moves from CastSpell to Sacrifice transfer

Refs #119"
```

---

## Task 6: Integration test — verify full Treasure sacrifice annotations

**Files:**
- Modify: `matchdoor/src/test/kotlin/leyline/conformance/TreasureTokenTest.kt`

- [ ] **Step 1: Add annotation assertion to existing test**

After the `castSpellByName("Lightning Bolt")` call in the existing test, capture messages and verify the Sacrifice frame contains the mana-ability bracket:

```kotlin
// --- After Lightning Bolt cast: verify Treasure sacrifice annotations ---
val sacrificeMsgs = h.allMessages.filter { msg ->
    msg.hasGameStateMessage() && msg.gameStateMessage.annotationsList.any { ann ->
        ann.detailsList.any { d -> d.key == "category" && "Sacrifice" in d.valueStringList }
    }
}
sacrificeMsgs.shouldNotBeEmpty()

val sacrificeAnnotations = sacrificeMsgs.first().gameStateMessage.annotationsList
val types = sacrificeAnnotations.map { it.typeList.first() }

// Must contain the mana-ability bracket annotations
types shouldContain AnnotationType.AbilityInstanceCreated
types shouldContain AnnotationType.TappedUntappedPermanent
types shouldContain AnnotationType.UserActionTaken
types shouldContain AnnotationType.ManaPaid
types shouldContain AnnotationType.AbilityInstanceDeleted
```

Add imports: `import io.kotest.matchers.collections.shouldContain` and `import io.kotest.matchers.collections.shouldNotBeEmpty`.

- [ ] **Step 2: Run the test**

```bash
just test-one TreasureTokenTest
```

Expected: PASS — the mana bracket annotations are now present.

- [ ] **Step 3: Commit**

```bash
git add matchdoor/src/test/kotlin/leyline/conformance/TreasureTokenTest.kt
git commit -m "test(conform): verify Treasure sacrifice emits mana-ability bracket

Asserts AbilityInstanceCreated, TappedUntapped, UserActionTaken,
ManaPaid, AbilityInstanceDeleted present in Sacrifice frame
after Lightning Bolt cast using Treasure mana.

Refs #119"
```

---

## Task 7: Update conformance golden baseline

**Files:**
- Modify: `matchdoor/src/test/resources/golden/conform-sacrifice.json`

- [ ] **Step 1: Re-run conformance pipeline**

```bash
just conform Sacrifice 2026-02-28_09-33-05
```

Inspect output: the diff should show fewer gaps than the baseline captured in Task 1. The remaining diffs (if any) are expected — abilityGrpId=183 resolution depends on #160 (AbilityRegistry).

- [ ] **Step 2: Capture updated golden**

```bash
just conform-golden Sacrifice 2026-02-28_09-33-05
```

- [ ] **Step 3: Run full test gate**

```bash
just test-gate
```

Expected: all green.

- [ ] **Step 4: Format**

```bash
just fmt
```

- [ ] **Step 5: Commit and push**

```bash
git add matchdoor/src/test/resources/golden/conform-sacrifice.json
git commit -m "test(conform): update Sacrifice golden baseline with mana bracket

Captures improved conformance after mana-ability annotations added.
Remaining known diffs: abilityGrpId resolution (see #160).

Refs #119"
```

---

## Task 8: Update docs — catalog + rosetta

**Files:**
- Modify: `docs/catalog.yaml`
- Modify: `docs/rosetta.md`

- [ ] **Step 1: Update catalog.yaml**

Find the Treasure Token or sacrifice entry. Update status from partial/missing to reflect the new annotations. If no entry exists, add one under the appropriate mechanic group.

- [ ] **Step 2: Update rosetta.md**

Add/update entries for:
- `AnnotationType 36 (AbilityInstanceCreated)` — now emitted for mana-sacrifice
- `AnnotationType 37 (AbilityInstanceDeleted)` — now emitted for mana-sacrifice
- `AnnotationType 34 (ManaPaid)` — now emitted for mana-sacrifice (was CastSpell only)
- `AnnotationType 73 (UserActionTaken) actionType=4` — now emitted for mana-sacrifice

- [ ] **Step 3: Commit**

```bash
git add docs/catalog.yaml docs/rosetta.md
git commit -m "docs: update catalog + rosetta for Treasure sacrifice annotations

Refs #119"
```

---

## Known Limitations (not in scope)

- **abilityGrpId=183 for Treasure**: Current resolver only handles basic land abilities. Full resolution requires #160 (AbilityRegistry). The annotation will emit `abilityGrpId=0` until that's done — cosmetic only.
- **Standalone mana activation** (sacrifice Treasure without casting a spell): Not covered — requires a different annotation sequence. This plan only covers the "sacrifice to pay for spell" path documented in #119.
- **Multiple Treasures per spell**: The post-pass handles this correctly (iterates all Sacrifice transfers), but the MANA_ID_BASE counter doesn't account for multiple mana sources. Low priority — correct counter requires global tracking across the GSM.
