set shell := ["bash", "-euo", "pipefail", "-c"]

import '../build/java.just'

root_dir     := justfile_directory() / ".."
nexus_dir    := justfile_directory()
web_dir      := root_dir / "forge-web"
classpath    := nexus_dir / "target/classpath.txt"
logback      := nexus_dir / "src/main/resources/logback.xml"
logback_cli  := nexus_dir / "src/main/resources/logback-cli.xml"
templates    := nexus_dir / "src/main/resources/arena-templates"
certs        := env("NEXUS_CERTS", env("HOME", "/tmp") / ".local/share/forge-nexus/certs")
fd_ip        := env("NEXUS_FD_IP", "35.160.172.88")
md_ip        := env("NEXUS_MD_IP", "44.245.90.131")
payloads     := env("NEXUS_PAYLOADS", nexus_dir / "recordings/latest/capture/payloads")
ports        := "30010 30003 8090"

# --- JVM flags (shared base + per-mode overrides) ---

_jvm_base    := "-Dio.netty.tryReflectionSetAccessible=true --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED"
jvm_opts     := _jvm_base + " -Dlogback.configurationFile=" + logback
jvm_opts_cli := _jvm_base + " -Dlogback.configurationFile=" + logback_cli + " -Dlogback.statusListenerClass=ch.qos.logback.core.status.NopStatusListener"

# --- Java launch helpers ---

# Full classpath expression (shared by _nexus_java and _nexus_cli)
_cp := '"$classpath:' + nexus_dir + '/target/classes:' + web_dir + '/target/classes"'

# Kill ports + launch (for server targets)
_nexus_java := 'for p in ' + ports + '; do for pid in $(lsof -ti :$p 2>/dev/null); do echo "Killing pid $pid on port $p"; kill -9 $pid 2>/dev/null || true; done; done; sleep 0.3; classpath="$(< "' + classpath + '")"; "$JAVA_HOME/bin/java" ' + jvm_opts + ' -cp ' + _cp
# Read-only CLI (no port kill)
_nexus_cli  := 'classpath="$(< "' + classpath + '")"; "$JAVA_HOME/bin/java" ' + jvm_opts_cli + ' -cp ' + _cp

# --- Cert flags (shared by all serve-* targets) ---
# Only passed when all four files exist; otherwise server uses self-signed.

_fd_cert := certs / "frontdoor-combined.pem"
_fd_key  := certs / "frontdoor.key"
_md_cert := certs / "matchdoor-combined.pem"
_md_key  := certs / "matchdoor.key"
_cert_check := '[ -f "' + _fd_cert + '" ] && [ -f "' + _fd_key + '" ] && [ -f "' + _md_cert + '" ] && [ -f "' + _md_key + '" ]'
_cert_flags := '--fd-cert "' + _fd_cert + '" --fd-key "' + _fd_key + '" --md-cert "' + _md_cert + '" --md-key "' + _md_key + '"'

# --- Build ---

flatten := "org.codehaus.mojo:flatten-maven-plugin:1.6.0:flatten"

# auto-format Kotlin sources (spotless/ktlint)
fmt: check-java
    cd "{{root_dir}}" && mvn -pl forge-nexus com.diffplug.spotless:spotless-maven-plugin:apply -q
    @echo "fmt done."

# check formatting without modifying (CI)
fmt-check: check-java
    cd "{{root_dir}}" && mvn -pl forge-nexus com.diffplug.spotless:spotless-maven-plugin:check -q

# compile proto + Kotlin, install forge-web JAR to ~/.m2
build: check-java _check-upstream
    #!/usr/bin/env bash
    set -euo pipefail
    rm -rf "$HOME/.m2/repository/forge/forge/\${revision}" 2>/dev/null || true
    cd "{{root_dir}}" && mvn -pl forge-web {{flatten}} {{mvn_skip}} install -q
    cd "{{root_dir}}" && mvn -pl forge-nexus {{mvn_skip}} compile
    just -f "{{nexus_dir}}/justfile" _refresh-classpath
    echo "Build complete. Classpath: {{classpath}}"

