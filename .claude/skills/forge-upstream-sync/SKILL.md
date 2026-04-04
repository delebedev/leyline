---
name: forge-upstream-sync
description: Sync the forge engine submodule with upstream Card-Forge/forge. Merge upstream into stable branch, resolve conflicts preserving fork-local patches, validate with leyline test suite, create PR.
---

## What I do

Merge upstream `Card-Forge/forge` master into `delebedev/forge` `stable` branch, validate against leyline's test suite, and land via PR.

**Reference:** `PATCHES.md` in the forge submodule root has the full fork-local patch catalog. Read it before starting.

## Repo Structure

`delebedev/forge` is a **true GitHub fork** of `Card-Forge/forge`.

- `master` — tracks upstream automatically. Don't commit here.
- `stable` — default branch. Our engine patches on top of a pinned upstream point. Leyline submodule points here.
- `PATCHES.md` — catalog of fork-local changes. Update when adding/removing patches.

## Prerequisites

- `mvn` in PATH (`brew install maven`)
- Forge submodule initialized (`git submodule update --init`)

## Flow

### 1. Start from leyline root

```bash
cd ~/src/leyline
git status --short --branch
git submodule status
```

### 2. Fetch upstream via master

```bash
git -C forge fetch origin master --depth=100
```

Divergence check:

```bash
git -C forge rev-list --left-right --count stable...origin/master
```

Check preserve surface for upstream changes (see `fork-deltas.md` for full file list):

```bash
git -C forge log --oneline origin/master --not stable -- \
  forge-gui/src/main/java/forge/player/PlayerControllerHuman.java \
  forge-gui/src/main/java/forge/player/TargetSelection.java \
  forge-game/src/main/java/forge/game/player/PlaySpellAbility.java \
  forge-game/src/main/java/forge/game/event/ \
  forge-game/src/main/java/forge/game/GameAction.java \
  forge-game/src/main/java/forge/game/spellability/AbilityManaPart.java
```

### 3. Create sync branch from stable

```bash
git -C forge fetch origin stable
git -C forge worktree add ../forge--sync -b sync/upstream-$(date +%Y-%m-%d) origin/stable
cd ~/src/leyline/forge--sync
git merge --no-commit --no-ff origin/master
```

### 4. Resolve conflicts

Priority order:

1. **CI workflows** — keep fork deletions (`git rm`)
2. **IGameEventVisitor** — keep ours (fork adds visit methods for custom events)
3. **Controller seam** (`PlayerControllerHuman`, `TargetSelection`) — take upstream, preserve fork seam methods
4. **Event classes** — keep fork enrichments
5. **Adventure/mobile/desktop content** — take upstream (`git checkout --theirs`)
6. **pom.xml** — take upstream but **restore `versionCode` to `2.0.10`** (or current stable version)
7. **Everything else** — take upstream

Bulk upstream-take for non-leyline files:

```bash
git diff --diff-filter=U --name-only | grep -v "preserve-pattern" | while IFS= read -r f; do
  git checkout --theirs -- "$f" && git add "$f" || git rm "$f"
done
```

### 5. Install merged forge

`just install-forge` hardcodes `forge/` dir. Run maven directly:

```bash
cd ~/src/leyline/forge--sync
mvn org.codehaus.mojo:flatten-maven-plugin:1.6.0:flatten install \
  -pl forge-core,forge-game,forge-ai,forge-gui -am \
  -DskipTests -Dcheckstyle.skip=true -q
rsync -a ~/src/leyline/forge--sync/.m2-local/ ~/src/leyline/forge/.m2-local/
```

### 6. Build + test

```bash
cd ~/src/leyline
just build
just test-gate        # must pass 100%
just test-integration # known pre-existing failures ok
```

### 7. If leyline breaks, patch in the right place

- Engine API change: patch in `forge--sync`, commit, re-install, re-test
- Leyline adapter code: patch in leyline

Common fixes:
- **Constructor signature changes** — update fork seam calls
- **Duplicate methods from auto-merge** — git silently duplicates identical methods added at different positions
- **Missing fork-local event fire sites** — single-line `game.fireEvent(...)` calls auto-merge away. Always verify fire sites (see `fork-deltas.md`)
- **Version mismatch** — upstream bumps `versionCode` in pom.xml. Restore to stable's version or update `libs.versions.toml` to match.

### 8. Land it

1. Commit merge in `forge--sync`
2. Push branch to `delebedev/forge`, create PR against `stable`
3. Update `PATCHES.md` if patches changed
4. Squash-merge forge PR (one logical sync)
5. In leyline: `git -C forge fetch origin stable && git -C forge checkout origin/stable && git add forge`
6. Create leyline branch + PR with submodule pointer update

## Troubleshooting

### Maven install fails with checkstyle errors
Add `-Dcheckstyle.skip=true`.

### Upstream fetch times out
Use `--depth=100`.

### Compile error: duplicate method
Auto-merge artifact. Search for the method name — delete the duplicate.

### Compile error: constructor signature mismatch
Read the new constructor, update the fork seam call.

### Tests fail for "missing annotation"
First check: (1) fork-local fire sites still present? (2) engine thread crashing earlier? (3) upstream changed Java call paths bypassing Kotlin overrides?

### Integration test timing regression
Check if pre-existing by testing against old forge jars.
