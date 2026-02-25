#!/usr/bin/env bash
# client-auto.sh — launch game client with log-driven automation
#
# Usage: ./scripts/client-auto.sh [--proxy|--stub] [--timeout 120]
#
# Monitors Player.log for state transitions, takes screenshots when
# state is ambiguous, and uses tools/click for input.
set -euo pipefail

MODE="${1:---stub}"
TIMEOUT="${2:-120}"
LOG_PATH="$HOME/Library/Logs/Wizards Of The Coast/MTGA/Player.log"
SHOT_DIR="/tmp/client-auto"
BUNDLE_ID="com.wizards.mtga"

mkdir -p "$SHOT_DIR"

# --- helpers ---

log() { echo "[$(date +%H:%M:%S)] $*"; }

screenshot() {
    local raw="$SHOT_DIR/_raw.png"
    local out="${1:-$SHOT_DIR/screen.jpg}"
    # Fullscreen apps need activation first
    activate_app
    sleep 0.5
    screencapture -x "$raw" 2>/dev/null || return 1
    # Downsize to 1280px + JPEG q60 — ~200KB vs ~1.7MB PNG
    sips --resampleWidth 1280 "$raw" --out "$SHOT_DIR/_resized.png" >/dev/null 2>&1
    sips -s format jpeg -s formatOptions 60 "$SHOT_DIR/_resized.png" --out "$out" >/dev/null 2>&1 \
        || mv "$SHOT_DIR/_resized.png" "$out"
    rm -f "$raw" "$SHOT_DIR/_resized.png"
    echo "$out"
}

screenshot_region() {
    # x,y,w,h
    local raw="$SHOT_DIR/_raw_region.png"
    local out="${5:-$SHOT_DIR/region.jpg}"
    activate_app
    sleep 0.5
    screencapture -x -R"$1,$2,$3,$4" "$raw" 2>/dev/null || return 1
    sips --resampleWidth 800 "$raw" --out "$SHOT_DIR/_resized.png" >/dev/null 2>&1
    sips -s format jpeg -s formatOptions 60 "$SHOT_DIR/_resized.png" --out "$out" >/dev/null 2>&1 \
        || mv "$SHOT_DIR/_resized.png" "$out"
    rm -f "$raw" "$SHOT_DIR/_resized.png"
    echo "$out"
}

click() {
    local script_dir
    script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
    "$script_dir/tools/click" "$1" "$2"
    log "clicked ($1,$2)"
}

activate_app() {
    osascript -e "tell application \"MTGA\" to activate" 2>/dev/null || true
}

get_window_bounds() {
    # Returns: x y w h
    osascript -e '
        tell application "System Events"
            tell process "MTGA"
                set p to position of window 1
                set s to size of window 1
                return (item 1 of p) & " " & (item 2 of p) & " " & (item 1 of s) & " " & (item 2 of s)
            end tell
        end tell
    ' 2>/dev/null || echo "0 0 1920 1080"
}

log_lines_since() {
    # Return new lines since last check
    local marker="$1"
    if [ -f "$LOG_PATH" ]; then
        local total
        total=$(wc -l < "$LOG_PATH" | tr -d ' ')
        if [ "$total" -gt "$marker" ]; then
            tail -n "+$((marker + 1))" "$LOG_PATH"
            echo "$total" > "$SHOT_DIR/.log_pos"
        fi
    fi
}

wait_for_log_pattern() {
    local pattern="$1"
    local timeout="${2:-60}"
    local start=$SECONDS
    local pos=0
    [ -f "$LOG_PATH" ] && pos=$(wc -l < "$LOG_PATH" | tr -d ' ')

    while [ $((SECONDS - start)) -lt "$timeout" ]; do
        if [ -f "$LOG_PATH" ]; then
            local new_lines
            new_lines=$(tail -n "+$((pos + 1))" "$LOG_PATH" 2>/dev/null || true)
            if echo "$new_lines" | grep -q "$pattern"; then
                return 0
            fi
            pos=$(wc -l < "$LOG_PATH" | tr -d ' ')
        fi
        sleep 1
    done
    return 1
}

# --- state detection from logs ---

