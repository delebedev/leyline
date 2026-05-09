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
ports        := "30010 30003 8090 8091"

# --- JVM flags (shared base + per-mode overrides) ---

_jvm_base    := "-Xms384m -Xmx1g -Dio.netty.tryReflectionSetAccessible=true --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED"
jvm_opts     := _jvm_base + " -Dlogback.configurationFile=" + logback
jvm_opts_cli := _jvm_base + " -Dlogback.configurationFile=" + logback_cli + " -Dlogback.statusListenerClass=ch.qos.logback.core.status.NopStatusListener"

# --- Java launch helpers ---

# Full classpath expression (shared by _java and _cli launch helpers).
# Module class dirs prepended so fresh classes take precedence over stale jars.
# Fixes: `just dev-build` (compileKotlin only) + CLI tools seeing old jar bytecode.
_module_classes := project_dir + '/matchdoor/build/classes/kotlin/main:' + project_dir + '/matchdoor/build/classes/java/main:' + project_dir + '/matchdoor/build/resources/main:' + project_dir + '/frontdoor/build/classes/kotlin/main:' + project_dir + '/frontdoor/build/resources/main:' + project_dir + '/account/build/classes/kotlin/main:' + project_dir + '/account/build/resources/main:' + project_dir + '/app/build/classes/kotlin/main:' + project_dir + '/app/build/resources/main'
_cp := '"' + _module_classes + ':$classpath:' + project_dir + '/build/classes/kotlin/main:' + project_dir + '/build/classes/java/main:' + project_dir + '/build/resources/main"'

# Kill ports + launch (for server targets)
_java := 'for p in ' + ports + '; do for pid in $(lsof -ti :$p 2>/dev/null); do echo "Killing pid $pid on port $p"; kill -9 $pid 2>/dev/null || true; done; done; sleep 0.3; classpath="$(< "' + classpath + '")"; "$JAVA_HOME/bin/java" ' + jvm_opts + ' -cp ' + _cp
# Read-only CLI (no port kill)
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
    cd "{{project_dir}}/forge" && mvn org.codehaus.mojo:flatten-maven-plugin:1.6.0:flatten install -Dmaven.repo.local="$forge_m2" -pl forge-core,forge-game,forge-ai,forge-gui -am -DskipTests -q
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
    cd "{{project_dir}}"
    {{_forge_m2_setup}}
    ./gradlew classes jar
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

# one-command setup: submodules → forge install → build → seed DB
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

    # Install forge (skip if already up to date)
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

    # Seed DB
    mkdir -p data
    echo "==> Seeding database..."
    just seed-db

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

# one-time: seed player.db from local starter data
[group('setup')]
seed-db: (_require classpath) check-java
    @{{_cli}} leyline.cli.SeedDb

# --- Sim-client (synthetic GRE log generation) ---

