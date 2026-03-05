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

## Digging into response shapes with jq

`just fd-raw <seq>` outputs pure JSON — no headers, pipe-friendly.

```bash
# Top-level keys of a response
just fd-raw 56 | jq 'keys'

# Precon deck count and first deck's structure
just fd-raw 56 | jq '.PreconDecks | length'
just fd-raw 56 | jq '.PreconDecks[0] | keys'
just fd-raw 56 | jq '.PreconDecks[0].Summary | keys'

# Carousel banner shape
just fd-raw 92 | jq '.[0]'

# All event names from Event_Join requests
just fd-search Event_Join   # find seq numbers, then:
just fd-raw 271 | jq '.EventName'

# Deck upsert: request vs response field diff
diff <(just fd-raw 145 | jq '.Summary | keys') <(just fd-raw 146 | jq '.Summary | keys')

# Extract a field across all decks
just fd-raw 31 | jq '.DeckSummariesV2[:3] | .[].Name'

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

The tool always picks the latest session. To inspect an older one, use the JSONL directly:

```bash
# List all sessions with FD captures
ls -td recordings/*/capture/fd-frames.jsonl

# Use jq directly on a specific session
cat recordings/2026-03-05_20-02-46/capture/fd-frames.jsonl | \
  jq -r 'select(.dir == "C2S") | "\(.seq) \(.cmdTypeName // .cmdType)"'

# Count C2S vs S2C
cat recordings/2026-03-05_20-02-46/capture/fd-frames.jsonl | \
  jq -r '.dir' | sort | uniq -c
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
