# engine

Forge bridge and GRE match-session engine. Native TCP and web transports both feed parsed GRE messages into `leyline.match.MatchHandler`; ports and byte framing live outside this module.

- **Proto:** `src/main/proto/messages.proto` — canonical client protobuf schema.
- **Forge coupling is structural:** `PlayerController` extends `PlayerControllerHuman`; `GameBootstrap` constructs Forge `Match`, `Game`, and `Deck`.
- **Proto pervasive:** GRE protobuf is the output format, not an anti-corruption boundary.

## Packages

```
bridge/      Forge adapter and engine-thread interaction surface.
config/      MatchConfig and runtime match config.
game/        Engine state -> GRE protobuf mapping, annotations, data, generators.
infra/       Message sinks and output plumbing.
match/       MatchHandler, MatchSession, FamiliarSession, combat/targeting/mulligan/puzzle handlers.
protocol/    GRE handshake/proto dump helpers. TCP frame codecs live in native.protocol.
tooling/     Headless harnesses, simclient, test tooling.
```

ArchUnit enforces internal layering. Keep transport identity out of engine: engine advances a match from parsed GRE messages; native binds TCP; web bridges WebSocket to the handler in-process. Concrete rules live in `PackageLayeringTest` (`engine/src/test/kotlin/leyline/architecture/`); match-handler constructor contracts are enforced alongside it in `HandlerConstructorContractTest`.

Read `docs/forge-api-concepts.md` before changing Forge-facing code.
