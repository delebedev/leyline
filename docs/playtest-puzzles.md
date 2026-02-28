# Playtest Puzzles

FDN (Foundations) puzzles for manual playtesting of nexus annotation and protocol coverage.

```bash
just serve-puzzle src/test/resources/puzzles/fdn-keyword-combat.pzl
```

## Puzzle Index

| Puzzle | Tests | Annotation Types Exercised |
|--------|-------|---------------------------|
| `fdn-keyword-combat` | Combat with keyword creatures, pump spells | DamageDealt, ModifiedPower/Toughness, TappedUntapped, ModifiedLife (lifelink), ZoneTransfer (destroy) |
| `fdn-equip-enchant` | Equipment + aura casting, equip action | CreateAttachment, Attachment, RemoveAttachment (if destroyed), ModifiedPower/Toughness |
| `fdn-removal-bounce` | Targeted removal, zone transfers | ZoneTransfer (destroy via SBA, exile, bounce), DamageDealt (Stab P/T change) |
| `fdn-counters-tokens` | ETB triggers, +1/+1 counters, tokens | CounterAdded, TokenCreated, ZoneTransfer |

## What Each Puzzle Covers

### fdn-keyword-combat

Board with keyword-rich creatures on both sides. Player attacks, AI blocks.

| Card | Side | Keywords / Abilities | What to Test |
|------|------|---------------------|--------------|
| Vampire Nighthawk | Human | Flying, Deathtouch, Lifelink | Attack — AI can't block (no flier), life gain fires ModifiedLife |
| Healer's Hawk | Human | Flying, Lifelink | Evasion, lifelink stacks with combat damage |
| Savannah Lions | Human | Vanilla 2/1 | Ground attacker, can be blocked |
| Giant Growth | Human hand | +3/+3 instant | Cast during combat → ModifiedPower + ModifiedToughness annotations |
| Sure Strike | Human hand | +3/+0, first strike | First strike damage phase, P/T pump |
| Treetop Snarespinner | AI | Reach, Deathtouch | Blocks fliers, kills anything it touches |
| Crackling Cyclops | AI | 0/4, +3/+0 on noncreature cast | Wall-like blocker |

**Play pattern:** Declare attackers → AI blocks (Snarespinner can block fliers) → cast pump spell → observe P/T change + first strike + lifelink annotations.

### fdn-equip-enchant

Equipment and aura attachment workflow.

| Card | Side | Type | What to Test |
|------|------|------|--------------|
| Savannah Lions | Human BF | Creature 2/1 | Equip/enchant target |
| Llanowar Elves | Human BF | Creature 1/1, tap: add G | Mana source + alternate equip target |
| Quick-Draw Katana | Human hand | Equipment (2), equip 2 | Cast artifact → equip action → CreateAttachment + Attachment |
| Blanchwood Armor | Human hand | Aura (2G), +1/+1 per Forest | Cast aura → auto-attaches → P/T change (4 Forests = +4/+4) |
| Pacifism | Human hand | Aura (1W), can't attack/block | Cast on opponent creature → attachment on enemy |
| Courageous Goblin | AI BF | Creature 2/2 | Pacifism target |

**Play pattern:** Cast Quick-Draw Katana (artifact appears on BF) → equip to Savannah Lions (attachment annotations) → cast Blanchwood Armor on Savannah Lions (aura attachment + P/T buff) → cast Pacifism on opponent's Courageous Goblin (cross-seat attachment). Attack with buffed creature.

**Equip caveat:** Equip uses the Activate action type (2), which is wired but less tested than Cast/Play. If equip doesn't work, the attachment story is still testable via auras (auto-attach on cast).

### fdn-removal-bounce

Three targeted spells exercising different zone-transfer categories.

| Card | Cost | Target | Zone Transfer | Category |
|------|------|--------|---------------|----------|
| Stab | B | Savannah Lions (2/1) | BF → GY (0 toughness SBA) | Destroy (17) |
| Banishing Light | 2W | any nonland permanent | BF → Exile | Exile (16) |
| Unsummon | U | any creature | BF → Hand | Bounce (32) |

