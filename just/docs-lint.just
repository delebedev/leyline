# check that .md file references across docs/code actually exist
[group('docs')]
docs-lint:
    #!/usr/bin/env bash
    set -euo pipefail

    errors=0
    repo_root="$(git rev-parse --show-toplevel)"
    cd "$repo_root"

    # rg glob excludes
    rg_exclude="--glob=!forge/ --glob=!.gradle/ --glob=!build/ --glob=!proto/upstream/ --glob=!.github/ --glob=!.beads/ --glob=!node_modules/ --glob=!.agent/ --glob=!.claude/worktrees/ --glob=!.claude/skills/"

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
        elif [[ "$ref" = docs/* || "$ref" = matchdoor/* || "$ref" = frontdoor/* || "$ref" = account/* || "$ref" = app/* || "$ref" = .claude/* || "$ref" = forge/* ]]; then
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
    done < <(rg -n '\.md[)#]' --type md $rg_exclude 2>/dev/null || true)

    # --- Phase 2: path-shaped .md refs across all file types ---
    echo "Scanning path refs (.md, .yaml, .kt)..."
    path_pat='[a-zA-Z0-9_./-]+/[a-zA-Z0-9_./-]*\.md'
    while IFS=: read -r file line_num content; do
        rest="$content"
        while [[ "$rest" =~ $path_pat ]]; do
            ref="${BASH_REMATCH[0]}"
            check_ref "$file" "$ref" || report "$file" "$line_num" "$ref"
            rest="${rest#*${ref}}"
        done
    done < <(rg -n "$path_pat" --type md --type yaml --type kotlin $rg_exclude 2>/dev/null || true)

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

    rg_exclude="--glob=!forge/ --glob=!.gradle/ --glob=!build/ --glob=!proto/upstream/ --glob=!.github/ --glob=!.beads/ --glob=!node_modules/ --glob=!.agent/ --glob=!.claude/worktrees/ --glob=!.claude/skills/ --glob=!.claude/plugins/"

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
            .claude/rules/*) continue ;;        # loaded by agent framework
            .claude/agent-memory/*) continue ;;  # agent working memory
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
