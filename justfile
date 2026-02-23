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
fd_ip        := env("NEXUS_FD_IP", "52.88.10.148")
md_ip        := env("NEXUS_MD_IP", "54.71.214.244")
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
_nexus_java := 'for p in ' + ports + '; do for pid in $(lsof -ti :$p 2>/dev/null); do echo "Killing pid $pid on port $p"; kill $pid 2>/dev/null || true; done; done; classpath="$(< "' + classpath + '")"; "$JAVA_HOME/bin/java" ' + jvm_opts + ' -cp ' + _cp
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
gen-certs fd_host="" md_host="":
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
            -extfile <(echo "subjectAltName=DNS:$host,DNS:localhost,IP:127.0.0.1") 2>/dev/null
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
