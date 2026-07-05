#!/usr/bin/env bash
# Resolves a bare test class simple name to its fully-qualified name by
# locating the single matching source file under <module>/src/test/kotlin.
#
# Why this exists: Gradle's `--tests` glob matching (`--tests "*ClassName"`)
# silently matches zero tests for kotest specs on this project's Gradle/kotest
# combo — the class's own tests run and pass inside kotest, but the result
# never reaches Gradle's reporting layer, so `just test-one` failed loud for
# every class including valid ones. A fully-qualified `--tests` pattern (no
# wildcard) is the one shape proven to work reliably and to fail loud (via
# Gradle's own "No tests found for given includes") when nothing matches.
# See .claude/rules/leyline-tests.md "Running tests" for the full writeup.
set -euo pipefail

class="${1:?usage: resolve-test-fqcn.sh <ClassName> <module> <project-dir>}"
module="${2:?usage: resolve-test-fqcn.sh <ClassName> <module> <project-dir>}"
root="${3:?usage: resolve-test-fqcn.sh <ClassName> <module> <project-dir>}"

test_dir="$root/$module/src/test/kotlin"
if [ ! -d "$test_dir" ]; then
    echo "✗ no such test source dir: $test_dir" >&2
    exit 1
fi

matches=()
while IFS= read -r line; do
    matches+=("$line")
done < <(find "$test_dir" \( -name "${class}.kt" -o -name "${class}.java" \))

if [ "${#matches[@]}" -eq 0 ]; then
    echo "✗ no source file named ${class}.kt (or .java) under $test_dir" >&2
    exit 1
fi

if [ "${#matches[@]}" -gt 1 ]; then
    echo "✗ ambiguous: multiple files named ${class} under $test_dir:" >&2
    printf '  %s\n' "${matches[@]}" >&2
    exit 1
fi

pkg=$(grep -m1 '^package ' "${matches[0]}" | sed -E 's/^package[[:space:]]+//; s/[;[:space:]]*$//')
if [ -z "$pkg" ]; then
    echo "✗ no 'package' declaration found in ${matches[0]}" >&2
    exit 1
fi

echo "${pkg}.${class}"