# fast Kotlin-only compile (~3-5s, skip forge-web install)
dev-build: check-java
    #!/usr/bin/env bash
    set -euo pipefail
    cd "{{root_dir}}" && mvn -pl forge-nexus {{mvn_skip}} compile -q && echo "dev-build OK"
    just -f "{{nexus_dir}}/justfile" _refresh-classpath

# --- Test ---

# run tests (assumes `just build` has been run)
test: check-java _check-upstream _clean-surefire (_mvn-test "")

# single test class (e.g. `just test-one StructuralFingerprintTest`)
test-one class: check-java _check-upstream _clean-surefire (_mvn-test ("-Dtest=" + class + " -Dsurefire.failIfNoSpecifiedTests=false"))

# unit tests only (no engine bootstrap, fastest)
test-unit: check-java _check-upstream _clean-surefire (_mvn-test "-Dgroups=unit")

# all conformance tests (~5s, wire-shape checks against Arena patterns)
test-conformance: check-java _check-upstream _clean-surefire (_mvn-test "-Dgroups=conformance")

# integration tests (parallel forks — boots engine per fork, runs classes concurrently)
test-integration: check-java _check-upstream _clean-surefire (_mvn-test "-Dgroups=integration -DforkCount=4 -DreuseForks=true")

# pre-commit gate: unit + conformance (fast, single fork)
test-gate: check-java _check-upstream _clean-surefire (_mvn-test "-Dgroups=unit,conformance")

# full gate: unit + conformance, then integration in parallel forks
test-full: test-gate test-integration

# --- Dev ---

# watch *.kt, recompile + restart hybrid on change (fast: nexus-only compile)
dev: check-java
    #!/usr/bin/env bash
    set -euo pipefail
    echo "Starting hybrid with file watcher (dev-build, nexus-only compile)..."
    echo "Ctrl-C to stop. Changes to *.kt trigger recompile + restart."
    echo "Tip: if you changed forge-web code, run 'just build' first."
    trap 'kill %% 2>/dev/null; exit 0' INT TERM
    while true; do
        just dev-build
        echo "--- hybrid running (pid below) ---"
        just serve &
        NEXUS_PID=$!
        if command -v fswatch >/dev/null 2>&1; then
            fswatch -1 -r -e '.*' -i '\.kt$' "{{nexus_dir}}/src/main/kotlin"
        else
            echo "(fswatch not found — poll fallback, 3s)"
            STAMP=$(stat -f%m "{{nexus_dir}}/src/main/kotlin" 2>/dev/null || echo 0)
            while true; do
                sleep 3
                NEW=$(stat -f%m "{{nexus_dir}}/src/main/kotlin" 2>/dev/null || echo 0)
                [ "$STAMP" != "$NEW" ] && break
            done
        fi
        echo "--- change detected, rebuilding ---"
        kill $NEXUS_PID 2>/dev/null; wait $NEXUS_PID 2>/dev/null || true
    done

# headless smoke test (validates auth → mulligan flow without MTGA)
smoke: (_require classpath) check-java
    @{{_nexus_java}} forge.nexus.debug.SmokeTestKt

# puzzle mode: serve with a specific .pzl file
serve-puzzle filename: (_require classpath) check-java
    #!/usr/bin/env bash
    set -euo pipefail
    cert_flags=""; if {{_cert_check}}; then cert_flags="{{_cert_flags}}"; fi
    {{_nexus_java}} forge.nexus.NexusMainKt $cert_flags --proxy-fd {{fd_ip}} --puzzle "{{filename}}"

# --- Certs ---

