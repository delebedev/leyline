# Sealed Implementation — Process Reflections

Captured during sealed format implementation (2026-03-07). Raw notes for later streamlining.

## What was slow

1. **Hand-built wire stubs vs golden diff.** Built `InventoryInfo` stub by hand, got field types wrong (`Changes` as string not array, `Cosmetics`/`Vouchers`/`CustomTokens` as arrays not objects). The golden was right there — should have programmatically diffed our output shape against it before starting the server.

2. **Stale bytecode after fix.** After fixing `buildJoinResponse`, `just serve` ran old compiled code. Debug log never appeared. Always `./gradlew compileKotlin` before restarting.

3. **Exploratory UI when targeted check sufficed.** Phase 2.5 golden playtest already validated the wire shapes. Phase 3 smoke test should have been "join sealed, check Player.log" — not a full UI walkthrough.

4. **Late Player.log check.** Spent time guessing from server logs and UI behavior. Player.log had the exact deserialization error. Check it immediately on any client error.

## Rules to internalize

- **Conformance-first for wire builders:** before smoke testing, diff builder output against golden programmatically. Catches type mismatches without touching Arena.
- **Player.log is the oracle:** first thing to check on any client-side failure.
- **Compile before serve:** always. Non-negotiable.
- **Golden = ground truth for types:** when building a stub response, extract field types from golden, don't guess from memory.
