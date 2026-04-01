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

/// Resolve the path to `bin/leyline` inside the bundled resources.
fn resolve_leyline_bin(app: &AppHandle) -> Result<std::path::PathBuf, String> {
    let resource_dir = app
        .path()
        .resource_dir()
        .map_err(|e| format!("No resource dir: {e}"))?;

    let bin = resource_dir.join("leyline").join("bin").join("leyline");
    if bin.exists() {
        return Ok(bin);
    }

    // Dev fallback: use build/bundle from project root
    let dev_bin = std::env::current_dir()
        .unwrap_or_default()
        .join("../build/bundle/bin/leyline");
    if dev_bin.exists() {
        return Ok(dev_bin.canonicalize().unwrap_or(dev_bin));
    }

    Err(format!(
        "leyline binary not found at {:?} or dev fallback",
        bin
    ))
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

    let bin_path = resolve_leyline_bin(&app)?;
    server.set_state(ServerState::Starting, &app);

    let child = Command::new(&bin_path)
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
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
