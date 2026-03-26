# Kiora, the Rising Tide — Card Spec

## Identity

- **Name:** Kiora, the Rising Tide
- **grpId:** 93758
- **Set:** FDN
- **Type:** Legendary Creature — Merfolk Noble
- **Cost:** {2}{U}
- **Base P/T:** 3/2
- **Forge script:** `forge/forge-gui/res/cardsfolder/k/kiora_the_rising_tide.txt`

## Mechanics

| Mechanic | Forge DSL | Catalog status |
|----------|-----------|----------------|
| ETB trigger: draw 2, then discard 2 (loot) | `T:Mode$ ChangesZone … Execute$ TrigDraw` / `SVar:TrigDraw:DB$ Draw \| NumCards$ 2 \| SubAbility$ DBDiscard` / `SVar:DBDiscard:DB$ Discard \| Mode$ TgtChoose` | partial |
| Threshold attack trigger: if 7+ in GY, may create 8/8 Octopus token | `T:Mode$ Attacks … Threshold$ True \| OptionalDecider$ You` | **missing** (token path unobserved) |
| AbilityWordActive (Threshold) annotation | server-side persistent annotation | **missing** |

### Ability grpIds

| grpId | Description |
|-------|-------------|
| 143924 | ETB triggered ability (draw 2, discard 2) |
| 175800 | Threshold attack trigger (create Scion of the Deep) |

## What it does

1. **ETB loot** — when Kiora enters the battlefield, draw two cards, then discard two cards. Discard is chosen by the player (Mode$ TgtChoose). The draw and discard are one chained sub-ability (TrigDraw → DBDiscard): draw fires first, then a SelectNreq is issued for the discard choice, then discard resolves.
2. **Threshold attack trigger** — whenever Kiora attacks, if 7+ cards are in your graveyard, you may create Scion of the Deep, a legendary 8/8 blue Octopus creature token. Optional (player chooses yes/no).

## Trace (session 2026-03-25_22-37-18, seat 1)

Kiora (grpId 93758) was cast from hand on turn 16. iid 308 was drawn at gs142 and remained in hand; at gs389 a second copy (iid 390) was drawn via ETB loot of another spell. iid 308 was then cast (→ iid 392) at gs390, landing on battlefield at gs392. The ETB loot trigger resolved across gs394–gs395. Threshold was never reached in time for an attack with Kiora — the attack trigger path is unobserved.

### Cast

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 142 | 308 | Hand (31) | Drawn from library (iid 170→308, ZoneTransfer category=Draw) |
| 390 | 308→392 | Limbo→Stack (30→27) | Cast: ObjectIdChanged (orig=308, new=392) + ZoneTransfer (31→27, category=CastSpell); 3 lands tapped: Plains (iid 365, abilityGrpId 1001), 2× Island (iid 298+318, abilityGrpId 1002) |
| 392 | 392 | Battlefield (28) | Resolved: ResolutionStart/Complete grpid=93758, ZoneTransfer (27→28 category=Resolve), hasSummoningSickness=true; ETB ability iid 396 (grpId 143924) created on stack |

### ETB trigger: draw phase (gs394)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 394 | 396 (ability) | Stack | ResolutionStart grpid=143924 (ETB ability); 2× ObjectIdChanged + ZoneTransfer category=Draw (lib→hand, zone 32→31): iid 181→397, iid 182→398 |

SelectNreq fires at the same gsId=394 (alongside the draw diffs), before the discard resolves:

- `promptId`: 1034
- `minSel`/`maxSel`: 2/2
- `context`: "Resolution_a163", `optionContext`: "Resolution_a9d7"
- `listType`: Dynamic, `idType`: InstanceId_ab2c
- `sourceId`: 392 (the Kiora permanent)
- `ids`: [165, 380, 390, 397, 398] — all cards currently in hand (including the two just drawn)
- `validationType`: NonRepeatable

### ETB trigger: discard phase (gs395)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 395 | 380→399, 390→400 | Hand→GY (31→33) | 2× ObjectIdChanged + ZoneTransfer category=Discard; affectorId=396 (ability instance) on all |
| 395 | 396 (ability) | — | ResolutionComplete grpid=143924; AbilityInstanceDeleted (affectorId=392, affectedIds=396) |

Player chose to discard iid 380 (grpId 93748, Human Wizard) and iid 390 (grpId 93758, the second Kiora copy drawn this turn).

### AbilityWordActive (Threshold) persistent annotation

Kiora instances on battlefield carry a persistent `AbilityWordActive` annotation that tracks GY count for the threshold condition:

```json
{
  "types": ["AbilityWordActive"],
  "affectorId": 392,
  "affectedIds": [392],
  "details": {
    "threshold": 7,
    "value": 2,
    "AbilityGrpId": 175800,
    "AbilityWordName": "Threshold"
  }
}
```

`value` updates every diff to reflect current GY card count. The annotation is present on all Kiora instances (including cards in GY — iids 389, 400, 430 also carry it once in GY). Value reached 7 at gs466, 8 at gs495. Kiora (iid 392) was available as a qualified attacker at gs470 (after threshold met) but was never declared as attacker — the optional token creation was never triggered in this session.

### Annotations (from raw proto)

**Cast (gsId 390, file 000000463_MD_S-C_MATCH_DATA.bin):**
- `ObjectIdChanged` — affectedIds=[308], details={orig_id:308, new_id:392} (affectorId absent)
- `ZoneTransfer` — affectedIds=[392], zone_src=31, zone_dest=27, category=`CastSpell` (affectorId absent)
- 3× mana bracket: AbilityInstanceCreated → TappedUntappedPermanent → UserActionTaken actionType=4 → ManaPaid → AbilityInstanceDeleted (per land)
- `UserActionTaken` — affectorId=1, affectedIds=[392], actionType=1, abilityGrpId=0 (confirm cast)
- persistent `AbilityWordActive` — affectorId=392, value=2, AbilityGrpId=175800

