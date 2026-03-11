# Agent Retro

- Own turns played: 5 (turns 2,4,6,8,10 as active_player=1, but meaningful play only on turn 2)
- Cards played:
  - Plains (turn 2, land-drag at x=300, y=500 → 480,300) — SUCCEEDED (hand 8→7)
  - No spells cast: only 1 Plains on battlefield = 1 mana, all 2-cost spells required 2 mana
- Cards skipped:
  - Ethereal Armor — enchantment/aura type, skipped per rules
  - Spellbook Vendor — 2 mana required, only had 1 mana available each turn
  - A Most Helpful Weaver — 2 mana, discarded at cleanup turn 4
  - Sheltered by Ghosts — 2 mana, discarded at cleanup turn 7
  - Feather of Flight — 2 mana
  - Veteran Survivor — 1 mana white (W), could have been cast but I was in Cleanup each time my turn came to scry
- Deck type: White weenie/enchantments — Plains, Fountainport (blue-white land), Spellbook Vendor (2/2 for 2), Veteran Survivor, Ethereal Armor, Feather of Flight, Sheltered by Ghosts, A Most Helpful Weaver
- Stuck moments:
  1. Land drag at x=200 failed (gsId unchanged) — pivoted to x=300, succeeded on turn 2
  2. Missed turns 4,6,8: clicking 888,504 during Sparky's turn also queued-passed my own Main1 phase — by the time scry ran, I was in Ending/Cleanup
  3. Turn 10: tried to cancel before land drag by clicking 888,504 — this advanced phase from Main2 to Cleanup, losing the turn
  4. Land drag never worked twice: the single Plains played on turn 2 was the only land — never got 2 mana to cast anything
  5. I died on turn 11 to Sparky's attackers (6+ power of zombies/rats vs 6 life) before getting a clean turn
- Concede: Not needed — game ended naturally (I died, scene=PostGame). Dismissed with 3× click 480,300.
