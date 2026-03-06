# FD frame inspection tools
# Imported by justfile — all variables available from parent.

_fd := "python3 tooling/scripts/fd-inspect.py"

# last N frames, formatted table
[group('fd')]
fd-tail n="20":
    @{{_fd}} tail {{n}}

# search inside jsonPayload + cmdTypeName (case-insensitive)
[group('fd')]
fd-search term:
    @{{_fd}} search "{{term}}"

# show full payload for a specific seq number
[group('fd')]
fd-show seq:
    @{{_fd}} show {{seq}}

# raw JSON payload for a seq (pipe to jq)
[group('fd')]
fd-raw seq:
    @{{_fd}} raw {{seq}}

# unique cmdTypes with counts, C2S vs S2C breakdown
[group('fd')]
fd-summary:
    @{{_fd}} summary

# pair C2S requests with S2C responses by transactionId
[group('fd')]
fd-pairs:
    @{{_fd}} pairs

# S2C response payload for a cmdType (number or name substring, pipe to jq)
[group('fd')]
fd-response cmd:
    @{{_fd}} response "{{cmd}}"

# S2C response for a cmdType across ALL sessions (JSONL, pipe to jq -s)
[group('fd')]
fd-response-all cmd:
    @{{_fd}} response-all "{{cmd}}"

# CmdType coverage: handled in code vs observed in recordings
[group('fd')]
fd-coverage:
    @{{_fd}} coverage
