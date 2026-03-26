---
name: card-candidates
description: Find unspec'd cards in recordings worth investigating — broad keyword scan or targeted gap-filling from SYNTHESIS.md
---

## What I do

Search all recording sessions for cards that haven't been spec'd yet, ranked by how interesting they are for leyline development. Two modes: broad discovery and targeted gap-filling.

## When to use me

- "what cards should we spec next"
- "find interesting cards in recordings"
- "what cards fill our trace gaps"
- After new recording sessions, before choosing spec targets

## Process

### 1. Inventory existing specs

```bash
ls docs/card-specs/*.md | sed 's/.*\///' | sed 's/\.md//' | sort
```

Build exclusion list from spec filenames.

### 2. Generate cards.json where missing

```bash
for d in recordings/2026-*/; do
    name=$(basename "$d")
    if [ -f "$d/md-frames.jsonl" ] && [ ! -f "$d/cards.json" ]; then
        just tape session cards "$name"
    fi
done
```

### 3. Choose mode

**Ask the user:** "Broad scan (discover interesting cards by mechanic density) or targeted (fill specific gaps from SYNTHESIS.md)?"

If the user doesn't have a preference, do both — broad first for overview, then targeted for priorities.

#### Mode A: Broad scan

Aggregate all cards.json across sessions. For each card not already spec'd, score by mechanic keywords in ability text:

| Keyword | Weight | Why |
|---------|--------|-----|
| flashback, adventure, transform, ninjutsu | 5 | Missing mechanics |
| kicker, investigate, search your library, copy | 4 | Partial/missing |
| create a, sacrifice, exile, surveil, mill | 3 | Interesting interactions |
| counter on, when.*enters, whenever, draw a card | 2 | Common but composable |
| scry, ward, destroy target | 2 | Wired, conformance validation |

```python
import json, os, re

specs_done = set()  # read from docs/card-specs/*.md filenames
basics = {"Plains", "Island", "Swamp", "Mountain", "Forest"}
all_cards = {}

for d in sorted(os.listdir("recordings")):
    cards_path = f"recordings/{d}/cards.json"
    if not os.path.exists(cards_path):
        continue
    with open(cards_path) as f:
        data = json.load(f)
    for c in data.get("cards", []):
        name = c.get("name", "")
        if not name or name in basics or c.get("isToken"):
            continue
        if name not in all_cards:
            all_cards[name] = {
                "grpId": c["grpId"],
                "sessions": [],
                "abilities": [a.get("text", "") for a in c.get("abilities", [])],
            }
        all_cards[name]["sessions"].append(d)

# Score and rank
# ... (keyword matching as above)
```

Output top 10-15 candidates with: name, score, matched keywords, session(s).

**Then suggest groupings:** "These 3 cards all exercise sacrifice triggers — spec one, the others are cross-references."

#### Mode B: Targeted gap-filling

Read `docs/card-specs/SYNTHESIS.md` trace gaps section. For each gap:

```bash
# Example: find cards with GY→BF return in recordings
grep -r "return.*graveyard.*battlefield\|Origin.*Graveyard.*Destination.*Battlefield" \
    recordings/*/cards.json
```

Or search Forge scripts:

```bash
grep -rl "DB\$ ChangeZone.*Origin.*Graveyard.*Destination.*Battlefield" \
    forge/forge-gui/res/cardsfolder/
```

Then check if any of those cards appear in our recordings:

```bash
just card-grp "<name>"  # get grpId
for d in recordings/2026-*/; do
    just cards-in-session "$(basename $d)" 2>&1 | grep "<grpId>" && echo "  ^ $(basename $d)"
done
```

Output: "For gap X, card Y was played in session Z."

### 4. Present recommendations

Format as a table:

```
| Priority | Card | Mechanic gap it fills | Session | Notes |
|----------|------|-----------------------|---------|-------|
| 1 | Sun-Blessed Healer | GY→BF return (kicker) | 22-37-18 | Fills Nullpriest gap |
| 2 | Cryogen Relic | LTB trigger | 21-22-05-00 | New mechanic category |
| ...
```

Group by mechanic gap when possible — "these 3 all test the same thing, pick the simplest."

### 5. Suggest next actions

Based on findings, suggest:
- **Spec these N cards** — dispatch card-spec agents
- **Play these mechanics** — if no recording has the gap, suggest deck composition for next proxy session
- **Run synthesis** — if enough new specs, re-run card-spec-synthesis

## Rules

- Don't recommend cards we've already spec'd
- Prefer seat 1 sessions (full visibility)
- Note when a card appears in multiple sessions — more data points = better spec
- Flag cards where the interesting mechanic likely wasn't exercised (e.g. "kicker cards often aren't kicked")
- Keep the list to 5-10 — more is overwhelming

## Reference

- `docs/card-specs/` — existing specs (exclusion list)
- `docs/card-specs/SYNTHESIS.md` — trace gaps for targeted mode
- `recordings/*/cards.json` — card manifests per session
- `just tape session cards <session>` — generate cards.json
- `just card-grp "<name>"` — grpId lookup
