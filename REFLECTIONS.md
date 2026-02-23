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

## 2.1 SelectNReq Handler Plumbing

**No bug; new feature.** Full request/response path wired: MatchHandler dispatches `SelectNresp` → MatchSession.onSelectN() → TargetingHandler.onSelectN() maps instanceIds back to prompt indices → submits to engine.

Outbound path also added: StateMapper.buildSelectNReq() → BundleBuilder.selectNBundle() builds GS Diff + SelectNReq proto message.

Current behavior: the auto-pass engine still auto-resolves "choose_cards" prompts with `defaultIndex` before the client sees SelectNReq. To send it to the client instead, modify `checkPendingPrompt()` to detect choose_cards + candidateRefs and call `sendSelectNReq()` instead of auto-resolving.

**How to find faster:** The SelectTargetsReq/Resp pattern is the template. Every new prompt type follows the same 6-file pattern: PromptIds → StateMapper → BundleBuilder → TargetingHandler → MatchSession → MatchHandler. Could create a checklist/template for this.

## 2.2 PayCostsReq Plumbing

**Outbound-only.** PayCostsReq (type 36) is server→client; the response comes back as PerformActionResp (already handled). Added BundleBuilder.payCostsBundle() for when we want to show the Arena client's native mana payment UI instead of auto-solving.

**Current behavior:** Engine's AI mana solver + WebCostDecision handles payment automatically. PayCostsReq is cosmetic — lets the client display its tap-land animation properly.

## 2.3 Reveal Annotations (RevealedCardCreated/Deleted)

**Deferred — requires forge-game changes.** Arena uses annotation types 59/60 (RevealedCardCreated/Deleted) to animate card reveals. Forge has no `GameEvent` for reveals — the Revealed zone exists and StateMapper maps it, but there's no event-driven signal when cards enter/leave.

**Options:**
1. Add `GameEventCardRevealed` to forge-game's EventBus → subscribe in GameEventCollector → emit annotation. Most correct but invasive (touches a module we don't own).
2. Detect reveals in StateMapper by diffing Revealed zone contents between snapshots. Less invasive but fragile — must track per-player revealed zones and handle transient reveals.

**Moving on** per instructions. The client handles missing reveal annotations gracefully (no animation, card just appears/disappears in revealed zone).

## 2.4 Spell-Forced Discard

**No bug found.** Spell-forced discard (simulating Mind Rot etc.) correctly produces Discard category for Hand→GY. Multiple discards in a batch each get their own Discard annotation. Cross-contamination with SpellResolved events is prevented by forgeCardId matching (same fix pattern as 1.3-1.5).

**Key insight:** The `player.discard()` path fires `GameEventCardChangeZone` Hand→GY which the collector maps to `CardDiscarded`. This is identical to cleanup discard-to-hand-size. No special handling needed for spell-vs-cleanup discard — the zone-pair heuristic in the collector handles both.

## Item 8 — Activate Action Handler

**Bug fix.** `MatchSession.onPerformAction()` had no `Activate_add3` arm — activated abilities from the Arena client were silently converted to PassPriority. Added the handler mapping to `PlayerAction.ActivateAbility(forgeCardId, abilityIndex)`. Also generalized post-action prompt check to trigger after Activate (not just Cast), since targeted activated abilities need the same targeting flow.

**Limitation:** abilityIndex always 0 (first non-mana ability). Multi-ability cards (planeswalkers) need abilityGrpId→index correlation via CardDb. Deferred.

**How to find faster:** The `else` branch logging "unhandled action type" was the clue. Any time an action type falls through to else, it's a missing handler.

## Item 9 — Attachment Annotations

**New feature.** Full attachment annotation pipeline: `GameEventCardAttachment` → `GameEventCollector.visit()` emits `CardAttached`/`CardDetached` → `AnnotationPipeline.mechanicAnnotations()` produces transient `AttachmentCreated` (type 70) + persistent `Attachment` (type 20) → `StateMapper` Stage 4 routes persistent annotations through `nextPersistentAnnotationId()`.

**Design decision:** Extended `mechanicAnnotations()` to return `MechanicAnnotationResult(transient, persistent)` instead of flat `List<AnnotationInfo>`. Follows the same pattern as `annotationsForTransfer()` which already returns `Pair<transient, persistent>`. Cleaner than adding a separate method — all mechanic events go through one pipeline.

**Key insight:** `GameEventCardAttachment.newTarget()` is null for detach, non-null for attach. Cast `newTarget` to `Card` to extract forge ID. Detach events are captured (CardDetached) but not yet wired to annotation deletion — would need a `RemoveAttachment` annotation or persistent annotation removal pipeline. Deferred.

**Files touched:** NexusGameEvent.kt, GameEventCollector.kt, AnnotationBuilder.kt, AnnotationPipeline.kt, StateMapper.kt, MechanicClassifier.kt, AnnotationPipelineTest.kt (adapted for new return type), AttachmentAnnotationTest.kt (new).