# regenerate TLS certs signed by mitmproxy CA (run after Arena patch)
# extra_san: additional IP for SAN (e.g. Tailscale IP for remote client)
gen-certs fd_host="" md_host="" extra_san="":
    #!/usr/bin/env bash
    set -euo pipefail
    ca_cert="$HOME/.mitmproxy/mitmproxy-ca-cert.pem"
    ca_key="$HOME/.mitmproxy/mitmproxy-ca.pem"
    out="{{certs}}"
    if [ ! -f "$ca_cert" ] || [ ! -f "$ca_key" ]; then
        echo "mitmproxy CA not found. Run: brew install mitmproxy && mitmdump -q & sleep 2 && kill %1" >&2
        exit 1
    fi
    # auto-discover hostnames from Player.log if not provided
    player_log="$HOME/Library/Logs/Wizards Of The Coast/MTGA/Player.log"
    fd="{{fd_host}}"
    md="{{md_host}}"
    if [ -z "$fd" ] && [ -f "$player_log" ]; then
        fd=$(grep -oE "frontdoor-mtga-production-[a-zA-Z0-9._-]+\.w2\.mtgarena\.com" "$player_log" | sort -u | tail -1)
    fi
    if [ -z "$md" ] && [ -f "$player_log" ]; then
        md=$(grep -oE "matchdoor-mtga-production-[a-zA-Z0-9._-]+\.w2\.mtgarena\.com" "$player_log" | sort -u | tail -1)
    fi
    if [ -z "$fd" ]; then echo "Cannot auto-detect FD hostname. Pass fd_host= or launch Arena once." >&2; exit 1; fi
    if [ -z "$md" ]; then echo "Cannot auto-detect MD hostname. Pass md_host= or launch Arena once." >&2; exit 1; fi
    mkdir -p "$out"
    echo "Generating certs in $out"
    echo "  FD: $fd"
    echo "  MD: $md"
    for door in frontdoor matchdoor; do
        host="$fd"; [ "$door" = "matchdoor" ] && host="$md"
        openssl req -new -newkey rsa:2048 -nodes \
            -keyout "$out/$door.key" -out "$out/$door.csr" \
            -subj "/CN=$host" 2>/dev/null
        openssl x509 -req -in "$out/$door.csr" \
            -CA "$ca_cert" -CAkey "$ca_key" -CAcreateserial \
            -out "$out/$door.crt" -days 365 \
            -extfile <(
                san="DNS:$host,DNS:localhost,IP:127.0.0.1"
                [ -n "{{extra_san}}" ] && san="$san,IP:{{extra_san}}"
                echo "subjectAltName=$san"
            ) 2>/dev/null
        cat "$out/$door.crt" "$out/$door.key" > "$out/$door-combined.pem"
        rm -f "$out/$door.csr"
        echo "  ✓ $door-combined.pem"
    done
    echo "Done. Certs valid for 365 days."

# --- Serve ---

# hybrid mode: proxy FD to real Arena, stub MD (default dev mode)
serve: (_require classpath) check-java
    #!/usr/bin/env bash
    set -euo pipefail
    cert_flags=""; if {{_cert_check}}; then cert_flags="{{_cert_flags}}"; fi
    {{_nexus_java}} forge.nexus.NexusMainKt $cert_flags --proxy-fd {{fd_ip}}

# stub mode (fake both doors, fully offline)
serve-stub: (_require classpath) check-java
    #!/usr/bin/env bash
    set -euo pipefail
    cert_flags=""; if {{_cert_check}}; then cert_flags="{{_cert_flags}}"; fi
    {{_nexus_java}} forge.nexus.NexusMainKt $cert_flags

