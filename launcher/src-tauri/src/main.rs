#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod arena;
mod server;

use server::ServerProcess;

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
        ])
        .build(tauri::generate_context!())
        .expect("error while building leyline launcher")
        .run(|app, event| {
            if let tauri::RunEvent::ExitRequested { .. } = event {
                let _ = server::stop_server(app.clone());
            }
        });
}
