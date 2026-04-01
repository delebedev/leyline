# Temple Garden — Card Spec

## Identity

- **Name:** Temple Garden
- **grpId:** 68739
- **Set:** GRN
- **Type:** Land — Forest Plains
- **Cost:** (no mana cost)
- **Forge script:** `forge/forge-gui/res/cardsfolder/t/temple_garden.txt`

## Mechanics

| Mechanic | Forge DSL | Catalog status |
|----------|-----------|----------------|
| Dual land (Forest + Plains) | `Types:Land Forest Plains` | wired |
| Mana: {T}→{G} | abilityGrpId=1001 | wired |
| Mana: {T}→{W} | abilityGrpId=1005 | wired |
| ETB replacement: may pay 2 life or enter tapped | `R:Event$ Moved … ReplaceWith$ DBTap` | **missing** |

## What it does

1. **ETB replacement effect fires as the land enters.** Before the land resolves to the battlefield, the server pauses and offers a modal prompt.
2. **Player accepts (pays 2 life):** the land enters untapped. ModifiedLife -2 is emitted in the resolution diff.
3. **Player declines:** the land enters tapped (`isTapped: true` on the gameObject in the resolution diff). No life loss.

## Trace (session 2026-03-29_17-04-26, seat 1)

Temple Garden was played **twice** in this session by seat 1 (human, full visibility). Two complete ETB flows were captured — one paying life (untapped), one declining (tapped).

| # | iid | gsId play | gsId prompt | gsId resolve | Outcome |
|---|-----|-----------|-------------|--------------|---------|
| 1 | 188→327 | 23 | 24 | 25 | Paid 2 life, entered untapped |
| 2 | 351→364 | 158 | 159 | 160 | Declined, entered tapped |

### Play 1 — paid 2 life (gsId 23–25, turn 2)

**PerformActionResp (gsId 23):** `actionType: Play, instanceId: 188, grpId: 68739`

**gsId 24 — replacement effect suspended (`updateType: Send`)**

The server allocates a new instanceId (327) for the landing card and suspends resolution:

```
gameObject { instanceId: 327, grpId: 68739, zoneId: 0, ... }  ← zone 0 = limbo/staging
persistentAnnotations {
  id: 73
  type: ReplacementEffect
  affectorId: 9002                    ← system seat (server) as affector
  affectedIds: [327]                  ← incoming land instance
  details { "grpid": 90846 }          ← replacement effect abilityGrpId
  details { "ReplacementSourceZcid": 188 }  ← zcid of hand card
}
```

Immediately after the GSM, the server sends `OptionalActionMessage` (same transaction):

```
OptionalActionMessage {
  sourceId: 9002
  prompt { promptId: 2233, parameters[0]: { "CardId": 327 } }
  allowCancel: No_a526
}
```

**Client response (OptionalActionResp) — body is empty.** An empty `OptionalActionResp` = **accept** (pay 2 life). No boolean field in the proto.

**gsId 25 — resolution (`updateType: SendHiFi`)**

```
annotations {
  id: 75  type: ObjectIdChanged   affectedIds: [188]
          details { orig_id: 188, new_id: 327 }
}
annotations {
  id: 76  type: ZoneTransfer      affectedIds: [327]
          details { zone_src: 31, zone_dest: 28, category: "PlayLand" }
}
annotations {
  id: 77  type: SyntheticEvent    affectorId: 9002  affectedIds: [1]
          details { type: 1 }
}
annotations {
  id: 78  type: ModifiedLife      affectorId: 9002  affectedIds: [1]
          details { life: -2 }     ← life payment here
}
annotations {
  id: 79  type: UserActionTaken   affectorId: 1  affectedIds: [327]
          details { actionType: 3, abilityGrpId: 0 }
}
```

Player life total in `players {}`: 18 (down from 20, confirming -2).
gameObject: `instanceId: 327, zoneId: 28, isTapped: (absent = false)` — entered **untapped**.

ReplacementEffect pAnn 73 is deleted (`diffDeletedPersistentAnnotationIds: 73, 74`) in this diff.
A `ColorProduction` pAnn (id: 81) is added on 327 with values `[1, 5]` = White + Green.
An `EnteredZoneThisTurn` pAnn (id: 5) is added: `affectorId: 28, affectedIds: [327]`.

### Play 2 — declined (entered tapped) (gsId 158–160, turn 8)

**PerformActionResp (gsId 158):** `actionType: Play, instanceId: 351, grpId: 68739`

**gsId 159 — identical pre-resolution structure:**

