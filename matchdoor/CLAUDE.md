# matchdoor

Game engine adapter — translates between Forge engine and the client protocol. Most new code lands here.

- **Proto:** `src/main/proto/messages.proto` — client protobuf schema
- **Forge coupling is structural:** `PlayerController` extends `PlayerControllerHuman` (30+ overrides). `GameBootstrap` constructs Forge `Match`, `Game`, `Deck`. Can't abstract away — it's the adapter layer's job.
- **Proto pervasive:** 62 import sites. Every file touching game state depends on proto. No anti-corruption layer — proto IS the output format.

## Internal Packages

```
bridge/             Forge adapter — the thread/process frontier with Forge.
  forge/            Forge inheritance seams — the only place matchdoor extends
                    Forge classes (PlayerController, HeadlessGuiBase,
                    ClientGuiGame, CostDecision).
  handoff/          CompletableFuture-based bridges. Engine thread blocks here
                    until the session thread completes the future
                    (GameActionBridge, InteractivePromptBridge, MulliganBridge,
                    OptionalActionGate, PromptJournal, PromptSideEffect).
  coord/            Engine-thread orchestration split off from PlayerController
                    (PriorityLoopCoordinator, GameLoopController, GameLoopPoller,
                    CostPaymentCoordinator, TargetingCoordinator, SpellExecutor).
  bootstrap/        Match + deck setup (GameBootstrap, DeckLoader, DeckConverter,
                    FormatService).
  types/            Pure-data shared types (Ids, PriorityDecision, PrioritySignal,
                    PhaseStopProfile, MulliganPhase, ClientAutoPassState, BridgeDto).
  (root)            Cross-cutting utilities — CardLookup, ResourceResolver,
                    BridgeTimeoutDiagnostic, PlayableActionQuery.

game/               Engine state → client protobuf.
  snapshot/         Captured engine state (GsmSnapshot, SnapshotCapture, per-area
                    snapshots).
  state/            Session-lifetime mutable state + contracts (GameBridge,
                    BridgeContracts, BridgeMutations, EffectTracker,
                    PersistentAnnotationStore, InstanceIdRegistry, AbilityRegistry,
                    LimboTracker, RevealProxyTracker, TokenIdentityRegistry,
                    DiffSnapshotter).
  event/            Engine → typed GameEvent (GameEvent, GameEventCollector).
  mapper/           Snapshot → proto pipeline (StateMapper +
                    Action/Object/Player/Zone mappers + helpers).
  annotations/      Annotation pipeline stages 1–5 (AnnotationBuilder,
                    AnnotationConstants, ZoneTransferDetector,
                    TransferAnnotations, CombatAnnotations, MechanicAnnotations,
                    TransferCategory(Resolver), AnnotationOrderEnforcer,
                    AnnotationLossReason, AbilityWordScanner).
  bundle/           GRE bundle assembly (BundleBuilder, BundleCursor, GsmBuilder,
                    RequestBuilder, InvariantChecker, MessageCounter).
  data/             Card repository + parsing (CardData, CardDataParsing,
                    CardProtoBuilder, CardRepository, ExposedCardRepository,
                    AbilityIdDeriver).
  codes/            Arena protocol code tables + static mappings (CounterTypes,
                    DetailKeys, KeywordGrpIds, KeywordQualifications,
                    ManaColorMapping, QualificationType, SlotLayout).
  generator/        Pre-match generators (DraftPackGenerator, SealedPoolGenerator,
                    PuzzleSource).
  (root)            GamePlayback.

match/              Match orchestration — MatchHandler, MatchSession, FamiliarSession,
                    combat, targeting, mulligan, puzzle handlers. Entry point for
                    client messages. Two session types: MatchSession (human, full
                    game logic) and FamiliarSession (read-only mirror, no-op actions).
```

ArchUnit enforces: bridge → game → match (no reverse deps within the module).

## Mental Model

Read `docs/forge-api-concepts.md` before changing Forge-facing code. Short version: Forge owns rules; `PlayerController` callbacks are the blocking interaction surface; `SpellAbility` is often a chain; use shared cast/cost helpers; use events for causes and snapshots for current truth.

