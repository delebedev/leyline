# Kotlinification Plan

Audit date: 2026-02-24
Scope: forge-nexus production + test modules (66 main, 60 test Kotlin files)

---

## 1. Proto Builder DSL ✅

**Status:** Done (2026-02-24)
**Priority:** Highest ROI
**Why:** Cuts test verbosity everywhere; ~10 call sites in MatchFlowHarness alone, more in test files.

**Problem:** Every harness/test method builds proto messages with deeply nested Java-style builders:

```kotlin
val greMsg = ClientToGREMessage.newBuilder()
    .setType(ClientMessageType.PerformActionResp_097b)
    .setPerformActionResp(
        PerformActionResp.newBuilder().addActions(
            Action.newBuilder()
                .setActionType(ActionType.Play_add3)
                .setInstanceId(instanceId)
                .setGrpId(grpId),
        ),
    ).build()
```

**Plan:**

- Create `forge.nexus.protocol.ProtoDsl.kt` (or similar) with builder functions:
  ```kotlin
  fun performAction(block: Action.Builder.() -> Unit): ClientToGREMessage
  fun declareAttackersResp(block: DeclareAttackersResp.Builder.() -> Unit): ClientToGREMessage
  fun declareBlockersResp(block: DeclareBlockersResp.Builder.() -> Unit): ClientToGREMessage
  fun selectTargetsResp(block: SelectTargetsResp.Builder.() -> Unit): ClientToGREMessage
  fun submitAttackersReq(seatId: Int): ClientToGREMessage
  fun submitBlockersReq(seatId: Int): ClientToGREMessage
  ```
- Migrate `MatchFlowHarness.kt` methods: `playLand`, `castCreature`, `passPriority`, `declareAttackers`, `declareBlockers`, `selectTargets`, `castSpellByName`, `declareAllAttackers`, `declareNoBlockers`
- Migrate direct proto construction in `CombatFlowTest`, `TargetingFlowTest`, and any other test files

**Files:**
- New: `src/main/kotlin/forge/nexus/protocol/ProtoDsl.kt` (or `src/test/` if test-only)
- Edit: `MatchFlowHarness.kt`, `CombatFlowTest.kt`, `TargetingFlowTest.kt`

**Verification:** `just test-gate` + `just test-integration`

---

## 2. Collection Operators Sweep

**Priority:** High -- mechanical, big readability win, safe refactor
**Why:** 12+ files use `mutableListOf` + for-loop where `buildList`, `map`, `filter`, `associate`, `mapNotNull`, `flatMap`, `buildMap` would be cleaner and more expressive.

**Plan -- production files:**

| File | Location | Current | Target |
|---|---|---|---|
| `GameStateCollector.kt:172-203` | `extractSnapshot` zone/object loops | `mutableMapOf` + for | `associateBy` / `associate` |
| `GameStateCollector.kt:227-241` | annotation details loop | `mutableMapOf` + for | `associate` with `when` |
| `GameStateCollector.kt:273-326` | `computeDiff` zone/object/player deltas | `mutableListOf` + for | `mapNotNull` |
| `GameStateCollector.kt:353-366` | `diffObject` field comparisons | `mutableMapOf` + if | `buildMap` |
| `RecordingDecoder.kt:349-373` | `summarizeAnnotation` | `mutableMapOf` + for | `associate` |
| `RecordingDecoder.kt:377-406` | `extractActions` | `mutableListOf` + for | `buildList` |
| `SemanticTimeline.kt:113-148` | `extractZoneTransfers` | manual map + re-loop | `associate` on annotation list |
| `SemanticTimeline.kt:189-216` | `diff()` | `mutableListOf` | `buildList` |
| `DebugCollector.kt:115` | `idMap()` | `mutableListOf` + for + `result.add` | `flatMap` + `map` |
| `Inspect.kt:40-53` | `topFields`, `parts` | `mutableListOf` + conditional `+=` | `buildList` |
| `Trace.kt:159-164` | `learnLabels` | `mutableListOf` + loops | `buildList` |
| `SmokeTest.kt:40-42` | `topFields` | `mutableListOf` | `buildList` |

