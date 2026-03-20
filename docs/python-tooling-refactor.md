# Python Tooling Refactor

## Why

Current Python tooling shape was wrong:

- `tools/arena/arena.py` is a 3k-line god script
- `arena` reaches into `scry` via `sys.path` hacks
- docs still describe older entrypoints
- Python tests are not wired as a real package/test surface

Need a real `uv` project with explicit package boundaries.
Home for packaged Python code: `tools/py/`.

## Dependency rule

- `leyline_tools.scry` owns Player.log parsing, accumulation, scene tracking, card resolution
- `leyline_tools.arena` owns MTGA automation, OCR/capture/input, navigation, gameplay actions
- `arena` may depend on `scry`
- `scry` must not depend on `arena`
- shared code only when genuinely cross-cutting

No circular imports inside packaged code. Thin wrappers in `tools/arena/arena.py`
and `tools/scry/cli.py` may keep a tiny bootstrap `sys.path` shim during migration.

## Target layout

```text
tools/py/
  pyproject.toml
  uv.lock
  src/leyline_tools/
    scry/
      cli.py
      parser.py
      tail.py
      models.py
      accumulator.py
      tracker.py
      annotations.py
      errors.py
      cards.py
      server.py
    arena/
      cli.py
      macos.py
      debug_api.py
      capture.py
      interaction.py
      hand.py
      gameplay.py
      bot_match.py
      nav.py
      board.py
      diagnostics.py
      commands/
```

Compatibility shims remain in `tools/` for `just` stability.

## Current status

- package workspace moved to `tools/py/`
- `scry` moved into `leyline_tools.scry`
- `arena` split into package modules; CLI now thin registry/main
- `just arena` / `just scry` run package entrypoints via `uv`
- repo docs updated to point at `tools/py`

## Migration phases

### Phase 1

- package the project properly under `src/`
- move `scry` core into `leyline_tools.scry`
- keep `tools/scry/cli.py` as thin wrapper
- make `arena` import packaged `scry`
- no command behavior change

### Phase 2

- extract `arena` infrastructure from `arena.py`
- split native/macOS glue, OCR/capture, debug API, session logging
- keep existing CLI surface stable

### Phase 3

- split `arena` gameplay/navigation commands into modules
- add proper Python test entrypoints to the repo
- repair docs to match reality

## Immediate non-goals

- no rewrite of OCR/card-play logic
- no switch away from Swift native helpers
- no attempt to merge `arena` and `scry` into one package
- no broad command redesign in the first pass