```
gameObject { instanceId: 364, grpId: 68739, zoneId: 0, ... }
persistentAnnotations {
  id: 347
  type: ReplacementEffect
  affectorId: 9007                    ← different system affectorId this game
  affectedIds: [364]
  details { "grpid": 90846, "ReplacementSourceZcid": 351 }
}
OptionalActionMessage { sourceId: 9007, prompt { promptId: 2233, CardId: 364 }, allowCancel: No_a526 }
```

**Client response — also an empty `OptionalActionResp`.** Both accept and decline are empty.

**gsId 160 — resolution (`updateType: SendAndRecord`)**

```
annotations { id: 349  type: ObjectIdChanged  orig_id: 351 → new_id: 364 }
annotations { id: 350  type: RevealedCardDeleted  affectorId: 352  affectedIds: [352] }
annotations {
  id: 351  type: ZoneTransfer  affectedIds: [364]
           details { zone_src: 31, zone_dest: 28, category: "PlayLand" }
}
annotations {
  id: 352  type: UserActionTaken  affectorId: 1  affectedIds: [364]
           details { actionType: 3, abilityGrpId: 0 }
}
```

gameObject: `instanceId: 364, zoneId: 28, isTapped: true` — entered **tapped**.
**No ModifiedLife annotation.** No SyntheticEvent. Life stays at whatever it was before.
ReplacementEffect pAnn 347 is deleted.

### Protocol disambiguation: accept vs. decline

Both `OptionalActionResp` messages are byte-identical (empty proto body). The server's outcome determines the wire shape:

| Choice | OptionalActionResp body | ModifiedLife in next diff | isTapped on gameObject |
|--------|------------------------|--------------------------|------------------------|
| Pay 2 life | empty | `life: -2` | absent (false) |
| Decline | empty | absent | `true` |

The client's intent is inferred from… nothing visible on the wire. The server appears to track which "option" the UI offered as the primary accept action. The `allowCancel: No_a526` flag means the prompt cannot be dismissed — one of two options must be chosen. The Arena UI button layout determines which action `OptionalActionResp` means.

**Working hypothesis:** `OptionalActionResp` = "take the optional action" (pay life = primary). A secondary/cancel path for "don't pay" may use a different message type that was not decoded by the JSONL tool (possibly `PerformActionResp` or an undecoded field). This is a gap — see Gaps section.

## Annotations — full summary

### ReplacementEffect pAnn (type appears in rosetta as type name `ReplacementEffect_803b`)

- Fires on `Play` of any shock land
- `affectorId`: server system seat (9002, 9007, etc. — not stable across plays)
- `affectedIds`: incoming land instanceId (the new id, not the hand id)
- `details["grpid"]`: `90846` — the replacement effect's ability grpId
- `details["ReplacementSourceZcid"]`: zcid of the hand copy (pre-ObjectIdChanged id)
- Lives in `persistentAnnotations` for the duration of the prompt (gsId 24 / 159)
- Deleted via `diffDeletedPersistentAnnotationIds` in the resolution diff

### Post-ETB persistent annotations

- `EnteredZoneThisTurn`: `affectorId=28` (zoneId of Battlefield), `affectedIds=[landIid]`
- `ColorProduction`: `affectorId=landIid`, `affectedIds=[landIid]`, details `colors=[1,5]` (White=1, Green=5)

### uniqueAbilities on Temple Garden gameObject

```
uniqueAbilities { id: N    grpId: 1001 }   ← {T}: Add {W}   (Plains mana)
uniqueAbilities { id: N+1  grpId: 1005 }   ← {T}: Add {G}   (Forest mana)
uniqueAbilities { id: N+2  grpId: 90846 }  ← replacement effect
```

Three abilities; grpIds are stable across both plays. Ability instance `id` fields are local and increment across the game.

## Key findings

1. **OptionalActionMessage is the shock land prompt.** `promptId: 2233` is the shock land decision point. Same promptId on both plays — this is likely a stable identifier for the "pay life or enter tapped" choice. `sourceId = system seat id` (9002, 9007 — not stable; appears to be a server-assigned session affector id).

2. **OptionalActionResp body is always empty** — both accept and decline send identical empty messages. This is a significant gap for leyline: the server must infer choice from some signal not visible in the JSONL decode. Either:
   - The Arena client sends an additional field not decoded by the JSONL tool (e.g. a boolean `accepted` in the proto)
   - Or "cancel" uses a different response message (e.g. `PerformActionResp`)
   Further raw proto inspection needed.

3. **Tapped state is encoded on the gameObject, not via TappedUntapped annotation.** When entering tapped, `isTapped: true` appears directly on the gameObject in the resolution diff. No `TappedUntapped` transient annotation fires. This is the ETB-tapped pattern (distinct from mana-tap, which does emit `TappedUntapped`).

