set shell := ["bash", "-euo", "pipefail", "-c"]

import 'buildscripts/java.just'
import 'just/lookup.just'
import 'just/proto.just'
import 'just/recording.just'
import 'just/client.just'
import 'just/certs.just'

project_dir  := justfile_directory()
classpath    := project_dir / "target/classpath.txt"
logback      := project_dir / "src/main/resources/logback.xml"
logback_cli  := project_dir / "src/main/resources/logback-cli.xml"
templates    := project_dir / "src/main/resources/arena-templates"
certs        := env("LEYLINE_CERTS", env("HOME", "/tmp") / ".local/share/leyline/certs")
fd_ip        := env("LEYLINE_FD_IP", "35.160.172.88")
md_ip        := env("LEYLINE_MD_IP", "44.245.90.131")
payloads     := env("LEYLINE_PAYLOADS", project_dir / "recordings/latest/capture/payloads")
ports        := "30010 30003 8090"

# --- JVM flags (shared base + per-mode overrides) ---

_jvm_base    := "-Dio.netty.tryReflectionSetAccessible=true --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED"
jvm_opts     := _jvm_base + " -Dlogback.configurationFile=" + logback
jvm_opts_cli := _jvm_base + " -Dlogback.configurationFile=" + logback_cli + " -Dlogback.statusListenerClass=ch.qos.logback.core.status.NopStatusListener"

# --- Java launch helpers ---

# Full classpath expression (shared by _java and _cli launch helpers)
_cp := '"$classpath:' + project_dir + '/build/classes/kotlin/main:' + project_dir + '/build/classes/java/main"'

# Kill ports + launch (for server targets)
_java := 'for p in ' + ports + '; do for pid in $(lsof -ti :$p 2>/dev/null); do echo "Killing pid $pid on port $p"; kill -9 $pid 2>/dev/null || true; done; done; sleep 0.3; classpath="$(< "' + classpath + '")"; "$JAVA_HOME/bin/java" ' + jvm_opts + ' -cp ' + _cp
# Read-only CLI (no port kill)
_cli  := 'classpath="$(< "' + classpath + '")"; "$JAVA_HOME/bin/java" ' + jvm_opts_cli + ' -cp ' + _cp

# --- Cert flags (shared by all serve-* targets) ---
# Only passed when all four files exist; otherwise server uses self-signed.

_fd_cert  := certs / "frontdoor-combined.pem"
_fd_key   := certs / "frontdoor.key"
_md_cert  := certs / "matchdoor-combined.pem"
_md_key   := certs / "matchdoor.key"
_was_cert := certs / "was-combined.pem"
_was_key  := certs / "was.key"
_cert_check := '[ -f "' + _fd_cert + '" ] && [ -f "' + _fd_key + '" ] && [ -f "' + _md_cert + '" ] && [ -f "' + _md_key + '" ]'
_was_cert_flags := 'if [ -f "' + _was_cert + '" ] && [ -f "' + _was_key + '" ]; then echo "--was-cert ' + _was_cert + ' --was-key ' + _was_key + '"; fi'
_cert_flags := '--fd-cert "' + _fd_cert + '" --fd-key "' + _fd_key + '" --md-cert "' + _md_cert + '" --md-key "' + _md_key + '"'

# --- Build ---

# install forge engine jars from submodule (run after git submodule update)
install-forge:
    cd "{{project_dir}}/forge" && mvn org.codehaus.mojo:flatten-maven-plugin:1.6.0:flatten install -pl forge-core,forge-game,forge-ai,forge-gui -am -DskipTests -q
    cd "{{project_dir}}/forge" && git log -1 --format=%H -- forge-core/src forge-game/src forge-ai/src forge-gui/src pom.xml > "{{project_dir}}/.upstream-installed"
    @echo "Forge engine installed to forge/.m2-local/"

# generate messages.proto from upstream submodule + rename map
sync-proto:
    cd "{{project_dir}}" && ./gradlew syncProto -q

# auto-format Kotlin sources (spotless/ktlint)
fmt:
    cd "{{project_dir}}" && ./gradlew spotlessApply -q
    @echo "fmt done."

# check formatting without modifying (CI)
fmt-check:
    cd "{{project_dir}}" && ./gradlew spotlessCheck -q

# static analysis (detekt)
lint:
    cd "{{project_dir}}" && ./gradlew detekt

# report outdated dependencies
deps-outdated:
    cd "{{project_dir}}" && ./gradlew dependencyUpdates -q