**Outbound (engine → client):** Forge `Game` → `StateMapper.buildFromGame()` snapshots zones/objects/players → `GameEventCollector.drainEvents()` feeds `TransferCategoryResolver.categoryFromEvents()` for transfer categories → `annotationsForTransfer()` builds per-event proto annotations → `BundleBuilder` assembles GRE messages (Diff/Full GSM + ActionsAvailableReq) → `MessageSink` → client.

**Inbound (client → engine):** client proto (`PerformActionResp`, `DeclareAttackersResp`, etc.) → `MatchHandler` dispatches unconditionally to session (`SessionOps`) → `MatchSession` translates to `PlayerAction` or prompt response → submits through `GameActionBridge.submitAction()` or `InteractivePromptBridge.submitResponse()` (both `CompletableFuture.complete()`) → engine thread unblocks. `FamiliarSession` no-ops all action methods.

**Session types:** `MatchHandler` creates `MatchSession` (human, full game logic) or `FamiliarSession` (read-only mirror) based on `clientId` suffix. No `isFamiliar` boolean gates — the type system enforces the constraint.

**Per-seat GamePlayback:** Each seat gets its own `GamePlayback` instance (via `bridge.playbacks[SeatId]`). Each fires on the EventBus when the OTHER player acts (`isRemoteActing()`). 1vAI: seat 1 only. Paired flow: both seats. Delivers animated opponent diffs with the same animation fidelity as AI turns.

**Threading:** Engine runs on a dedicated daemon thread, blocks on `CompletableFuture.get()` at every priority stop / prompt. `MatchSession` receives client messages on Netty I/O thread, completes the future. All session entry points synchronized on `sessionLock`. Timeout = engine blocked waiting for a response MatchSession never submitted.

**Event-driven annotations:** Forge fires `GameEvent` on its Guava EventBus → `GameEventCollector` (subscribes synchronously on engine thread) translates to `GameEvent` sealed variants → queued in `ConcurrentLinkedQueue` → `StateMapper` drains at diff-build time → `TransferCategoryResolver.categoryFromEvents()` picks most-specific category (LandPlayed > ZoneChanged) → builder methods construct proto `AnnotationInfo` with the expected type numbers and detail keys.

**Five-stage annotation pipeline** (4 files, each an `object` in `game/`): (1) `ZoneTransferDetector.detectZoneTransfers` → `TransferResult` — realloc instanceIds, patched objects/zones, stack ability lifecycle. (2) `TransferAnnotations.annotationsForTransfer` — pure function, proto annotations per transfer. (3) `CombatAnnotations.combatAnnotations` — damage/life/phase annotations. (4) `MechanicAnnotations.mechanicAnnotations` — counters, tokens, attachments, controller change. (5) `MechanicAnnotations.effectAnnotations` — P/T boosts, keyword grants from EffectTracker. All numbered after assembly.

**Pipeline purity:** `buildFromGame` follows gather/compute/apply phases. The COMPUTE section has zero `bridge.*` mutations — all annotation pipeline stages (`detectZoneTransfers`, `combatAnnotations`, `mechanicAnnotations`, `effectAnnotations`, `computeBatch`) are pure functions taking function params (`idResolver`, `previousZones`, `lifeTotals`) instead of `GameBridge`. A delegating bridge-param overload wraps each for backward compat. Test new annotation logic with constructed data via `PurePipelineTest` (~0.01s), not engine startup (~3s).