4. **ModifiedLife is the life-payment confirmation.** `affectorId: 9002` (system), `affectedIds: [1]` (seatId of the paying player), `details { life: -2 }`. Accompanies `SyntheticEvent` (type: 1) in same diff. These appear only in the pay-life path.

5. **ZoneTransfer category is always "PlayLand"** — same as basic lands. The shock land's replacement effect does not change the ZoneTransfer category.

6. **Dual land subtypes confirmed:** `subtypes: [Forest, Plains]` on the gameObject in all diffs. The client derives mana ability display from these subtypes.

7. **abilityGrpId 90846 = shock land replacement effect.** Present in `uniqueAbilities` on every Temple Garden object. This grpId applies to all 10 Ravnica shock lands — they all use the same replacement ability text.

8. **No ETB trigger.** Temple Garden generates no `AbilityInstanceCreated`. The replacement effect fires before the land enters, not as a triggered ability after.

9. **ColorProduction pAnn.** Added at resolution with `colors: [1, 5]` (int32 list). Color values match other dual lands — 1=White, 5=Green. First confirmed shock land ColorProduction observation.

## Gaps for leyline

1. **OptionalActionMessage handler.** leyline does not implement `OptionalActionMessage` / `OptionalActionResp` for ETB replacements. This covers all 10 shock lands plus any other "may pay X" replacement effects. `promptId: 2233` and `sourceId: <system_affector>` need to be emitted.

2. **Accept vs. decline disambiguation.** The JSONL decode shows both responses as empty. Raw proto inspection of the C-S payload files (`000000037_MD_C-S_DATA.bin` for accept, `000000117_MD_C-S_DATA.bin` for decline) may reveal a boolean field not surfaced by the decoder. This must be resolved before the handler can work. Flag for engine-bridge.

3. **ReplacementEffect pAnn.** leyline must emit `ReplacementEffect` pAnn (`affectorId=<system>`, `affectedIds=[newIid]`, `grpid=90846`, `ReplacementSourceZcid=<handIid>`) in the pre-resolution diff and delete it in the resolution diff.

4. **ETB-tapped encoding.** When player declines, the gameObject must carry `isTapped: true` in the resolution diff. No `TappedUntapped` annotation should fire. This is distinct from the mana-tap path.

5. **ModifiedLife + SyntheticEvent pair.** When player accepts, both must be emitted in the resolution diff. `SyntheticEvent { type: 1 }` always co-occurs with `ModifiedLife` for life-payment costs.

6. **ColorProduction pAnn.** Must be added at ETB for dual lands (and any other mana-producing permanents). Colors encoded as int32 list matching Arena's color enum.

## Supporting evidence needed

- [ ] Raw proto decode of `000000037_MD_C-S_DATA.bin` and `000000117_MD_C-S_DATA.bin` to confirm accept vs. decline disambiguation
- [ ] Other shock lands (Hallowed Fountain, Sacred Foundry, etc.) — confirm `grpid: 90846` is consistent across all 10
- [ ] Breeding Pool (GRN) — another G/U shock land; confirm ColorProduction colors=[3,5] (Blue=3, Green=5)
- [ ] Shock land decline at low life (< 2) — does server suppress the OptionalActionMessage, or always show it and auto-decline?
- [ ] Confirm `promptId: 2233` stability across sessions and Arena patches

## Agent Feedback

**JSONL decode misses C-S message bodies.** The most significant pain point in this spec: both `OptionalActionResp` messages appear as empty objects in the JSONL, making it impossible to determine the accept/decline signal from JSONL alone. The `just wire` tool and `md-frames.jsonl` decode S-C traffic well but C-S payloads lose proto field detail. `just tape proto show` covers S-C; there's no equivalent for C-S files. A `tape proto show-cs` command that decodes client-to-server frames by gsId would close this gap.

**`tape proto show` does not filter to a single gsId cleanly on shared bin files.** When multiple gsIds share a `.bin` file (common for `QueuedGameStateMessage` batches in the seat-2 stream), `show` dumps the entire file and grep is needed to isolate the target gsId's content. A `--gsid` filter flag would make the command more precise.

**`allowCancel: No_a526` semantics are opaque.** The flag name suggests it prevents cancellation, but since both "accept" and "decline" use the same `OptionalActionResp` message type, what does "cancel" mean here vs. "decline"? The distinction is not surfaced anywhere in the tooling output. Better prompt semantics documentation (or a field in the proto summary) would help.
