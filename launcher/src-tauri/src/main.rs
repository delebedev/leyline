#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod arena;
mod server;
mod tls;

use server::ServerProcess;
use tauri::Manager;

/// Read bundled changelog (release notes) from Tauri resources.
#[tauri::command]
fn get_changelog(app: tauri::AppHandle) -> Result<String, String> {
    let resource_dir = app
        .path()
        .resource_dir()
        .map_err(|e| format!("No resource dir: {e}"))?;

    let changelog = resource_dir.join(".bundle-stage").join("changelog.md");
    if changelog.exists() {
        std::fs::read_to_string(&changelog)
            .map_err(|e| format!("Failed to read changelog: {e}"))
    } else {
        // Dev fallback: read from repo root
        let dev = std::env::current_dir()
            .unwrap_or_default()
            .join("../.changelog.md");
        if dev.exists() {
            std::fs::read_to_string(&dev)
                .map_err(|e| format!("Failed to read dev changelog: {e}"))
        } else {
            Ok(String::new())
        }
    }
}

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .manage(ServerProcess::new())
        .invoke_handler(tauri::generate_handler![
            server::start_server,
            server::stop_server,
            server::server_status,
            arena::detect_arena,
            arena::deploy_config,
            arena::restore_arena,
            arena::launch_mtga,
            get_changelog,
        ])
        .build(tauri::generate_context!())
        .expect("error while building leyline launcher")
        .run(|app, event| {
            if let tauri::RunEvent::ExitRequested { .. } = event {
                let _ = server::stop_server(app.clone());
            }
        });
}
