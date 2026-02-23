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

## Item 6 — Reveal Pipeline

**New feature.** Reveal annotations (RevealedCardCreated, type 59) now fire when the engine reveals cards. Architecture:

1. **forge-web**: `WebPlayerController.reveal()` override intercepts the `PlayerController.reveal(CardCollectionView, ZoneType, Player, String, boolean)` call. Captures forge card IDs and pushes them to `InteractivePromptBridge.revealQueue` (new `ConcurrentLinkedQueue<RevealRecord>`).

2. **forge-nexus**: `StateMapper.buildFromGame()` drains `bridge.promptBridge.drainReveals()` alongside `bridge.drainEvents()`, converts to `NexusGameEvent.CardsRevealed`. `AnnotationPipeline.mechanicAnnotations()` produces `RevealedCardCreated` transient annotations.

**Key insight:** Forge has NO `GameEvent` for reveals — the engine communicates reveals through `PlayerController.reveal()` → `IGuiGame.reveal()` (a GUI callback chain, not EventBus). The 36+ call sites in `GameAction.reveal()` all route through this chain. Overriding at the controller level is the natural injection point without touching forge-game.

**Not yet implemented:** `RevealedCardDeleted` (type 60, when reveal display ends), populating Revealed zones (18/19) with temporary `GameObjectInfo` entries of `ObjectType.RevealedCard = 8`. These are cosmetic — the client handles missing deletions gracefully (card just stays visible until next GSM).

**Design decision:** Capture mechanism uses `InteractivePromptBridge` as the conduit (already accessible from both forge-web controller and forge-nexus StateMapper). Alternative was adding a callback interface — simpler to use the existing bridge object.

**Files touched:** InteractivePromptBridge.kt (revealQueue + RevealRecord), WebPlayerController.kt (reveal override), NexusGameEvent.kt (CardsRevealed variant), AnnotationBuilder.kt (revealedCardCreated/Deleted factories), AnnotationPipeline.kt (wiring), StateMapper.kt (drain), MechanicClassifier.kt (reveal tag), RevealAnnotationTest.kt (new, 3 tests).

---

## Architecture Review — What Makes Strong Game Server Architecture

Meta-knowledge distilled from the forge-nexus/forge-web review. Reference for future architectural discussions.

### Principle 1: Separate Game Logic from Transport (the Transport-Head pattern)

The single most impactful architectural decision in forge-nexus: the game engine's integration layer (bridges, player controller, game loop) is transport-agnostic. Both the web UI (WebSocket/JSON) and Arena client (TCP/protobuf) are "transport heads" on the same orchestration layer.

**Why it works:** `CompletableFuture` doesn't know what completes it. The engine blocks and waits. The transport layer — whatever it is — provides the answer. This means:
- New transports are additive, not duplicative
- Engine features automatically work for all transports
- The bridge contract is testable without any transport

**Counter-pattern:** per-transport game logic, or game logic that assumes a specific serialization format. Once `Game → DTO → ProtoMessage` exists, you've baked in a round-trip. Go `Game → ProtoMessage` directly (which nexus does).

**Generalized rule:** The bridge layer should speak in domain types (`PlayerAction`, `PromptRequest`, `List<Int>`), never in wire types (`JsonObject`, `ByteBuf`, `GeneratedMessageV3`). Wire types belong exclusively to the transport head.

### Principle 2: Authoritative State + Deterministic Diff > Streaming Mutations

The engine owns authoritative state. The transport layer computes diffs by comparing snapshots. This is strictly better than streaming mutations because:
- Diffs are derivable from any two snapshots (crash recovery, reconnect, spectator join)
- Snapshot + diff = full client state at any point (debugging, replay)
- No ordering bugs from interleaved mutation streams
- Client can always resync by requesting a Full GSM

**The trap:** trying to stream fine-grained mutations from the engine ("card moved to zone X", "life changed by -3"). This creates ordering dependencies, missing-event bugs, and makes reconnection hard. Snapshot-then-diff eliminates all of these.

**forge-nexus implementation:** `StateMapper.buildFromGame()` takes a full snapshot. `buildDiffFromGame()` computes delta against previous snapshot. Annotations are derived from events, but the *state* is always authoritative from the snapshot.

### Principle 3: Single-Owner Counters, Never Shared-Mutate

Every counter (gsId, msgId, annotationId) must have exactly one writer. Two writers with `max()` sync is a patch, not a solution. Learned the hard way (Learnings §1, §4):
- gsId lived in SessionOps AND NexusGamePlayback → self-referential gsIds
- The fix was monotonic `updateAndGet { maxOf(it, value) }` — correct but fragile

