## Batch 2026-03-10_13-13-25
1. Re-enable `arena play "<name>"` for card plays — still FORBIDDEN in this batch despite being recommended in 12-48-24. Drag coordinate failures persisted in all 3 runs. With `arena play`, scry's `hand` array gives exact name → no coord guessing.
2. Replace triple-click Sparky-pass with scry-gated pass — `887,491 && 890,510 && 888,504` fires blindly; third click lands on own Main1 priority stop ~60% of time, causing agent to skip own turn. Replace: 2 clicks + scry → only fire third click if still active_player=2.
3. Add lobby state-detection pre-check — `bin/arena ocr --fmt | head -5` before clicking "Play". Run-2 burned 58% of all tool calls on lobby confusion that one OCR call would have resolved.
4. Reduce drag-failure stuck-kill timeout to 30s (recommended in 12-48-24, still not applied) — run-2 spent 90s with all drags failing; problem was visible within 20s.
5. Verify land play by hand-count not gsId — gsId can lag one poll cycle after a successful play (confirmed run-3 L84: drag succeeded but gsId didn't change until L87). Hand count is the reliable signal.
6. Fix token aggregation for harness-killed runs — write intermediate token counts to a temp file during run; aggregate from file if final result line is missing. Third batch with 0 tokens on killed runs.
