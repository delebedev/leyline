# Spinner of Souls — Card Spec

## Identity

- **Name:** Spinner of Souls
- **grpId:** 93825 (FDN)
- **Ability grpId:** 175878 (triggered ability — "dig until creature")
- **Set:** FDN (Foundations)
- **Type:** Creature — Spider Spirit
- **Cost:** {2}{G}
- **P/T:** 4/3
- **Keywords:** Reach
- **Forge script:** `forge/forge-gui/res/cardsfolder/s/spinner_of_souls.txt`

## What it does

1. **Reach** — static keyword, standard.
2. **Death trigger (may):** Whenever another nontoken creature you control dies, you may reveal cards from the top of your library until you reveal a creature card. Put that card into your hand and the rest on the bottom of your library in a random order.

## Mechanics

| Mechanic | Forge DSL | Wire annotation / message | Catalog status |
|----------|-----------|---------------------------|----------------|
| Reach | `K:Reach` | Standard keyword, client-side rendering | wired |
| Death trigger — nontoken creature | `T:Mode$ ChangesZone \| Origin$ Battlefield \| Destination$ Graveyard \| ValidCard$ Creature.!token+YouCtrl+Other` | `AbilityInstanceCreated` (affectorId=Spinner iid, source_zone=28 Battlefield) | missing |
| Optional trigger ("you may") | `OptionalDecider$ You` | `OptionalActionMessage` (GRE message, not GSM) — client shows Decline / Take Action CTAs | missing |
| DigUntil — reveal until creature | `DB$ DigUntil \| Valid$ Creature \| FoundDestination$ Hand \| RevealedDestination$ Library \| RevealedLibraryPosition$ -1 \| RevealRandomOrder$ True` | `RevealedCardCreated` + `GameObjectType_RevealedCard` objects in Library zone (zoneId=32) + one in Revealed zone (zoneId=18) → `ZoneTransfer` Library→Hand (category=`Put`) | missing |
| Shuffle after reveal | implicit in DigUntil | `Shuffle` annotation (OldIds/NewIds for remaining library cards) | wired |
| RevealedCardCreated / Deleted lifecycle | — | `RevealedCardCreated` on resolution, `RevealedCardDeleted` when the revealed card is subsequently cast from hand | missing |

### Ability grpIds

| grpId | Description |
|-------|-------------|
| 93825 | Spinner of Souls (Creature — Spider Spirit, {2}{G}) |
| 175878 | Triggered ability — "dig until creature, put into hand" |

## Forge Script Decomposition

```
T:Mode$ ChangesZone | Origin$ Battlefield | Destination$ Graveyard
  | ValidCard$ Creature.!token+YouCtrl+Other
  | Execute$ TrigDigUntil
  | OptionalDecider$ You
  | TriggerDescription$ Whenever another nontoken creature you control dies, ...

SVar:TrigDigUntil:DB$ DigUntil
  | Valid$ Creature
  | ValidDescription$ creature that shares a creature type
  | FoundDestination$ Hand
  | RevealedDestination$ Library
  | RevealedLibraryPosition$ -1
  | RevealRandomOrder$ True
```

Key DSL observations:
- **`Mode$ ChangesZone`** — triggers on any zone change from BF to GY, filtered by `ValidCard$`.
- **`Creature.!token+YouCtrl+Other`** — not a token, you control it, not self.
- **`OptionalDecider$ You`** — "you may" ability. Server sends `OptionalActionMessage` to the controller; if declined, the ability is removed from the stack without resolving.
- **`DigUntil`** — reveals cards one at a time until the `Valid$` condition matches. The found card goes to `FoundDestination$` (Hand). The rest go to `RevealedDestination$` (Library) at `RevealedLibraryPosition$ -1` (bottom) in random order.
- **Note:** The `ValidDescription$` says "creature that shares a creature type" but the `Valid$` just says `Creature`. The ValidDescription appears to be flavor text for the UI prompt, not a filter — the actual filter is any creature card. This matches the Oracle text.

## Trace (session 2026-03-30_20-33, seat 1)

Two copies of Spinner cast. Both died in combat and triggered the DigUntil ability. Both triggers resolved (player chose "Take Action" for the may ability).

### Cast #1 — T12 Main1

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 270 | 320→342 | Hand→Stack | CastSpell, paid {2}{G} (ManaPaid: 2x color=4/Generic, 1x color=5/Green) |
| 272 | 342 | Stack→Battlefield | ResolutionStart + ResolutionComplete (grpid=93825) + ZoneTransfer Resolve |

### Cast #2 — T16 Main1

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 374 | 367→371 | Hand→Stack | CastSpell, paid {2}{G} |
| 376 | 371 | Stack→Battlefield | Resolve |