**Plan -- test files:**

| File | Location | Current | Target |
|---|---|---|---|
| `ClientAccumulator.kt:68-77` | `actionInstanceIdsMissingFromObjects` | `mutableListOf` + for | `filter` + `map` |
| `ClientAccumulator.kt:88-103` | `zoneObjectsMissingFromObjects` | `mutableListOf` + for | `flatMap` + `filter` |

**Approach:** One file at a time, run `just test-gate` after each. Pure mechanical refactor -- no behavior change.

**Verification:** `just test-gate` after each file; `just test-full` at end.

---

## 3. GameFlowAnalyzer Classifier Chain

**Priority:** Medium-high -- makes the analyzer extensible and each classifier testable in isolation
**Why:** `classifyAll()` is a 160-line monolith with 15+ `if/continue` blocks. Adding a new segment type means editing a massive function.

**Problem:** `GameFlowAnalyzer.kt:202-363` -- single function with interleaved pattern matching:

```kotlin
fun classifyAll(): List<Segment> {
    // 160 lines of if (matchesGameStart(i)) { ... continue }
    //                if (matchesNewTurn(i)) { ... continue }
    //                ...
}
```

**Plan:**

- Define classifier function type: `(index: Int) -> Pair<Segment, Int>?` (segment + consumed count)
- Extract each pattern block into a named private function: `tryGameStart`, `tryNewTurn`, `tryDraw`, `tryCombat`, `tryPlayLand`, `tryCastCreature`, `tryCastSpell`, `tryTargetedSpell`, `tryPhaseTransition`, `tryGameEnd`, etc.
- Replace monolith with:
  ```kotlin
  private val classifiers = listOf(::tryGameStart, ::tryNewTurn, ::tryDraw, ...)

  fun classifyAll(): List<Segment> = buildList {
      var i = 0
      while (i < fingerprints.size) {
          val (segment, consumed) = classifiers.firstNotNullOfOrNull { it(i) }
              ?: (Segment(MISC, i, i, "misc") to 1)
          add(segment)
          i += consumed
      }
  }
  ```
- Each classifier becomes individually testable

**Also fix:** `groupIntoTimeline` repeated "flush turn" block (3 occurrences) -- extract local `flushTurn()` function.

**Files:** `GameFlowAnalyzer.kt`

**Verification:** `just test-gate` (conformance group exercises this). Compare analyzer output on a sample recording before/after.

---

## 4. DRY Duplications

**Priority:** Medium

### 4a. Duplicated `annotationTypes` extraction (SessionRecorder.kt)

Lines 122-138 and 208-214 have identical logic. Extract:

```kotlin
private fun extractAnnotationTypes(gre: GREToClientMessage): List<String> =
    if (gre.hasGameStateMessage()) {
        gre.gameStateMessage.annotationsList.flatMap { ann ->
            ann.typeList.map { it.name.removeSuffix("_695e").removeSuffix("_aa0d") }
        }
    } else emptyList()
```

**Files:** `SessionRecorder.kt`

### 4b. Duplicated CLI `--seat` parsing (CompareMain.kt, RecordingDecoderMain.kt)

Both files have identical `--seat` flag parsing and seat identification printing. Extract to shared utility:

```kotlin
// In a shared file, e.g. conformance/CliUtils.kt
fun parseSeatFilter(args: List<String>, default: Int = 1): Int? { ... }
fun printSeatIdentification(decoder: RecordingDecoder) { ... }
```

**Files:** `CompareMain.kt`, `RecordingDecoderMain.kt`, new `CliUtils.kt`

### 4c. Repeated "flush turn" block (GameFlowAnalyzer.kt)

Three identical blocks in `groupIntoTimeline`. Extract local function:

```kotlin
fun flushTurn() {
    if (currentTurnSegments.isNotEmpty()) {
        turns.add(Turn(...))
        currentTurnSegments = mutableListOf()
    }
}
```

**Files:** `GameFlowAnalyzer.kt` (covered in item 3, but listed here for completeness)

**Verification:** `just test-gate`

---

## 5. Polish Items

**Priority:** Low -- good cleanup pass, each individually small

