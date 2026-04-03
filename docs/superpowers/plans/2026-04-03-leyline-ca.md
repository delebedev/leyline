# Leyline Local CA Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate a local CA during `just dev-setup`, trust it in the macOS keychain, and sign server certs so Arena's UnityTls accepts connections.

**Architecture:** A shell script (`just/tls.just`) handles all cert generation via `openssl`. `just dev-setup` calls it. `just serve` reads the generated certs. Launcher integration is designed but deferred — future work passes `--cert`/`--key` to the sidecar.

**Tech Stack:** openssl (LibreSSL, ships with macOS), just (task runner), bash

---

## File Structure

| File | Responsibility |
|------|---------------|
| `just/tls.just` (create) | All cert generation + OS trust recipes — imported by root justfile |
| `justfile` (modify) | Import `tls.just`, update `certs` default path, update `_cert_flags` filenames, call `tls-setup` from `dev-setup` |
| `.claude/rules/build-infra.md` (modify) | Update TLS docs with new CA workflow |

---

### Task 1: Create `just/tls.just` with CA generation

**Files:**
- Create: `just/tls.just`

- [ ] **Step 1: Create `just/tls.just` with `tls-setup` recipe**

```just
# TLS certificate management — local CA for Arena compatibility.
# Arena's UnityTls validates cert chains against the OS trust store.
# Self-signed certs are rejected. This generates a local CA, trusts it
# in the macOS keychain, and signs server certs with it.

_tls_dir := env("LEYLINE_CERTS", env("HOME", "/tmp") / "Library/Application Support/dev.leyline/tls")

# generate local CA + server certs, trust CA in macOS keychain
[group('setup')]
tls-setup:
    #!/usr/bin/env bash
    set -euo pipefail
    tls_dir="{{_tls_dir}}"
    mkdir -p "$tls_dir"

    # --- CA (one-time) ---
    if [ -f "$tls_dir/ca.pem" ] && [ -f "$tls_dir/ca.key" ]; then
        echo "==> CA already exists, skipping generation"
    else
        echo "==> Generating Leyline Local CA..."
        openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
            -keyout "$tls_dir/ca.key" -out "$tls_dir/ca.pem" \
            -subj "/CN=Leyline Local CA" 2>/dev/null
        echo "    Created: $tls_dir/ca.pem"
    fi

    # --- Trust CA in keychain (one-time) ---
    if security find-certificate -c "Leyline Local CA" ~/Library/Keychains/login.keychain-db >/dev/null 2>&1; then
        echo "==> CA already trusted in keychain"
    else
        echo ""
        echo "Leyline needs to install a local certificate authority so Arena trusts your server."
        echo "macOS will ask for your password — this is a one-time setup."
        echo ""
        security add-trusted-cert -r trustRoot \
            -k ~/Library/Keychains/login.keychain-db "$tls_dir/ca.pem"
        echo "==> CA trusted in login keychain"
    fi

    # --- Server cert (regenerate if missing or expiring within 24h) ---
    needs_cert=true
    if [ -f "$tls_dir/server-chain.pem" ] && [ -f "$tls_dir/server.key" ]; then
        if openssl x509 -checkend 86400 -noout -in "$tls_dir/server-chain.pem" 2>/dev/null; then
            echo "==> Server cert valid, skipping"
            needs_cert=false
        else
            echo "==> Server cert expired or expiring soon, regenerating..."
        fi
    fi

    if [ "$needs_cert" = true ]; then
        echo "==> Generating server cert (signed by Leyline CA)..."
        openssl req -newkey rsa:2048 -nodes \
            -keyout "$tls_dir/server.key" -out "$tls_dir/server.csr" \
            -subj "/CN=localhost" 2>/dev/null
        openssl x509 -req -in "$tls_dir/server.csr" \
            -CA "$tls_dir/ca.pem" -CAkey "$tls_dir/ca.key" \
            -CAcreateserial -days 365 -out "$tls_dir/server.pem" \
            -extfile <(echo "subjectAltName=DNS:localhost,IP:127.0.0.1") 2>/dev/null
        cat "$tls_dir/server.pem" "$tls_dir/ca.pem" > "$tls_dir/server-chain.pem"
        echo "    Created: $tls_dir/server-chain.pem + server.key"
    fi

# show current TLS cert status
[group('setup')]
tls-status:
    #!/usr/bin/env bash
    set -euo pipefail
    tls_dir="{{_tls_dir}}"
    if [ ! -f "$tls_dir/ca.pem" ]; then
        echo "No CA found. Run: just tls-setup"
        exit 0
    fi
    echo "CA:"
    openssl x509 -in "$tls_dir/ca.pem" -noout -subject -dates
    echo ""
    if security find-certificate -c "Leyline Local CA" ~/Library/Keychains/login.keychain-db >/dev/null 2>&1; then
        echo "Keychain: trusted"
    else
        echo "Keychain: NOT trusted (run: just tls-setup)"
    fi
    echo ""
    if [ -f "$tls_dir/server-chain.pem" ]; then
        echo "Server cert:"
        openssl x509 -in "$tls_dir/server-chain.pem" -noout -subject -dates -ext subjectAltName
    else
        echo "No server cert. Run: just tls-setup"
    fi

# remove local CA + certs and untrust from keychain
[group('setup')]
tls-teardown:
    #!/usr/bin/env bash
    set -euo pipefail
    tls_dir="{{_tls_dir}}"
    # Remove from keychain
    if security find-certificate -c "Leyline Local CA" ~/Library/Keychains/login.keychain-db >/dev/null 2>&1; then
        security delete-certificate -c "Leyline Local CA" ~/Library/Keychains/login.keychain-db 2>/dev/null || true
        echo "==> Removed CA from keychain"
    fi
    # Remove cert files
    if [ -d "$tls_dir" ]; then
        trash "$tls_dir"
        echo "==> Moved $tls_dir to Trash"
    fi
    echo "TLS teardown complete."
```

