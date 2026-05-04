# Test recipes
# Imported by justfile — all variables available from parent.

# single test class (e.g. `just test-one ShouldStopEvaluatorTest`)
[group('test')]
test-one class:
    cd "{{project_dir}}" && ./gradlew :matchdoor:test :frontdoor:test :account:test -Pkotest.filter.specs=".*{{class}}"

# single test class with stdout/stderr visible (e.g. `just test-debug BiteDownTest`)
[group('test')]
test-debug class:
    cd "{{project_dir}}" && ./gradlew :matchdoor:test :frontdoor:test :account:test -Pkotest.filter.specs=".*{{class}}" -Pverbose

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
    ./gradlew ktlintCheck detekt \
        :matchdoor:testGate :matchdoor:jacocoTestReport \
        :frontdoor:test :frontdoor:jacocoTestReport \
        :account:test :account:jacocoTestReport

# matchdoor integration tests (boots engine per fork — run after risky matchdoor changes)
[group('test')]
test-integration:
    cd "{{project_dir}}" && ./gradlew :matchdoor:testIntegration :matchdoor:jacocoTestReport

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
    deck_hash=$(grep -A6 'COMBAT_DECK = ' matchdoor/src/test/kotlin/leyline/session/combat/CombatInteractionTest.kt | head -8 | shasum | awk '{print $1}')
    jar_hash=$(shasum forge/.m2-local/forge/forge-gui/2.0.10-SNAPSHOT/forge-gui-2.0.10-SNAPSHOT.jar 2>/dev/null | awk '{print $1}' || echo "missing")
    echo "slice-verify input manifest:"
    echo "  leyline:        $leyline_sha"
    echo "  forge:          $forge_sha"
    echo "  edition-files:  $edition_hash"
    echo "  COMBAT_DECK:    $deck_hash"
    echo "  forge-gui jar:  $jar_hash"
    echo
    ./gradlew :matchdoor:testIntegrationStrict --console=plain

# JaCoCo coverage report (per-module, merged summary)
[group('test')]
coverage:
    #!/usr/bin/env bash
    set +e
    cd "{{project_dir}}"
    ./gradlew :matchdoor:testGate :matchdoor:jacocoTestReport \
              :frontdoor:test :frontdoor:jacocoTestReport \
              :account:test :account:jacocoTestReport; rc=$?
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
