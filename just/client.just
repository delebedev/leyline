# macOS client automation (MTGA interaction, screenshots)
# Imported by justfile — all variables available from parent.

# (re)launch MTGA client — kills existing instance first
[group('client')]
mtga:
    @pkill -9 -f MTGA 2>/dev/null || true; sleep 1; open -a "MTGA"


# kill server (all ports) + MTGA client — clean slate for next run
[group('client')]
stop:
    #!/usr/bin/env bash
    set -euo pipefail
    for p in {{ports}}; do
        for pid in $(lsof -ti :$p 2>/dev/null); do
            echo "Killing pid $pid on port $p"
            kill -9 $pid 2>/dev/null || true
        done
    done
    pkill -9 -f MTGA 2>/dev/null && echo "Killed MTGA" || echo "MTGA not running"
    echo "Stopped."

# composable MTGA automation CLI
[group('client')]
arena *args: (_require classpath) check-java
    @uv run --project "{{project_dir}}/tools/py" arena {{args}}

# scry — Player.log state accumulator (current game state from client perspective)
[group('client')]
scry *args:
    @uv run --project "{{project_dir}}/tools/py" scry {{args}}

# start a puzzle match via Sparky's Challenge (requires puzzle in leyline.toml)
[group('client')]
puzzle:
    #!/usr/bin/env bash
    set -euo pipefail
    a() { just arena "$@"; }

    echo "Navigating to Sparky's Challenge..."
    # Open play blade if not already open (right-side blade)
    if ! a ocr --find "Sparky" 2>/dev/null | grep -q "Sparky"; then
        a click 867,519  # Play blade button (bottom-right, stable)
        sleep 2
    fi
    a click "Sparky" --retry 3
    sleep 2

    # Event screen: Start (fresh event) or Play/Resume (already joined)
    # Start needs two clicks (joins event, then screen refreshes), then Play to launch
    a click 864,517  # Start or Play — same position
    sleep 2
    a click 864,517  # second click: Play after Start, or no-op if was already Play
    sleep 1
    a click 864,517  # Play to launch match
    sleep 8

    # Wait for puzzle to load (no mulligan — goes straight to game)
    sleep 5
    state=$(just scry state --no-cards 2>/dev/null | head -1 || echo "")
    if echo "$state" | grep -qE "Main|Combat|End"; then
        echo "Puzzle loaded: $state"
    else
        echo "Warning: puzzle may not have loaded — check scry state"
    fi
