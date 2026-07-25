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

- **shared** (clean submodule): `forge/.m2-local` is a symlink → `~/.cache/leyline/forge-m2/<commit>/`. Multiple worktrees reuse the same jars. A file lock serializes the first install for a commit; concurrent and later installs reuse its success marker.
- **local** (dirty submodule): `forge/.m2-local` is a real directory. Prevents leaking uncommitted forge changes to other worktrees.

The script outputs three shell variables via `printf` — the justfile `eval`s the output to get `current_forge` (commit hash), `forge_cache_mode`, and `forge_m2` (resolved path).

## `.forge-commit-installed` stamp

Contains the Forge submodule commit. Written by `just install-forge` and read by `CheckUpstreamTask` so Gradle rejects a checkout whose Forge jars have not been installed.

## Worktree submodule init

Bootstrap's reference-clone optimization (lines ~198-227 in justfile) tries to reuse another worktree's forge objects via `git submodule update --reference`. Requires a **non-shallow** forge checkout as source — if all existing checkouts are shallow, falls back to a fresh shallow clone. Functional but not fast; deepening one forge checkout (`git -C forge fetch --unshallow`) enables the optimization for all future worktrees.