**Generalized rule:** If two threads need a counter, one thread owns it and the other reads it. If both need to increment it, the architecture is wrong — merge the increment points into a single path.

### Principle 4: Test at the Right Abstraction Layer

Four-layer testing is expensive to build but compounds:

| Layer | What | Speed | Catches |
|-------|------|-------|---------|
| Unit | Pure logic (annotation builders, category classification) | ~1s | Logic bugs, edge cases |
| Conformance | Wire shape vs known-good patterns | ~5s | Structural regressions, missing fields |
| Integration | Full engine boot + scripted actions | ~90s | Threading bugs, lifecycle issues |
| Recording | vs real server traffic | manual | Protocol misunderstandings |

**Key insight:** Most game server projects only have integration tests. This means every test is slow (90s+), and when something breaks, the failure is far from the root cause. Unit tests on the annotation pipeline catch 80% of issues in 1s.

**The MatchFlowHarness pattern:** Integration tests that exercise the *full production path* (zero reimplemented logic) are worth the 90s. Tests that stub out the engine to run faster often miss the bugs that matter (threading, lifecycle, counter sync). Choose: either full production path (slow, high-value) or pure-logic unit test (fast, targeted). The middle ground (partial mocks) catches neither category well.

### Principle 5: Observability is Architecture, Not Tooling

Debug server, SSE event stream, state timeline, instance history, recording introspection, cross-session mechanic manifests — these aren't afterthoughts, they're load-bearing architectural components.

**Why:** A protocol bridge is reverse-engineering. You discover requirements by comparing your output against a reference implementation. Without observability, every bug requires adding temporary logging, reproducing, reading logs, removing logging. With observability, the data is always there.

**Generalized rule:** For any system where the specification is incomplete or adversarial (protocol bridges, game servers, distributed systems), invest in observability infrastructure *before* the feature work. It pays for itself within the first week.

### Principle 6: Event-Driven Annotation > State-Derived Annotation

Two ways to know "this card was destroyed":
1. **State-derived:** Compare snapshots, see card was on BF, now in GY. Infer "destroyed."
2. **Event-driven:** Engine fires `GameEventCardDestroyed`. Collector captures it. Pipeline produces annotation.

Event-driven is strictly better because:
- Events carry *why* (destroyed vs sacrificed vs exiled) — state diffs only show *what*
- Events arrive in causal order — diffs lose ordering within a batch
- Events can distinguish simultaneous state changes (two creatures dying in combat)
- Events are the engine's own language — less room for inference errors

**The hybrid approach (what forge-nexus does):** Events for annotation *categories* (the "why"), snapshots for annotation *content* (the "what"). `categoryFromEvents()` picks the reason; `annotationsForTransfer()` builds the proto from the snapshot. Best of both worlds.

### Principle 7: The Snapshot Timing Invariant

> Snapshot exactly once per sent message, and only after the diff has been computed from the old baseline.

Violating this causes:
- Snapshot before send → diff baseline advanced → next diff misses objects client never received
- Snapshot twice → self-referential gsId
- No snapshot → next diff includes stale baseline objects

**Generalized rule:** Snapshot management is the single hardest part of a diff-based game server. Make it a first-class architectural concept, not a side-effect buried in method chains. The `DiffSnapshotter` abstraction is good; the dual-timeline divergence (diff baseline vs client awareness) is the remaining pain point.

### Principle 8: Formalizing the Reuse Boundary

When Module B reuses Module A's internals, the reuse boundary must be:
1. **Documented** — which classes are the API surface, which are internal
2. **Narrow** — minimal import count (forge-nexus: 10 classes from forge-web)
3. **Domain-typed** — shared types are domain concepts, not wire formats
4. **One-directional** — A never imports B (forge-web never imports forge-nexus)

If the boundary starts leaking (more than ~15 imports, or wire types crossing), extract a shared module. Don't wait until it's painful — the extraction cost grows superlinearly with coupling depth.

### Anti-patterns Observed (and avoided)

- **DTO round-trip:** `Game → GameStateDto → GameStateMessage` was explicitly rejected in the bridge-architecture doc. Direct `Game → GameStateMessage` via StateMapper. This is correct — intermediate DTOs exist for the web UI's needs, not the engine's.
- **God Object accumulation:** StateMapper grew to 869 LOC through incremental feature additions. Each addition was small and reasonable. The fix isn't preventing additions — it's periodic refactoring into pipeline stages.
- **Test mode flags:** `CardDb.testMode` is the classic antipattern of production code knowing about tests. Fix: dependency injection. The production code should be test-unaware; the test provides a different configuration.
