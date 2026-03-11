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

## Disclaimer

This project provides a local game server for personal playtesting. It requires a legally obtained copy of Magic: The Gathering Arena. It is not affiliated with, endorsed by, or connected to Wizards of the Coast, Hasbro, or any of their affiliates. "Magic: The Gathering" is a trademark of Wizards of the Coast LLC. This project does not distribute any copyrighted game assets.

## License

GPL-3.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
