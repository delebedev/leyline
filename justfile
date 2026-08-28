set shell := ["bash", "-euo", "pipefail", "-c"]

import 'just/java.just'
import 'just/lookup.just'
import 'just/tools.just'
import 'just/test.just'
import 'just/docs-lint.just'

project_dir  := justfile_directory()
classpath    := project_dir / "target/classpath.txt"
logback      := project_dir / "app/main/resources/logback.xml"
logback_cli  := project_dir / "app/main/resources/logback-cli.xml"
templates    := project_dir / "app/main/resources/arena-templates"
certs        := env("LEYLINE_CERTS", env("HOME", "/tmp") / "Library/Application Support/dev.leyline/tls")

# --- JVM flags (shared base + per-mode overrides) ---

_jvm_base    := "-Xms384m -Xmx1g -Dio.netty.tryReflectionSetAccessible=true --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED"
jvm_opts     := _jvm_base + " -Dlogback.configurationFile=" + logback
jvm_opts_cli := _jvm_base + " -Dlogback.configurationFile=" + logback_cli + " -Dlogback.statusListenerClass=ch.qos.logback.core.status.NopStatusListener"

# --- Java launch helpers ---

# Full classpath expression (shared by _java and _cli launch helpers).
# Module class dirs prepended so fresh classes take precedence over stale jars.
# Fixes: `just dev-build` (compileKotlin only) + CLI tools seeing old jar bytecode.
_module_classes := project_dir + '/engine/build/classes/kotlin/main:' + project_dir + '/engine/build/classes/java/main:' + project_dir + '/engine/build/resources/main:' + project_dir + '/domain/build/classes/kotlin/main:' + project_dir + '/domain/build/resources/main:' + project_dir + '/native/build/classes/kotlin/main:' + project_dir + '/native/build/resources/main:' + project_dir + '/web/build/classes/kotlin/main:' + project_dir + '/web/build/resources/main:' + project_dir + '/build/classes/kotlin/main:' + project_dir + '/build/classes/java/main:' + project_dir + '/build/resources/main'
_cp := '"' + _module_classes + ':$classpath:' + project_dir + '/build/classes/kotlin/main:' + project_dir + '/build/classes/java/main:' + project_dir + '/build/resources/main"'

# Launch a server target on the resolved classpath. Ports come from leyline.toml
# and LEYLINE_* overrides only; nothing is injected and no processes are killed,
# so one worktree's server never terminates another's.
_java := 'classpath="$(< "' + classpath + '")"; "$JAVA_HOME/bin/java" ' + jvm_opts + ' -cp ' + _cp
# Read-only CLI (no server launch)
_cli  := 'classpath="$(< "' + classpath + '")"; "$JAVA_HOME/bin/java" ' + jvm_opts_cli + ' -cp ' + _cp

# --- TLS certs (provide custom cert/key or place them in the default local path) ---
_cert     := certs / "server-chain.pem"
_key      := certs / "server.key"
_cert_flags := 'cert_flags=(); if [ -f "' + _cert + '" ] && [ -f "' + _key + '" ]; then cert_flags=(--cert "' + _cert + '" --key "' + _key + '"); fi'
_forge_m2_setup := 'eval "$(' + project_dir + '/gradle/scripts/forge-m2.sh ' + project_dir + ')"'

# --- Build ---

# install forge engine jars from submodule (run after git submodule update)
[group('build')]
install-forge:
    #!/usr/bin/env bash
    set -euo pipefail
    cd "{{project_dir}}"
    {{_forge_m2_setup}}
    echo "Forge checkout $forge_cache_mode; using cache: $forge_m2"
    cd "{{project_dir}}/forge"
    install_command=(mvn org.codehaus.mojo:flatten-maven-plugin:1.6.0:flatten install -Dmaven.repo.local="$forge_m2" -pl forge-core,forge-game,forge-ai,forge-gui -am -DskipTests -q)
    if [ "$forge_cache_mode" = "shared" ]; then
        python3 "{{project_dir}}/gradle/scripts/forge-install.py" "$forge_m2" "${install_command[@]}"
    else
        "${install_command[@]}"
    fi
    printf '%s\n' "$current_forge" > "{{project_dir}}/.forge-commit-installed"
    echo "Forge engine installed to $forge_m2"