# replay-stub mode: replay captured FD session (fd-frames.jsonl), stub MD
serve-replay-stub golden="": (_require classpath) check-java
    #!/usr/bin/env bash
    set -euo pipefail
    cert_flags=""; if {{_cert_check}}; then cert_flags="{{_cert_flags}}"; fi
    golden="{{golden}}"
    if [ -z "$golden" ]; then
        # Auto-detect: latest capture with fd-frames.jsonl
        golden=$(ls -td recordings/*/capture/fd-frames.jsonl 2>/dev/null | head -1)
        if [ -z "$golden" ]; then
            echo "No fd-frames.jsonl found. Run: just serve-proxy (then connect client)" >&2
            exit 1
        fi
        echo "Using golden: $golden"
    fi
    {{_nexus_java}} forge.nexus.NexusMainKt $cert_flags --fd-golden "$golden"

# proxy mode (both doors, capture traffic)
serve-proxy: (_require classpath) check-java
    #!/usr/bin/env bash
    set -euo pipefail
    cert_flags=""; if {{_cert_check}}; then cert_flags="{{_cert_flags}}"; fi
    {{_nexus_java}} forge.nexus.NexusMainKt $cert_flags --proxy-fd {{fd_ip}} --proxy-md {{md_ip}}

# replay mode (proxy FD, replay recorded bytes on MD)
serve-replay: (_require classpath) check-java
    #!/usr/bin/env bash
    set -euo pipefail
    cert_flags=""; if {{_cert_check}}; then cert_flags="{{_cert_flags}}"; fi
    {{_nexus_java}} forge.nexus.NexusMainKt $cert_flags --proxy-fd {{fd_ip}} --replay {{payloads}}

# smoke test: start stub, launch MTGA, check for FD errors via debug API
smoke-client timeout="60": (_require classpath) check-java
    #!/usr/bin/env bash
    set -euo pipefail
    cert_flags=""; if {{_cert_check}}; then cert_flags="{{_cert_flags}}"; fi
    echo "Starting stub server..."
    {{_nexus_java}} forge.nexus.NexusMainKt $cert_flags &
    SERVER_PID=$!
    trap "kill $SERVER_PID 2>/dev/null" EXIT
    sleep 5
    if ! kill -0 $SERVER_PID 2>/dev/null; then echo "FAIL: server didn't start"; exit 1; fi
    echo "Launching MTGA..."
    open -a "MTGA"
    sleep 3
    echo "Monitoring for {{timeout}}s..."
    deadline=$((SECONDS + {{timeout}}))
    match_door_connected=false
    while [ $SECONDS -lt $deadline ]; do
        # Check for MatchCreated in FD messages
        if curl -sf "http://localhost:8090/api/fd-messages" | grep -q '"cmdType":600'; then
            echo "  MatchCreated pushed!"
        fi
        # Check for client errors
        err_count=$(curl -sf "http://localhost:8090/api/client-errors" | grep -co '"exceptionType"' 2>/dev/null | tail -1)
        if [ "${err_count:-0}" -gt 0 ] 2>/dev/null; then echo "  Client errors: $err_count"; fi
        # Check if Match Door got a connection
        if curl -sf "http://localhost:8090/api/logs?level=INFO" | grep -q 'Match Door: client connected'; then
            echo ""; echo "PASS: Client connected to Match Door!"; match_door_connected=true; break
        fi
        sleep 3
    done
    if [ "$match_door_connected" = "false" ]; then
        echo ""; echo "FAIL: Client did not connect to Match Door within {{timeout}}s"
        echo "Check: curl http://localhost:8090/api/fd-messages"
        echo "Check: curl http://localhost:8090/api/client-errors"
        exit 1
    fi

# (re)launch MTGA client — kills existing instance first
mtga:
    @pkill -9 -f MTGA 2>/dev/null || true; sleep 1; open -a "MTGA"

# launch client with log-driven automation (screenshots + cliclick)
client-auto mode="--proxy" timeout="120":
    @bash scripts/client-auto.sh {{mode}} {{timeout}}

# synthetic mouse click (works on macOS 15+ / Unity via timestamp fix)
# NOTE: must activate MTGA window first — Unity ignores clicks on background windows
click x y action="click":
    @osascript -e 'tell application "MTGA" to activate' 2>/dev/null; sleep 0.3; {{nexus_dir}}/tools/click {{x}} {{y}} {{action}}

# screenshot game client window (by window ID, JPEG q60, max 1280px)
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

# re-decode FD raw frames → fd-frames.jsonl (fixes compressed payloads)
decode-golden dir="": (_require classpath) check-java
    #!/usr/bin/env bash
    set -euo pipefail
    d="{{dir}}"
    if [ -z "$d" ]; then
        d=$(ls -td recordings/*/capture 2>/dev/null | head -1)
        if [ -z "$d" ]; then echo "No captures found." >&2; exit 1; fi
    fi
    {{_nexus_cli}} forge.nexus.protocol.DecodeFdCaptureKt "$d"

