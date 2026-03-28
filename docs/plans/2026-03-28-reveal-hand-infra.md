# Reveal Opponent Hand Infrastructure (#256)

## Context

Cards like Duress, Thoughtseize, and Concealing Curtains/Revealing Eye say "target opponent reveals their hand, you choose a card." Currently the engine resolves these internally (AI auto-picks) — the client gets no reveal UI, no choose prompt, and no RevealedCard proxy objects.

This is horizontal infrastructure that unblocks an entire card family. Duress and Concealing Curtains Phase 2 drive the implementation.

**Issue:** #256
**Branch:** `feat/reveal-hand`
**Cards:** Duress (grpId 83792), Concealing Curtains/Revealing Eye (78895/78896)
**Card specs:** `docs/card-specs/duress.md`, `docs/card-specs/concealing-curtains.md`
**Forge internals:** `docs/forge-reveal-internals.md`

## Premise Verification

The issue's claims are verified against recording traces (session 2026-03-27_23-55-23 for Duress, 2026-03-22_23-02-04 for Curtains). The six gaps are real:

1. Revealed zone 19 exists but is always empty — no proxy objects
2. Hand visibility never flips to Public during reveal
3. SelectNReq never sets `unfilteredIds` (proto field 14), never sets `sourceId`
4. `RevealedCardDeleted` builder exists but is never called
5. `chooseCardsViaBridge()` doesn't populate `candidateRefs` — SelectNReq `ids` would be empty
6. No semantic distinction between "discard from own hand" and "choose from revealed opponent hand"

## Key Wire Findings

**Proxy placement (from Duress spec):** `gameObject.zoneId = 35` (hand zone), but Revealed zone 19 lists proxy instanceIds in `objectInstanceIds`. Revealed zone is an **index**, proxies **overlay** the hand zone.

**Proxy type:** `GameObjectType.RevealedCard` (value 8), `visibility: Public`, `viewers: [2]`.

**SelectNReq shape:**
- `ids` = filtered (noncreature nonland), can be empty
- `unfilteredIds` = all revealed cards
- `minSel/maxSel` = 1/1 when ids non-empty; absent when empty
- `sourceId` = spell instanceId
- `context: Resolution`, `listType: Dynamic`, `idType: InstanceId`

**Two Forge paths converge at bridge:**
- Duress: `chooseCardsToDiscardFrom(p, sa, validCards, min, max)` — `validCards` already filtered, full hand on `p`
- Revealing Eye: `chooseCardsForEffect(sourceList, sa, ...)` — `sourceList` already filtered

Both hit `chooseCardsViaBridge()`. Both are preceded by `WebPlayerController.reveal()` which already captures card IDs.

## Approach

Build bottom-up: bridge state → choice wiring → SelectNReq → proxy synthesis → cleanup. Each phase is independently testable.

### Phase A: Bridge Reveal State

**`InteractivePromptBridge.kt`:**
- Add `data class ActiveReveal(allHandCardIds: List<ForgeCardId>, ownerSeatId: SeatId)`
- Add `@Volatile var activeReveal: ActiveReveal? = null`
- Add `RevealChoose` to `PromptSemantic` enum
- Add `unfilteredRefs: List<PromptCandidateRefDto> = emptyList()` to `PromptRequest`

**`WebPlayerController.kt` — `reveal()` override (~line 295):**
- After existing `recordReveal()`, set `bridge.activeReveal = ActiveReveal(cardIds, ownerSeat)` capturing the **full hand** snapshot

### Phase B: Choice Method Wiring

**`WebPlayerController.kt`:**
- New private `chooseCardsViaBridgeForReveal(cards, min, max, message, sa)`:
  - Reads `bridge.activeReveal` for `unfilteredRefs` (all hand card IDs)
  - Builds `candidateRefs` from `cards` (filtered valid choices)
  - Builds `unfilteredRefs` from `activeReveal.allHandCardIds`
  - Sets `semantic = RevealChoose`, `sourceEntityId = sa?.hostCard?.id`
  - Handles empty-cards case: `min=0, max=0`
  - Clears `bridge.activeReveal` in `finally` block

- Update `chooseCardsToDiscardFrom` (~line 347): when `bridge.activeReveal != null`, delegate to `chooseCardsViaBridgeForReveal()` instead of generic path

- Update `chooseCardsForEffect` (~line 374): same pattern — when `bridge.activeReveal != null`, delegate to reveal-aware method

### Phase C: SelectNReq + Classification

**`RequestBuilder.kt` — `buildSelectNReq()` (~line 143):**
- Add `RevealChoose` branch: `context=Resolution, listType=Dynamic, optionContext=Resolution`
- After existing `ids` loop, add: `for (ref in prompt.request.unfilteredRefs) { builder.addUnfilteredIds(instanceId) }`
- Set `sourceId` from `prompt.request.sourceEntityId` → `bridge.getOrAllocInstanceId()`
- When `candidateRefs` empty: don't set minSel/maxSel (defaults to 0)

**`PromptClassifier.kt`:**
- Add `RevealChoose` variant to `ClassifiedPrompt`
- Route `PromptSemantic.RevealChoose` → `ClassifiedPrompt.RevealChoose`

**`TargetingHandler.kt`:**
- Route `ClassifiedPrompt.RevealChoose` → `sendSelectNReq()` (existing infra handles it once RequestBuilder is wired)

### Phase D: RevealedCard Proxy Synthesis

**`InstanceIdRegistry.kt`:**
- Add `allocSynthetic(): InstanceId` — allocates ID without ForgeCardId mapping
- Track synthetic IDs in `syntheticIds: MutableSet<Int>` for cleanup

