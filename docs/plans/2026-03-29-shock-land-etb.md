# Shock Land ETB Replacement (#TBD)

## Context

Shock lands (Temple Garden, Hallowed Fountain, etc.) cannot be played in leyline
matches. Forge calls `confirmReplacementEffect()` on the bridge, which falls
through to the generic `requestChoice()` path — the Arena client receives a
`PromptRequest`-style message it cannot handle, the engine thread blocks, and
the game times out.

Wire shape is fully documented from session `2026-03-29_17-04-26` (two complete
ETB flows, accept and decline). See `docs/card-specs/temple-garden.md`.

**Critical gap:** both `OptionalActionResp` messages in the recording are
byte-identical (empty proto body). The accept-vs-decline signal is not visible in
the JSONL decode. Raw proto inspection of the C-S payload files is required before
the session handler can work correctly. This plan treats it as a blocker on
Phase C and marks the investigation as a required pre-task.

**Scope:** unblocks all 10 Ravnica shock lands with one implementation. Also
establishes the `OptionalActionMessage` → `OptionalActionResp` round-trip pattern
reusable by Wildborn Preserver and other "may" prompts.

**Issue:** link after filing
**Branch:** `feat/shock-land-etb`
**Card spec:** `docs/card-specs/temple-garden.md`
**Design spec:** `docs/specs/2026-03-29-shock-land-etb-design.md`
**Recording:** `recordings/2026-03-29_17-04-26/`

## Premise Verification

Both gaps are confirmed real from the recording:

1. Shock lands currently time out — engine blocks on `confirmReplacementEffect()`.
2. `OptionalActionMessage` (GRE type 45, `promptId: 2233`) is the correct prompt
   shape — observed for both plays in gsId 24 and 159.
3. `ReplacementEffect` pAnn (`type=ReplacementEffect_803b`, `grpid=90846`,
   `affectorId=9002/9007`, `affectedIds=[newIid]`) is emitted in the pre-resolve
   GSM and deleted by `diffDeletedPersistentAnnotationIds` in the resolution diff.
4. Pay path emits `ModifiedLife { life: -2, affectedIds: [seatId] }` +
   `SyntheticEvent { type: 1, affectorId: <system>, affectedIds: [seatId] }`.
5. Decline path sets `isTapped: true` on the land gameObject — no
   `TappedUntapped` annotation.

## Key Wire Findings

**`OptionalActionMessage` shape** (proto field 8 on `GREToClientMessage`):

```
GREToClientMessage {
  type: OptionalActionMessage_695e   // enum 45
  gameStateId: <gsId>
  msgId: <N>
  optionalActionMessage {
    sourceId: 9002                   // system seat affectorId (not stable)
    prompt {
      promptId: 2233                 // stable shock land prompt ID
      parameters[0] {
        parameterName: "CardId"
        type: Number
        numberValue: <newLandIid>    // the staging-zone instanceId
      }
    }
  }
  allowCancel: No_a526               // enum 3; cannot dismiss
}
```

**`OptionalActionResp`** is `ClientMessageType.OptionalActionResp = 25`. The
`optionalResp { response: AllowYes/CancelNo }` field in `ClientToGREMessage`
carries the answer — but JSONL shows both as empty. See Phase C investigation.

**`ReplacementEffect` pAnn** (persistent, type `ReplacementEffect_803b`):

```
AnnotationInfo {
  type: ReplacementEffect_803b
  affectorId: <system_seat>         // 9002 or 9007 — varies per session
  affectedIds: [<newLandIid>]       // staging-zone iid (zone 0)
  details { key: "grpid",  valueUInt32: 90846 }
  details { key: "ReplacementSourceZcid",  valueUInt32: <handIid> }
}
```

Lives for exactly one GSM (gsId 24/159); deleted in the immediately following
resolution diff.

**ETB-tapped encoding:** `isTapped: true` set directly on the `gameObject` in
the resolution diff. No `TappedUntapped` annotation fires (distinct from
mana-tap).

**`ModifiedLife` + `SyntheticEvent` for pay path** — both already have builder
methods in `AnnotationBuilder`. The `syntheticEvent()` signature uses
`attackerIid` + `targetSeatId` which maps to `affectorId=<system>` +
`affectedIds=[seatId]` for this case. A system-affector overload is needed
(see Phase E).

