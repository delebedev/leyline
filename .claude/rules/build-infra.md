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

- `just build` produces jars, but a **running server holds old bytecode**. After code changes: `just stop` + `just serve`. `just dev` auto-restarts on `.kt` changes.
- Forge submodule must point to a commit that exists on remote. If `git submodule update` fails with "Unable to find current revision", the pinned commit was force-pushed away. Fix: `git -C forge checkout origin/master`.

## TLS & Networking

- TLS: self-signed at boot. Pass `--cert`/`--key` (or `LEYLINE_CERT_PATH`/`LEYLINE_KEY_PATH` env vars) for explicit certs.

## Proto workflow

`proto/upstream/messages.proto` → `matchdoor/src/main/proto/messages.proto` via `just sync-proto`. The upstream submodule has the raw client schema; `proto/rename-map.sed` applies field/type renames for readability. Don't edit `messages.proto` directly — edit the rename map and re-sync.

## Individual build steps

When `just bootstrap` isn't enough or you need to debug:

```bash
git submodule update --init --recursive   # forge + proto/upstream
just install-forge                        # mvn install forge jars to forge/.m2-local/
just build                                # gradle: proto-sync + compileKotlin + jar
just seed-db                              # create data/player.db with starter decks
```
