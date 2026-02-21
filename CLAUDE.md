# forge-nexus

Client compat layer — stubs/proxies the client's Front Door + Match Door so the game client connects to Forge's engine.

- **Transport:** raw Netty TLS TCP (not HTTP — client uses 6-byte framing + protobuf)
- **Depends on:** forge-web (game bridges, bootstrap) — never reverse the dependency
- **Proto:** `src/main/proto/messages.proto` — client protobuf schema (from MtgaProto project)
- **Card data:** `CardDb.kt` reads the client's local SQLite for grpId, types, mana cost
- **Server modes:** `just serve` (hybrid, main dev), `just serve-stub` (offline), `just serve-proxy` (passthrough), `just serve-replay`

## Testing

TestNG groups control what runs. Use `just --list` for all targets.

| Target | Group | What | Speed |
|--------|-------|------|-------|
| `just test-unit` | `unit` | Pure logic, no engine | ~1s |
| `just test-conformance` | `conformance` | Wire shape vs client patterns | ~5s |
| `just test-integration` | `integration` | Full engine boot (includes conformance) | ~30s |
| `just test-gate` | all three | Pre-commit gate (skips `recording`) | ~30s |
| `just test-one Foo` | — | Single class by name | varies |
| `just test` | ungrouped | Everything (may hit pre-existing init issues) | slow |

**Before committing:** run `just test-gate`.

**RULE: Every test class MUST have at least one group.** Annotate with `@Test(groups = ["unit"])`, `@Test(groups = ["conformance"])`, `@Test(groups = ["integration"])`, etc. Tests without a group are invisible to `just test-unit`/`test-conformance`/`test-integration` and only run via `just test` (which is slow and unreliable). If a test is pure logic with no engine, use `unit`. If it boots GameBridge, use `integration`.

**`recording` group** requires client capture files — skip in CI/normal dev.

## Debug Panel & API

Debug server on `:8090` (auto-starts with `just serve`). Full endpoint reference: `docs/debug-api.md`.