## Approach

Build bottom-up in six phases. Each phase has independent tests before the next
begins.

**Phase A — Bridge prompt object.** Add `ShockLandPrompt` data class +
`@Volatile pendingShockLandPrompt` to `WebPlayerController`. No client
interaction yet.

**Phase B — `confirmReplacementEffect()` detection.** Detect the shock land
replacement via `UnlessCost$ PayLife` on the `ReplacementEffect`, set the prompt
field, send the `OptionalActionMessage` bundle, block on a
`CompletableFuture<Boolean>`. Non-shock replacements fall through to existing
generic path.

**Phase C — Accept vs. decline disambiguation (blocker).** Inspect raw C-S
proto files from recording `2026-03-29_17-04-26` to determine whether
`OptionalResp.response` is `AllowYes`/`CancelNo`, or whether both are
`None_ae84` and a different signal distinguishes them. Resolve before
implementing `onOptionalAction()`.

**Phase D — Session handler + dispatch.** Add `onOptionalAction()` to
`SessionOps`/`MatchSession`, dispatch `OptionalActionResp` in `MatchHandler`,
complete the future.

**Phase E — Annotation builders.** Add `replacementEffect()` and
`syntheticEventSystem()` to `AnnotationBuilder`; extend `modifiedLife()` if the
existing overload doesn't support system-affector correctly.

**Phase F — GSM bundles.** Add `shockLandPromptBundle()` to `BundleBuilder` for
the pre-resolve GSM (Send + `OptionalActionMessage`). Wire `ReplacementEffect`
pAnn through `PersistentAnnotationStore` (external inject, deleted next diff).
Wire `ModifiedLife` + `SyntheticEvent` into the resolution diff on pay path; set
`isTapped` on the land object on decline path.

**Phase G — Puzzle + integration test.** Two puzzle tests (accept + decline);
`MatchFlowHarness` integration test.

## Deliverables

- [ ] `ShockLandPrompt` data class in `WebPlayerController`
- [ ] `confirmReplacementEffect()` detection + `OptionalActionMessage` bundle
- [ ] C-S disambiguation resolved (raw proto investigation)
- [ ] `SessionOps.onOptionalAction()` + `MatchSession` implementation
- [ ] `MatchHandler` dispatch for `OptionalActionResp`
- [ ] `AnnotationBuilder.replacementEffect()` builder
- [ ] `AnnotationBuilder.syntheticEventSystem()` overload
- [ ] `BundleBuilder.shockLandPromptBundle()`
- [ ] `PersistentAnnotationStore` external-inject path for `ReplacementEffect` pAnn
- [ ] Resolution diff: `ModifiedLife` + `SyntheticEvent` on pay; `isTapped` on decline
- [ ] Puzzle: `shock-land-temple-garden-accept.pzl`
- [ ] Puzzle: `shock-land-temple-garden-decline.pzl`
- [ ] Tests (see below)
- [ ] `docs/catalog.yaml` — shock land ETB replacement status → `wired`

## Phase A — Bridge Prompt Object

**`bridge/WebPlayerController.kt`** (~line 132, near `pendingDamageAssignment`):

```kotlin
/**
 * Shock land ETB replacement prompt.
 *
 * Set by [confirmReplacementEffect] when a shock land enters. The auto-pass
 * loop in [CombatHandler] does not touch this — the bridge thread blocks on
 * [future] directly. Cleared in the finally block of confirmReplacementEffect.
 *
 * Uses the same dedicated-future pattern as [pendingDamageAssignment].
 */
data class ShockLandPrompt(
    val landInstanceId: Int,       // staging-zone iid allocated before prompt
    val handInstanceId: Int,       // original hand iid (ReplacementSourceZcid)
    val systemAffectorId: Int,     // session system seat ID (9002, 9007, ...)
    val future: CompletableFuture<Boolean>,
)

@Volatile var pendingShockLandPrompt: ShockLandPrompt? = null
    private set
```

The `systemAffectorId` must be sourced from `GameBridge` (or game state) — see
investigation note in Phase F.

