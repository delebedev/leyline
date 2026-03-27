# Duress — Card Spec

## Identity

- **Name:** Duress
- **grpId:** 83792 (FDN printing; also 66175, 67868, 69882, 70141, 71878, 77508, 77571, 78439, 80868, 94034)
- **Set:** FDN (and many reprints)
- **Type:** Sorcery
- **Cost:** {B}
- **Ability grpId:** 21775
- **Forge script:** `forge/forge-gui/res/cardsfolder/d/duress.txt`

## Mechanics

| Mechanic | Forge DSL | Forge event | Catalog status |
|----------|-----------|-------------|----------------|
| Target opponent | `ValidTgts$ Opponent` | **none** (targeting is protocol-level, not engine event) | wired |
| Reveal hand | `Mode$ RevealYouChoose` (implicit) | `GameEventCardChangeZone` (cards become visible) | partial — RevealedCardCreated fires via `drainReveals()`, but RevealedCard proxy objects not synthesized, hand visibility flip missing |
| Choose noncreature nonland | `DiscardValid$ Card.nonCreature+nonLand` | **none** (engine resolves internally via `chooseSingleCard`) | **missing** — SelectNReq with filtered `ids` vs `unfilteredIds` not wired |
| Forced discard | `SP$ Discard \| NumCards$ 1` | `GameEventCardChangeZone` | wired (Hand→Graveyard, category Discard) |

## What it does

1. Target opponent reveals their hand.
2. Controller chooses a noncreature, nonland card from the revealed hand.
3. That player discards the chosen card.
4. If no valid choice exists (all cards are creatures and/or lands), the spell still resolves — the hand is revealed, no card is discarded.

## Trace (session 2026-03-27_23-55-23, seat 1)

Seat 1 (human) cast Duress twice. Cast 1 (instanceId 284) hit a hand of all creatures + lands — no valid discard target. Cast 2 (instanceId 370) hit a single Instant — forced discard.

### Cast 1 — instanceId 284 (no valid target)

#### Cast + Target (gsId 25–27)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 25 | 282→284 | Hand→Stack | ObjectIdChanged(282→284) + ZoneTransfer(31→27, CastSpell). SelectTargetsReq: target opponent (seatId=2), min/max=1, targetingAbilityGrpId=21775. |
| 26 | — | — | SelectTargetsReq re-sent (confirmation): target 2 selected, legalAction=Unselect. allowCancel=Abort. |
| 27 | — | — | PlayerSubmittedTargets(affectorId=1, affectedIds=[284]). ManaPaid. Priority passes. |

#### Resolution — reveal + empty choice (gsId 29–30)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 29 | 284 | Stack | ResolutionStart(grpid=83792). Zone 19 (Revealed, ownerSeat=2) populated with 6 RevealedCard proxies (iids 286–291). 6 real hand cards (222–227) appear as gameObjects with `visibility: Public`. **SelectNReq: `ids` is EMPTY, `unfilteredIds: [222,223,224,225,226,227]`.** All 6 cards are creatures or lands — no valid noncreature-nonland choice. |
| 30 | 284→292 | Stack→Graveyard | ResolutionComplete(grpid=83792). ObjectIdChanged(284→292) + ZoneTransfer(27→33, Resolve). Real hand cards (222–227) diffDeleted (become private again). |

#### RevealedCard proxy cleanup (gsId 47+)

Proxy 286 gets `RevealedCardDeleted` annotation + diffDeleted in gsId 47. Remaining proxies (287–291) are diffDeleted in subsequent gsIds without explicit `RevealedCardDeleted` annotations — staggered cleanup, not batch.

### Cast 2 — instanceId 370 (valid target found)

#### Cast + Target (gsId 288–290)

Same targeting pattern as Cast 1. SelectTargetsReq → PlayerSubmittedTargets for opponent (seatId=2).