# build performance profile (opens HTML report)
build-profile:
    cd "{{project_dir}}" && ./gradlew classes --profile && open build/reports/profile/*.html

# compile proto + Kotlin (includes sync-proto + upstream check)
build:
    #!/usr/bin/env bash
    set -euo pipefail
    cd "{{project_dir}}" && ./gradlew classes
    echo "Build complete. Classpath: {{classpath}}"

# fast Kotlin-only compile
dev-build:
    cd "{{project_dir}}" && ./gradlew compileKotlin -q && echo "dev-build OK"

# --- Test ---

# run all tests
test:
    cd "{{project_dir}}" && ./gradlew test

# single test class (e.g. `just test-one ShouldStopEvaluatorTest`)
test-one class:
    cd "{{project_dir}}" && ./gradlew test -Pkotest.filter.specs=".*{{class}}"

# unit tests only (no engine bootstrap, fastest)
test-unit:
    cd "{{project_dir}}" && ./gradlew testUnit

# all conformance tests (~5s, wire-shape checks against Arena patterns)
test-conformance:
    cd "{{project_dir}}" && ./gradlew testConformance

# integration tests (parallel forks — boots engine per fork, runs classes concurrently)
test-integration:
    cd "{{project_dir}}" && ./gradlew testIntegration

# pre-commit gate: unit + conformance (fast, single fork)
test-gate:
    cd "{{project_dir}}" && ./gradlew testGate

# full gate: unit + conformance, then integration in parallel forks
test-full: test-gate test-integration

# JaCoCo coverage report (unit+conformance only)
coverage:
    #!/usr/bin/env bash
    set +e
    cd "{{project_dir}}" && ./gradlew testGate jacocoTestReport; rc=$?
    xml="{{project_dir}}/build/reports/jacoco/test/jacocoTestReport.xml"
    if [ -f "$xml" ]; then
        echo ""
        python3 "{{project_dir}}/buildscripts/coverage-summary.py" "$xml"
        echo ""
        echo "HTML: build/reports/jacoco/test/html/index.html"
    else
        echo "⚠ No coverage report (tests may have failed before report phase)"
    fi
    exit $rc

# --- Dev ---

# continuous compile — watches *.kt, recompiles on change (no server restart)
dev-watch:
    cd "{{project_dir}}" && ./gradlew -t compileKotlin

# compile + serve + auto-restart on *.kt change
dev: check-java
    #!/usr/bin/env bash
    set -euo pipefail
    echo "Dev: compile → serve → watch *.kt → restart. Ctrl-C to stop."
    trap 'kill $(jobs -p) 2>/dev/null; exit 0' INT TERM
    while true; do
        ./gradlew compileKotlin -q
        just serve &
        fswatch -1 -r -e '.*' -i '\.kt$' "{{project_dir}}/src/main/kotlin"
        echo "--- change detected, rebuilding ---"
        kill $(jobs -p) 2>/dev/null; wait 2>/dev/null || true
    done

# headless smoke test (validates auth → mulligan flow without MTGA)
smoke: (_require classpath) check-java
    @{{_java}} leyline.debug.SmokeTestKt

# --- Client Setup ---

# MTGA.app location (standard Epic Games install on macOS)
_mtga_path := "/Users/Shared/Epic Games/MagicTheGathering/MTGA.app"
_streaming := _mtga_path / "Contents/Resources/Data/StreamingAssets"
_audio_dir := _streaming / "Audio/GeneratedSoundBanks/Mac"

# one-time local dev setup: generate certs, patch Arena client for localhost
dev-setup:
    #!/usr/bin/env bash
    set -euo pipefail
    # 1. Generate TLS certs if missing
    if [ ! -f "{{_fd_cert}}" ]; then
        echo "==> Generating TLS certs..."
        just gen-certs
    else
        echo "==> Certs exist: {{certs}}"
    fi
    # 2. Copy localhost services.conf into Arena
    streaming="{{_streaming}}"
    if [ ! -d "$streaming" ]; then
        echo "Error: MTGA not found at {{_mtga_path}}"
        echo "Install Arena via Epic Games first."
        exit 1
    fi
    cp "{{project_dir}}/src/main/resources/services.conf" "$streaming/services.conf"
    echo "==> Copied localhost services.conf"
    # 3. Ensure NPE_VO.bnk exists
    audio="{{_audio_dir}}"
    if [ ! -f "$audio/NPE_VO.bnk" ]; then
        mkdir -p "$audio"
        echo "QktIRDAAAACWAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" \
            | base64 -d > "$audio/NPE_VO.bnk"
        echo "==> Created NPE_VO.bnk stub"
    fi
    # 4. macOS defaults — skip cert check + hash verification
    defaults write com.Wizards.MtGA CheckSC -integer 0
    defaults write com.Wizards.MtGA HashFilesOnStartup -integer 0
    echo "==> macOS defaults set (CheckSC=0, HashFilesOnStartup=0)"
    echo ""
    echo "Dev setup complete. Run: just serve"

# undo dev-setup: remove services.conf, clear macOS defaults
dev-teardown:
    #!/usr/bin/env bash
    set -euo pipefail
    streaming="{{_streaming}}"
    if [ -f "$streaming/services.conf" ]; then
        rm "$streaming/services.conf"
        echo "==> Removed services.conf"
    fi
    defaults delete com.Wizards.MtGA CheckSC 2>/dev/null || true
    defaults delete com.Wizards.MtGA HashFilesOnStartup 2>/dev/null || true
    echo "==> macOS defaults cleared"
    echo "Dev teardown complete. Arena restored to stock."

# --- Serve ---

# hybrid mode: proxy FD to real Arena, stub MD (default dev mode)
serve: (_require classpath) check-java
    #!/usr/bin/env bash
    set -euo pipefail
    cert_flags=""; if {{_cert_check}}; then cert_flags="{{_cert_flags}}"; fi
    was_flags=$({{_was_cert_flags}})
    {{_java}} leyline.LeylineMainKt $cert_flags $was_flags --proxy-fd {{fd_ip}}

# stub mode (fake both doors, fully offline)
serve-stub: (_require classpath) check-java
    #!/usr/bin/env bash
    set -euo pipefail
    cert_flags=""; if {{_cert_check}}; then cert_flags="{{_cert_flags}}"; fi
    was_flags=$({{_was_cert_flags}})
    {{_java}} leyline.LeylineMainKt $cert_flags $was_flags

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
    {{_java}} leyline.LeylineMainKt $cert_flags --fd-golden "$golden"

# proxy mode (both doors, capture traffic)
serve-proxy: (_require classpath) check-java
    #!/usr/bin/env bash
    set -euo pipefail
    cert_flags=""; if {{_cert_check}}; then cert_flags="{{_cert_flags}}"; fi
    {{_java}} leyline.LeylineMainKt $cert_flags --proxy-fd {{fd_ip}} --proxy-md {{md_ip}}

# replay mode (proxy FD, replay recorded bytes on MD)
serve-replay: (_require classpath) check-java
    #!/usr/bin/env bash
    set -euo pipefail
    cert_flags=""; if {{_cert_check}}; then cert_flags="{{_cert_flags}}"; fi
    {{_java}} leyline.LeylineMainKt $cert_flags --proxy-fd {{fd_ip}} --replay {{payloads}}

# puzzle mode: serve with a specific .pzl file
serve-puzzle filename: (_require classpath) check-java
    #!/usr/bin/env bash
    set -euo pipefail
    cert_flags=""; if {{_cert_check}}; then cert_flags="{{_cert_flags}}"; fi
    {{_java}} leyline.LeylineMainKt $cert_flags --proxy-fd {{fd_ip}} --puzzle "{{filename}}"

# smoke test: start stub, launch MTGA, check for FD errors via debug API
smoke-client timeout="60": (_require classpath) check-java
    #!/usr/bin/env bash
    set -euo pipefail
    cert_flags=""; if {{_cert_check}}; then cert_flags="{{_cert_flags}}"; fi
    echo "Starting stub server..."
    {{_java}} leyline.LeylineMainKt $cert_flags &
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

# --- Docker ---

_registry := "ghcr.io/delebedev/leyline"

# build + push Docker image with registry cache (fast rebuilds after first build)
docker-build tag=(_registry + ":latest"):
    docker buildx build \
        -f deploy/Dockerfile \
        --cache-from type=registry,ref={{_registry}}:buildcache \
        --cache-to type=registry,ref={{_registry}}:buildcache,mode=max \
        -t "{{tag}}" \
        --push .

# deploy: build + push, then pull + restart on VPS
deploy:
    just docker-build
    ssh {{env("LEYLINE_VPS", "vps")}} "cd /opt/leyline && docker compose pull && docker compose up -d"

# --- Private helpers ---

[private]
_require file:
    @test -f "{{file}}" || { echo "Missing {{file}}. Run: just build" >&2; exit 1; }