**Test:** `ShockLandPromptStructureTest` (unit, ~0.01s) — construct and assert
field types, no engine required.

## Phase B — `confirmReplacementEffect()` Detection

**`bridge/WebPlayerController.kt`** — replace the existing body of
`confirmReplacementEffect()` (~line 585):

```kotlin
override fun confirmReplacementEffect(
    replacementEffect: ReplacementEffect,
    sa: SpellAbility?,
    affected: GameEntity?,
    prompt: String?,
): Boolean {
    if (isShockLandReplacement(replacementEffect)) {
        return confirmShockLandReplacement(replacementEffect, affected)
    }
    // Generic fallback — non-shock replacement effects
    val request = PromptRequest(
        promptType = "confirm",
        message = prompt ?: replacementEffect.toString(),
        options = listOf("Yes", "No"),
        min = 1, max = 1, defaultIndex = 0,
    )
    return bridge.requestChoice(request).firstOrNull() == 0
}

/**
 * True when [re] is the shock land "pay 2 life or enter tapped" replacement.
 *
 * Forge DSL: `R:Event$ Moved, ... UnlessCost$ PayLife<2>` — the `UnlessCost`
 * part is a [CostPart] of type PayLife. Checking the ReplacementEffect map key
 * or the `mapParams` for "UnlessCost" containing "PayLife" is the most robust
 * way to detect this without hardcoding grpIds.
 */
private fun isShockLandReplacement(re: ReplacementEffect): Boolean {
    val unlessCost = re.mapParams["UnlessCost"] ?: return false
    return unlessCost.contains("PayLife")
}

private fun confirmShockLandReplacement(
    replacementEffect: ReplacementEffect,
    affected: GameEntity?,
): Boolean {
    // Notify state (bridge-side equivalent of notifyStateChanged)
    // so the client sees the staging-zone gameObject before the prompt arrives.
    val landInstanceId = resolveStagingInstanceId(affected)
    val handInstanceId = resolveHandInstanceId(affected)
    val systemAffectorId = resolveSystemAffectorId()

    val future = CompletableFuture<Boolean>()
    pendingShockLandPrompt = ShockLandPrompt(landInstanceId, handInstanceId, systemAffectorId, future)

    try {
        // The session's auto-pass loop will detect pendingShockLandPrompt,
        // build and send the bundle, then wait for client response.
        // (See Phase F for the bundle-send path.)
        notifyShockLandPrompt()
        return future.get(PROMPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } catch (ex: TimeoutException) {
        log.error("ShockLand: prompt timed out — defaulting to decline (enter tapped)")
        DevCheck.failOnAutoPass { "ShockLand prompt timed out" }
        return false
    } finally {
        pendingShockLandPrompt = null
    }
}
```

`resolveStagingInstanceId()`, `resolveHandInstanceId()`, and
`resolveSystemAffectorId()` are private helpers; see Phase F for their
implementations.

`notifyShockLandPrompt()` signals the session thread to build and send the
bundle. Mechanism: post to the session via `bridge.notifyStateChanged()` or a
dedicated callback — exact wiring determined in Phase F.

**Test:** `ShockLandDetectionTest` (unit, ~0.01s) — construct mock
`ReplacementEffect` with `UnlessCost=PayLife<2>`, assert `isShockLandReplacement`
returns true. Construct one without, assert false.

## Phase C — Accept vs. Decline Disambiguation (Blocker)

**Investigation required before implementing Phase D.**

Inspect raw binary files from recording `2026-03-29_17-04-26`:

- Accept path: `000000037_MD_C-S_DATA.bin`
- Decline path: `000000117_MD_C-S_DATA.bin`

Decode each as `ClientToGREMessage` and read `optionalResp.response`. Expected
outcomes:

| Outcome | Signal | Implementation |
|---------|--------|----------------|
| `AllowYes`/`CancelNo` | `OptionResponse` enum | Map enum to Boolean in `onOptionalAction()` |
| Both `None_ae84` | No enum signal | Need alternate disambiguation; flag for analysis |
| Second message is different type | Different `ClientMessageType` | Separate handler branch |

Use `just tape proto show-cs` if available; otherwise decode manually with
`protoc --decode`.

