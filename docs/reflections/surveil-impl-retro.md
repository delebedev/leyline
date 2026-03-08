# Surveil Implementation — Process Reflections

Captured during surveil/scry mechanic implementation (2026-03-08).

## What went wrong

1. **Jumped to code before studying the recording.** Root cause was identified quickly (TargetingHandler auto-resolves empty-candidateRef prompts), and the fix seemed obvious: detect surveil prompts, send GroupReq. But we didn't look at the full message sequence the real server sends until the client showed face-down cards. The recording had the answer the whole time — it sends a GSM diff with the card object (`visibility:Private, viewers:[seatId]`) *before* the GroupReq. That one diff is what makes the card face-up in the modal.

2. **Recording decode tool gap.** Our `rec-actions` found the surveil action, `proto-decode-recording` decoded the JSONL, but the JSONL decoder strips GroupReq inner fields (instanceIds, groupSpecs, context). Had to manually `grep` the decoded JSONL for adjacent msgIds (228-231) to reconstruct the full sequence. A `rec-message <msgId>` command that dumps the full proto for one message would have saved 10 minutes.

3. **Guessed proto enum names.** Used `SubZoneType.None_a2cf` — doesn't exist, it's `None_a455`. Used `ZoneType.Library` in test harness but it resolved to forge's `ZoneType` not proto's. Both caught at compile but wasted a round-trip. The renamed proto enums are in `messages.proto` — should grep first, not guess suffixes.

## Pattern identified

**Engine-prompt-to-client-modal mechanics** (surveil, scry, explore, clash) all follow the same shape:

1. Forge engine calls `WebPlayerController.arrangeForXxx()` -> `arrangeTopNCards()`
2. `arrangeTopNCards` -> `bridge.requestChoice(PromptRequest(...))` — blocks engine thread
3. `TargetingHandler.checkPendingPrompt()` sees the prompt — must NOT auto-resolve
4. Build GSM diff revealing the card objects (`Private + viewers`) so client shows face-up
5. Build and send `GroupReq` with correct `GroupingContext` (Surveil/Scry/etc.)
6. Client responds with `GroupResp` — groups[0] = keep, groups[1] = away
7. Translate group assignments back to prompt option indices
8. `submitResponse()` unblocks engine

The detection heuristic is message prefix: `"Surveil:"`, `"Scry:"`. This works because `WebPlayerController.arrangeTopNCards` uses the `label` parameter as prefix. Fragile but correct — matches 1:1 with the Forge adapter code we control.

## Lesson

**Always decode the recording first.** The real server's message sequence is the specification. Don't build the proto from the proto schema + intuition — build it by matching what the real server sends. `msgId N-1` before the GroupReq had exactly the GSM diff we needed. Had we printed that first, the face-down bug wouldn't have happened.
