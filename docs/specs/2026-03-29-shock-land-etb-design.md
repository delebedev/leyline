# Shock Land ETB Replacement — Design Spec

## Problem

Shock lands (Temple Garden, etc.) can't be played in leyline matches. Forge calls `confirmReplacementEffect()` on the bridge, which sends a generic `PromptRequest` — the Arena client doesn't know how to respond, engine thread times out.

## Wire shape (from recording `2026-03-29_17-04-26`)

### Server → Client

Pre-resolution diff contains:
- `ReplacementEffect` pAnn: `affectorId=<system>`, `affectedIds=[newIid]`, `grpId=90846`, `ReplacementSourceZcid=<handIid>`
- `OptionalActionMessage`: `promptId=2233`, `sourceId=<system>`, `allowCancel=No_a526`

Resolution diff contains:
- `ZoneTransfer(PlayLand)` — hand → battlefield
- Pay path: `ModifiedLife{life:-2}` + `SyntheticEvent{type:1}`, land enters untapped
- Decline path: land enters with `isTapped: true`, no life change

### Client → Server

- Accept (pay 2 life): `OptionalActionResp { optionalResp { response: AllowYes } }`
- Decline (enter tapped): `OptionalActionResp { optionalResp { response: CancelNo } }`

## Design

### Approach: wire Forge's existing prompt through OptionalActionMessage

Forge already handles the game logic (life payment, tapped state). We just need to translate the bridge prompt into the Arena protocol and relay the response back.

### Changes

**1. WebPlayerController — classify replacement prompts**

`confirmReplacementEffect()` currently uses generic `requestChoice()`. Change to:
- Detect shock land replacement (check `replacementEffect` type or `UnlessCost$ PayLife` pattern)
- Create an `OptionalActionPrompt` (new type or reuse existing prompt infra)
- Submit to a dedicated `CompletableFuture<Boolean>` (like `pendingDamageAssignment`)
- Block until client responds

For non-shock-land replacements, fall back to existing generic behavior.

**2. MatchHandler — dispatch OptionalActionResp**

Add case in message dispatch:
```
ClientMessageType.OptionalActionResp -> session.onOptionalAction(greMsg)
```

**3. MatchSession.onOptionalAction()**

- Read `optionalResp.response` from the proto
- Map `AllowYes` → `true`, `CancelNo` → `false`
- Complete the `CompletableFuture<Boolean>` in the bridge

**4. BundleBuilder — emit OptionalActionMessage**

Build the GRE message:
- `type = OptionalActionMessage`
- `promptId = 2233`
- `sourceId` = system affector ID (from replacement effect context)
- `allowCancel = No`

Emit in the same GSM bundle as the `ReplacementEffect` pAnn.

**5. AnnotationPipeline — ReplacementEffect pAnn**

Emit persistent annotation:
- `type = ReplacementEffect`
- `affectorId = <system>`, `affectedIds = [newIid]`
- `grpId = 90846` (shock land replacement)
- `ReplacementSourceZcid = <handIid>`

Delete in the resolution diff.

**6. Resolution annotations**

Pay path:
- `ModifiedLife` with `life: -2`, `affectedIds = [seatId]`
- `SyntheticEvent` with `type: 1` (co-occurs with life payment)

Decline path:
- Set `isTapped: true` on the land gameObject (no `TappedUntapped` annotation)

### Not in scope

- Generic OptionalActionMessage for all triggers (Wildborn Preserver, etc.) — separate system
- Other replacement effects (Amulet of Vigor, etc.)
- `grpId` per shock land verification — `90846` may be shared or per-card, to confirm

### Test plan

Puzzle: Temple Garden in hand, enough lands to play it.
- Test 1: accept → verify land untapped, life -2
- Test 2: decline → verify land tapped, life unchanged

Integration test in `MatchFlowHarness`:
- Play shock land → receive OptionalActionMessage → respond AllowYes → verify state
- Play shock land → respond CancelNo → verify tapped

### Files touched

| File | Change |
|------|--------|
| `bridge/WebPlayerController.kt` | Detect shock land in `confirmReplacementEffect`, emit OptionalActionMessage, block on future |
| `match/MatchHandler.kt` | Dispatch `OptionalActionResp` |
| `match/MatchSession.kt` | `onOptionalAction()` — complete the bridge future |
| `match/SessionOps.kt` | Add `onOptionalAction` interface method |
| `game/BundleBuilder.kt` | Build OptionalActionMessage GRE message |
| `game/AnnotationPipeline.kt` | ReplacementEffect pAnn emission |
| `game/AnnotationBuilder.kt` | ModifiedLife + SyntheticEvent for pay path |
| Puzzle file | `shock-land-temple-garden.pzl` |

### Leverage

Unblocks all 10 Ravnica shock lands with one implementation. Also establishes the `OptionalActionMessage` → `OptionalActionResp` round-trip pattern that Wildborn Preserver and other "may" triggers will reuse.
