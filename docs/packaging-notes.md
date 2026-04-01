# Standalone Bundle

Self-contained leyline distribution — no system Java needed.

`just bundle` → `build/dist/leyline-<version>-<platform>.tgz`

Extract anywhere, run `bin/leyline`. All five servers start (FD, MD, Account, Debug, Management).

```bash
just bundle
mkdir /tmp/leyline-test && cd /tmp/leyline-test
tar xzf /path/to/leyline-*.tgz
cd leyline && bin/leyline          # no Java needed
curl http://localhost:8091/health  # → {"status":"ok"}
```

## What's inside

| Component | Size |
|-----------|------|
| jlink JRE (stripped, no java.desktop) | 37 MB |
| Application JARs (sqlite platform-stripped) | 53 MB |
| Card resources (32k scripts, editions, formats) | ~160 MB |
| Seed DB + config | <1 MB |
| **Archive (tar.gz)** | **~81 MB** |

## How it works

**jlink** strips the JDK to 12 modules (vs ~70 in full JDK). `java.desktop` (14 MB) is safely excluded — only one Forge class (`AutoUpdater`) references AWT, never loaded at runtime.

**sqlite-jdbc** ships 23 native libs for all platforms (14 MB). `JlinkBundleTask` repacks it keeping only the target platform (~800 KB).

**Dead dependency exclusions** in `build.gradle.kts` remove Jetty, servlet-api, and tinylog's SLF4J binding — Forge POMs pull these for desktop/mobile features leyline doesn't use. Tinylog API + impl are kept because Forge code calls `org.tinylog.Logger` directly (e.g. `Card.addAssignedDamage`).

**Resource resolution**: `bin/leyline` passes `-Dleyline.res.dir=$DIR/res`. `ResourceResolver` maps `forge-gui/res/X` to `res/X` when this property is set, falling back to dev-layout paths.

## Per-platform builds

The bundle is platform-specific (JRE native code + sqlite native lib). Currently macOS arm64 only. Extend via `-Dleyline.bundle.platform=Linux/x86_64` and cross-platform JDK for jlink.

| Platform | sqlite path | jlink target |
|----------|------------|-------------|
| macOS arm64 | `Mac/aarch64` | current host JDK |
| macOS x64 | `Mac/x86_64` | needs x64 JDK |
| Linux x64 | `Linux/x86_64` | needs linux JDK |

## Next steps

- CI matrix for macOS x64, Linux x64
- Update Dockerfile to use jlink runtime (distroless base)
- Tauri sidecar integration

## Future size reductions

| Optimization | Savings | Effort |
|-------------|---------|--------|
| Replace BouncyCastle with JDK cert API | ~9 MB | Medium (PEM parsing) |
| GraalVM native-image | JRE → 0 (~25 MB total) | High (Forge reflection) |
| ProGuard/R8 class shrinking | ~10-20 MB | Medium |
