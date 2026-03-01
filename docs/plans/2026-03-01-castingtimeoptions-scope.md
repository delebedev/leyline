# CastingTimeOptionsReq: Modal Spells & Kicker Implementation Scope

## What we captured

8 instances across 2 sessions (2026-03-01). Two subtypes:

### Modal (4 instances)

**Goblin Surprise** (grpId 93913) — "Choose one: creatures get +2/+0 OR create two 1/1 Goblins"

```
castingTimeOptionsReq {
  castingTimeOptionReq {
    ctoId: 2
    castingTimeOptionType: Modal_a7b4
    affectedId: 295              ← spell instanceId on stack
    affectorId: 295
    grpId: 93913                 ← card grpId
    playerIdToPrompt: 1
    isRequired: true
    modalReq {
      modalOptions { grpId: 23611 }   ← "Creatures you control get +2/+0"
      modalOptions { grpId: 1360 }    ← "Create two 1/1 red Goblin creature tokens"
      abilityGrpId: 175922            ← modal ability ID
      minSel: 1
      maxSel: 1
    }
  }
}
allowCancel: Abort
allowUndo: true
```

**Valorous Stance** (grpId 94011) — "Choose one: indestructible OR destroy toughness 4+"

Same shape, different modal options (grpIds 22657, 22658).

Modal option grpIds are **abilityIds** with localized text in `Abilities` table.
Use `SELECT l.Loc FROM Abilities a JOIN Localizations_enUS l ON a.TextId=l.LocId WHERE a.Id=X`
to get mode text.

### Kicker (2 instances)

**Burst Lightning** (grpId 93905) — "Kicker {4}" (deal 4 damage instead of 2)

```
castingTimeOptionsReq {
  castingTimeOptionReq {
    ctoId: 4
    castingTimeOptionType: Kicker
    affectedId: 336              ← spell instanceId
    affectorId: 336
    grpId: 2852                  ← kicker ability grpId (not the card!)
    playerIdToPrompt: 1
    manaCost {                   ← additional kicker cost
      color: Red_afc9   count: 1   objectId: 336
    }
    manaCost {
      color: Generic     count: 4   objectId: 336
    }
  }
  castingTimeOptionReq {         ← "Done" = skip kicker
    castingTimeOptionType: Done
    isRequired: true
    manaCost { ... }             ← base spell cost
    autoTapSolution { ... }      ← pre-computed mana tap solution
  }
}
allowCancel: Abort
allowUndo: true
```

Note: kicker's `grpId` is the **kicker ability** (2852 = "Kicker {4}"), not the
card. The card grpId (93905 = Burst Lightning) is on the `affectedId` game object.

### Other card with kicker (session 00-11-05)

abilityId 9313 = "Kicker {1}{W}" — matches cards like Keldon Strike Team,
Excavation Elephant, etc.

## When this fires

**During casting, before spell goes on stack.** The server pauses the cast
sequence to ask for casting-time decisions. Flow:

1. Player clicks "Cast" on a modal/kicker spell
2. Server sends `ActionsAvailableReq` → client submits `PerformActionResp` (Cast)
3. Server sends **`CastingTimeOptionsReq`** with the choices
4. Client responds with **`CastingTimeOptionsResp`** (chosen ctoId + modal selections)
5. Server continues casting: targets, mana payment, spell resolves

## What other card mechanics use this

- **Modal spells** (Choose one/two/three) — Valorous Stance, Goblin Surprise, Prismari Command, Inscription of X
- **Kicker** — Burst Lightning, Keldon Strike Team, Vine Gecko
- **Entwine** — probably another `castingTimeOptionType` variant
- **Multikicker** — likely repeated Kicker options
- **Additional costs with choices** — Escalate, Strive, etc.
- **Overload** — alternative casting cost choice

## What we need to build

### 1. RequestBuilder — `buildCastingTimeOptionsReq`

New builder method. Key fields:
- `castingTimeOptionReq[]` — list of options, each with:
  - `ctoId` — option identifier
  - `castingTimeOptionType` — Modal, Kicker, Done
  - `affectedId`, `affectorId` — spell instanceId
  - `grpId` — card or ability grpId
  - `isRequired` — must this be chosen?
  - `modalReq` — for Modal: `{abilityGrpId, minSel, maxSel, modalOptions[{grpId}]}`
  - `manaCost[]` — for Kicker: additional cost breakdown
  - `autoTapSolution` — pre-computed mana tap (for Done/base cost)
- `allowCancel: Abort`
- `allowUndo: true`

### 2. MatchSession handler — `onCastingTimeOptionsResp`

Parse client response → extract chosen `ctoId` and modal selections →
translate to Forge's casting-time choice mechanism.

### 3. Forge engine integration

Forge handles modality via `SpellAbility.getTargetRestrictions()` and
`PlayerController.chooseModeForAbility()`. Kicker goes through
`SpellAbility.isKicked()` / `SpellAbility.addOptionalCost()`.

Need to identify the right `InteractivePromptBridge` hook or create one
that intercepts Forge's modal/kicker prompt and sends `CastingTimeOptionsReq`.

### 4. Golden test

Copy `000000235_MD_S-C_MATCH_DATA.bin` (modal) and `000000455_MD_S-C_MATCH_DATA.bin`
(kicker) → `src/test/resources/golden/`. Add field coverage tests.

## Key reference files

| File | Why |
|---|---|
| `RequestBuilder.kt` | Add `buildCastingTimeOptionsReq` |
| `BundleBuilder.kt` | Wire into bundle assembly |
| `MatchSession.kt` | Add `onCastingTimeOptionsResp` handler |
| `MatchHandler.kt` | Wire `ClientMessageType` dispatch |
| `ActionMapper.kt` | Already handles `CastingTimeOptionSummary` in decode |
| `RecordingDecoder.kt` | Already indexes `castingTimeOptions` in md-frames |

## Open questions

1. **Entwine/Multikicker** — do they use `castingTimeOptionType: Entwine` or
   reuse Modal/Kicker? Need recordings with these mechanics.
2. **Multiple required options** — can a spell have both Modal + Kicker?
   (e.g. kicked modal spell). Probably yes — the `castingTimeOptionReq` is
   a repeated field.
3. **autoTapSolution on kicker** — the Done option includes a pre-computed
   mana tap. Do we need to generate this? Probably yes for the client's
   auto-tap UI to work.
4. **C→S response format** — our decoder garbles the `CastingTimeOptionsResp`.
   Need to fix `decodeClientMessage` to handle this message type.
