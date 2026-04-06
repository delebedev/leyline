# Puzzle helpers
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
    # Support bare names: "bolt-face" → look in test resources, then root puzzles/
    f="{{file}}"
    if [ ! -f "$f" ]; then
        f="matchdoor/src/test/resources/puzzles/${f%.pzl}.pzl"
    fi
    if [ ! -f "$f" ]; then
        f="puzzles/${f%.pzl}.pzl"
    fi
    if [ ! -f "$f" ]; then
        echo "Puzzle not found: {{file}} (checked matchdoor test resources and root puzzles/)" >&2
        exit 1
    fi
    name=$(basename "${f%.pzl}")
    curl -s -X POST "http://localhost:8090/api/puzzle?file=$name"
