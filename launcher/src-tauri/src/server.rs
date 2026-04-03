use std::process::{Child, Command};
use std::sync::Mutex;
use std::time::Duration;

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
}

impl ServerProcess {
    pub fn new() -> Self {
        Self {
            child: Mutex::new(None),
            state: Mutex::new(ServerState::Stopped),
        }
    }

    fn set_state(&self, new_state: ServerState, app: &AppHandle) {
        let mut state = self.state.lock().unwrap();
        *state = new_state.clone();
        let _ = app.emit("server-state", new_state);
    }
}

/// Sidecar binary name — jlink produces a shell script on Unix, a .bat on Windows.
fn sidecar_bin_name() -> &'static str {
    #[cfg(target_os = "windows")]
    return "leyline.bat";
    #[cfg(not(target_os = "windows"))]
    return "leyline";
}

/// Resolve the leyline bundle root (contains bin/, lib/, jre/, res/, data/).
fn resolve_bundle_dir(app: &AppHandle) -> Result<std::path::PathBuf, String> {
    let resource_dir = app
        .path()
        .resource_dir()
        .map_err(|e| format!("No resource dir: {e}"))?;

    let bundle = resource_dir.join(".bundle-stage").join("leyline");
    if bundle.join("bin").join(sidecar_bin_name()).exists() {
        return Ok(bundle);
    }

    // Dev fallback: walk up from CWD to find build/bundle at repo root
    let mut dir = std::env::current_dir().unwrap_or_default();
    loop {
        let candidate = dir.join("build/bundle");
        if candidate.join("bin").join(sidecar_bin_name()).exists() {
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

/// Ensure player.db exists in the app data directory.
/// Copies the seed DB from the bundle on first launch.
fn ensure_player_db(app: &AppHandle, bundle_dir: &std::path::Path) -> Option<std::path::PathBuf> {
    let data_dir = app.path().app_data_dir().ok()?;
    let _ = std::fs::create_dir_all(&data_dir);
    let db_path = data_dir.join("player.db");

    if !db_path.exists() {
        let seed = bundle_dir.join("data").join("player.db");
        if seed.exists() {
            let _ = std::fs::copy(&seed, &db_path);
        }
    }

    if db_path.exists() {
        Some(db_path)
    } else {
        None
    }
}

/// Resolve sidecar CWD.
/// Production: bundle dir (self-contained with toml, data/, res/).
/// Dev: repo root (has leyline.toml, data/player.db, logs/).
/// Must NOT be src-tauri/ — Tauri's file watcher restarts the app on any file change.
fn resolve_sidecar_cwd(app: &AppHandle, bundle_dir: &std::path::Path) -> std::path::PathBuf {
    // Production: bundle dir has leyline.toml
    if bundle_dir.join("leyline.toml").exists() {
        return bundle_dir.to_path_buf();
    }

    // Dev: walk up from bundle to find repo root (has leyline.toml)
    let mut dir = bundle_dir.to_path_buf();
    loop {
        if dir.join("leyline.toml").exists() {
            return dir;
        }
        if !dir.pop() {
            break;
        }
    }

    // Fallback: app log dir (safe, outside watch path)
    app.path()
        .app_log_dir()
        .unwrap_or_else(|_| std::path::PathBuf::from("/tmp"))
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

    let bundle_dir = resolve_bundle_dir(&app)?;
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
        .unwrap_or_else(|_| std::path::PathBuf::from("/tmp"));
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

    let mut cmd = Command::new(&bin_path);
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

        for _ in 0..60 {
            tokio::time::sleep(Duration::from_millis(500)).await;

            // Check if process died
            {
                let server = app_handle.state::<ServerProcess>();
                let mut guard = server.child.lock().unwrap();
                if let Some(ref mut child) = *guard {
                    if let Ok(Some(status)) = child.try_wait() {
                        let msg = format!("Server exited with {status}");
                        drop(guard);
                        server.set_state(ServerState::Error(msg), &app_handle);
                        return;
                    }
                }
            }

            if let Ok(resp) = client.get("http://127.0.0.1:8091/health").send().await {
                if resp.status().is_success() {
                    let server = app_handle.state::<ServerProcess>();
                    server.set_state(ServerState::Running, &app_handle);
                    return;
                }
            }
        }

        let server = app_handle.state::<ServerProcess>();
        server.set_state(
            ServerState::Error("Health check timed out after 30s".into()),
            &app_handle,
        );
    });

    Ok(())
}

#[tauri::command]
pub fn stop_server(app: AppHandle) -> Result<(), String> {
    let server = app.state::<ServerProcess>();
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
