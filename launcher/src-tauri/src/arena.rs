use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;

use serde::Serialize;
use tauri::AppHandle;

/// Known MTGA install locations, tagged by storefront.
const KNOWN_INSTALLS: &[(&str, &str)] = &[
    ("Epic", "/Users/Shared/Epic Games/MagicTheGathering/MTGA.app"),
    // Steam path uses ~ — expanded at runtime below
];

const SERVICES_CONF: &str = include_str!("../../resources/services.conf");
const NPE_VO_BNK: &[u8] = include_bytes!("../../resources/NPE_VO.bnk");

#[derive(Debug, Clone, Serialize)]
pub struct ArenaInfo {
    pub path: String,
    pub source: String,
    pub configured: bool,
}

/// Find MTGA.app at known locations, returning (path, source).
fn find_arena() -> Option<(PathBuf, String)> {
    for &(source, p) in KNOWN_INSTALLS {
        let path = PathBuf::from(p);
        if path.exists() {
            return Some((path, source.into()));
        }
    }

    // Steam path with home expansion
    if let Some(home) = dirs::home_dir() {
        let steam = home
            .join("Library/Application Support/Steam/steamapps/common/MTGA/MTGA.app");
        if steam.exists() {
            return Some((steam, "Steam".into()));
        }
    }

    // Saved path from previous session
    if let Some((saved, source)) = load_saved_path() {
        if saved.exists() {
            return Some((saved, source));
        }
    }

    None
}

fn streaming_assets(mtga_path: &Path) -> PathBuf {
    mtga_path.join("Contents/Resources/Data/StreamingAssets")
}

fn audio_dir(streaming: &Path) -> PathBuf {
    streaming.join("Audio/GeneratedSoundBanks/Mac")
}

fn config_dir() -> Option<PathBuf> {
    dirs::config_dir().map(|d| d.join("dev.leyline.launcher"))
}

fn save_arena_path(path: &Path, source: &str) {
    if let Some(dir) = config_dir() {
        let _ = fs::create_dir_all(&dir);
        let data = format!("{}\n{}", source, path.to_string_lossy());
        let _ = fs::write(dir.join("arena_path"), data.as_bytes());
    }
}

fn load_saved_path() -> Option<(PathBuf, String)> {
    let dir = config_dir()?;
    let data = fs::read_to_string(dir.join("arena_path")).ok()?;
    let mut lines = data.lines();
    let source = lines.next()?.to_string();
    let path = PathBuf::from(lines.next()?.trim());
    if path.exists() {
        Some((path, source))
    } else {
        None
    }
}

fn is_configured(streaming: &Path) -> bool {
    streaming.join("services.conf").exists()
}

#[tauri::command]
pub fn detect_arena(_app: AppHandle) -> Result<ArenaInfo, String> {
    let (path, source) = find_arena()
        .ok_or("MTGA not found. Install Arena via Epic Games or Steam.")?;
    let streaming = streaming_assets(&path);

    save_arena_path(&path, &source);

    Ok(ArenaInfo {
        path: path.to_string_lossy().into(),
        source,
        configured: is_configured(&streaming),
    })
}

/// Deploy services.conf + NPE_VO.bnk, set macOS defaults.
#[tauri::command]
pub fn deploy_config(_app: AppHandle) -> Result<(), String> {
    let (mtga, _) = find_arena().ok_or("MTGA not found")?;
    let streaming = streaming_assets(&mtga);

    if !streaming.exists() {
        return Err(format!("StreamingAssets not found: {:?}", streaming));
    }

    // 1. services.conf
    fs::write(streaming.join("services.conf"), SERVICES_CONF)
        .map_err(|e| format!("Failed to write services.conf: {e}"))?;

    // 2. NPE_VO.bnk stub
    let audio = audio_dir(&streaming);
    let bnk = audio.join("NPE_VO.bnk");
    if !bnk.exists() {
        fs::create_dir_all(&audio)
            .map_err(|e| format!("Failed to create audio dir: {e}"))?;
        fs::write(&bnk, NPE_VO_BNK)
            .map_err(|e| format!("Failed to write NPE_VO.bnk: {e}"))?;
    }

    // 3. macOS defaults
    let _ = Command::new("defaults")
        .args(["write", "com.Wizards.MtGA", "CheckSC", "-integer", "0"])
        .output();
    let _ = Command::new("defaults")
        .args(["write", "com.Wizards.MtGA", "HashFilesOnStartup", "-integer", "0"])
        .output();

    Ok(())
}

/// Remove services.conf, restore macOS defaults.
#[tauri::command]
pub fn restore_arena(_app: AppHandle) -> Result<(), String> {
    let (mtga, _) = find_arena().ok_or("MTGA not found")?;
    let streaming = streaming_assets(&mtga);

    // 1. Remove services.conf
    let conf = streaming.join("services.conf");
    if conf.exists() {
        fs::remove_file(&conf)
            .map_err(|e| format!("Failed to remove services.conf: {e}"))?;
    }

    // 2. Restore macOS defaults
    let _ = Command::new("defaults")
        .args(["delete", "com.Wizards.MtGA", "CheckSC"])
        .output();
    // Keep HashFilesOnStartup=0 — deleting reverts to default (verify all),
    // which triggers ~1.5GB re-download of Audio assets on next real-server boot.
    let _ = Command::new("defaults")
        .args(["write", "com.Wizards.MtGA", "HashFilesOnStartup", "-integer", "0"])
        .output();

    Ok(())
}

/// Launch MTGA.app
#[tauri::command]
pub fn launch_mtga(_app: AppHandle) -> Result<(), String> {
    let (mtga, _) = find_arena().ok_or("MTGA not found")?;

    Command::new("open")
        .arg("-a")
        .arg(&mtga)
        .spawn()
        .map_err(|e| format!("Failed to launch MTGA: {e}"))?;

    Ok(())
}