detect_state() {
    if [ ! -f "$LOG_PATH" ]; then
        echo "NO_LOG"
        return
    fi
    local last50
    last50=$(tail -50 "$LOG_PATH" 2>/dev/null || true)

    # Check most-specific patterns first
    if echo "$last50" | grep -q "ArgumentNullException"; then
        echo "NRE_LOOP"
    elif echo "$last50" | grep -q "Client.TcpConnection.Close"; then
        echo "DISCONNECTED"
    elif echo "$last50" | grep -q "loadDesignerMetadata"; then
        echo "DESIGNER_METADATA"
    elif echo "$last50" | grep -q "PrepareAssets"; then
        echo "PREPARE_ASSETS"
    elif echo "$last50" | grep -q "FrontDoorConnectionAWS.Open"; then
        echo "FD_CONNECTED"
    elif echo "$last50" | grep -q "Doorbell response"; then
        echo "DOORBELL_OK"
    elif echo "$last50" | grep -q "Ringing Doorbell"; then
        echo "DOORBELL_WAIT"
    elif echo "$last50" | grep -q "fast login"; then
        echo "AUTO_LOGIN"
    elif echo "$last50" | grep -q "CredentialLoginContext"; then
        echo "STARTUP"
    elif echo "$last50" | grep -q "Initialize engine"; then
        echo "ENGINE_INIT"
    else
        echo "UNKNOWN"
    fi
}

# --- main ---

log "Client automation — mode=$MODE timeout=$TIMEOUT"

# Kill existing client
pkill -f MTGA 2>/dev/null || true
sleep 2

# Record initial log position (or note it doesn't exist yet)
LOG_START=0
[ -f "$LOG_PATH" ] && LOG_START=$(wc -l < "$LOG_PATH" | tr -d ' ')

log "Launching client..."
open -a "MTGA"
sleep 5

# Wait for log to start being written
log "Waiting for Player.log activity..."
for i in $(seq 1 30); do
    if [ -f "$LOG_PATH" ]; then
        current=$(wc -l < "$LOG_PATH" | tr -d ' ')
        if [ "$current" -gt "$LOG_START" ] || [ "$current" -lt "$LOG_START" ]; then
            log "Player.log active ($current lines)"
            break
        fi
    fi
    sleep 2
done

# Main monitoring loop
DEADLINE=$((SECONDS + TIMEOUT))
LAST_STATE=""
SCREENSHOT_COUNT=0

while [ $SECONDS -lt $DEADLINE ]; do
    STATE=$(detect_state)

    if [ "$STATE" != "$LAST_STATE" ]; then
        log "State: $STATE"
        LAST_STATE="$STATE"
    fi

    case "$STATE" in
        DOORBELL_WAIT)
            # Client stuck at doorbell — might need login. Wait a bit, then screenshot.
            sleep 10
            NEW_STATE=$(detect_state)
            if [ "$NEW_STATE" = "DOORBELL_WAIT" ]; then
                log "Still at doorbell after 10s — taking screenshot"
                activate_app
                sleep 1
                SHOT=$(screenshot "$SHOT_DIR/doorbell_stuck_$SCREENSHOT_COUNT.png")
                SCREENSHOT_COUNT=$((SCREENSHOT_COUNT + 1))
                log "Screenshot: $SHOT"
                log "Client may need manual login. Check screenshot."
                # Could add cliclick here if we know the login button coords
            fi
            ;;

        DOORBELL_OK|AUTO_LOGIN)
            log "Doorbell/login OK, waiting for FD connection..."
            sleep 5
            ;;

        FD_CONNECTED)
            log "Front Door connected! Waiting for startup sequence..."
            sleep 5
            ;;

        PREPARE_ASSETS|DESIGNER_METADATA)
            log "Client loading assets/metadata..."
            sleep 5
            ;;

        NRE_LOOP)
            log "Client in NRE loop (loadDesignerMetadata). Capturing state."
            SHOT=$(screenshot "$SHOT_DIR/nre_loop_$SCREENSHOT_COUNT.png")
            SCREENSHOT_COUNT=$((SCREENSHOT_COUNT + 1))
            log "Screenshot: $SHOT"
            # Count errors
            err_count=$(grep -c "ArgumentNullException" "$LOG_PATH" 2>/dev/null || echo 0)
            log "NRE count: $err_count"
            sleep 10
            ;;

        DISCONNECTED)
            log "Client disconnected from FD."
            break
            ;;

        *)
            sleep 3
            ;;
    esac
done

FINAL_STATE=$(detect_state)
log "Final state: $FINAL_STATE"
log "Log lines: $(wc -l < "$LOG_PATH" | tr -d ' ')"
log "Screenshots in: $SHOT_DIR"

# Dump last 20 lines of log
log "=== Last 20 log lines ==="
tail -20 "$LOG_PATH"
