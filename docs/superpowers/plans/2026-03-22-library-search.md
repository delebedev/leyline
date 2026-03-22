# Library Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement library search so human players see the search UI, pick a card, and the card arrives in hand with correct transfer category.

**Architecture:** New `ClassifiedPrompt.Search` variant in `PromptClassifier` routes search prompts to `SearchReq`/`SearchResp` handshake instead of auto-resolving. The Forge engine already handles card selection — we just need the UI gate. Transfer category fix distinguishes search-to-hand (`Put`) from draw (`Draw`).

**Tech Stack:** Kotlin, protobuf, Kotest FunSpec

**Wire spec:** `docs/plans/2026-03-21-library-search-wire-spec.md`
**Issue:** #169
**Acceptance puzzle:** `puzzles/library-search-lethal.pzl`

---

## File Structure

| File | Responsibility | Action |
|------|---------------|--------|
| `matchdoor/src/main/kotlin/leyline/bridge/InteractivePromptBridge.kt` | `PromptSemantic` enum | Modify: add `Search` variant |
| `matchdoor/src/main/kotlin/leyline/bridge/WebGuiGame.kt` | Forge→bridge prompt dispatch | Modify: tag search prompts with `PromptSemantic.Search` |
| `matchdoor/src/main/kotlin/leyline/match/PromptClassifier.kt` | Prompt classification | Modify: add `Search` classified type |
| `matchdoor/src/main/kotlin/leyline/match/TargetingHandler.kt` | Prompt→protocol dispatch | Modify: add `sendSearchReq` + `onSearchResp` |
| `matchdoor/src/main/kotlin/leyline/match/MatchHandler.kt` | Client message dispatch | Modify: add `SearchResp` arm |
| `matchdoor/src/main/kotlin/leyline/match/MatchSession.kt` | Session operations | Modify: add `onSearch` |
| `matchdoor/src/main/kotlin/leyline/match/SessionOps.kt` | Session interface | Modify: add `onSearch` |
| `matchdoor/src/main/kotlin/leyline/match/FamiliarSession.kt` | Read-only session | Modify: no-op `onSearch` |
| `matchdoor/src/main/kotlin/leyline/game/BundleBuilder.kt` | GRE message construction | Modify: add `buildSearchReq` |
| `matchdoor/src/main/kotlin/leyline/game/GameEvent.kt` | Event sealed class | Modify: add `sourceForgeCardId` to `CardSearchedToHand` |
| `matchdoor/src/main/kotlin/leyline/game/GameEventCollector.kt` | Forge event→GameEvent | Modify: emit `CardSearchedToHand` |
| `matchdoor/src/main/kotlin/leyline/game/AnnotationBuilder.kt` | Category mapping | Modify: `categoryFromEvents` — `CardSearchedToHand` → `Put` |
| `matchdoor/src/test/kotlin/leyline/match/SearchReqTest.kt` | Integration test | Create |
| `matchdoor/src/test/kotlin/leyline/game/CategoryFromEventsTest.kt` | Category test | Modify: add search-to-hand case |

---

### Task 1: Add PromptSemantic.Search + ClassifiedPrompt.Search

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/bridge/InteractivePromptBridge.kt:257` — add `Search` to enum
- Modify: `matchdoor/src/main/kotlin/leyline/match/PromptClassifier.kt:43-64` — add `Search` variant + classify arm

- [ ] **Step 1: Add `Search` to `PromptSemantic` enum**

In `InteractivePromptBridge.kt`:
```kotlin
enum class PromptSemantic {
    Generic,
    GroupingSurveil,
    GroupingScry,
    ModalChoice,
    SelectNLegendRule,
    Search,  // library search — send SearchReq handshake
}
```

- [ ] **Step 2: Add `ClassifiedPrompt.Search` sealed variant**

In `PromptClassifier.kt`:
```kotlin
data class Search(
    override val pendingPrompt: InteractivePromptBridge.PendingPrompt,
) : ClassifiedPrompt
```

- [ ] **Step 3: Add classify arm before AutoResolve fallback**

In `PromptClassifier.classify()`, before the `else -> AutoResolve` branch:
```kotlin
req.semantic == PromptSemantic.Search -> ClassifiedPrompt.Search(pendingPrompt)
```

- [ ] **Step 4: Build and verify compilation**

Run: `just build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```
feat: add PromptSemantic.Search + ClassifiedPrompt.Search
```