### Trigger #1 — T18 Combat/CombatDamage (first Spinner dies)

**Context:** Spinner #1 (iid 342, 4/3) blocked Shrine Keeper + dealt/received combat damage. Both Spinner #1 and the blocker died to SBA. Spinner #2 (iid 371) was on the battlefield as the trigger source.

| gsId | What happened |
|------|---------------|
| 436 | **DamageDealt** from=342→[286] damage=3 + from=286→[342] damage=3 (mutual combat). `AbilityInstanceCreated` from=371→[387] source_zone=28. Ability 387 (grpId=175878) goes on stack. Spinner #1 dies: iid 342→386, ZoneTransfer BF→GY(33) category=SBA_Damage. |
| 437 | HiFi update — opponent gets priority (priorityPlayer=2). No annotations. |
| 438 | **ResolutionStart** affectorId=387 grpid=175878. **`pendingMessageCount=1`** — signals OptionalActionMessage follows. |
| 439 | **DigUntil resolves.** Full sequence below. |

**Trigger #1 resolution (gs=439) — detailed annotation sequence:**

1. `ObjectIdChanged` affectorId=387, orig_id=186→new_id=397 — the found creature card gets a new instanceId as it leaves the library.
2. `RevealedCardCreated` affectorId=398, affectedIds=[398] — a `GameObjectType_RevealedCard` (iid 398) appears in the **Revealed zone** (zoneId=18) AND in Hand zone (zoneId=31). The RevealedCard mirrors the real card (same grpId=94073, same cardTypes, P/T, etc.).
3. `ZoneTransfer` affectorId=387, affectedIds=[397], zone_src=32(Library)→zone_dest=31(Hand), category=`Put` — the creature card moves to hand.
4. `Shuffle` affectorId=387, affectedIds=[1] (player seat), OldIds=[179,180,181,182,183,184,185]→NewIds=[399,400,401,402,403,404,405] — 7 remaining library cards reshuffled (these are the "rest" that go to bottom in random order — the server shuffles the entire remaining library).
5. `ResolutionComplete` grpid=175878
6. `AbilityInstanceDeleted` affectorId=371, affectedIds=[387]

**RevealedCard objects at gs=439:**

9 `GameObjectType_RevealedCard` objects created — 8 in the Library zone (zoneId=32), 1 in the Hand zone (zoneId=31). These represent the cards revealed during the dig:

| instanceId | grpId | Zone | Card type | Notes |
|-----------|-------|------|-----------|-------|
| 389 | 70035 | Library(32) | Land | Revealed, not the target — stays in library |
| 390 | 70755 | Library(32) | Land | |
| 391 | 58453 | Library(32) | Land | |
| 392 | 58449 | Library(32) | Land | |
| 393 | 93920 | Library(32) | Sorcery (Red) | |
| 394 | 93826 | Library(32) | Enchantment (Green) | |
| 395 | 69397 | Library(32) | Land | |
| 396 | 94073 | Library(32) | Creature (Green, 6/6) | A non-chosen creature — dug past it |
| 398 | 94073 | **Hand(31)** | Creature (Green, 6/6) | **The chosen creature** — also in Revealed zone (18) |

The found card (iid 398, grpId=94073) appears in both the Revealed zone AND the Hand zone simultaneously. The revealed zone (zoneId=18) with `Visibility_Public` is what the client uses to display the "revealed cards" popup.

### RevealedCardDeleted — when the card is cast (gs=612)

| gsId | What happened |
|------|---------------|
| 612 | Player casts the card that was revealed (iid 397→474, Hand→Stack). `RevealedCardDeleted` affectorId=398, affectedIds=[398] fires in the same GSM. The revealed card overlay is cleaned up when the underlying real card leaves Hand. |

This means `RevealedCardCreated` creates a visual overlay that persists until the card leaves the zone where it was placed. The client displays a highlight/badge on the card in hand to show it was recently revealed.

### Trigger #2 — T26 Combat/CombatDamage (opponent creature dies)

Spinner #2 (iid 371) was still alive. An opponent creature (iid 449) died in combat.

| gsId | What happened |
|------|---------------|
| 661 | Combat damage dealt. `AbilityInstanceCreated` from=371→[503] source_zone=28. Creature 449 dies (BF→GY seat 2). |
| 662 | HiFi update — opponent priority. |
| 663 | **ResolutionStart** affectorId=503 grpid=175878. |
| 664 | **DigUntil resolves.** Found creature (grpId=93912, Goblin Berserker 1/1). Only 2 RevealedCard objects (1 enchantment in Library, 1 creature in Hand). Shuffle: OldIds=[208]→NewIds=[508] — only 1 card reshuffled (library was nearly empty). |

