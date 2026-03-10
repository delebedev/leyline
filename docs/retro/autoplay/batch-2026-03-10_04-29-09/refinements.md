## Batch 2026-03-10_04-29-09 — 2026-03-10

1. Replace blind `arena drag <coords>` with `arena play "Card Name"` for all card plays — agent ignores the recommended high-level tool; raw coords are probabilistic and unverified.
2. Add combat sub-step to the game loop — no attacks occurred in any of 5 games; after Main1 pass, if active_player=1 and Phase_Combat, attempt to attack.
3. Add zone-change verification after each drag — call scry after drag and confirm BF count increased; current deferred-scry loop makes failed plays silent.
4. Wait for active_player=1 + Phase_Main1 before issuing first plays — first scry sometimes lands on turn 1 before our real first turn; add a spin-wait after Keep.
5. Define "5 turns" as 5 of the player's own Main Phase 1 steps — current wording causes runs to concede during Sparky's turn or count bot turns as player turns.
6. Add startup screen detection before lobby — if Arena is not on the standard Play screen, `arena navigate Home` first; run-1 spent 6 extra calls because Arena was on the Events tab.
7. Document or replace improvised `887,491`/`890,510` "double-stack" pass coords — appeared in 2 runs as workaround; add to arena-nav or provide explicit discard/confirm flow in prompt.
8. Reduce per-turn scry frequency for token cost — scry adds ~120-150k tokens each call; skip scry on turns where active_player=2 was just observed (bot turns auto-pass, no state check needed).
