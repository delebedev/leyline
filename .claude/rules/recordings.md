---
paths:
  - "recordings/**"
  - "tooling/src/main/kotlin/leyline/recording/**"
  - "tooling/src/main/kotlin/leyline/conformance/**"
  - "tooling/src/main/kotlin/leyline/debug/SessionRecorder.kt"
  - "tooling/scripts/fd-inspect.py"
  - "just/recording.just"
  - "just/fd.just"
---

# Recordings

Recordings are captured client↔server sessions from `just serve-proxy`. They are the ground truth for what the real server sends.

- **Read-only reference data.** Never modify recording files programmatically. Parse and analyze, don't mutate.
- **Use `just fd-*` and `just rec-*`** to inspect recordings before writing code against them. Understand the real data shape first.
- **Session paths are timestamped** (`recordings/2026-03-06_16-47-08/`). Always resolve via `recordings/latest` or glob — never hardcode session names.
- **fd-frames.jsonl is the FD rosetta stone.** When implementing a new FD handler, find the real response with `just fd-response <CmdType>` and match its shape exactly.
- **`just fd-coverage`** shows which CmdTypes are handled vs observed — use it to find gaps.
- **`just rec-turninfo <session>`** dumps the turnInfo timeline (turn/phase/step/active/priority per GSM). Use it to compare phase transitions between engine and proxy recordings.
- **`proto-annotation-variance` "NOT IMPLEMENTED" means "not seen in proxy recordings"** — NOT "our code doesn't emit it." The tool only scans proxy sessions (real server traffic). If we implement an annotation but only test via `just serve` (local engine), it still shows NOT IMPLEMENTED. Always check our code before assuming a gap.
- **`persistentAnnotations` ≠ `annotations`** in GSMs. Ongoing effects (granted keywords, layered effects, aura attachments) live in `persistentAnnotations`. Regular `annotations` are one-shot events (zone transfers, damage, taps). When grepping recordings for effect-related data (AddAbility, LayeredEffect, etc.), search `persistentAnnotations` — they won't appear in `annotations`.

## Playbooks

- `docs/playbooks/fd-payload-playbook.md` — FD extraction, cross-session analysis, wire format
- `docs/playbooks/annotation-investigation-playbook.md` — investigating unknown annotations from variance reports
- `docs/playbooks/card-lookup-playbook.md` — grpId/ability lookups, zone IDs, SQLite queries
- `docs/playbooks/priority-debugging-playbook.md` — stuck game diagnosis, debug API endpoints, priority flow
