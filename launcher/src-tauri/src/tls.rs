use std::fs;
use std::net::IpAddr;
use std::path::PathBuf;
use std::process::Command;
use std::time::Duration;

use rcgen::{
    BasicConstraints, CertificateParams, DistinguishedName, DnType,
    ExtendedKeyUsagePurpose, Ia5String, IsCa, KeyPair, KeyUsagePurpose,
    SanType, PKCS_RSA_SHA256,
};
use time::OffsetDateTime;
use x509_parser::pem::parse_x509_pem;

/// Ensure CA + server certs exist and CA is trusted in OS keychain.
/// Returns (cert_path, key_path) for passing to sidecar --cert/--key.
pub fn ensure_certs() -> Result<(PathBuf, PathBuf), String> {
    let tls_dir = resolve_tls_dir()?;
    fs::create_dir_all(&tls_dir)
        .map_err(|e| format!("Failed to create TLS dir {}: {e}", tls_dir.display()))?;

    let ca_pem = tls_dir.join("ca.pem");
    let ca_key_path = tls_dir.join("ca.key");
    let server_chain = tls_dir.join("server-chain.pem");
    let server_key_path = tls_dir.join("server.key");

    // --- CA ---
    if !ca_pem.exists() || !ca_key_path.exists() {
        let (cert_pem, key_pem) = generate_ca()?;
        fs::write(&ca_pem, &cert_pem)
            .map_err(|e| format!("Failed to write CA cert: {e}"))?;
        fs::write(&ca_key_path, &key_pem)
            .map_err(|e| format!("Failed to write CA key: {e}"))?;
    }

    // --- OS trust ---
    if !is_ca_trusted() {
        trust_ca(&ca_pem)?;
    }

    // --- Server cert ---
    let needs_server_cert = if server_chain.exists() && server_key_path.exists() {
        let pem_bytes = fs::read(&server_chain)
            .map_err(|e| format!("Failed to read server cert: {e}"))?;
        expires_within(&pem_bytes, 86400)
    } else {
        true
    };

    if needs_server_cert {
        let ca_cert_pem = fs::read_to_string(&ca_pem)
            .map_err(|e| format!("Failed to read CA cert: {e}"))?;
        let ca_key_pem = fs::read_to_string(&ca_key_path)
            .map_err(|e| format!("Failed to read CA key: {e}"))?;
        let (cert_pem, key_pem) = generate_server_cert(&ca_cert_pem, &ca_key_pem)?;

        let chain = format!("{}{}", cert_pem, ca_cert_pem);
        fs::write(&server_chain, chain)
            .map_err(|e| format!("Failed to write server chain: {e}"))?;
        fs::write(&server_key_path, key_pem)
            .map_err(|e| format!("Failed to write server key: {e}"))?;
    }

    Ok((server_chain, server_key_path))
}

fn resolve_tls_dir() -> Result<PathBuf, String> {
    dirs::data_dir()
        .map(|d| d.join("dev.leyline").join("tls"))
        .ok_or_else(|| "Cannot determine application data directory".to_string())
}

fn generate_ca() -> Result<(String, String), String> {
    let key = KeyPair::generate_for(&PKCS_RSA_SHA256)
        .map_err(|e| format!("CA key generation failed: {e}"))?;

    let mut params = CertificateParams::default();
    params.is_ca = IsCa::Ca(BasicConstraints::Unconstrained);
    params.distinguished_name = {
        let mut dn = DistinguishedName::new();
        dn.push(DnType::CommonName, "Leyline Local CA");
        dn
    };
    params.key_usages = vec![KeyUsagePurpose::KeyCertSign, KeyUsagePurpose::CrlSign];
    params.not_before = OffsetDateTime::now_utc();
    params.not_after = OffsetDateTime::now_utc() + time::Duration::days(3650);

    let cert = params
        .self_signed(&key)
        .map_err(|e| format!("CA cert generation failed: {e}"))?;

    Ok((cert.pem(), key.serialize_pem()))
}

fn generate_server_cert(
    ca_cert_pem: &str,
    ca_key_pem: &str,
) -> Result<(String, String), String> {
    let ca_key = KeyPair::from_pem(ca_key_pem)
        .map_err(|e| format!("Failed to parse CA key: {e}"))?;
    let ca_params = CertificateParams::from_ca_cert_pem(ca_cert_pem)
        .map_err(|e| format!("Failed to parse CA cert: {e}"))?;
    let ca_cert = ca_params
        .self_signed(&ca_key)
        .map_err(|e| format!("Failed to reconstruct CA cert: {e}"))?;

    let server_key = KeyPair::generate_for(&PKCS_RSA_SHA256)
        .map_err(|e| format!("Server key generation failed: {e}"))?;

    let mut params = CertificateParams::default();
    params.distinguished_name = {
        let mut dn = DistinguishedName::new();
        dn.push(DnType::CommonName, "localhost");
        dn
    };
    params.subject_alt_names = vec![
        SanType::DnsName(
            Ia5String::try_from("localhost").map_err(|e| format!("SAN error: {e}"))?,
        ),
        SanType::IpAddress("127.0.0.1".parse::<IpAddr>().unwrap()),
    ];
    params.extended_key_usages = vec![ExtendedKeyUsagePurpose::ServerAuth];
    params.not_before = OffsetDateTime::now_utc();
    params.not_after = OffsetDateTime::now_utc() + time::Duration::days(365);

    let cert = params
        .signed_by(&server_key, &ca_cert, &ca_key)
        .map_err(|e| format!("Server cert signing failed: {e}"))?;

    Ok((cert.pem(), server_key.serialize_pem()))
}