**This phase has no code deliverables — only a finding that unblocks Phase D.**
Record the finding in `docs/card-specs/temple-garden.md` under "Gaps resolved".

## Phase D — Session Handler + Dispatch

**`match/SessionOps.kt`** — add to the interface:

```kotlin
/** Handle OptionalActionResp (shock land ETB, "may pay" prompts). Default no-op. */
fun onOptionalAction(greMsg: ClientToGREMessage) {}
```

**`match/MatchSession.kt`** — add implementation (alongside `onAssignDamage`):

```kotlin
override fun onOptionalAction(greMsg: ClientToGREMessage) = synchronized(sessionLock) {
    val bridge = gameBridge ?: return
    val controller = bridge.humanController ?: return
    val prompt = controller.pendingShockLandPrompt ?: run {
        log.warn("MatchSession: OptionalActionResp but no pending shock land prompt — ignoring")
        return
    }

    // See Phase C investigation for how to read the boolean from the proto.
    val accepted = decodeOptionalRespAsBoolean(greMsg)
    log.info("MatchSession: OptionalActionResp accepted={}", accepted)
    prompt.future.complete(accepted)
}

/**
 * Decode the client's OptionalActionResp to a boolean.
 *
 * Wire disambiguation result from Phase C goes here.
 * Current placeholder: read OptionResponse enum from optionalResp.response.
 */
private fun decodeOptionalRespAsBoolean(greMsg: ClientToGREMessage): Boolean {
    return when (greMsg.optionalResp.response) {
        OptionResponse.AllowYes -> true
        OptionResponse.CancelNo -> false
        else -> {
            log.warn("MatchSession: unexpected OptionResponse={} — defaulting false", greMsg.optionalResp.response)
            false
        }
    }
}
```

**`match/MatchHandler.kt`** — add dispatch case in `processGREMessage()`:

```kotlin
ClientMessageType.OptionalActionResp -> s?.onOptionalAction(greMsg)
```

Place after `ClientMessageType.AssignDamageResp_097b` to maintain alphabetical
grouping with other prompt responses.

**Test:** `OptionalActionDispatchTest` (unit, ~0.01s) — build a
`ClientToGREMessage` with `type=OptionalActionResp` and `AllowYes`, wire a mock
session, assert `onOptionalAction` called and future completed with `true`. Same
for `CancelNo`.

## Phase E — Annotation Builders

**`game/AnnotationBuilder.kt`:**

```kotlin
/**
 * Shock land ETB replacement effect — persistent annotation.
 *
 * Emitted in the pre-resolution diff (gsId N), deleted in the resolution
 * diff (gsId N+1) via diffDeletedPersistentAnnotationIds.
 *
 * Wire: affectorId = system seat, affectedIds = [newLandIid],
 *       grpid = 90846 (replacement ability), ReplacementSourceZcid = handIid.
 */
fun replacementEffect(
    systemAffectorId: Int,
    newLandInstanceId: Int,
    abilityGrpId: Int,
    handInstanceId: Int,
): AnnotationInfo = AnnotationInfo.newBuilder()
    .addType(AnnotationType.ReplacementEffect_803b)
    .setAffectorId(systemAffectorId)
    .addAffectedIds(newLandInstanceId)
    .addDetails(uint32Detail(DetailKeys.GRP_ID, abilityGrpId))
    .addDetails(uint32Detail(DetailKeys.REPLACEMENT_SOURCE_ZCID, handInstanceId))
    .build()

/**
 * SyntheticEvent for life-payment cost (system affector variant).
 *
 * Co-occurs with [modifiedLife] in shock land pay path and life-loss triggers.
 * Type 1 = life-loss event; affectorId = system seat, affectedIds = [seatId].
 *
 * Distinct from the combat [syntheticEvent] (attacker → target) which uses
 * a creature instanceId as affectorId.
 */
fun syntheticEventSystem(systemAffectorId: Int, targetSeatId: Int): AnnotationInfo =
    AnnotationInfo.newBuilder()
        .addType(AnnotationType.SyntheticEvent)
        .setAffectorId(systemAffectorId)
        .addAffectedIds(targetSeatId)
        .addDetails(uint32Detail(DetailKeys.TYPE, 1))
        .build()
```

