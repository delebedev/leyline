# Surveil 2 — Live Wire Data (2026-03-21)

Recording: `recordings/2026-03-21_23-30-58/md-frames.jsonl`

---

## Card identity correction

The session requested analysis of grpId **93756** described as "Surveil 2, then draw a card."

Per Forge card data, grpId 93756 = **Inspiration from Beyond**:
> Mill three cards, then return an instant or sorcery card from your graveyard to your hand. Flashback {5}{U}{U}.

This is NOT a surveil card. It is a mill + graveyard return spell. **The recording contains zero Surveil ZoneTransfers** — confirmed by exhaustive search of the JSONL.

The "Surveil 2, then draw a card" card is **Discovery // Dispersal** (grpId=68688/68689). Discovery was not cast in this session.

Despite the mismatch, the live wire data here is highly valuable: Inspiration from Beyond uses `SelectNreq/SelectNresp` as its selection modal, which has direct implications for how we expected surveil to work.

---

## Instance identities

| Instance | grpId | Role |
|---|---|---|
| iid=293 | 93756 | Card in hand (pre-cast) |
| iid=333 | 93756 | Spell on stack (post ObjectIdChanged 293→333) |
| iid=342 | 93756 | Spell in graveyard post-resolve (Resolved→GY) |

The card was cast once on turn 9 (Main 1). It remains in GY with flashback available (confirmed in ActionsAvailableReq frames at lines 663, 785, 895 — `abilityGrpId=16080`). It was not cast again within the session.

---

## Full wire sequence — cast and resolution

### GSM msgId=249 gsId=191 (line 487) — Cast

```
ObjectIdChanged:  aff=[293]  orig_id=293  new_id=333
ZoneTransfer:     aff=[333]  zone_src=31(Hand) → zone_dest=27(Stack)  category="CastSpell"
ManaPaid × 3:     aff=[333]  colors=2,2,2 (3 blue mana)
UserActionTaken:  aff=[333]  affectorId=1  actionType=1 (cast)
```

iid=293 (card in hand) renamed to iid=333 (spell on stack). Three mana islands tapped.

### GSM msgId=251 gsId=193 (line 491) — Resolution + Mill 3

```
ResolutionStart:  affectorId=333  aff=[333]  grpid=93756

ObjectIdChanged:  affectorId=333  aff=[172]  orig_id=172  new_id=337
ZoneTransfer:     affectorId=333  aff=[337]  zone_src=32(Library) → zone_dest=33(GY)  category="Mill"

ObjectIdChanged:  affectorId=333  aff=[173]  orig_id=173  new_id=338
ZoneTransfer:     affectorId=333  aff=[338]  zone_src=32(Library) → zone_dest=33(GY)  category="Mill"

ObjectIdChanged:  affectorId=333  aff=[174]  orig_id=174  new_id=339
ZoneTransfer:     affectorId=333  aff=[339]  zone_src=32(Library) → zone_dest=33(GY)  category="Mill"
```

Three cards milled from library top. Each gets its own ObjectIdChanged + ZoneTransfer pair. Category is `"Mill"` (not "Surveil" — this is not a surveil effect).

Milled cards:
- iid=337 (grpId=94116 — Gate land)
- iid=338 (grpId=95194 — Basic Island)
- iid=339 (grpId=93661 — Opt, an Instant)

This GSM also contains updated zone lists: Library (zoneId=32) shrunk by 3, GY (zoneId=33) gained 337/338/339.

### SelectNreq msgId=252 gsId=193 (line 493) — Return modal

```json
{
  "greType": "SelectNreq",
  "msgId": 252,
  "gsId": 193,
  "promptId": 13393,
  "systemSeatIds": [1],
  "promptType": "SelectNReq",
  "promptData": {
    "minSel": 1,
    "maxSel": 1,
    "context": "Resolution_a163",
    "optionContext": "Resolution_a9d7",
    "listType": "Dynamic",
    "ids": [301, 330, 339],
    "prompt": { "parameters": [{ "parameterName": "Parameter", "type": "PromptId", "promptId": 1 }] },
    "idType": "InstanceId_ab2c",
    "sourceId": 333,
    "validationType": "NonRepeatable",
    "minWeight": -2147483648,
    "maxWeight": 2147483647
  }
}
```

**Key observations:**

- Message type: `SelectNreq` (not `GroupReq`). This is the real server's modal for "choose one from list."
- `ids=[301, 330, 339]` — ALL eligible cards in the GY (instant/sorcery cards owned by seat 1), not only the three just milled. iid=301 and iid=330 were already in GY before this cast.
  - iid=301 = Rise of the Dark Realms (grpId=93896, Sorcery) — already in GY
  - iid=330 = Stab (grpId=93784, Instant) — already in GY
  - iid=339 = Opt (grpId=93661, Instant) — just milled this turn
- `minSel=1, maxSel=1` — exactly one card must be chosen.
- `sourceId=333` — the resolving spell's instanceId (iid=333 on stack).
- `promptId=13393` — unique per cast; distinct from the common promptId=1024 used for other SelectNreq prompts in this session.
- `context="Resolution_a163"`, `optionContext="Resolution_a9d7"` — same as all other SelectNreq prompts in this session.
- `listType="Dynamic"` — card list determined at resolution time.
- `idType="InstanceId_ab2c"` — ids are instanceIds, not grpIds.

**Same GSM as ResolutionStart** (gsId=193) — the SelectNreq is sent in the same game state message as the mill annotations. The modal appears mid-resolution.

### SelectNresp gsId=193 (line 495) — Player choice

```json
{ "greType": "SelectNresp", "gsId": 193, "clientType": "SelectNresp" }
```

