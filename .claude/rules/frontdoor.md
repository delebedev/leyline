---
paths:
  - "frontdoor/**"
---

# Front Door

Progressive enablement — stubs are scaffolding, not the goal. Every FD handler starts as a stub returning golden recorded data, then graduates to a real implementation when the feature matters to gameplay.

- **If a stub's golden response contains data the player acts on** (decks, events, matchmaking, collection), prioritize replacing it with real logic.
- **If a stub is cosmetic** (store, carousel, rewards), it can stay a stub longer.
- **New handlers:** start with `just fd-response <CmdType>` to see what the real server sends, match the shape, then decide stub vs real.
- **Zero coupling to game engine.** Frontdoor depends on its own domain model and persistence. Never import from matchdoor.
