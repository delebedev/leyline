# Agent Retro

- Turns reached: 5 (global turns 1–5; my active turns: 1, 3, 5)
- Cards played: ~2 Islands (dragged to battlefield via coords); spells too expensive to cast early (Windstorm Drake 4U, Riddlemaster Sphinx 4UU, Winged Words 2U — not enough mana until late)
- Stuck at: `arena play "Island"` failed — basic lands not found by debug API lookup. Fell back to coord drags (y=500).
- Discard: not needed (hand stayed ≤7)
- What worked: `bin/scry state` to read phase/turn; coord drags (370,500 → 480,300) to play cards; repeated 888,504 clicks advanced through Sparky's turns automatically (jumped turn 1→5 in one batch); concede via 940,42 + "Concede" text click; defeat screen dismissed via 480,300 ×3
- What didn't: `arena play "<name>"` — fails for basic lands and named creatures (lookup via debug API returned "not found in hand"); first match was already mid-game (turn 7) when session started, advanced into a fresh game
- Wish I had: clearer mana tracking to know when I could afford to cast spells; `arena play` working for basic lands
