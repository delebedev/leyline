# forge-nexus

MTGA client compat layer — stubs/proxies Arena's Front Door + Match Door so MTGA connects to Forge's engine.

- **Transport:** raw Netty TLS TCP (not HTTP — Arena uses 6-byte framing + protobuf)
- **Depends on:** forge-web (game bridges, bootstrap) — never reverse the dependency
- **Proto:** `src/main/proto/messages.proto` — Arena protobuf schema (from MtgaProto project)
- **Card data:** `CardDb.kt` reads Arena's local SQLite for grpId, types, mana cost
- **Server modes:** `just serve` (hybrid, main dev), `just serve-stub` (offline), `just serve-proxy` (passthrough), `just serve-replay`