# tail Player.log for client-side exceptions (standalone, no server)
watch-client: (_require classpath) check-java
    @{{_nexus_cli}} forge.nexus.debug.WatchClientLogKt

# --- Proto inspection ---

# inspect a .bin template (no port kill — safe while server runs)
proto-inspect file=(templates / "mulligan-req-seat1.bin"): (_require classpath) check-java
    @{{_nexus_cli}} forge.nexus.debug.InspectKt "{{file}}"

# decode Match Door payloads
proto-decode: (_require classpath) check-java
    @{{_nexus_cli}} forge.nexus.protocol.DecodeCaptureKt {{payloads}}

# save last S→C payload as template
proto-extract name="extracted": (_require classpath) check-java
    #!/usr/bin/env bash
    set -euo pipefail
    latest=$(ls -t {{payloads}}/S-C_*.bin 2>/dev/null | head -1)
    if [ -z "$latest" ]; then
        echo "No S→C payloads found in {{payloads}}" >&2; exit 1
    fi
    dest="{{templates}}/{{name}}.bin"
    cp "$latest" "$dest"
    echo "Saved $latest → $dest ($(wc -c < "$dest") bytes)"

# dump all payloads as text for diffing
proto-diff-prep: (_require classpath) check-java
    #!/usr/bin/env bash
    set -euo pipefail
    real_dir=/tmp/arena-diff/real
    mkdir -p "$real_dir"
    if [ ! -d "{{payloads}}" ]; then
        echo "No captures at {{payloads}}. Run: just serve-proxy" >&2; exit 1
    fi
    for f in {{payloads}}/S-C_*.bin; do
        [ -f "$f" ] || continue
        base=$(basename "$f" .bin)
        {{_nexus_cli}} forge.nexus.debug.InspectKt "$f" > "$real_dir/$base.txt" 2>/dev/null
        echo "  $base.txt"
    done
    echo "Real payloads dumped to $real_dir/"

# diff stub output vs real captures
proto-diff:
    #!/usr/bin/env bash
    set -euo pipefail
    real=/tmp/arena-diff/real
    stub=/tmp/arena-dump
    [ -d "$real" ] || { echo "No real dumps. Run: just proto-diff-prep" >&2; exit 1; }
    [ -d "$stub" ] || { echo "No stub dumps. Run stub with ARENA_DUMP=1" >&2; exit 1; }
    echo "=== Real ($real) ==="
    ls -1 "$real"/*.txt 2>/dev/null | while read f; do echo "  $(basename $f)"; done
    echo ""
    echo "=== Stub ($stub) ==="
    ls -1 "$stub"/*.txt 2>/dev/null | while read f; do echo "  $(basename $f)"; done
    echo ""
    echo "--- diff example ---"
    echo "  diff $real/<name>.txt $stub/<name>.txt"

# compare our output vs real Arena captures structurally (no port kill)
proto-compare *args: (_require classpath) check-java
    @{{_nexus_cli}} forge.nexus.conformance.CompareMainKt {{args}}

# trace an ID across all recorded payloads (no port kill — safe while server runs)
# dir: payloads dir (default: capture), or "engine" / "latest-engine" shortcuts
proto-trace id dir="": (_require classpath) check-java
    #!/usr/bin/env bash
    set -euo pipefail
    d="{{dir}}"
    if [[ -z "$d" ]]; then d="{{payloads}}"; fi
    if [[ "$d" == "engine" || "$d" == "latest-engine" ]]; then
        d="$(ls -dt "{{nexus_dir}}"/recordings/*/engine 2>/dev/null | head -1)"
        [[ -z "$d" ]] && { echo "No engine recording found"; exit 1; }
    fi
    {{_nexus_cli}} forge.nexus.debug.TraceKt "{{id}}" "$d"

# decode a recording directory to structured JSONL (no port kill)
proto-decode-recording dir output="": (_require classpath) check-java
    @{{_nexus_cli}} forge.nexus.conformance.RecordingDecoderMainKt "{{dir}}" {{output}}

