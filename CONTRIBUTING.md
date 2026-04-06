# Contributing

## Ways to help

**Play and report bugs.** Most valuable. Play a game, hit something weird,
[open an issue](https://github.com/delebedev/leyline/issues).

**Fix an issue.** Browse [open issues](https://github.com/delebedev/leyline/issues),
pick one. Bug fixes with a test get merged fastest.

**Add a mechanic.** Bug fixes, focused mechanic support, and tests all help.

## Setup

```bash
git clone --recursive https://github.com/delebedev/leyline.git
cd leyline
just bootstrap   # submodules, forge, build, seed DB
just serve        # verify it runs
```

## Content rules

This project reimplements a protocol for interoperability. Keep it clean:

- **No third-party proprietary data.** No external service responses, vendor databases, or user data. Test fixtures must be synthetic or project-generated.
- **Client log snippets in bug reports** should come from local leyline sessions (`just serve`).
- **Interop identifiers are fine.** grpIds, set codes, type numbers, loc keys.
- **Tone:** "protocol implementation" or "local client-compatible server", not "private server".

## AI contributors

Built with AI agents. [CLAUDE.md](CLAUDE.md) is the deep guide.

## License

GPL-3.0, inherited from [Forge](https://github.com/Card-Forge/forge).
