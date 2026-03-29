# Front Door

Progressive enablement — stubs are scaffolding, not the goal. Every FD handler starts as a stub returning golden recorded data, then graduates to a real implementation when the feature matters to gameplay.

- **If a stub's golden response contains data the player acts on** (decks, events, matchmaking, collection), prioritize replacing it with real logic.
- **If a stub is cosmetic** (store, carousel, rewards), it can stay a stub longer.
- **New handlers:** start with `just wire response <CmdType>` to see what the real server sends, match the shape, then decide stub vs real.
- **Zero coupling to game engine.** Frontdoor depends on its own domain model and persistence. Never import from matchdoor.
- **Client is schema-sensitive — failure mode is black screen.** The client silently fails (no error in Player.log) when golden data is missing expected keys, enums, or array shapes. Graph definitions are a known case: `CampaignGraphManager` has hardcoded type enums, so removing a graph → black screen. To decommission: keep an empty stub. **If you hit a black screen after changing golden data:** diff your change against main, check `~/src/arena-notes/` for schema notes, and bisect by reverting fields one at a time.