# Run simclient batch with optional matrix overrides + ingest results into ~/.scry/games/.
#
# Args:
#   decks  — comma-separated deck names; built-ins or `<name>` for data/decks/<name>.txt.
#            Default: forest-only,bears,mono-g-curve,mono-r-burn
#   seeds  — comma-separated longs OR `start..end` range (inclusive).
#            Default: 7,13,42,99,314
#
# Requires LEYLINE_CARD_DB. Point it at the local Arena
# Raw_CardDatabase_*.mtga / *.sqlite file used by the server.
# To add a deck, save Arena/export-style text as data/decks/<name>.txt, then
# invoke with that basename: `just simclient "My deck" 1..5`.
#
# Examples:
#   LEYLINE_CARD_DB="$HOME/Library/Application Support/com.wizards.mtga/Downloads/Raw/Raw_CardDatabase_x.mtga" just simclient
#   LEYLINE_CARD_DB="$HOME/Library/Application Support/com.wizards.mtga/Downloads/Raw/Raw_CardDatabase_x.mtga" just simclient "Simple test" 42
#   LEYLINE_CARD_DB="$HOME/Library/Application Support/com.wizards.mtga/Downloads/Raw/Raw_CardDatabase_x.mtga" just simclient mono-r-burn 1..20
#   LEYLINE_CARD_DB="$HOME/Library/Application Support/com.wizards.mtga/Downloads/Raw/Raw_CardDatabase_x.mtga" just simclient "Auras,Black aggro" "1,2,3"
#
# Output: matchdoor/build/simclient/*.log + .meta.json (source: simclient).
# Logs are copied into ~/.scry/games/ so scry-ts (with --source simclient) can
# read them alongside other saved games.
[group('simclient')]
simclient decks="" seeds="":
    #!/usr/bin/env bash
    set -euo pipefail
    cd "{{project_dir}}"
    if [ -n "{{decks}}" ]; then export SIMCLIENT_DECKS="{{decks}}"; fi
    if [ -n "{{seeds}}" ]; then export SIMCLIENT_SEEDS="{{seeds}}"; fi
    if [ -z "${LEYLINE_CARD_DB:-}" ]; then
        echo "LEYLINE_CARD_DB is not set." >&2
        echo "Set it to your local Raw_CardDatabase_*.mtga / *.sqlite path before running simclient." >&2
        echo "Example:" >&2
        echo "  LEYLINE_CARD_DB=\"$HOME/Library/Application Support/com.wizards.mtga/Downloads/Raw/Raw_CardDatabase_x.mtga\" just simclient \"Simple test\" 42" >&2
        echo "Add custom decks as data/decks/<name>.txt, then pass \"<name>\" as the deck argument." >&2
        exit 1
    fi
    src="matchdoor/build/simclient"
    # Clear prior outputs so the ingest step only picks up the current run.
    if [ -d "$src" ]; then trash "$src"; fi
    ./gradlew :matchdoor:simclient
    out="${HOME}/.scry/games"
    mkdir -p "$out"
    n=0
    for f in "$src"/*.log; do
        [ -e "$f" ] || continue
        base=$(basename "$f" .log)
        cp "$f" "$out/${base}.log"
        cp "${src}/${base}.meta.json" "$out/${base}.meta.json" 2>/dev/null || true
        n=$((n+1))
    done
    echo "Sim-client: $n game(s) ingested into $out (source: simclient)"

# Run simclient against one or more `.pzl` puzzles instead of shuffled decks.
# Same SIMCLIENT_SEEDS semantics; logs land tagged `puzzle:<basename>` so scry
# filters them distinctly from deck-shuffle runs.
#
# Examples:
#   LEYLINE_CARD_DB="$HOME/Library/Application Support/com.wizards.mtga/Downloads/Raw/Raw_CardDatabase_x.mtga" just simclient-puzzle bolt-face.pzl
#   LEYLINE_CARD_DB="$HOME/Library/Application Support/com.wizards.mtga/Downloads/Raw/Raw_CardDatabase_x.mtga" just simclient-puzzle bolt-face.pzl 1..5
#   LEYLINE_CARD_DB="$HOME/Library/Application Support/com.wizards.mtga/Downloads/Raw/Raw_CardDatabase_x.mtga" just simclient-puzzle "bolt-face.pzl,kicker-burst.pzl" "7,13,42"
# Requires LEYLINE_CARD_DB.
[group('simclient')]
simclient-puzzle puzzles seeds="42":
    #!/usr/bin/env bash
    set -euo pipefail
    cd "{{project_dir}}"
    export SIMCLIENT_PUZZLE="{{puzzles}}"
    export SIMCLIENT_SEEDS="{{seeds}}"
    if [ -z "${LEYLINE_CARD_DB:-}" ]; then
        echo "LEYLINE_CARD_DB is not set." >&2
        echo "Set it to your local Raw_CardDatabase_*.mtga / *.sqlite path before running simclient puzzles." >&2
        exit 1
    fi
    src="matchdoor/build/simclient"
    if [ -d "$src" ]; then trash "$src"; fi
    # The matrix tests gate on SIMCLIENT_PUZZLE so deck tests no-op cleanly.
    ./gradlew :matchdoor:simclient
    out="${HOME}/.scry/games"
    mkdir -p "$out"
    n=0
    for f in "$src"/*.log; do
        [ -e "$f" ] || continue
        base=$(basename "$f" .log)
        cp "$f" "$out/${base}.log"
        cp "${src}/${base}.meta.json" "$out/${base}.meta.json" 2>/dev/null || true
        n=$((n+1))
    done
    echo "Sim-client puzzle: $n game(s) ingested into $out (source: simclient)"

# --- Serve ---

# default dev mode: local FD + local MD
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
