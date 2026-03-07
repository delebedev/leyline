# Recording CLI & analysis tools
# Imported by justfile — all variables available from parent.

# list discovered recording sessions (engine/proxy)
[group('recording')]
rec-list: (_require classpath) check-java
    @{{_cli}} leyline.debug.RecordingCliKt list

# compact summary for one recording session directory (or session id from rec-list)
[group('recording')]
rec-summary session *args: (_require classpath) check-java
    @{{_cli}} leyline.debug.RecordingCliKt summary "{{session}}"

# action timeline query (all actions)
[group('recording')]
rec-actions session limit="500": (_require classpath) check-java
    @{{_cli}} leyline.debug.RecordingCliKt actions "{{session}}" --limit "{{limit}}"

# action timeline query (filtered; positional: session card [actor] [limit])
[group('recording')]
rec-actions-filtered session card actor="" limit="500": (_require classpath) check-java
    @{{_cli}} leyline.debug.RecordingCliKt actions "{{session}}" --card "{{card}}" --actor "{{actor}}" --limit "{{limit}}"

# who played a specific card (name or grp id)
[group('recording')]
rec-who-played session card: (_require classpath) check-java
    @{{_cli}} leyline.debug.RecordingCliKt who-played "{{session}}" --card "{{card}}"

# turnInfo timeline: turn/phase/step/active/priority per GSM
[group('recording')]
rec-turninfo session: (_require classpath) check-java
    @{{_cli}} leyline.debug.RecordingCliKt turninfo "{{session}}"

# compare two recordings by compact action stream
[group('recording')]
rec-compare left right: (_require classpath) check-java
    @{{_cli}} leyline.debug.RecordingCliKt compare "{{left}}" "{{right}}"

# --- Recording Analysis ---

# run SessionAnalyzer on a session (if analysis.json missing or --force)
[group('recording')]
rec-analyze session *args: (_require classpath) check-java
    @{{_cli}} leyline.analysis.AnalysisCliKt analyze "{{session}}" {{args}}

# run analysis on all sessions missing analysis.json
[group('recording')]
rec-analyze-all: (_require classpath) check-java
    @{{_cli}} leyline.analysis.AnalysisCliKt analyze-all

# show invariant violations (latest session if omitted)
[group('recording')]
rec-violations session="": (_require classpath) check-java
    @{{_cli}} leyline.analysis.AnalysisCliKt violations {{session}}

# show cross-session mechanic coverage report
[group('recording')]
rec-mechanics: (_require classpath) check-java
    @{{_cli}} leyline.analysis.AnalysisCliKt mechanics

# show summary + analysis of most recent session
[group('recording')]
rec-latest: (_require classpath) check-java
    @{{_cli}} leyline.analysis.AnalysisCliKt latest

# all unique grpIds in a session → card names
[group('recording')]
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
