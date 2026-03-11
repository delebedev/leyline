# Agent Retro

- **Own turns played:** 5 (game turns 1, 3, 5, 7, 9 — all Phase_Main2 except turn 1 which was Main1)

## Cards Played

| Card | My Turn | Game Turn | Method |
|------|---------|-----------|--------|
| Swamp | 1 | 1 | blind-drag (x=300, x=200 failed) |
| Hopeless Nightmare | 1 | 1 | ocr-drag (cx=507) |
| Swamp | 2 | 3 | blind-drag (x=300, x=200 failed) |
| Hopeless Nightmare | 2 | 3 | ocr-drag (cx=373) |
| Swamp | 3 | 5 | blind-drag (x=300, x=200 failed) |
| Grim Bauble | 3 | 5 | ocr-drag (cx=587) |
| Tithing Blade | 3 | 5 | ocr-drag (cx=459) |
| Mephitic Draught | 4 | 7 | ocr-drag (cx=371) |
| (no land — none in hand) | 5 | 9 | — |

## Cards Skipped
- Mephitic Draught / Fanatical Offering / Cornered by Black Mages on turns 4+5: mana spent or turn ended

## Deck Type
- Black mono — Swamps, Hopeless Nightmare (1B ETB discard+drain), Tithing Blade, Mephitic Draught, Grim Bauble, Fanatical Offering, Cornered by Black Mages

## Stuck Moments
- Land drag at x=200 failed every time (3 occasions). Always succeeded on x=300 retry. Root cause: 6-card hand lands sit at ~x=300, not x=200.
- Game jumped straight to Phase_Main2 on turns 3, 5, 7, 9 — passes through combat automatically when no attackers.

## Concede
- Clean. Cog click → "Concede" → Defeat screen → dismissed with 3 clicks.

## Opponent (Sparky)
- Green deck: Woodland Mystic, Treetop Warden, Forest, Colossal Majesty
- Life: 20 → 18 → 16 at end (two Hopeless Nightmare drains; Grim Bauble also killed a Woodland Mystic)
- My life: 20 → 19 → 17 (Mephitic Draught self-damage + combat)
