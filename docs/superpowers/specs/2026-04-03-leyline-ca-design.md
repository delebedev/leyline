# Leyline Local CA — Design Spec

> **For agentic workers:** Use superpowers:executing-plans to implement the plan generated from this spec.

**Goal:** Arena's UnityTls rejects self-signed certs. Generate a local CA, trust it in the OS keychain once, and sign server certs with it — so Arena connects without manual cert setup.

**Scope:** Dev-setup flow first (`just dev-setup` + `just serve`). Launcher integration designed but deferred to a follow-up.

---

## Problem

Arena validates TLS cert chains against the OS trust store on every connection (FrontDoor, MatchDoor, AccountServer). `CheckSC=0` only skips the service-check hash — it does NOT bypass UnityTls certificate validation. Self-signed certs fail with `UNITYTLS_X509VERIFY_FLAG_NOT_TRUSTED`.

Previously, leyline used mitmproxy's CA (already trusted in the keychain from proxy setup) to sign certs via BouncyCastle. That infrastructure was removed in PR #340 because proxy mode moved to leyline-private. Now neither self-signed nor mitmproxy-signed certs are generated — the server boots with Netty/Ktor self-signed certs that Arena rejects.

## Solution

Generate a **Leyline Local CA** at dev-setup time. Trust it once in the OS keychain (password prompt). Sign server certs with it. Both Netty (FD/MD) and Ktor (AccountServer) consume the same PEM cert+key.

## Certificate Chain

```
Leyline Local CA (self-signed root, 10yr)
  └── localhost server cert (signed by CA, 1yr)
        SAN: dns:localhost, ip:127.0.0.1
```

Both FrontDoor/MatchDoor (Netty) and AccountServer (Ktor) share the same server cert. This matches existing behavior — they already share `--cert`/`--key` flags.

## File Layout

```
~/Library/Application Support/dev.leyline/tls/
├── ca.key              # CA private key
├── ca.pem              # CA cert (trusted in keychain)
├── server.key          # Server private key (PKCS#8, PEM)
├── server-chain.pem    # Server cert + CA cert concatenated (PEM chain)
└── server.csr          # Ephemeral, can be deleted
```

Path resolved via `LEYLINE_CERTS` env var override, defaulting to platform-conventional location:
- macOS: `~/Library/Application Support/dev.leyline/tls/`
- Windows (future): `%APPDATA%\leyline\tls\`

## Generation Flow

All cert generation uses `openssl` (LibreSSL ships with macOS). Three commands:

```bash
# 1. CA — one-time
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
  -keyout ca.key -out ca.pem \
  -subj "/CN=Leyline Local CA"

# 2. Server key + CSR
openssl req -newkey rsa:2048 -nodes \
  -keyout server.key -out server.csr \
  -subj "/CN=localhost"

# 3. Sign server cert with CA
openssl x509 -req -in server.csr \
  -CA ca.pem -CAkey ca.key -CAcreateserial \
  -days 365 -out server.pem \
  -extfile <(echo "subjectAltName=DNS:localhost,IP:127.0.0.1")

# 4. Build chain file (server cert + CA cert)
cat server.pem ca.pem > server-chain.pem
```

## OS Trust

macOS:
```bash
security add-trusted-cert -r trustRoot \
  -k ~/Library/Keychains/login.keychain-db ca.pem
```

Triggers a password prompt. One-time — the CA persists in the login keychain until explicitly removed.

Windows (future):
```bash
certutil -addstore Root ca.pem
```
Triggers UAC elevation prompt.

## Idempotency

`just dev-setup` is idempotent:

1. **CA exists?** → skip generation + trust. (Check: `ca.pem` and `ca.key` both exist.)
2. **Server cert exists and not expired?** → skip. (Check: `openssl x509 -checkend 86400 -noout -in server-chain.pem` — renew if expiring within 24h.)
3. **CA trusted in keychain?** → skip trust step. (Check: `security find-certificate -c "Leyline Local CA"` succeeds.)

On re-run after CA is trusted, `just dev-setup` prints "Certs OK" and moves on.

## Integration Points

### `just dev-setup`

Add cert generation as a new step after Arena config. Sequence:

1. Validate MTGA installation (existing)
2. Copy services.conf (existing)
3. Create stub audio file (existing)
4. Set macOS preferences (existing)
5. **Generate CA if missing** (new)
6. **Trust CA in keychain if not trusted** (new)
7. **Generate server cert if missing/expired** (new)

Print explanatory message before password prompt:
```
Leyline needs to install a local certificate authority so Arena trusts your server.
macOS will ask for your password — this is a one-time setup.
```

### `just serve`

Update `_cert_flags` to look for certs at the new path:
- `$LEYLINE_CERTS/server-chain.pem` → `--cert`
- `$LEYLINE_CERTS/server.key` → `--key`
- Same files also passed as `--account-cert` / `--account-key`

Existing `LEYLINE_CERT_PATH`/`LEYLINE_KEY_PATH` env vars and explicit `--cert`/`--key` CLI args still override (escape hatch for custom certs).

### LeylineServer (Netty)

No changes. `SslContextBuilder.forServer(certChainFile, keyFile)` handles PEM chain files natively.

### AccountServer (Ktor)

No changes. `loadPemKeyStore()` uses `CertificateFactory.getInstance("X.509")` which reads multiple certs from a PEM stream — the chain file works as-is.

### Launcher (future, not this PR)

The launcher's `start_server()` in `server.rs` currently passes no cert flags to the sidecar. Future work:

1. On first launch: generate CA + server cert (shell out to bundled `openssl` or use `rcgen` crate)
2. Trust CA in OS keychain (platform-specific command)
3. Pass `--cert`/`--key` to sidecar process via env vars or CLI args

Same cert directory, same file layout. The launcher just becomes another consumer of the same convention.

## Testing

- **Unit:** No new Kotlin code — cert generation is shell scripting in justfile.
- **Integration:** `just dev-setup` on a clean machine (no `~/.../dev.leyline/tls/`) should generate certs + trust CA.
- **E2E:** `just serve` → launch Arena → FD handshake succeeds (no `UNITYTLS_X509VERIFY_FLAG_NOT_TRUSTED` in Player.log).
- **Idempotency:** Run `just dev-setup` twice — second run should skip all cert steps.
- **Expire renewal:** Manually set server cert to 0-day validity, run `just dev-setup` — should regenerate server cert only (not CA).

## Security Notes

- CA key stays local (`~/.../dev.leyline/tls/ca.key`). Never committed, never shared.
- CA is only trusted in the user's login keychain — not system-wide.
- Server cert is localhost-only (SAN restricts to `dns:localhost,ip:127.0.0.1`).
- 10yr CA validity is fine for a local dev tool — no revocation infrastructure needed.

## Out of Scope

- Windows/Linux implementation (designed for, deferred)
- Launcher cert generation (designed for, separate PR)
- Cert rotation/revocation (unnecessary for local-only CA)
- leyline.toml cert config (CLI/env is sufficient)