---

### Task 2: Tag search prompts in WebGuiGame

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/bridge/WebGuiGame.kt`

Forge's `ChangeZoneEffect.resolve()` → `player.searchLibraryWithShuffle()` → `PlayerControllerHuman.chooseSingleCardForZoneChange()` delegates to `WebPlayerController.chooseSingleEntityForEffect()` (line 310). The comment at line 365 confirms this delegation.

- [ ] **Step 1: Read `WebPlayerController.chooseSingleEntityForEffect` (line 310-362)**

Understand the current tagging logic. The legend rule check at line ~335 tags `SelectNLegendRule`. Everything else falls to `Generic`.

- [ ] **Step 2: Add search detection heuristic**

In `chooseSingleEntityForEffect`, detect library search by checking `delayedReveal != null` (library searches use delayed reveal to show library contents) or `sa?.api` for search-related API types. Tag with `PromptSemantic.Search`:

```kotlin
// After the existing legend rule check, before the generic fallback:
val isLibrarySearch = delayedReveal != null
val semantic = when {
    isLegendRule -> PromptSemantic.SelectNLegendRule
    isLibrarySearch -> PromptSemantic.Search
    else -> PromptSemantic.Generic
}
```

If `delayedReveal` is not available at this point, check `sa?.api?.name` for `"ChangeZone"` + source zone is Library as an alternative heuristic.

- [ ] **Step 3: Build and verify**

Run: `just build`

- [ ] **Step 4: Commit**

```
feat: tag library search prompts with PromptSemantic.Search
```

---

### Task 3: Build SearchReq message + send it

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/BundleBuilder.kt` — add `buildSearchReq`
- Modify: `matchdoor/src/main/kotlin/leyline/match/TargetingHandler.kt` — add `sendSearchReq` + handle in `checkPendingPrompt`

- [ ] **Step 1: Write failing test**

