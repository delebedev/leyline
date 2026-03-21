# QueuedGameStateMessage CastSpell Split

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split CastSpell GSMs into the real server's triplet pattern — QueuedGSM (spell on stack) → QueuedGSM (empty checkpoint) → GSM (mana + cost details) — driven by a new Forge `GameEventSpellMovedToStack` signal.

**Architecture:** Forge fires `GameEventSpellMovedToStack` when a spell moves to the stack (before cost payment). The collector captures it as `GameEvent.SpellMovedToStack`. `StateMapper.buildFromGame` returns a new `BuildResult` (GSM + metadata flags) instead of bare `GameStateMessage`. BundleBuilder reads the `hasCastSpell` flag and splits the GSM into the triplet, extracting CastSpell annotations into the QueuedGSM. Pure post-processing — the annotation pipeline is unchanged.

**Tech Stack:** Java (Forge event), Kotlin (matchdoor), protobuf, Kotest

**Evidence:** 177 QueuedGSMs across all recordings. Pattern is 100% consistent: CastSpell-only annotations in QueuedGSM, empty checkpoint, then full GSM with mana/mechanic annotations.

---

## File Map

### Forge (signal event)
- Create: `forge/forge-game/src/main/java/forge/game/event/GameEventSpellMovedToStack.java`
- Modify: `forge/forge-game/src/main/java/forge/game/event/IGameEventVisitor.java`
- Modify: `forge/forge-game/src/main/java/forge/game/player/PlaySpellAbility.java:627`

### matchdoor (event wiring + split)
- Modify: `matchdoor/src/main/kotlin/leyline/game/GameEvent.kt` — new sealed variant
- Modify: `matchdoor/src/main/kotlin/leyline/game/GameEventCollector.kt` — visitor
- Modify: `matchdoor/src/main/kotlin/leyline/game/StateMapper.kt` — return `BuildResult` wrapper
- Modify: `matchdoor/src/main/kotlin/leyline/game/BundleBuilder.kt` — triplet split logic
- Modify: all callers of `buildFromGame` / `buildDiffFromGame` to use `BuildResult`

### Tests
- Modify: `matchdoor/src/test/kotlin/leyline/game/AnnotationPipelineTest.kt` — unit test for split
- Modify: `matchdoor/src/test/kotlin/leyline/conformance/TreasureTokenTest.kt` — verify triplet
- Modify: `matchdoor/src/test/resources/golden/conform-sacrifice.json` — recapture

---

## Task 1: Add GameEventSpellMovedToStack to Forge

Mechanical — same pattern as GameEventManaAbilityActivated.

**Files:**
- Create: `forge/forge-game/src/main/java/forge/game/event/GameEventSpellMovedToStack.java`
- Modify: `forge/forge-game/src/main/java/forge/game/event/IGameEventVisitor.java`
- Modify: `forge/forge-game/src/main/java/forge/game/player/PlaySpellAbility.java`

- [ ] **Step 1: Create the event record**

```java
package forge.game.event;

import forge.game.card.CardView;

/** Fired when a spell is placed on the stack, before costs are paid. */
public record GameEventSpellMovedToStack(CardView card) implements GameEvent {
    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String toString() {
        return card + " moved to stack";
    }
}
```

- [ ] **Step 2: Add visitor methods to IGameEventVisitor**

Interface: `T visit(GameEventSpellMovedToStack event);`
Base: `public T visit(GameEventSpellMovedToStack event) { return null; }`

- [ ] **Step 3: Fire in PlaySpellAbility.playAbility()**

After line 627 (`ability.setHostCard(game.getAction().moveToStack(c, ability));`), before line 628 (`ability.changeText();`):

```java
game.fireEvent(new GameEventSpellMovedToStack(CardView.get(ability.getHostCard())));
```

Add import: `import forge.game.event.GameEventSpellMovedToStack;`

- [ ] **Step 4: Build Forge**

```bash
just install-forge
```

- [ ] **Step 5: Commit**

```bash
cd forge && git add ... && git commit -m "feat: add GameEventSpellMovedToStack signal"
cd .. && git add forge && git commit -m "feat(forge): GameEventSpellMovedToStack for QueuedGSM split

Fires in PlaySpellAbility.playAbility() after moveToStack, before cost
payment. Signals that a spell is on the stack — downstream uses this
to split the GSM into QueuedGSM (spell) + GSM (costs).

Refs #119"
```

---

## Task 2: Wire GameEvent.SpellMovedToStack through collector

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/GameEvent.kt`
- Modify: `matchdoor/src/main/kotlin/leyline/game/GameEventCollector.kt`

- [ ] **Step 1: Add sealed variant**

In GameEvent.kt, after `SpellCast`:

```kotlin
/** A spell was placed on the stack before costs were paid.
 *  Signals that this GSM should be split into QueuedGSM (spell) + GSM (costs).
 *  Wired from GameEventSpellMovedToStack. */