**Play pattern:** Cast Stab on Savannah Lions → -2/-2 makes it 0/-1 → SBA kills it → ZoneTransfer with Destroy category. Cast Banishing Light → ETB targeting → exile Courageous Goblin → ZoneTransfer with Exile category. Cast Unsummon → bounce Crackling Cyclops → ZoneTransfer with Bounce category.

**Banishing Light caveat:** ETB triggers with targeting use SelectTargetsReq, which is wired. The "until leaves" return clause works via engine SBAs but isn't tested in this puzzle.

### fdn-counters-tokens

ETB trigger chain with Good-Fortune Unicorn amplifying every creature entry.

| Card | Cost | What Happens |
|------|------|-------------|
| Good-Fortune Unicorn | (on BF) | Each creature entering → +1/+1 counter on it |
| Resolute Reinforcements | 1W | Enters (→ Unicorn gives it a counter) + creates 1/1 Soldier token (→ Unicorn gives token a counter) |
| Dwynen's Elite | 1G | Enters (→ counter) + you control Llanowar Elves (Elf) → creates 1/1 Elf Warrior (→ counter on token) |
| Snakeskin Veil | G | +1/+1 counter on target + hexproof |

**Expected annotation sequence for Resolute Reinforcements:**
1. ZoneTransfer (Hand → BF, Resolve)
2. CounterAdded (+1/+1 on Reinforcements, from Unicorn trigger)
3. TokenCreated (1/1 Soldier)
4. CounterAdded (+1/+1 on Soldier token, from Unicorn trigger)

This exercises counter + token annotations in a dense burst.

## FDN Mechanic Support Catalog

### Works in Nexus

| Mechanic | FDN Cards Using It | Notes |
|----------|--------------------|-------|
| Vanilla creatures | Savannah Lions, many others | Cast from hand, resolve to BF |
| Flying | Vampire Nighthawk, Healer's Hawk, Serra Angel, many | Evasion enforced by engine |
| Lifelink | Vampire Nighthawk, Healer's Hawk | ModifiedLife annotation fires |
| Deathtouch | Vampire Nighthawk, Treetop Snarespinner | Engine handles lethal damage |
| Vigilance | Serra Angel, Drake Hatcher | Attacker doesn't tap |
| First Strike | Quick-Draw Katana (grants), Sure Strike (grants) | Separate damage phase |
| Reach | Treetop Snarespinner | Can block fliers |
| Haste | Fanatical Firebrand, Resolute Reinforcements (flash) | Can attack immediately |
| Flash | Resolute Reinforcements | Cast on opponent's turn |
| Hexproof | Swiftfoot Boots (grants), Snakeskin Veil (grants) | Excluded from targeting |
| Menace | Courageous Goblin (conditional) | Requires 2+ blockers |
| Prowess | Drake Hatcher | P/T pump on noncreature cast |
| Trample | Ambush Wolf, Eager Trufflesnout | Excess damage to player |
| Defender | Aegis Turtle, Rune-Sealed Wall | Can't attack |
| Indestructible | Celestial Armor (grants, EOT) | Engine skips destroy |
| Protection | Knight of Grace (from black) | DEBT rules enforced |
| +1/+1 counters | Snakeskin Veil, Good-Fortune Unicorn, many | CounterAdded/CounterRemoved |
| P/T pump spells | Giant Growth, Sure Strike, Stab | ModifiedPower/Toughness |
| Equipment cast | Quick-Draw Katana, Swiftfoot Boots | Artifact enters BF |
| Equip (activate) | Quick-Draw Katana (2), Swiftfoot Boots (1) | CreateAttachment + Attachment |
| Aura cast | Blanchwood Armor, Pacifism | Auto-attaches on resolve |
| Token creation (ETB) | Resolute Reinforcements, Dwynen's Elite | TokenCreated annotation |
| Targeted instants | Stab, Giant Growth, Sure Strike, Unsummon | SelectTargetsReq wired |
| ETB with targeting | Banishing Light, Reclamation Sage | SelectTargetsReq on entry |
| Bounce | Unsummon, Run Away Together | BF → Hand, Bounce category |
| Exile | Banishing Light | BF → Exile, Exile category |
| Sacrifice (as cost) | Eaten Alive, Fanatical Firebrand | BF → GY, Sacrifice category |
| Land play | All basics | PlayLand category |

