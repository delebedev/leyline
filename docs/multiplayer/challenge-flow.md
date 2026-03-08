# Challenge Flow

Observations from proxy recording `2026-03-08_19-30-44-CHALLENGE-JOINER-SEAT2` (garnett joins forgetest's challenge) and `2026-03-08_19-44-CHALLENGE-STARTER-SEAT1` (forgetest's perspective).

## Protocol Summary

Challenge lifecycle uses FD CmdTypes 3000-3012. All C2S commands have **empty payloads** — state is server-side. The server pushes `ChallengeNotification` with full state on every transition.

## CmdTypes

| CmdType | Name | Direction |
|---------|------|-----------|
| 3000 | ChallengeJoin | C2S |
| 3001 | ChallengeCreate | C2S |
| 3002 | ChallengeSendMessage | C2S |
| 3003 | ChallengeExit | C2S |
| 3004 | ChallengeInvite | C2S |
| 3005 | ChallengeClose | C2S |
| 3006 | ChallengeReconnectAll | C2S |
| 3007 | ChallengeKick | C2S |
| 3008 | ChallengeReady | C2S |
| 3009 | ChallengeUnready | C2S |
| 3010 | ChallengeSetSettings | C2S |
| 3011 | ChallengeIssue | C2S |
| 3012 | ChallengeStartLaunchCountdown | C2S |
| 19000 | ChallengeNotification | S2C push |

## Observed Flow (garnett joining forgetest's challenge)

### Phase 1: garnett creates own challenge, then closes it

```
seq 109  C2S  ChallengeCreate         (empty payload)
seq 110  S2C  ACK                     (empty)
seq 111  C2S  LogBusinessEvents       ChallengeCreated, id=3d2d12db, owner=garnett, status=Setup
seq 157  C2S  ChallengeClose          (empty)
seq 159  S2C  ChallengeNotification   type=ChallengeClose, id=3d2d12db
```

### Phase 2: garnett joins forgetest's challenge

```
seq 302  C2S  ChallengeJoin           (empty — challengeId from MQTT invite, not FD)
seq 303  S2C  ACK                     (empty)
seq 312  C2S  ChallengeReady          (empty — deckId via MQTT or implicit)
seq 316  S2C  ChallengeNotification   type=ChallengeStatus — full lobby state (see below)
seq 317  S2C  ChallengeNotification   type=ChallengeStartLaunchCountdown, countdownSeconds=10
seq 323  C2S  ChallengeIssue          (empty — acknowledges match start)
seq 326  S2C  MatchCreated            EventId=DirectGame, YourSeat=2, both players + cosmetics
seq 327  S2C  ChallengeNotification   type=ChallengeStatus — both players ready=false (post-issue reset)
seq 328  C2S  ChallengeUnready
```

### Phase 3: post-game

```
seq 342  C2S  LogBusinessEvents       MatchId, ChallengeId, SeatId=2, GameNumber=1
seq 467  S2C  ChallengeNotification   type=ChallengeClose — challenge ended
seq 566  C2S  ChallengeReconnectAll   (login refresh, no active challenges)
```

## ChallengeNotification: ChallengeStatus payload

Full lobby state pushed after both players ready:

```json
{
  "challengeNotificationType": "ChallengeStatus",
  "challenge": {
    "challengeId": "651fb477-...",
    "challengeTitle": "forgetest's Challenge",
    "challengeName": "DirectGame",
    "playFirst": "Random",
    "winCondition": "SingleElimination",
    "ownerPlayerId": "AJC63SL...",
    "players": [
      {
        "playerId": "AJC63SL...",
        "displayName": "forgetest#15230",
        "ready": true,
        "presence": "Available",
        "cosmetics": { "avatar": "Avatar_Basic_AjaniGoldmane", ... },
        "deckTileId": 75472
      },
      {
        "playerId": "OZ2H676...",
        "displayName": "garnett#01186",
        "ready": true,
        "presence": "Available",
        "cosmetics": { "avatar": "Avatar_Basic_Adventurer", "sleeve": "...", "pet": "VOW_Bat.Level1", ... },
        "deckTileId": 93715
      }
    ]
  }
}
```

## Key Observations

1. **All C2S challenge commands have empty payloads.** ChallengeId and deckId travel via MQTT (social SDK game messages), not FD. FD handles state transitions only.

2. **ChallengeReconnectAll** (3006) fires on login — client checks for pending challenges. Currently stubbed in leyline (returns empty).

3. **MatchCreated** is identical to queue-paired matches — same `MatchInfoV3` structure with `EventId: "DirectGame"` instead of a queue event name.

4. **Seat assignment** is server-decided. Challenge owner (forgetest) got seat 1, joiner (garnett) got seat 2. `YourSeat` in MatchCreated tells each client.

5. **Countdown** — server pushes `ChallengeStartLaunchCountdown` with `countdownSeconds: 10` before `MatchCreated`. Client shows a 10s timer in the lobby.

6. **Legacy CmdTypes 500-501** (`DC_IssueChallenge`/`DC_CancelChallenge`) exist but are superseded by the 3000-series.

## What We Need for Leyline

To support direct challenges without MQTT:

- **ChallengeCreate** — allocate challenge room, return id
- **ChallengeJoin** — add player to room (we know both players locally)
- **ChallengeReady** — mark player ready with deck
- **ChallengeNotification** pushes — ChallengeStatus with lobby state, ChallengeStartLaunchCountdown
- **ChallengeIssue** — trigger match creation
- **MatchCreated** push — already implemented for bot matches, just needs `EventId: "DirectGame"` and correct `YourSeat`

Skip: ChallengeInvite (MQTT), ChallengeSetSettings, ChallengeKick, ChallengeSendMessage (chat).
