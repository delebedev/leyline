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

# pre-commit gate: unit + conformance + fmt check + coverage (all modules)
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

