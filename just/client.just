# macOS client automation (MTGA interaction, screenshots)
# Imported by justfile — all variables available from parent.

# (re)launch MTGA client — kills existing instance first
[group('client')]
mtga:
    @pkill -9 -f MTGA 2>/dev/null || true; sleep 1; open -a "MTGA"

# launch client with log-driven automation (screenshots + cliclick)
[group('client')]
client-auto mode="--proxy" timeout="120":
    @bash scripts/client-auto.sh {{mode}} {{timeout}}

# synthetic mouse click (works on macOS 15+ / Unity via timestamp fix)
# NOTE: must activate MTGA window first — Unity ignores clicks on background windows
[group('client')]
click x y action="click":
    @osascript -e 'tell application "MTGA" to activate' 2>/dev/null; sleep 0.3; {{project_dir}}/tools/click {{x}} {{y}} {{action}}

# screenshot game client window (by window ID, JPEG q60, max 1280px)
[group('client')]
capture-screenshot out="/tmp/mtga.jpg":
    #!/usr/bin/env bash
    set -euo pipefail
    wid=$(swift -e 'import CoreGraphics; let l=CGWindowListCopyWindowInfo(.optionAll,kCGNullWindowID) as! [[String:Any]]; var best=0; var bestArea=0; for w in l { let o=w["kCGWindowOwnerName"] as? String ?? ""; if o.contains("MTGA"){ let b=w["kCGWindowBounds"] as? [String:Any] ?? [:]; let h=b["Height"] as? Int ?? 0; let ww=b["Width"] as? Int ?? 0; let a=h*ww; if a>bestArea{ bestArea=a; best=w["kCGWindowNumber"] as? Int ?? 0}}}; if best>0{print(best)}' 2>/dev/null)
    if [ -z "$wid" ]; then echo "MTGA window not found"; exit 1; fi
    screencapture -x -l "$wid" /tmp/_cap_raw.png
    sips --resampleWidth 1280 /tmp/_cap_raw.png --out /tmp/_cap_r.png >/dev/null 2>&1
    sips -s format jpeg -s formatOptions 60 /tmp/_cap_r.png --out {{out}} >/dev/null 2>&1
    rm -f /tmp/_cap_raw.png /tmp/_cap_r.png
    echo "{{out}} ($(du -h {{out}} | cut -f1))"

# one-shot client state from Player.log (no server needed)
[group('client')]
client-state:
    #!/usr/bin/env bash
    log="$HOME/Library/Logs/Wizards Of The Coast/MTGA/Player.log"
    if [ ! -f "$log" ]; then echo "NO_LOG"; exit 0; fi
    lines=$(wc -l < "$log" | tr -d ' ')
    last=$(tail -30 "$log")
    state="UNKNOWN"
    if echo "$last" | grep -q "ArgumentNullException"; then state="NRE_LOOP"
    elif echo "$last" | grep -q "home page notification"; then state="LOBBY"
    elif echo "$last" | grep -q "TcpConnection.Close"; then state="DISCONNECTED"
    elif echo "$last" | grep -q "PrepareAssets\|loadDesignerMetadata"; then state="LOADING"
    elif echo "$last" | grep -q "FrontDoorConnectionAWS.Open"; then state="FD_CONNECTED"
    elif echo "$last" | grep -q "Doorbell response"; then state="DOORBELL_OK"
    elif echo "$last" | grep -q "Ringing Doorbell"; then state="DOORBELL_WAIT"
    elif echo "$last" | grep -q "Initialize engine"; then state="ENGINE_INIT"
    fi
    echo "$state ($lines lines)"
    tail -5 "$log"

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

# composable MTGA automation CLI (tools/arena is the standalone entry point)
[group('client')]
arena *args: (_require classpath) check-java
    @{{project_dir}}/tools/arena {{args}}

# tail Player.log for client-side exceptions (standalone, no server)
[group('client')]
watch-client: (_require classpath) check-java
    @{{_cli}} leyline.debug.WatchClientLogKt
