# Eaten Alive — Card Spec

## Identity

- **Name:** Eaten Alive
- **grpId:** 93885 (FDN), 92906, 78440
- **Set:** FDN (Foundations)
- **Type:** Sorcery
- **Cost:** {B} (additional cost: sacrifice a creature OR pay {3}{B})
- **Forge script:** `forge/forge-gui/res/cardsfolder/e/eaten_alive.txt`

## Mechanics

| Mechanic | Forge DSL | Forge event | Catalog status |
|----------|-----------|-------------|----------------|
| Alternate additional cost | `K:AlternateAdditionalCost:Sac<1/Creature>:3 B` | **none** (cost selection, not game event) | wired (optional-costs) |
| Sacrifice as cost | `Sac<1/Creature>` (cost component) | `GameEventCardSacrificed` | wired |
| Exile target | `A:SP$ ChangeZone … Destination$ Exile` | `GameEventCardChangeZone` | wired |
| Target creature or planeswalker | `ValidTgts$ Creature,Planeswalker` | **none** (targeting) | wired |

## What it does

1. **Cast:** pay {B} plus one of two additional costs — sacrifice a creature you control, or pay {3}{B}.
2. **Effect:** exile target creature or planeswalker.

The card always costs {B} base. The two additional cost modes are mutually exclusive alternatives, not optional. You must pay exactly one.

## Trace (session 2026-04-02_21-43-41, seat 1)

Eaten Alive cast **twice** in this game. First cast: sacrifice mode ({B} + sacrifice creature). Second cast: mana mode ({B} + {3}{B} = {4}{B} total). Seat 1 (human) = full visibility. A second session (2026-04-02_10-10-39) confirms the sacrifice-mode pattern.

### Cast 1 — Sacrifice mode (T9, gs=195→202)

Player undid the cast twice before committing (undo cycles at gs=185/190, visible as repeated CastSpell + back to ActionsAvailableReq). Final successful sequence:

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 181 | 344 | Hand | Drawn from library (id 177→344) |
| 195 | 346 | Stack | CastSpell (id 344→346) — third attempt |
| 196–198 | 346 | Stack | SelectTargetsReq — player selects Loxodon Line Breaker (335) |
| 199 | 346 | Stack | ManaPaid: 1B from Swamp (295) |
| 200 | 348 | Graveyard | Sacrifice: Hungry Ghoul (300→348) BF→GY, category=Sacrifice |
| 200 | 346 | Stack | UserActionTaken: actionType=1, abilityGrpId=0 |
| 202 | 350 | Exile | Resolution: Loxodon Line Breaker (335→350) BF→Exile, category=Exile |
| 202 | 351 | Graveyard | Eaten Alive (346→351) Stack→GY, category=Resolve |

**Side effect:** Aura (Pacifism, id=305→349) falls off sacrificed creature → GY via SBA_UnattachedAura (gs=200).

### Cast 2 — Mana mode (T15, gs=333→338)

No creatures available to sacrifice — only mana mode presented.

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 330 | 375 | Hand | Drawn from library (id 180→375) |
| 333 | 376 | Stack | CastSpell (id 375→376) |
| 334–336 | 376 | Stack | SelectTargetsReq — player selects Desecration Demon (327) |
| 336 | 376 | Stack | ManaPaid: 5B from 5 Swamps (295, 299, 309, 326, 345) |
| 336 | 376 | Stack | UserActionTaken: actionType=1, abilityGrpId=0 |
| 338 | 382 | Exile | Resolution: Desecration Demon (327→382) BF→Exile, category=Exile |
| 338 | 383 | Graveyard | Eaten Alive (376→383) Stack→GY, category=Resolve |

### Annotations

**Cost mode selection — NO dedicated prompt (gs=183–184):**

The server does NOT use CastingTimeOptionsReq or PromptReq for cost mode selection. Instead, it sends **two separate ActionsAvailableReqs at consecutive gsIds**, one per available cost mode:

- **gs=183 (sacrifice mode):** Cast action for Eaten Alive with `manaCost=[{Black,1}]` and `autoTapSolution` with **1 land** (just {B}).
- **gs=184 (mana mode):** Same Cast action, same `manaCost=[{Black,1}]`, but `autoTapSolution` with **5 lands** ({4}{B} total).

Both share `promptId=2`. The `manaCost` field is always just `[{Black,1}]` — the base cost. The autoTapSolution encodes which mode the server is suggesting: 1 land = sacrifice mode, 5 lands = mana mode.

When only one mode is available (Cast 2 — no creatures to sacrifice), the server sends only **one** ActionsAvailableReq (gs=332) with the 5-land autoTap.