# decode + accumulate state snapshots from a recording (no port kill)
proto-accumulate dir output="": (_require classpath) check-java
    @{{_nexus_cli}} forge.nexus.conformance.RecordingDecoderMainKt "{{dir}}" --accumulate {{output}}

# --- Recording CLI ---

# list discovered recording sessions (engine/proxy)
rec-list: (_require classpath) check-java
    @{{_nexus_cli}} forge.nexus.debug.RecordingCliKt list

# compact summary for one recording session directory (or session id from rec-list)
rec-summary session *args: (_require classpath) check-java
    @{{_nexus_cli}} forge.nexus.debug.RecordingCliKt summary "{{session}}"

# action timeline query (all actions)
rec-actions session limit="500": (_require classpath) check-java
    @{{_nexus_cli}} forge.nexus.debug.RecordingCliKt actions "{{session}}" --limit "{{limit}}"

# action timeline query (filtered; positional: session card [actor] [limit])
rec-actions-filtered session card actor="" limit="500": (_require classpath) check-java
    @{{_nexus_cli}} forge.nexus.debug.RecordingCliKt actions "{{session}}" --card "{{card}}" --actor "{{actor}}" --limit "{{limit}}"

# who played a specific card (name or grp id)
rec-who-played session card: (_require classpath) check-java
    @{{_nexus_cli}} forge.nexus.debug.RecordingCliKt who-played "{{session}}" --card "{{card}}"

# compare two recordings by compact action stream
rec-compare left right: (_require classpath) check-java
    @{{_nexus_cli}} forge.nexus.debug.RecordingCliKt compare "{{left}}" "{{right}}"

# --- Recording Analysis ---

# run SessionAnalyzer on a session (if analysis.json missing or --force)
rec-analyze session *args: (_require classpath) check-java
    @{{_nexus_cli}} forge.nexus.analysis.AnalysisCliKt analyze "{{session}}" {{args}}

# run analysis on all sessions missing analysis.json
rec-analyze-all: (_require classpath) check-java
    @{{_nexus_cli}} forge.nexus.analysis.AnalysisCliKt analyze-all

# show invariant violations (latest session if omitted)
rec-violations session="": (_require classpath) check-java
    @{{_nexus_cli}} forge.nexus.analysis.AnalysisCliKt violations {{session}}

# show cross-session mechanic coverage report
rec-mechanics: (_require classpath) check-java
    @{{_nexus_cli}} forge.nexus.analysis.AnalysisCliKt mechanics

# show summary + analysis of most recent session
rec-latest: (_require classpath) check-java
    @{{_nexus_cli}} forge.nexus.analysis.AnalysisCliKt latest

# --- Private helpers ---

[private]
_require file:
    @test -f "{{file}}" || { echo "Missing {{file}}. Run: just build" >&2; exit 1; }

[private]
_check-upstream:
    @"{{root_dir}}/build/check-upstream.sh" "{{root_dir}}"

[private]
_clean-surefire:
    @rm -rf "{{nexus_dir}}/target/surefire-reports"

# Run mvn test with extra flags, emit summary, preserve exit code.
[private]
_mvn-test extra_flags:
    #!/usr/bin/env bash
    set +e
    cd "{{root_dir}}" && mvn -pl forge-nexus {{mvn_quiet}} {{extra_flags}} test; rc=$?
    python3 "{{root_dir}}/build/test-summary.py" "{{nexus_dir}}/target" 2>/dev/null \
        || echo "⚠ Could not parse test results"
    exit $rc

# --- Remote (Tailscale) ---

# auto-detect Tailscale IPv4
_ts_ip := `tailscale ip -4 2>/dev/null || echo ""`

# server-side: regenerate certs with Tailscale IP in SAN (run on Mac mini)
remote-certs:
    #!/usr/bin/env bash
    set -euo pipefail
    ts_ip="{{_ts_ip}}"
    if [ -z "$ts_ip" ]; then echo "Tailscale not running." >&2; exit 1; fi
    echo "==> Regenerating certs with Tailscale IP ($ts_ip) in SAN..."
    just -f "{{nexus_dir}}/justfile" gen-certs "" "" "$ts_ip"