Minimal decoded representation. Player selected iid=330 (Stab). No selected IDs in the decoded JSON — choice is inferred from subsequent GSM.

### GSM msgId=253 gsId=194 (line 497) — Return + ResolutionComplete

```
ObjectIdChanged:  affectorId=333  aff=[330]  orig_id=330  new_id=340
RevealedCardCreated:  affectorId=341  aff=[341]  (no details)
ZoneTransfer:     affectorId=333  aff=[340]  zone_src=33(GY) → zone_dest=31(Hand)  category="Return"
ResolutionComplete:  affectorId=333  aff=[333]  grpid=93756
ObjectIdChanged:  affectorId=1   aff=[333]  orig_id=333  new_id=342
ZoneTransfer:     affectorId=1   aff=[342]  zone_src=27(Stack) → zone_dest=33(GY)  category="Resolve"
```

Persistent annotations delta:
```
EnteredZoneThisTurn (id=9):  affectorId=31(Hand)  aff=[340, 332]   -- 340 newly added
EnteredZoneThisTurn (id=11): affectorId=33(GY)    aff=[342,339,338,337]
InstanceRevealedToOpponent (id=475): affectorId=340  aff=[340]     -- new persistent ann
```

Deleted persistent annotation ids: [472, 4]

Objects in this GSM:
- iid=330 (Stab, grpId=93784) — still in Limbo (zone 30) as Public card
- iid=333 (Inspiration from Beyond, grpId=93756) — still in Limbo (zone 30) as Public
- iid=340 (Stab, grpId=93784) — in Hand (zone 31), visibility=Private, type=Card
- iid=341 (Stab, grpId=93784) — in Hand (zone 31), type=**RevealedCard**, visibility=Public
- iid=342 (Inspiration from Beyond, grpId=93756) — in GY (zone 33), type=Card, visibility=Public

**Key observations:**

1. The returned card (iid=330→340) gets a RevealedCard proxy (iid=341) simultaneously. The proxy and the real card both appear in Hand zone with different types (RevealedCard vs Card). This matches the explore/reveal pattern documented in `explore-reveal-gaps.md`.

2. `InstanceRevealedToOpponent` (persistent annotation id=475) is added for iid=340 — the actual hand card, not the proxy. Persists so opponent knows which specific card went there.

3. The spell itself (iid=333→342) is renamed and sent to GY with `category="Resolve"`, `affectorId=1` (seatId=1 — the player). This is standard for non-exile spells that resolve.

4. No draw annotation anywhere — this card's effect is "mill then return," not "mill then draw." There is no draw step for Inspiration from Beyond.

---

## Critical finding: SelectNreq, not GroupReq

The prior wire spec (`2026-03-21-surveil2-wire-spec.md`) assumed surveil uses `GroupReq/GroupResp`. This recording shows the real server uses `SelectNreq/SelectNresp` for a "choose one card from a list" effect.

**Implication for surveil:** True surveil 2 (e.g., Discovery) likely also uses `SelectNreq/SelectNresp`, NOT `GroupReq/GroupResp`. The existing codebase (`GsmBuilder.buildSurveilScryGroupReq`, `TargetingHandler.sendGroupReqForSurveilScry`) sends GroupReq — this is likely wrong.

However, scry 1 from the 2026-03-08 retro used what protocol? That needs cross-check. If scry also uses SelectNreq, the GroupReq machinery is unused entirely.

There is also a prior `SelectNreq` at line 219 (msgId=116, promptId=1024, `context="Resolution_a163"`, `ids=[161,163,165,293,300]`, `sourceId=295`) — appears to be a "choose which card to draw/find" from a different spell. The pattern is consistent.

---

## Wire differences: Mill vs Surveil

This recording used `category="Mill"` for library→GY transfers. For actual surveil, we expect `category="Surveil"` (per `sba-categories.md` reasoning, and from code in `TransferCategory.kt`). Not confirmed from live wire — zero Surveil transfers observed in this session. The scry annotation bugs doc (`scry-annotation-bugs.md`) is the closest related prior art.

---

## Zone map (confirmed from wire)

| zoneId | type | owner |
|---|---|---|
| 18 | Revealed | 1 |
| 27 | Stack | 0 |
| 30 | Limbo | 0 |
| 31 | Hand | 1 |
| 32 | Library | 1 |
| 33 | Graveyard | 1 |

---

## What we still need

1. **A true Surveil 2 cast.** Discovery // Dispersal (grpId=68689) or Notion Rain in a recording. Must confirm SelectNreq shape for surveil, and whether ZoneTransfer uses `category="Surveil"`.

2. **Surveil split scenarios.** Both-top, both-GY, and 1-top-1-GY paths. The SelectNreq for surveil 2 with `minSel=0, maxSel=2` (?) or some other bounds needs confirmation.

3. **Draw annotation post-surveil.** Discovery draws a card after surveiling. What GSM contains the draw? Is it in the same gsId as ResolutionComplete, or a subsequent one?

4. **GroupReq usage audit.** Determine whether GroupReq is used anywhere in real server protocol, or if SelectNreq handles all "choose from list" mechanics. May need to flag GroupReq machinery for replacement.

---

## Action items for engine-bridge agent

- Flag `GsmBuilder.buildSurveilScryGroupReq` — real server may use `SelectNreq` instead of `GroupReq` for surveil. High confidence based on this recording's Mill+Return pattern using `SelectNreq`.
- Flag `TargetingHandler.sendGroupReqForSurveilScry` / `onGroupResp` — same concern.
- The `player.allCards` bug in multi-card GroupResp may be moot if GroupReq is not the right protocol. However, if GroupReq is used anywhere else, the bug still applies.
- The "premature GroupResp" issue (surveil-status.md) may also dissolve if surveil switches to SelectNreq.