**Sacrifice annotation (gs=200):**
- `ZoneTransfer` — affectorId=346 (Eaten Alive on stack), affected=348, zone_src=28 (BF), zone_dest=33 (GY seat 1), category=`Sacrifice`
- `UserActionTaken` — affectorId=1 (seat), affected=346, actionType=1 (Cast), abilityGrpId=0

**ManaPaid (gs=199, sacrifice mode):**
- `ManaPaid` — affectorId=295 (Swamp), affected=346 (spell), id=318, color=3 (Black)
- Standard mana bracket: AIC → TUP → UAT(actionType=4) → ManaPaid → AID

**ManaPaid (gs=336, mana mode):**
- Five `ManaPaid` annotations, one per Swamp (ids 740–744), all color=3 (Black)

**Resolution (gs=202, gs=338):**
- `ResolutionStart` — affectorId=346/376 (spell), grpid=93885
- `ZoneTransfer` — category=`Exile`, target BF→Exile
- `ResolutionComplete` — affectorId=346/376, grpid=93885
- `ZoneTransfer` — category=`Resolve`, spell Stack→GY

**Card object uniqueAbilities:**
- grpId=133059: "As an additional cost to cast this spell, sacrifice a creature or pay {3}{B}."
- grpId=139942: "Exile target creature or planeswalker." (shared with Overwhelming Remorse)
- Targeting uses abilityGrpId=139942 in SelectTargetsReq, while the cast action uses abilityGrpId=93885.

### Cross-session confirmation (2026-04-02_10-10-39)

Same sacrifice-mode pattern: ManaPaid 1B → Sacrifice (Reassembling Skeleton) → Resolution → Exile (Zephyr Gull). No undo cycles. Confirms the annotation sequence is deterministic.

### Key findings

- **No CastingTimeOptionsReq for AlternateAdditionalCost.** Unlike kicker/buyback (which use CastingTimeOptionsReq with per-cost toggles), Eaten Alive's cost mode selection is encoded as **multiple ActionsAvailableReqs with different autoTapSolutions.** The client infers the cost mode from the autoTap hint, not from a dedicated prompt.
- **manaCost always shows base cost only.** Both modes show `[{Black,1}]` — the additional cost ({3}{B} or sacrifice) is never reflected in the Cast action's `manaCost` field. The client knows the total cost from the autoTapSolution.
- **Dual ActionsAvailableReq = dual cost modes.** Server sends N ActionsAvailableReqs where N = number of available cost modes. This is a general pattern for `AlternateAdditionalCost` cards, not specific to sacrifice-vs-mana.
- **Sacrifice annotation is standard.** The `ZoneTransfer(category=Sacrifice)` with affectorId pointing to the spell on stack is the same shape as other sacrifice-as-cost effects (Treasure, Bargain). No special handling needed.
- **Undo cycles produce repeated CastSpell annotations.** The server replays ObjectIdChanged + ZoneTransfer for each undo→retry. The undo/redo is implicit — no explicit undo annotation, just a return to ActionsAvailableReq with the original instanceId.
- **abilityGrpId=0 in UserActionTaken.** The cast action's UAT uses abilityGrpId=0 (not 133059 or 139942), consistent with basic spell casting.

## Gaps for leyline

1. **Dual ActionsAvailableReq for alternate additional costs.** Leyline currently sends one ActionsAvailableReq per priority pass. For cards with `AlternateAdditionalCost`, it needs to send one per available cost mode, each with a different autoTapSolution. This requires `ActionMapper` or `BundleBuilder` to detect alternate additional costs (via `getAdditionalCostSpell`) and emit multiple action bundles.

2. **autoTapSolution on Cast actions.** Leyline's Cast actions currently lack `autoTapSolution`. The real server always includes it — and for alternate costs, it's the mechanism that communicates cost mode to the client. Implementing this is a prerequisite for gap #1.

3. **ManaCost field shows only base cost.** Leyline may be computing the full cost ({4}{B}) for the mana mode variant. The real server always shows just the base cost ({B}) in the Cast action's `manaCost`. `computeEffectiveCost` may need to emit base cost only, with the additional cost communicated through autoTapSolution.

4. **Cost mode → Forge SA routing.** When the client picks a Cast action (from one of the N ActionsAvailableReqs), leyline must map the choice back to the correct Forge `SpellAbility` variant (sacrifice vs mana). `getAdditionalCostSpell` returns the two SA variants — the response routing needs to select the right one based on which ActionsAvailableReq the client responded to.

## Supporting evidence needed

- [ ] Cards with the same `AlternateAdditionalCost` pattern to confirm dual-ActionsAvailableReq is general: Bone Splinters, Deadly Dispute, Village Rites, Plumb the Forbidden
- [ ] Recording with both cost modes available AND player choosing mana mode (this game always picked sacrifice when creatures were available)
- [ ] Bargain cards (`K:Bargain`) — do they also use dual ActionsAvailableReq, or CastingTimeOptionsReq?
