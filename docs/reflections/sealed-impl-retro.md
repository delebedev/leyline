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
