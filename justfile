set shell := ["bash", "-euo", "pipefail", "-c"]

import '../build/java.just'

root_dir     := justfile_directory() / ".."
nexus_dir    := justfile_directory()
web_dir      := root_dir / "forge-web"
classpath    := nexus_dir / "target/classpath.txt"
logback      := nexus_dir / "src/main/resources/logback.xml"
templates    := nexus_dir / "src/main/resources/arena-templates"
certs        := env("NEXUS_CERTS", "/tmp/arena-capture")
fd_ip        := env("NEXUS_FD_IP", "54.190.138.101")
md_ip        := env("NEXUS_MD_IP", "54.218.223.216")
payloads     := env("NEXUS_PAYLOADS", "/tmp/arena-capture/payloads")
ports        := "30010 30003 8090"

jvm_opts := "-Dio.netty.tryReflectionSetAccessible=true --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED -Dlogback.configurationFile=" + logback

# auto-format Kotlin sources (spotless/ktlint)
fmt: check-java
    cd "{{root_dir}}" && mvn -pl forge-nexus com.diffplug.spotless:spotless-maven-plugin:apply -q
    @echo "fmt done."

# --- Build ---

flatten := "org.codehaus.mojo:flatten-maven-plugin:1.6.0:flatten"

# compile proto + Kotlin, install forge-web JAR to ~/.m2
build: check-java _check-upstream
    #!/usr/bin/env bash
    set -euo pipefail
    # clear cached ${revision} failures
    rm -rf "$HOME/.m2/repository/forge/forge/\${revision}" 2>/dev/null || true
    # install forge-web (flattened) so nexus can resolve it without -am
    cd "{{root_dir}}" && mvn -pl forge-web \
        {{flatten}} {{mvn_skip}} install -q
    cd "{{root_dir}}" && mvn -pl forge-nexus \
        {{mvn_skip}} compile
    if [ ! -f "{{classpath}}" ] || [ "{{nexus_dir}}/pom.xml" -nt "{{classpath}}" ] || [ "{{root_dir}}/pom.xml" -nt "{{classpath}}" ]; then
        cd "{{root_dir}}" && mvn -pl forge-nexus \
            {{mvn_skip}} \
            -DincludeScope=runtime dependency:build-classpath \
            -Dmdep.outputFile="{{classpath}}"
    fi
    echo "Build complete. Classpath: {{classpath}}"

# fast Kotlin-only compile (~3-5s, skip forge-web install)
dev-build: check-java
    #!/usr/bin/env bash
    set -euo pipefail
    cd "{{root_dir}}" && mvn -pl forge-nexus {{mvn_skip}} compile -q
    if [ ! -f "{{classpath}}" ] || [ "{{nexus_dir}}/pom.xml" -nt "{{classpath}}" ] || [ "{{root_dir}}/pom.xml" -nt "{{classpath}}" ]; then
        cd "{{root_dir}}" && mvn -pl forge-nexus \
            {{mvn_skip}} \
            -DincludeScope=runtime dependency:build-classpath \
            -Dmdep.outputFile="{{classpath}}"
    fi

# run tests (assumes `just build` has been run)
test: check-java _check-upstream _clean-surefire
    cd "{{root_dir}}" && mvn -pl forge-nexus \
        {{mvn_quiet}} test

# single test class (e.g. `just test-one StructuralFingerprintTest`)
test-one class: check-java _check-upstream _clean-surefire
    cd "{{root_dir}}" && mvn -pl forge-nexus \
        {{mvn_quiet}} \
        -Dtest="{{class}}" -Dsurefire.failIfNoSpecifiedTests=false test

# all conformance tests (fast: ~5s, runs all *ConformanceTest classes)
test-conformance: check-java _check-upstream _clean-surefire
    cd "{{root_dir}}" && mvn -pl forge-nexus \
        {{mvn_quiet}} \
        -Dgroups=conformance test

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
    @{{_nexus_java}} forge.nexus.NexusMainKt \
        --fd-cert {{certs}}/frontdoor-combined.pem \
        --fd-key  {{certs}}/frontdoor.key \
        --md-cert {{certs}}/matchdoor-combined.pem \
        --md-key  {{certs}}/matchdoor.key \
        --proxy-fd {{fd_ip}}

# stub mode (fake both doors, fully offline)
serve-stub: (_require classpath) check-java
    @{{_nexus_java}} forge.nexus.NexusMainKt \
        --fd-cert {{certs}}/frontdoor-combined.pem \
        --fd-key  {{certs}}/frontdoor.key \
        --md-cert {{certs}}/matchdoor-combined.pem \
        --md-key  {{certs}}/matchdoor.key

# proxy mode (both doors, capture traffic)
serve-proxy: (_require classpath) check-java
    @{{_nexus_java}} forge.nexus.NexusMainKt \
        --fd-cert {{certs}}/frontdoor-combined.pem \
        --fd-key  {{certs}}/frontdoor.key \
        --md-cert {{certs}}/matchdoor-combined.pem \
        --md-key  {{certs}}/matchdoor.key \
        --proxy-fd {{fd_ip}} \
        --proxy-md {{md_ip}}

# replay mode (proxy FD, replay recorded bytes on MD)
serve-replay: (_require classpath) check-java
    @{{_nexus_java}} forge.nexus.NexusMainKt \
        --fd-cert {{certs}}/frontdoor-combined.pem \
        --fd-key  {{certs}}/frontdoor.key \
        --md-cert {{certs}}/matchdoor-combined.pem \
        --md-key  {{certs}}/matchdoor.key \
        --proxy-fd {{fd_ip}} \
        --replay {{payloads}}

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
proto-trace id: (_require classpath) check-java
    @{{_nexus_cli}} forge.nexus.debug.TraceKt "{{id}}" {{payloads}}

# --- Private helpers ---

[private]
_require file:
    @test -f "{{file}}" || { echo "Missing {{file}}. Run: just build" >&2; exit 1; }

[private]
_check-upstream:
    #!/usr/bin/env bash
    stamp="{{root_dir}}/.upstream-installed"
    upstream_hash=$(cd "{{root_dir}}" && git log -1 --format=%H -- forge-core/src forge-game/src forge-ai/src forge-gui/src pom.xml)
    if [ ! -f "$stamp" ]; then
        echo "Upstream JARs not installed. Run: just install-upstream (from repo root)" >&2; exit 1
    fi
    stamp_hash=$(cat "$stamp")
    if [ "$stamp_hash" != "$upstream_hash" ]; then
        echo "Upstream sources changed. Run: just install-upstream (from repo root)" >&2; exit 1
    fi

[private]
_clean-surefire:
    @rm -rf "{{nexus_dir}}/target/surefire-reports"

_nexus_java := 'for p in ' + ports + '; do for pid in $(lsof -ti :$p 2>/dev/null); do echo "Killing pid $pid on port $p"; kill $pid 2>/dev/null || true; done; done; classpath="$(< "' + classpath + '")"; "$JAVA_HOME/bin/java" ' + jvm_opts + ' -cp "$classpath:' + nexus_dir + '/target/classes:' + web_dir + '/target/classes"'
# Same as _nexus_java but without killing ports — for read-only CLI tools
_nexus_cli  := 'classpath="$(< "' + classpath + '")"; "$JAVA_HOME/bin/java" ' + jvm_opts + ' -cp "$classpath:' + nexus_dir + '/target/classes:' + web_dir + '/target/classes"'
