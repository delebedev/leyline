# Last Session

**Date:** 2026-03-17
**Branch:** main

## What happened
- Merged 4 refactor commits to main + pushed (PersistentAnnotationStore, LoopSignal, PvP E2E test)
- Restructured memory: monolith MEMORY.md → 6 focused files + thin index
- Built `tape proto find-card` — card name → instanceIds + zone transitions in one command
- Added `-s` session flag to `tape proto trace` (auto-discovers seat-based MD payloads)
- Fixed `card-grp` to support partial name matching (LIKE instead of exact)
- Fixed justfile quoting: wire/tape recipes now preserve multi-word args
- Trimmed SessionAnalyzer: removed cardIndex, invariantViolations, gsidChain (noisy/redundant)
- Fixed annotationCoverage (was silently dropping all types due to broken numeric parser)
- Created `/recording-inspect` skill — interactive human-in-the-loop session analysis
- Established `notes.md` convention for per-session observations (gitignored)
- Cleaned recordings: 476 → 20 sessions (trashed empty + no-MD), saved ~500MB
- Updated `/handoff` skill for informal/mixed sessions

## Changed
- `tools/tape/tape.py` — find-card, session-aware trace, MD payload resolver
- `just/lookup.just` — partial match for card-grp
- `just/tools.just` — positional-arguments + "$@" for quoting
- `tooling/.../SessionAnalyzer.kt` — trimmed output, seat-aware discovery, annotation fix
- `tooling/.../AnalysisCli.kt` + `DebugServer.kt` — follow-up for removed fields
- `.claude/skills/recording-inspect/` — new skill
- `.claude/skills/handoff/` — updated template
- `CLAUDE.md` — notes.md in recordings section

## Open threads
- SessionAnalyzer `mode` always "unknown" for proxy sessions (no mode.txt written)
- `interestingMoments` empty because manifest already has all mechanics — only fires on net-new
- Per-match recording split discussed but not implemented
- Other agent's matchdoor changes still uncommitted (WebGuiGame, GameEventCollector, etc.)