# generate messages.proto from upstream submodule + rename map
[group('build')]
sync-proto:
    cd "{{project_dir}}" && ./gradlew syncProto -q

# auto-format Kotlin sources (ktlint-gradle, reads .editorconfig)
[group('build')]
fmt:
    cd "{{project_dir}}" && ./gradlew ktlintFormat -q
    @echo "fmt done."

# check formatting without modifying (CI)
[group('build')]
fmt-check:
    cd "{{project_dir}}" && ./gradlew ktlintCheck -q

# static analysis (detekt with type resolution — main + test source sets)
[group('build')]
lint:
    cd "{{project_dir}}" && ./gradlew detektMain detektTest

# report outdated dependencies
[group('build')]
deps-outdated:
    # The versions plugin requires isolated execution on Gradle 9.
    cd "{{project_dir}}" && ./gradlew dependencyUpdates -q --no-parallel --no-configuration-cache

# build performance profile (opens HTML report)
[group('build')]
build-profile:
    cd "{{project_dir}}" && ./gradlew classes --profile && open build/reports/profile/*.html

# compile proto + Kotlin (includes sync-proto + upstream check)
[group('build')]
build:
    #!/usr/bin/env bash
    set -euo pipefail
    cd "{{project_dir}}"
    {{_forge_m2_setup}}
    # Runtime classpath entries are subproject jars, so refresh each one before launch.
    ./gradlew classes jar :domain:jar :engine:jar :native:jar :web:jar
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


# --- Bootstrap ---

# install repo-tracked git hooks for this clone
[group('setup')]
hooks-install:
    #!/usr/bin/env bash
    set -euo pipefail
    cd "{{project_dir}}"
    git config core.hooksPath .githooks
    chmod +x .githooks/pre-push
    echo "Git hooks installed."

# one-command setup: submodules → forge install → build → hooks
[group('setup')]
bootstrap:
    #!/usr/bin/env bash
    set -euo pipefail
    cd "{{project_dir}}"

    echo "==> Checking prerequisites..."
    # Git submodules
    if [ ! -f forge/pom.xml ]; then
        echo "    Initializing git submodules..."
        forge_reference="$(
            git worktree list --porcelain |
            awk '/^worktree /{print substr($0,10)}' |
            while IFS= read -r wt; do
                [ "$wt" = "$PWD" ] && continue
                candidate="$wt/forge"
                if [ -f "$candidate/pom.xml" ]; then
                    # Reference clones need a complete object store; shallow
                    # submodules cannot seed another worktree reliably.
                    if git -C "$candidate" rev-parse --is-shallow-repository | grep -qx true; then
                        continue
                    fi
                    printf '%s\n' "$candidate"
                    break
                fi
            done
        )"
        if [ -n "${forge_reference:-}" ]; then
            echo "    Using local forge reference: $forge_reference"
            git submodule update --init --recursive --reference "$forge_reference" forge
            git submodule update --init --recursive proto/upstream
        else
            echo "    No non-shallow local forge reference found; trying shallow forge clone"
            if git submodule update --init --depth 1 forge; then
                :
            else
                echo "    Shallow forge clone failed; retrying full clone"
                git submodule deinit -f forge || true
                git submodule update --init forge
            fi
            git submodule update --init --recursive proto/upstream
        fi
    else
        echo "    Submodules OK"
    fi

    # Maven (needed for install-forge)
    if ! command -v mvn &>/dev/null; then
        echo "Error: maven not found. Install: brew install maven" >&2
        exit 1
    fi

    # The stamp binds locally installed Forge jars to the checked-out submodule.
    # Shared caches are commit-addressed; dirty Forge checkouts use an isolated repo.
    {{_forge_m2_setup}}
    installed_forge=""
    if [ -f .forge-commit-installed ]; then
        installed_forge=$(cat .forge-commit-installed)
    fi
    if [ "$forge_cache_mode" = "shared" ] && [ -d "$forge_m2/forge" ]; then
        printf '%s\n' "$current_forge" > .forge-commit-installed
        echo "==> Forge already installed ($(echo "$current_forge" | head -c 8)) [shared cache]"
    elif [ "$current_forge" = "$installed_forge" ] && [ -d "$forge_m2/forge" ]; then
        echo "==> Forge already installed ($(echo "$current_forge" | head -c 8)) [local cache]"
    else
        echo "==> Installing forge engine..."
        just install-forge
    fi

    # Build (proto sync + compile + jars + classpath)
    echo "==> Building..."
    just build

    echo "==> Installing git hooks..."
    just hooks-install

    echo ""
    echo "Bootstrap complete. You can now:"
    echo "  just test-gate     # run tests"
    echo "  just serve         # start server"
    echo "  just dev           # compile + serve + watch"

# --- Docs ---

# list docs with summary from YAML frontmatter (optional grep filter)
[group('docs')]
docs filter="":
    #!/usr/bin/env bash
    set -euo pipefail
    find docs -name '*.md' -o -name '*.yaml' | sort | while read -r f; do
      summary=$(awk '/^---$/{n++; next} n==1 && /^summary:/{sub(/^summary: *"?/,""); sub(/"$/,""); print; exit}' "$f")
      [ -z "$summary" ] && continue
      printf "%-50s %s\n" "$f" "$summary"
    done | { [ -n "{{filter}}" ] && grep -i "{{filter}}" || cat; }

# --- Data ---

# one-time: seed player.db from local starter data.
# Requires the client card database (LEYLINE_CARD_DB override or
# standard-location autodiscovery) to resolve deck card names.
[group('setup')]
seed-db: (_require classpath) check-java
    @{{_cli}} leyline.cli.SeedDb

# --- Sim-client (synthetic GRE log generation) ---

# Run the standalone simclient runner with CLI passthrough and ingest results
# into ~/.scry/games/.
#
# Card data comes from the client database (LEYLINE_CARD_DB override or
# standard-location autodiscovery); every deck and puzzle row requires it.
# Deck names resolve as data/decks/<name>.txt basenames. Default matrix:
# forest-only,bears,mono-g-curve,mono-r-burn.
#
# Examples:
#   just simclient                                    # default matrix + ingest
#   just simclient --decks mono-r-burn --seeds 1..20
#   just simclient --puzzles bolt-face.pzl --seeds 7,13,42
#   just simclient --decks 'Deck A,Deck B' --seeds 1..5 --resume
#
# Output: engine/build/simclient/*.log + .meta.json (source: simclient).
# Logs are copied into ~/.scry/games/ so scry-ts (with --source simclient) can
# read them alongside other saved games.
[group('simclient')]
simclient *args="--ingest-scry":
    #!/usr/bin/env bash
    set -euo pipefail
    cd "{{project_dir}}"
    src="engine/build/simclient"
    # Clear prior outputs so ingest only picks up the current run.
    if [ -d "$src" ]; then trash "$src"; fi
    ./gradlew :engine:simclient --args="{{args}}"

# --- Serve ---

# default dev mode: local FD + local MD. Ports and paths come from leyline.toml
# and LEYLINE_* overrides (for example LEYLINE_NATIVE_FD_PORT); no flags are
# injected, so TOML participates in the documented precedence.
[group('serve')]
serve: build check-java
    #!/usr/bin/env bash
    set -euo pipefail
    {{_cert_flags}}
    if [ ${#cert_flags[@]} -gt 0 ]; then
      {{_java}} leyline.LeylineMainKt "${cert_flags[@]}"
    else
      {{_java}} leyline.LeylineMainKt
    fi

# verify web profile excludes local client door/debug posture
[group('serve')]
web-profile-check:
    cd "{{project_dir}}" && ./gradlew verifyWebProfilePosture


# --- Packaging ---

# build self-contained archive (jlink JRE + JARs + card resources, no system Java needed)
[group('deploy')]
bundle:
    ./gradlew bundleArchive --no-daemon
    chmod -R u+rw build/bundle/ 2>/dev/null || true
    @ls build/dist/leyline-*.tgz build/dist/leyline-*.zip 2>/dev/null | head -1 | xargs -I{} echo "Archive: {}"

# --- Private helpers ---

[private]
_require file:
    @test -f "{{file}}" || { echo "Missing {{file}}. Run: just build" >&2; exit 1; }
