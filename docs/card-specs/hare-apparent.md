# Hare Apparent — Card Spec

## Identity

- **Name:** Hare Apparent
- **grpId:** 93728
- **Set:** FDN
- **Type:** Creature — Rabbit Noble
- **Cost:** {1}{W}
- **P/T:** 2/2
- **Forge script:** `forge/forge-gui/res/cardsfolder/h/hare_apparent.txt`

## Mechanics

| Mechanic | Forge DSL | Catalog status |
|----------|-----------|----------------|
| ETB trigger | `T:Mode$ ChangesZone ... Execute$ TrigToken` | wired |
| Token creation (X) | `DB$ Token | TokenAmount$ X | TokenScript$ w_1_1_rabbit` | wired |
| X = other Hare Apparents controlled | `X:Count$Valid Creature.namedHare Apparent+YouCtrl+Other` | wired |
| Deck size override ("any number") | `K:A deck can have any number of cards named CARDNAME` | **missing** |
| Simultaneous trigger ordering | OrderReq + SelectNreq(Stacking) | **missing** |

## What it does

1. **ETB trigger:** when Hare Apparent enters the battlefield, create X 1/1 white Rabbit creature tokens, where X = the number of other creatures you control named Hare Apparent.
   - First copy: enters alone → 0 tokens.
   - Second copy enters while first is on field → 1 token.
   - Third → 2 tokens. Etc.
2. **"Any number" rule:** a deck may contain any number of copies (overrides the normal 4-copy limit). This is a deckbuilding constraint, not a game rule — the client enforces it at deck construction.
3. **Simultaneous ETB (the interesting case):** when two Hare Apparents enter at the same time (e.g., Raise the Past returns two from GY), both triggers fire simultaneously. The server issues an **OrderReq** (GRE type 17) asking the active player to choose which trigger resolves first. Order matters: the trigger that resolves second counts one extra Hare Apparent already on the battlefield (from the first resolution), creating one more token.

## Trace (session 2026-03-29_16-45-39, seat 1)

GW Rabbits deck, 15 copies of Hare Apparent. Seat 1 = full visibility.

### Single ETB (gsId 109, iid 322, turn 6)

One Hare Apparent cast, one other already on battlefield → ETB trigger fires, resolves for 1 token.

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 109 | 322 | Stack→BF (27→28) | Cast resolves: ZoneTransfer category=`Resolve`, AbilityInstanceCreated for trigger iid 325 (abilityGrpId 175767) |
| 111 | 325 | Stack→(deleted) | Trigger resolves: ResolutionStart/Complete (grpid 175767), TokenCreated (iid 341, grpId 94160), AbilityInstanceDeleted |

**gsId 111 annotations:**
```
ResolutionStart  affectorId=325  grpid=175767
TokenCreated     affectorId=325  affectedIds=[341]
ResolutionComplete affectorId=325  grpid=175767
AbilityInstanceDeleted  affectorId=322  affectedIds=[325]
```

Token object: `grpId=94160, type=Token, subtypes=[Rabbit], power=1, toughness=1, objectSourceGrpId=93728`

### Two simultaneous ETBs via Raise the Past (gsId 243–251, turn 12)

Raise the Past (grpId 93735, iid 367/376) resolved, returning two Hare Apparent corpses (iids 356→372, 358→373) from GY to BF simultaneously. Both Hare Apparents were on the field before Raise the Past resolved — their ETB triggers were the only triggers fired.

| gsId | File | What happened |
|------|------|---------------|
| 243 | 000000146 | Raise the Past cast (iid 175→367, Hand→Stack, {1}{W}{G} paid across 4 lands) |
| 245 | 000000146 | Resolution: two ObjectIdChanged+ZoneTransfer(`Return`) for iids 356→372 and 358→373 (GY 33 → BF 28). Two new Hare Apparents appear with `hasSummoningSickness: true`. **pendingMessageCount: 1.** |
| 245 | 000000146 | **OrderReq** (msgId 318) appended to same GreToClientEvent — see below |
| 246 | 000000148 | Two AbilityInstanceCreated (iids 374/375, abilityGrpId 175767) in Pending zone (25). ResolutionComplete + ZoneTransfer(`Resolve`) for Raise the Past itself. **SelectNreq** (msgId 320, Stacking) |
| 247 | 000000150 | Both ability instances move Pending→Stack (zone 25→27): iids [375, 374] — 375 on top |
| 249 | 000000151 | **First resolve** (top of stack: iid 375, from Hare Apparent 373): 1 token created (grpId 94160, iid 377). Counts 1 "other" Hare Apparent (iid 372 was already on BF) |
| 251 | 000000152 | **Second resolve** (iid 374, from Hare Apparent 372): 2 tokens created (378, 379). Counts 2 "others" (373 + 377 token? — no, only *named* Hare Apparents) → 2 because both 372 and 373 are on BF when 374 resolves |

**Token count breakdown:**
- When 375 resolves: BF has iids 372 + 373. Trigger from 373 → counts 372 as "other" → 1 token.
- When 374 resolves: BF has iids 372 + 373 + token 377. Trigger from 372 → counts 373 as "other" → 2 tokens... wait.

Actually: the SelectNresp chose iid 375 to resolve first (player selected index 2 in SelectNreq, which was ids=[374,375]; index 2 = 375). After 375 resolves, stack has only 374 left. 374 fires from 372 — counts all other Hare Apparents on BF = 372 is the source so "other" = 373 → 1 token created (iid 378). Final token total: 2 Rabbit tokens from the pair.

Total from full game: approximately 8+ Rabbit tokens across multiple turns.

## Annotations — key frames

### Frame 1: OrderReq (gsId 245, msgId 318)

