# Agent Retro

- Turns reached: 5 (jumped T1→T5 after first pass sequence — bot turns resolved fast)
- Cards played: ~3 (dragged from hand on T1 and T5; Blossoming Sands + Wary Thespian likely on T1; one more on T5 — hand dropped 8→7)
- Stuck at: Lobby — "Play" tab not in nav bar. Fixed by clicking "Play" at 258,154 (Store→Events area) which revealed Find Match + Bot Match
- Discard: hand was 8 on T5 Main1, played one card down to 7 — no end-step discard needed
- Tool calls used: ~22
- What worked: scry state parsing with python3 one-liner; clicking action button repeatedly to advance; concede flow was clean (cog → Concede → Defeat screen)
- What didn't: "Play" at lobby — not at standard nav coord, needed 3 OCR calls to find it at (258,154); deck select at (82,455) may not have hit the right target but Play at (867,516) still launched the match