data class SpellMovedToStack(
    val forgeCardId: Int,
    val seatId: Int,
) : GameEvent
```

- [ ] **Step 2: Add visitor in GameEventCollector**

```kotlin
override fun visit(ev: GameEventSpellMovedToStack) {
    val card = ev.card()
    val seat = seatOf(card.controller) ?: return
    queue.add(GameEvent.SpellMovedToStack(card.id, seat))
    log.debug("event: SpellMovedToStack card={} seat={}", card.name, seat)
}
```

Add import: `import forge.game.event.GameEventSpellMovedToStack`

Also add `is GameEvent.SpellMovedToStack -> {}` to any exhaustive `when` on GameEvent (MechanicClassifier).

- [ ] **Step 3: Build**

```bash
just build
```

- [ ] **Step 4: Commit**

```bash
git add matchdoor/src/main/kotlin/leyline/game/GameEvent.kt \
       matchdoor/src/main/kotlin/leyline/game/GameEventCollector.kt
git commit -m "feat(matchdoor): wire GameEvent.SpellMovedToStack from Forge

Refs #119"
```

---

## Task 3: Return BuildResult from StateMapper

Change `buildFromGame` to return a result wrapper with metadata, so BundleBuilder can decide on framing without inspecting annotation contents.

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/StateMapper.kt`
- Modify: all callers of `buildFromGame` and `buildDiffFromGame`

- [ ] **Step 1: Define BuildResult**

In StateMapper.kt, before the `buildFromGame` function:

```kotlin
/** Result of [buildFromGame] — GSM plus metadata about what happened. */
data class BuildResult(
    val gsm: GameStateMessage,
    /** True if a CastSpell zone transfer was detected (triggers QueuedGSM split). */
    val hasCastSpell: Boolean = false,
)
```

- [ ] **Step 2: Change buildFromGame to return BuildResult**

At the end of `buildFromGame`, detect whether a CastSpell transfer exists:

```kotlin
val hasCastSpell = transferResult.transfers.any { it.category == TransferCategory.CastSpell }
return BuildResult(built, hasCastSpell)
```

Change return type from `GameStateMessage` to `BuildResult`.

- [ ] **Step 3: Change buildDiffFromGame to return BuildResult**

`buildDiffFromGame` calls `buildFromGame` internally. Thread through the `BuildResult`:
- The full state build at line 299 produces a `BuildResult` — extract `.gsm` for diffing but preserve `.hasCastSpell`
- Return `BuildResult(diffGsm, fullResult.hasCastSpell)` at the end

- [ ] **Step 4: Update all callers**

Search for all call sites of `buildFromGame` and `buildDiffFromGame`. Each needs `.gsm` access:
- `BundleBuilder.postAction` — `val gsBase = StateMapper.buildDiffFromGame(...)` → `val result = ...; val gsBase = result.gsm`
- `BundleBuilder.stateOnlyDiff` — same pattern
- `BundleBuilder.remoteActionDiff` — same
- Any other callers (grep for `buildFromGame\|buildDiffFromGame` in matchdoor)

For now, all callers just use `.gsm` — the `hasCastSpell` flag is only consumed in Task 4.

- [ ] **Step 5: Build + run tests**

```bash
just build && ./gradlew :matchdoor:test --rerun 2>&1 | tail -5
```

Expected: 761/761 pass. No behavior change, just signature.

- [ ] **Step 6: Commit**

```bash
git add matchdoor/src/main/kotlin/leyline/game/StateMapper.kt \
       matchdoor/src/main/kotlin/leyline/game/BundleBuilder.kt \
       [any other changed callers]
git commit -m "refactor(matchdoor): StateMapper returns BuildResult with metadata

buildFromGame and buildDiffFromGame now return BuildResult(gsm, hasCastSpell)
instead of bare GameStateMessage. Enables BundleBuilder to decide message
framing based on what happened without inspecting annotation contents.

No behavior change — all callers use .gsm for now.

Refs #119"
```

---

## Task 4: Implement QueuedGSM split in BundleBuilder

