use std::process::{Child, Command};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::time::Duration;

use log::{error, info, warn};
use serde::Serialize;
use tauri::{AppHandle, Emitter, Manager};

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(tag = "state", content = "message")]
pub enum ServerState {
    Stopped,
    Starting,
    Running,
    Error(String),
}

pub struct ServerProcess {
    pub child: Mutex<Option<Child>>,
    pub state: Mutex<ServerState>,
    stopping: AtomicBool,
}

impl ServerProcess {
    pub fn new() -> Self {
        Self {
            child: Mutex::new(None),
            state: Mutex::new(ServerState::Stopped),
            stopping: AtomicBool::new(false),
        }
    }

    fn set_state(&self, new_state: ServerState, app: &AppHandle) {
        let mut state = self.state.lock().unwrap();
        *state = new_state.clone();
        if let Err(e) = app.emit("server-state", new_state) {
            warn!("Failed to emit server-state event: {e}");
        }
    }
}

/// Sidecar binary name — jlink produces a shell script on Unix, a .bat on Windows.
fn sidecar_bin_name() -> &'static str {
    #[cfg(target_os = "windows")]
    return "leyline.bat";
    #[cfg(not(target_os = "windows"))]
    return "leyline";
}

/// Resolve bundle dir given the Tauri resource dir.
/// Pure function — no AppHandle dependency.
fn resolve_bundle_dir_from(resource_dir: &std::path::Path) -> Result<std::path::PathBuf, String> {
    let bundle = resource_dir.join(".bundle-stage").join("leyline");
    if bundle.join("bin").join(sidecar_bin_name()).exists() {
        info!("Bundle found at {}", bundle.display());
        return Ok(bundle);
    }

    // Dev fallback: walk up from CWD to find build/bundle at repo root
    let mut dir = std::env::current_dir().unwrap_or_default();
    loop {
        let candidate = dir.join("build/bundle");
        if candidate.join("bin").join(sidecar_bin_name()).exists() {
            info!("Bundle found via dev fallback at {}", candidate.display());
            return Ok(candidate.canonicalize().unwrap_or(candidate));
        }
        if !dir.pop() {
            break;
        }
    }

    Err(format!(
        "leyline bundle not found at {:?} or dev fallback",
        bundle
    ))
}

/// Resolve the leyline bundle root (contains bin/, lib/, jre/, res/, data/).
fn resolve_bundle_dir(app: &AppHandle) -> Result<std::path::PathBuf, String> {
    let resource_dir = app
        .path()
        .resource_dir()
        .map_err(|e| format!("No resource dir: {e}"))?;
    resolve_bundle_dir_from(&resource_dir)
}

/// Copy seed DB to target path if it doesn't exist yet.
/// Returns Some(target) if DB is available after the call.
fn ensure_player_db_at(
    target_dir: &std::path::Path,
    seed_dir: &std::path::Path,
) -> Option<std::path::PathBuf> {
    if let Err(e) = std::fs::create_dir_all(target_dir) {
        warn!("Failed to create dir {}: {e}", target_dir.display());
    }
    let db_path = target_dir.join("player.db");

    if !db_path.exists() {
        let seed = seed_dir.join("player.db");
        if seed.exists() {
            match std::fs::copy(&seed, &db_path) {
                Ok(_) => info!("Copied seed DB to {}", db_path.display()),
                Err(e) => warn!("Failed to copy seed DB to {}: {e}", db_path.display()),
            }
        } else {
            warn!("No seed DB at {}", seed.display());
        }
    }

    if db_path.exists() {
        Some(db_path)
    } else {
        None
    }
}

/// Ensure player.db exists in the app data directory.
/// Copies the seed DB from the bundle on first launch.
fn ensure_player_db(app: &AppHandle, bundle_dir: &std::path::Path) -> Option<std::path::PathBuf> {
    let data_dir = app.path().app_data_dir().ok()?;
    ensure_player_db_at(&data_dir, &bundle_dir.join("data"))
}

/// Walk up from a directory looking for leyline.toml.
/// Returns the directory containing it, or None.
fn find_repo_root(start: &std::path::Path) -> Option<std::path::PathBuf> {
    let mut dir = start.to_path_buf();
    loop {
        if dir.join("leyline.toml").exists() {
            return Some(dir);
        }
        if !dir.pop() {
            return None;
        }
    }
}