Appended to the same `GreToClientEvent` as the Raise the Past resolution diff. The OrderReq arrives while the Raise the Past spell is resolving — before ability instances exist in the Pending zone.

```
type: OrderReq_695e
systemSeatIds: 1
msgId: 318
gameStateId: 245
prompt {
  promptId: 91
  parameters {
    parameterName: "CardId"
    type: Number
    numberValue: 367        # Raise the Past iid (the spell that caused simultaneous ETBs)
  }
}
orderReq {
  ids: 373                  # Hare Apparent iid on BF — ordered first (resolves last)
  ids: 372                  # Hare Apparent iid on BF — ordered second (resolves first)
}
allowCancel: No_a526
```

**Key observations:**
- `orderReq.ids` are the **permanent iids** (the source creatures), not ability instance iids. Ability instances don't exist yet at this point.
- `prompt.parameters[0]` carries the causing spell's instance iid (`numberValue: 367` = Raise the Past), not a grpId.
- `promptId: 91` — distinct from SelectNreq Stacking promptId (23). Each prompt type has its own ID namespace.
- `allowCancel: No_a526` — player must order; no pass option.
- The OrderResp (file 000000147) is 73 bytes and carries the selected ordering encoded in raw proto field 13.

### Frame 2: SelectNreq Stacking (gsId 246, msgId 320)

Arrives in the next GreToClientEvent, after ability instances materialize in the Pending zone (25).

```
type: SelectNreq
systemSeatIds: 1
msgId: 320
gameStateId: 246
prompt {
  promptId: 23
}
selectNReq {
  minSel: 1
  maxSel: 2
  context: TriggeredAbility_c799
  optionContext: Stacking
  listType: Dynamic
  ids: 374                  # ability instance iid (parentId: 372)
  ids: 375                  # ability instance iid (parentId: 373)
  idType: InstanceId_ab2c
  validationType: NonRepeatable
}
```

**Key observations:**
- `optionContext: Stacking` — same field used in discard/choose mechanics but with a different semantic: determines stack ordering.
- `ids` here are **ability instance iids** (in Pending zone 25), not permanent iids. This is the switch from OrderReq's permanent-level ordering.
- `context: TriggeredAbility_c799` distinguishes trigger stacking from other SelectNreq uses.
- `validationType: NonRepeatable` — player selects one at a time; the chosen one goes to top of stack.
- The SelectNresp (file 000000149) selects ids[1] (iid 375, from Hare Apparent 373) to go on top. Resolution order: 375 first, 374 second.

### OrderReq vs SelectNreq(Stacking) — protocol distinction

Two separate prompts serve two separate purposes:

| | OrderReq (type 17) | SelectNreq(Stacking) |
|---|---|---|
| When | While triggering spell is still on stack | After spell resolves, ability instances in Pending |
| ids contain | Permanent iids (sources) | Ability instance iids (the triggers) |
| promptId | 91 | 23 |
| optionContext | — (not present) | Stacking |
| Payload | `orderReq { ids: [...] }` | `selectNReq { ... optionContext: Stacking }` |

It is not yet clear whether both prompts are always sent for simultaneous triggers, or whether OrderReq is sometimes skipped (e.g., when the player has no real choice). This recording has only one data point.

## Gaps for leyline

1. **OrderReq (GRE type 17) not implemented** — `docs/rosetta.md:219` marks it MISSING. The engine currently resolves simultaneous triggers in arbitrary order without prompting. Required: decode OrderReq, send it to the active player, decode OrderResp, use the result to order ability instances when they materialize in Pending.

2. **SelectNreq(Stacking) pathway** — The follow-up SelectNreq uses `optionContext=Stacking`. The SelectNreq handler likely needs a dedicated branch for this context (distinct from choose/discard handling) that maps selected ability iids to stack insertion order.

3. **"Any number" deckbuilding flag** — `K:A deck can have any number of cards named CARDNAME`. The client validates deck construction — the leyline front door deck service may need to skip the 4-copy limit for cards with this keyword. Not a game-rules gap but a deck-import gap.

4. **Rabbit token grpId** — grpId `94160` observed in this session. Add to the token registry (SYNTHESIS.md Tier 1).

5. **Token count ordering correctness** — Forge's `Count$Valid Creature.namedHare Apparent+YouCtrl+Other` is evaluated at resolution time, so order of resolution determines count. This is correct Forge behavior; the leyline bug is entirely in the ordering prompt, not the Forge side.

## Supporting evidence — other cards that exercise OrderReq

Any simultaneous ETB scenario involving multiple triggers triggers OrderReq. Other candidates in the FDN card pool:

- **Guarded Heir** — ETB creates two Knight tokens; if two Guarded Heirs enter simultaneously the same ordering question applies.
- **Blossoming Sands** — ETB life-gain trigger; if two enter simultaneously the triggers are simultaneous.
- Any two different creatures with ETB triggers that enter via a mass-return effect (Raise the Past, Living Death, etc.).

The pattern generalizes: any time `pendingMessageCount > 0` accompanies a diff that ETBs multiple triggers owned by the same player, an OrderReq follows.

## Supporting evidence needed

- [ ] Confirm OrderResp raw encoding: field 13 in the `authenticateResponse` wrapper encodes the chosen ordering — decode it properly
- [ ] Observe OrderReq with 3+ simultaneous triggers to see if `orderReq.ids` grows linearly
- [ ] Confirm OrderReq is skipped when only one trigger fires (single ETB — gsId 111 in this session has no OrderReq)
- [ ] Observe trigger ordering when the active player passes — does a timeout auto-assign FIFO?
- [ ] Verify "any number" keyword handling in front door deck service