fn expires_within(pem_bytes: &[u8], secs: u64) -> bool {
    let Ok((_, pem)) = parse_x509_pem(pem_bytes) else {
        return true;
    };
    let Ok(cert) = pem.parse_x509() else {
        return true;
    };
    match cert.validity().time_to_expiration() {
        Some(remaining) => remaining < Duration::from_secs(secs),
        None => true,
    }
}

// --- Platform-specific OS trust ---

#[cfg(target_os = "macos")]
fn is_ca_trusted() -> bool {
    Command::new("security")
        .args(["find-certificate", "-c", "Leyline Local CA"])
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

#[cfg(target_os = "macos")]
fn trust_ca(ca_pem: &std::path::Path) -> Result<(), String> {
    let status = Command::new("security")
        .args(["add-trusted-cert", "-r", "trustRoot", "-k"])
        .arg(
            dirs::home_dir()
                .unwrap()
                .join("Library/Keychains/login.keychain-db"),
        )
        .arg(ca_pem)
        .status()
        .map_err(|e| format!("Failed to run security command: {e}"))?;

    if status.success() {
        Ok(())
    } else {
        Err("Failed to trust CA — did you cancel the password prompt?".to_string())
    }
}

#[cfg(target_os = "windows")]
fn is_ca_trusted() -> bool {
    Command::new("certutil")
        .args(["-verifystore", "Root", "Leyline Local CA"])
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

#[cfg(target_os = "windows")]
fn trust_ca(ca_pem: &std::path::Path) -> Result<(), String> {
    let status = Command::new("certutil")
        .args(["-addstore", "Root"])
        .arg(ca_pem)
        .status()
        .map_err(|e| format!("Failed to run certutil: {e}"))?;

    if status.success() {
        Ok(())
    } else {
        Err("Failed to trust CA — did you cancel the UAC prompt?".to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generate_ca_produces_valid_pem() {
        let (cert_pem, key_pem) = generate_ca().unwrap();
        assert!(cert_pem.contains("BEGIN CERTIFICATE"));
        assert!(key_pem.contains("BEGIN PRIVATE KEY"));

        // Parse back with x509-parser
        let (_, pem) = parse_x509_pem(cert_pem.as_bytes()).unwrap();
        let cert = pem.parse_x509().unwrap();
        assert_eq!(
            cert.subject().to_string(),
            "CN=Leyline Local CA"
        );
        assert!(cert.is_ca());
    }

    #[test]
    fn generate_server_cert_signed_by_ca() {
        let (ca_cert_pem, ca_key_pem) = generate_ca().unwrap();
        let (server_cert_pem, server_key_pem) =
            generate_server_cert(&ca_cert_pem, &ca_key_pem).unwrap();

        assert!(server_cert_pem.contains("BEGIN CERTIFICATE"));
        assert!(server_key_pem.contains("BEGIN PRIVATE KEY"));

        let (_, pem) = parse_x509_pem(server_cert_pem.as_bytes()).unwrap();
        let cert = pem.parse_x509().unwrap();
        assert_eq!(cert.subject().to_string(), "CN=localhost");
        assert_eq!(
            cert.issuer().to_string(),
            "CN=Leyline Local CA"
        );
        assert!(!cert.is_ca());
    }

    #[test]
    fn chain_file_roundtrip() {
        let (ca_cert_pem, ca_key_pem) = generate_ca().unwrap();
        let (server_cert_pem, _) =
            generate_server_cert(&ca_cert_pem, &ca_key_pem).unwrap();

        let chain = format!("{}{}", server_cert_pem, ca_cert_pem);

        // First cert in chain is server cert
        let (_, pem) = parse_x509_pem(chain.as_bytes()).unwrap();
        let cert = pem.parse_x509().unwrap();
        assert_eq!(cert.subject().to_string(), "CN=localhost");
    }

    #[test]
    fn expires_within_detects_valid_cert() {
        let (ca_cert_pem, _) = generate_ca().unwrap();
        // CA has 10yr validity — should NOT expire within 24h
        assert!(!expires_within(ca_cert_pem.as_bytes(), 86400));
    }

    #[test]
    fn expires_within_returns_true_for_garbage() {
        assert!(expires_within(b"not a cert", 86400));
    }

    #[test]
    fn full_flow_to_temp_dir() {
        let tmp = std::env::temp_dir().join("leyline-tls-test");
        let _ = std::fs::remove_dir_all(&tmp);
        std::fs::create_dir_all(&tmp).unwrap();

        // Generate CA
        let (ca_cert, ca_key) = generate_ca().unwrap();
        std::fs::write(tmp.join("ca.pem"), &ca_cert).unwrap();
        std::fs::write(tmp.join("ca.key"), &ca_key).unwrap();

        // Generate server cert
        let (server_cert, server_key) =
            generate_server_cert(&ca_cert, &ca_key).unwrap();
        let chain = format!("{}{}", server_cert, ca_cert);
        std::fs::write(tmp.join("server-chain.pem"), &chain).unwrap();
        std::fs::write(tmp.join("server.key"), &server_key).unwrap();

        // All files exist
        assert!(tmp.join("ca.pem").exists());
        assert!(tmp.join("ca.key").exists());
        assert!(tmp.join("server-chain.pem").exists());
        assert!(tmp.join("server.key").exists());

        // Chain is not expiring
        let chain_bytes = std::fs::read(tmp.join("server-chain.pem")).unwrap();
        assert!(!expires_within(&chain_bytes, 86400));

        let _ = std::fs::remove_dir_all(&tmp);
    }
}
