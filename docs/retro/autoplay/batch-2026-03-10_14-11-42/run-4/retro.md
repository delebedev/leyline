# Agent Retro

## Match summary
- Game 4 of a bot match vs Sparky
- Deck: Black (Swamp-based) — Hopeless Nightmare, Tithing Blade, Fanatical Offering, Rowan's Grim Search, Grim Bauble, Mephitic Draught, Cornered by Black Mages
- Final life: Me 14, Sparky 18 (conceded on own turn 5)

## Own turns played: 5
- Turn 1 (game turn 1): Swamp + Hopeless Nightmare (B). Sparky lost 2 life → 18.
- Turn 2 (game turn 3): Swamp + Tithing Blade (1B). Sparky shocked me (20→18) at some point.
- Turn 3 (game turn 5): Swamp + Grim Bauble (B) + attempted Fanatical Offering (canceled — sacrifice target UI stuck) + end turn
- Turn 4 (game turn 7): Rowan's Grim Search (2B) — accidentally cast via blind drag at x=300 instead of land; drew 2 cards (Swamp, Cornered by Black Mages) but lost 2 life (18→16); then played Swamp via land drag
- Turn 5 (game turn 9): Incremented to 5 → immediately conceded

## Cards played
| Card | Turn | Method |
|------|------|--------|
| Swamp | 1 | land-drag (x=300) |
| Hopeless Nightmare | 1 | ocr-drag (cx=419) |
| Swamp | 2 | land-drag (x=300) |
| Tithing Blade | 2 | ocr-drag (cx=459) |
| Swamp | 3 | land-drag (x=300) |
| Grim Bauble | 3 | ocr-drag (cx=588) |
| Rowan's Grim Search | 4 | blind-drag (x=300 grabbed wrong card — spell not land) |
| Swamp | 4 | land-drag (x=300, second attempt after Rowan's was cast) |

## Cards skipped
- Fanatical Offering (1B) — requires sacrificing artifact/creature; sacrifice target selection UI couldn't be navigated (modal prompt, no obvious clickable target in battlefield); canceled 3x
- Mephitic Draught (1B) — turn 5 we conceded before playing
- Cornered by Black Mages (1BB) — turn 5 we conceded before playing

## Deck type
Black aggro/control: Hopeless Nightmare (drain), Tithing Blade (equipment), Fanatical Offering (draw/sacrifice synergy), Rowan's Grim Search (draw 2, lose 2), Grim Bauble (artifact), Mephitic Draught, Cornered by Black Mages. Sparky was Red (Mountains, Shock, Goblin Gathering, Hurloon Minotaur).

## Stuck moments
1. **Fanatical Offering sacrifice prompt** (turn 3): Spell went on stack and showed "Sacrifice an artifact or creature" modal. Clicking 480,300, 350,360, 500,420 all failed to select a valid sacrifice target. Resolved by clicking Cancel button at 887,456.
2. **Rowan's Grim Search "Choose One"** (turn 4): Blind drag at x=300 grabbed the spell instead of the Swamp land. The spell triggered a "Choose One / Cast With Bargain" modal. Clicked left option at 353,177 which resolved it as normal cast (drew 2, lost 2 life).

## Concede
Clean — concede button found immediately, "Defeat" text matched within 2s.
