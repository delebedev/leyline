# Tatsunari, Toad Rider — Card Spec

## Identity

- **Name:** Tatsunari, Toad Rider
- **grpId:** 79551 (also 79833 — alternate art)
- **Set:** NEO (Kamigawa: Neon Dynasty)
- **Type:** Legendary Creature — Human Ninja
- **Cost:** {2}{B}
- **P/T:** 3/3
- **Forge script:** `forge/forge-gui/res/cardsfolder/t/tatsunari_toad_rider.txt`

### Keimi (token)

- **grpId:** 79755
- **Type:** Legendary Creature — Frog
- **P/T:** 3/3
- **Colors:** Black, Green
- **Token script:** `forge/forge-gui/res/tokenscripts/keimi.txt`

## Mechanics

| Mechanic | Forge DSL | Forge event | Catalog status |
|----------|-----------|-------------|----------------|
| Legendary creature | `Types:Legendary` | -- | wired (legend-rule) |
| Triggered ability (enchantment cast) | `T:Mode$ SpellCast \| ValidCard$ Enchantment` | `GameEventSpellAbilityCast` | wired (tokens, ability-targeting) |
| Conditional trigger ("if you don't control Keimi") | `IsPresent$ Creature.YouCtrl+namedKeimi \| PresentCompare$ EQ0` | -- (engine-side check) | **unknown** |
| Named token creation (Keimi) | `SVar:TrigToken:DB$ Token \| TokenScript$ keimi` | `GameEventTokenCreated` | wired (tokens) |
| Activated ability ({1}{G/U}) | `A:AB$ Effect \| Cost$ 1 GU` | -- | wired (ability-targeting) |
| Hybrid mana cost | `Cost$ 1 GU` | -- | **unknown** (mana payment wired, hybrid cost offering unverified) |
| Target selection (Frog you control) | `ValidTgts$ Frog.YouCtrl` | -- | wired (ability-targeting) |
| Evasion grant ("can't be blocked except by flying/reach") | `SVar:STCantBlockBy:Mode$ CantBlockBy` | -- | **missing** (uses Qualification pAnn) |
| Token with triggered ability (Keimi drains) | `T:Mode$ SpellCast` on token | `GameEventSpellAbilityCast` | wired (tokens + triggered abilities) |
| Life loss / life gain (Keimi) | `DB$ LoseLife` / `DB$ GainLife` | -- | wired (life-total) |

## What it does

1. **Enchantment-cast trigger** — Whenever you cast an enchantment spell, if you don't control a creature named Keimi, create Keimi, a legendary 3/3 black and green Frog creature token. The "if you don't control" is an intervening-if condition checked both at trigger and resolution.
2. **Activated ability** — {1}{G/U}: Tatsunari and target Frog you control can't be blocked this turn except by creatures with flying or reach. Targets one Frog you control; the evasion applies to both Tatsunari and the targeted Frog.
3. **Keimi's trigger** — Whenever you cast an enchantment spell, each opponent loses 1 life and you gain 1 life. Independent trigger on the token — fires alongside Tatsunari's trigger when both are on the battlefield.

## Trace (session 2026-03-27_20-37-21, seat 1)

BG Sagas/Shrines deck. Tatsunari cast once from hand (gsId=102), triggered twice (creating two Keimi tokens — first died mid-game), activated ability used twice. Rich exercise of all abilities.

### Cast + ETB (gsId 94-104)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 94 | 300 | Hand (31) | In hand |
| 102 | 300→303 | Hand→Stack (31→27) | Cast: ObjectIdChanged (300→303), ZoneTransfer |
| 104 | 303 | Battlefield (28) | Resolves, enters battlefield |

### First trigger — Keimi creation (gsId 154-156)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 154 | 317 (ability) | Stack (27) | AbilityInstanceCreated — affectorId=303 (Tatsunari), grpId=147924 |
| 156 | 317 | Stack (27) | ResolutionStart (grpId=147924) |
| 156 | 318 (Keimi) | Battlefield (28) | TokenCreated — affectorId=317 (trigger ability), affectedIds=[318] |
| 156 | 317 | -- | ResolutionComplete, ability instance deleted |

### First Keimi dies (gsId 277)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 277 | 318 | BF→Limbo (28→30) | Keimi destroyed (exact cause not traced — combat or removal) |