**Trigger #2 annotations (gs=664):**

1. `ObjectIdChanged` orig_id=209→new_id=506
2. `RevealedCardCreated` affectorId=507, affectedIds=[507]
3. `ZoneTransfer` zone_src=32→zone_dest=31, category=`Put`
4. `Shuffle` OldIds=[208]→NewIds=[508]
5. `ResolutionComplete` grpid=175878
6. `AbilityInstanceDeleted` affectorId=371→[503]

Same annotation shape as trigger #1 — consistent.

## Protocol Findings

### 1. OptionalActionMessage for "may" triggers

The "you may" pattern uses `OptionalActionMessage` (GRE message type 45), NOT a GSM action. Sequence:

1. Ability goes on stack (`AbilityInstanceCreated`)
2. Both players pass priority
3. `ResolutionStart` GSM fires with **`pendingMessageCount=1`**
4. `OptionalActionMessage` follows (separate GRE message) — client shows Decline / Take Action CTAs
5. Player responds with `OptionalResp` (`AllowYes` = take action, `CancelNo` = decline)
6. If accepted: DigUntil resolves in next GSM. If declined: `AbilityInstanceDeleted` without resolution.

**Note:** scry-ts does not capture `OptionalActionMessage` — it only parses GSMs from Player.log. The `pendingMessageCount=1` on the ResolutionStart GSM is the only protocol-visible signal that an optional prompt follows. The notes "Decline/Take Action CTAs visible" were observing the client UI response to this message.

### 2. RevealedCardCreated — lifecycle

- **Created:** When DigUntil resolves, ALL dug cards become `GameObjectType_RevealedCard` objects. They appear in the Library zone (zoneId=32) with `Visibility_Public`. The found card ALSO gets a RevealedCard mirror in the Revealed zone (zoneId=18, `Visibility_Public`).
- **Persists:** The RevealedCard overlay (iid 398) stays in the Hand zone after the real card (iid 397) moves there. This is a visual indicator in the client.
- **Deleted:** `RevealedCardDeleted` fires when the underlying card leaves the zone (e.g., cast from hand). No details on the annotation — just affectorId = affectedId = the RevealedCard instanceId.
- **Key:** RevealedCard objects have their own instanceIds, distinct from the real cards. They carry full card data (grpId, cardTypes, P/T, abilities) for client rendering.

### 3. Shuffle annotation after DigUntil

The server shuffles the entire remaining library after the DigUntil resolves — not just the revealed non-creature cards. OldIds contains ALL library card instanceIds that remain after the found card is removed; NewIds are fresh instanceIds. This matches the Oracle text ("rest on the bottom of your library in a random order") — since the rest go to the bottom randomly, and the library was being dug from the top, a full shuffle is the server's implementation.

### 4. ZoneTransfer category = "Put"

The found card moves Library→Hand with category `Put` (not `Draw`). This is correct — the Oracle says "put that card into your hand," not "draw." This distinction matters for cards that care about draw triggers.

### 5. AbilityInstanceCreated source

The trigger's `affectorId` is the Spinner that's ALIVE on the battlefield (iid 371, the second copy), not the creature that died. The `parentId` on the ability object (387) points to the source Spinner (371). The trigger fires from the battlefield (source_zone=28), consistent with "whenever another creature dies" — Spinner must be on the battlefield to trigger.

### 6. Spinner itself can trigger Spinner

The first Spinner dying (iid 342→386, T18) triggered the second Spinner's ability (iid 371→[387]). This is correct — "another nontoken creature you control" includes other Spinners. If only one Spinner is on the battlefield and IT dies, it would not trigger itself (the "Other" constraint in Forge: `ValidCard$ Creature.!token+YouCtrl+Other`).

## Gaps for leyline

1. **OptionalActionMessage handler.** The "you may" trigger pattern requires sending an `OptionalActionMessage` after `ResolutionStart` (with `pendingMessageCount=1`) and handling `OptionalResp`. If the player declines, emit `AbilityInstanceDeleted` without resolution. This is a horizontal blocker — any "may" triggered ability needs it.

2. **DigUntil resolution.** New ability effect: reveal cards from library until finding one matching a filter. Create `GameObjectType_RevealedCard` objects for all revealed cards, move the found card to the destination zone, shuffle the rest. Forge handles this via `DigUntilEffect.java`.

3. **RevealedCardCreated / RevealedCardDeleted annotations.** New annotation types. RevealedCardCreated fires during DigUntil resolution. RevealedCardDeleted fires when the underlying card changes zones later. The RevealedCard objects need their own instanceIds and full card data.