**Diff strategy: snapshot-compare, not incremental tracking.** `buildDiffFromGame` rebuilds a full GSM from engine state and compares it against the previous baseline (proto equality). This is O(all objects) per diff (~50 cards, <1ms) but eliminates an entire class of bugs: no dirty flags to forget, no change lists to keep in sync with engine state. The engine is opaque (Forge doesn't expose change sets), so the alternative would be a parallel bookkeeping system — two sources of truth that can diverge. Snapshot-compare is correct by construction.

**Per-frame card view: `BoundCard`.** `SnapshotCapture.bindCards` pairs every `CardSnapshot` with its static `CardData` and pre-resolves the static-derived live questions: alt-cost ability rows (`altCosts: List<AltCostBinding>`), Mobilize cleanup grpId (`mobilizeCleanup`), parent linkage (`parentLinkage: ParentLinkage?`), and card-state designations (`designations: DesignationSet`). Mappers read `snap.boundCards[fid]?.X` instead of reaching back into `bridge.cardRepository`.

**Residual `bridge.cardRepository` consumers, by design:**
- `SnapshotCapture` + `GrpIdResolver` — the binder.
- `BundleBuilder` — engine-state bind on out-of-snapshot paths.
- `ActionPerformer` — action-execution time, no per-frame snap in scope.
- `GameEventCollector` — event stream, no per-card snap in scope.
- `SourceAbilityResolverFactory` — factory abstraction, not card-bound.
- `GamePlayback.shouldSplitOnLocalTurn` — event-driven, no per-frame snap.
- `ActionMapper`'s `cardDataLookup` lambdas in `buildActionList`, `buildAutoTapSolution`, `buildActivateManaAction` — `(GrpId) -> CardData?` abstraction is grpId-keyed, not forgeCardId-keyed; the cost solver queries any grpId, not just the carrying card's.
- Name-keyed lookups: `TargetingHandler.sendCastingTimeOptionsReq` (modal options by card name), `StateMapper.buildTargetSpecAnnotations` (target spec by spell name), `ZoneTransferDetector`'s `grpIdResolver` (token grpId by Forge card name) — BoundCard is keyed on `ForgeCardId`, not name.

`GsmSnapshot.objects` is a memoized derived view of `boundCards.mapValues { it.snapshot }`; `boundCards` is the single source of truth and `equals`/`hashCode` ignore the derived projection.

**TokenIdentityRegistry stays load-bearing.** The cross-frame `instanceId → grpId` cache guards against Forge state that mutates between diff ticks — a token's `tokenSpawningAbility` can detach (source sacrificed, host bounced) and a copied token's source `Card` can stop being legally resolvable while the token instance lives on. The registry's "first write wins" pin keeps the wire grpId stable through those transitions.

## Cost Data Flow

Mana cost reaches the client through two paths depending on the action type. `ManaColorMapping` is the single source of truth for Forge→client color translation in both paths.

| Action type | Cost source | Why |
|---|---|---|
| Regular cast (hand) | `computeEffectiveCost(sa, player)` via `CostAdjustment` | Includes static reductions + commander tax; falls back to `CardData.manaCost` in naive mode |
| Alt cost / flashback / escape / zone cast | `computeEffectiveCost(sa, player)` via `CostAdjustment` | Handles commander tax, raise costs, and reductions |
| Adventure cast | `computeEffectiveCost(adventureSa, player)` | Adventure face SA with full cost adjustments |
| Activated ability | `SpellAbility.payCosts.totalMana` + `abilityGrpId` | Ability cost ≠ card cast cost; `abilityGrpId` links to modal UI |

**Decision rule:** Use `computeEffectiveCost(sa, player)` for all cast actions — it chains `CostAdjustment.adjust(Cost)` (commander tax + raises) and `CostAdjustment.adjust(ManaCostBeingPaid)` (static reductions). Falls back to `CardData.manaCost` only in naive mode (no SpellAbility available). Activated abilities still use raw `SA.payCosts` (no reduction path needed yet).

**Payment:** `CostDecision` visitor pattern — extends Forge's `CostDecisionMakerBase`, routes interactive cost decisions (sacrifice, tap creatures for convoke, etc.) through `InteractivePromptBridge`.

## Cookbook

### Adding a new annotation type

1. `game/GameEventCollector` — subscribe to Forge `GameEvent`, emit `GameEvent`
2. `game/GameEvent.kt` — add sealed variant with forge card IDs (not instanceIds)
3. `game/AnnotationBuilder` — add builder method matching annotation type number + detail keys (reference: protocol documentation)
4. `game/StateMapper` annotation pipeline — wire event into annotation generation (either transfer-based or standalone in `buildFromGame`)
5. Test: unit test in `AnnotationBuilderTest`, category test in `CategoryFromEventsTest`

### Adding a new zone transition category

1. `game/TransferCategory.kt` — add variant if needed (with `.label` matching client's reason string)
2. `game/GameEventCollector` — ensure the right Forge event is emitted (e.g. `GameEventCardDestroyed` → `CardDestroyed`)
3. `game/TransferCategoryResolver.categoryFromEvents()` — add match arm; specific events take priority over generic `ZoneChanged`
4. `game/StateMapper.annotationsForTransfer()` — add `when` branch for the new category (ObjectIdChanged, ZoneTransfer, etc.)
5. Test: `CategoryFromEventsTest` for event→category mapping, conformance test for full proto output

### Adding a new client action handler

1. `match/MatchSession` — add handler method (e.g. `onDeclareAttackers`)
2. Translate client proto fields to Forge `PlayerAction` or prompt response (instanceId → forgeCardId via `bridge.getForgeCardId()`)
3. Submit through appropriate bridge: `GameActionBridge` for priority actions, `InteractivePromptBridge` for engine-initiated choices
4. Wire handler in `match/MatchHandler` message dispatch (match on `ClientMessageType`)
5. Test: `MatchFlowHarness` test exercising the full production path (zero reimplemented logic)

### Debugging a test timeout

1. Read the timeout log — `BridgeTimeoutDiagnostic` auto-captures phase, stack, priority holder, and engine thread trace on every timeout
2. If engine thread is in a bridge's `CompletableFuture.get()`: `MatchSession` handler isn't wiring through, or isn't translating the proto correctly
3. If engine thread is elsewhere (e.g. desktop `Input` class): unimplemented `PlayerController` override — needs bridge integration
4. Check phase in diagnostic: combat phases need combat-specific handlers (`onDeclareAttackers` etc.), not just `onPerformAction`

### Debugging a proto shape failure

Check nearby tests and mapper/annotation code for annotation ordering, category codes, instanceId lifecycle, gsId chain, detail key types, diff vs full, and triage flow.

## PlayerController

The full pattern (single-inheritance constraint, coordinator / helper structure, state-ownership rules, anti-patterns, and the decision tree for adding a new override) lives in the `PlayerController` class KDoc. Read the class, not a standalone doc.

Shape invariants to know:

- **47 overrides, pinned by `PlayerControllerStructureTest`.** Adding or removing one requires updating the test and the table below in the same commit.
- **Cross-class state stays on the class.** `pendingOptionalAction`, `pendingDamageAssignment`, `damageAssignCache`, `autoPassState`, `recentDecisions` have external readers (`GameBridge`, `CombatHandler`, `OptionalActionHandler`, `DebugServer`, `MatchFlowHarness`).
- **Prompt side-effects flow through `PromptJournal`.** `InteractivePromptBridge.journal` carries typed `PromptSideEffect` entries (`SearchedToHand`, `LegendVictim`, `RevealStarted`/`RevealEnded`, `OptionalCostStash`); producers record, consumers (`GameEventCollector`, `CostPaymentCoordinator`, `StateMapper`) drain. `promptJustResolved` lives on `PrioritySignal`. Reveal proxy IDs are encapsulated as `GameBridge.revealProxies: RevealProxyTracker`.
- **The `pendingOptionalAction` future lifecycle belongs to `OptionalActionGate`.** The three override sites (`confirmTrigger`, `playSaFromPlayEffect`, `payCostToPreventEffect`) delegate to `gate.await(...)`.

### Override reference

All 47 overrides, by concern. "Bridge" column names the primary mechanism each uses.

**Priority loop.** Uses `GameActionBridge`.

| Override | Description |
|---|---|
| `chooseSpellAbilityToPlay` | Main priority window — notify state, await client action, return spell or null (pass) |
| `declareAttackers` | Await attacker declaration, wire into `Combat` |
| `enlistAttackers` | Return attackers whose DeclareAttackers option selected Enlist |
| `declareBlockers` | Await blocker assignments, wire into `Combat` |
| `assignCombatDamage` | Manual damage distribution — blocks on `pendingDamageAssignment` future |

**Spell resolution.** Drives `PlaySpellAbility` / `AbilityUtils` paths.

| Override | Description |
|---|---|
| `playChosenSpellAbility` | Resolve chosen spell (costs, targets, mana) |
| `playSpellAbilityNoStack` | Direct-resolve triggered/replacement abilities |
| `playSaFromPlayEffect` | Optional-cast prompt (Madness, Cascade) — `OptionalActionGate` |
| `chooseModeForAbility` | Modal spell/ability mode selection (charms, commands) |

**Targeting and entity choice.** Uses `InteractivePromptBridge`.

| Override | Description |
|---|---|
| `chooseSingleEntityForEffect` | Pick one entity (tutor search, legend rule, generic) |
| `chooseEntitiesForEffect` | Pick multiple entities |
| `chooseCardsForEffect` | Generic card selection for spell/ability effects |
| `chooseCardsToRevealFromHand` | Select cards from hand to reveal |
| `selectTargetsInteractively` | Target selection (players + cards), auto-resolve single mandatory |
| `chooseSomeType` | Pick a subtype via static SelectN |
| `reveal` | Capture revealed card IDs for the annotation pipeline (both card-list overloads) |

**Binary confirmations.** Uses `InteractivePromptBridge` or `OptionalActionGate`.

| Override | Description |
|---|---|
| `confirmAction` | Generic yes/no confirmation |
| `confirmTrigger` | Optional trigger — `OptionalActionGate` (GRE type 45) |
| `confirmPayment` | Cost payment confirmation |
| `confirmReplacementEffect` | Replacement effect yes/no |
| `confirmStaticApplication` | Auto-decline `AlternativeDamageAssignment` (client never sends this) |
| `chooseBinary` | Two-option choice (heads/tails, tap/untap, play/draw, etc.) |
| `chooseColor` | Pick one color via static SelectN |
| `chooseColors` | Pick one or more colors via static SelectN |
| `willPutCardOnTop` | Top-or-bottom library placement |
| `chooseStartingPlayer` | Auto-choose self (variant-only, no prompt) |

**Discard and sacrifice.**

| Override | Description |
|---|---|
| `chooseCardsToDiscardFrom` | Discard selection (also handles reveal-choose: Duress, Thoughtseize) |
| `chooseCardsToDiscardToMaximumHandSize` | End-of-turn hand size discard |
| `chooseCardsToDiscardUnlessType` | Discard-unless-type prompt (reveal matching type or discard) |
| `choosePermanentsToSacrifice` | Select permanents to sacrifice |
| `choosePermanentsToDestroy` | Select permanents to destroy |

**Cost payment.**

| Override | Description |
|---|---|
| `getCostDecisionMaker` | Returns `CostDecision` — visitor for interactive cost choices |
| `payManaCost` | Delegates to `PlaySpellAbility.payManaCost` |
| `applyManaToCost` | AI mana payment via `ComputerUtilMana` |
| `chooseCardsForCost` | Card selection for cost payment (exile, discard as cost) |
| `chooseNumberForKeywordCost` | Numeric keyword cost (strive, multikicker count) |
| `chooseOptionalCosts` | Kicker/buyback — reads stashed indices from `TargetingHandler` |
| `chooseCardsForConvokeOrImprovise` | Tap creatures/artifacts to reduce mana cost |
| `payCostToPreventEffect` | Shock land pay-life — `OptionalActionGate` |

**Zone ordering.**

| Override | Description |
|---|---|
| `arrangeForScry` | Scry N — top/bottom split + ordering |
| `arrangeForSurveil` | Surveil N — top/graveyard split + ordering |
| `orderMoveToZoneList` | Order cards entering a zone |

**Mulligan.** Uses `MulliganBridge`.

| Override | Description |
|---|---|
| `mulliganKeepHand` | Keep/mulligan decision |
| `tuckCardsViaMulligan` | London mulligan — choose cards to put back |

**Other.**

| Override | Description |
|---|---|
| `isAI` | Returns `false` (human player) |
