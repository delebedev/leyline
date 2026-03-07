# Card & ability lookups against Arena's local SQLite DB.
# Imported by justfile — all variables available from parent.

arena_db := env("HOME", "/tmp") / "Library/Application Support/com.wizards.mtga/Downloads/Raw"
cardsfolder := project_dir / "forge/forge-gui/res/cardsfolder"

# grpId → card name (accepts multiple IDs)
[group('lookup')]
card +ids:
    #!/usr/bin/env bash
    set -euo pipefail
    db=$(echo "{{arena_db}}"/Raw_CardDatabase_*.mtga)
    if [ ! -f "$db" ]; then echo "Arena DB not found at {{arena_db}}" >&2; exit 1; fi
    ids="{{ids}}"
    in_clause=$(echo "$ids" | tr ' ' ',')
    sqlite3 "$db" "SELECT c.GrpId, l.Loc FROM Cards c JOIN Localizations_enUS l ON c.TitleId = l.LocId WHERE c.GrpId IN ($in_clause);"

# abilityId → name, category, text, owning cards
[group('lookup')]
ability id:
    #!/usr/bin/env bash
    set -euo pipefail
    db=$(echo "{{arena_db}}"/Raw_CardDatabase_*.mtga)
    if [ ! -f "$db" ]; then echo "Arena DB not found at {{arena_db}}" >&2; exit 1; fi
    echo "--- Info ---"
    sqlite3 -header "$db" "SELECT a.Id, COALESCE(l.Loc, '(no name)') AS Name, a.Category, a.SubCategory, a.IsIntrinsicAbility FROM Abilities a LEFT JOIN Localizations_enUS l ON a.TextId = l.LocId WHERE a.Id = {{id}};"
    echo "--- Text ---"
    sqlite3 "$db" "SELECT l.Loc FROM Abilities a JOIN Localizations_enUS l ON a.TextId = l.LocId WHERE a.Id = {{id}};"
    echo "--- Owners (first 10) ---"
    sqlite3 "$db" "SELECT c.GrpId, l.Loc FROM Cards c JOIN Localizations_enUS l ON c.TitleId = l.LocId WHERE c.AbilityIds LIKE '%{{id}}:%' OR c.HiddenAbilityIds LIKE '%{{id}}:%' LIMIT 10;"

# card name → grpId (exact match)
[group('lookup')]
card-grp name:
    #!/usr/bin/env bash
    set -euo pipefail
    db=$(echo "{{arena_db}}"/Raw_CardDatabase_*.mtga)
    if [ ! -f "$db" ]; then echo "Arena DB not found at {{arena_db}}" >&2; exit 1; fi
    escaped=$(echo "{{name}}" | sed "s/'/''/g")
    sqlite3 "$db" "SELECT c.GrpId, l.Loc FROM Cards c JOIN Localizations_enUS l ON c.TitleId = l.LocId WHERE l.Formatted = 1 AND l.Loc = '$escaped';"

# validate all card names in a .pzl puzzle file against Arena's card DB
[group('lookup')]
puzzle-check file:
    #!/usr/bin/env bash
    set -euo pipefail
    db=$(echo "{{arena_db}}"/Raw_CardDatabase_*.mtga)
    if [ ! -f "$db" ]; then echo "Arena DB not found at {{arena_db}}" >&2; exit 1; fi
    if [ ! -f "{{file}}" ]; then echo "Puzzle file not found: {{file}}" >&2; exit 1; fi
    cards=$(grep -E '^(human|ai)(battlefield|hand|library|graveyard|exile)=' "{{file}}" \
        | sed 's/^[^=]*=//' | tr ';' '\n' | sed 's/^ *//;s/ *$//' | grep -v '^$' \
        | sed 's/|.*//' \
        | grep -Ev '^(Plains|Island|Swamp|Mountain|Forest)$' | sort -u)
    fail=0
    while IFS= read -r name; do
        escaped=$(echo "$name" | sed "s/'/''/g")
        grp=$(sqlite3 "$db" "SELECT c.GrpId FROM Cards c JOIN Localizations_enUS l ON c.TitleId = l.LocId WHERE l.Formatted = 1 AND l.Loc = '$escaped' LIMIT 1;")
        if [ -z "$grp" ]; then echo "FAIL  $name"; fail=1; else echo "  OK  $name ($grp)"; fi
    done <<< "$cards"
    [ "$fail" -eq 0 ] && echo "All cards valid." || { echo "Missing cards — puzzle will crash."; exit 1; }

# card name → Forge script path (fuzzy: lowercased, spaces/punctuation → underscore)
[group('lookup')]
card-script name:
    #!/usr/bin/env bash
    set -euo pipefail
    slug=$(echo "{{name}}" | tr '[:upper:]' '[:lower:]' | sed "s/'//g" | sed "s/[^a-z0-9]/_/g" | sed "s/__*/_/g" | sed "s/^_//;s/_$//")
    first=${slug:0:1}
    path="{{cardsfolder}}/$first/$slug.txt"
    if [ -f "$path" ]; then
        echo "$path"
    else
        # fuzzy fallback: glob for partial match
        hits=$(find "{{cardsfolder}}" -iname "*${slug}*" -type f 2>/dev/null)
        if [ -n "$hits" ]; then
            echo "$hits"
        else
            echo "No script found for '{{name}}' (slug: $slug)" >&2; exit 1
        fi
    fi
