# Test recipes
# Imported by justfile — all variables available from parent.

# single test class (e.g. `just test-one ShouldStopEvaluatorTest`)
[group('test')]
test-one class:
    cd "{{project_dir}}" && ./gradlew :matchdoor:test :frontdoor:test :account:test test -Pkotest.filter.specs=".*{{class}}"

# pre-commit gate: unit + conformance + fmt check + coverage (all modules)
[group('test')]
test-gate:
    cd "{{project_dir}}" && ./gradlew spotlessCheck detekt \
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

