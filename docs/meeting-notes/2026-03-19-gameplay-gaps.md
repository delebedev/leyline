# Gameplay Gaps — Priority Review

2026-03-19

Gap analysis from Rosetta + catalog cross-referenced against recent work.

## Fixed this session

- **ActivateMana** — was falling through to PassPriority. Lands and mana dorks now tap correctly.
- **Activate** — catalog said "partial", was actually fully wired. Docs updated.

## Tier 1 — Blocks real Standard gameplay

| Gap | Impact | Difficulty | Notes |
|-----|--------|------------|-------|
| **MDFC (CastMdfc/PlayMdfc)** | ~20% of Standard cards. Neither face playable. | Medium | Action type routing + face selection UI (CastingTimeOptionsReq?) |
| **AddAbility (ann 9)** | "Gains flying/haste/etc" — keyword badge never updates on client. Affects anthems, equipment, auras granting keywords. | Medium | Need Forge event wiring. No GameEvent exists for granted abilities. |
| **ControllerChanged (ann 15)** | Steal effects (Claim, Agent type cards). Permanent doesn't visually move to new controller. | Medium | GameEventPlayerControl fires but unwired to nexus events. |
| **OrderReq (GRE 17)** | Multi-card scry/surveil ordering. Currently auto-resolved server-side — wrong for scry 2+. | Low-medium | Bridge code exists (WebPlayerController), needs GRE message wiring to client. |

## Tier 2 — Common mechanics, degraded UX

| Gap | Impact | Notes |
|-----|--------|-------|
| **Adventure (CastAdventure)** | Popular Standard mechanic, can't cast adventure half | Action type 16 |
| **ManaPaid details (ann 34)** | Stub — no mana payment visual | Low priority, cosmetic |
| **CardRevealed (ann 47)** | Tutors, impulse draw — no reveal animation | |
| **DynamicAbility (ann 30)** | Activated ability cost display missing in client UI | |
| **AssignDamageReq (GRE 30)** | Multi-blocker damage assignment auto-distributed, wrong with trample + multiple blockers | |
| **Phasing (ann 95/96)** | Growing in Standard. GameEventCardPhased unwired. | |
| **LoyaltyActivationsRemaining (ann 97)** | PW ability use limits not shown | |

## Tier 3 — Lower priority

| Gap | Notes |
|-----|-------|
| Split cards (CastLeft/CastRight) | Less common in current Standard |
| Prototype (CastPrototype) | Niche |
| Room cards (CastLeftRoom/CastRightRoom) | BRO/aftermath sets |
| FaceDown (ann 28) | Morph/manifest — low priority for Standard |
| CoinFlip/DieRoll (ann 57/85) | Edge case cards |
| Foretell (CastOmen) | Rotating out |

## Suggested build order

1. MDFCs — highest card coverage gain per effort
2. AddAbility — most visible UX gap (keyword badges)
3. Adventure — popular mechanic
4. ControllerChanged — gameplay correctness
5. OrderReq — scry 2+ correctness