- [ ] **Step 2: Verify the file parses**

Run: `cd /Users/denis/src/leyline && just --list --list-submodules 2>&1 | head -5`

Expected: no parse errors (the file isn't imported yet, so it won't appear — but just verifying no syntax issues would require importing it first; defer to Task 2).

- [ ] **Step 3: Commit**

```bash
git add just/tls.just
git commit -m "feat: add tls.just — local CA generation + OS trust recipes"
```

---

### Task 2: Wire `tls.just` into root justfile

**Files:**
- Modify: `justfile:4` — add import
- Modify: `justfile:14` — update `certs` default path
- Modify: `justfile:36-41` — update `_cert_flags` to use new filenames
- Modify: `justfile:137-163` — call `tls-setup` from `dev-setup`

- [ ] **Step 1: Add import for `tls.just`**

In `justfile`, after line 6 (`import 'just/tools.just'`), add:

```just
import 'just/tls.just'
```

- [ ] **Step 2: Update `certs` default path**

Change line 14 from:
```just
certs        := env("LEYLINE_CERTS", env("HOME", "/tmp") / ".local/share/leyline/certs")
```
to:
```just
certs        := env("LEYLINE_CERTS", env("HOME", "/tmp") / "Library/Application Support/dev.leyline/tls")
```

- [ ] **Step 3: Update `_cert_flags` filenames**

Replace lines 36-41 (the TLS cert block) with:

```just
# --- TLS certs (generated by `just tls-setup`, pass --cert/--key to override) ---
_cert     := certs / "server-chain.pem"
_key      := certs / "server.key"
_cert_flags := 'cert_flags=""; if [ -f "' + _cert + '" ] && [ -f "' + _key + '" ]; then cert_flags="--cert ' + _cert + ' --key ' + _key + '"; fi'
```

Note: FD and AccountServer now share the same cert (no separate `_account_cert`/`_account_key`). The `--account-cert`/`--account-key` flags are no longer passed — `buildAccountServer` in `LeylineMain.kt:127-128` falls back to `tls.first`/`tls.second` when account-specific flags are absent, which is the same cert. This is correct — both servers should use the same CA-signed cert.

- [ ] **Step 4: Add `tls-setup` call to `dev-setup`**

In the `dev-setup` recipe (around line 161, before the final "Dev setup complete" echo), add:

```bash
    # 4. Generate + trust TLS certs
    just tls-setup
```

The full `dev-setup` recipe becomes:

```just
# one-time local dev setup: gen certs, patch Arena client for localhost
[group('setup')]
dev-setup:
    #!/usr/bin/env bash
    set -euo pipefail
    # 1. Copy localhost services.conf into Arena
    streaming="{{_streaming}}"
    if [ ! -d "$streaming" ]; then
        echo "Error: MTGA not found at {{_mtga_path}}"
        echo "Install Arena via Epic Games first."
        exit 1
    fi
    cp "{{project_dir}}/app/main/resources/services.conf" "$streaming/services.conf"
    echo "==> Copied localhost services.conf"
    # 2. Ensure NPE_VO.bnk exists
    audio="{{_audio_dir}}"
    if [ ! -f "$audio/NPE_VO.bnk" ]; then
        mkdir -p "$audio"
        echo "QktIRDAAAACWAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" \
            | base64 -d > "$audio/NPE_VO.bnk"
        echo "==> Created NPE_VO.bnk stub"
    fi
    # 3. macOS defaults — skip service check hash + hash verification
    defaults write com.Wizards.MtGA CheckSC -integer 0
    defaults write com.Wizards.MtGA HashFilesOnStartup -integer 0
    echo "==> macOS defaults set (CheckSC=0, HashFilesOnStartup=0)"
    # 4. Generate + trust TLS certs
    just tls-setup
    echo ""
    echo "Dev setup complete. Run: just serve"
```

- [ ] **Step 5: Verify justfile parses and recipes are listed**

Run: `cd /Users/denis/src/leyline && just --list 2>&1 | grep -E 'tls|dev-setup|serve'`

Expected output includes `tls-setup`, `tls-status`, `tls-teardown`, `dev-setup`, `serve`.

- [ ] **Step 6: Commit**

```bash
git add justfile
git commit -m "feat: wire tls.just into justfile — new cert path + dev-setup integration"
```

---

### Task 3: Update build-infra docs

**Files:**
- Modify: `.claude/rules/build-infra.md`

- [ ] **Step 1: Rewrite TLS section**

Replace the existing "TLS & Networking" section in `.claude/rules/build-infra.md` with:

```markdown
## TLS & Certificates

Arena's UnityTls validates cert chains against the OS trust store — self-signed certs are rejected. Leyline generates a local CA and signs server certs with it.

- **First-time setup:** `just dev-setup` (or `just tls-setup` standalone) generates a Leyline Local CA, trusts it in the macOS login keychain (password prompt), and creates a server cert signed by it.
- **Cert location:** `~/Library/Application Support/dev.leyline/tls/` (override with `LEYLINE_CERTS` env var).
- **Idempotent:** re-running `tls-setup` skips existing CA/trust, only regenerates expired server certs.
- **Override:** pass `--cert`/`--key` CLI flags or set `LEYLINE_CERT_PATH`/`LEYLINE_KEY_PATH` env vars to use custom certs.
- **Teardown:** `just tls-teardown` removes certs and untrusts the CA from keychain.
- **Status:** `just tls-status` shows CA/cert expiry and keychain trust state.
```

- [ ] **Step 2: Commit**

```bash
git add .claude/rules/build-infra.md
git commit -m "docs: update build-infra with local CA workflow"
```

---

### Task 4: Smoke test — end to end

This task is manual verification with the user.

- [ ] **Step 1: Clean slate — trash any existing certs**

```bash
trash ~/Library/Application\ Support/dev.leyline/tls 2>/dev/null || true
security delete-certificate -c "Leyline Local CA" ~/Library/Keychains/login.keychain-db 2>/dev/null || true
```

- [ ] **Step 2: Run `just tls-setup`**

Run: `just tls-setup`

Expected:
```
==> Generating Leyline Local CA...
    Created: .../tls/ca.pem

Leyline needs to install a local certificate authority so Arena trusts your server.
macOS will ask for your password — this is a one-time setup.

==> CA trusted in login keychain
==> Generating server cert (signed by Leyline CA)...
    Created: .../tls/server-chain.pem + server.key
```

macOS password dialog appears. After entering password, CA is trusted.

- [ ] **Step 3: Verify idempotency**

Run: `just tls-setup`

Expected:
```
==> CA already exists, skipping generation
==> CA already trusted in keychain
==> Server cert valid, skipping
```

No password prompt.

- [ ] **Step 4: Check cert status**

Run: `just tls-status`

Expected: shows CA subject/dates, "Keychain: trusted", server cert subject/dates/SAN.

- [ ] **Step 5: Start server with generated certs**

Run: `just serve`

Expected: server boots without TLS errors. Logs show cert being loaded (no "Using self-signed TLS certificate" message).

- [ ] **Step 6: Connect Arena**

Launch Arena, verify FrontDoor handshake succeeds. Check Player.log — no `UNITYTLS_X509VERIFY_FLAG_NOT_TRUSTED` errors.

- [ ] **Step 7: Test teardown**

Run: `just tls-teardown`

Expected: CA removed from keychain, cert files trashed.

- [ ] **Step 8: Commit (if any fixes were needed)**

```bash
git add -A
git commit -m "fix: adjustments from smoke test"
```
