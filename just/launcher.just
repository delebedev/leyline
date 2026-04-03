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

# headless smoke test: generate certs, spawn sidecar, verify health + TLS chain
[group('launcher')]
launcher-smoke:
    #!/usr/bin/env bash
    set -euo pipefail
    echo "=== Launcher smoke test ==="

    # 1. Find sidecar binary
    BIN="{{project_dir}}/build/bundle/bin/leyline"
    if [ ! -f "$BIN" ]; then
        echo "FAIL: sidecar not found at $BIN — run 'just bundle' first"
        exit 1
    fi
    echo "  sidecar: $BIN"

    # 2. Generate certs to temp dir (no OS trust needed)
    TLS_DIR=$(mktemp -d)
    trap 'kill $SIDECAR_PID 2>/dev/null; rm -rf "$TLS_DIR"' EXIT
    echo "  tls dir: $TLS_DIR"

    openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
        -keyout "$TLS_DIR/ca.key" -out "$TLS_DIR/ca.pem" \
        -subj "/CN=Leyline Smoke CA" 2>/dev/null
    openssl req -newkey rsa:2048 -nodes \
        -keyout "$TLS_DIR/server.key" -out "$TLS_DIR/server.csr" \
        -subj "/CN=localhost" 2>/dev/null
    openssl x509 -req -in "$TLS_DIR/server.csr" \
        -CA "$TLS_DIR/ca.pem" -CAkey "$TLS_DIR/ca.key" \
        -CAcreateserial -days 1 -out "$TLS_DIR/server.pem" \
        -extfile <(echo "subjectAltName=DNS:localhost,IP:127.0.0.1") 2>/dev/null
    cat "$TLS_DIR/server.pem" "$TLS_DIR/ca.pem" > "$TLS_DIR/server-chain.pem"
    echo "  certs: generated"

    # 3. Kill anything on our ports
    for p in 30010 30003 8090 8091 9443; do
        for pid in $(lsof -ti :$p 2>/dev/null); do kill -9 $pid 2>/dev/null || true; done
    done

    # 4. Spawn sidecar
    "$BIN" --cert "$TLS_DIR/server-chain.pem" --key "$TLS_DIR/server.key" \
        > "$TLS_DIR/sidecar.log" 2>&1 &
    SIDECAR_PID=$!
    echo "  sidecar pid: $SIDECAR_PID"

    # 5. Wait for health
    echo -n "  health: "
    for i in $(seq 1 30); do
        if curl -sf http://127.0.0.1:8091/health >/dev/null 2>&1; then
            echo "ok (${i}s)"
            break
        fi
        if ! kill -0 $SIDECAR_PID 2>/dev/null; then
            echo "FAIL: sidecar died"
            cat "$TLS_DIR/sidecar.log" | tail -20
            exit 1
        fi
        sleep 1
    done
    if ! curl -sf http://127.0.0.1:8091/health >/dev/null 2>&1; then
        echo "FAIL: health timeout after 30s"
        cat "$TLS_DIR/sidecar.log" | tail -20
        exit 1
    fi

    # 6. Verify TLS cert chain on FrontDoor
    ISSUER=$(openssl s_client -connect localhost:30010 -servername localhost </dev/null 2>&1 | grep "issuer=" | head -1)
    if echo "$ISSUER" | grep -q "Leyline Smoke CA"; then
        echo "  tls chain: ok (issuer=$ISSUER)"
    else
        echo "FAIL: unexpected cert issuer: $ISSUER"
        exit 1
    fi

    # 7. Verify AccountServer TLS too
    ACCT_ISSUER=$(openssl s_client -connect localhost:9443 -servername localhost </dev/null 2>&1 | grep "issuer=" | head -1)
    if echo "$ACCT_ISSUER" | grep -q "Leyline Smoke CA"; then
        echo "  account tls: ok"
    else
        echo "FAIL: account cert issuer: $ACCT_ISSUER"
        exit 1
    fi

    echo "=== Smoke test passed ==="

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
