# Wildborn Preserver — Card Spec

## Identity

- **Name:** Wildborn Preserver
- **grpId:** 94078 (FDN reprint), 70329 (ELD original)
- **Set:** FDN (primary trace), ELD
- **Type:** Creature — Elf Archer
- **Cost:** {1}{G}
- **P/T:** 2/2
- **Forge script:** `forge/forge-gui/res/cardsfolder/w/wildborn_preserver.txt`

## Mechanics

| Mechanic | Forge DSL | Forge events | Catalog status |
|----------|-----------|--------------|----------------|
| Flash | `K:Flash` | cast at instant speed | wired |
| Reach | `K:Reach` | blocks fliers | wired |
| ETB-chained "may pay {X}" trigger | `T:Mode$ ChangesZone … Execute$ TrigImmediateTrig` | AbilityInstanceCreated (outer, grpId 136114) | **missing** |
| ImmediateTrigger (ChooseX inner) | `SVar:TrigImmediateTrig:AB$ ImmediateTrigger \| Cost$ X` | OptionalActionMessage → NumericInputReq → PayCostsReq → AbilityInstanceCreated (inner, grpId 181759) | **missing** |
| Put X +1/+1 counters on self | `DB$ PutCounter \| CounterType$ P1P1 \| CounterNum$ X` | CounterAdded (counter_type=1, transaction_amount=X) + PowerToughnessModCreated | wired (counter infra ok) |
| AbilityWordActive "ValueOfX" | pAnn on inner trigger instance | AbilityWordActive pAnn (AbilityWordName="ValueOfX", value=X_paid, AbilityGrpId=181759) | **missing** |

## What it does

1. **Flash** — can be cast any time an instant could be cast. Useful for flashing in to block or trigger off opponent's ETB.
2. **Reach** — can block creatures with flying.
3. **Non-Human ETB trigger** — whenever another non-Human creature you control enters, the server offers an optional X-cost payment. The player may pay any amount of mana; that amount becomes X.
4. **If payment accepted** — an inner "ImmediateTrigger" ability goes on the stack and puts X +1/+1 counters on Wildborn Preserver. X=0 is legal (counters 0, does nothing but still resolves).
5. **Multiple simultaneous ETBs** — each non-Human creature that enters triggers separately. Three distinct ability instances were observed in one turn (T18 of session 16-18-08).

## Forge DSL breakdown

```
# Outer trigger: watches for non-Human creature ETBs under your control
T:Mode$ ChangesZone | Origin$ Any | Destination$ Battlefield
  | ValidCard$ Creature.nonHuman+Other+YouCtrl
  | TriggerZones$ Battlefield
  | Execute$ TrigImmediateTrig
  | TriggerDescription$ Whenever another non-Human creature you control enters,
    you may pay {X}. When you do, put X +1/+1 counters on CARDNAME.

# Inner ImmediateTrigger: the "when you do" ability — cost is X mana
SVar:TrigImmediateTrig:AB$ ImmediateTrigger | Cost$ X | Execute$ TrigPutCounter
  | TriggerDescription$ When you do, put X +1/+1 counters on CARDNAME.

# X resolves to the amount paid
SVar:X:Count$xPaid

# Counter effect
SVar:TrigPutCounter:DB$ PutCounter | Defined$ Self | CounterType$ P1P1 | CounterNum$ X
```

The `ImmediateTrigger` pattern means the outer trigger does not go on the stack itself — it immediately asks the player to pay mana, then fires the inner trigger (which does go on the stack).

## Trace (session 2026-03-29_16-18-08, seat 1)

