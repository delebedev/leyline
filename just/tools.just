# Tool CLIs — catchall passthrough
# Usage: just wire tail 5, just tape session list, just ralph --max 3

[group('tools')]
wire *ARGS:
    @python3 tools/wire/wire.py {{ARGS}}

[group('tools')]
tape *ARGS:
    @python3 tools/tape/tape.py {{ARGS}}

# play-fix-play loop: agent plays MTGA, fixes what breaks, repeats
[group('tools')]
ralph *ARGS:
    @bin/ralph {{ARGS}}

# all unique grpIds in a session → card names
[group('tools')]
cards-in-session session:
    #!/usr/bin/env bash
    set -euo pipefail
    dir="{{project_dir}}/recordings"
    # resolve session: full path, relative, or substring match
    if [ -d "{{session}}" ]; then md="{{session}}/md-frames.jsonl"
    elif [ -d "$dir/{{session}}" ]; then md="$dir/{{session}}/md-frames.jsonl"
    else md=$(ls -d "$dir"/*"{{session}}"*/md-frames.jsonl 2>/dev/null | head -1); fi
    if [ ! -f "$md" ]; then echo "No md-frames.jsonl for session '{{session}}'" >&2; exit 1; fi
    grpids=$(jq -r '[.objects[]?.grpId // empty] | .[]' "$md" | sort -un | tr '\n' ',' | sed 's/,$//')
    if [ -z "$grpids" ]; then echo "No grpIds found" >&2; exit 1; fi
    db=$(echo "{{arena_db}}"/Raw_CardDatabase_*.mtga)
    if [ ! -f "$db" ]; then echo "Arena DB not found" >&2; exit 1; fi
    sqlite3 "$db" "SELECT c.GrpId, l.Loc FROM Cards c JOIN Localizations_enUS l ON c.TitleId = l.LocId WHERE c.GrpId IN ($grpids) ORDER BY l.Loc;"
