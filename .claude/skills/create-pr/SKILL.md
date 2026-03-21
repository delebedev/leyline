---
name: create-pr
description: Use when creating a pull request — runs pre-flight checks for feature coherence, issue linkage, test coverage, catalog freshness, and branch hygiene before opening the PR.
---

## What I do

Pre-flight validation focused on **feature completeness**, not code style. Ensures the branch tells a coherent story, links issues, has test coverage for what it claims, and updates docs that track mechanic state. Then creates the PR.

## When to use

- "Create a PR"
- "Open a PR for this branch"
- After completing feature work, before `gh pr create`
- When asked to "ship this" or "PR this"

## Process

### 1. Understand the branch

```bash
git log --oneline main..HEAD                    # commit story
git diff --stat main...HEAD                     # scope
git diff --name-only main...HEAD                # files touched
```

Determine: what modules changed, what feature/fix this is, what the branch name implies.

### 2. Pre-flight checks

Run all checks. Collect findings. **Do not stop on first failure** — report all at once.

#### 2a. Issue linkage

Extract issue references from: branch name (`fix/foo-123`), commit messages (`closes #N`, `refs #N`, `#N`), and any open issues matching the topic.

```bash
# Branch name pattern
git branch --show-current | grep -oE '[0-9]+'

# Commit messages
git log --oneline main..HEAD | grep -oE '#[0-9]+'

# Search open issues for related keywords
gh issue list -R delebedev/leyline --state open --limit 100 --json number,title
```

**Flag if:** no issue is linked and the branch has >3 commits or touches >5 files. Small fixes are fine without an issue.

#### 2b. Test coverage for changed production code

For each module with production code changes (`src/main/`), check if corresponding test changes exist (`src/test/`).

```bash
# Production files changed
git diff --name-only main...HEAD | grep 'src/main/'

# Test files changed
git diff --name-only main...HEAD | grep 'src/test/'
```

**Flag if:** production code changed in a module but zero test files changed in that module. Exception: pure refactors that don't change behavior (rename, move, extract).

#### 2c. Catalog & rosetta freshness

If matchdoor production code changed (new annotations, zone transitions, action handlers, game events):

```bash
git diff --name-only main...HEAD | grep -q 'matchdoor/src/main/' && {
  git diff --name-only main...HEAD | grep -qE 'catalog\.yaml|rosetta\.md'
}
```

**Flag if:** matchdoor production code changed but neither `docs/catalog.yaml` nor `docs/rosetta.md` were updated. Not every change needs it — use judgment (new mechanic = must update, bug fix in existing mechanic = skip).

#### 2d. Plan cleanup

Check for implementation plans that were used during this branch's work:

```bash
# Plans in docs/
git diff --name-only main...HEAD | grep -iE 'plan|design'

# Check if any plan files reference completed work
ls docs/*plan* docs/*design* 2>/dev/null
```

If a plan drove this branch's work: mark completed items, archive the plan (move to `docs/archive/` or add `Status: implemented` header), or delete if fully consumed. Plans left as "todo" after the feature ships are confusing.

**Flag if:** a plan file was added or modified in this branch but still has unchecked items that the branch actually implements.

#### 2e. Documentation freshness

For substantial changes (new feature, new handler, changed API surface, new module wiring), check that docs match reality:

**KDoc:** Read changed production files. Flag public classes/functions that are new or have changed signatures but lack or have stale KDoc — especially at module boundaries (bridge/, match/, game/ entry points).

**Markdown:** Check if relevant docs need updating:

```bash
# Module-level docs
git diff --name-only main...HEAD | sed 's|/.*||' | sort -u | while read mod; do
  [ -f "$mod/CLAUDE.md" ] && echo "Check: $mod/CLAUDE.md"
done

# Project docs that track state
for f in docs/catalog.yaml docs/rosetta.md docs/architecture.md matchdoor/CLAUDE.md; do
  [ -f "$f" ] && echo "Check: $f"
done
```

**Flag if:** new public API without KDoc at a seam boundary, or a CLAUDE.md cookbook/mental-model section that no longer matches changed code. Skip for internal helpers and test code.

#### 2f. Commit story coherence

Review commit messages for:
- **Fix-on-fix chains**: `feat: add X` followed by `fix: fix X` — suggest squashing
- **Scope creep**: commits touching unrelated modules/features
- **Conventional commits**: all messages follow `type(scope): description`

**Flag if:** >2 fix commits for the same feature (suggests incomplete work landed). Don't flag if fixes address review feedback.

#### 2g. Uncommitted changes

```bash
git status --porcelain
```

**Flag if:** uncommitted changes exist that look related to the feature.

#### 2h. Branch freshness

```bash
git fetch origin main --quiet
git rev-list --count HEAD..origin/main
```

**Flag if:** branch is >10 commits behind main. Suggest rebase/merge.

### 3. Report findings

Present findings as a checklist:

```
Pre-flight checks:
  [x] Issue linkage — closes #122
  [x] Test coverage — matchdoor tests added
  [ ] Catalog update — matchdoor changed but catalog.yaml not updated
  [x] Plan cleanup — design doc archived
  [x] Docs freshness — KDoc on new MatchHandler entry point, CLAUDE.md cookbook updated
  [x] Commit story — 5 commits, clean progression
  [x] Clean working tree
  [x] Branch fresh (2 behind main)
```

If any check fails, ask: "Want me to fix these before creating the PR, or proceed as-is?"

### 4. Create the PR

Build PR body from branch analysis:

```bash
gh pr create --title "<type>(scope): concise title" --body "$(cat <<'EOF'
## Summary
<bullet points derived from commit log — what changed and why>

<issue link if found: Closes #N>

## Test plan
<what tests were added/changed, how to verify>

Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

**Title rules:**
- Under 70 chars
- Conventional commit prefix matching dominant commit type
- If branch has a clear issue, reference it

**Body rules:**
- Summary from commit analysis, not restated commit messages
- Link issues with `Closes #N` (if fix/feat) or `Refs #N` (if partial)
- Test plan section describing what's covered
- Mention modules touched if >2

### 5. Post-create

```bash
gh pr view --json url --jq '.url'
```

Return the PR URL.

## What this is NOT

- Not a code review — doesn't evaluate implementation quality (use `feature-review`)
- Not a hygiene scan — doesn't check dead assets or large blobs (use `pre-merge-scan`)
- Not a linter — doesn't check formatting or static analysis (CI handles that)
- Doesn't run tests — assumes `just test-gate` was already run (or tells you to run it)