# client-side: fetch CA + hostnames from server, install locally (run on laptop)
# server: Tailscale hostname or IP of the Mac mini running nexus
remote-install server:
    #!/usr/bin/env bash
    set -euo pipefail
    server="{{server}}"
    certs_dir="$HOME/.local/share/forge-nexus/certs"
    echo "==> Fetching mitmproxy CA from $server..."
    scp "$server:~/.mitmproxy/mitmproxy-ca-cert.pem" /tmp/nexus-ca.pem
    echo "==> Fetching cert hostnames from $server..."
    fd_host=$(ssh "$server" "openssl x509 -in $certs_dir/frontdoor.crt -noout -subject" 2>/dev/null | sed 's/.*CN *= *//')
    md_host=$(ssh "$server" "openssl x509 -in $certs_dir/matchdoor.crt -noout -subject" 2>/dev/null | sed 's/.*CN *= *//')
    server_ip=$(ssh "$server" "tailscale ip -4")
    if [ -z "$fd_host" ] || [ -z "$md_host" ]; then
        echo "Cannot read certs on $server. Run 'just remote-certs' there first." >&2; exit 1
    fi
    # trust CA
    echo "==> Trusting CA in Keychain (will prompt for sudo)..."
    sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain /tmp/nexus-ca.pem
    echo "  ✓ CA trusted"
    # update /etc/hosts
    marker="# forge-nexus remote"
    hosts_block="$marker ($server)\n$server_ip  $fd_host\n$server_ip  $md_host"
    if grep -q "$marker" /etc/hosts; then
        echo "==> Updating existing forge-nexus entries in /etc/hosts..."
        sudo sed -i '' "/$marker/,+2d" /etc/hosts
    fi
    echo "==> Adding to /etc/hosts..."
    echo -e "$hosts_block" | sudo tee -a /etc/hosts >/dev/null
    echo "  ✓ /etc/hosts updated"
    echo ""
    echo "Done! Launch Arena — it will connect to nexus at $server_ip"
    echo "Debug panel: http://$server_ip:8090"

# show what remote-install would do (dry run, no sudo)
remote-status server:
    #!/usr/bin/env bash
    set -euo pipefail
    server="{{server}}"
    certs_dir="$HOME/.local/share/forge-nexus/certs"
    fd_host=$(ssh "$server" "openssl x509 -in $certs_dir/frontdoor.crt -noout -subject" 2>/dev/null | sed 's/.*CN *= *//')
    md_host=$(ssh "$server" "openssl x509 -in $certs_dir/matchdoor.crt -noout -subject" 2>/dev/null | sed 's/.*CN *= *//')
    server_ip=$(ssh "$server" "tailscale ip -4")
    echo "Server:     $server ($server_ip)"
    echo "FD host:    $fd_host"
    echo "MD host:    $md_host"
    echo ""
    echo "/etc/hosts entries:"
    grep "forge-nexus" /etc/hosts 2>/dev/null || echo "  (none)"
    echo ""
    echo "CA trusted:"
    security find-certificate -c mitmproxy /Library/Keychains/System.keychain >/dev/null 2>&1 \
        && echo "  ✓ mitmproxy CA found in System keychain" \
        || echo "  ✗ mitmproxy CA not in System keychain"

# --- Internal helpers ---

# Refresh classpath file if pom changed (used by build + dev-build).
[private]
_refresh-classpath:
    #!/usr/bin/env bash
    if [ ! -f "{{classpath}}" ] || [ "{{nexus_dir}}/pom.xml" -nt "{{classpath}}" ] || [ "{{root_dir}}/pom.xml" -nt "{{classpath}}" ]; then
        cd "{{root_dir}}" && mvn -pl forge-nexus \
            {{mvn_skip}} \
            -DincludeScope=runtime dependency:build-classpath \
            -Dmdep.outputFile="{{classpath}}"
    fi
