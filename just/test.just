# Test recipes
# Imported by justfile — all variables available from parent.

# single test class, engine by default (e.g. `just test-one ShouldStopEvaluatorTest`, `just test-one WebRoutesTest web`)
# Resolves the fully-qualified class name from source first: Gradle's --tests
# glob matching (`--tests "*Class"`) silently matches zero tests for kotest
# specs on this project's Gradle/kotest combo (the class's tests run and pass
# inside kotest, but the result never reaches Gradle's reporting layer) — see
# .claude/rules/leyline-tests.md "Running tests". A fully-qualified --tests
# pattern is the one shape proven to work and to fail loud when nothing
# matches, so resolve-test-fqcn.sh does that resolution before invoking gradle.
[group('test')]
test-one class module='engine':
    #!/usr/bin/env bash
    set -euo pipefail
    cd "{{project_dir}}"
    fqcn=$(gradle/scripts/resolve-test-fqcn.sh "{{class}}" "{{module}}" "{{project_dir}}")
    ./gradlew :{{module}}:test --tests "$fqcn"

# single test class with stdout/stderr visible, engine by default (e.g. `just test-debug BiteDownTest`)
[group('test')]
test-debug class module='engine':
    #!/usr/bin/env bash
    set -euo pipefail
    cd "{{project_dir}}"
    fqcn=$(gradle/scripts/resolve-test-fqcn.sh "{{class}}" "{{module}}" "{{project_dir}}")
    ./gradlew :{{module}}:test --tests "$fqcn" -Pverbose

# several test classes in one run, space-separated, engine by default
# (e.g. `just test-many "PackageLayeringTest HandlerConstructorContractTest"`)
# kotest.filter.specs comma/semicolon lists are NOT a substitute — Kotest
# 5.9.1's SystemPropertySpecFilterInterceptor ANDs multiple patterns together
# instead of ORing them, so a real multi-class list always matches zero specs,
# silently (BUILD SUCCESSFUL, PASS 0/0 — no non-zero exit at all). Repeated
# --tests flags with fully-qualified names give real OR semantics and fail
# loud, so that's what this recipe uses.
[group('test')]
test-many classes module='engine':
    #!/usr/bin/env bash
    set -euo pipefail
    cd "{{project_dir}}"
    args=()
    for cls in {{classes}}; do
        fqcn=$(gradle/scripts/resolve-test-fqcn.sh "$cls" "{{module}}" "{{project_dir}}")
        args+=(--tests "$fqcn")
    done
    ./gradlew :{{module}}:test "${args[@]}"

# pre-commit gate: unit + board + fmt check + coverage (all modules)
[group('test')]
test-gate: docs-lint
    #!/usr/bin/env bash
    set -euo pipefail
    cd "{{project_dir}}"
    # Warn if pre-push hooks aren't installed — silent skip means CI failures
    # surprise you instead of being caught locally in ~15s.
    if [ "$(git config --get core.hooksPath || echo)" != ".githooks" ]; then
        echo "⚠  pre-push hooks not installed — run 'just hooks-install' to enable fmt-check/detekt/docs-lint pre-push" >&2
    fi
    ./gradlew ktlintCheck detekt verifyWebProfilePosture :web:generateOpenApi
    ./gradlew --rerun-tasks \
        :test \
        :domain:test \
        :engine:testGate \
        :native:test \
        :web:test
    ./gradlew \
        :domain:jacocoTestReport \
        :engine:jacocoTestReport \
        :native:jacocoTestReport \
        :web:jacocoTestReport
    git diff --exit-code -- web/src/main/resources/openapi.json

# engine integration tests (boots engine per fork — run after risky engine changes)
# Forked JVMs run test classes in parallel; each fork boots its own card DB
# (~4s) and keeps Forge's static state (MyRandom et al.) isolated, unlike
# in-process kotest parallelism which races on it. Fork count defaults to
# ~cores/4 (gradle-side); pass a number to override.
[group('test')]
test-integration forks='':
    cd "{{project_dir}}" && ./gradlew --rerun-tasks -PintegrationForks={{forks}} :engine:testIntegration :engine:jacocoTestReport

