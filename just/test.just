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

# JaCoCo coverage report (per-module, merged summary)
[group('test')]
coverage:
    #!/usr/bin/env bash
    set +e
    cd "{{project_dir}}"
    ./gradlew :matchdoor:testGate :matchdoor:jacocoTestReport \
              :tooling:testGate :tooling:jacocoTestReport \
              :frontdoor:test :frontdoor:jacocoTestReport \
              :account:test :account:jacocoTestReport; rc=$?
    xmls=$(find . -path '*/build/reports/jacoco/*/jacocoTestReport.xml' -not -path '*/forge/*' 2>/dev/null | tr '\n' ' ')
    if [ -n "$xmls" ]; then
        echo ""
        for xml in $xmls; do
            echo "--- $(echo $xml | sed 's|^\./||;s|/build/.*||') ---"
            uv run --project "{{project_dir}}" python "{{project_dir}}/gradle/scripts/coverage-summary.py" "$xml"
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
# Chains: ConformancePipelineTest → tape segment template → tape conform run
# Exit 0 = PASS, 1 = annotation diffs, 2 = regression vs golden
[group('test')]
conform category session="":
    #!/usr/bin/env bash
    set -euo pipefail
    cd "{{project_dir}}"

    # Derive frame filename from category (PlayLand → playland, CastSpell → castspell)
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
    uv run --project "{{project_dir}}" python tools/tape/segments.py template "{{category}}" ${session_arg:+"$session_arg"} > "$template_file"
    echo "  → $template_file"

    echo ""
    echo "=== Step 3: Diff template vs engine ==="
    golden_flag=""
    if [ -f "$golden_file" ]; then
        golden_flag="--golden $golden_file"
    fi
    uv run --project "{{project_dir}}" python tools/tape/segments.py diff "$template_file" "$frame_file" $golden_flag

# capture golden diff result for a category (run after `just conform` succeeds)
# Usage: just conform-golden PlayLand [session]
[group('test')]
conform-golden category session="":
    #!/usr/bin/env bash
    set -euo pipefail
    cd "{{project_dir}}"

    frame=$(echo "{{category}}" | tr '[:upper:]' '[:lower:]')
    frame_file="matchdoor/build/conformance/${frame}-frame.json"
    golden_file="matchdoor/src/test/resources/golden/conform-${frame}.json"
    template_file="/tmp/conform-${frame}-template.json"

    if [ ! -f "$frame_file" ] || [ ! -f "$template_file" ]; then
        echo "Run 'just conform {{category}}' first to generate engine output + template" >&2
        exit 1
    fi

    # Exit 0 = PASS, 1 = known diffs (both valid for golden capture)
    uv run --project "{{project_dir}}" python tools/tape/segments.py diff "$template_file" "$frame_file" --json > "$golden_file" || true
    echo "Golden captured: $golden_file"
