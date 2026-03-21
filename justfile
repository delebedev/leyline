set shell := ["bash", "-euo", "pipefail", "-c"]

import 'just/java.just'
import 'just/lookup.just'
import 'just/proto.just'
import 'just/client.just'
import 'just/tools.just'
import 'just/test.just'

project_dir  := justfile_directory()
classpath    := project_dir / "target/classpath.txt"
logback      := project_dir / "app/main/resources/logback.xml"
logback_cli  := project_dir / "app/main/resources/logback-cli.xml"
templates    := project_dir / "app/main/resources/arena-templates"
certs        := env("LEYLINE_CERTS", env("HOME", "/tmp") / ".local/share/leyline/certs")
fd_ip        := env("LEYLINE_FD_IP", "52.43.17.6")
md_ip        := env("LEYLINE_MD_IP", "54.202.201.20")
payloads     := env("LEYLINE_PAYLOADS", project_dir / "recordings/latest/capture/payloads")
ports        := "30010 30003 8090 8091"

# --- JVM flags (shared base + per-mode overrides) ---

_jvm_base    := "-Dio.netty.tryReflectionSetAccessible=true --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED"
jvm_opts     := _jvm_base + " -Dlogback.configurationFile=" + logback
jvm_opts_cli := _jvm_base + " -Dlogback.configurationFile=" + logback_cli + " -Dlogback.statusListenerClass=ch.qos.logback.core.status.NopStatusListener"

# --- Java launch helpers ---

# Full classpath expression (shared by _java and _cli launch helpers).
# Module class dirs prepended so fresh classes take precedence over stale jars.
# Fixes: `just dev-build` (compileKotlin only) + CLI tools seeing old jar bytecode.
_module_classes := project_dir + '/matchdoor/build/classes/kotlin/main:' + project_dir + '/matchdoor/build/classes/java/main:' + project_dir + '/matchdoor/build/resources/main:' + project_dir + '/tooling/build/classes/kotlin/main:' + project_dir + '/tooling/build/classes/java/main:' + project_dir + '/tooling/build/resources/main:' + project_dir + '/frontdoor/build/classes/kotlin/main:' + project_dir + '/frontdoor/build/resources/main:' + project_dir + '/account/build/classes/kotlin/main:' + project_dir + '/account/build/resources/main:' + project_dir + '/app/build/classes/kotlin/main:' + project_dir + '/app/build/resources/main'
_cp := '"' + _module_classes + ':$classpath:' + project_dir + '/build/classes/kotlin/main:' + project_dir + '/build/classes/java/main:' + project_dir + '/build/resources/main"'

# Kill ports + launch (for server targets)
_java := 'for p in ' + ports + '; do for pid in $(lsof -ti :$p 2>/dev/null); do echo "Killing pid $pid on port $p"; kill -9 $pid 2>/dev/null || true; done; done; sleep 0.3; classpath="$(< "' + classpath + '")"; "$JAVA_HOME/bin/java" ' + jvm_opts + ' -cp ' + _cp
# Read-only CLI (no port kill)
_cli  := 'classpath="$(< "' + classpath + '")"; "$JAVA_HOME/bin/java" ' + jvm_opts_cli + ' -cp ' + _cp

# --- TLS certs (optional — self-signed if missing) ---
# UnityTls validates ALL certs (FD, MD, WAS). Needs mitmproxy CA certs.
# CheckSC=0 does NOT bypass Unity's Mono TLS stack.
_cert     := certs / "frontdoor-combined.pem"
_key      := certs / "frontdoor.key"
_account_cert := certs / "account-combined.pem"
_account_key  := certs / "account.key"
_cert_flags := 'cert_flags=""; if [ -f "' + _cert + '" ] && [ -f "' + _key + '" ]; then cert_flags="--cert ' + _cert + ' --key ' + _key + '"; fi; account_flags=""; if [ -f "' + _account_cert + '" ] && [ -f "' + _account_key + '" ]; then account_flags="--account-cert ' + _account_cert + ' --account-key ' + _account_key + '"; fi; cert_flags="$cert_flags $account_flags"'

# --- Build ---

# install forge engine jars from submodule (run after git submodule update)
[group('build')]
install-forge:
    cd "{{project_dir}}/forge" && mvn org.codehaus.mojo:flatten-maven-plugin:1.6.0:flatten install -pl forge-core,forge-game,forge-ai,forge-gui -am -DskipTests -q
    cd "{{project_dir}}/forge" && git log -1 --format=%H -- forge-core/src forge-game/src forge-ai/src forge-gui/src pom.xml > "{{project_dir}}/.upstream-installed"
    @echo "Forge engine installed to forge/.m2-local/"

# generate messages.proto from upstream submodule + rename map
[group('build')]
sync-proto:
    cd "{{project_dir}}" && ./gradlew syncProto -q

# auto-format Kotlin sources (spotless/ktlint)
[group('build')]
fmt:
    cd "{{project_dir}}" && ./gradlew spotlessApply -q
    @echo "fmt done."

# check formatting without modifying (CI)
[group('build')]
fmt-check:
    cd "{{project_dir}}" && ./gradlew spotlessCheck -q

