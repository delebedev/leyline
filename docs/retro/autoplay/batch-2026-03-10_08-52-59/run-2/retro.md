# Agent Retro

## Match Summary
- Match ended: DEFEAT (opponent killed me during turn 11 combat before I could concede)
- Final life: 6 → 0 (opponent attacked with 3 creatures for 7+ damage)

## Own turns played: 5
(Turns 2, 4, 6, 8, 10 — active_player=1)

## Cards played: 2
- Mountain (turn 2, drag from ~324,500)
- Temple of Triumph (turn 4, drag from ~336,500) → triggered Scry 1, kept Fanatical Firebrand on top

## Cards failed (drag missed or cancelled): 0
- No spell drags attempted; never had enough mana (only 2 lands on board throughout)
- All castable spells needed white mana (Plains) which I never played

## Discard: Yes, 3 total
- Turn 6 end step: discarded Heroic Reinforcements (2RW, too expensive)
- Turn 8 end step: discarded Goblin Surprise (2R, 3 CMC, mana available but irrelevant at that point)
- Turn 10 end step: discarded Mountain (basic land, redundant)

## Stuck: No major stucks
- Scry prompt appeared after Temple of Triumph — handled correctly (clicked TOP, Done)
- Opponent was ahead on board entire game (had 4 creatures vs my 0)

## What worked
- Lobby flow worked first try (Play → Bot Match → deck → Play → Keep)
- Scry prompt detected and handled correctly
- Discard prompts detected and handled each time (OCR found correct card names)
- Turn tracking via game_state_id + turn_number + active_player worked reliably
- Opponent's turns advanced without issues

## What didn't
- Never played a Plains despite having 3+ in hand — missed opportunity
  (First turn OCR showed Plains coords but I targeted Mountain instead; subsequent turns state showed Plains in actions but OCR did not always show them prominently)
- No creatures played: always had white-mana spells but no Plains on battlefield until very late
- Life dropped fast: 20 → 18 → 13 → 6 → 0 (opponent had Krovikan Scoundrel, Witch's Familiar, Skeleton Archer, 2x Malakir Cullblade)
- Match ended by opponent kill rather than concede — the opponent attacked on turn 11 combat for lethal before concede could fire

## Observations
- The game auto-advanced several phases during state polling (turns jumped: 2→4→6→8→10)
  This happened because clicking "Opponent's Turn" button advanced through multiple phases at once
- Hand size went to 8 every turn due to drawing a card each turn (kept drawing lands)
- Scry ability from Temple of Triumph worked correctly in server
