# Contributing

## Ways to help

**Play and report bugs.** Most valuable. Play a game, hit something weird,
[open an issue](https://github.com/delebedev/leyline/issues).

**Fix an issue.** Browse [open issues](https://github.com/delebedev/leyline/issues),
pick one. Bug fixes with a test and a short screen capture showing the fix get merged fastest.

**Add a mechanic.** [catalog.yaml](docs/catalog.yaml) tracks what works and what's missing.
Pick a `status: missing` mechanic and implement it.

## Setup

```bash
git clone --recursive https://github.com/delebedev/leyline.git
cd leyline
just bootstrap   # everything — submodules, forge, build, seed DB
just serve        # verify it runs
```

## Branching & PRs

- **Never commit to `main` directly.** Always branch + PR.
- Branch naming: `feat/<topic>`, `fix/<topic>`, `refactor/<topic>`.
- Run `just test-gate` before opening a PR.
- CI runs on maintainer infra — a maintainer triggers it after review.

## Changelog

Add a bullet to the `[Unreleased]` section of `CHANGELOG.md` for every
user-visible change. Group under: Protocol, Engine, Launcher, Puzzles, Docs.

## Documentation

If your PR changes protocol behavior, mechanic support, or architecture —
update the relevant doc in the same PR. Don't leave it for a follow-up.

Standalone docs have `read_when` YAML frontmatter. If you add a new doc to
`docs/`, include frontmatter. See `docs/principles-documentation.md` for the
full strategy.

## Content rules

- **No captured server data.** No server responses, card databases, or network captures.
  Test fixtures must be synthetic.
- **Interop identifiers are fine.** grpIds, set codes, type numbers, loc keys.
- **Tone:** "reimplemented protocol" not "reverse-engineered". "Local server" not "private server".

## AI contributors

Built with AI agents. [CLAUDE.md](CLAUDE.md) is the deep guide — it doubles
as the contributor handbook for both agents and humans.

## License

GPL-3.0, inherited from [Forge](https://github.com/Card-Forge/forge).
