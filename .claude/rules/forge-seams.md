---
paths:
  - "matchdoor/src/main/**"
---

# Forge Boundary APIs

How to get data out of Forge correctly. Grow from experience — when you discover a trap, add it here.

## Mana / Color Production

**Getting colors a land produces:**
- Check `manaPart.isComboMana` first
- Combo (dual/multi): `manaPart.getComboColors(sa)` → `"B G"` (space-separated)
- Single-color: `manaPart.origProduced` → `"G"`
- Split on spaces, map each token through `ManaColorMapping.fromProduced(token)`

**Traps:**
- `origProduced` shape varies: `"G"` / `"Combo B G"` / `"Combo Chosen"` — never iterate chars
- `MagicColor` bitmasks (B=4, R=8, G=16) ≠ Arena ManaColor ordinals (B=3, R=4, G=5). Always use `ManaColorMapping`.

**autoTapSolution:** required on Cast actions for one-click casting. Dual lands contribute one `ManaSource` per color (same `isComboMana` path).

### Playing a land in tests

- `player.playLand(land, true, null)` — full Forge path. Fires `GameEventLandPlayed`, consumes land drop, triggers landfall/ETB replacements. **Use when testing the land play itself** (annotations, color production).
- `game.action.moveToPlay(land, null, AbilityKey.newMap())` — raw zone move. No events, no land drop consumed, no triggers. **Use for board setup** (putting lands on BF before the real test action).
- `base.addCard("Forest", human, ZoneType.Battlefield)` — places card directly in zone during `startWithBoard`. No zone change at all. **Use when you just need cards in place.**

## Combat

*Grow from burns.*

## Spell Casting / Costs

*Grow from burns.*
