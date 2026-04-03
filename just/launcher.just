# dev mode — hot-reload frontend, server runs separately via `just serve`
[group('launcher')]
launcher-dev:
    cd "{{project_dir}}/launcher" && bun tauri dev

# build distributable .dmg (runs `bundle` first to produce sidecar, generates changelog)
[group('launcher')]
launcher-build: bundle
    just _launcher-stage
    just _launcher-tauri

# fast rebuild — reuses existing bundle artifacts, skips gradle (use after first `just launcher-build`)
[group('launcher')]
launcher-build-fast:
    test -d "{{project_dir}}/build/bundle/bin" || (echo "ERROR: no bundle — run 'just bundle' first"; exit 1)
    ls "{{project_dir}}"/build/dist/leyline-*.tgz >/dev/null 2>&1 || (echo "ERROR: no archive — run 'just bundle' first"; exit 1)
    just _launcher-stage
    just _launcher-tauri

# internal: stage bundle artifacts for Tauri packaging
_launcher-stage:
    test -f .changelog.md || bun scripts/curate-changelog.ts
    rm -rf "{{project_dir}}/launcher/src-tauri/.bundle-stage"
    mkdir -p "{{project_dir}}/launcher/src-tauri/.bundle-stage"
    # build/bundle/ has jre+lib+bin from jlink; extract res+data from the archive
    cp -R "{{project_dir}}/build/bundle" "{{project_dir}}/launcher/src-tauri/.bundle-stage/leyline"
    tar -xzf "{{project_dir}}"/build/dist/leyline-*.tgz -C "{{project_dir}}/launcher/src-tauri/.bundle-stage/leyline" --strip-components=1 leyline/res
    mkdir -p "{{project_dir}}/launcher/src-tauri/.bundle-stage/leyline/data"
    cp "{{project_dir}}/data/player.db" "{{project_dir}}/launcher/src-tauri/.bundle-stage/leyline/data/player.db" 2>/dev/null \
        || cp "{{project_dir}}/launcher/resources/player.db" "{{project_dir}}/launcher/src-tauri/.bundle-stage/leyline/data/player.db" \
        || (echo "ERROR: no player.db found — run 'just seed-db' or check launcher/resources/player.db"; exit 1)
    cp .changelog.md "{{project_dir}}/launcher/src-tauri/.bundle-stage/changelog.md"

# internal: run Tauri build + post-build check
_launcher-tauri:
    cd "{{project_dir}}/launcher" && bun tauri build --config '{"bundle":{"resources":[".bundle-stage/leyline/",".bundle-stage/changelog.md"]}}'
    just launcher-check

# verify launcher bundle has all required components
[group('launcher')]
launcher-check:
    #!/usr/bin/env bash
    set -euo pipefail
    STAGE="{{project_dir}}/launcher/src-tauri/.bundle-stage/leyline"
    DMG_DIR="{{project_dir}}/launcher/src-tauri/target/release/bundle/dmg"
    ERRORS=0
    check() { if [ ! -e "$1" ]; then echo "MISSING: $1"; ERRORS=$((ERRORS+1)); else echo "  OK: $(basename "$1")"; fi; }
    echo "=== Launcher bundle check ==="
    check "$STAGE/bin/leyline"
    check "$STAGE/jre/bin/java"
    check "$STAGE/lib"
    check "$STAGE/res/cardsfolder"
    check "$STAGE/res/editions"
    check "$STAGE/data/player.db"
    check "{{project_dir}}/launcher/src-tauri/.bundle-stage/changelog.md"
    # DMG count — must be exactly 1
    DMG_COUNT=$(ls "$DMG_DIR"/*.dmg 2>/dev/null | wc -l | tr -d ' ')
    if [ "$DMG_COUNT" -eq 0 ]; then
        echo "MISSING: no .dmg in $DMG_DIR"; ERRORS=$((ERRORS+1))
    elif [ "$DMG_COUNT" -gt 1 ]; then
        echo "AMBIGUOUS: $DMG_COUNT .dmg files in $DMG_DIR (stale cache?)"; ERRORS=$((ERRORS+1))
        ls "$DMG_DIR"/*.dmg
    else
        DMG=$(ls "$DMG_DIR"/*.dmg)
        SIZE=$(stat -f%z "$DMG" 2>/dev/null || stat -c%s "$DMG")
        MB=$((SIZE / 1048576))
        if [ "$MB" -lt 50 ]; then
            echo "SUSPECT: DMG is only ${MB}MB (expect >50MB — resources likely missing)"; ERRORS=$((ERRORS+1))
        else
            echo "  OK: DMG ${MB}MB"
        fi
    fi
    if [ "$ERRORS" -gt 0 ]; then echo "FAIL: $ERRORS check(s) failed"; exit 1; fi
    echo "All launcher checks passed."

# generate release notes from git log (LLM-curated if OPENROUTER_API_KEY set)
[group('deploy')]
changelog:
    bun scripts/curate-changelog.ts
    @cat .changelog.md

# verify tag matches launcher version files
[group('deploy')]
release-check:
    #!/usr/bin/env bash
    set -euo pipefail
    TAG=$(git describe --tags --exact-match 2>/dev/null || echo "")
    if [ -z "$TAG" ]; then echo "Not on a tag — nothing to check"; exit 0; fi
    V="${TAG#v}"
    TAURI=$(jq -r .version launcher/src-tauri/tauri.conf.json)
    PKG=$(jq -r .version launcher/package.json)
    OK=true
    [ "$V" != "$TAURI" ] && echo "MISMATCH: tag=$V tauri.conf.json=$TAURI" && OK=false
    [ "$V" != "$PKG" ] && echo "MISMATCH: tag=$V package.json=$PKG" && OK=false
    $OK && echo "Versions match: $V"