### 5a. `assertTrue(x != null)` + `x!!` pattern (TestExtensions.kt:161-165)

```kotlin
// Before:
assertTrue(limboZone != null, "GSM should have Limbo zone")
assertTrue(limboZone!!.objectInstanceIdsList.contains(instanceId), ...)

// After:
val limbo = checkNotNull(limboZone) { "GSM should have Limbo zone" }
assertTrue(instanceId in limbo.objectInstanceIdsList, ...)
```

**Files:** `TestExtensions.kt`

### 5b. `System.exit(1)` --> `exitProcess(1)` (remaining CLIs)

Already fixed in `AnalysisCli.kt`. Still present in:
- `Inspect.kt:16,28`
- `SmokeTest.kt`

**Files:** `Inspect.kt`, `SmokeTest.kt`

### 5c. Stringly-typed `changeType` in `ObjectDelta` (GameStateCollector.kt)

`changeType: String` uses `"added"` / `"removed"` / `"modified"`. Replace with enum:

```kotlin
enum class ChangeType { ADDED, REMOVED, MODIFIED }
```

**Files:** `GameStateCollector.kt`

### 5d. Duplicated actor/card label logic (RecordingCli.kt, RecordingInspector.kt)

Pattern repeated 3x:
```kotlin
it.actor ?: it.actorSeat?.let { seat -> "seat-$seat" } ?: "?"
```

Extract as extension property:
```kotlin
val ActionEvent.actorLabel: String
    get() = actor ?: actorSeat?.let { "seat-$it" } ?: "?"
```

**Files:** `RecordingCli.kt`, `RecordingInspector.kt`

### 5e. `NexusPaths.detectRecordingsRoot()` parent-walking

Manual while loop. Extract extension:

```kotlin
private fun File.walkUpFind(pred: (File) -> Boolean): File? {
    var dir = parentFile
    while (dir != null) { if (pred(dir)) return dir; dir = dir.parentFile }
    return null
}
```

**Files:** `NexusPaths.kt`

### 5f. DebugServer route registration verbosity (DebugServer.kt:44-61)

18 repetitive `srv.createContext` calls. Consolidate:

```kotlin
mapOf(
    "/api/messages" to ::serveMessages,
    "/api/state" to ::serveState,
    ...
).forEach { (path, handler) ->
    srv.createContext(path) { ex -> safe(ex) { handler(ex) } }
}
```

**Files:** `DebugServer.kt`

### 5g. Verbose `Messages.` qualification (GameBridgeTest.kt)

~30 occurrences of `wotc.mtgo.gre.external.messaging.Messages.AnnotationType` etc. inline. Add targeted imports at the top of the file.

**Files:** `GameBridgeTest.kt`

**Verification for all polish items:** `just test-gate`

---

## Already Applied (2026-02-24)

Quick wins applied during audit, all tests green (PASS 259/259):

| File | Change |
|---|---|
| `LimboTracker.kt` | `MutableList` --> `linkedSetOf` (O(1) contains) |
| `CaptureSink.kt` | Manual `copyInto` --> `prev + bytes` |
| `FrontDoorService.kt` | 8x `.Companion.` removal |
| `MatchSession.kt` | 3x `recorder?.X()` chains --> `recorder?.run {}` |
| `MatchHandler.kt` | Same recorder scope-function pattern |
| `AutoPassEngine.kt` | `if`/`if` on `CombatHandler.Signal` --> exhaustive `when` |
| `GameEventCollector.kt` | `drainEvents()` poll loop --> `buildList` |
| `NexusGamePlayback.kt` | `drainQueue()` poll loop --> `buildList` |
| `AnalysisCli.kt` | `System.exit(1)` --> `exitProcess(1)` |

## Not Recommended

- **CompletableFuture --> coroutines:** Threading model (engine blocks on `CF.get()`, Netty IO completes) is correct and well-documented. Coroutines would add complexity without benefit.
- **Test framework swap:** TestNG is established with group-based execution. Not worth churning.
- **`synchronized` blocks --> coroutine mutex:** The `synchronized` usage is minimal and correct for the JDK `HttpServer` / Netty threading context.