**Resolve (gsId 392, file 000000467_MD_S-C_MATCH_DATA.bin):**
- `ResolutionStart` — affectorId=392, affectedIds=[392], grpid=93758
- `ResolutionComplete` — affectorId=392, affectedIds=[392], grpid=93758
- `ZoneTransfer` — affectorId=1, affectedIds=[392], zone_src=27, zone_dest=28, category=`Resolve`
- `AbilityInstanceCreated` — affectorId=392, affectedIds=[396], source_zone=28 ← ETB ability queued on stack
- persistent `TriggeringObject` — affectorId=396, affectedIds=[392], source_zone=28

**ETB draw (gsId 394, file 000000471_MD_S-C_MATCH_DATA.bin):**
- `ResolutionStart` — affectorId=396, affectedIds=[396], grpid=143924
- `ObjectIdChanged` — affectorId=396, affectedIds=[181], {orig_id:181, new_id:397}
- `ZoneTransfer` — affectorId=396, affectedIds=[397], zone_src=32, zone_dest=31, category=`Draw`
- `ObjectIdChanged` — affectorId=396, affectedIds=[182], {orig_id:182, new_id:398}
- `ZoneTransfer` — affectorId=396, affectedIds=[398], zone_src=32, zone_dest=31, category=`Draw`
- SelectNreq (same msgId bracket, gsId=394): promptId=1034, sourceId=392, ids=[165,380,390,397,398], minSel=maxSel=2

**ETB discard (gsId 395, file 000000486_MD_S-C_MATCH_DATA.bin):**
- `ObjectIdChanged` — affectorId=396, affectedIds=[380], {orig_id:380, new_id:399}
- `ZoneTransfer` — affectorId=396, affectedIds=[399], zone_src=31, zone_dest=33, category=`Discard`
- `ObjectIdChanged` — affectorId=396, affectedIds=[390], {orig_id:390, new_id:400}
- `ZoneTransfer` — affectorId=396, affectedIds=[400], zone_src=31, zone_dest=33, category=`Discard`
- `ResolutionComplete` — affectorId=396, affectedIds=[396], grpid=143924
- `AbilityInstanceDeleted` — affectorId=392, affectedIds=[396]

### Key findings

- **Draw and discard are separate diffs (gs394 vs gs395)** — draw resolves first; SelectNreq fires in the same diff as the draw (gs394), then the player's response triggers the discard diff (gs395). No priority pass between them.
- **SelectNreq sourceId = Kiora permanent (iid 392)**, not the ability instance (396). Same pattern as other SelectNreq discard prompts (confirmed against `discard-selectnreq-bug.md`).
- **SelectNreq context = "Resolution_a163" / optionContext = "Resolution_a9d7"** — matches the established discard-choice pattern (same context strings observed for hand-size discard). The `ids` list contains all current hand cards at the time of prompting, including the two just drawn.
- **Ability affectorId on ZoneTransfer annotations = ability iid (396)**, not the Kiora permanent. This is the correct shape for triggered ability resolution.
- **AbilityWordActive tracks all Kiora instances** — even copies in GY carry the annotation, each on their own iid with their own pAnn id. The `affectorId` is the Kiora instance, `affectedIds` is also the same Kiora instance. `value` is the same GY count (seat-wide) across all copies.
- **Token trigger unobserved** — Kiora was a qualified attacker at gs470 (value=7, threshold met) but was never declared. No SelectNreq, no TokenCreated for Scion of the Deep in this session.

## Gaps for leyline

1. **SelectNreq for discard** — the ETB discard uses SelectNreq (minSel=maxSel=2, Mode$ TgtChoose), not a simple forced discard. Confirmed context="Resolution_a163"/optionContext="Resolution_a9d7". The existing loot (catalog status=partial) likely lacks this player-choice shape — verify `DBDiscard` with `Mode$ TgtChoose` emits SelectNreq.
2. **Chained sub-ability sequencing** — `TrigDraw` → `DBDiscard` fires as a single ability instance (grpId 143924) but resolves in two diff steps. The SelectNreq is issued mid-resolution (after draw, before discard). Leyline must hold the ability on stack through the SelectNreq/resp roundtrip before emitting the discard diff.
3. **AbilityWordActive annotation (Threshold)** — persistent annotation for the threshold attack trigger is absent in leyline. Required for the client's threshold counter UI. See prior conformance research (AbilityWordActive threshold) and catalog.yaml `threshold` entry (status=missing).
4. **Threshold attack trigger + optional token** — triggered ability on attack, conditional on GY count, with `OptionalDecider$ You`. Requires: (a) AbilityWordActive value tracking, (b) optional trigger prompt (ActionsAvailableReq with pass option), (c) Scion of the Deep token creation. Entire path unobserved in this session.

## Supporting evidence needed

- [ ] Session with Kiora attacking at threshold (7+ GY) to observe optional trigger wire shape
- [ ] Confirm Scion of the Deep token grpId (check `token_scripts/scion_of_the_deep.txt` and `just card-grp`)
- [ ] Second session (2026-03-25_21-59-57) may contain earlier Kiora cast — cross-check for variance in ETB SelectNreq shape
- [ ] Verify loot catalog entry (`status: partial`) covers the TgtChoose discard path vs forced discard
