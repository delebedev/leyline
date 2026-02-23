# Reflections

Post-fix notes: what could we have figured out faster?

## 1.1 Countered Spell (fizzled SpellResolved -> Countered category)

**Bug:** `AnnotationBuilder.categoryFromEvents()` returned `Resolve` for fizzled spells. `SpellResolved` match on line 31 returned immediately without checking `hasFizzled`.

**Fix:** Check `ev.hasFizzled` before returning `Resolve`; set `zoneCategory = Countered` for fizzled spells so the deferred-priority logic picks it up.

**How to find faster:** The `SpellCountered` NexusGameEvent variant exists but is never emitted — dead code smell. A unit test asserting `categoryFromEvents` returns `Countered` when given a `SpellResolved(fizzled=true)` event would have caught this immediately. The existing `CategoryFromEventsTest` only covered the happy path.

**Rule:** When adding a new event variant, always add both positive and negative category tests (especially for boolean flags like `hasFizzled`).

## 1.2 SBA Deaths (ZeroToughness / Damage / Deathtouch)

**No bug found.** All three SBA death paths (zero toughness, lethal damage, deathtouch damage) correctly produce `Destroy` category annotations via the `GameEventCardChangeZone` → `CardDestroyed` collector path.

**Key insight:** Zero-toughness SBA bypasses Forge's `destroy()` method (uses `sacrificeDestroy()` directly), so `GameEventCardDestroyed` never fires. But `GameEventCardChangeZone` still fires BF→GY, and the collector correctly emits `CardDestroyed` for that zone pair. The annotation pipeline is agnostic to the Forge-level distinction.

**How to find faster:** Reading `GameAction.checkStateEffects()` lines 1466-1596 immediately reveals the two code paths (noRegCreats vs desCreats). The zone-change event is the only reliable signal across both paths.