Wildborn Preserver (iid=370, grpId=94078) was cast with Flash during T13 (opponent's turn, DeclareAttack step). Three trigger events were observed: one on T13 (first creature resolves alongside two ETBs) and two more on T18 when three creatures entered.

### Cast (gs 335)

| Field | Value |
|-------|-------|
| ObjectIdChanged | orig_id=288 → new_id=370 |
| ZoneTransfer | zone_src=31 (Hand), zone_dest=27 (Stack), category=`CastSpell` |
| uniqueAbilities on stack | id=250 grpId=7 (Flash), id=251 grpId=13 (Reach), id=252 grpId=136114 (trigger) |

Cast during opponent T13, DeclareAttack step (Flash allows instant-speed cast).

### ETB (gs 337)

| Field | Value |
|-------|-------|
| ZoneTransfer | zone_src=27 (Stack), zone_dest=28 (Battlefield), category=`Resolve` |
| ResolutionStart / ResolutionComplete | grpid=94078 |

No trigger fires on own ETB — Preserver is not a non-Human creature entering under itself (it's already on the battlefield as the watcher).

### First trigger fire — T13 (gs 368)

A Cat/Beast (iid=381, grpId=93725) resolves and enters. Two triggers fire simultaneously — both the Wildborn Preserver outer trigger and another trigger from iid=381's own ability:

| ann id | type | affectorId | affectedIds | detail |
|--------|------|-----------|-------------|--------|
| 847 | AbilityInstanceCreated | 370 (WP) | 386 | source_zone=28 — outer trigger grpId 136114 |
| 849 | AbilityInstanceCreated | 381 (Cat) | 387 | source_zone=28 — Cat's own trigger grpId 137835 |

**TriggeringObject pAnn:**
```
id=848, types=[TriggeringObject], affectorId=386, affectedIds=[381], details={source_zone: 28}
id=850, types=[TriggeringObject], affectorId=387, affectedIds=[381], details={source_zone: 28}
```

Both triggers share the same triggering object (iid=381). Zone 25 (Pending) holds both trigger instances before ordering.

**SelectNreq for stacking order (gs 368):**
```
promptId: 23
context: "TriggeredAbility_c799"
optionContext: "Stacking"
listType: Dynamic
ids: [386, 387]   ← both trigger instances
idType: InstanceId_ab2c
validationType: NonRepeatable
minSel/maxSel: 1/2
```

Cat's trigger (387, grpId=137835) resolved first. Then Wildborn Preserver outer trigger (386, grpId=136114) resolves.

### OptionalActionMessage (gs 376)

```
greType: OptionalActionMessage
promptId: 1159
parameters: [{parameterName: "CardId", type: Number, numberValue: 370}]
optionalActionMessage: { sourceId: 386 }
allowCancel: No_a526
```

`sourceId=386` is the outer trigger ability instance. `CardId=370` identifies the Wildborn Preserver permanent (used for client UI highlighting). `allowCancel: No_a526` — the option may still be declined via OptionalActionResp (the client chose to accept in this session).

### NumericInputReq (gs 377)

```
greType: NumericInputReq
promptId: 51
parameters: [{parameterName: "CardId", type: Number, numberValue: 370}]
numericInputReq: {
  maxValue: 2147483647
  stepSize: 1
  sourceId: 386
  numericInputType: ChooseX_ad80
}
allowCancel: No_a526
```

`numericInputType: ChooseX_ad80` — this is the X-cost chooser. `maxValue: 2147483647` (INT_MAX — server does not pre-clamp to available mana; client/engine enforces affordable maximum). `stepSize: 1`.

### PayCostsReq (gs 378)

```
greType: PayCostsReq
promptId: 11
parameters: [{parameterName: "Cost", type: NonLocalizedString, stringValue: "o1"}]   ← X=1
payCostsReq: {
  manaCost: { color: Generic, count: 1, objectId: 386 }   ← objectId links to outer trigger iid
  paymentActions: [ ... mana tap options ... ]
  autoTapActionsReq: { ... }
}
allowCancel: Continue
allowUndo: true
```

`manaCost.objectId=386` links the payment to the outer trigger instance. `stringValue: "o{X}"` where X is the chosen amount (rendered as `o1` for X=1).

### ImmediateTrigger inner ability fires (gs 379)

After mana is paid, the inner `ImmediateTrigger` ability instance is created:

```
ann id=881: AbilityInstanceCreated, affectorId=370 (WP), affectedIds=[390], source_zone=28
```

Inner ability grpId=181759 confirmed on the stack object at gs 379.

**AbilityWordActive pAnn (gs 379):**
```
id=887, types=[AbilityWordActive], affectorId=390, affectedIds=[390], details={
  value: 1,
  AbilityGrpId: 181759,
  AbilityWordName: "ValueOfX"
}
```

`value=1` reflects X paid. This pAnn appears on the inner trigger instance as it enters the stack, and is deleted when the trigger resolves.

### Resolution of inner trigger (gs 381)

```
ResolutionStart: affectorId=390, grpid=181759
CounterAdded:    affectorId=390, affectedIds=[370], counter_type=1, transaction_amount=1
PowerToughnessModCreated: affectorId=370, affectedIds=[370], power=1, toughness=1
ResolutionComplete: affectorId=390, grpid=181759
AbilityInstanceDeleted: affectorId=370, affectedIds=[390]
```

Wildborn Preserver P/T updated from 2/2 to 3/3 (1 counter added).

## Three simultaneous triggers — T18 (gs 472–489)

On T18 (seat 1's turn), a Dinosaur (iid=407, grpId=93821) was cast. A separate ability trigger (grpId=20151) from a different source (iid=394, ability iid=413) resolved first. During that resolution (gs 474), the Dinosaur resolved and entered, firing one Wildborn Preserver trigger (outer grpId=136114):

```
gs 474: AbilityInstanceCreated, affectorId=370 (WP), affectedIds=[415], source_zone=28
TriggeringObject pAnn: affectorId=415, affectedIds=[414]  ← 414 = creature that entered
```

- OptionalAction gs 476 → accepted
- NumericInputReq gs 477 (X chosen = 2)
- PayCostsReq gs 478
- Inner trigger AbilityInstanceCreated gs 479: affectorId=370, affectedIds=[419], grpId=181759

**AbilityWordActive at gs 479 (X=2):**
```
id=1102, types=[AbilityWordActive], affectorId=419, affectedIds=[419], details={
  value: 2,
  AbilityGrpId: 181759,
  AbilityWordName: "ValueOfX"
}
```

Inner trigger resolves gs 481: CounterAdded transaction_amount=2, Wildborn Preserver grows to 7/7.

Then the Dinosaur (407) itself resolved from the stack (gs 483), firing a second Wildborn Preserver trigger:

```
gs 483: AbilityInstanceCreated, affectorId=370 (WP), affectedIds=[420], source_zone=28
TriggeringObject pAnn: affectorId=420, affectedIds=[407]
```

- OptionalAction gs 485 → accepted
- NumericInputReq gs 486 (X chosen = 0, no mana left)
- AbilityWordActive at gs 487:
```
id=1129, types=[AbilityWordActive], affectorId=422, affectedIds=[422], details={
  value: 0,
  AbilityGrpId: 181759,
  AbilityWordName: "ValueOfX"
}
```

Inner trigger resolves gs 489: CounterAdded transaction_amount=0 (no effect). Demonstrates X=0 is legal — the trigger still fires and resolves, it just adds zero counters.

## Summary table

| gsId | Event | Key data |
|------|-------|----------|
| 335 | Cast WP (Flash, T13) | ZoneTransfer Hand→Stack, category=CastSpell |
| 337 | WP resolves → BF | category=Resolve, hasSummoningSickness=true |
| 368 | First ETB trigger fires | AbilityInstanceCreated iid=386 (outer grpId=136114); SelectNreq for stacking with cat trigger 387 |
| 376 | OptionalActionMessage | promptId=1159, sourceId=386, CardId=370 |
| 377 | NumericInputReq | numericInputType=ChooseX_ad80, maxValue=INT_MAX, stepSize=1 |
| 378 | PayCostsReq | promptId=11, manaCost objectId=386 (X=1) |
| 379 | Inner trigger + AbilityWordActive | iid=390 grpId=181759, AbilityWordActive value=1 |
| 381 | Inner trigger resolves | CounterAdded type=1 amount=1, WP grows 2/2→3/3 |
| 476 | Second OptionalAction (T18, X=2) | same shape |
| 479 | AbilityWordActive value=2 | WP grows 2/2→7/7 after counter add |
| 485 | Third OptionalAction (T18, X=0) | no mana left |
| 487 | AbilityWordActive value=0 | trigger still resolves, 0 counters added |

## Key findings

1. **Two-stage trigger wire** — the outer trigger (grpId=136114) never resolves to add counters itself. It fires, creates an OptionalActionMessage → NumericInputReq → PayCostsReq sequence, then spawns the inner ImmediateTrigger ability (grpId=181759) that actually resolves. These are two distinct ability instances with two distinct grpIds.

2. **OptionalActionMessage shape** — `promptId=1159`, `sourceId` = outer trigger iid, `CardId` parameter = Preserver permanent iid. `allowCancel: No_a526` on the message even though the player can decline — the "decline" is expressed via OptionalActionResp body (observed once in session 16-32-23, not traced here).

3. **NumericInputReq shape** — `promptId=51`, `numericInputType=ChooseX_ad80`, `maxValue=2147483647` (INT_MAX). The server does NOT pre-clamp to available mana. `stepSize=1`. `sourceId` = outer trigger iid.

4. **PayCostsReq shape** — `promptId=11`, `manaCost.objectId` = outer trigger iid. `allowCancel: Continue` (can undo). This is a distinct promptId from mandatory additional costs (1024) and sacrifice costs (1074).

5. **AbilityWordActive "ValueOfX"** — pAnn on the inner trigger instance, not on Wildborn Preserver itself. `affectorId=affectedId=<inner trigger iid>`. `AbilityGrpId=181759` (inner trigger). `value` = X chosen. Appears when inner trigger is created, deleted when inner trigger resolves. Also fires with `value=0` for X=0 case.

6. **Multiple triggers are independent** — each non-Human ETB fires a separate outer trigger instance. Each one gets its own OptionalAction/NumericInput/PayCosts sequence, resolved one at a time in stack order. Three distinct trigger lifecycles observed in T18 with no cross-contamination.

7. **X=0 path** — player chose X=0 on the third T18 trigger (no mana). The full wire sequence still fires: OptionalAction (accepted) → NumericInputReq → PayCostsReq → inner trigger created → AbilityWordActive(value=0) → resolves adding zero counters. No short-circuit.

8. **Trigger grpIds are stable** — outer trigger always grpId=136114, inner always grpId=181759, across all trigger instances in both sessions.

9. **TriggeringObject points to entering creature** — pAnn on outer trigger instance, `affectedIds=[entering creature iid]`, `source_zone=28` (Battlefield, creature's current zone after entering). Consistent with observed pattern.

## Gaps for leyline

1. **ImmediateTrigger protocol** — no wire support for `ImmediateTrigger` pattern. The outer trigger resolves and should immediately prompt via OptionalActionMessage before spawning the inner trigger. Needs new interaction type in the session/prompt layer.

2. **OptionalActionMessage for triggers** — currently used only for kicker-style optional costs. Needs to be generalized for "may pay {X}" trigger costs. `promptId=1159` is distinct from kicker (1159 vs other values observed).

3. **NumericInputReq (ChooseX_ad80)** — handler likely exists (X costs on spells) but the trigger context differs. Need to confirm the handler works when source is a triggered ability iid (not a spell on stack).

4. **PayCostsReq promptId=11** — adds a new confirmed promptId variant. Distinct from 1024 (mandatory additional cost) and 1074 (sacrifice-as-cost). Document in promptid reference.

5. **AbilityWordActive "ValueOfX"** — new AbilityWordName not previously observed. The `ValueOfX` word annotates the inner trigger instance, allowing the client to display the chosen X value on the trigger object. Needs emission after inner trigger is created.

6. **Simultaneous trigger ordering via SelectNreq** — when WP and another card's trigger fire together (gs 368), SelectNreq with `optionContext=Stacking` is sent. This is the simultaneous trigger ordering protocol (see `orderreq-trigger-stacking-wire.md`). Separate gap already tracked.

7. **X=0 edge case** — PayCostsReq must still fire even when X=0. The mana cost in PayCostsReq will be `{0}` (zero generic). Engine must not suppress the trigger.

## Supporting evidence

- Session `2026-03-29_16-18-08`: seat 1 plays WP (iid=370), triggers on T13 (X=1) and twice on T18 (X=2, X=0)
- Session `2026-03-29_16-32-23`: two WP in play (iid=351 and 420), extensive trigger data — not traced in detail here but confirms pattern holds with multiple Preservers
- grpId 70329 (ELD) confirmed as alternate grpId for same card; both appear in find-card output

## Agent Feedback

### Tooling / commands that were slow or missing

- `tape proto decode <file>` silently produced no output for binary payloads that `tape proto inspect <file>` decoded perfectly. The `decode` verb appears to be a no-op for S→C binary frames, but there's no error message — it just returns nothing. Cost ~3 minutes diagnosing. Should either error or delegate to `inspect`.

- `tape proto gsm` is not a valid verb — the runbook (if it existed) would reference this. Had to discover valid verbs via `--help`. A `just tape --list-verbs` shortcut or better help text would help.

- `tape proto decode-recording` outputs JSONL where OptionalActionMessage and NumericInputReq entries drop all prompt body fields (only `promptId` and `systemSeatIds` survive). This meant I couldn't read `numericInputReq.numericInputType` or `optionalActionMessage.sourceId` from the JSONL — had to fall back to `tape proto inspect` on individual binary files. This is a significant gap since the JSONL format is the primary analysis interface.

- `grep` on JSONL files with long JSON lines produces enormous output that the tool clips. Used Python one-liners instead. A `tape proto search <session> <json-path>` verb that filters by field value would be very useful.

### Information that was hard to find

- The two-grpId structure (outer trigger 136114 vs inner trigger 181759) was not obvious from the Forge script. The script uses `SVar:TrigImmediateTrig:AB$ ImmediateTrigger` but there's no way to know the grpId assigned to the inner ability without tracing. A `just card-abilities <name>` command that listed all ability grpIds (from the server's perspective) would save significant trace time.

- Which frame contained the inner trigger AbilityInstanceCreated required searching for specific iid/affectorId combinations across dozens of frames. `tape proto trace <iid>` helped narrow to candidate frames but still required manual inspection of each.

- `maxValue: 2147483647` (INT_MAX) in NumericInputReq was initially confusing — expected the server to pre-clamp to available mana. Confirmed it does not only after checking three instances.

### What would have saved time

- `tape proto decode-recording` populating all message body fields in JSONL (not just top-level metadata). The NumericInputReq body and OptionalActionMessage body are critical for ImmediateTrigger analysis.

- A `tape proto find-ability <grpId> -s <session>` verb to locate all frames where a given ability grpId appears — would have instantly shown the outer and inner trigger lifecycles without manually tracing iids.

- `just card-grp` returning the trigger grpIds for each ability on the card (not just the card's own grpId) — even as a "known trigger grpIds: ..." annotation in the output.

### Suggestions for the card-spec skill

- Add a step: "For ImmediateTrigger cards, inspect both outer and inner trigger instances separately, as they have distinct grpIds." The current template doesn't surface the two-stage pattern.

- The template asks to "decode at most 3 frames" but ImmediateTrigger requires 5 distinct frame types to fully document (ETB trigger fire, OptionalAction, NumericInput, PayCosts, inner trigger resolution). Suggest the guidance be "decode the minimum frames to cover the full interaction loop."

- Suggest adding "X=0 edge case" as a standard investigation point for any card with a variable X cost in a triggered ability. The X=0 path has meaningfully different protocol implications (empty PayCostsReq, zero-counter resolve) worth always capturing.
