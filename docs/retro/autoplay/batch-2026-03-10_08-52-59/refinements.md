## Batch 2026-03-10_08-52-59 — 2026-03-10
1. Before any drag, parse hand from `scry state` (zones where type=Hand, extract name+instanceId per card). Eliminates all blind x-coord sweeps. Agent must know what's in hand before choosing what to drag.
2. Use `arena detect` to locate hand card positions by bounding box (filter: cy>490, cx<800). OCR text coords for lands are unreliable — cards overlap and land names lack distinctive bold text. Detect gives actual hit region.
3. Gate 888,504 pass clicks on `priority_player == own_seat` from scry. When Sparky has priority, sleep+rescry instead. (Repeated from batch-08-24-51 — not yet applied.)
4. Check mana available and spell CMC from scry before attempting spell drags. Do not attempt spells requiring targets or mana the agent does not have.
5. Add Mastery Pass modal to known-modals list: OCR signature "Mastery Pass" or "Teenage Mutant Ninja Turtles Mastery" → Escape key via osascript. Blocked lobby in run 3 and cost ~8 extra tool calls.
6. Drop redundant `ocr --fmt` after drag when scry state is sufficient to verify success (gsId advance + hand count change). Only use OCR for discard prompt card names or unknown modal text.
7. Fix harness token tracking for subagent runs — run 1 reports 0 tokens/cost because outer agent does not aggregate subagent modelUsage. Propagate subagent result.modelUsage into harness metrics.
