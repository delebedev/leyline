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

# abilityId → owning card + ability text
[group('lookup')]
ability id:
    #!/usr/bin/env bash
    set -euo pipefail
    db=$(echo "{{arena_db}}"/Raw_CardDatabase_*.mtga)
    if [ ! -f "$db" ]; then echo "Arena DB not found at {{arena_db}}" >&2; exit 1; fi
    echo "--- Owner ---"
    sqlite3 "$db" "SELECT c.GrpId, l.Loc FROM Cards c JOIN Localizations_enUS l ON c.TitleId = l.LocId WHERE c.AbilityIds LIKE '%{{id}}:%' OR c.HiddenAbilityIds LIKE '%{{id}}:%';"
    echo "--- Text ---"
    sqlite3 "$db" "SELECT l.Loc FROM Abilities a JOIN Localizations_enUS l ON a.TextId = l.LocId WHERE a.Id = {{id}};"

# card name → grpId (exact match)
[group('lookup')]
card-grp name:
    #!/usr/bin/env bash
    set -euo pipefail
    db=$(echo "{{arena_db}}"/Raw_CardDatabase_*.mtga)
    if [ ! -f "$db" ]; then echo "Arena DB not found at {{arena_db}}" >&2; exit 1; fi
    escaped=$(echo "{{name}}" | sed "s/'/''/g")
    sqlite3 "$db" "SELECT c.GrpId, l.Loc FROM Cards c JOIN Localizations_enUS l ON c.TitleId = l.LocId WHERE l.Formatted = 1 AND l.Loc = '$escaped';"

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