In `matchdoor/src/test/kotlin/leyline/match/SearchReqTest.kt`:
```kotlin
class SearchReqTest : FunSpec({
    test("library search puzzle completes without timeout") {
        val harness = MatchFlowHarness()
        val result = harness.startPuzzleAtMain1(
            File("puzzles/library-search-lethal.pzl").readText()
        )
        result.gameOver shouldBe true
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `just test-one SearchReqTest`
Expected: FAIL (timeout — SearchReq not sent, engine blocks)

- [ ] **Step 3: Add `buildSearchReq` to BundleBuilder**

```kotlin
fun buildSearchReq(msgId: Int, gsId: Int, seatId: Int): GREToClientMessage {
    return GREToClientMessage.newBuilder()
        .setType(GREMessageType.SearchReq_695e)
        .setMsgId(msgId)
        .setGameStateId(gsId)
        .addSystemSeatIds(seatId)
        .setPrompt(Prompt.newBuilder().setPromptId(1065))
        .setSearchReq(SearchReq.getDefaultInstance())
        .build()
}
```

Wire shows SearchReq is empty proto with `promptId=1065`. No fields populated for basic-land search.

- [ ] **Step 4: Add `sendSearchReq` to TargetingHandler**

```kotlin
private fun sendSearchReq(bridge: GameBridge, pendingPrompt: InteractivePromptBridge.PendingPrompt) {
    val msgId = ops.nextMsgId()
    val gsId = ops.currentGsId()
    val msg = BundleBuilder.buildSearchReq(msgId, gsId, ops.seatId)
    ops.send(msg)
    searchPendingPromptId = pendingPrompt.promptId
    log.info("SearchReq sent, awaiting SearchResp")
}
```

Track the pending state using `PendingClientInteraction` (not a bare field — follow existing pattern):

```kotlin
// In PendingClientInteraction.kt, add:
data class Search(val promptId: String) : PendingClientInteraction
```

In `sendSearchReq`, set: `pendingInteraction = PendingClientInteraction.Search(pendingPrompt.promptId)`

- [ ] **Step 5: Handle Search in checkPendingPrompt**

In `checkPendingPrompt`, add arm before AutoResolve:
```kotlin
is ClassifiedPrompt.Search -> {
    ops.traceEvent(MatchEventType.TARGET_PROMPT, game, "search: ${pendingPrompt.request.message}")
    sendSearchReq(bridge, classified.pendingPrompt)
    PromptResult.SENT_TO_CLIENT
}
```

- [ ] **Step 5b: Handle Search in handlePostCastPrompt**

`handlePostCastPrompt()` (~line 153) handles prompts that fire during spell resolution. Search prompts fire here (spell resolves → triggers search). Without this arm, search prompts silently drop and the engine hangs.

```kotlin
is ClassifiedPrompt.Search -> {
    val game = bridge.getGame() ?: return false
    ops.traceEvent(MatchEventType.TARGET_PROMPT, game, "post-cast search")
    sendSearchReq(bridge, classified.pendingPrompt)
    return true
}
```

- [ ] **Step 6: Build and verify compilation**

Run: `just build`

- [ ] **Step 7: Commit**

```
feat: send SearchReq for library search prompts
```

---

### Task 4: Handle SearchResp from client

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/match/MatchHandler.kt` — dispatch SearchResp
- Modify: `matchdoor/src/main/kotlin/leyline/match/SessionOps.kt` — add `onSearch`
- Modify: `matchdoor/src/main/kotlin/leyline/match/MatchSession.kt` — implement `onSearch`
- Modify: `matchdoor/src/main/kotlin/leyline/match/FamiliarSession.kt` — no-op `onSearch`
- Modify: `matchdoor/src/main/kotlin/leyline/match/TargetingHandler.kt` — `onSearchResp`

- [ ] **Step 1: Add `onSearch` to SessionOps interface**

```kotlin
fun onSearch(greMsg: ClientToGREMessage)
```

- [ ] **Step 2: Implement in MatchSession**

```kotlin
override fun onSearch(greMsg: ClientToGREMessage) = synchronized(sessionLock) {
    val bridge = gameBridge ?: return
    targetingHandler.onSearchResp(bridge) { autoPassEngine.autoPassAndAdvance(it) }
}
```

- [ ] **Step 3: No-op in FamiliarSession**

```kotlin
override fun onSearch(greMsg: ClientToGREMessage) { /* read-only seat */ }
```

- [ ] **Step 4: Add dispatch in MatchHandler**

```kotlin
ClientMessageType.SearchResp_097b -> s?.onSearch(greMsg)
```

- [ ] **Step 5: Implement `onSearchResp` in TargetingHandler**

SearchResp is a pure handshake — the engine already has the card selected. We just need to auto-resolve the pending prompt with the default choice. Follow the `autoPass` callback pattern used by all other response handlers (`onGroupResp`, `onSubmitTargets`, etc.):

```kotlin
fun onSearchResp(bridge: GameBridge, autoPass: (GameBridge) -> Unit) {
    val pending = pendingInteraction as? PendingClientInteraction.Search ?: run {
        log.warn("SearchResp received but no search pending")
        return
    }
    pendingInteraction = null
    val seatBridge = bridge.seat(ops.seatId)
    val prompt = seatBridge.prompt.getPendingPrompt()
    if (prompt != null && prompt.promptId == pending.promptId) {
        seatBridge.prompt.submitResponse(pending.promptId, listOf(prompt.request.defaultIndex))
        bridge.awaitPriority()
    }
    // Send intermediate GSM showing the Library→Hand transfer before continuing
    ops.sendRealGameState(bridge)
    autoPass(bridge)
}
```

- [ ] **Step 6: Run the puzzle test**

Run: `just test-one SearchReqTest`
Expected: PASS (game completes, Lava Axe deals lethal)

