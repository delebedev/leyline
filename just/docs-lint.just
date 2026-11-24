# check that .md file references across docs/code actually exist
[group('docs')]
docs-lint: protocol-docs-lint
    #!/usr/bin/env bash
    set -euo pipefail

    errors=0
    repo_root="$(git rev-parse --show-toplevel)"
    cd "$repo_root"

    # The scans below treat rg failure as "no matches", so a missing rg would pass silently.
    command -v rg >/dev/null || { echo "docs-lint requires ripgrep (rg)" >&2; exit 1; }

    # rg glob excludes
    rg_exclude="--glob=!forge/ --glob=!.gradle/ --glob=!build/ --glob=!proto/upstream/ --glob=!.github/ --glob=!.beads/ --glob=!node_modules/ --glob=!.agent/"

    # Track already-reported to avoid duplicates (phase 1 links + phase 2 path refs)
    seen_file=$(mktemp)
    trap "rm -f $seen_file" EXIT

    report() {
        local key="$1:$2:$3"
        if grep -qxF "$key" "$seen_file" 2>/dev/null; then
            return 0
        fi
        echo "$key" >> "$seen_file"
        echo "  $1:$2 → $3"
        errors=$((errors + 1))
        return 0
    }

    check_ref() {
        local src="$1" ref="$2"

        # Strip anchor fragment and query string
        ref="${ref%%#*}"
        ref="${ref%%\?*}"
        [[ -z "$ref" ]] && return 0
        # Must end in .md
        [[ "$ref" != *.md && "$ref" != *.MD ]] && return 0
        # Skip build artifact paths
        [[ "$ref" = .bundle-stage/* ]] && return 0

        # Resolve path
        local target
        if [[ "$ref" = /* ]]; then
            target="$repo_root$ref"
        elif [[ "$ref" = docs/* || "$ref" = engine/* || "$ref" = native/* || "$ref" = app/* || "$ref" = forge/* ]]; then
            target="$repo_root/$ref"
        else
            target="$(dirname "$src")/$ref"
        fi

        # Normalize
        target="$(cd "$(dirname "$target")" 2>/dev/null && echo "$(pwd)/$(basename "$target")")" 2>/dev/null || target=""

        if [[ -z "$target" || ! -f "$target" ]]; then
            # Fallback: strip ../ prefixes and try from repo root
            local stripped="$ref"
            while [[ "$stripped" = ../* ]]; do stripped="${stripped#../}"; done
            if [[ -f "$repo_root/$stripped" ]]; then
                return 0
            fi
            return 1
        fi
        return 0
    }

    # --- Phase 1: .md bracket links — [text](path.md) ---
    echo "Scanning .md links..."
    while IFS=: read -r file line_num content; do
        rest="$content"
        md_link_pat='\]\(([^)]+)\)'
        while [[ "$rest" =~ $md_link_pat ]]; do
            ref="${BASH_REMATCH[1]}"
            if [[ ! "$ref" =~ ^https?:// && ! "$ref" =~ ^mailto: && ! "$ref" =~ ^# && ! "$ref" =~ \{\{ ]]; then
                check_ref "$file" "$ref" || report "$file" "$line_num" "$ref"
            fi
            rest="${rest/"](${ref})"/ }"
        done
    done < <(rg -n '\.md[)#]' --type md $rg_exclude . 2>/dev/null || true)

    # --- Phase 2: path-shaped .md refs across all file types ---
    echo "Scanning path refs (.md, .yaml, .kt)..."
    path_pat='[a-zA-Z0-9_./-]+/[a-zA-Z0-9_./-]*\.md'
    url_pat="https?://[^[:space:])>\"',]+"
    while IFS=: read -r file line_num content; do
        rest="$content"
        # Remote URLs end in .md too; drop them so only local paths are checked.
        while [[ "$rest" =~ $url_pat ]]; do
            rest="${rest/"${BASH_REMATCH[0]}"/ }"
        done
        while [[ "$rest" =~ $path_pat ]]; do
            ref="${BASH_REMATCH[0]}"
            check_ref "$file" "$ref" || report "$file" "$line_num" "$ref"
            rest="${rest#*${ref}}"
        done
    done < <(rg -n "$path_pat" --type md --type yaml --type kotlin $rg_exclude . 2>/dev/null || true)

    if ((errors > 0)); then
        echo ""
        echo "Found $errors broken .md reference(s)."
        exit 1
    else
        echo ""
        echo "All .md references valid."
    fi

# find .md files that nothing references (potential orphans)
[group('docs')]
docs-orphans:
    #!/usr/bin/env bash
    set -euo pipefail

    repo_root="$(git rev-parse --show-toplevel)"
    cd "$repo_root"

    rg_exclude="--glob=!forge/ --glob=!.gradle/ --glob=!build/ --glob=!proto/upstream/ --glob=!.github/ --glob=!.beads/ --glob=!node_modules/ --glob=!.agent/"

    # Collect all referenced .md filenames (basename only — cheap but catches most links)
    refs_file=$(mktemp)
    trap "rm -f $refs_file" EXIT
    rg -o '[a-zA-Z0-9_./-]*[a-zA-Z0-9_-]\.md' $rg_exclude 2>/dev/null \
        | sed 's/^[^:]*://' | xargs -I{} basename {} | sort -u > "$refs_file"

    # Find all .md files, skip exempt dirs/patterns
    orphans=0
    while IFS= read -r f; do
        base="$(basename "$f")"

        # Skip entry-point files (read directly, never cited)
        case "$base" in
            CLAUDE.md|README.md|CONTRIBUTING.md|RELEASING.md|MEMORY.md|AGENTS.md|SESSION.md) continue ;;
            SYNTHESIS.md|INDEX.md|index.md) continue ;;
        esac

        # Skip exempt directories
        case "$f" in
            docs/superpowers/*) continue ;;     # superpowers skill output
            docs/decisions/*) continue ;;       # decision records — standalone by convention
        esac

        # Check if basename appears in any reference
        if ! grep -qxF "$base" "$refs_file"; then
            echo "  $f"
            orphans=$((orphans + 1))
        fi
    done < <(rg --files --glob '*.md' $rg_exclude --glob='!*/target/*' 2>/dev/null | sort)

    if ((orphans > 0)); then
        echo ""
        echo "Found $orphans orphan .md file(s) — not referenced anywhere."
    else
        echo ""
        echo "No orphans found."
    fi

# check protocol reference statement IDs, citations and implementation-map owners
[group('docs')]
protocol-docs-lint:
    #!/usr/bin/env bash
    set -euo pipefail

    repo_root="$(git rev-parse --show-toplevel)"
    cd "$repo_root"
    command -v rg >/dev/null || { echo "protocol-docs-lint requires ripgrep (rg)" >&2; exit 1; }

    ref_dir="docs/protocol"
    map="$ref_dir/implementation.md"
    [[ -d "$ref_dir" ]] || exit 0

    work="$(mktemp -d)"
    trap 'rm -rf "$work"' EXIT
    errors=0
    fail() { echo "  $1"; errors=$((errors + 1)); }

    # Contract statement definitions and normative-keyword placement.
    # Emits "def<TAB>ID<TAB>file:line" and "err<TAB>file:line<TAB>message".
    scan_page='
    function report(msg) { printf "err\t%s:%d\t%s\n", FILENAME, FNR, msg }
    FNR == 1 { entity = ""; front = 0; fence = 0; contract = 0 }
    FNR == 1 && /^---$/ { front = 1; next }
    front && /^---$/ { front = 0; next }
    front {
        if ($0 ~ /^entity:[ \t]*/) { entity = $0; sub(/^entity:[ \t]*/, "", entity); sub(/^[a-z_]+\//, "", entity) }
        next
    }
    /^[ \t]*```/ { fence = !fence; next }
    fence { next }
    /^#{1,2} / { contract = ($0 ~ /^## Contract[ \t]*$/) }
    {
        line = $0
        while (match(line, /<a id="[^"]*"><\/a>/)) {
            id = substr(line, RSTART + 7, RLENGTH - 13)
            rest = substr(line, RSTART + RLENGTH)
            line = rest
            if (id !~ /^[A-Z][A-Za-z0-9_]*\.[a-z0-9-]+$/) { report("malformed statement ID: " id); continue }
            if (!contract) report("statement ID outside a Contract section: " id)
            if (entity == "") report("statement ID on a page without an entity key: " id)
            else if (substr(id, 1, length(entity) + 1) != entity ".") report("statement ID " id " does not start with page entity " entity ".")
            if (index(rest, "**" id ".**") != 1) report("statement ID " id " must be followed by **" id ".**")
            printf "def\t%s\t%s:%d\n", id, FILENAME, FNR
        }
        if (!contract) {
            prose = $0
            gsub(/`[^`]*`/, "", prose)
            if (match(prose, /(^|[^A-Za-z])(MUST|REQUIRED|SHALL|SHOULD|RECOMMENDED|MAY|OPTIONAL)([^A-Za-z]|$)/)) report("normative keyword outside a Contract section")
        }
    }'
    find "$ref_dir" -name '*.md' -print0 | sort -z | xargs -0 awk "$scan_page" > "$work/scan"

    echo "Checking protocol statement IDs..."
    while IFS=$'\t' read -r kind where msg; do
        [[ "$kind" == err ]] && fail "$where → $msg"
    done < "$work/scan"
    awk -F'\t' '$1 == "def" { print $2 "\t" $3 }' "$work/scan" | sort > "$work/defs"
    cut -f1 "$work/defs" | uniq -d | while read -r id; do
        echo "duplicate statement ID: $id ($(awk -F'\t' -v id="$id" '$1 == id { printf "%s ", $2 }' "$work/defs"))"
    done > "$work/dups"
    while read -r line; do fail "$line"; done < "$work/dups"

    # Citations: "protocol: ID[, ID...]" anywhere, and Markdown links to a statement anchor.
    echo "Checking protocol citations..."
    id_pat='[A-Z][A-Za-z0-9_]*\.[a-z0-9-]+'
    rg_exclude=(--glob '!forge/' --glob '!.gradle/' --glob '!**/build/' --glob '!proto/upstream/' --glob '!.git/' --glob '!node_modules/')
    rg -n -o --no-heading "${rg_exclude[@]}" "protocol:[[:space:]]*${id_pat}([[:space:]]*,[[:space:]]*${id_pat})*" . 2>/dev/null \
        | while IFS=: read -r file line_num marker; do
            ids="${marker#protocol:}"
            for id in ${ids//,/ }; do printf '%s\t%s:%s\n' "$id" "${file#./}" "$line_num"; done
        done > "$work/cites" || true
    while IFS=$'\t' read -r id where; do
        cut -f1 "$work/defs" | grep -qxF "$id" || fail "$where → cited statement does not exist: $id"
    done < "$work/cites"

    rg -n -o --no-heading --type md "${rg_exclude[@]}" "\]\([^)#[:space:]]*\.md#${id_pat}\)" . 2>/dev/null \
        | while IFS=: read -r file line_num link; do
            target="${link#](}"; target="${target%)}"
            printf '%s\t%s\t%s:%s\n' "${target#*#}" "$(dirname "$file")/${target%%#*}" "${file#./}" "$line_num"
        done > "$work/links" || true
    while IFS=$'\t' read -r id target where; do
        resolved="$(cd "$(dirname "$target")" 2>/dev/null && echo "$(pwd -P)/$(basename "$target")")" || resolved=""
        resolved="${resolved#"$(pwd -P)/"}"
        awk -F'\t' -v id="$id" -v file="$resolved" '$1 == id && index($2, file ":") == 1 { found = 1 } END { exit !found }' "$work/defs" \
            || fail "$where → link anchor is not a statement in its target: $target#$id"
    done < "$work/links"

    # Owner symbols in the implementation map: entity-table Owner column and "Owner:" lines.
    if [[ -f "$map" ]]; then
        echo "Checking implementation-map owners..."
        awk '
            /^## / { table = ($0 ~ /^## Entities/) }
            table && /^\|/ && !/^\|[ \t]*(Entity|-)/ { n = split($0, col, "|"); print FNR "\t" col[3] }
            /^[ \t]*- Owner:/ { print FNR "\t" $0 }
        ' "$map" > "$work/owner-lines"
        main_dirs=()
        for d in */src/main; do [[ "$d" == buildSrc/* || "$d" == forge/* ]] || main_dirs+=("$d"); done
        while IFS=$'\t' read -r line_num text; do
            rest="$text"
            while [[ "$rest" =~ \`([^\`]+)\` ]]; do
                symbol="${BASH_REMATCH[1]}"
                rest="${rest#*\`"$symbol"\`}"
                if [[ ! "$symbol" =~ ^([A-Z][A-Za-z0-9]*)(\.([a-z][A-Za-z0-9]*))?$ ]]; then
                    fail "$map:$line_num → malformed owner symbol: $symbol"; continue
                fi
                type="${BASH_REMATCH[1]}"; member="${BASH_REMATCH[3]}"
                files="$(rg -l --type kotlin "\b(class|object|interface|typealias)[[:space:]]+${type}\b" "${main_dirs[@]}" 2>/dev/null || true)"
                if [[ -z "$files" ]]; then fail "$map:$line_num → owner type not found in main code: $type"; continue; fi
                # A member is declared at class-member indentation (4, or 8 inside a companion) in a file
                # that declares the type, or as an extension `fun Type.member` anywhere in main code.
                if [[ -n "$member" ]] \
                    && ! rg -q "^( {4}| {8})([a-z@][A-Za-z@]*[[:space:]]+)*(fun|val|var)[[:space:]]+(<[^>]+>[[:space:]]+)?${member}\b" $files \
                    && ! rg -q --type kotlin "\b(fun|val|var)[[:space:]]+(<[^>]+>[[:space:]]+)?${type}\.${member}\b" "${main_dirs[@]}"; then
                    fail "$map:$line_num → owner member not found in $type: $member"
                fi
            done
        done < "$work/owner-lines"
    fi

    # Informational: statements that no test or conformance contract cites.
    awk -F'\t' '$2 ~ /(^|\/)src\/test\// || $2 ~ /^conformance\// { print $1 }' "$work/cites" | sort -u > "$work/proved"
    uncovered="$(cut -f1 "$work/defs" | sort -u | comm -23 - "$work/proved")"
    total="$(cut -f1 "$work/defs" | sort -u | grep -c . || true)"
    echo ""
    if [[ -n "$uncovered" ]]; then
        echo "Statements without a citing test or conformance contract ($(grep -c . <<< "$uncovered") of $total; informational):"
        sed 's/^/  /' <<< "$uncovered"
    else
        echo "Every statement ($total) has a citing test or conformance contract."
    fi

    if ((errors > 0)); then
        echo ""
        echo "Found $errors protocol reference error(s)."
        exit 1
    fi
    echo "Protocol reference checks passed."
