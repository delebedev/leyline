# matchdoor

Game engine adapter — translates between Forge engine and Arena client protocol. Where 60%+ of new code lands.

- **Proto:** `src/main/proto/messages.proto` — client protobuf schema
- **Forge coupling is structural:** `WebPlayerController` extends `PlayerControllerHuman` (30+ overrides). `GameBootstrap` constructs Forge `Match`, `Game`, `Deck`. Can't abstract away — it's the adapter layer's job.
- **Proto pervasive:** 62 import sites. Every file touching game state depends on proto. No anti-corruption layer — proto IS the output format.

## Internal Packages

```
bridge/     Forge adapter — WebPlayerController, cost decisions, bootstrap,
            mulligan, deck loading. Extends Forge classes directly.

game/       State mapping, annotations, proto builders, card data.
            Pure translation: Forge state → Arena protobuf.
  mapper/   Per-domain mappers (actions, objects, players, zones, stops).

match/      Match orchestration — MatchHandler, MatchSession, FamiliarSession,
            combat, targeting, mulligan, puzzle handlers. Entry point for
            client messages. Two session types: MatchSession (human, full
            game logic) and FamiliarSession (read-only mirror, no-op actions).
```

ArchUnit enforces: bridge → game → match (no reverse deps within the module).

## Mental Model

**Outbound (engine → client):** Forge `Game` → `StateMapper.buildFromGame()` snapshots zones/objects/players → `GameEventCollector.drainEvents()` feeds `AnnotationBuilder.categoryFromEvents()` for transfer categories → `annotationsForTransfer()` builds per-event proto annotations → `BundleBuilder` assembles GRE messages (Diff/Full GSM + ActionsAvailableReq) → `MessageSink` → Arena client.

**Inbound (client → engine):** Arena proto (`PerformActionResp`, `DeclareAttackersResp`, etc.) → `MatchHandler` dispatches unconditionally to session (`SessionOps`) → `MatchSession` translates to `PlayerAction` or prompt response → submits through `GameActionBridge.submitAction()` or `InteractivePromptBridge.submitResponse()` (both `CompletableFuture.complete()`) → engine thread unblocks. `FamiliarSession` no-ops all action methods.

**Session types:** `MatchHandler` creates `MatchSession` (human, full game logic) or `FamiliarSession` (read-only mirror) based on `clientId` suffix. No `isFamiliar` boolean gates — the type system enforces the constraint.

**Per-seat GamePlayback:** Each seat gets its own `GamePlayback` instance (via `bridge.playbacks[SeatId]`). Each fires on the EventBus when the OTHER player acts (`isRemoteActing()`). 1vAI: seat 1 only. PvP: both seats. Delivers animated opponent diffs with the same animation fidelity as AI turns.

**Threading:** Engine runs on a dedicated daemon thread, blocks on `CompletableFuture.get()` at every priority stop / prompt. `MatchSession` receives client messages on Netty I/O thread, completes the future. All session entry points synchronized on `sessionLock`. Timeout = engine blocked waiting for a response MatchSession never submitted.

**Event-driven annotations:** Forge fires `GameEvent` on its Guava EventBus → `GameEventCollector` (subscribes synchronously on engine thread) translates to `GameEvent` sealed variants → queued in `ConcurrentLinkedQueue` → `StateMapper` drains at diff-build time → `AnnotationBuilder.categoryFromEvents()` picks most-specific category (LandPlayed > ZoneChanged) → builder methods construct proto `AnnotationInfo` with correct Arena type numbers and detail keys.

**Three-stage diff pipeline:** (1) `detectZoneTransfers` → `TransferResult` — realloc instanceIds, return patched objects/zones + deferred retirements/zone recordings. (2) `annotationsForTransfer` — pure function, proto annotations per transfer. (3) `combatAnnotations` — damage/life/phase annotations. All numbered after assembly.

**Pipeline purity:** `buildFromGame` follows gather/compute/apply phases. The COMPUTE section has zero `bridge.*` mutations — all annotation pipeline stages (`detectZoneTransfers`, `combatAnnotations`, `mechanicAnnotations`, `effectAnnotations`, `computeBatch`) are pure functions taking function params (`idResolver`, `previousZones`, `lifeTotals`) instead of `GameBridge`. A delegating bridge-param overload wraps each for backward compat. Test new annotation logic with constructed data via `PurePipelineTest` (~0.01s), not engine startup (~3s). See `docs/notes/2026-03-21-rich-hickey-review.md` for design rationale.

