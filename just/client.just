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
    @python3 {{project_dir}}/tools/arena/arena.py {{args}}

# scry — Player.log state accumulator (current game state from client perspective)
[group('client')]
scry *args:
    @python3 {{project_dir}}/tools/scry/scry_lib/cli.py {{args}}

