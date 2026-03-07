# Test recipes
# Imported by justfile — all variables available from parent.

# run all tests (all modules, all tiers)
[group('test')]
test:
    cd "{{project_dir}}" && ./gradlew :matchdoor:test :tooling:test :frontdoor:test :account:test

# single test class (e.g. `just test-one ShouldStopEvaluatorTest`)
[group('test')]
test-one class:
    cd "{{project_dir}}" && ./gradlew :matchdoor:test :tooling:test :frontdoor:test :account:test -Pkotest.filter.specs=".*{{class}}"

# pre-commit gate: unit + conformance + fmt check (all modules)
[group('test')]
test-gate:
    cd "{{project_dir}}" && ./gradlew spotlessCheck :matchdoor:testGate :tooling:testGate :frontdoor:test :account:test

# matchdoor integration tests (boots engine per fork — run after risky matchdoor changes)
[group('test')]
test-integration:
    cd "{{project_dir}}" && ./gradlew :matchdoor:testIntegration

# JaCoCo coverage report (unit+conformance only)
[group('test')]
coverage:
    #!/usr/bin/env bash
    set +e
    cd "{{project_dir}}" && ./gradlew :matchdoor:testGate jacocoTestReport; rc=$?
    xml="{{project_dir}}/build/reports/jacoco/test/jacocoTestReport.xml"
    if [ -f "$xml" ]; then
        echo ""
        python3 "{{project_dir}}/gradle/scripts/coverage-summary.py" "$xml"
        echo ""
        echo "HTML: build/reports/jacoco/test/html/index.html"
    else
        echo "⚠ No coverage report (tests may have failed before report phase)"
    fi
    exit $rc
