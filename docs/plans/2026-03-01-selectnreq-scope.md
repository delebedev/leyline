# SelectNReq: Deep-Cavern Bat / "Choose N from revealed" Implementation Scope

## What we captured

Session `2026-03-01_00-18-46`, gsId=51, file `000000943_MD_S-C_MATCH_DATA.bin`.

**Deep-Cavern Bat** (grpId 87246) ETB trigger resolves. Server sends `SelectNreq` asking
player to choose 1 of 2 nonland cards from opponent's hand:
- Hurloon Minotaur (instanceId 229, grpId 75515)
- Raging Goblin (instanceId 232, grpId 75522)

## Proto shape

```
selectNReq {
  minSel: 1
  maxSel: 1
  context: Resolution_a163
  optionContext: Resolution_a9d7
  listType: Dynamic
  ids: 229          ← instanceId choices
  ids: 232
  idType: InstanceId_ab2c
  sourceId: 294     ← ability instance on stack (abilityId 169561 → Deep-Cavern Bat)
  validationType: NonRepeatable
  prompt { parameters { parameterName: "Parameter"  type: PromptId  promptId: 1 } }
}
allowCancel: Continue
```

Wrapper also has `prompt.parameters` with `CardId: 294` and `CardId: 1`.

Client responds with `SelectNResp` containing the chosen instanceId.

## What cards use this

`SelectNreq` is the generic "choose N from a list" prompt. Cards that trigger it:
- **ETB hand disruption**: Deep-Cavern Bat, Thoughtseize-style effects, Gonti
- **Search/reveal**: cards that reveal top N and pick one (Collected Company style)
- **Pile splits**: Fact or Fiction (choose pile), though that may use different params
- **Modal choices**: any "choose one or more" during resolution

## What we need to build

### 1. RequestBuilder — `buildSelectNReq`
New builder method. Fields: `minSel`, `maxSel`, `context`, `ids` (instanceIds),
`sourceId`, `idType`, `listType`, `validationType`, `prompt`.

### 2. MatchSession handler — `onSelectNResp`
Parse client's `SelectNResp` → extract chosen instanceId(s) → translate to
Forge card IDs via `bridge.getForgeCardId()` → submit through prompt bridge.

### 3. Forge engine integration
Need to identify which Forge `GameEvent` or prompt corresponds to "choose from
revealed cards". Likely `SpellAbility.getTargetRestrictions()` or a
`PlayerController.chooseCardFromList()` call. Check if `InteractivePromptBridge`
already has a suitable hook or needs a new one.

### 4. Golden test
Copy `000000943_MD_S-C_MATCH_DATA.bin` → `src/test/resources/golden/select-n-req.bin`.
Add field coverage test in `GoldenFieldCoverageTest`.

## Key reference files

| File | Why |
|---|---|
| `RequestBuilder.kt` | Add `buildSelectNReq` (model on `buildSelectTargetsReq`) |
| `BundleBuilder.kt` | Wire into bundle assembly |
| `MatchSession.kt` | Add `onSelectNResp` handler |
| `MatchHandler.kt` | Wire `ClientMessageType` dispatch |
| `InteractivePromptBridge.kt` | Prompt submission |
| `recordings/2026-03-01_00-18-46/capture/payloads/000000943_MD_S-C_MATCH_DATA.bin` | Golden source |

## Open questions

1. **Is `SelectNreq` distinct from `SelectTargetsReq`?** Yes — SelectTargets is
   during casting (targeting), SelectN is during resolution (choosing). Different
   proto messages, different client UI flows.
2. **Does Forge fire a specific event for "choose from revealed"?** Need to check
   `PlayerController` for `chooseCardFromList` or similar.
3. **Can minSel != maxSel?** Probably yes (e.g. "choose up to 2"). Our one sample
   has minSel=maxSel=1.
