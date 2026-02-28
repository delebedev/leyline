# Recording CLI & analysis tools
# Imported by forge-nexus/justfile — all variables available from parent.

# list discovered recording sessions (engine/proxy)
rec-list: (_require classpath) check-java
    @{{_nexus_cli}} forge.nexus.debug.RecordingCliKt list

# compact summary for one recording session directory (or session id from rec-list)
rec-summary session *args: (_require classpath) check-java
    @{{_nexus_cli}} forge.nexus.debug.RecordingCliKt summary "{{session}}"

# action timeline query (all actions)
rec-actions session limit="500": (_require classpath) check-java
    @{{_nexus_cli}} forge.nexus.debug.RecordingCliKt actions "{{session}}" --limit "{{limit}}"

# action timeline query (filtered; positional: session card [actor] [limit])
rec-actions-filtered session card actor="" limit="500": (_require classpath) check-java
    @{{_nexus_cli}} forge.nexus.debug.RecordingCliKt actions "{{session}}" --card "{{card}}" --actor "{{actor}}" --limit "{{limit}}"

# who played a specific card (name or grp id)
rec-who-played session card: (_require classpath) check-java
    @{{_nexus_cli}} forge.nexus.debug.RecordingCliKt who-played "{{session}}" --card "{{card}}"

# compare two recordings by compact action stream
rec-compare left right: (_require classpath) check-java
    @{{_nexus_cli}} forge.nexus.debug.RecordingCliKt compare "{{left}}" "{{right}}"

# --- Recording Analysis ---

# run SessionAnalyzer on a session (if analysis.json missing or --force)
rec-analyze session *args: (_require classpath) check-java
    @{{_nexus_cli}} forge.nexus.analysis.AnalysisCliKt analyze "{{session}}" {{args}}

# run analysis on all sessions missing analysis.json
rec-analyze-all: (_require classpath) check-java
    @{{_nexus_cli}} forge.nexus.analysis.AnalysisCliKt analyze-all

# show invariant violations (latest session if omitted)
rec-violations session="": (_require classpath) check-java
    @{{_nexus_cli}} forge.nexus.analysis.AnalysisCliKt violations {{session}}

# show cross-session mechanic coverage report
rec-mechanics: (_require classpath) check-java
    @{{_nexus_cli}} forge.nexus.analysis.AnalysisCliKt mechanics

# show summary + analysis of most recent session
rec-latest: (_require classpath) check-java
    @{{_nexus_cli}} forge.nexus.analysis.AnalysisCliKt latest