Verify `DetailKeys.REPLACEMENT_SOURCE_ZCID` and `DetailKeys.GRP_ID` exist; add
if missing. The `modifiedLife(playerSeatId, lifeDelta, affectorId)` overload
already accepts a system affectorId — pass the system seat directly.

**Test:** `ReplacementEffectAnnotationTest` (unit, ~0.01s) — assert proto field
values, detail key names, and types match recording exactly. Mirror with
`SyntheticEventSystemTest`.

## Phase F — GSM Bundles

### System affector ID resolution

The `affectorId` for system events (9002, 9007) is assigned per-session by the
Arena server. For leyline, use a stable synthetic value. Check what value
`AnnotationBuilder.modifiedLife` uses for combat life loss affectorId — use the
same convention. If none exists, define `SYSTEM_SEAT_ID = 9001` in
`mapper/ZoneIds.kt` or a new `SystemIds.kt`.

### `BundleBuilder.shockLandPromptBundle()`

New method emitting two GRE messages per gsId:

```kotlin
/**
 * Shock land ETB replacement prompt bundle (gsId N).
 *
 * Message 1 (GameStateMessage, Send):
 *   - Diff with staging-zone gameObject (land iid, zoneId=0)
 *   - ReplacementEffect pAnn injected via external inject (see below)
 *   - pendingMessageCount = 1 (client holds until OptionalActionMessage arrives)
 *
 * Message 2 (OptionalActionMessage):
 *   - promptId 2233, sourceId = systemAffectorId, CardId parameter = landIid
 *   - allowCancel = No_a526
 */
fun shockLandPromptBundle(
    game: Game,
    counter: MessageCounter,
    prompt: WebPlayerController.ShockLandPrompt,
): BundleResult {
    val nextGs = counter.nextGsId()

    // Inject the ReplacementEffect pAnn into PersistentAnnotationStore before
    // building the diff — snapshot-compare will include it in persistentAnnotations.
    bridge.annotations.injectExternal(
        AnnotationBuilder.replacementEffect(
            systemAffectorId = prompt.systemAffectorId,
            newLandInstanceId = prompt.landInstanceId,
            abilityGrpId = SHOCK_LAND_REPLACEMENT_GRPID,
            handInstanceId = prompt.handInstanceId,
        )
    )

    val diff = StateMapper.buildDiffFromGame(
        game, nextGs, matchId, bridge,
        updateType = GameStateUpdate.Send,
        viewingSeatId = seatId,
    )

    val gsm = diff.gsm.toBuilder()
        .setPendingMessageCount(1)
        .build()

    val gsmMsg = makeGRE(GREMessageType.GameStateMessage_695e, nextGs, counter.nextMsgId()) {
        it.gameStateMessage = gsm
    }

    val optionalMsg = makeGRE(GREMessageType.OptionalActionMessage_695e, nextGs, counter.nextMsgId()) {
        it.optionalActionMessage = buildOptionalActionMessage(prompt)
        it.allowCancel = AllowCancel.No_a526
    }

    return BundleResult(listOf(gsmMsg, optionalMsg))
}

private fun buildOptionalActionMessage(
    prompt: WebPlayerController.ShockLandPrompt,
): OptionalActionMessage = OptionalActionMessage.newBuilder()
    .setSourceId(prompt.systemAffectorId)
    .setPrompt(
        Prompt.newBuilder()
            .setPromptId(SHOCK_LAND_PROMPT_ID)
            .addParameters(
                PromptParameter.newBuilder()
                    .setParameterName("CardId")
                    .setType(ParameterType.Number)
                    .setNumberValue(prompt.landInstanceId),
            ),
    )
    .build()

companion object {
    const val SHOCK_LAND_REPLACEMENT_GRPID = 90846
    const val SHOCK_LAND_PROMPT_ID = 2233
}
```

### `PersistentAnnotationStore` — external inject

The `ReplacementEffect` pAnn lives for exactly one GSM, then is deleted. It
doesn't correspond to any `EffectTracker` lifecycle or `MechanicAnnotationResult`
— it's externally triggered. Add:

