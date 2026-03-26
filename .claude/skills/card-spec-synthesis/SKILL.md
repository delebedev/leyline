---
name: card-spec-synthesis
description: Cross-cut analysis of card specs — extract horizontal layers, update catalog/rosetta, link gaps to issues, flag trace gaps, write prioritized SYNTHESIS.md
---

## What I do

Read all card specs in `docs/card-specs/`, extract gaps, group into horizontal layers, update reference docs, and produce a prioritized work plan.

## When to use me

- After a batch of card specs is written
- "synthesize the specs" / "what horizontal work falls out"
- "update catalog from card specs"
- Before planning implementation work

## Process

### 1. Read all specs, extract gaps

```bash
# List all specs
ls docs/card-specs/*.md

# Extract Gaps sections
for spec in docs/card-specs/*.md; do
    echo "=== $(basename $spec .md) ==="
    sed -n '/^## Gaps/,/^## /p' "$spec" | head -25
done
```

For each gap, note: what mechanic, what's missing, which card.

### 2. Group into horizontal layers

Cluster gaps by shared infrastructure. Three tiers:

- **Tier 1 — Data registration.** Counter types, token grpIds, ability mappings. No logic changes, just adding entries. Do first.
- **Tier 2 — Shared protocol handlers.** Transform, cast-from-non-hand, AbilityWordActive, transfer categories. One handler unblocks multiple cards.
- **Tier 3 — New mechanics.** Card-type-specific (adventure, ninjutsu, class leveling). Build when trace data is sufficient.

Prioritize by **unblock count** — how many card specs does this layer enable?

### 3. Update reference docs

Check if card specs discovered new data that belongs in committed reference docs:

**`docs/rosetta.md`** — new annotation types, zone IDs, transfer categories, counter types, action types.

**`docs/catalog.yaml`** — mechanic status changes. If a spec confirmed something works or found it's more broken than listed, update the status + notes.

Only update with **confirmed findings** (traced on wire), not assumptions.

### 4. Link gaps to existing issues

Search open issues for overlap before creating new ones:

```bash
gh issue list --state open --limit 60 --json number,title --jq '.[] | "\(.number) \(.title)"'
```

Or use the `gh-issue-sync` skill if available for local search:

```bash
grep -r "<keyword>" .issues/open/
```

For each horizontal layer:
- **Existing issue found:** comment with spec evidence (card spec path + key finding). Keep it brief.
- **No existing issue:** create one. Title = horizontal layer name. Body = card spec refs + wire findings.

### 5. Flag trace gaps

List mechanics that were **unobserved across all specs** — the card was played but the mechanic never triggered:

- Nullpriest: kicker never paid → kicked ETB unknown
- Cauldron Familiar: no Food → GY→BF return unknown
- Archfiend's Vessel: cast from hand → conditional ETB unknown
- Brass's Tunnel-Grinder: game ended early → transform unknown
- Cleric Class: never leveled up → all class mechanics unknown
- Thousand-Faced Shadow: never activated → ninjutsu unknown

These need **dedicated play sessions** or puzzles. Output as a "next recording targets" list for `just deck coverage`.

### 6. Write SYNTHESIS.md

Output to `docs/card-specs/SYNTHESIS.md`. Structure:

```markdown
# Card Spec Synthesis — Horizontal Layers

## Tier 1 — Data registration
### Counter type mapper
### Token grpId registry

## Tier 2 — Shared protocol handlers
### <Layer name>
| Evidence | Card specs |
...

## Tier 3 — New mechanics
### <Mechanic>
...

## Existing issues updated
| Issue | Evidence added |

## New issues created
| Issue | Layer |

## Trace gaps — next recording targets
- <mechanic>: <what to play>
```

## Rules

- **Only reference committed docs.** Never `.claude/agent-memory/`.
- **Don't duplicate issue content.** Issues get a comment with spec refs, not a copy of the spec.
- **Tier 1 before Tier 2 before Tier 3.** Data registration is highest ROI.
- **Confirmed findings only** for rosetta/catalog updates. "Expected" or "assumed" ≠ confirmed.

## Reference

- `docs/card-specs/` — all card specs
- `docs/card-specs/SYNTHESIS.md` — output (overwrite on each run)
- `docs/rosetta.md` — protocol reference
- `docs/catalog.yaml` — mechanic status
- `docs/conformance/how-we-conform.md` — workflow context
