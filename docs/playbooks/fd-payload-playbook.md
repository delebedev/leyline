# FD Payload Investigation Playbook

How to analyze Front Door protocol traffic from proxy recordings.

## Prerequisites

- A proxy recording: `just serve-proxy`, connect client, play through the flow you want to capture
- Session data lands in `recordings/<timestamp>/capture/fd-frames.jsonl`
- Tools auto-detect the latest session

## Quick orientation

```bash
# What happened in this session?
just fd-summary

# Last 30 frames (table: seq, dir, type, cmd, size, preview)
just fd-tail 30

# Request/response pairs matched by transactionId
just fd-pairs
```

## Finding specific traffic

```bash
# Search by CmdType name or payload content (case-insensitive)
just fd-search Deck
just fd-search Carousel
just fd-search MWM_StandardPauper

# Show a specific frame (header + pretty-printed JSON)
just fd-show 92
```

## Extracting response payloads

`fd-response` gets the S2C response for a CmdType — by number or name substring.
Stdout is clean JSON; header goes to stderr. Pipe directly to jq.

```bash
# By CmdType number
just fd-response 624 | jq '.Events | length'

# By name substring (case-insensitive)
just fd-response Queue | jq '.[].Id'
just fd-response Preferences | jq 'keys'

# Full event object for a specific event
just fd-response 624 | jq '.Events[] | select(.InternalEventName == "PremierDraft_TMT_20260303")'

# All draft events
just fd-response 624 | jq '[.Events[] | select(.FormatType == "Draft") | .InternalEventName]'

# Course list
just fd-response 623 | jq '[.Courses[] | {event:.InternalEventName, module:.CurrentModule}]'
```

## Cross-session analysis

`fd-response-all` emits one JSONL line per session (payload wrapped with `_session` metadata).
Use `jq -s` (slurp) to aggregate.

```bash
# Did queue config ever change?
just fd-response-all 1910 | jq -r '[.payload[].Id] | sort | join(",")' | sort -u

# All unique draft event names across all sessions
just fd-response-all 624 | jq -r '.payload.Events[] | select(.FormatType=="Draft") | .InternalEventName' | sort -u

# Which events appeared/disappeared between earliest and latest session?
diff <(just fd-response-all 624 | head -1 | jq '[.payload.Events[].InternalEventName] | sort') \
     <(just fd-response-all 624 | tail -1 | jq '[.payload.Events[].InternalEventName] | sort')

# Field values grouped by blade behavior
just fd-response 624 | jq '[.Events[] | {name:.InternalEventName, blade:(.EventUXInfo.EventBladeBehavior // "none"), prio:.EventUXInfo.DisplayPriority}] | group_by(.blade) | .[] | {blade: .[0].blade, events: [.[] | "\(.prio) \(.name)"] | sort}'

# Check if a field is stable across sessions for a given event
just fd-response-all 624 | jq -r '{s:._session, prio: [.payload.Events[] | select(.InternalEventName=="Ladder") | .EventUXInfo.DisplayPriority][0]} | "\(.s) \(.prio)"'
```

## Raw payload access (by seq number)

`just fd-raw <seq>` outputs pure JSON — no headers, pipe-friendly.
Use when you already know the seq from `fd-pairs` or `fd-tail`.

```bash
just fd-raw 56 | jq 'keys'
just fd-raw 56 | jq '.PreconDecks | length'

# Deck upsert: request vs response field diff
diff <(just fd-raw 145 | jq '.Summary | keys') <(just fd-raw 146 | jq '.Summary | keys')

# FormatLegalities: which formats is a deck legal in?
just fd-raw 146 | jq '.FormatLegalities | to_entries | map(select(.value == true)) | .[].key'
```

## Finding unhandled CmdTypes

After a playthrough on stub server:

```bash
grep UNHANDLED logs/leyline.log | sort -u
```

This shows every CmdType the client sent that we don't handle yet.

## Extracting golden JSON for a new handler

When adding a handler for a CmdType we don't serve yet:

```bash
# 1. Find the request/response pair
just fd-pairs | grep PreconDecks

# 2. Check request shape
just fd-raw 42 | jq .

# 3. Extract response as golden file
just fd-raw 56 | jq . > src/main/resources/fd-golden/precon-decks.json

# 4. Validate it round-trips
cat src/main/resources/fd-golden/precon-decks.json | jq 'keys'
```

## Comparing sessions

`fd-response-all` covers most cross-session queries (see above).
For lower-level access to a specific older session:

```bash
# List all sessions with FD captures
ls -td recordings/*/capture/fd-frames.jsonl

# Use jq directly on a specific session's raw frames
cat recordings/2026-03-05_20-02-46/capture/fd-frames.jsonl | \
  jq -r 'select(.dir == "C2S") | "\(.seq) \(.cmdTypeName // .cmdType)"'
```

## Match flow sequence

Typical match join captured in proxy:

```
C2S  Event_Join(600)         {"EventName":"MWM_StandardPauper_20260303",...}
S2C  RESPONSE                {"CurrentModule":"Joined",...}
C2S  Event_SetDeckV2(622)    {"EventName":"...","Summary":{...},"Deck":{...}}
S2C  RESPONSE                (ack)
C2S  Event_EnterPairing(603) {"EventName":"..."}
S2C  RESPONSE                {"CurrentModule":"CreateMatch","Payload":"Success"}
S2C  RESPONSE (push)         {"Type":"MatchCreated","MatchInfoV3":{...}}
```

## Wire format reference

Each FD TCP message: `[2-byte version] [4-byte LE payload length] [protobuf payload]`

Envelope types:
- **Cmd** (C2S command, S2C push): CmdType + transactionId + JSON/proto payload
- **Request** (C2S newer path): RequestType + key + transactionId + payload
- **Response** (S2C reply): transactionId + JSON/proto payload

Full schema: `~/src/mtga-internals/docs/fd-envelope-proto.md`