- [ ] **Step 7: Commit**

```
feat: handle SearchResp — complete library search handshake
```

---

### Task 5: Fix transfer category Library→Hand for search

**Files:**
- Modify: `matchdoor/src/main/kotlin/leyline/game/GameEvent.kt` — add `CardSearchedToHand`
- Modify: `matchdoor/src/main/kotlin/leyline/game/GameEventCollector.kt` — emit it
- Modify: `matchdoor/src/main/kotlin/leyline/game/AnnotationBuilder.kt` — map to `Put`
- Modify: `matchdoor/src/test/kotlin/leyline/game/CategoryFromEventsTest.kt`

- [ ] **Step 1: Write failing category test**

In `CategoryFromEventsTest.kt`:
```kotlin
test("search to hand uses Put category") {
    val events = listOf(GameEvent.CardSearchedToHand(forgeCardId = 1, sourceForgeCardId = 2))
    val category = AnnotationBuilder.categoryFromEvents(events, Zone.Library, Zone.Hand)
    category shouldBe TransferCategory.Put
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `just test-one CategoryFromEventsTest`
Expected: FAIL — `CardSearchedToHand` doesn't exist

- [ ] **Step 3: Add `CardSearchedToHand` to GameEvent**

```kotlin
data class CardSearchedToHand(
    val forgeCardId: Int,
    val sourceForgeCardId: Int,
) : GameEvent()
```

- [ ] **Step 4: Add category mapping in `AnnotationBuilder.categoryFromEvents`**

```kotlin
is GameEvent.CardSearchedToHand -> TransferCategory.Put
```

- [ ] **Step 5: Emit event in GameEventCollector**

In `GameEventCollector`, subscribe to the Forge event that fires when a card moves from library to hand via search. This is `GameEventCardChangeZone` where the cause is a search effect. The detection heuristic: Library→Hand during spell resolution (not draw step).

Check if Forge fires a specific event type for search, or if it's a generic zone change. If generic, use the pending `PromptSemantic.Search` state as a flag.

- [ ] **Step 6: Run test**

Run: `just test-one CategoryFromEventsTest`
Expected: PASS

- [ ] **Step 7: Commit**

```
fix: Library→Hand search uses Put category instead of Draw
```

---

### Task 6: Arena playtest

- [ ] **Step 1: Run puzzle in Arena**

```bash
just serve-puzzle puzzles/library-search-lethal.pzl
```

Launch Arena, play through: cast Sylvan Ranger → search UI should open → pick Mountain → play Mountain → cast Lava Axe → win.

- [ ] **Step 2: Verify in debug server**

```bash
curl -s http://localhost:8090/api/state
```

Check that the game completed successfully.

- [ ] **Step 3: Check for client errors**

```bash
just scry state
```

Look for GRE errors or stalls.

- [ ] **Step 4: Fix any issues found, iterate**

Common problems:
- Search UI doesn't open → `SearchReq` message shape wrong, check proto fields
- Game stalls after search → `onSearchResp` not auto-resolving the prompt correctly
- Wrong card animation → transfer category still `Draw` instead of `Put`
- Client crash → check `promptId` value, try different values

- [ ] **Step 5: Commit fixes**

```
fix: library search Arena playtest fixes
```

---

### Task 7: Update catalog and close issue

- [ ] **Step 1: Update catalog.yaml**

Change `spells.library-search` status from `missing` to `wired`.

- [ ] **Step 2: Update issue #169**

Add comment with test results and close.

- [ ] **Step 3: Final commit**

```
docs: library search wired, close #169
```

---

## Out of scope (separate issues)

These are documented in the wire spec but not needed for basic functionality:

- **RevealedCard proxy objects + zone 19** — cosmetic reveal animation (#172 overlap)
- **InstanceRevealedToOpponent persistent annotation** — opponent doesn't see revealed card (#172 overlap)
- **Shuffle OldIds/NewIds** — shuffle animation (#42)
- **Non-basic search** (fetch lands, tutors with criteria) — needs SearchReq field population
