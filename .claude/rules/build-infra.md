---
paths:
  - "forge/**"
  - "just/build.just"
  - "just/serve.just"
  - "deploy/**"
  - "proto/**"
  - "build.gradle.kts"
  - "settings.gradle.kts"
---

# Build & Infrastructure

## Build gotchas

- `just build` produces jars, but a **running server holds old bytecode**. After code changes: restart the running server, then `just serve` again. `just dev` auto-restarts on `.kt` changes.
- Forge submodule must point to a commit that exists on remote. If `git submodule update` fails with "Unable to find current revision", the pinned commit was force-pushed away. Fix: `git -C forge checkout origin/master`.

## TLS & Certificates

Arena's UnityTls validates cert chains against the OS trust store — self-signed certs are rejected.

- **Setup:** use a trusted localhost cert/key from your local tooling.
- **Default cert location:** `~/Library/Application Support/dev.leyline/tls/` (override with `LEYLINE_CERTS` env var).
- **Override:** pass `--cert`/`--key` CLI flags or set `LEYLINE_CERT_PATH`/`LEYLINE_KEY_PATH` env vars to use custom certs.

## Proto workflow

`proto/upstream/messages.proto` → `matchdoor/src/main/proto/messages.proto` via `just sync-proto`. The upstream submodule has the raw client schema; `proto/rename-map.sed` applies field/type renames for readability. Don't edit `messages.proto` directly — edit the rename map and re-sync.

## Individual build steps

When `just bootstrap` isn't enough or you need to debug:

```bash
git submodule update --init --recursive   # forge + proto/upstream
just install-forge                        # mvn install forge jars → forge/.m2-local/ (writes .forge-commit-installed stamp)
just build                                # gradle: proto-sync + compileKotlin + jar
just seed-db                              # create data/player.db with starter decks
```

See `.claude/rules/build-bootstrap.md` for forge cache internals (shared vs local mode, stamp file, worktree reference-clone).
