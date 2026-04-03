# Leyline Launcher

Desktop app for playing Arena offline. Manages the leyline server and Arena configuration — no terminal needed.

<p align="center">
  <img src="../assets/launcher.png" alt="Leyline launcher" width="592">
</p>

## What it does

1. **Start** — configures Arena to connect to leyline, starts the local server
2. **Restore** — undoes all changes, Arena connects to official servers again
3. Launch Arena normally — it connects to leyline instead of WotC

## Install

Download the latest `Leyline_*_aarch64.dmg` from [GitHub Releases](https://github.com/delebedev/leyline/releases).

The app is unsigned. macOS will block it with a "damaged" error. Remove the quarantine flag first:

```bash
xattr -cr ~/Downloads/Leyline_*.dmg
```

Then open the `.dmg` and drag Leyline to Applications. If macOS complains on first launch, also run:

```bash
xattr -cr /Applications/Leyline.app
```

Requires a local [MTGA](https://magic.wizards.com/en/mtgarena) installation (Epic Games or Steam).

## Build from source

Requires [Bun](https://bun.sh) (1.3+), [Rust](https://rustup.rs) (1.75+), and JDK 17+.

```bash
# Full pipeline from repo root
just launcher-build
```

This runs `just bundle` (jlink JRE + JARs + card resources), generates a changelog, stages everything into `src-tauri/.bundle-stage/`, and builds the `.dmg`.

The `.dmg` lands in `src-tauri/target/release/bundle/dmg/`.

For development (hot-reload):

```bash
just bundle          # needed once for sidecar
cd launcher
bun install
bun tauri dev
```

### Verifying a build locally

Always check the `.dmg` size — **~90MB means the sidecar is bundled, ~6MB means it's missing.**

```bash
# Mount and inspect
hdiutil attach src-tauri/target/release/bundle/dmg/*.dmg
ls /Volumes/Leyline/Leyline.app/Contents/Resources/.bundle-stage/leyline/
# Should contain: bin/ lib/ jre/ res/ data/

# Install and test
cp -R /Volumes/Leyline/Leyline.app /Applications/
xattr -cr /Applications/Leyline.app
open /Applications/Leyline.app
```

## How it works

The launcher embeds the leyline server (a stripped JVM + game engine, ~90MB) as a sidecar process. When you click Start:

1. Copies `services.conf` into Arena's StreamingAssets (tells Arena to connect to localhost)
2. Creates a stub audio file Arena expects
3. Sets macOS preferences (`CheckSC=0` — skips Arena service check hash)
4. Starts the leyline server and waits for it to be healthy

Restore reverses all of this — removes the config file and resets preferences.

### Data locations

| Path | Contents |
|------|----------|
| `~/Library/Application Support/dev.leyline.launcher/` | Player database (copied from bundle on first launch) |
| `~/Library/Logs/dev.leyline.launcher/leyline-server.log` | Server output (for debugging startup failures) |

## Troubleshooting

**"Damaged" error on open** — Run `xattr -cr` on both the `.dmg` and the `.app` (see Install above).

**Start button shows red error briefly** — Check server log at `~/Library/Logs/dev.leyline.launcher/leyline-server.log`. Common causes:
- Port conflict: another leyline instance or `just serve` is running on ports 8091/30010/30003
- Missing resources: `.dmg` was built without `just bundle` (6MB instead of 90MB)

**Server starts but Arena can't connect** — Make sure Arena was launched *after* clicking Start. If Arena was already running, quit and relaunch it.

## Platform support

macOS arm64 only for now. Windows and Linux builds are planned.
