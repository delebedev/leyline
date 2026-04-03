# Launcher TLS — rcgen Cert Generation

> **For agentic workers:** Use superpowers:executing-plans to implement the plan generated from this spec.

**Goal:** The launcher generates a local CA and server certs using `rcgen` (pure Rust), trusts the CA in the OS keychain, and passes cert paths to the sidecar — so Arena connects without any manual cert setup.

**Scope:** macOS implementation. Windows uses `#[cfg(target_os)]` for the OS trust command; cert generation is platform-independent.

---

## Problem

The launcher currently spawns the sidecar with no cert flags. The server falls back to self-signed certs, which Arena rejects (`UNITYTLS_X509VERIFY_FLAG_NOT_TRUSTED`). Non-dev users have no way to generate trusted certs.

## Solution

Add a `tls.rs` module to the launcher that generates certs via `rcgen` and trusts the CA in the OS keychain. Called before sidecar spawn in `start_server()`.

## File Layout

Same directory as `just tls-setup` — launcher and dev workflow are interchangeable:

```
~/Library/Application Support/dev.leyline/tls/
├── ca.key           # CA private key (PEM, PKCS#8)
├── ca.pem           # CA cert (PEM)
├── server.key       # Server private key (PEM, PKCS#8)
├── server-chain.pem # Server cert + CA cert concatenated
```

Path resolved via `dirs::data_dir()` + `dev.leyline/tls/` (macOS: `~/Library/Application Support/dev.leyline/tls/`, Windows: `%APPDATA%\dev.leyline\tls\`).

## New Module: `tls.rs`

### `ensure_certs() -> Result<(PathBuf, PathBuf), String>`

Returns `(cert_path, key_path)` for passing to sidecar.

1. **Resolve tls dir** — `dirs::data_dir().join("dev.leyline/tls")`, create if missing.

2. **CA (skip if exists):**
   - Check `ca.pem` + `ca.key` exist
   - If missing: generate with `rcgen`:
     - RSA 2048 key pair
     - Self-signed, CN=`Leyline Local CA`
     - `is_ca: IsCa::Ca(BasicConstraints::Unconstrained)`
     - Validity: 10 years
   - Write `ca.pem` (cert PEM) and `ca.key` (private key PKCS#8 PEM)

3. **OS trust (skip if trusted):**
   - macOS: check `security find-certificate -c "Leyline Local CA"` exit code
   - If not trusted: `security add-trusted-cert -r trustRoot -k ~/Library/Keychains/login.keychain-db ca.pem`
   - Windows: check `certutil -verifystore Root "Leyline Local CA"` exit code
   - If not trusted: `certutil -addstore Root ca.pem`

4. **Server cert (skip if valid):**
   - Check `server-chain.pem` + `server.key` exist
   - If exists: parse cert, check expiry (>24h remaining via `x509-parser`)
   - If missing or expiring: generate with `rcgen`:
     - RSA 2048 key pair
     - Signed by CA (load CA cert + key from disk)
     - CN=`localhost`, SAN: `DNS:localhost`, `IP:127.0.0.1`
     - Validity: 1 year
   - Write `server.key` (private key PKCS#8 PEM)
   - Write `server-chain.pem` (server cert PEM + CA cert PEM concatenated)

5. **Return** `(tls_dir.join("server-chain.pem"), tls_dir.join("server.key"))`

## Integration: `server.rs`

In `start_server()`, before spawning the child process:

```rust
let (cert_path, key_path) = tls::ensure_certs().map_err(|e| {
    server.set_state(ServerState::Error(format!("TLS setup failed: {e}")), &app);
    e
})?;

let child = Command::new(&bin_path)
    .arg("--cert").arg(&cert_path)
    .arg("--key").arg(&key_path)
    .stdout(std::process::Stdio::null())
    .stderr(std::process::Stdio::null())
    .spawn()
    ...
```

## New Dependencies

```toml
rcgen = { version = "0.13", features = ["pem"] }
x509-parser = "0.16"
```

`rcgen` is pure Rust (no openssl linking). `x509-parser` for reading existing certs to check expiry. Both are well-maintained, widely used.

## Platform Handling

```rust
#[cfg(target_os = "macos")]
fn is_ca_trusted(ca_pem: &Path) -> bool { ... }  // security find-certificate

#[cfg(target_os = "macos")]
fn trust_ca(ca_pem: &Path) -> Result<(), String> { ... }  // security add-trusted-cert

#[cfg(target_os = "windows")]
fn is_ca_trusted(ca_pem: &Path) -> bool { ... }  // certutil -verifystore

#[cfg(target_os = "windows")]
fn trust_ca(ca_pem: &Path) -> Result<(), String> { ... }  // certutil -addstore
```

## Error Handling

All errors from `ensure_certs()` propagate to `start_server()` which sets `ServerState::Error(msg)`. The frontend shows the error message. No silent failures, no fallback to self-signed (that doesn't work anyway).

Specific failure modes:
- **rcgen generation fails:** unlikely (pure computation), but propagate error
- **File write fails:** permission error on tls dir — show path in error message
- **OS trust fails:** user cancelled password prompt — error message suggests retrying
- **Cert parse fails:** corrupt file — delete and regenerate on next attempt

## Testing

- **Unit:** `ensure_certs()` generates valid certs (check PEM structure, SAN, issuer chain)
- **Idempotency:** call twice, second call is a no-op (files unchanged)
- **Expiry:** mock an expired cert, verify regeneration
- **Integration:** launcher start → sidecar gets `--cert`/`--key` → server loads CA-signed certs

## Compatibility with `just tls-setup`

Both produce identical file layout. Either can run first:
- Dev runs `just tls-setup` → launcher finds certs, skips generation
- Player uses launcher → launcher generates certs → dev can `just serve` with same certs
- CA trusted once, both workflows benefit
