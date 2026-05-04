---
paths:
  - "matchdoor/src/main/**"
---

# Forge Boundary APIs

Read `docs/forge-api-concepts.md` before changing Forge-facing matchdoor code.

Hard reminders:

- `SpellAbility` is often a chain; outer-SA predicates can miss sub-ability work.
- `canPlay()` is legality, not affordability; check mana payment separately.
