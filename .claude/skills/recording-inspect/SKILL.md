---
name: recording-inspect
description: Interactive recording inspection — analyze a proxy session, trace cards, compare with Forge scripts, write notes. Human-in-the-loop workflow for understanding what happened in a real game.
---

## What I do

Walk through a proxy recording session interactively: show what happened, let you pick cards to trace, check if Forge can handle them, and capture findings in notes.

## When to use me

- After a proxy recording session ("let's look at last game")
- When investigating how a specific card/mechanic played out on the real server
- Before implementing a mechanic — understand what the client saw first
- `/recording-inspect` or `/recording-inspect 2026-03-17`

## Process

### 1. Resolve session

Parse the argument as a session hint. Default to latest proxy session with MD frames.

```bash
just tape session list
```

### 2. Read existing notes

Check `recordings/<session>/notes.md` — if it exists, read it and show to user. Previous observations inform what to look at next.

### 3. Analysis overview

If `analysis.json` exists, read it. If not, run:

```bash
just tape session analyze <session> --force
```

Present the summary to the user:
- Turns, winner, duration
- Mechanics exercised (with counts)
- Annotation coverage (types seen)
- Interesting moments (if any)

### 4. Ask what to explore

Ask the user: **"Which cards or mechanics interest you?"**

Don't guess — wait for direction. The user knows what they played and what felt interesting.

### 5. Card investigation loop

For each card the user names:

**a. Find instances:**
```bash
just tape proto find-card "<name>" -s <session>
```
Shows instanceIds + zone transitions. Present and briefly interpret (cast, died, exiled, etc.)

**b. Deep trace (if user wants):**
```bash
just tape proto trace <instanceId> -s <session>
```
Show the protocol-level lifecycle. Call out notable moments: ObjectIdChanged (realloc), DeclareAttackers/Blockers, SelectTargetsReq, etc.

**c. Forge script check:**
```bash
just card-script "<name>"
```
Read the script. Compare abilities against what the recording shows. Flag:
- Ability present in recording but missing from Forge script → **can't implement yet**
- Ability in Forge but not exercised in recording → **no conformance data**
- Both present → **ready to wire/verify**

**d. Summarize the card** — one paragraph: what it did, key protocol moments, Forge readiness.

Repeat for each card. Let the user drive — they may want to go deeper on one card or skip to the next.

### 6. Write/update notes

At the end, offer to write or update `recordings/<session>/notes.md` with:
- Deck description and opponent (if known)
- Result (winner, turn count)
- Notable plays with instanceIds and gsId references
- Conformance-relevant moments (new annotation types, edge cases, Forge gaps)

## Key principles

- **Human drives, agent digs.** Don't auto-trace every card — ask first.
- **Recording is truth.** When recording and Forge disagree, recording wins.
- **Notes are durable.** Write them — next conversation can pick up where this one left off.
- **Keep it conversational.** This is exploration, not a pipeline. Short answers, ask follow-ups.
