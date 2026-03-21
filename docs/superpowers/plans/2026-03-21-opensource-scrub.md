# Open-Source Scrub Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring leyline repo to publishable state — zero personal data, zero WotC captures, clean tone, scrubbed history.

**Architecture:** Systematic pass through tracked files fixing references, tone, and hygiene. Then `git filter-repo` to erase captured binaries from history. Then flip public.

**Policy reference:** `/Users/denislebedev/src/mtga-internals/docs/legal/POLICY.md` — the litmus tests and red lines.

**Current state:** Phases 1-4 of the scrub checklist are done on `main`. Phases 5-9 remain. New findings: 59 hardcoded absolute paths, Tailscale IPs, infra details.

---

### Task 1: Fix remaining mtga-internals references (Phase 3 stragglers)

**Files:**
- Modify: `.claude/skills/ralph/SKILL.md`
- Modify: `docs/SESSION.md`

- [ ] **Step 1: Fix ralph skill**

In `.claude/skills/ralph/SKILL.md:18`, replace the `~/src/mtga-internals/docs/autoplay/forge-mode-notes.md` reference. The relevant content should already be in `docs/agentic-playtesting.md` or the skill itself. Either inline the needed info or point to a leyline-local doc. If the referenced content doesn't exist locally, just delete the line — it's an agent hint, not user-facing.

- [ ] **Step 2: Fix SESSION.md**

`docs/SESSION.md:11-12` references mtga-internals paths. Replace with generic references:
- Line 11: `"Filed decompilation request + got results"` (drop the path)
- Line 12: `"Scrub checklist updated"` (drop the path)

Or better: SESSION.md is ephemeral session state — consider deleting it entirely if it's stale (last session: 2026-03-18). It's tracked but has no long-term value for open-source consumers.

- [ ] **Step 3: Verify**

Run: `git grep mtga-internals -- ':(exclude).git' ':(exclude).claude/CLAUDE.md'`
Expected: zero matches.

- [ ] **Step 4: Commit**

```bash
git add .claude/skills/ralph/SKILL.md docs/SESSION.md
git commit -m "scrub: remove remaining mtga-internals references"
```

---

### Task 2: Scrub hardcoded absolute paths

**Files:**
- Modify: `.claude/skills/arena-nav/SKILL.md`
- Modify: `.claude/skills/debug-server/SKILL.md`
- Modify: `.claude/skills/reproduce/SKILL.md`
- Modify: `docs/forge-ability-id-mapping.md`
- Modify: `docs/forge-upstream-sync-runbook.md`
- Modify: `docs/playbooks/event-discovery-playbook.md`

- [ ] **Step 1: Replace absolute paths in .claude skills**

These are agent instructions. Replace `/Users/denislebedev/src/leyline` with relative paths or `$REPO_ROOT`. Replace `/Users/denislebedev/Library/Logs/Wizards Of The Coast/MTGA/Player.log` with `~/Library/Logs/Wizards Of The Coast/MTGA/Player.log` (tilde is portable to any macOS user).

- [ ] **Step 2: Replace absolute paths in docs**

`docs/forge-ability-id-mapping.md` — has ~18 absolute paths to forge source files. Replace `/Users/denislebedev/src/leyline/forge/...` with `forge/...` (relative to repo root). Same for matchdoor paths → `matchdoor/...`.

`docs/forge-upstream-sync-runbook.md` — same pattern, ~25 absolute paths. All `/Users/denislebedev/src/leyline/X` → `X`.

`docs/playbooks/event-discovery-playbook.md` — one line: `Working dir: /Users/denislebedev/src/leyline`. Replace with "Working dir: repo root" or delete.

- [ ] **Step 3: Verify**

Run: `git grep '/Users/denislebedev' -- ':(exclude)forge/' ':(exclude).claude/CLAUDE.md'`
Expected: zero matches. (`.claude/CLAUDE.md` is gitignored — not public.)

- [ ] **Step 4: Commit**

```bash
git commit -am "scrub: replace hardcoded absolute paths with relative paths"
```

---

### Task 3: Scrub Tailscale IP and infra details

