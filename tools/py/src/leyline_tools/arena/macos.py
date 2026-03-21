from __future__ import annotations

import subprocess
import time
from pathlib import Path

from .paths import NATIVE_DIR, PROJECT_DIR, REFERENCE_WIDTH
from .shell import run

_cached_bounds: tuple[int, int, int, int] | None = None
_cached_bounds_ts: float = 0.0
_BOUNDS_TTL = 1.0
_last_activate: float = 0.0
_ACTIVATE_DEDUP = 2.0


def _applescript_quote(text: str) -> str:
    return text.replace("\\", "\\\\").replace('"', '\\"')


def _run_osascript(script: str) -> tuple[int, str, str]:
    return run("osascript", "-e", script)


def _send_keystroke(text: str) -> tuple[int, str, str]:
    return _run_osascript(
        f'tell application "System Events" to keystroke "{_applescript_quote(text)}"'
    )


def _send_keycode(code: int) -> tuple[int, str, str]:
    return _run_osascript(f'tell application "System Events" to key code {code}')


def _send_command_a() -> tuple[int, str, str]:
    return _run_osascript(
        'tell application "System Events" to keystroke "a" using command down'
    )


def _send_command_v() -> tuple[int, str, str]:
    return _run_osascript(
        'tell application "System Events" to keystroke "v" using command down'
    )


def _copy_to_clipboard(text: str) -> tuple[int, str]:
    try:
        subprocess.run(
            ["pbcopy"],
            input=text,
            text=True,
            capture_output=True,
            check=True,
            cwd=PROJECT_DIR,
        )
        return 0, ""
    except subprocess.CalledProcessError as e:
        return e.returncode, e.stderr.strip()
    except FileNotFoundError:
        return 1, "command not found: pbcopy"


def _type_text_slow(
    text: str, delay_ms: int = 120, initial_delay_ms: int = 250
) -> tuple[int, str]:
    time.sleep(initial_delay_ms / 1000)
    for ch in text:
        code, _, stderr = _send_keystroke(ch)
        if code != 0:
            return code, stderr or f"failed on character {ch!r}"
        time.sleep(delay_ms / 1000)
    return 0, ""


def ocr(image_path: str, *extra_args: str) -> tuple[int, str, str]:
    return run(f"{NATIVE_DIR}/ocr", image_path, "--json", *extra_args)


def _activate_mtga() -> None:
    global _last_activate
    now = time.time()
    if now - _last_activate < _ACTIVATE_DEDUP:
        return
    run("osascript", "-e", 'tell application "MTGA" to activate')
    time.sleep(0.3)
    _last_activate = time.time()


def _reset_activate() -> None:
    global _last_activate
    _last_activate = 0.0


def mtga_window_bounds() -> tuple[int, int, int, int] | None:
    global _cached_bounds, _cached_bounds_ts
    now = time.time()
    if _cached_bounds is not None and now - _cached_bounds_ts < _BOUNDS_TTL:
        return _cached_bounds

    code, stdout, _ = run(f"{NATIVE_DIR}/window-bounds")
    if code != 0 or not stdout.strip():
        return None
    parts = stdout.split()
    if len(parts) != 4:
        return None
    bounds = (int(parts[0]), int(parts[1]), int(parts[2]), int(parts[3]))
    _cached_bounds = bounds
    _cached_bounds_ts = time.time()
    return bounds


def click_screen(x: int, y: int, action: str = "click") -> tuple[int, str, str]:
    _activate_mtga()
    return run(f"{NATIVE_DIR}/click", str(x), str(y), action)


def drag_screen(x1: int, y1: int, x2: int, y2: int) -> tuple[int, str, str]:
    _activate_mtga()
    return run(f"{NATIVE_DIR}/click", str(x1), str(y1), "drag", str(x2), str(y2))


def _mtga_window_id() -> int | None:
    try:
        import Quartz

        windows = Quartz.CGWindowListCopyWindowInfo(
            Quartz.kCGWindowListOptionAll, Quartz.kCGNullWindowID
        )
        for w in windows:
            if (
                w.get("kCGWindowOwnerName") == "MTGA"
                and w.get("kCGWindowName") == "MTGA"
            ):
                return int(w["kCGWindowNumber"])
    except ImportError:
        pass
    return None


def capture_window(
    out_path: str, *, hires: bool = False
) -> tuple[int, int, int, int] | None:
    _activate_mtga()
    bounds = mtga_window_bounds()
    if bounds is None:
        return None

    x, y, w, h = bounds
    code, _, _ = run("screencapture", "-R", f"{x},{y},{w},{h}", "-x", out_path)
    if code != 0:
        return None

    if not hires:
        run(
            "sips",
            "--resampleWidth",
            str(REFERENCE_WIDTH),
            out_path,
            "--out",
            out_path,
        )
    return bounds
