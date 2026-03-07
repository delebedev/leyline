---
name: pre-merge-scan
description: Pre-merge branch hygiene scan — find dead assets, large blobs, stale paths, policy violations, and PR description drift before merging a big branch.
---

## What I do

Quick triage of a branch before merge/PR. Finds junk that accumulates in long-lived branches. Reports findings, asks what to trash/keep. Not a code review — just hygiene.

## When to use

- Before creating a PR on a branch with >10 commits
- When asked to "clean up", "review this branch", or "prep for merge"
- After `/handoff` on a big feature branch

## Steps

Run each check against `git diff --name-only main..HEAD`. Report findings per section. Skip sections with zero findings.

### 1. Dead assets

Files in the diff that no code references — golden data, fixtures, images, configs.

```bash
# For each non-code file (json, bin, png, yaml, txt, csv, xml):
# grep its basename (without extension) across *.kt, *.swift, *.ts, *.py, etc.
# No hits = suspect. Report it with file size.
```

Exclude: `.md` docs, `.gitignore`, config roots (`justfile`, `build.gradle.kts`, etc.), `.claude/` skill/rule files.

### 2. Large blobs

Files >50KB in the diff. Flag with size. Extra suspicious if:
- Single-line JSON (minified capture data)
- Binary outside `test/resources/`
- Not loaded by any code (overlaps with check 1)

```bash
for f in $(git diff --name-only main..HEAD); do
  size=$(git cat-file -s HEAD:$f 2>/dev/null)
  [ "$size" -gt 51200 ] && echo "$size $f"
done | sort -rn
```

### 3. Orphaned old paths

After renames/moves, check that old paths are deletions and new paths exist. Look for:
- Files still under old directory prefixes that should have moved
- Partial moves (some files moved, some forgotten)

```bash
# Look at deleted files — are they mirrored by additions in the new location?
git diff --diff-filter=D --name-only main..HEAD
git diff --diff-filter=A --name-only main..HEAD
```

### 4. Empty / zero-byte files

Tracked files with no content — usually leftovers from `> file` or botched deletions.

```bash
git diff --name-only main..HEAD | while read f; do
  [ "$(git cat-file -s HEAD:$f 2>/dev/null)" = "0" ] && echo "$f"
done
```

### 5. Policy violations

Check project-specific rules from `CLAUDE.md`:
- Local TODO/BUGS files (should use GitHub Issues)
- Files matching `.gitignore` patterns that snuck in
- Credentials/secrets patterns (`.env`, `*secret*`, `*credential*`, `*.key`, `*.pem`)
- `recordings/` content beyond `manifest.json`

### 6. PR description drift

If a PR already exists (`gh pr view`):
- Count top-level directories touched in the diff
- Compare against what the PR description mentions
- Flag if diff scope >> description scope

## Output

Present findings as a numbered list per section. For each finding, show:
- File path + size
- Why it's flagged (unreferenced / large / empty / policy)
- Suggested action: **trash**, **keep** (with reason), or **ask user**

Then ask: "Want me to trash the flagged files and update the PR description?"

## What this is NOT

- Not a code review — doesn't read implementation logic
- Not a test runner — doesn't verify anything compiles
- Not exhaustive — quick scan, not static analysis