4. **Revealed zone (zoneId=18).** `ZoneType_Revealed` with `Visibility_Public`. The found card's RevealedCard mirror goes here (AND in the destination zone). The Library-zone RevealedCards stay in zoneId=32 but become `Visibility_Public`.

5. **ZoneTransfer category "Put".** Verify ActionMapper / annotation builder supports this category. It's distinct from "Draw" and "Resolve."

## Supporting evidence needed

- [ ] Game where the "may" trigger is DECLINED — to see the `OptionalResp(CancelNo)` → `AbilityInstanceDeleted` without DigUntil resolution
- [ ] Another DigUntil card (e.g., Brawl-legal creature with "reveal until") to confirm RevealedCard annotation shape is consistent
- [ ] Edge case: what if no creature is found (library runs out)? Forge handles this (all cards go to bottom in random order, nothing goes to hand) but wire shape unobserved

---

## Tooling Feedback

### What worked well

- **`just scry-ts trace "Spinner" --game`** — excellent for understanding the full card lifecycle. Auto-groups by turn/phase, shows all annotation types with key details. Found both copies, both triggers, all zone transitions. This is the go-to command for card investigation.
- **`just scry-ts gsm show N --game --json`** — essential for drilling into specific GSMs. Piping to `jq` for filtering RevealedCard objects, annotations, and action lists worked perfectly.
- **`just scry-ts board --game --gsid`** — good for battlefield context at trigger time. Immediately showed both Spinners on the field plus the blocker.
- **`just scry-ts game notes`** — the human notes with gsId references were a perfect starting point. Knowing exactly where to look (gs=438, gs=443) saved significant exploration time.
- **`just scry-ts gsm list --has RevealedCard --game --view annotations`** — found both trigger resolutions AND the RevealedCardDeleted when the card was cast. Good for annotation-type-scoped searches across a whole game.
- **`--game` substring matching** — typing `2026-03-30_20-33` instead of the full timestamp is convenient.

### What was missing or awkward

- **`trace --gsid --json` did not work.** Returned unparseable output (jq parse error on line 1). The `--json` flag on `trace` with `--gsid` filter appears broken — had to fall back to `gsm show --json` for each individual GSM. This was the biggest friction point.
- **No GRE message capture.** scry-ts only parses GSMs from Player.log. The `OptionalActionMessage` (GRE message type 45) is invisible. The only signal was `pendingMessageCount=1` on the preceding GSM. For investigating "may" abilities, order prompts, or any non-GSM GRE message, there's a blind spot. The human notes ("Decline/Take Action CTAs visible") were essential — without them, the optional trigger pattern would have been inferred indirectly.
- **RevealedCard objects not named in trace.** The trace shows `RevealedCardCreated from=398 → [398]` but doesn't say what card 398 is. Had to drill into `gsm show --json` to discover it was grpId=94073 (a 6/6 green creature). Adding the grpId or card name to RevealedCardCreated trace output would help.
- **No way to see ALL objects in a zone at a gsId.** Wanted to quickly list "what's in the Library zone at gs=439?" — had to filter the full GSM JSON. A `gsm show --zone Library --gsid 439` or `board --zone Library` would be useful.

### Wish list review

**Card name resolution in trace affector/affected IDs** — **Upvote.** This was the #1 pain point again. The trace shows `AbilityInstanceCreated from=371 → [387]` but doesn't say 371 is Spinner of Souls or that 387 is the DigUntil ability. Every non-trivial annotation requires a cross-reference to `gsm show --json`. Even just `from=371[Spinner of Souls]` would cut investigation time significantly.

**abilityGrpId → ability text lookup** — **Upvote.** Knowing that 175878 = "reveal cards until creature, put into hand" without checking the Forge script would help. Lower priority than card name resolution but still useful for ability-heavy cards.

**Opponent zone labeling (ours/theirs vs seat 1/seat 2)** — **Upvote.** The `board` command does this well already, but the `trace` and `gsm list --view annotations` outputs use raw seat numbers. Since you always know which seat you are (from the game metadata), labeling zones as "our Library" vs "their Graveyard" would be more natural.

### New wishes

- **GRE message capture.** Even a summary view of non-GSM GRE messages (OptionalActionMessage, OrderReq, SelectNReq, etc.) would fill the biggest blind spot. These messages are where player interaction happens for "may" abilities, ordering, and complex choices.
- **`trace --json` fix.** The `--json` flag on `trace` with `--gsid` filter needs fixing — currently produces unparseable output.
- **Zone contents query.** `gsm show --zone Library --gsid N` to list all objects in a specific zone at a given game state. Useful for DigUntil / reveal / search investigations.
