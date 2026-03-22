# Proto inspection & diffing tools
# Imported by justfile — all variables available from parent.

# re-decode FD raw frames → fd-frames.jsonl (fixes compressed payloads)
[group('proto')]
decode-golden dir="": (_require classpath) check-java
    #!/usr/bin/env bash
    set -euo pipefail
    d="{{dir}}"
    if [ -z "$d" ]; then
        d=$(ls -td recordings/*/capture 2>/dev/null | head -1)
        if [ -z "$d" ]; then echo "No captures found." >&2; exit 1; fi
    fi
    {{_cli}} leyline.debug.DecodeFdCaptureKt "$d"

# inspect a .bin template (no port kill — safe while server runs)
[group('proto')]
proto-inspect file=(templates / "mulligan-req-seat1.bin"): (_require classpath) check-java
    @{{_cli}} leyline.debug.InspectKt "{{file}}"

# decode Match Door payloads
[group('proto')]
proto-decode: (_require classpath) check-java
    @{{_cli}} leyline.debug.DecodeCaptureKt {{payloads}}

# save last S→C payload as template
[group('proto')]
proto-extract name="extracted": (_require classpath) check-java
    #!/usr/bin/env bash
    set -euo pipefail
    latest=$(ls -t {{payloads}}/S-C_*.bin 2>/dev/null | head -1)
    if [ -z "$latest" ]; then
        echo "No S→C payloads found in {{payloads}}" >&2; exit 1
    fi
    dest="{{templates}}/{{name}}.bin"
    cp "$latest" "$dest"
    echo "Saved $latest → $dest ($(wc -c < "$dest") bytes)"

# dump all payloads as text for diffing
[group('proto')]
proto-diff-prep: (_require classpath) check-java
    #!/usr/bin/env bash
    set -euo pipefail
    real_dir=/tmp/arena-diff/real
    mkdir -p "$real_dir"
    if [ ! -d "{{payloads}}" ]; then
        echo "No captures at {{payloads}}. Run: just serve-proxy" >&2; exit 1
    fi
    for f in {{payloads}}/S-C_*.bin; do
        [ -f "$f" ] || continue
        base=$(basename "$f" .bin)
        {{_cli}} leyline.debug.InspectKt "$f" > "$real_dir/$base.txt" 2>/dev/null
        echo "  $base.txt"
    done
    echo "Real payloads dumped to $real_dir/"

# diff stub output vs real captures
[group('proto')]
proto-diff:
    #!/usr/bin/env bash
    set -euo pipefail
    real=/tmp/arena-diff/real
    stub=/tmp/arena-dump
    [ -d "$real" ] || { echo "No real dumps. Run: just proto-diff-prep" >&2; exit 1; }
    [ -d "$stub" ] || { echo "No stub dumps. Run stub with ARENA_DUMP=1" >&2; exit 1; }
    echo "=== Real ($real) ==="
    ls -1 "$real"/*.txt 2>/dev/null | while read f; do echo "  $(basename $f)"; done
    echo ""
    echo "=== Stub ($stub) ==="
    ls -1 "$stub"/*.txt 2>/dev/null | while read f; do echo "  $(basename $f)"; done
    echo ""
    echo "--- diff example ---"
    echo "  diff $real/<name>.txt $stub/<name>.txt"

# compare our output vs real Arena captures structurally (no port kill)
[group('proto')]
proto-compare *args: (_require classpath) check-java
    @{{_cli}} leyline.conformance.CompareMainKt {{args}}

# trace an ID across all recorded payloads (no port kill — safe while server runs)
# dir: payloads dir (default: capture), or "engine" / "latest-engine" shortcuts
[group('proto')]
proto-trace id dir="": (_require classpath) check-java
    #!/usr/bin/env bash
    set -euo pipefail
    d="{{dir}}"
    if [[ -z "$d" ]]; then d="{{payloads}}"; fi
    if [[ "$d" == "engine" || "$d" == "latest-engine" ]]; then
        d="$(ls -dt "{{project_dir}}"/recordings/*/engine 2>/dev/null | head -1)"
        [[ -z "$d" ]] && { echo "No engine recording found"; exit 1; }
    fi
    {{_cli}} leyline.debug.TraceKt "{{id}}" "$d"

# decode a recording directory to structured JSONL (no port kill)
[group('proto')]
proto-decode-recording dir output="": (_require classpath) check-java
    @{{_cli}} leyline.recording.RecordingDecoderMainKt "{{dir}}" {{output}}

# decode + accumulate state snapshots from a recording (no port kill)
[group('proto')]
proto-accumulate dir output="": (_require classpath) check-java
    @{{_cli}} leyline.recording.RecordingDecoderMainKt "{{dir}}" --accumulate {{output}}

# priority analysis report for a recording (no port kill)
[group('proto')]
proto-priority dir: (_require classpath) check-java
    @{{_cli}} leyline.recording.RecordingDecoderMainKt "{{dir}}" --priority --seat 0

# annotation type variance profile across all proxy recordings (no port kill)
[group('proto')]
proto-annotation-variance *args: (_require classpath) check-java
    @{{_cli}} leyline.debug.AnnotationVarianceKt {{args}}

# decode + diff raw proto recording frames (lossless — no JSON intermediary)
# Usage: just conform-proto SearchReq 2026-03-21_22-05-00 [--gsid 52]
[group('proto')]
conform-proto *args: (_require classpath) check-java
    @{{_cli}} leyline.conformance.ProtoConformMainKt {{args}}

# segment variance: field presence/variance per mechanic across recordings
# Usage: just segment-variance [CastSpell] [--session 2026-03-21_22-05-00]
[group('proto')]
segment-variance *args: (_require classpath) check-java
    @{{_cli}} leyline.conformance.SegmentVarianceMainKt {{args}}

