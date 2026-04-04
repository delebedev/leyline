---
paths:
  - "justfile"
  - "just/**"
  - "gradle/scripts/**"
  - "buildSrc/**"
---

# Bootstrap & Forge Cache

`just bootstrap` is the single entry point for fresh clones and worktrees. It chains: submodule init → forge jar install → gradle build → seed DB.

## Forge M2 cache (`gradle/scripts/forge-m2.sh`)

Two modes, chosen automatically by whether `forge/` has uncommitted changes:

- **shared** (clean submodule): `forge/.m2-local` is a symlink → `~/.cache/leyline/forge-m2/<commit>/`. Multiple worktrees reuse the same jars. This is why `mvn install` is skipped when another worktree already built the same forge commit.
- **local** (dirty submodule): `forge/.m2-local` is a real directory. Prevents leaking uncommitted forge changes to other worktrees.

The script outputs three shell variables via `printf` — the justfile `eval`s the output to get `current_forge` (commit hash), `forge_cache_mode`, and `forge_m2` (resolved path).

## `.forge-commit-installed` stamp

Contains the forge content hash (from `git log -1 --format=%H -- forge-core/src ...`). Written by `just install-forge`, read by `CheckUpstreamTask` (gradle) and `check-upstream.sh` to skip redundant `mvn install`. Not the full submodule commit — only tracks source-affecting changes.

## Worktree submodule init

Bootstrap's reference-clone optimization (lines ~198-227 in justfile) tries to reuse another worktree's forge objects via `git submodule update --reference`. Requires a **non-shallow** forge checkout as source — if all existing checkouts are shallow, falls back to a fresh shallow clone. Functional but not fast; deepening one forge checkout (`git -C forge fetch --unshallow`) enables the optimization for all future worktrees.
