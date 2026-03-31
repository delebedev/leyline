# OptionalActionMessage — GRE prompt spec

**GRE type:** `GREMessageType_OptionalActionMessage` = 45
**Response type:** `ClientMessageType_OptionalActionResp` = 25
**Status:** NOT IMPLEMENTED
**Occurrences:** 16 across 7 games (March 29–31 2026)

## Wire shape (GRE → Client)

Top-level fields (on `GREToClientMessage`):

| Field | Always/Sometimes | Type | Notes |
|-------|-----------------|------|-------|
| `type` | Always | enum | `GREMessageType_OptionalActionMessage` |
| `systemSeatIds` | Always | uint32[] | Single seat — `[1]` or `[2]` |
| `msgId` | Always | uint32 | Monotonic, used as `respId` in response |
| `gameStateId` | Always | uint32 | References the GSM that carries ResolutionStart |
| `prompt` | Always | Prompt | See below |
| `optionalActionMessage` | Always | nested | See below |
| `allowCancel` | Always | enum | Always `AllowCancel_No` (16/16) |

### `prompt` subfields

| Field | Always/Sometimes | Type | Notes |
|-------|-----------------|------|-------|
| `promptId` | Always | uint32 | Loc key for UI text. 6 distinct values seen (1159, 2233, 9371, 13399, 13621, 144) |
| `parameters[]` | Always | PromptParameter[] | Usually 1 CardId. Rarely 2 (zone-transfer case had `[0, 389]`) |

### `optionalActionMessage` subfields

| Field | Always/Sometimes | Type | Notes |
|-------|-----------------|------|-------|
| `sourceId` | **Always** | uint32 | InstanceId of the ability source object on the stack |
| `optionalActionTypes` | Rare (1/16) | CardMechanicType[] | Seen: `["CardMechanicType_ZoneTransfer"]` |
| `recipientIds` | Rare (1/16) | uint32[] | InstanceIds of affected objects. Co-occurs with optionalActionTypes |
| `highlight` | **Never** | HighlightType | Not observed |
| `highlightIds` | **Never** | uint32[] | Not observed |

## Wire shape (Client → GRE response)

| Field | Type | Values |
|-------|------|--------|
| `type` | enum | `ClientMessageType_OptionalActionResp` |
| `gameStateId` | uint32 | Matches prompt's `gameStateId` |
| `respId` | uint32 | Matches prompt's `msgId` |
| `optionalResp.response` | enum | `OptionResponse_Allow_Yes` or `OptionResponse_Cancel_No` |

Response distribution: 10 Accept (62.5%), 6 Decline (37.5%).

`persistence`, `appliesTo`, `mapTo` fields on OptionalResp not observed in any response.

## Preceding GSM pattern

- **`pendingMessageCount: 1`** — set on the **same GSM** the prompt references (not the preceding one)
- **`AnnotationType_ResolutionStart`** — always present in the prompt's GSM (the ability is beginning to resolve)
- **Phase:** Mostly `Phase_Main1` (5/6 in traced game). Also seen in `Phase_Ending / Step_End`
- **Priority flip:** Preceding GSM has `priorityPlayer=opponent`. Prompt GSM flips to `priorityPlayer=deciding_player`
- **Delivery:** Separate GRE message following the GSM (not bundled inside it)

Sequence: `GSM(diff, ResolutionStart, pendingMessageCount=1)` → `OptionalActionMessage` → client responds → `GSM(diff, resolution outcome)`

## Gameplay meaning

"You may" triggered ability — the player decides whether to execute an optional triggered ability that just went on the stack. Client shows "Decline" / "Take Action" buttons. Choosing Decline removes the ability without resolving it.

## Occurrences (from recordings)

| Game | gsId | Seat | promptId | Card (likely) | Response |
|------|------|------|----------|--------------|----------|
| 2026-03-29_16-10-08 | — | 1 | 13621 | Wildborn Preserver | Accept |
| 2026-03-29_16-18-08 | — | 1 | 1159 | Wildborn Preserver | 3x Accept |
| 2026-03-29_16-32-24 | 334 | 1 | 1159 | Wildborn Preserver | 1 Accept, 5 Decline |
| 2026-03-29_17-04-27 | — | 1 | 2233 | Giada / Sun-Blessed Healer | 1 Accept, 1 Decline |
| 2026-03-30_20-33-50 | — | 1 | 13399 | — | 2 Accept |
| 2026-03-30_21-37-32 | — | 1 | 9371 | Micromancer | 2 Accept |
| 2026-03-31_10-44-09 | 204 | **2** | 144 | Sephiroth / Vengeful Bloodwitch | Accept |

## Our code status

- **Proto:** Fully defined — `OptionalActionMessage` (msg), `OptionalResp` (resp), enums for both
- **Builder:** Missing — no factory method to construct OptionalActionMessage
- **Handler:** Missing — WebPlayerController mentions it in a comment (line 129) as "future engine-initiated prompt"
- **Response dispatch:** Missing — `OptionalResp` is routable as ClientMessage field 12 but no handler processes it

## Dependencies

- **`pendingMessageCount`** — already supported in GsmBuilder (line 370). Must be set to 1 on the GSM that carries ResolutionStart.
- **ResolutionStart annotation** — must be present in the same GSM. Currently emitted?
- **AbilityInstanceDeleted annotation** — emitted on Decline (ability removed without resolving)
- **Normal resolution flow** — on Accept, standard resolution continues (whatever the ability does)

## Implementation contract

- **Emit after:** ResolutionStart for an ability whose Forge `SpellAbility` has an `OptionalDecider` (the "you may" check)
- **Detect via:** Check `sa.hasParam("OptionalDecider")` or equivalent on the SpellAbility being resolved. Forge calls `sa.getActivatingPlayer().getController().confirmOptionalCost(...)` — we need to intercept this
- **Response routing:** Client sends `ClientMessageType_OptionalActionResp` → match session receives → unblock engine thread waiting for the optional decision
- **On Accept (`Allow_Yes`):** Resolution continues — Forge executes the ability's resolve method
- **On Decline (`Cancel_No`):** Ability is removed from stack without resolving. Emit `AbilityInstanceDeleted` annotation. No further resolution events.

## Prompt IDs

| promptId | Context | Games |
|----------|---------|-------|
| 1159 | Repeated "you may" trigger (same card, multiple triggers per game) | 16-18-08, 16-32-24 |
| 2233 | Different card's "you may" | 17-04-27 |
| 9371 | ETB optional search | 21-37-32 |
| 13399 | — | 20-33-50 |
| 13621 | — | 16-10-08 |
| 144 | Zone-transfer optional (richer shape) | 10-44-09 |

PromptIds are loc-key references — each "you may" ability has its own text. Leyline doesn't need to match these exactly (client only uses them for display text lookup), but should emit a consistent value per ability type.

## Notes

- **`allowCancel: AllowCancel_No`** — counterintuitive naming. This controls the client's Cancel button visibility, NOT whether declining is allowed. Players can always decline via `Cancel_No` response.
- **Forge integration point:** `PlayerControllerWifi.confirmOptionalCost()` or equivalent method where Forge asks the player controller whether to proceed with an optional effect. This is the natural interception point.
- **Seat routing is straightforward** — `systemSeatIds` targets the ability's controller. No multi-seat prompts observed.
- **The rare extended shape** (optionalActionTypes + recipientIds) appeared with a zone-transfer optional. May need to populate these for certain ability types, but the minimal shape (sourceId only) covers >90% of cases.
- **No `highlight`/`highlightIds` observed** — safe to omit initially.