The core feature. When `BuildResult.hasCastSpell` is true, split the GSM into the triplet.

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/BundleBuilder.kt`

- [ ] **Step 1: Add splitCastSpellGsm helper**

Pure function that takes a GSM and splits it:

```kotlin
/**
 * Split a CastSpell GSM into the real server's triplet pattern.
 *
 * Returns null if the GSM has no CastSpell ZoneTransfer (caller should
 * send the GSM as-is). Otherwise returns three GSMs:
 *   1. QueuedGSM: CastSpell annotations only (ObjectIdChanged + ZoneTransfer + UserActionTaken(Cast))
 *   2. QueuedGSM: empty checkpoint (0 annotations)
 *   3. GSM: everything else (mana brackets, mechanic annotations, persistent)
 */
fun splitCastSpellGsm(
    gsm: GameStateMessage,
    counter: MessageCounter,
): Triple<GameStateMessage, GameStateMessage, GameStateMessage>? {
    // Find CastSpell annotations: ObjectIdChanged whose new_id matches ZoneTransfer's affectedId,
    // ZoneTransfer(CastSpell), and UserActionTaken(actionType=1).
    val castSpellIdx = gsm.annotationsList.indexOfFirst { ann ->
        ann.detailsList.any { d -> d.key == "category" && "CastSpell" in d.valueStringList }
    }
    if (castSpellIdx < 0) return null

    val castSpellAnn = gsm.annotationsList[castSpellIdx]
    val spellInstanceId = castSpellAnn.affectedIdsList.firstOrNull() ?: return null

    // Collect CastSpell group: the ZoneTransfer + its preceding ObjectIdChanged + UserActionTaken(Cast)
    val castGroup = mutableSetOf<Int>()
    castGroup.add(castSpellIdx)

    // Find ObjectIdChanged whose new_id matches the spell instanceId
    for ((i, ann) in gsm.annotationsList.withIndex()) {
        if (ann.typeList.any { it == AnnotationType.ObjectIdChanged }) {
            val newId = ann.detailsList.firstOrNull { it.key == "new_id" }?.getValueInt32(0)
            if (newId == spellInstanceId) {
                castGroup.add(i)
            }
        }
        // UserActionTaken with actionType=1 (Cast) referencing the spell
        if (ann.typeList.any { it == AnnotationType.UserActionTaken }) {
            val actionType = ann.detailsList.firstOrNull { it.key == "actionType" }?.getValueInt32(0)
            if (actionType == 1 && spellInstanceId in ann.affectedIdsList) {
                castGroup.add(i)
            }
        }
    }

    val castAnnotations = castGroup.sorted().map { gsm.annotationsList[it] }
    val restAnnotations = gsm.annotationsList.filterIndexed { i, _ -> i !in castGroup }

    // GSM 1: QueuedGSM with CastSpell annotations, same state
    val gsId1 = gsm.gameStateId  // already allocated
    val queued1 = gsm.toBuilder()
        .clearAnnotations()
        .addAllAnnotations(castAnnotations)
        .clearPersistentAnnotations()  // persistent goes in main GSM
        .build()

    // GSM 2: empty checkpoint
    val gsId2 = counter.nextGsId()
    val queued2 = gsm.toBuilder()
        .setGameStateId(gsId2)
        .setPrevGameStateId(gsId1)
        .clearAnnotations()
        .clearPersistentAnnotations()
        .build()

    // GSM 3: main GSM with remaining annotations + persistent
    val gsId3 = counter.nextGsId()
    val main = gsm.toBuilder()
        .setGameStateId(gsId3)
        .setPrevGameStateId(gsId2)
        .clearAnnotations()
        .addAllAnnotations(restAnnotations)
        .build()

    return Triple(queued1, queued2, main)
}
```

- [ ] **Step 2: Wire split into postAction**

In `postAction`, after getting the GSM from `buildDiffFromGame`:

```kotlin
val result = StateMapper.buildDiffFromGame(game, nextGs, matchId, bridge, updateType = updateType, viewingSeatId = seatId)
val gsBase = result.gsm

// ... existing action embedding ...
val gs = GsmBuilder.embedActions(gsBase, actions, frame, recipientSeatId = seatId)

// Check for CastSpell split
val split = if (result.hasCastSpell) splitCastSpellGsm(gs, counter) else null