**Files:**
- Modify: `docs/plans/2026-03-20-netplay-laptop-setup.md`
- Modify: `.claude/skills/arena-update/SKILL.md`

- [ ] **Step 1: Evaluate netplay-laptop-setup.md**

This doc has Tailscale IP `100.73.207.103`, `ssh mini`, and detailed internal infra. Two options:
1. **Delete it** — it's a personal setup guide, not useful to OSS contributors
2. **Generalize it** — replace IP with `<server-ip>`, `mini` with `<server-hostname>`

Recommend: delete. It's under `docs/plans/` (ephemeral). The useful parts (services.conf for remote play) are covered in `deploy/setup.sh`.

- [ ] **Step 2: Fix arena-update skill**

`.claude/skills/arena-update/SKILL.md:107` references `100.73.207.103`. Replace with `<server-ip>` or remove the line about updating `/etc/hosts` with remote IPs.

- [ ] **Step 3: Verify**

Run: `git grep '100\.73\.' -- ':(exclude)forge/'`
Expected: zero matches.

- [ ] **Step 4: Commit**

```bash
git commit -am "scrub: remove Tailscale IPs and internal infra details"
```

---

### Task 4: Docs tone review (Phase 5)

**Files (identified by grep):**
- Modify: `docs/index.md`
- Modify: `docs/game-types-research.md`
- Modify: `docs/priority-loop.md`
- Modify: `docs/principles-documentation.md`
- Modify: `docs/bridge-architecture.md` (check)
- Review all docs with: `grep -rn 'reverse.engineer\|intercept.*traffic\|crack\|private server\|alternative server\|free Arena\|MITM' docs/ --include='*.md'`

Tone rules (from POLICY.md):
- "reverse-engineered" → "reimplemented" or "protocol-compatible"
- "intercept WotC traffic" → "record local sessions for development"
- "alternative server" → "local playtesting server"
- Proxy/cert docs → frame as development tooling
- Remove language implying competition with Arena

- [ ] **Step 1: Fix docs/index.md**

Line 3: "reverse-engineering" → "protocol analysis" or "development"

- [ ] **Step 2: Fix docs/principles-documentation.md**

Line 4: "reverse-engineered protocol" → "reimplemented protocol"

- [ ] **Step 3: Fix docs/priority-loop.md**

Line 3: "Eliminates reverse-engineering" → "Eliminates guesswork" or similar

- [ ] **Step 4: Fix docs/game-types-research.md**

Line 434: This describes MTGate (third party). Keep factual — "reverse-engineering" is what MTGate actually does. No change needed here unless you want to soften to "protocol analysis".

- [ ] **Step 5: Scan for remaining tone issues**

Run: `grep -rn 'reverse.engineer\|private server\|crack\|free Arena\|MITM\|man.in.the.middle\|intercept.*WotC\|intercept.*Wizards' docs/ .claude/ README.md LEGAL.md --include='*.md'`

Fix any hits. The word "intercept" in engine/bridge context (intercepting Forge priority calls) is fine — it's about internal code architecture, not network interception.

- [ ] **Step 6: Commit**

```bash
git commit -am "scrub: soften docs tone for public release"
```

---

### Task 5: Gitignore and hygiene audit (Phase 6)

No files to modify — verification only.

- [ ] **Step 1: Confirm .gitignore coverage**

Run:
```bash
grep -E 'recordings/|/data/|\.mtga|services-proxy' .gitignore
```

Expected: all four patterns present. Current `.gitignore` already has `recordings/*`, `/data/`, `deploy/services-proxy.conf`.

Check for `*.mtga`: if missing, add it.

- [ ] **Step 2: Check recordings never in history**

Run: `git log --all --name-only | grep -E '^recordings/' | sort -u`
Expected: zero file paths (commit messages mentioning "recordings" are fine).

- [ ] **Step 3: Check card DB never in history**

Run: `git log --all --name-only | grep -iE 'Raw_CardDatabase|\.mtga$' | sort -u`
Expected: zero matches.

- [ ] **Step 4: Check no credentials in history**

