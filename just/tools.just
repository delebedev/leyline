# Puzzle helpers
# Usage: just puzzle bolt-face
# Clear: just puzzle --clear

[group('tools')]
puzzle file="":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ "{{file}}" = "--clear" ] || [ -z "{{file}}" ]; then
        curl -s -X POST "http://localhost:8090/api/puzzle"
        exit 0
    fi
    curl -s -X POST "http://localhost:8090/api/puzzle?file={{file}}"
