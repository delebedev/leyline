# Plan: AccountServer

Replace MockWasServer with a real account system. Same WAS protocol the Arena client expects, backed by SQLite. Supports registration, login, profile. Runs in all modes (`just serve` uses dev seed, `just serve-proxy` keeps forwarding to Wizards).

## Module: `:account`

New Gradle subproject. Zero forge/netty/protobuf deps.

```
account/
  build.gradle.kts          # Ktor, Exposed, SQLite, jBCrypt, kotlinx-serialization
  src/main/kotlin/leyline/account/
    AccountServer.kt         # Ktor HTTPS server, start/stop lifecycle
    AccountRoutes.kt         # route definitions (real + stubs)
    AccountStore.kt          # Exposed table + queries
    TokenService.kt          # JWT build + refresh token logic
  src/test/kotlin/leyline/account/
    AccountStoreTest.kt      # DB layer
    AccountServerTest.kt     # HTTP layer (embedded Ktor testApplication)
```

### Dependencies (`:account` module)

| Lib | Purpose |
|-----|---------|
| `ktor-server-netty` | HTTP engine (reuses existing Netty dep) |
| `ktor-server-content-negotiation` | JSON request/response |
| `ktor-serialization-kotlinx-json` | Wire format |
| `ktor-server-status-pages` | Error response shape |
| `ktor-network-tls-certificates` | Self-signed TLS (dev) |
| `exposed-core` + `exposed-jdbc` | DB (already in project) |
| `sqlite-jdbc` | Driver (already in project) |
| `jbcrypt` (org.mindrot:jbcrypt:0.4) | Password hashing |
| `logback-classic` | Logging (already in project) |

Root module adds: `implementation(project(":account"))`.

## Database: `accounts` table (in existing `player.db`)

```sql
CREATE TABLE accounts (
  account_id  TEXT PRIMARY KEY,        -- UUID, becomes wotc-acct claim
  persona_id  TEXT NOT NULL UNIQUE,    -- UUID, becomes sub claim
  email       TEXT NOT NULL UNIQUE,    -- login username
  display_name TEXT NOT NULL,          -- "ForgePlayer#00001"
  password_hash TEXT NOT NULL,         -- bcrypt
  country     TEXT NOT NULL DEFAULT 'US',
  dob         TEXT NOT NULL,           -- ISO date "1990-01-15"
  created_at  TEXT NOT NULL DEFAULT (datetime('now'))
);
```

Links to existing `players` table by convention: `accounts.persona_id` = `players.player_id`. Registration creates both rows.

## Endpoints

### Real handlers

| Method | Path | Behavior |
|--------|------|----------|
| `POST` | `/auth/oauth/token` | Form-encoded. `grant_type=password`: validate email+password, return tokens. `grant_type=refresh_token`: validate refresh JWT, return fresh tokens. Error: `{code:401, grpcCode:"16", error:"INVALID ACCOUNT CREDENTIALS"}` |
| `POST` | `/accounts/register` | JSON body: `{displayName, email, password, country, dateOfBirth, acceptedTC, ...}`. Validate email uniqueness, hash password, insert account + player, return `{accountID, displayName, tokens:{access_token, refresh_token}}`. Error: `{code:422, grpcCode:"6", error:"INVALID EMAIL"}` for duplicates. |
| `GET` | `/profile` | Read Bearer JWT, look up account, return `{accountID, personaID, displayName, email, countryCode, createdAt, emailVerified, gameID:"arena", ...}` |

### Stubs (200 + minimal body)

| Method | Path | Response |
|--------|------|----------|
| `POST` | `/accounts/requires-age-gate` | `{requiresAgeGate: false}` |
| `POST` | `/accounts/moderate` | 200, empty body |
| `GET` | `/xsollaconnector/client/skus` | `{items:[]}` |

### Infrastructure

| Method | Path | Response |
|--------|------|----------|
| `POST` | `/api/doorbell/api/v2/ring` | `{FdURI:"<fdHost>", BundleManifests:[]}` |
| `*` | `/*` (catch-all) | `{}` + warn log |

### Proxy mode

When `upstreamWas` is set, all routes forward to upstream (same as today). Doorbell rewrites `FdURI`. Logs to `was-frames.jsonl`.

## JWT shape

Match real server claims (from recordings):

```json
{
  "aud": "<clientId>",
  "exp": 1772755294,
  "iat": 1772754334,
  "iss": "<accountId>",
  "sub": "<personaId>",
  "wotc-acct": "<accountId>",
  "wotc-name": "ForgePlayer#00001",
  "wotc-domn": "wizards",
  "wotc-game": "arena",
  "wotc-flgs": 0,
  "wotc-rols": ["MDNALPHA"],
  "wotc-prms": [],
  "wotc-scps": ["first-party"],
  "wotc-pdgr": "<personaId>",
  "wotc-sgts": [],
  "wotc-socl": {},
  "wotc-cnst": 0
}
```

`alg:none` for now. Structure ready for RS256 via BouncyCastle later.

Access token: 16 min expiry. Refresh token: 14 day expiry (matches real server).

## Dev seed

On first boot, if no accounts exist in DB:
- Create account: `forge@local` / password `forge` / displayName `ForgePlayer#00001`
- Reuse existing hardcoded `playerId` (`9da3ee9f-...`) as `persona_id` so existing `players` row + decks still work
- Log: `AccountServer: dev account seeded (forge@local / forge)`

## Wiring changes

### LeylineMain.kt
- `import leyline.account.AccountServer` replaces `MockWasServer`
- `buildWasServer()` → `buildAccountServer()`, passes `playerDbPath` for shared DB
- Same lifecycle: `start()` / `stop()` in shutdown hook
- Proxy mode: `AccountServer(upstreamWas=..., upstreamDoorbell=...)`
- Banner: "AccountServer" instead of "Mock WAS"

### MatchConfig.kt
- Rename `wasPort` → `accountPort` (keep TOML key `was_port` for compat, add `account_port` alias)

### services.conf
- No change needed (client still points to `localhost:9443`)

### justfile
- No change needed (`just serve` / `just serve-proxy` work as before)

## Test plan

### AccountStoreTest (UnitTag)
- Insert account, find by email
- Duplicate email → error
- Password verify (bcrypt round-trip)
- Display name discriminator generation

### AccountServerTest (UnitTag)
- Ktor `testApplication` (no real port, no TLS)
- Login success → JWT with correct claims
- Login wrong password → 401 + error shape
- Register → 201 + tokens + account in DB
- Register duplicate email → 422
- Profile with valid JWT → account data
- Doorbell → FdURI
- Stubs return expected shapes

## Implementation order

1. Gradle module setup (settings.gradle.kts, account/build.gradle.kts, version catalog)
2. AccountStore (Exposed table, queries, bcrypt)
3. TokenService (JWT builder with full claims)
4. AccountServer + AccountRoutes (Ktor server, all endpoints)
5. Tests (store + server)
6. Wire into LeylineMain, remove MockWasServer
7. test-gate green