/// Resolve sidecar CWD.
/// Must be a writable directory — Forge creates cache/, and the server writes logs/.
///
/// Production (Windows): app data dir — Program Files is read-only.
/// Production (macOS): bundle dir (app bundle is writable by owner).
/// Dev: repo root (has leyline.toml, data/player.db, logs/).
/// Must NOT be src-tauri/ — Tauri's file watcher restarts the app on any file change.
fn resolve_sidecar_cwd(app: &AppHandle, bundle_dir: &std::path::Path) -> std::path::PathBuf {
    // Dev: walk up from bundle to find repo root (has leyline.toml)
    if let Some(root) = find_repo_root(bundle_dir) {
        info!("Sidecar CWD: repo root at {}", root.display());
        return root;
    }

    // Production (Windows): use writable app data dir
    #[cfg(target_os = "windows")]
    {
        let data_dir = app.path().app_data_dir()
            .unwrap_or_else(|_| std::env::temp_dir());
        let _ = std::fs::create_dir_all(&data_dir);
        let toml_dest = data_dir.join("leyline.toml");
        if !toml_dest.exists() {
            let toml_src = bundle_dir.join("leyline.toml");
            if toml_src.exists() {
                let _ = std::fs::copy(&toml_src, &toml_dest);
            }
        }
        return data_dir;
    }

    // Production (macOS): bundle dir is writable
    #[cfg(not(target_os = "windows"))]
    {
        if bundle_dir.join("leyline.toml").exists() {
            return bundle_dir.to_path_buf();
        }
        app.path()
            .app_log_dir()
            .unwrap_or_else(|_| std::env::temp_dir())
    }
}

#[tauri::command]
pub async fn start_server(app: AppHandle) -> Result<(), String> {
    let server = app.state::<ServerProcess>();

    // Already running?
    {
        let state = server.state.lock().unwrap();
        if *state == ServerState::Running || *state == ServerState::Starting {
            return Ok(());
        }
    }

    server.stopping.store(false, Ordering::SeqCst);

    let bundle_dir = resolve_bundle_dir(&app)?;
    info!("Bundle dir: {:?}", bundle_dir);
    let bin_path = bundle_dir.join("bin").join(sidecar_bin_name());
    server.set_state(ServerState::Starting, &app);

    // TLS: generate CA + server cert, trust CA in OS keychain
    let (cert_path, key_path) = crate::tls::ensure_certs().map_err(|e| {
        let msg = format!("TLS setup failed: {e}");
        server.set_state(ServerState::Error(msg.clone()), &app);
        msg
    })?;

    // Ensure player DB in app data dir (copies seed on first launch)
    let player_db = ensure_player_db(&app, &bundle_dir);

    // Log sidecar output to a file for debugging
    let log_dir = app
        .path()
        .app_log_dir()
        .unwrap_or_else(|_| std::env::temp_dir());
    let _ = std::fs::create_dir_all(&log_dir);
    let log_path = log_dir.join("leyline-server.log");
    let log_file = std::fs::File::create(&log_path).ok();
    let stdout_file = log_file
        .as_ref()
        .and_then(|f| f.try_clone().ok())
        .map(std::process::Stdio::from)
        .unwrap_or_else(std::process::Stdio::null);
    let stderr_file = log_file
        .as_ref()
        .and_then(|f| f.try_clone().ok())
        .map(std::process::Stdio::from)
        .unwrap_or_else(std::process::Stdio::null);

    let sidecar_cwd = resolve_sidecar_cwd(&app, &bundle_dir);
    info!("Sidecar CWD: {:?}", sidecar_cwd);
    info!("Server log: {:?}", log_path);

    let mut cmd;
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x08000000;
        // Invoke java.exe directly — running .bat spawns java as a detached process
        // and the .bat itself exits immediately, making child.try_wait() think it died.
        // Strip \\?\ extended path prefix — Java and cmd.exe can't handle it
        fn strip_unc(p: &std::path::Path) -> String {
            p.to_str().unwrap_or_default()
                .strip_prefix(r"\\?\").unwrap_or(p.to_str().unwrap_or_default())
                .to_string()
        }
        let java_path = strip_unc(&bundle_dir.join("jre").join("bin").join("java.exe"));
        let lib_dir = strip_unc(&bundle_dir.join("lib"));
        let res_dir = strip_unc(&bundle_dir.join("res"));
        cmd = Command::new(&java_path);
        cmd.args([
            "-Dio.netty.tryReflectionSetAccessible=true",
            "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-opens", "java.base/java.nio=ALL-UNNAMED",
        ]);
        cmd.arg(format!("-Dleyline.res.dir={}", res_dir));
        cmd.arg("-cp").arg(format!("{}/*", lib_dir));
        cmd.arg("leyline.LeylineMainKt");
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    #[cfg(not(target_os = "windows"))]
    {
        cmd = Command::new(&bin_path);
    }
    cmd.current_dir(&sidecar_cwd)
        .arg("--cert")
        .arg(&cert_path)
        .arg("--key")
        .arg(&key_path)
        .stdout(stdout_file)
        .stderr(stderr_file);
    if let Some(ref db) = player_db {
        cmd.env("LEYLINE_PLAYER_DB", db);
    }
    let child = cmd.spawn()
        .map_err(|e| {
            let msg = format!("Failed to spawn leyline: {e}");
            server.set_state(ServerState::Error(msg.clone()), &app);
            msg
        })?;

    {
        let mut guard = server.child.lock().unwrap();
        *guard = Some(child);
    }

    // Poll health in background
    let app_handle = app.clone();
    tokio::spawn(async move {
        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(2))
            .build()
            .unwrap();

        for _ in 0..120 {
            tokio::time::sleep(Duration::from_millis(500)).await;

            // Check if process died
            {
                let server = app_handle.state::<ServerProcess>();
                let mut guard = server.child.lock().unwrap();
                if let Some(ref mut child) = *guard {
                    if let Ok(Some(status)) = child.try_wait() {
                        if server.stopping.load(Ordering::SeqCst) {
                            return; // stop_server is handling this
                        }
                        let msg = format!("Server exited with {status}");
                        error!("{}", msg);
                        drop(guard);
                        if server.stopping.load(Ordering::SeqCst) {
                            return;
                        }
                        server.set_state(ServerState::Error(msg), &app_handle);
                        return;
                    }
                }
            }

            if let Ok(resp) = client.get("http://127.0.0.1:8091/health").send().await {
                if resp.status().is_success() {
                    info!("Server health check passed — server is running");
                    let server = app_handle.state::<ServerProcess>();
                    server.set_state(ServerState::Running, &app_handle);
                    return;
                }
            }
        }

        error!("Server health check timed out after 60s");
        let server = app_handle.state::<ServerProcess>();
        server.set_state(
            ServerState::Error("Health check timed out after 60s".into()),
            &app_handle,
        );
    });

    Ok(())
}