### Probably Works (Untested in Puzzles)

| Mechanic | FDN Cards | Risk |
|----------|-----------|------|
| Activated abilities (non-mana) | Fanatical Firebrand (sac: 1 dmg), Shivan Dragon (R: +1/+0), Treetop Snarespinner (+1/+1 counter) | Activate action type (2) is wired but incomplete — may not appear in ActionsAvailableReq |
| ETB "you may" | Reclamation Sage ("you may destroy") | Engine may auto-choose or use SelectNReq |
| Conditional triggers | Courageous Goblin (attack trigger with power check) | Trigger should fire, P/T change should annotate |
| Sacrifice on resolution | Eaten Alive (sac as additional cost) | Cost payment via engine, zone transfer fires |
| Enchantment removal | Reclamation Sage (destroy artifact/enchantment) | Attachment removed → RemoveAttachment |

### Not Supported — Missing Client UI

These FDN mechanics require protocol messages or action types that aren't wired yet.

| Mechanic | FDN Cards | Blocker | What Happens |
|----------|-----------|---------|-------------|
| **Kicker** | Burst Lightning | SelectNReq (type 22) not wired | Engine may auto-kick if mana available, or hang waiting for choice |
| **Modal "Choose one"** | Abrade, Fiery Annihilation (multi-target) | SelectNReq not wired | Engine waits for mode choice, hangs |
| **X costs** | Mossborn Hydra | No cost-input UI | Engine can't ask how much X to pay |
| **Flashback** | Think Twice, Electroduplicate | No alt-cast action type | Card sits in graveyard, no cast option shown |
| **Planeswalker abilities** | Ajani, Kaito, Chandra, Liliana, Vivien, Kiora | Activate (2) incomplete + loyalty UI | Abilities may not appear in action list |
| **Scry choices** | Opt (scry 1), many ETB scry | OrderReq (type 17) not wired | Engine auto-orders (always top) or hangs |
| **MDFC** | (none in FDN core) | CastMdfc/PlayMdfc (18/19) missing | N/A for FDN |
| **Adventure** | (none in FDN core) | CastAdventure (16) missing | N/A for FDN |
| **Split cards** | (none in FDN core) | CastLeft/CastRight missing | N/A for FDN |
| **Foretell / Omen** | (none in FDN core) | CastOmen (24) missing | N/A for FDN |
| **Transform / DFC** | (none in FDN core) | No face-down/transform UI | N/A for FDN |
| **Ward (tax)** | (various) | Counter-tax payment uncertain | Trigger fires, but opponent may not get pay prompt |
| **Food/Treasure sac** | Cat Collector (Food), Goldvein Pick (Treasure) | Activate action type incomplete | Token created but can't activate sacrifice |
| **Copy tokens** | Electroduplicate | Complex token cloning | Likely crashes or produces wrong state |
| **Damage assignment** | (multi-block scenarios) | AssignDamageReq (type 30) missing | Engine auto-distributes, player has no choice |
| **"Choose a creature type"** | Banner of Kinship, Secluded Courtyard | SelectNReq | Hangs waiting for type choice |

### Safe FDN Cards for Puzzles

Cards confirmed to work with current nexus protocol coverage:

**Creatures:** Savannah Lions, Healer's Hawk, Vampire Nighthawk, Serra Angel, Llanowar Elves, Courageous Goblin, Crackling Cyclops, Treetop Snarespinner, Aegis Turtle, Dwynen's Elite, Resolute Reinforcements, Good-Fortune Unicorn

**Instants:** Giant Growth, Sure Strike, Stab, Unsummon, Snakeskin Veil

**Sorceries:** (simple targeted ones without modes)

**Enchantments:** Blanchwood Armor (aura), Pacifism (aura), Banishing Light (ETB exile)

**Equipment:** Quick-Draw Katana, Swiftfoot Boots

**Avoid:** Anything with kicker, modes, X costs, flashback, activated sacrifice, scry choices, choose-a-type.
