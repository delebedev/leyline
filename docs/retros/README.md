# Retro Insights

Distilled from implementation retros (surveil, sealed, quick-draft, familiar-session). The original retros covered specific bugs — these are the patterns that keep recurring.

## Process

**Decode recording before coding.** The real server's message sequence is the spec. Don't build from proto schema + intuition. Every mechanic that was hard to debug (surveil, sealed wire shapes, quick-draft EventDef) had the answer in the recording before we wrote a line of code. Use `/recording-scope`.

**Golden = ground truth for wire types.** Before smoke testing any wire builder, diff output against golden programmatically. Catches type mismatches (string vs array, object vs array) without touching Arena. `just fd-response <cmd>` for targeted comparison.

**Arena wire omits default values.** `CurrentWins: 0` is not sent. `EventState` with default value is not sent. Always check golden for field ABSENCE, not just presence. Fields present = emit. Fields absent = omit.

**Player.log is the oracle.** First thing to check on any client-side failure. Has exact deserialization errors, exception traces, GRE state.

## Architecture

**Engine-prompt-to-client-modal pattern.** Surveil, scry, explore, clash all follow the same shape:

1. Forge engine → `WebPlayerController.arrangeTopNCards()` → `bridge.requestChoice()` (blocks engine)
2. Build GSM diff revealing card objects (`Private + viewers:[seatId]`) — this makes cards face-up in modal
3. Send `GroupReq` with correct `GroupingContext` (Surveil/Scry/etc.)
4. Client responds `GroupResp` — groups[0] = keep, groups[1] = away
5. Translate group assignments → prompt option indices → `submitResponse()` unblocks engine

Detection: message prefix (`"Surveil:"`, `"Scry:"`) from `WebPlayerController.arrangeTopNCards` label parameter.

**Extraction boundaries leak.** When replacing a concrete type with an interface (e.g. `MatchSession` → `SessionOps`), every callsite must work with the interface. `as? ConcreteType ?: return` silently fails. Use compile-time enforcement: make methods take the interface type.

## Automation

**Subagent-driven execution.** 15 commits across 9 dispatches, 50-140s each works well. Skip spec review for pure domain logic but never for wire format tasks — FormatType/EventTag bugs caught by spec review against recording.

**Wire values from recording, not guesses.** Two quick-draft crashes from invalid Arena enum values (`"BotDraft"` instead of `"Draft"`, `"Draft"` instead of `"QuickDraft"`). Extract EventDef fields from recording: `just fd-response 624 | jq '.Events[] | select(...)'`.
