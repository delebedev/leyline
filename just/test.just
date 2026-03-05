# Test recipes
# Imported by justfile — all variables available from parent.

# run all tests
[group('test')]
test:
    cd "{{project_dir}}" && ./gradlew test

# single test class (e.g. `just test-one ShouldStopEvaluatorTest`)
[group('test')]
test-one class:
    cd "{{project_dir}}" && ./gradlew test -Pkotest.filter.specs=".*{{class}}"

# unit tests only (no engine bootstrap, fastest)
[group('test')]
test-unit:
    cd "{{project_dir}}" && ./gradlew testUnit

# all conformance tests (~5s, wire-shape checks against Arena patterns)
[group('test')]
test-conformance:
    cd "{{project_dir}}" && ./gradlew testConformance

# integration tests (parallel forks — boots engine per fork, runs classes concurrently)
[group('test')]
test-integration:
    cd "{{project_dir}}" && ./gradlew testIntegration

# front door tests only (fast FD iteration)
[group('test')]
test-fd:
    cd "{{project_dir}}" && ./gradlew testFd

# pre-commit gate: unit + conformance + fd (fast, single fork)
[group('test')]
test-gate:
    cd "{{project_dir}}" && ./gradlew testGate

# full gate: unit + conformance, then integration in parallel forks
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