**Diff strategy: snapshot-compare, not incremental tracking.** `buildDiffFromGame` rebuilds a full GSM from engine state and compares it against the previous baseline (proto equality). This is O(all objects) per diff (~50 cards, <1ms) but eliminates an entire class of bugs: no dirty flags to forget, no change lists to keep in sync with engine state. The engine is opaque (Forge doesn't expose change sets), so the alternative would be a parallel bookkeeping system — two sources of truth that can diverge. Snapshot-compare is correct by construction.

## Cost Data Flow

Mana cost reaches the client through two paths depending on the action type. `ManaColorMapping` is the single source of truth for Forge→Arena color translation in both paths.

| Action type | Cost source | Why |
|---|---|---|
| Regular cast (hand) | `computeEffectiveCost(sa, player)` via `CostAdjustment` | Includes static reductions + commander tax; falls back to `CardData.manaCost` in naive mode |
| Alt cost / flashback / escape / zone cast | `computeEffectiveCost(sa, player)` via `CostAdjustment` | Handles commander tax, raise costs, and reductions |
| Adventure cast | `computeEffectiveCost(adventureSa, player)` | Adventure face SA with full cost adjustments |
| Activated ability | `SpellAbility.payCosts.totalMana` + `abilityGrpId` | Ability cost ≠ card cast cost; `abilityGrpId` links to modal UI |

**Decision rule:** Use `computeEffectiveCost(sa, player)` for all cast actions — it chains `CostAdjustment.adjust(Cost)` (commander tax + raises) and `CostAdjustment.adjust(ManaCostBeingPaid)` (static reductions). Falls back to `CardData.manaCost` only in naive mode (no SpellAbility available). Activated abilities still use raw `SA.payCosts` (no reduction path needed yet).

**Payment:** `WebCostDecision` visitor pattern — extends Forge's `CostDecisionMakerBase`, routes interactive cost decisions (sacrifice, tap creatures for convoke, etc.) through `InteractivePromptBridge`.

## Cookbook

### Adding a new annotation type

1. `game/GameEventCollector` — subscribe to Forge `GameEvent`, emit `GameEvent`
2. `game/GameEvent.kt` — add sealed variant with forge card IDs (not instanceIds)
3. `game/AnnotationBuilder` — add builder method matching Arena annotation type number + detail keys (reference: decompiled client annotation system)
4. `game/StateMapper` annotation pipeline — wire event into annotation generation (either transfer-based or standalone in `buildFromGame`)
5. Test: unit test in `AnnotationBuilderTest`, category test in `CategoryFromEventsTest`

### Adding a new zone transition category

1. `game/TransferCategory.kt` — add variant if needed (with `.label` matching Arena's reason string)
2. `game/GameEventCollector` — ensure the right Forge event is captured (e.g. `GameEventCardDestroyed` → `CardDestroyed`)
3. `game/AnnotationBuilder.categoryFromEvents()` — add match arm; specific events take priority over generic `ZoneChanged`
4. `game/StateMapper.annotationsForTransfer()` — add `when` branch for the new category (ObjectIdChanged, ZoneTransfer, etc.)
5. Test: `CategoryFromEventsTest` for event→category mapping, conformance test for full proto output

### Adding a new client action handler

1. `match/MatchSession` — add handler method (e.g. `onDeclareAttackers`)
2. Translate Arena proto fields to Forge `PlayerAction` or prompt response (instanceId → forgeCardId via `bridge.getForgeCardId()`)
3. Submit through appropriate bridge: `GameActionBridge` for priority actions, `InteractivePromptBridge` for engine-initiated choices
4. Wire handler in `match/MatchHandler` message dispatch (match on `ClientMessageType`)
5. Test: `MatchFlowHarness` test exercising the full production path (zero reimplemented logic)

### Debugging a test timeout

1. Read the timeout log — `BridgeTimeoutDiagnostic` auto-captures phase, stack, priority holder, and engine thread trace on every timeout
2. If engine thread is in a bridge's `CompletableFuture.get()`: `MatchSession` handler isn't wiring through, or isn't translating the proto correctly
3. If engine thread is elsewhere (e.g. desktop `Input` class): unimplemented `WebPlayerController` override — needs bridge integration
4. Check phase in diagnostic: combat phases need combat-specific handlers (`onDeclareAttackers` etc.), not just `onPerformAction`

### Debugging a proto conformance failure

See `docs/conformance/debugging.md` — covers annotation ordering, category codes, instanceId lifecycle, gsId chain, detail key types, diff vs full, and triage flow.

## WebPlayerController Override Reference

37 overrides of `PlayerControllerHuman`. Methods not listed here (~130) inherit from PCHuman and route through `WebGuiGame` automatically.

### Game Loop

| Override | Bridge | Description |
|---|---|---|
| `chooseSpellAbilityToPlay` | GameAction | Main priority loop — notify state, await client action, return spell or null (pass) |
| `playChosenSpellAbility` | — | Resolve chosen spell via `PlaySpellAbility` (costs, targets, mana) |
| `playSpellAbilityNoStack` | — | Direct-resolve triggered/replacement abilities via `AbilityUtils.resolve` |
| `declareAttackers` | GameAction | Await attacker declaration from client, wire into `Combat` |
| `declareBlockers` | GameAction | Await blocker assignments from client, wire into `Combat` |
| `assignCombatDamage` | Dedicated future | Manual damage distribution — blocks on `pendingDamageAssignment` future |

### Decision / Choice

| Override | Bridge | Description |
|---|---|---|
| `chooseSingleEntityForEffect` | Interactive | Pick one entity (tutor search, legend rule, generic) |
| `chooseEntitiesForEffect` | Interactive | Pick multiple entities for an effect |
| `chooseCardsForEffect` | Interactive | Generic card selection for spell/ability effects |
| `chooseBinary` | Interactive | Two-option choice (heads/tails, tap/untap, play/draw, etc.) |
| `chooseColor` | Interactive | Pick a mana color from available options |
| `chooseModeForAbility` | Interactive | Modal spell/ability mode selection (charms, commands) |
| `willPutCardOnTop` | Interactive | Top-or-bottom library placement |
| `chooseStartingPlayer` | — | Auto-choose self (variant-only, no prompt) |

### Confirm

| Override | Bridge | Description |
|---|---|---|
| `confirmAction` | Interactive | Generic yes/no confirmation |
| `confirmTrigger` | Dedicated future | Optional trigger — routes through `pendingOptionalAction` for GRE type 45 |
| `confirmPayment` | Interactive | Cost payment confirmation |
| `confirmReplacementEffect` | Interactive | Replacement effect yes/no |
| `confirmStaticApplication` | — | Auto-decline `AlternativeDamageAssignment` (Arena never sends this) |

### Discard

| Override | Bridge | Description |
|---|---|---|
| `chooseCardsToDiscardFrom` | Interactive | Discard selection (also handles reveal-choose: Duress, Thoughtseize) |
| `chooseCardsToDiscardToMaximumHandSize` | Interactive | End-of-turn hand size discard |
| `chooseCardsToDiscardUnlessType` | Interactive | Discard-unless-type prompt (reveal matching type or discard) |
| `chooseCardsToRevealFromHand` | Interactive | Select cards from hand to reveal |

### Sacrifice / Destroy

| Override | Bridge | Description |
|---|---|---|
| `choosePermanentsToSacrifice` | Interactive | Select permanents to sacrifice |
| `choosePermanentsToDestroy` | Interactive | Select permanents to destroy |

### Cost

| Override | Bridge | Description |
|---|---|---|
| `getCostDecisionMaker` | Interactive | Returns `WebCostDecision` — visitor for interactive cost choices (sac, tap, etc.) |
| `payManaCost` | — | Delegates to `PlaySpellAbility.payManaCost` |
| `applyManaToCost` | — | AI mana payment via `ComputerUtilMana` |
| `chooseCardsForCost` | Interactive | Card selection for cost payment (exile, discard as cost) |
| `chooseNumberForKeywordCost` | Interactive | Numeric keyword cost (strive, multikicker count) |
| `chooseOptionalCosts` | — | Kicker/buyback — reads stashed indices from `TargetingHandler` |
| `chooseCardsForConvokeOrImprovise` | Interactive | Tap creatures/artifacts to reduce mana cost |
| `payCostToPreventEffect` | Dedicated future | Shock land pay-life — routes through `pendingOptionalAction` |

### Targeting

| Override | Bridge | Description |
|---|---|---|
| `selectTargetsInteractively` | Interactive | Bridge-based target selection (players + cards), auto-resolve single mandatory |

### Ordering / Library

| Override | Bridge | Description |
|---|---|---|
| `arrangeForScry` | Interactive | Scry N — top/bottom split + ordering |
| `arrangeForSurveil` | Interactive | Surveil N — top/graveyard split + ordering |
| `orderMoveToZoneList` | Interactive | Order cards entering a zone |
| `reveal` | — | Capture revealed card IDs for annotation pipeline + hand reveal tracking |

### Mulligan

| Override | Bridge | Description |
|---|---|---|
| `mulliganKeepHand` | Mulligan | Block until client submits keep/mulligan decision |
| `tuckCardsViaMulligan` | Mulligan | Block until client chooses cards to put back (London mulligan) |

### Other

| Override | Bridge | Description |
|---|---|---|
| `isAI` | — | Returns `false` (human player) |
