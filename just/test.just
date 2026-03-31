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

# conformance pipeline: engine test → template from recording → diff
# Usage: just conform PlayLand [session]
# NOTE: requires leyline-private tools (tape/segments.py)
[group('test')]
conform category session="":
    #!/usr/bin/env bash
    set -euo pipefail
    cd "{{project_dir}}"
    private="{{project_dir}}/../leyline-private"
    if [ ! -d "$private/tools/tape" ]; then
        echo "Conformance pipeline requires ~/src/leyline-private" >&2
        exit 1
    fi

    frame=$(echo "{{category}}" | tr '[:upper:]' '[:lower:]')
    frame_file="matchdoor/build/conformance/${frame}-frame.json"
    golden_file="matchdoor/src/test/resources/golden/conform-${frame}.json"
    template_file="/tmp/conform-${frame}-template.json"

    echo "=== Step 1: Run engine test ==="
    ./gradlew :matchdoor:testIntegration -Pkotest.filter.specs=".*ConformancePipelineTest" -q

    if [ ! -f "$frame_file" ]; then
        echo "Error: engine output not found at $frame_file" >&2
        exit 1
    fi
    echo "  → $frame_file"

    echo ""
    echo "=== Step 2: Generate template from recording ==="
    session_arg="{{session}}"
    python3 "$private/tools/tape/segments.py" template "{{category}}" ${session_arg:+"$session_arg"} > "$template_file"
    echo "  → $template_file"

    echo ""
    echo "=== Step 3: Diff template vs engine ==="
    golden_flag=""
    if [ -f "$golden_file" ]; then
        golden_flag="--golden $golden_file"
    fi
    python3 "$private/tools/tape/segments.py" diff "$template_file" "$frame_file" $golden_flag

# capture golden diff result for a category
[group('test')]
conform-golden category session="":
    #!/usr/bin/env bash
    set -euo pipefail
    cd "{{project_dir}}"
    private="{{project_dir}}/../leyline-private"
    if [ ! -d "$private/tools/tape" ]; then
        echo "Conformance pipeline requires ~/src/leyline-private" >&2
        exit 1
    fi

    frame=$(echo "{{category}}" | tr '[:upper:]' '[:lower:]')
    frame_file="matchdoor/build/conformance/${frame}-frame.json"
    golden_file="matchdoor/src/test/resources/golden/conform-${frame}.json"
    template_file="/tmp/conform-${frame}-template.json"

    if [ ! -f "$frame_file" ] || [ ! -f "$template_file" ]; then
        echo "Run 'just conform {{category}}' first to generate engine output + template" >&2
        exit 1
    fi

    python3 "$private/tools/tape/segments.py" diff "$template_file" "$frame_file" --json > "$golden_file" || true
    echo "Golden captured: $golden_file"
