# Think Twice — Card Spec

## Identity

- **Name:** Think Twice
- **grpId:** 93878
- **Set:** FDN
- **Type:** Instant
- **Cost:** {1}{U} (flashback {2}{U})
- **Forge script:** `forge/forge-gui/res/cardsfolder/t/think_twice.txt`

## Mechanics

| Mechanic | Forge DSL | Catalog status |
|----------|-----------|----------------|
| Cast instant | `A:SP$ Draw` | wired |
| Draw a card | `Draw` | wired |
| Flashback | `K:Flashback:2 U` | **missing** |

## What it does

1. **Cast from hand** ({1}{U}): draw a card. Normal instant.
2. **Flashback from graveyard** ({2}{U}): cast again from GY, draw a card, then exile (not graveyard).

## Trace (session 2026-03-25_22-37-18, seat 1)

Think Twice was cast **twice from hand** and once from **graveyard (flashback)** in this game. Seat 1 (human) = full visibility.

### Cast from hand (first time)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 21 | 280 | Hand (31) | Initial — in opening hand |
| 59 | 280→286 | Limbo→Stack (30→27) | Cast: ObjectIdChanged + ZoneTransfer (hand→stack) |
| 61 | 286→290 | Limbo→GY (30→33) | Resolve: ResolutionStart, draw (ZoneTransfer), ResolutionComplete, spell→GY |

### Cast from graveyard (flashback)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 192 | 290→319 | Limbo→Stack (30→27) | Flashback cast: GY→stack, 3 mana payments (3 lands tapped) |
| 200 | 319→328 | Limbo→Exile (30→29) | Resolve: draw, then spell→Exile |
| 204 | 328 | Exile (29) | Final resting place |

### Annotations (from raw proto)

**Flashback cast (gsId 192):**
- `ObjectIdChanged` — orig_id=290, new_id=319
- `ZoneTransfer` — zone_src=33 (GY), zone_dest=27 (Stack), **category=`CastSpell`**
- Standard mana payment bracket (3× TappedUntapped/ManaPaid/AID for 3 Islands)

**Flashback resolve (gsId 200):**
- `ResolutionStart` — affectorId=319, grpid=93878
- `ObjectIdChanged` + `ZoneTransfer` — zone_src=32 (Library), zone_dest=31 (Hand), category=`Draw` ← the drawn card
- `ResolutionComplete` — grpid=93878
- `ObjectIdChanged` + `ZoneTransfer` — zone_src=27 (Stack), zone_dest=29 (Exile), **category=`Resolve`** ← spell exiled, NOT category Exile

### Key findings

- **Flashback cast uses `CastSpell` category** — identical to hand cast, only zone_src differs (33 vs 31)
- **Flashback resolve uses `Resolve` category to Exile** — the category is `Resolve` (same as normal), but destination is zone 29 (Exile) instead of 33 (GY). The engine's replacement effect handles the redirect; the wire category stays `Resolve`.
- **No flashback-specific annotation** — no ShouldntPlay or special marker on the card after flashback. The exile itself is the signal.
- **ShouldntPlay in this frame is unrelated** — it's about a legendary permanent (affectedIds=165, Reason=`Legendary`), not Think Twice.

## Gaps for leyline

1. **CastFlashback action type** — ActionMapper needs to offer flashback-eligible cards in GY as castable. The server uses the same CastSpell wire shape.
2. **GY as cast origin** — ZoneTransfer with category=`CastSpell` from zone 33 (GY) instead of 31 (Hand). The annotation shape is identical.
3. **Resolve to Exile** — flashback spells resolve to Exile (29) not GY (33). Category stays `Resolve`. Forge engine handles this via replacement effect — need to verify leyline's zone mapping passes through the correct destination.

## Supporting evidence needed

- [ ] Other flashback cards in traces (Revenge of the Rats, Electroduplicate, Bulk Up from session 22-22-14)
- [ ] Variance: confirm CastSpell category is always used for flashback cast across cards
- [ ] Verify Forge engine already redirects flashback resolve to exile (likely yes — it's a rules-level replacement)