```kotlin
/**
 * Inject an externally-sourced persistent annotation for one diff cycle.
 *
 * Used for prompts that insert a pAnn before a human-decision GSM
 * (shock land ReplacementEffect, OptionalActionMessage prompts). The
 * annotation enters [active] immediately; removal is via the normal
 * [drainDeletions] path once the caller explicitly removes it.
 *
 * Thread: must be called on the engine thread before the diff build.
 */
fun injectExternal(ann: AnnotationInfo): Int {
    val id = nextPersistentId.getAndIncrement()
    val numbered = ann.toBuilder().setId(id).build()
    synchronized(this) {
        active[id] = numbered
    }
    return id
}

/** Remove a previously injected annotation by ID (adds to pending deletions). */
fun removeExternal(id: Int) {
    synchronized(this) {
        active.remove(id)
        pendingDeletions.add(id)
    }
}
```

The bridge stores the injected ID in `ShockLandPrompt` (add `replacementEffectAnnId: Int` field). In `confirmShockLandReplacement()` finally block, call `bridge.annotations.removeExternal(prompt.replacementEffectAnnId)` so the deletion appears in the resolution diff.

### Resolution diff — pay path

After `future.get()` returns `true` in `confirmShockLandReplacement()`, the engine
applies the life payment and the land enters untapped. The standard
`buildDiffFromGame` path will see the zone transfer (Hand→BF). The pay-path
annotations must be injected before the diff builds:

In the engine thread path (still inside `confirmShockLandReplacement` or via a
`GameEvent` subscriber):

```kotlin
// Inject ModifiedLife + SyntheticEvent for the pay-life event.
// These are transient annotations; add directly to bridge.pendingExtraAnnotations
// (existing mechanism used by combat).
bridge.pendingExtraAnnotations += AnnotationBuilder.modifiedLife(
    playerSeatId = playerSeatId,
    lifeDelta = -2,
    affectorId = prompt.systemAffectorId,
)
bridge.pendingExtraAnnotations += AnnotationBuilder.syntheticEventSystem(
    systemAffectorId = prompt.systemAffectorId,
    targetSeatId = playerSeatId,
)
```

Check whether `bridge.pendingExtraAnnotations` exists; if not, use the existing
pattern that `CombatHandler` uses for damage annotations — the mechanism is
already established.

### Resolution diff — decline path

When `future.get()` returns `false`, Forge calls `DBTap` (enter tapped). The
engine sets the tapped flag. `buildDiffFromGame` snapshot-compare will detect
`isTapped: true` on the land gameObject naturally — no annotation injection
needed. Confirm the gameObject builder sets `isTapped` from `card.isTapped`.

**Test:** `BundleBuilderShockLandTest` (pure pipeline, ~0.01s) — use
`startWithBoard` to place a staging-zone land, call `shockLandPromptBundle()`,
assert GSM contains `ReplacementEffect` pAnn with correct fields, assert
`OptionalActionMessage` has `promptId=2233`, `CardId=<landIid>`,
`allowCancel=No_a526`, `pendingMessageCount=1`.

## Phase G — Puzzle + Integration Tests

### Puzzle files

`puzzles/shock-land-temple-garden-accept.pzl`:

```
# Shock land ETB — accept (pay 2 life, enter untapped)
#
# Win condition: Temple Garden enters untapped, can tap for {G},
#                cast a 1/1 (e.g. Savannah Lions) for {W} from mana of both colors.
# Start of turn: human has 20 life, empty board, Temple Garden in hand.
# Sequence: Play Temple Garden → OptionalActionResp(AllowYes) → tap for {W}
#           → cast Lions → victory (can attack next turn).
#
# Acceptance: life = 18, Garden on BF isTapped=false, Lions on BF.

[HAND]
68739   Temple Garden
grpId_lions   Savannah Lions

[LAND_DROPS]
1

[ASSERT]
life: 18
battlefield_contains: 68739
battlefield_untapped: 68739
battlefield_contains: grpId_lions
```

`puzzles/shock-land-temple-garden-decline.pzl`:

