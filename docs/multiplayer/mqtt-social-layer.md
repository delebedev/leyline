# MQTT Social Layer

Arena's social features (friends, presence, chat, challenge invites) run on a separate MQTT message bus, independent of the Front Door TCP protocol.

## Architecture

| Feature | Transport | Notes |
|---------|-----------|-------|
| Friend list CRUD | HTTPS REST | `api.platform.wizards.com` endpoints |
| Presence (set own) | HTTPS REST | `PresenceBaseEndpoint` |
| Presence (receive) | MQTT pub/sub | Per-friend topic subscriptions |
| Chat / DMs | MQTT pub/sub | Topic-based messaging |
| Challenge invites | MQTT pub/sub | `ChallengeMessage` JSON payloads |
| Platform presence | Epic Online Services SDK | Separate from MQTT entirely |

## MQTT Broker

- Library: **MQTTnet** (managed client, bundled DLLs)
- Port **8883** (TLS) or **8084** (WSS at `/mqtt`)
- Auth: access token (domainId + accountId + token provider)
- TLS cert validation disabled in client code
- Broker URL from `Configuration.ProdBaseWizMessageBusUrl` — likely `api.platform.wizards.com` or sibling

## Presence Model

Four states: `Offline` (0), `Away` (1), `Busy` (2), `Available` (3).

Set via REST, received via MQTT per-friend topic subscriptions. Friend list changes trigger topic subscribe/unsubscribe.

## Challenge Messages (MQTT side)

Challenge flow uses MQTT for real-time peer notifications alongside FD for authoritative state. JSON `ChallengeMessage` payloads with types:

| Type | Value | Purpose |
|------|-------|---------|
| GeneralUpdate | 0 | Challenge state sync |
| IncomingInvite | 1 | Invite notification to opponent |
| CancelChallenge | 2 | Cancellation |
| RespondToChallenge | 3 | Accept/reject |
| PlayerUpdate | 4 | Player status change |

Sent via `SendGameMessage()` which publishes to MQTT topics.

## Leyline Implications

We don't need to implement MQTT for the initial multiplayer milestone. Challenge invites can be handled server-side (we control both players). Friends list / presence is cosmetic — the client tolerates empty responses.

If we ever want friend presence or cross-device challenge invites, we'd need an MQTT broker (Mosquitto or similar) and the topic subscription model.