# static analysis (detekt)
[group('build')]
lint:
    cd "{{project_dir}}" && ./gradlew detekt

# report outdated dependencies
[group('build')]
deps-outdated:
    cd "{{project_dir}}" && ./gradlew dependencyUpdates -q

# build performance profile (opens HTML report)
[group('build')]
build-profile:
    cd "{{project_dir}}" && ./gradlew classes --profile && open build/reports/profile/*.html

# compile proto + Kotlin (includes sync-proto + upstream check)
[group('build')]
build:
    #!/usr/bin/env bash
    set -euo pipefail
    cd "{{project_dir}}" && ./gradlew classes jar
    echo "Build complete. Classpath: {{classpath}}"

# fast Kotlin-only compile
[group('build')]
dev-build:
    cd "{{project_dir}}" && ./gradlew compileKotlin -q && echo "dev-build OK"


# --- Dev ---

# continuous compile — watches *.kt, recompiles on change (no server restart)
[group('dev')]
dev-watch:
    cd "{{project_dir}}" && ./gradlew -t compileKotlin

# compile + serve + auto-restart on *.kt change
[group('dev')]
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


# --- Client Setup ---

# MTGA.app location (standard Epic Games install on macOS)
_mtga_path := "/Users/Shared/Epic Games/MagicTheGathering/MTGA.app"
_streaming := _mtga_path / "Contents/Resources/Data/StreamingAssets"
_audio_dir := _streaming / "Audio/GeneratedSoundBanks/Mac"

# one-time local dev setup: gen certs, patch Arena client for localhost
[group('setup')]
dev-setup:
    #!/usr/bin/env bash
    set -euo pipefail
    echo "==> TLS certs auto-generated at server boot (mitmproxy CA required)"
    # 1. Copy localhost services.conf into Arena
    streaming="{{_streaming}}"
    if [ ! -d "$streaming" ]; then
        echo "Error: MTGA not found at {{_mtga_path}}"
        echo "Install Arena via Epic Games first."
        exit 1
    fi
    cp "{{project_dir}}/app/main/resources/services.conf" "$streaming/services.conf"
    echo "==> Copied localhost services.conf"
    # 2. Ensure NPE_VO.bnk exists
    audio="{{_audio_dir}}"
    if [ ! -f "$audio/NPE_VO.bnk" ]; then
        mkdir -p "$audio"
        echo "QktIRDAAAACWAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" \
            | base64 -d > "$audio/NPE_VO.bnk"
        echo "==> Created NPE_VO.bnk stub"
    fi
    # 3. macOS defaults — skip cert check + hash verification
    defaults write com.Wizards.MtGA CheckSC -integer 0
    defaults write com.Wizards.MtGA HashFilesOnStartup -integer 0
    echo "==> macOS defaults set (CheckSC=0, HashFilesOnStartup=0)"
    echo ""
    echo "Dev setup complete. Run: just serve"

# undo dev-setup: remove services.conf, clear macOS defaults
[group('setup')]
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

# --- Data ---

# one-time: seed player.db from golden captures + starter decks
[group('setup')]
seed-db: (_require classpath) check-java
    @{{_cli}} leyline.cli.SeedDb

# --- Serve ---

# default dev mode: local FD + local MD (fully offline, no real Arena needed)
[group('serve')]
serve: build check-java
    #!/usr/bin/env bash
    set -euo pipefail
    cp "{{project_dir}}/app/main/resources/services.conf" "{{_streaming}}/services.conf"
    {{_cert_flags}}
    {{_java}} leyline.LeylineMainKt $cert_flags

# replay-local mode: replay captured FD session (fd-frames.jsonl), local MD
[group('serve')]
serve-replay-stub golden="": build check-java
    #!/usr/bin/env bash
    set -euo pipefail
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
    {{_cert_flags}}
    {{_java}} leyline.LeylineMainKt $cert_flags --fd-golden "$golden"

# proxy mode (both doors, capture traffic for recording/analysis)
[group('serve')]
serve-proxy: build check-java
    #!/usr/bin/env bash
    set -euo pipefail
    cp "{{project_dir}}/deploy/services-proxy.conf" "{{_streaming}}/services.conf"
    {{_cert_flags}}
    {{_java}} leyline.LeylineMainKt $cert_flags --proxy-fd {{fd_ip}} --proxy-md {{md_ip}}

# replay mode (local FD, replay recorded bytes on MD)
[group('serve')]
serve-replay: build check-java
    #!/usr/bin/env bash
    set -euo pipefail
    {{_cert_flags}}
    {{_java}} leyline.LeylineMainKt $cert_flags --replay {{payloads}}

# puzzle mode: serve with a specific .pzl file
[group('serve')]
serve-puzzle filename: build check-java
    #!/usr/bin/env bash
    set -euo pipefail
    {{_cert_flags}}
    {{_java}} leyline.LeylineMainKt $cert_flags --puzzle "{{filename}}"


# --- Docker ---

# build Docker image for local use
[group('deploy')]
docker-build tag="leyline:latest":
    docker buildx build -f deploy/Dockerfile -t "{{tag}}" .

# --- Private helpers ---

[private]
_require file:
    @test -f "{{file}}" || { echo "Missing {{file}}. Run: just build" >&2; exit 1; }