# puzzle-backed scripted acceptance suites
[group('test')]
test-acceptance:
    cd "{{project_dir}}" && ./gradlew --rerun-tasks :engine:testAcceptance

# sim-client E2E suite — synthetic GRE log generation, drives full games (slow)
[group('test')]
test-simclient:
    cd "{{project_dir}}" && ./gradlew --rerun-tasks :engine:testSimClient

# strict integration test for slice verification — bypasses gradle's test
# build cache (which serves stale PASS results when forge jar inputs match
# a prior cached run, hiding regressions across forge submodule pointer
# bumps). Asserts clean leyline + forge working trees and prints an input
# manifest so what's tested is observable, not assumed.
[group('test')]
slice-verify:
    #!/usr/bin/env bash
    set -euo pipefail
    cd "{{project_dir}}"
    fail=0
    # Allow forge submodule pointer to differ from tracked (verification harnesses
    # bump it across forge SHAs). Forge working-tree cleanliness is checked
    # separately below.
    leyline_dirty=$(git status --porcelain | grep -v '^?? ' | grep -v ' forge$' || true)
    if [ -n "$leyline_dirty" ]; then
        echo "✗ leyline working tree dirty — commit or stash first" >&2
        echo "$leyline_dirty" >&2
        fail=1
    fi
    forge_dirty=$(git -C forge status --porcelain | grep -vE '^\?\? \.m2-local(\.worktree)?($|/)' || true)
    if [ -n "$forge_dirty" ]; then
        echo "✗ forge submodule working tree dirty — commit, stash, or reset first" >&2
        echo "$forge_dirty" >&2
        fail=1
    fi
    if [ "$fail" = 1 ]; then exit 1; fi
    leyline_sha=$(git rev-parse HEAD)
    forge_sha=$(git -C forge rev-parse HEAD)
    edition_hash=$(shasum forge/forge-gui/res/editions/Secrets\ of\ Strixhaven*.txt forge/forge-gui/res/editions/Special\ Guests.txt 2>/dev/null | shasum | awk '{print $1}')
    deck_hash=$(grep -A6 'COMBAT_DECK = ' engine/src/test/kotlin/leyline/session/combat/CombatInteractionTest.kt | head -8 | shasum | awk '{print $1}')
    forge_version=$(grep '^forge = ' gradle/libs.versions.toml | cut -d '"' -f2)
    forge_gui_jar="forge/.m2-local/forge/forge-gui/${forge_version}/forge-gui-${forge_version}.jar"
    jar_hash=$(shasum "$forge_gui_jar" 2>/dev/null | awk '{print $1}' || echo "missing")
    echo "slice-verify input manifest:"
    echo "  leyline:        $leyline_sha"
    echo "  forge:          $forge_sha"
    echo "  edition-files:  $edition_hash"
    echo "  COMBAT_DECK:    $deck_hash"
    echo "  forge-gui jar:  $jar_hash"
    echo
    ./gradlew --rerun-tasks :engine:testIntegrationStrict --console=plain

# JaCoCo coverage report (per-module, merged summary)
[group('test')]
coverage:
    #!/usr/bin/env bash
    set +e
    cd "{{project_dir}}"
    ./gradlew :test :jacocoTestReport \
              :domain:test :domain:jacocoTestReport \
              :engine:testGate :engine:jacocoTestReport \
              :native:test :native:jacocoTestReport \
              :web:test :web:jacocoTestReport; rc=$?
    xmls=$(find . -path '*/build/reports/jacoco/*/jacocoTestReport.xml' -not -path '*/forge/*' 2>/dev/null | tr '\n' ' ')
    if [ -n "$xmls" ]; then
        echo ""
        for xml in $xmls; do
            echo "--- $(echo $xml | sed 's|^\./||;s|/build/.*||') ---"
            python3 "{{project_dir}}/gradle/scripts/coverage-summary.py" "$xml"
            echo ""
        done
    else
        echo "No coverage reports found"
    fi
    exit $rc

# detekt static analysis (all modules)
[group('test')]
detekt:
    cd "{{project_dir}}" && ./gradlew detekt
