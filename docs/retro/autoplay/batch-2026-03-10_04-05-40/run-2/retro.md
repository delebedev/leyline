# Agent Retro — Game 2

- Turns reached: 6 (5 full game turns + into turn 6 before concede)
- Cards played: attempted drags from hand (land + spell) on turn 2; Sparky's deck had Tin Street Cadet on battlefield
- Stuck at: Turn 5, Phase_Combat (Sparky's turn) — combat had two buttons "Pass" and "To Blockers"; clicking 888,504 alone didn't advance. Fix: needed to click "Pass" at (888,490) first to get to DeclareBlock step, then pass again.
- Discard: handled once (turn 4 cleanup, 8 cards → discarded 1 via click 400,500 + submit 888,489)
- What worked:
  - OCR to detect "Pass" vs "To Blockers" buttons saved the stuck-in-combat situation
  - `bin/arena click "Concede"` after cog click worked cleanly
  - `bin/arena wait text="Defeat"` confirmed concede landed
- What didn't:
  - `bin/arena play "Plains"` — returned "Card not found in hand" (API lookup issue)
  - Blind 888,504 clicks don't advance through all combat steps — needed OCR to identify specific buttons
  - Deck selection required clicking the label (y=455) not 80px above (y=375) for starter decks
- Wish I had: a single "no blocks" command; a way to detect which combat step sub-button is needed without OCR each time
