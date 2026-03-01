set shell := ["bash", "-euo", "pipefail", "-c"]

import '../build/java.just'
import 'just/lookup.just'
import 'just/proto.just'
import 'just/recording.just'
import 'just/client.just'
import 'just/certs.just'

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
    rm -rf "{{nexus_dir}}/target/classes"  # purge stale classes from package moves
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

# JaCoCo coverage report (unit+conformance only; see TODO.md for integration)
coverage: check-java _check-upstream _clean-surefire (_mvn-verify "-Dgroups=unit,conformance -Dmaven.test.failure.ignore=true")

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

# puzzle mode: serve with a specific .pzl file
serve-puzzle filename: (_require classpath) check-java
    #!/usr/bin/env bash
    set -euo pipefail
    cert_flags=""; if {{_cert_check}}; then cert_flags="{{_cert_flags}}"; fi
    {{_nexus_java}} forge.nexus.NexusMainKt $cert_flags --proxy-fd {{fd_ip}} --puzzle "{{filename}}"

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

# Run mvn verify (test + JaCoCo report), emit test summary + coverage summary.
[private]
_mvn-verify extra_flags:
    #!/usr/bin/env bash
    set +e
    cd "{{root_dir}}" && mvn -pl forge-nexus {{mvn_quiet}} {{extra_flags}} verify; rc=$?
    python3 "{{root_dir}}/build/test-summary.py" "{{nexus_dir}}/target" 2>/dev/null \
        || echo "⚠ Could not parse test results"
    xml="{{nexus_dir}}/target/site/jacoco/jacoco.xml"
    if [ -f "$xml" ]; then
        echo ""
        python3 "{{root_dir}}/build/coverage-summary.py" "$xml"
        echo ""
        echo "HTML: forge-nexus/target/site/jacoco/index.html"
    else
        echo "⚠ No coverage report (tests may have failed before verify phase)"
    fi
    exit $rc

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
