## Batch 2026-03-10_08-24-51 — 2026-03-10
1. After every drag that does not reduce hand count within 2s, call `arena ocr` and check for "Target a creature." If found, use `arena detect` to find creature coords, click best candidate, then submit (888,489). Targeting prompt left open is the terminal stall cause in all 3 runs.
2. Replace fixed y=500 card drags with OCR-detected card positions (hand cards at cy~530; cy~410 is hover preview). Eliminates multi-sweep "try x=300,330,360…" loops entirely.
3. Gate blind pass clicks on `priority_player == own_seat` from scry state. When Sparky has priority, sleep+rescry instead of firing 888,504 blindly.
4. Add "View Battlefield" modal to known-modals list in agent prompt: if OCR shows "View Battlefield", click "Yes" immediately. Modal appeared 6× in run 3 and cost ~12 extra tool calls handled ad-hoc.
5. Verify gsId advance after each individual drag attempt (not after a batch sweep of 5+). Exit loop as soon as hand count changes.
6. Fix token tracking in harness — all runs report total_input_tokens=0, making cost and phase-split analysis impossible.