```
# Shock land ETB — decline (enter tapped, no life loss)
#
# Win condition: decline → Garden enters tapped → pass turn → untap → tap for {G}
#                → nothing dies, life unchanged.
# Sequence: Play Temple Garden → OptionalActionResp(CancelNo).
#
# Acceptance: life = 20, Garden on BF isTapped=true after ETB.

[HAND]
68739   Temple Garden

[LAND_DROPS]
1

[ASSERT]
life: 20
battlefield_contains: 68739
battlefield_tapped: 68739
```

### Tests

| Test | Tier | What it validates |
|------|------|-------------------|
| `ShockLandDetectionTest` | Unit (0.01s) | `isShockLandReplacement` true/false for UnlessCost=PayLife and others |
| `ReplacementEffectAnnotationTest` | Unit (0.01s) | pAnn proto shape: type, affectorId, affectedIds, detail keys + values |
| `SyntheticEventSystemTest` | Unit (0.01s) | System-affector `SyntheticEvent` shape matches recording |
| `OptionalActionDispatchTest` | Unit (0.01s) | MatchHandler routes `OptionalActionResp` → `onOptionalAction()`; future completed |
| `BundleBuilderShockLandTest` | Pure pipeline (0.01s) | `shockLandPromptBundle` shape: pAnn present, OAM fields, pendingMessageCount |
| `ShockLandAcceptPuzzleTest` | Conformance (0.09s) | Accept path: life=18, Garden untapped |
| `ShockLandDeclinePuzzleTest` | Conformance (0.09s) | Decline path: life=20, Garden tapped |
| `ShockLandMatchFlowTest` | Integration (0.7s) | `MatchFlowHarness`: play shock land → OAM → AllowYes → state correct |

## Verification

1. `just test-one ShockLandAcceptPuzzleTest` — accept path green
2. `just test-one ShockLandDeclinePuzzleTest` — decline path green
3. `just test-one ShockLandMatchFlowTest` — production round-trip
4. `./gradlew :matchdoor:testGate` — no regressions
5. Arena playtest: Temple Garden in a real bot match — screenshot of the
   "pay 2 life or enter tapped" prompt; accept (life −2, untapped); decline
   (tapped, no life loss)
6. Playtest with a second shock land (Hallowed Fountain or Sacred Foundry) —
   confirm `grpId 90846` fires correctly on a different card

## Unknowns

1. **Accept vs. decline in `OptionalActionResp`.** Phase C must resolve this.
   If both responses are byte-identical (empty `OptionalResp`), the server must
   use a session-side default. One candidate: `AllowYes` = primary action (pay
   life), and the client sends `OptionalActionResp` only on accept — decline is
   a different message type not decoded by the JSONL tool. If so, the handler
   must track state differently (timeout = decline).

2. **`systemAffectorId` source.** The recording shows values 9002 and 9007 —
   they vary per Arena session. For leyline, determine if there is a stable
   system seat convention (e.g. `9001` or the Forge game seat count + offset).
   Check how `SBA` annotations source their `affectorId` — they use `0`
   (absent). The shock land pAnn requires a non-zero value.

3. **`pendingExtraAnnotations` mechanism.** Confirm the bridge has a
   `pendingExtraAnnotations` queue that `StateMapper` drains at diff-build time.
   If not, the pay-path `ModifiedLife`/`SyntheticEvent` injection in Phase F
   needs its own plumbing.

4. **`notifyShockLandPrompt()` signal.** The bridge blocks the engine thread on
   `future.get()` — but someone must send the bundle to the client. The engine
   thread can't send it (it's blocked). The session must detect `pendingShockLandPrompt`
   and trigger the send. Verify: does the existing auto-pass loop check for this
   kind of pending prompt? The `pendingDamageAssignment` pattern uses a
   similar mechanism — `CombatHandler.checkPendingDamageAssignment()` is called
   from `autoPassEngine`. Mirror that pattern.

5. **`ReplacementEffect` pAnn type number.** Confirm `AnnotationType.ReplacementEffect_803b`
   is the correct proto enum name for the recording's pAnn type. Check the proto
   enum definition.

6. **grpId 90846 uniqueness.** Recording confirms this grpId for Temple Garden.
   Verify it is shared across all 10 shock lands (same Forge replacement script).
   If any shock land has a different grpId, the `isShockLandReplacement` detection
   must key on the `UnlessCost` param, not grpId — which is what the current plan
   does.
