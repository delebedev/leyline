## Batch 2026-03-10_13-43-21 — 2026-03-10
1. Enable `arena play "<name>"` for all card plays — x=200 drag failure caused run-1 stuck kill (gsId=97, turn 5, 3 Plains in hand, 0 played). Third consecutive batch with this problem. `arena play` resolves by name from scry `hand` array, no coord guessing.
2. Pre-click scry before every 888,504 — check `active_player` and `phase` before firing. Run-2 had 9 blind clicks leading to combat phase confusion (Main1 pass advanced to DeclareAttackers); 86s/turn average.
3. Fix token aggregation for harness-killed runs — write intermediate token counts to temp file during run; aggregate from file if final result line missing. Runs 1 and 2 have 0/0 tokens/$null. Third consecutive batch with this bug.
4. Verify land play by hand-count not gsId — gsId can lag one poll cycle. After drag, check `len(hand)` decreased by 1 instead of polling for gsId change.
5. Reduce stuck-kill timeout to 30s — run-1 burned 90s at gsId=97 with all drags failing; problem was visible within 20s. Recommended in batches 12-48-24 and 13-13-25, still not applied.