#[tauri::command]
pub fn stop_server(app: AppHandle) -> Result<(), String> {
    let server = app.state::<ServerProcess>();
    server.stopping.store(true, Ordering::SeqCst);
    let mut guard = server.child.lock().unwrap();
    if let Some(ref mut child) = *guard {
        let _ = child.kill();
        let _ = child.wait();
    }
    *guard = None;
    drop(guard);
    server.set_state(ServerState::Stopped, &app);
    Ok(())
}

#[tauri::command]
pub fn server_status(app: AppHandle) -> ServerState {
    let server = app.state::<ServerProcess>();
    let state = server.state.lock().unwrap().clone();
    state
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resolve_bundle_finds_staged_binary() {
        let tmp = tempfile::tempdir().unwrap();
        let stage = tmp.path().join(".bundle-stage").join("leyline").join("bin");
        std::fs::create_dir_all(&stage).unwrap();
        std::fs::write(stage.join(sidecar_bin_name()), b"stub").unwrap();

        let result = resolve_bundle_dir_from(tmp.path());
        assert!(result.is_ok());
        assert!(result.unwrap().ends_with(".bundle-stage/leyline"));
    }

    #[test]
    fn resolve_bundle_missing_returns_error() {
        let tmp = tempfile::tempdir().unwrap();
        let result = resolve_bundle_dir_from(tmp.path());
        assert!(result.is_err());
        assert!(result.unwrap_err().contains("not found"));
    }

    #[test]
    fn ensure_player_db_copies_seed() {
        let target = tempfile::tempdir().unwrap();
        let seed = tempfile::tempdir().unwrap();
        std::fs::write(seed.path().join("player.db"), b"test-db-content").unwrap();

        let result = ensure_player_db_at(target.path(), seed.path());
        assert!(result.is_some());
        assert_eq!(
            std::fs::read(result.unwrap()).unwrap(),
            b"test-db-content"
        );
    }

    #[test]
    fn ensure_player_db_skips_existing() {
        let target = tempfile::tempdir().unwrap();
        let seed = tempfile::tempdir().unwrap();
        std::fs::write(target.path().join("player.db"), b"existing").unwrap();
        std::fs::write(seed.path().join("player.db"), b"seed").unwrap();

        let result = ensure_player_db_at(target.path(), seed.path());
        assert!(result.is_some());
        assert_eq!(
            std::fs::read(result.unwrap()).unwrap(),
            b"existing"
        );
    }

    #[test]
    fn ensure_player_db_no_seed_returns_none() {
        let target = tempfile::tempdir().unwrap();
        let seed = tempfile::tempdir().unwrap();

        let result = ensure_player_db_at(target.path(), seed.path());
        assert!(result.is_none());
    }

    #[test]
    fn find_repo_root_walks_up() {
        let tmp = tempfile::tempdir().unwrap();
        std::fs::write(tmp.path().join("leyline.toml"), b"").unwrap();
        let nested = tmp.path().join("build").join("bundle");
        std::fs::create_dir_all(&nested).unwrap();

        let result = find_repo_root(&nested);
        assert!(result.is_some());
        assert_eq!(result.unwrap(), tmp.path());
    }

    #[test]
    fn find_repo_root_returns_none_when_missing() {
        let tmp = tempfile::tempdir().unwrap();
        let result = find_repo_root(tmp.path());
        assert!(result.is_none());
    }
}
