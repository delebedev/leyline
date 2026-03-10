## Batch 004 — 2026-03-10 (04-05-40)

1. Fix `arena play` for basic lands and hand cards — debug API lookup fails 100% of games; forces coord-drag fallback every time. Fix API or remove `arena play` from prompt until resolved.
2. Fix deck-selection coord in agent memory — "80px above label" is wrong for starter decks (label y≈455, 80px above = 375 = blank). Correct is y≈455 or y≈440.
3. Add proactive hand-overflow check before Phase_Ending pass — check hand_count in the turn-end sequence; discard first if >=8. Eliminates "5 blind clicks then diagnose" stall.
4. Default to OCR before blind-click loops in combat phase — one `arena ocr | grep pass\|block` before any multi-click sequence; only use 888,504 when a single unambiguous button is present.
5. Suppress scry calls during lobby/navigation — no engine state calls until after mulligan Keep. Lobby is OCR-only territory. Scry in lobby wastes 5–6 calls per game.
6. Increase hard timeout or add per-phase call budget — 420s insufficient when lobby consumes 26+ tool calls. Suggest 600s or enforce "reach Keep within 15 calls or abort-and-retry" rule.
7. Discard target should be intentional — always scry first, pick lowest-value card by name, not blind (400,500).
