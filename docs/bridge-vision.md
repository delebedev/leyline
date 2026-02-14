# Bridge Vision

> **Status:** Brainstorm — contingent on protocol bridge working end-to-end

## Idea

If the Nexus bridge works, Forge's existing capabilities (32k cards, AI, formats, replays, multiplayer) get a high-fidelity rendering frontend for free.

| Existing capability | With Nexus |
|---|---|
| Replays | Animated playback instead of log files |
| Deck testing | Run batch simulations, visually review individual games |
| Puzzle mode | Interactive teaching puzzles with rich rendering |
| AI opponent | Forge AI with better UI than desktop client |
| Draft / sealed | Custom cube drafts with full card rendering |
| Custom formats | Oathbreaker, Canadian Highlander, Pauper — any format |
| Local play | Friends on a local server, no accounts needed |

## What already exists

No new game logic needed — this is wiring existing Forge modules to a different renderer:

- `forge-ai` — decision trees + simulation
- `res/cardsfolder/` — 32k card scripts
- Web port bridges — human-vs-human multiplayer
- `GameSimulator` — batch deck testing

The hard part is the protocol bridge. Everything else follows from what's already built.
