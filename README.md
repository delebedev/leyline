# Leyline

Open-source MTG Arena-compatible game server. Connects the real Arena client to [Forge](https://github.com/Card-Forge/forge)'s rules engine.

**Status:** Alpha. Core game loop works with simple creatures and AI opponent. Puzzle mode working. Lots still broken.

## Requirements

- JDK 17+
- MTG Arena client installed (for card database — not distributed)
- [just](https://github.com/casey/just) task runner

## Quick start

```bash
git clone --recursive https://github.com/delebedev/leyline.git
cd leyline
just install-forge   # build engine deps from submodule
just sync-proto      # generate proto from upstream
just build           # compile
just serve           # start server
```

## License

GPL-3.0 — see [LICENSE](LICENSE) and [LEGAL.md](LEGAL.md).
