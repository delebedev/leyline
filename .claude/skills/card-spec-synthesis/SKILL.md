---
name: card-spec-synthesis
description: Cross-cut analysis of card specs — extract horizontal layers, update catalog/rosetta, link gaps to issues, flag trace gaps, aggregate tooling feedback, write prioritized SYNTHESIS.md
---

## What I do

Read all card specs in `docs/card-specs/`, extract gaps, group into horizontal layers, update reference docs, aggregate tooling feedback, and produce a prioritized work plan.

## When to use me

- After a batch of card specs is written
- "synthesize the specs" / "what horizontal work falls out"
- "update catalog from card specs"
- Before planning implementation work

## Process

### 1. Read all specs, extract gaps

```bash
ls docs/card-specs/*.md
```

For each spec, extract: gaps, key findings, tooling feedback, unobserved mechanics.

### 2. Spec consistency check

Review specs for consistency. Fix in place:
- **Language:** "game" not "recording." Data comes from Player.log saved games.
- **Template compliance:** all required sections present (Identity, Mechanics, What it does, Trace, Key findings, Gaps, Tooling feedback, Supporting evidence)
- **Tooling feedback section exists.** If missing, flag it — every spec should have one.
- **Trim noise:** remove duplicate findings, overly verbose mana payment traces, redundant ShouldntPlay entries (once documented, don't repeat the pattern in every spec)

### 3. Aggregate tooling feedback

Collect all Tooling Feedback sections. Produce a vote tally:

| Feature request | Specs that requested it | Status |
|---|---|---|
| ... | kellan, ooze, spinner | Done / Open |

Check which requests were addressed since last synthesis. Update status. This drives scry-ts development priorities.

### 4. Group gaps into horizontal layers

Cluster gaps by shared infrastructure. Three tiers:

- **Tier 1 — Data registration.** Counter types, token grpIds, ability mappings. No logic changes, just adding entries. Do first.
- **Tier 2 — Shared protocol handlers.** OptionalActionMessage, CopyPermanent tokens, cost reduction, transfer categories. One handler unblocks multiple cards.
- **Tier 3 — New mechanics.** Card-type-specific (adventure, ninjutsu, class leveling). Build when trace data is sufficient.

Prioritize by **unblock count** — how many card specs does this layer enable?

### 5. Update reference docs

Check if card specs discovered new data that belongs in committed reference docs:

**`docs/rosetta.md`** — new annotation types, zone IDs, transfer categories, counter types, action types.

**`docs/catalog.yaml`** — mechanic status changes. If a spec confirmed something works or found it's more broken than listed, update the status + notes.

Only update with **confirmed findings** (traced in Player.log game data), not assumptions.

### 6. Link gaps to existing issues

Search open issues for overlap before creating new ones:

```bash
gh issue list --state open --limit 60 --json number,title --jq '.[] | "\(.number) \(.title)"'
```

For each horizontal layer:
- **Existing issue found:** comment with spec evidence (card spec path + key finding). Keep it brief.
- **No existing issue:** create one. Title = horizontal layer name. Body = card spec refs + findings.

### 7. Flag trace gaps

List mechanics that were **unobserved across all specs** — the card was played but the mechanic never triggered. These need **dedicated games** or puzzles. Output as a "next game targets" list.

### 8. Write SYNTHESIS.md

Output to `docs/card-specs/SYNTHESIS.md`. Structure:

```markdown
# Card Spec Synthesis — Horizontal Layers

## Tooling feedback tally

| Feature | Votes | Specs | Status |
|---|---|---|---|

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

## Trace gaps — next game targets
- <mechanic>: <what to play>

## Spec consistency fixes applied
- <what was fixed and where>
```

## Rules

- **Only reference committed docs.** Never `.claude/agent-memory/`.
- **Don't duplicate issue content.** Issues get a comment with spec refs, not a copy of the spec.
- **Tier 1 before Tier 2 before Tier 3.** Data registration is highest ROI.
- **Confirmed findings only** for rosetta/catalog updates. "Expected" or "assumed" ≠ confirmed.
- **Say "game" not "recording."** Data comes from Player.log saved games.
- **Fix specs in place.** Consistency issues get corrected directly, not just flagged.

## Reference

- `docs/card-specs/` — all card specs
- `docs/card-specs/SYNTHESIS.md` — output (overwrite on each run)
- `docs/rosetta.md` — protocol reference
- `docs/catalog.yaml` — mechanic status
- `just scry-ts --help` — available tooling commands
