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

Download the latest release for your platform from [GitHub Releases](https://github.com/delebedev/leyline/releases):

| Platform | File | Notes |
|----------|------|-------|
| **macOS** (Apple Silicon) | `Leyline_*_aarch64.dmg` | Unsigned — see below |
| **Windows** (x64) | `Leyline_*_x64-setup.exe` | NSIS installer |
| **Linux** (x64) | `Leyline_*_amd64.AppImage` | Steam Deck compatible |

### macOS

The app is unsigned. macOS will block it with a "damaged" error. Remove the quarantine flag first:

```bash
xattr -cr ~/Downloads/Leyline_*.dmg
```

Then open the `.dmg` and drag Leyline to Applications. See [#338](https://github.com/delebedev/leyline/issues/338) for details.

### Linux / Steam Deck

Make the AppImage executable and run it:

```bash
chmod +x Leyline_*.AppImage
./Leyline_*.AppImage
```

On Steam Deck Gaming Mode, you may need `--appimage-extract-and-run` if FUSE isn't available. MTGA must be installed via Steam (runs under Proton).

Requires a local [MTGA](https://magic.wizards.com/en/mtgarena) installation (Epic Games, Steam, or Steam/Proton on Linux).

## Build from source

Requires [Bun](https://bun.sh) (1.3+), [Rust](https://rustup.rs) (1.75+), and JDK 17+.

```bash
# Full pipeline from repo root
just launcher-build
```

This runs `just bundle` (jlink JRE + JARs + card resources), generates a changelog, stages everything into `src-tauri/.bundle-stage/`, and builds the platform installer (`.dmg`, `.exe`, or `.AppImage`).

Output lands in `src-tauri/target/release/bundle/<format>/`.

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

1. Generates a local TLS CA + server cert and trusts it in the OS cert store
2. Copies `services.conf` into Arena's StreamingAssets (tells Arena to connect to localhost)
3. Creates a stub audio file Arena expects
4. Sets platform-specific preferences (macOS: `CheckSC=0` via `defaults`)
5. Starts the leyline server and waits for it to be healthy

Restore reverses all of this — removes the config file and resets preferences.

### Data locations

| Location | macOS | Windows | Linux |
|----------|-------|---------|-------|
| Player DB | `~/Library/Application Support/dev.leyline.launcher/` | `%APPDATA%/dev.leyline.launcher/` | `~/.local/share/dev.leyline.launcher/` |
| Server log | `~/Library/Logs/dev.leyline.launcher/leyline-server.log` | `%APPDATA%/dev.leyline.launcher/logs/` | `~/.local/share/dev.leyline.launcher/logs/` |
| TLS certs | `~/Library/Application Support/dev.leyline/tls/` | `%APPDATA%/dev.leyline/tls/` | `~/.local/share/dev.leyline/tls/` |

## Troubleshooting

**"Damaged" error on open** — Run `xattr -cr` on both the `.dmg` and the `.app` (see Install above).

**Start button shows red error briefly** — Check server log at `~/Library/Logs/dev.leyline.launcher/leyline-server.log`. Common causes:
- Port conflict: another leyline instance or `just serve` is running on ports 8091/30010/30003
- Missing resources: `.dmg` was built without `just bundle` (6MB instead of 90MB)

**Server starts but Arena can't connect** — Make sure Arena was launched *after* clicking Start. If Arena was already running, quit and relaunch it.

## Platform support

| Platform | Format | Status |
|----------|--------|--------|
| macOS arm64 | `.dmg` | Tested |
| Windows x64 | `.exe` (NSIS) | Tested |
| Linux x64 | `.AppImage` | Builds, needs hardware testing |

Linux: MTGA runs under Steam/Proton. The launcher runs natively. TLS cert trust uses NSS `certutil` (`libnss3-tools` package). Steam Deck requires first-time setup in Desktop Mode (cert trust), then works from Gaming Mode.