#### Resolution — reveal + choose + discard (gsId 292–293)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 292 | 370 | Stack | ResolutionStart(grpid=83792). Zone 19 (Revealed, ownerSeat=2) with 1 RevealedCard proxy (iid 372). 1 real hand card (306, Instant grpId 75487) shown with `visibility: Public`. **SelectNReq: `ids: [306]`, `unfilteredIds: [306]`, minSel=1, maxSel=1.** Single valid target — must choose it. |
| 293 | 370→374 | Stack→Graveyard | ObjectIdChanged(306→373) + ZoneTransfer(35→37, Discard) — chosen card discarded from opponent's hand to opponent's graveyard. RevealedCardDeleted(372). ResolutionComplete(grpid=83792). ObjectIdChanged(370→374) + ZoneTransfer(27→33, Resolve) — Duress itself to graveyard. Hand zone 35 now empty (`viewers: 2` persists). |

### Annotations

**Cast (gsId 25):**
- `ObjectIdChanged` — orig_id=282 (hand), new_id=284 (stack)
- `ZoneTransfer` — zone_src=31 (Hand), zone_dest=27 (Stack), category="CastSpell"
- `PlayerSelectingTargets` — affectorId=1, affectedIds=[284]

**Resolution reveal (gsId 29):**
- `ResolutionStart` (id=90) — affectorId=284, affectedIds=[284], grpid=83792
- 6x `RevealedCardCreated` (ids 91,93,95,97,99,101) — one per hand card. affectorId=affectedIds=proxy instanceId (self-referential)

**SelectNReq (gsId 29, msgId 48) — the no-valid-target case:**
- `context: Resolution`, `optionContext: Resolution`, `listType: Dynamic`
- `idType: InstanceId`
- **`ids` field: EMPTY** (zero valid noncreature-nonland cards)
- `unfilteredIds: [222, 223, 224, 225, 226, 227]` (all 6 hand cards)
- `sourceId: 284` (the Duress on stack)
- `minSel/maxSel`: absent (defaults to 0) — choosing nothing is legal
- `allowCancel: No`
- `validationType: NonRepeatable`

**SelectNReq (gsId 292, msgId 393) — the valid-target case:**
- Same shape, but `ids: [306]`, `unfilteredIds: [306]`, **`minSel: 1`, `maxSel: 1`**
- Prompt has two parameters: CardId=370 (source) and CardId=1 (unknown — possibly player/count)

**Resolution discard (gsId 293):**
- `ObjectIdChanged` (id=657) — affectorId=370, orig_id=306, new_id=373. Note: affectorId is Duress (the cause), not the player.
- `RevealedCardDeleted` (id=658) — affectorId=372, affectedIds=[372] (proxy self-ref)
- `ZoneTransfer` (id=659) — affectorId=370, affectedIds=[373 (new id)], zone_src=35 (Hand), zone_dest=37 (opp Graveyard), category="Discard"
- `ResolutionComplete` (id=660) — grpid=83792
- `ObjectIdChanged` (id=661) — Duress itself 370→374
- `ZoneTransfer` (id=662) — Duress 374 to graveyard, zone_src=27 (Stack), zone_dest=33 (own Graveyard), category="Resolve"
- Persistent: `EnteredZoneThisTurn` for both discarded card (373→zone 37) and Duress (374→zone 33)

**TargetSpec persistent annotation (gsId 27):**
- affectorId=284, affectedIds=[2 (opponent seatId)]
- details: abilityGrpId=21775, index=1, promptParameters=284, promptId=1038

### Key findings