### Second trigger — Keimi re-creation (gsId 298-300)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 298 | -- | Stack (27) | New AbilityInstanceCreated from Tatsunari (303) — conditional passes (no Keimi on BF) |
| 300 | 412 (Keimi #2) | Battlefield (28) | TokenCreated — second Keimi token, new instanceId |

### Activated ability — first use (gsId 303-307)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 303 | 413 (ability) | Stack (27) | AbilityInstanceCreated — affectorId=303 (Tatsunari) |
| 303 | -- | -- | PlayerSelectingTargets — player picks target |
| 303 | -- | -- | SelectTargetsReq — legal target: Keimi (412), highlight=Tepid |
| 305 | 413 | -- | PlayerSubmittedTargets, ManaPaid (color=3 B, color=5 G), UserActionTaken (actionType=2, abilityGrpId=147925) |
| 307 | 413 | -- | ResolutionStart + ResolutionComplete (grpId=147925) |
| 307 | -- | -- | **Qualification pAnn** created: `CantBeBlockedByObjects` on Keimi (412) + Tatsunari (303) |
| 307 | 413 | -- | AbilityInstanceDeleted |

### Annotations

**Token creation (gsId 156):**
- `ResolutionStart` — affectorId=317, grpid=147924 (trigger ability grpId)
- `TokenCreated` — affectorId=317 (trigger instance, not Tatsunari), affectedIds=[318] (new Keimi)
- `ResolutionComplete` — grpid=147924
- Token's parentId=317 (the trigger ability instance)

**Activated ability resolution (gsId 305-307):**
- `PlayerSubmittedTargets` — affectedIds=[413]
- `ManaPaid` — two payments: color=3 (B) from one source, color=5 (G) from another. Hybrid {B/G} resolved as B+G across the two mana in the cost.
- `UserActionTaken` — actionType=2 (activated ability), abilityGrpId=147925
- `TargetSpec` persistent annotation — affectorId=413, affectedIds=[412] (Keimi), abilityGrpId=147925

**Evasion grant (gsId 307) — novel annotation:**
- `Qualification` (type 42) persistent annotation:
  - affectorId=413 (ability instance)
  - affectedIds=[412, 303] (Keimi and Tatsunari — both get evasion)
  - `CantBeBlockedByObjects` detail key with value 282
  - `SourceParent` = 303 (Tatsunari)
  - `grpid` = 147925 (activated ability grpId)
- This is **not** a LayeredEffect — evasion restriction uses Qualification, a currently MISSING annotation type.

### Key findings

- **Qualification (42) is the evasion annotation** — "can't be blocked except by flying/reach" uses a Qualification persistent annotation with `CantBeBlockedByObjects` detail, not LayeredEffect. This is distinct from P/T buffs. First concrete trace of this annotation type.
- **CantBeBlockedByObjects value (282)** — meaning unclear; may encode the exception condition (flying/reach). Needs cross-reference with other evasion-granting cards.
- **Conditional trigger is server-side** — the "if you don't control Keimi" check happens in the engine. When Keimi was on the battlefield, Tatsunari's trigger did NOT fire (confirmed by absence of AbilityInstanceCreated between gsId 156-277). After Keimi died (gsId 277), next enchantment cast re-triggered (gsId 298).
- **Hybrid mana resolves as two separate ManaPaid** — the {1}{G/U} cost produced two ManaPaid annotations: one for the generic {1} and one for the hybrid, with the player choosing B or G. No special hybrid annotation.
- **Token parentId = trigger ability instance** — Keimi's parentId is the trigger ability iid (317), not Tatsunari's iid (303). Consistent with token creation wiring where affectorId = trigger instance.
- **Activated ability targets Frog only** — SelectTargetsReq only offers Frog creatures (Keimi), but the Qualification pAnn applies to both Tatsunari AND the targeted Frog. The engine auto-includes the source.

## Gaps for leyline

1. **Qualification (42) persistent annotation** — MISSING in leyline (confirmed in `docs/rosetta.md`). Need to implement this annotation type to support "can't be blocked" evasion. The `CantBeBlockedByObjects` detail key and its value semantics need investigation. This is the blocking gap for Tatsunari's activated ability.
2. **Conditional triggered abilities** — the "if you don't control" intervening-if condition is handled by Forge engine (`IsPresent` / `PresentCompare`). Verify leyline's trigger collector correctly suppresses AbilityInstanceCreated when the condition fails. Likely works if Forge handles it, but no explicit test coverage.
3. **Named legendary token creation** — token creation is wired, but Keimi is a legendary named token with its own triggered ability. Verify: (a) legend rule applies to Keimi (can't have two), (b) Keimi's own SpellCast trigger fires independently, (c) TokenCreated uses correct grpId 79755.
4. **Hybrid mana cost offering** — the {1}{G/U} cost needs to appear in ActionsAvailableReq with correct mana payment options. Verify ActionMapper offers the activated ability with hybrid mana alternatives.
5. **Update `docs/catalog.yaml`** — add entries for: Qualification annotation (missing), conditional triggers (status unknown), evasion-grant via Qualification (missing).

## Supporting evidence needed

- [ ] Other cards with "can't be blocked except by" — trace Qualification annotation to confirm CantBeBlockedByObjects pattern is consistent (e.g. Suspicious Stowaway, Nighthawk Scavenger menace?)
- [ ] Cards with intervening-if conditional triggers — verify trigger suppression in other recordings
- [ ] Second session (2026-03-27_21-29-18, game 4) — cross-validate, especially if activated ability was used differently
- [ ] Puzzle: `tatsunari-keimi.pzl` — Tatsunari + enchantment in hand + no Keimi on BF, verify token creation + activated ability evasion
