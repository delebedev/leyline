# TLS — local CA generation + OS trust for Arena's UnityTls
# Arena validates cert chains against the macOS trust store.
# These recipes create a local CA, trust it in keychain, and sign server certs.

_tls_dir := env("LEYLINE_CERTS", env("HOME", "/tmp") / "Library/Application Support/dev.leyline/tls")

# Generate local CA + server certs, trust CA in macOS keychain
[group('setup')]
tls-setup:
    #!/usr/bin/env bash
    set -euo pipefail
    dir="{{_tls_dir}}"
    mkdir -p "$dir"
    cd "$dir"

    # --- CA ---
    if [ -f ca.pem ] && [ -f ca.key ]; then
        echo "CA already exists at $dir/ca.pem — skipping generation"
    else
        echo "Generating CA…"
        openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
            -keyout ca.key -out ca.pem \
            -subj "/CN=Leyline Local CA"
        echo "CA created."
    fi

    # --- Keychain trust ---
    if security find-certificate -c "Leyline Local CA" >/dev/null 2>&1; then
        echo "CA already trusted in keychain — skipping"
    else
        echo "Trusting CA in login keychain (you may be prompted for password)…"
        security add-trusted-cert -r trustRoot \
            -k ~/Library/Keychains/login.keychain-db ca.pem
        echo "CA trusted."
    fi

    # --- Server cert ---
    if [ -f server.pem ] && openssl x509 -in server.pem -checkend 86400 -noout 2>/dev/null; then
        echo "Server cert valid (>24h remaining) — skipping"
    else
        echo "Generating server cert…"
        openssl req -newkey rsa:2048 -nodes \
            -keyout server.key -out server.csr \
            -subj "/CN=localhost"
        openssl x509 -req -in server.csr \
            -CA ca.pem -CAkey ca.key -CAcreateserial -days 365 \
            -out server.pem \
            -extfile <(echo "subjectAltName=DNS:localhost,IP:127.0.0.1")
        cat server.pem ca.pem > server-chain.pem
        echo "Server cert signed and chain built."
    fi

    echo "TLS ready: $dir"

# Show CA info, keychain trust state, server cert details
[group('setup')]
tls-status:
    #!/usr/bin/env bash
    set -euo pipefail
    dir="{{_tls_dir}}"

    echo "=== Cert directory ==="
    echo "$dir"
    if [ ! -d "$dir" ]; then
        echo "  (does not exist — run 'just tls-setup')"
        exit 0
    fi
    ls -la "$dir"/ 2>/dev/null || true

    echo ""
    echo "=== CA ==="
    if [ -f "$dir/ca.pem" ]; then
        openssl x509 -in "$dir/ca.pem" -noout -subject -dates
    else
        echo "  No CA found"
    fi

    echo ""
    echo "=== Keychain trust ==="
    if security find-certificate -c "Leyline Local CA" >/dev/null 2>&1; then
        echo "  Leyline Local CA is trusted"
    else
        echo "  Leyline Local CA NOT found in keychain"
    fi

    echo ""
    echo "=== Server cert ==="
    if [ -f "$dir/server.pem" ]; then
        openssl x509 -in "$dir/server.pem" -noout -subject -dates -ext subjectAltName
        if openssl x509 -in "$dir/server.pem" -checkend 86400 -noout 2>/dev/null; then
            echo "  Status: valid (>24h remaining)"
        else
            echo "  Status: EXPIRING SOON or expired — rerun 'just tls-setup'"
        fi
    else
        echo "  No server cert found"
    fi

# Remove CA from keychain, trash cert dir
[group('setup')]
tls-teardown:
    #!/usr/bin/env bash
    set -euo pipefail
    dir="{{_tls_dir}}"

    echo "Removing Leyline Local CA from keychain…"
    if security find-certificate -c "Leyline Local CA" >/dev/null 2>&1; then
        security delete-certificate -c "Leyline Local CA" 2>/dev/null || true
        echo "Removed from keychain."
    else
        echo "Not found in keychain — nothing to remove."
    fi

    if [ -d "$dir" ]; then
        echo "Trashing cert directory: $dir"
        trash "$dir"
        echo "Done."
    else
        echo "Cert directory does not exist — nothing to trash."
    fi