- **Empty `ids` = no valid choice.** When all revealed cards are creatures/lands, SelectNReq has `ids` empty but `unfilteredIds` populated. `minSel`/`maxSel` are absent (default 0), making "choose nothing" the only legal response. When a valid target exists, `minSel=1`/`maxSel=1` force a selection.
- **RevealedCard proxies live in zone 35 (Hand), NOT zone 19 (Revealed).** Zone 19 holds their instanceIds in its `objectInstanceIds` list, but the gameObjects themselves have `zoneId: 35`. The Revealed zone acts as an index — the proxies overlay the hand zone. This differs from the Concealing Curtains spec which assumed proxies occupy zone 19 itself.
- **RevealedCard type is `RevealedCard`, not `Card`.** Proxies mirror the real card's grpId, types, subtypes, P/T, abilities, but have `type: RevealedCard` and `viewers: 2`. Real cards simultaneously appear with `visibility: Public` (no `viewers` field).
- **Proxy cleanup is staggered.** Only one proxy gets `RevealedCardDeleted` annotation per gsId. Others are silently diffDeleted in subsequent gsIds. In the discard case (Cast 2), the single proxy gets both `RevealedCardDeleted` and `diffDeletedInstanceIds` in the same resolution gsId.
- **ZoneTransfer for forced discard has affectorId = spell instanceId**, not player seatId. ObjectIdChanged also uses the spell as affectorId. This differs from cleanup-discard (hand size) where affectorId is typically the player.
- **Opponent's hand zone (35) gains `viewers: 2`** but this persists even after resolution (visible in gsId 293 where hand is empty). The viewer count doesn't reset — the real privacy comes from real cards disappearing via diffDelete.
- **Two-parameter prompt on second SelectNReq.** Cast 2's prompt has `CardId=370` (source) AND `CardId=1`. Cast 1 only has `CardId=284`. The second parameter appears when there IS a valid selection.

## Gaps for leyline

1. **RevealedCard proxy synthesis.** When Forge resolves `RevealYouChoose`, leyline must synthesize `type: RevealedCard` gameObjects mirroring each hand card. Each proxy gets its own instanceId, `viewers: 2`, and lives at `zoneId: <hand-zone>`. Zone 19 (Revealed) lists the proxy instanceIds. `RevealedCardCreated` annotation per proxy. Catalog notes this is partial — `drainReveals()` fires the annotation but doesn't build the proxy objects.

2. **Hand card visibility flip.** During reveal, opponent's hand cards must appear as gameObjects with `visibility: Public` in the Diff. This makes the real cards visible alongside the proxies.

3. **SelectNReq for "choose from revealed" with filtering.** Must emit SelectNReq with:
   - `ids` = filtered list (noncreature-nonland only, can be empty)
   - `unfilteredIds` = all revealed cards
   - `minSel`/`maxSel` = 1/1 when `ids` is non-empty; absent (0/0) when empty
   - `sourceId` = spell instanceId
   - `context: Resolution`, `listType: Dynamic`, `idType: InstanceId`

4. **RevealedCard proxy cleanup.** After resolution, proxies need `RevealedCardDeleted` annotation + `diffDeletedInstanceIds`. Real server staggers cleanup (one per gsId) but batch-deleting in the resolution gsId would likely work for conformance.

5. **Forced discard affectorId.** The ZoneTransfer for "target player discards" must use the spell's instanceId as affectorId, not the player seatId. Same for the ObjectIdChanged on the discarded card.

6. **Update `docs/catalog.yaml`** — add or update entry for `reveal-choose-discard` mechanic documenting the SelectNReq shape and RevealedCard proxy requirements.

## Supporting evidence needed

- [ ] Cross-reference with Concealing Curtains spec (`docs/card-specs/concealing-curtains.md`) — same reveal+choose infrastructure but triggered from a creature transform, not a sorcery. Reveals "nonland" vs Duress's "noncreature nonland" filter. Confirm RevealedCard proxy zone placement matches (Curtains says zone 19; Duress shows zone 35 in gameObject.zoneId).
- [ ] Thoughtseize recording — same mechanic, different filter (nonland, nonartifact vs noncreature, nonland). Would confirm the SelectNReq shape is consistent across similar effects.
- [ ] Puzzle: Duress vs hand of mixed types — verify the filter logic produces correct `ids`/`unfilteredIds` split when some cards qualify and some don't.
- [ ] Puzzle: Duress vs empty hand — edge case, does SelectNReq even fire?
