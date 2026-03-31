# Tool CLIs — catchall passthrough
# Usage: just scry-ts board

[group('tools'), positional-arguments]
scry-ts *ARGS:
    @cd "{{project_dir}}/tools/scry-ts" && bun run cli.ts "$@"

# arena-ts: MTGA automation tool
# Usage: just arena-ts preflight

[group('tools'), positional-arguments]
arena-ts *ARGS:
    @cd "{{project_dir}}/tools/arena-ts" && bun run cli.ts "$@"

# Set/inject a puzzle via debug API. Hot-swaps if in match, queues for next Sparky match.
# Usage: just puzzle puzzles/bolt-face.pzl  OR  just puzzle bolt-face
# Clear: just puzzle --clear

[group('tools')]
puzzle file="":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ "{{file}}" = "--clear" ] || [ -z "{{file}}" ]; then
        curl -s -X POST "http://localhost:8090/api/puzzle"
        exit 0
    fi
    # Support bare names: "bolt-face" → look in test resources
    f="{{file}}"
    if [ ! -f "$f" ]; then
        f="matchdoor/src/test/resources/puzzles/${f%.pzl}.pzl"
    fi
    if [ ! -f "$f" ]; then
        echo "Puzzle not found: {{file}}" >&2
        exit 1
    fi
    name=$(basename "${f%.pzl}")
    curl -s -X POST "http://localhost:8090/api/puzzle?file=$name"