Run: `git log --all -p | grep -iE 'Bearer [A-Za-z0-9]|eyJ[A-Za-z0-9]' | head -5`
Expected: zero matches (JWT-shaped strings that aren't code).

- [ ] **Step 5: Add *.mtga to .gitignore if missing, commit**

```bash
# Only if needed:
echo '*.mtga' >> .gitignore
git commit -am "chore: add *.mtga to gitignore"
```

---

### Task 6: GitHub Issues scrub (Phase 7)

**No file changes — GitHub API work.**

98 issues to scan. Look for:
- Pasted JSON payloads from proxy captures (WotC server responses)
- Screenshots showing real WotC server data
- Real JWTs or account tokens in debug logs
- Full protobuf dumps from recordings

- [ ] **Step 1: Dump all issue bodies for scanning**

```bash
gh issue list -R delebedev/leyline --state all --limit 200 --json number,title,body \
  | python3 -c "
import json, sys, re
issues = json.load(sys.stdin)
patterns = [
    r'eyJ[A-Za-z0-9+/=]{20,}',        # JWT
    r'\"sessionId\"',                    # captured session
    r'\"inventoryId\"',                  # captured inventory
    r'\"Gems\":\s*\d+',                 # real currency
    r'\"Gold\":\s*\d+',                 # real currency
    r'Bearer [A-Za-z0-9]',             # auth token
    r'mtga-internals',                  # private repo ref
]
for issue in issues:
    body = issue.get('body') or ''
    hits = [(p, re.search(p, body)) for p in patterns if re.search(p, body)]
    if hits:
        print(f\"#{issue['number']}: {issue['title']}\")
        for p, m in hits:
            print(f'  MATCH: {p} → ...{m.group()[:40]}...')
"
```

- [ ] **Step 2: Also scan issue comments**

```bash
for n in $(gh issue list -R delebedev/leyline --state all --limit 200 --json number -q '.[].number'); do
  comments=$(gh api repos/delebedev/leyline/issues/$n/comments --paginate -q '.[].body' 2>/dev/null)
  if echo "$comments" | grep -qE 'eyJ[A-Za-z0-9]{20}|"sessionId"|"inventoryId"|Bearer [A-Za-z]'; then
    echo "Issue #$n has suspect comment content"
  fi
done
```

- [ ] **Step 3: Edit or delete offending content**

For each hit: `gh issue edit <N> --body "$(new body)"` or `gh api -X PATCH repos/delebedev/leyline/issues/comments/<id> -f body="[redacted]"`

- [ ] **Step 4: Verify no remaining issues**

Re-run Step 1 scan. Expected: zero matches.

---

### Task 7: Forge submodule — confirm public-ready

**Verification only.**

- [ ] **Step 1: Check forge repo branch state**

Per scrub checklist: `delebedev/forge` was scrubbed 2026-03-17. Only `master` and `forge-ai` branches remain. Verify:

```bash
cd forge && git branch -r
```

Expected: only `origin/master`, maybe `origin/forge-ai`. No branches with nexus/golden/recording content.

- [ ] **Step 2: Verify forge submodule URL is HTTPS**

```bash
grep forge .gitmodules
```

Expected: `url = https://github.com/delebedev/forge.git` (already confirmed).

- [ ] **Step 3: Confirm forge repo can be flipped public**

This is a manual Denis action — just confirm the forge repo has no sensitive content on its remaining branches.

---

### Task 8: History scrub with git filter-repo (Phase 8)

**DESTRUCTIVE. Do this LAST, after all cleanup PRs are merged.**

**Prerequisites:** Tasks 1-7 complete. All cleanup committed to `main`.

- [ ] **Step 1: Identify files to scrub from history**

```bash
# Captured .bin files (WotC proto captures)
git log --all --name-only --diff-filter=A | sort -u | grep -E '\.bin$' | grep -v forge/ > /tmp/scrub-bins.txt

# Old golden files that were replaced
echo "frontdoor/src/main/resources/fd-golden/get-formats-response.bin" >> /tmp/scrub-bins.txt
echo "frontdoor/src/main/resources/fd-golden/get-sets-response.bin" >> /tmp/scrub-bins.txt

# Old arena-templates (captured proto)
git log --all --name-only | grep 'arena-templates/' | sort -u >> /tmp/scrub-bins.txt

# Review the list
cat /tmp/scrub-bins.txt | sort -u
```

- [ ] **Step 2: Dry-run filter-repo**

```bash
# Install if needed: brew install git-filter-repo
# Work on a fresh clone to be safe
cd /tmp && git clone ~/src/leyline leyline-scrub && cd leyline-scrub
git filter-repo --analyze
# Check analyze output for large blobs that might be captured data
cat .git/filter-repo/analysis/blobs-shas-and-paths.txt | head -30
```

- [ ] **Step 3: Run filter-repo**

```bash
cd /tmp/leyline-scrub
sort -u /tmp/scrub-bins.txt > /tmp/scrub-final.txt
git filter-repo --invert-paths --paths-from-file /tmp/scrub-final.txt
```

- [ ] **Step 4: Verify history is clean**

```bash
git log --all --name-only | grep -E '\.bin$' | grep -v forge/ | sort -u
# Expected: zero (or only forge submodule .bin refs which are in forge's history, not leyline's)

git log --all --name-only | grep 'arena-templates/' | sort -u
# Expected: zero
```

- [ ] **Step 5: Denis force-pushes the scrubbed repo**

This rewrites all commit hashes. Open PRs become invalid.

```bash
cd /tmp/leyline-scrub
git remote set-url origin https://github.com/delebedev/leyline.git
git push --force --all
git push --force --tags
```

**CONFIRM WITH DENIS BEFORE EXECUTING.**

---

### Task 9: Pre-publication final check (Phase 9)

**Checklist from POLICY.md:**

- [ ] All golden files replaced with hand-written equivalents
- [ ] `git filter-repo` run to remove old golden files from history
- [ ] `services.conf` and `setup.sh` point to `localhost` (not `leyline.games`)
- [ ] No `mtga-internals` cross-references in tracked files
- [ ] No captured recordings in git history
- [ ] No real credentials/tokens in git history
- [ ] `LICENSE` (GPLv3) at repo root
- [ ] `NOTICE` at repo root
- [ ] README disclaimer present
- [ ] Forge submodule points to public repo (or private fork is now public)
- [ ] All GitHub issues scrubbed of pasted WotC data
- [ ] Docs tone reviewed (local tool, not competing service)
- [ ] `.gitignore` covers `recordings/`, `data/`, card DB paths
- [ ] No hardcoded absolute paths (`/Users/denislebedev/...`)
- [ ] No internal IPs (Tailscale, LAN)
- [ ] No internal hostnames (`mini`, `klava`)

Run the full verification:

```bash
git grep '/Users/' -- ':(exclude)forge/' ':(exclude).claude/CLAUDE.md'
git grep 'mtga-internals' -- ':(exclude).claude/CLAUDE.md'
git grep 'leyline\.games'
git grep '100\.73\.'  -- ':(exclude)forge/'
git grep 'klava' -- ':(exclude)forge/'
git grep -E '\bmini\b' -- ':(exclude)forge/' ':(exclude)*.yaml' ':(exclude)*.json' | grep -v 'minimal\|minimize\|admin\|mini-'
```

All zero → flip the repo to public.

---

## Parallelism

| Task | Depends on | Can parallel with |
|------|-----------|-------------------|
| 1 (mtga-internals refs) | — | 2, 3, 4, 5 |
| 2 (absolute paths) | — | 1, 3, 4, 5 |
| 3 (Tailscale IPs) | — | 1, 2, 4, 5 |
| 4 (docs tone) | — | 1, 2, 3, 5 |
| 5 (gitignore audit) | — | 1, 2, 3, 4 |
| 6 (GitHub issues) | — | 1, 2, 3, 4, 5 |
| 7 (forge submodule) | — | 1, 2, 3, 4, 5, 6 |
| 8 (history scrub) | 1, 2, 3, 4 | 6 |
| 9 (final check) | ALL | — |

Tasks 1-7 are fully parallel. Task 8 after all file changes merged. Task 9 is the gate.
