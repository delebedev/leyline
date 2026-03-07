# Sealed Implementation — Process Reflections

Captured during sealed format implementation (2026-03-07). Raw notes for later streamlining.

## What was slow

1. **Hand-built wire stubs vs golden diff.** Built `InventoryInfo` stub by hand, got field types wrong (`Changes` as string not array, `Cosmetics`/`Vouchers`/`CustomTokens` as arrays not objects). The golden was right there — should have programmatically diffed our output shape against it before starting the server.

2. **Stale bytecode after fix.** After fixing `buildJoinResponse`, `just serve` ran old compiled code. Debug log never appeared. Always `./gradlew compileKotlin` before restarting.

3. **Exploratory UI when targeted check sufficed.** Phase 2.5 golden playtest already validated the wire shapes. Phase 3 smoke test should have been "join sealed, check Player.log" — not a full UI walkthrough.

4. **Late Player.log check.** Spent time guessing from server logs and UI behavior. Player.log had the exact deserialization error. Check it immediately on any client error.

5. **`just seed-db` before `just serve`.** Without seeded player.db, playerId=null and all CourseService handlers silently fall through to golden stubs. Burned a full join→build→start cycle before noticing.

6. **`./gradlew jar` not just `compileKotlin`.** Classpath uses jars. New matchdoor classes aren't on classpath until jars rebuilt. Got `ClassNotFoundException` at runtime.

7. **Sealed deck lives in Course, not Deck table.** `matchmaking.startMatch()` calls `decks.findById()` which won't find course decks. EnterPairing for sealed needs a different path — look up course deck by event, not by deckId.

8. **Pack opening flow has UI steps.** Sealed join shows: "Open packs" button → pack reveal animation → rare summary → "Continue" button → deck builder. Must handle these intermediate screens (not just click blindly at center).

## Rules to internalize

- **Conformance-first for wire builders:** before smoke testing, diff builder output against golden programmatically. Catches type mismatches without touching Arena.
- **Player.log is the oracle:** first thing to check on any client-side failure.
- **`./gradlew jar` before `just serve`:** compileKotlin is not enough — classpath uses jars.
- **`just seed-db` before first smoke test:** null playerId silently skips all course logic.
- **Golden = ground truth for types:** when building a stub response, extract field types from golden, don't guess from memory.
- **Sealed deck path is separate from constructed:** course decks are stored in Course entity, not Deck table. Match start for sealed events needs course-aware lookup.

## Phase 2 reflections (conformance + match result)

### What worked well

1. **`just fd-search` / `just fd-response` tooling.** Finding the TMT sealed recording and extracting per-endpoint golden responses was fast. The proxy recording had every sealed endpoint captured.

2. **Golden-driven wire fixing.** Compared `LossDetailsDisplay` field by field — found `{Games: 3}` vs our `{PlayUntilEventEnds}`. One line fix, immediate visible result (loss counter appeared).

3. **Match result callback was clean.** `onMatchComplete` lambda through MatchSession → MatchHandler → LeylineServer → CourseService. No cross-module imports needed.

### What was slow

9. **"Event blade doesn't open" — wrong click target.** Clicked event title text instead of tile image area above it. OCR shows text coords but the clickable area is the card art ~40px above. Burned time OCR-retrying.

10. **Conformance report via subagent was slow but thorough.** 4 minutes for analysis, but found real issues (LossDetailsDisplay, SelectedDeckWidget, EventState). Trade-off: could have done targeted field-by-field `just fd-response` comparison faster for known endpoints.

11. **Non-zero-only field pattern.** Golden omits `CurrentWins` when 0, includes when non-zero. Same for `CurrentLosses`. Built the conditional wrong initially (always emitted), had to re-check golden to match pattern.

### Rules to add

- **Use `just fd-response` for conformance:** targeted, fast, exact field comparison. Reserve subagent for broad unknown-shape analysis.
- **Non-zero-only is common in Arena wire:** `CurrentWins`, `CurrentLosses`, many fields omitted when default. Check golden for absence patterns.
- **Event tile click = image area, not text label:** same deck thumbnail pattern. Click 40-80px above the text.

## Process improvements — what to change for next feature

### 1. Conformance-first workflow (biggest lever)
Current: build stub → smoke test → client error → Player.log → fix → rebuild → retry.
Better: `just fd-response <cmd>` → diff our builder output against golden → fix mismatches → THEN smoke test.
A `just sealed-conformance` recipe comparing wire shapes against recording would catch 80% of issues in seconds, no Arena needed.

### 2. `just fd-response` as step 1 for any new handler
Before writing a single line of wire builder code, extract the golden shape. Pattern: `just fd-response <cmd> | jq keys` → match that shape → test. Not a debugging fallback — the starting point.

### 3. Arena wire omits default values
`CurrentWins: 0` not sent. `CurrentLosses: 0` not sent. `EventState` not sent (we invented it). Convention: always check golden for field ABSENCE, not just presence. Fields present in golden = emit. Fields absent in golden = omit.

### 4. Proxy-record our own sealed flow
TMT recording from real servers was invaluable. We should `just serve-proxy` our own sealed flow periodically to compare our shapes vs real. Catches drift early.

### 5. Deck editing not yet tested
"Sealed Deck" button renders (SelectedDeckWidget wired). Edit→save→play cycle untested. Next priority for sealed polish.
