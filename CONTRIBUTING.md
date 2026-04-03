# Contributing

## Ways to help

**Play and report bugs.** Most valuable. Play a game, hit something weird,
[open an issue](https://github.com/delebedev/leyline/issues).

**Fix an issue.** Browse [open issues](https://github.com/delebedev/leyline/issues),
pick one. Bug fixes with a test and a short screen capture showing the fix get merged fastest.

**Add a mechanic.** [catalog.yaml](docs/catalog.yaml) tracks what works and what's missing.

## Setup

```bash
git clone --recursive https://github.com/delebedev/leyline.git
cd leyline
just bootstrap   # everything — submodules, forge, build, seed DB
just serve        # verify it runs
```

## Workflow

Branch off `main`. Run `just test-gate` before opening a PR.
CI runs on maintainer infra — a maintainer triggers it after review.

## Content rules

- **No captured server data.** No server responses, card databases, or network captures.
  Test fixtures must be synthetic.
- **Interop identifiers are fine.** grpIds, set codes, type numbers, loc keys.

## AI contributors

Built with AI agents. [CLAUDE.md](CLAUDE.md) is the deep guide.

## License

GPL-3.0, inherited from [Forge](https://github.com/Card-Forge/forge).