**`ObjectMapper.kt`:**
- Add `buildRevealedCardProxy(card, proxyInstanceId, handZoneId, ownerSeatId, bridge)`:
  - `type = GameObjectType.RevealedCard` (8)
  - `visibility = Visibility.Public`
  - `zoneId = handZoneId` (proxy overlays hand, NOT in Revealed zone)
  - Mirrors real card's grpId, types, subtypes, P/T, abilities

**`GameBridge.kt`:**
- Add `activeRevealProxies: Map<ForgeCardId, InstanceId>` — tracks real→proxy mapping
- Add `pendingProxyDeletions: Set<InstanceId>` — proxies to clean up next diff

**`StateMapper.kt` — `buildFromGame()` (~line 65):**
- After draining reveals, check `bridge.activeReveal`:
  - For each card: allocate synthetic proxy ID, build RevealedCard proxy, add to `gameObjects`
  - Add proxy IDs to Revealed zone (19) `objectInstanceIds`
  - Store mapping in `bridge.activeRevealProxies`
- When `bridge.pendingProxyDeletions` is non-empty:
  - Add IDs to `diffDeletedInstanceIds`
  - Emit `RevealedCardDeleted` annotation per proxy
  - Clear `pendingProxyDeletions`

### Phase E: Hand Visibility Flip

**`ZoneMapper.kt` — `addPlayerZones()` (~line 32):**
- Add `revealActive: Boolean = false` parameter
- When `revealActive && seatId == revealedOwnerSeat`:
  - Hand zone: `visibility = Public`, add `viewers` field
  - Emit `GameObjectInfo` for ALL hand cards with `visibility: Public` (normally hidden from opponent)

**`StateMapper.kt`:**
- Thread `bridge.activeReveal?.ownerSeatId` to `ZoneMapper.addPlayerZones()`

### Phase F: Cleanup

When `chooseCardsViaBridgeForReveal` completes (choice resolved):
1. Clears `bridge.activeReveal`
2. Moves `bridge.activeRevealProxies` values to `bridge.pendingProxyDeletions`
3. Clears `activeRevealProxies`

Next `buildFromGame()` call:
- No active reveal → no proxies synthesized → hand reverts to private
- `pendingProxyDeletions` → `RevealedCardDeleted` annotations + `diffDeletedInstanceIds`
- Snapshot-compare naturally handles the rest

## Deliverables

- [ ] Bridge state: ActiveReveal, PromptSemantic.RevealChoose, unfilteredRefs
- [ ] Choice wiring: chooseCardsViaBridgeForReveal, both entry points
- [ ] SelectNReq: ids/unfilteredIds/sourceId, classification, routing
- [ ] RevealedCard proxies: synthetic IDs, ObjectMapper, StateMapper injection
- [ ] Hand visibility flip: ZoneMapper, StateMapper threading
- [ ] Proxy cleanup: RevealedCardDeleted, diffDeletedInstanceIds
- [ ] Tests (see below)
- [ ] Puzzles: duress-mixed-hand.pzl, duress-no-valid.pzl
- [ ] Update `docs/catalog.yaml` — revealed zone status, reveal-choose-discard mechanic
- [ ] Update #256 with findings

## Tests

| Test | Tier | What it validates |
|------|------|-------------------|
| `RequestBuilderRevealChooseTest` | Unit (0.01s) | SelectNReq shape: ids, unfilteredIds, sourceId, empty-ids case |
| `RevealProxyAnnotationTest` | Unit (0.01s) | RevealedCardCreated/Deleted annotation proto shape |
| `PromptClassifierRevealTest` | Unit (0.01s) | RevealChoose semantic → correct ClassifiedPrompt |
| `RevealProxySynthesisTest` | Conformance (0.01s) | startWithBoard: proxy objects in GSM, correct type/zone/visibility |
| `RevealHandVisibilityTest` | Conformance (0.01s) | startWithBoard: hand zone flips to Public during reveal |
| `DuressValidTargetPuzzleTest` | Conformance (0.09s) | Puzzle: Duress with valid target, full SelectNReq + discard flow |
| `DuressNoValidTargetPuzzleTest` | Conformance (0.09s) | Puzzle: Duress all creatures, empty ids, choose-nothing |
| `DuressMatchFlowTest` | Integration (0.7s) | MatchFlowHarness: full client round-trip cast→target→reveal→choose→discard |

## Verification

1. `just test-one DuressValidTargetPuzzleTest` — puzzle passes
2. `just test-one DuressNoValidTargetPuzzleTest` — empty-choice edge case
3. `just test-one DuressMatchFlowTest` — production path
4. `./gradlew :matchdoor:testGate` — no regressions
5. Arena playtest: Duress in a real bot match — screenshot of revealed hand UI
6. Arena playtest: Concealing Curtains transform → reveal → choose → discard

## Unknowns

1. **Concealing Curtains trigger wiring:** The on-transform trigger (`TriggerTransformed`) fires the `DB$ RevealHand` chain. Does Forge already fire this trigger when we call `card.changeState("Transform")`? Need to verify in a puzzle — if the trigger doesn't fire, that's a separate Forge bridge gap.

2. **Proxy `viewers` field:** Recording shows `viewers: 2` on proxies. Need to check if this is a repeated field (list of seat IDs that can see it) or a count. Proto definition will clarify.

3. **Two-parameter prompt:** Cast 2's SelectNReq prompt has `CardId=370` (source) AND `CardId=1`. The second parameter's meaning is unclear — possibly player count or a sentinel. May need to match exactly for client rendering.

## Leverage

**High.** This builds the reveal infrastructure that unblocks:
- All "reveal hand, choose" effects (Thoughtseize, Agonizing Remorse, Go Blank, etc.)
- Explore reveal (#172) shares RevealedCard proxy pattern
- Library search reveal (Bushwhack etc.) shares visibility flip pattern
- Any future "look at" / "reveal" mechanic
