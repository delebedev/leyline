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

## 1.3-1.5 Removal Spell Flow (Bounce / Destroy / Exile)

**No bug found.** All three removal categories (Bounce=BF→Hand, Destroy=BF→GY, Exile=BF→Exile) produce correct annotations. The critical cross-contamination test passed: when SpellResolved fires in the same event batch as a target's zone change, `categoryFromEvents` correctly attributes each card independently (forgeCardId match prevents cross-contamination).

**Key insight:** The `ZoneTransitionConformanceTest` already covered these zone pairs via direct GameAction calls. The new tests add value by testing the event-batch interleaving scenario (SpellResolved + target zone change in same drain window). This is the scenario that the fizzled-spell bug could have affected for non-fizzled spells too — but forgeCardId matching prevents it.
