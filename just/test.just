# Test recipes
# Imported by justfile — all variables available from parent.

# run all tests (all modules)
[group('test')]
test:
    cd "{{project_dir}}" && ./gradlew test :matchdoor:test :frontdoor:test :account:test

# single test class (e.g. `just test-one ShouldStopEvaluatorTest`)
[group('test')]
test-one class:
    cd "{{project_dir}}" && ./gradlew test :matchdoor:test :frontdoor:test -Pkotest.filter.specs=".*{{class}}"

# unit tests only (no engine bootstrap, fastest)
[group('test')]
test-unit:
    cd "{{project_dir}}" && ./gradlew testUnit :matchdoor:testUnit

# all conformance tests (~5s, wire-shape checks against Arena patterns)
[group('test')]
test-conformance:
    cd "{{project_dir}}" && ./gradlew testConformance :matchdoor:testConformance

# integration tests (parallel forks — boots engine per fork, runs classes concurrently)
[group('test')]
test-integration:
    cd "{{project_dir}}" && ./gradlew testIntegration :matchdoor:testIntegration

# front door tests only (fast FD iteration — :frontdoor module)
[group('test')]
test-fd:
    cd "{{project_dir}}" && ./gradlew :frontdoor:test

# match door tests only (fast MD iteration — :matchdoor module)
[group('test')]
test-md:
    cd "{{project_dir}}" && ./gradlew :matchdoor:testGate

# pre-commit gate: unit + conformance (all modules) + frontdoor
[group('test')]
test-gate:
    cd "{{project_dir}}" && ./gradlew testGate :matchdoor:testGate :frontdoor:test

# full gate: unit + conformance + frontdoor, then integration in parallel forks
[group('test')]
test-full: test-gate test-integration

# JaCoCo coverage report (unit+conformance only)
[group('test')]
coverage:
    #!/usr/bin/env bash
    set +e
    cd "{{project_dir}}" && ./gradlew testGate jacocoTestReport; rc=$?
    xml="{{project_dir}}/build/reports/jacoco/test/jacocoTestReport.xml"
    if [ -f "$xml" ]; then
        echo ""
        python3 "{{project_dir}}/buildscripts/coverage-summary.py" "$xml"
        echo ""
        echo "HTML: build/reports/jacoco/test/html/index.html"
    else
        echo "⚠ No coverage report (tests may have failed before report phase)"
    fi
    exit $rc