val messages = if (split != null) {
    val (queued1, queued2, main) = split
    listOf(
        makeGRE(GREMessageType.QueuedGameStateMessage, split.first.gameStateId, seatId, counter.nextMsgId()) {
            it.gameStateMessage = queued1
        },
        makeGRE(GREMessageType.QueuedGameStateMessage, queued2.gameStateId, seatId, counter.nextMsgId()) {
            it.gameStateMessage = queued2
        },
        makeGRE(GREMessageType.GameStateMessage_695e, main.gameStateId, seatId, counter.nextMsgId()) {
            it.gameStateMessage = main
        },
        makeGRE(GREMessageType.ActionsAvailableReq_695e, main.gameStateId, seatId, counter.nextMsgId()) {
            it.actionsAvailableReq = actions
            it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.PASS_PRIORITY).build())
        },
    )
} else {
    // No CastSpell — original behavior
    listOf(
        makeGRE(GREMessageType.GameStateMessage_695e, nextGs, seatId, counter.nextMsgId()) {
            it.gameStateMessage = gs
        },
        makeGRE(GREMessageType.ActionsAvailableReq_695e, nextGs, seatId, counter.nextMsgId()) {
            it.actionsAvailableReq = actions
            it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.PASS_PRIORITY).build())
        },
    )
}
```

- [ ] **Step 3: Wire split into stateOnlyDiff**

Same pattern — check `result.hasCastSpell`, split if true.

- [ ] **Step 4: Build + run tests**

```bash
just build && ./gradlew :matchdoor:test --rerun 2>&1 | tail -5
```

Expected: all pass. The split is active but tests may not exercise CastSpell paths.

- [ ] **Step 5: Commit**

```bash
git add matchdoor/src/main/kotlin/leyline/game/BundleBuilder.kt
git commit -m "feat(matchdoor): split CastSpell GSMs into QueuedGSM triplet

When BuildResult.hasCastSpell is true, postAction and stateOnlyDiff
emit three messages matching the real server pattern:
  QueuedGSM (ObjectIdChanged + ZoneTransfer + UserActionTaken(Cast))
  QueuedGSM (empty checkpoint)
  GSM (mana brackets + mechanic annotations + persistent)

Driven by SpellMovedToStack signal — no annotation inspection needed
to decide whether to split.

Refs #119"
```

---

## Task 5: Unit test the split

**Files:**
- Modify: `matchdoor/src/test/kotlin/leyline/game/AnnotationPipelineTest.kt` (or new file for BundleBuilder tests)

- [ ] **Step 1: Test splitCastSpellGsm with CastSpell annotations**

Build a GSM with ObjectIdChanged + ZoneTransfer(CastSpell) + UserActionTaken(Cast) + AbilityInstanceCreated + ManaPaid. Verify the split produces:
- queued1: 3 annotations (ObjIdChanged, ZoneTransfer, UserActionTaken)
- queued2: 0 annotations
- main: 2 annotations (AbilityInstanceCreated, ManaPaid)
- gsId chain: queued2.prevGsId == queued1.gsId, main.prevGsId == queued2.gsId

- [ ] **Step 2: Test splitCastSpellGsm returns null without CastSpell**

Build a GSM with only PlayLand annotations. Verify `splitCastSpellGsm` returns null.

- [ ] **Step 3: Run tests**

```bash
just test-one AnnotationPipelineTest  # or BundleBuilderTest
```

- [ ] **Step 4: Commit**

---

## Task 6: Integration test + conformance verification

**Files:**
- Modify: `matchdoor/src/test/kotlin/leyline/conformance/TreasureTokenTest.kt`
- Modify: `matchdoor/src/test/resources/golden/conform-sacrifice.json`

- [ ] **Step 1: Verify Treasure test still passes**

```bash
./gradlew :matchdoor:test -Pkotest.filter.specs=".*TreasureTokenTest" --rerun
```

- [ ] **Step 2: Add assertion for QueuedGSM message type**

In TreasureTokenTest, after casting Lightning Bolt, verify that at least one message has `GREMessageType.QueuedGameStateMessage` with a CastSpell ZoneTransfer annotation.

- [ ] **Step 3: Run conformance**

```bash
just conform Sacrifice 2026-02-28_09-33-05
```

The diff count should drop significantly — the CastSpell annotations are no longer in the main GSM, matching the recording's split.

- [ ] **Step 4: Recapture golden**

```bash
just conform-golden Sacrifice 2026-02-28_09-33-05
```

- [ ] **Step 5: Full test gate**

```bash
just test-gate
```

- [ ] **Step 6: Commit**

```bash
git add matchdoor/src/test/kotlin/leyline/conformance/TreasureTokenTest.kt \
       matchdoor/src/test/resources/golden/conform-sacrifice.json
git commit -m "test(conform): verify QueuedGSM split + recapture Sacrifice golden

Refs #119"
```

---

## Known Limitations

- **Only CastSpell triggers the split.** Recordings confirm QueuedGSM is never used for PlayLand, Resolve, or other categories. If that changes, the `hasCastSpell` flag generalizes to `splitCategory`.
- **Actions embedded in main GSM only.** The recording puts ActionsAvailableReq after the main GSM, not after the QueuedGSMs. Our implementation follows this.
- **gsId counter consumption.** The triplet uses 3 gsIds instead of 1. This matches the real server but means gsIds advance faster per spell cast.
