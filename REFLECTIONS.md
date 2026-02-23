# Reflections

Post-fix notes: what could we have figured out faster?

## 1.1 Countered Spell (fizzled SpellResolved -> Countered category)

**Bug:** `AnnotationBuilder.categoryFromEvents()` returned `Resolve` for fizzled spells. `SpellResolved` match on line 31 returned immediately without checking `hasFizzled`.

**Fix:** Check `ev.hasFizzled` before returning `Resolve`; set `zoneCategory = Countered` for fizzled spells so the deferred-priority logic picks it up.

**How to find faster:** The `SpellCountered` NexusGameEvent variant exists but is never emitted — dead code smell. A unit test asserting `categoryFromEvents` returns `Countered` when given a `SpellResolved(fizzled=true)` event would have caught this immediately. The existing `CategoryFromEventsTest` only covered the happy path.

**Rule:** When adding a new event variant, always add both positive and negative category tests (especially for boolean flags like `hasFizzled`).
