#!/usr/bin/env python3
"""
Arena CLI — MTGA window automation.

Commands: launch, capture, ocr, click, drag, play, state, errors, scene, where, navigate, wait, board, detect, issues
Zero external deps. Shells out to bin/click, bin/ocr, bin/window-bounds, peekaboo, sips.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import NoReturn
from urllib.error import URLError
from urllib.request import Request, urlopen

# ---------------------------------------------------------------------------
# Project root
# ---------------------------------------------------------------------------


def _find_project_dir() -> str:
    d = Path.cwd()
    while d != d.parent:
        if (d / "justfile").exists():
            return str(d)
        d = d.parent
    # fallback: script lives in bin/, project is one up
    return str(Path(__file__).resolve().parent.parent)


PROJECT_DIR = _find_project_dir()

# ---------------------------------------------------------------------------
# Shell helpers
# ---------------------------------------------------------------------------


def run(*cmd: str, timeout: int = 30) -> tuple[int, str, str]:
    """Run a command, return (exit_code, stdout, stderr)."""
    try:
        p = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=timeout,
            cwd=PROJECT_DIR,
        )
        return p.returncode, p.stdout.strip(), p.stderr.strip()
    except subprocess.TimeoutExpired:
        return 1, "", "timeout"
    except FileNotFoundError:
        return 1, "", f"command not found: {cmd[0]}"


def ocr(image_path: str, *extra_args: str) -> tuple[int, str, str]:
    return run(f"{PROJECT_DIR}/bin/ocr", image_path, "--json", *extra_args)


# Canonical coord space: 960-wide (macOS logical on 2x Retina with 1920 Unity render).
# All coords in guidance, transition tables, and arena_screens.py use this space.
# OCR normalizes to this space; cmd_click scales from this space to actual window coords.
REFERENCE_WIDTH = 960

# Window bounds cache (5s TTL)
_cached_bounds: tuple[int, int, int, int] | None = None
_cached_bounds_ts: float = 0.0
BOUNDS_TTL = 5.0

# Activate dedup (2s window)
_last_activate: float = 0.0
ACTIVATE_DEDUP = 2.0


def _activate_mtga() -> None:
    global _last_activate
    now = time.time()
    if now - _last_activate < ACTIVATE_DEDUP:
        return
    run("osascript", "-e", 'tell application "MTGA" to activate')
    time.sleep(0.15)
    _last_activate = time.time()


def mtga_window_bounds() -> tuple[int, int, int, int] | None:
    """Get MTGA window bounds (x, y, w, h) in logical points. Cached 5s."""
    global _cached_bounds, _cached_bounds_ts
    now = time.time()
    if _cached_bounds is not None and now - _cached_bounds_ts < BOUNDS_TTL:
        return _cached_bounds

    code, stdout, _ = run(f"{PROJECT_DIR}/bin/window-bounds")
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
    """Click at screen-absolute coordinates. Activates MTGA first."""
    _activate_mtga()
    return run(f"{PROJECT_DIR}/bin/click", str(x), str(y), action)


def drag_screen(x1: int, y1: int, x2: int, y2: int) -> tuple[int, str, str]:
    _activate_mtga()
    return run(f"{PROJECT_DIR}/bin/click", str(x1), str(y1), "drag", str(x2), str(y2))


def peekaboo(*args: str) -> tuple[int, str, str]:
    return run("/opt/homebrew/bin/peekaboo", *args)


def sips(*args: str) -> tuple[int, str, str]:
    return run("sips", *args)


def _mtga_window_id() -> int | None:
    """Get MTGA main window CGWindowID via Quartz. Works without activation."""
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
    """Capture MTGA window rect. Activates first, then screencapture -R at window bounds.

    By default resizes to logical size so OCR coords = window-relative click coords.
    With hires=True, keeps retina resolution (2x) for better text recognition.
    """
    _activate_mtga()
    bounds = mtga_window_bounds()
    if bounds is None:
        return None

    x, y, w, h = bounds
    code, _, _ = run("screencapture", "-R", f"{x},{y},{w},{h}", "-x", out_path)
    if code != 0:
        return None

    if not hires:
        # Retina captures at 2x — resize to logical so OCR coords match click coords
        run("sips", "--resampleWidth", str(w), out_path, "--out", out_path)
    return bounds


# ---------------------------------------------------------------------------
# Debug API
# ---------------------------------------------------------------------------


def fetch_api(path: str) -> str | None:
    try:
        req = Request(f"http://localhost:8090{path}")
        with urlopen(req, timeout=2) as resp:
            if resp.status == 200:
                return resp.read().decode()
    except (URLError, OSError, TimeoutError):
        pass
    return None


# ---------------------------------------------------------------------------
# Scry fallback (Player.log parser — works without debug server)
# ---------------------------------------------------------------------------

_scry_cache: dict | None = None
_scry_cache_ts: float = 0.0
_SCRY_TTL = 1.0  # cache 1s — scry takes ~0.3s


def _scry_state() -> dict | None:
    """Run bin/scry state, return parsed JSON dict or None on failure.

    Cached for 1s to avoid hammering the subprocess during polling loops.
    """
    global _scry_cache, _scry_cache_ts
    now = time.time()
    if _scry_cache is not None and now - _scry_cache_ts < _SCRY_TTL:
        return _scry_cache

    scry_path = Path(__file__).resolve().parent / "scry"
    try:
        p = subprocess.run(
            [str(scry_path), "state"],
            capture_output=True,
            text=True,
            timeout=10,
            cwd=PROJECT_DIR,
        )
        if p.returncode != 0 or not p.stdout.strip():
            return None
        data = json.loads(p.stdout)
        _scry_cache = data
        _scry_cache_ts = time.time()
        return data
    except (subprocess.TimeoutExpired, json.JSONDecodeError, FileNotFoundError, OSError):
        return None


def _normalize_zone(zone_type: str) -> str:
    """Normalize scry zone names (ZoneType_Hand → Hand, ZoneType_Battlefield → Battlefield)."""
    if zone_type.startswith("ZoneType_"):
        return zone_type[len("ZoneType_"):]
    return zone_type


_SCENE_RE = re.compile(r"\[UnityCrossThreadLogger\]Client\.SceneChange\s+(\{.+\})")
_CONNECT_RE = re.compile(r'"type"\s*:\s*"GREMessageType_ConnectResp"')
_GAME_OVER_RE = re.compile(r'"gameOver"\s*:\s*true')
_RESULT_RE = re.compile(r'"ResultType_WinLoss"')

_TAIL_BYTES = 1024 * 1024  # 1MB from end — GRE messages are bulky


def get_current_scene() -> str | None:
    """One-shot Player.log tail for latest scene.

    Reads last ~256KB of the log (not the whole file). Scans for:
    - Client.SceneChange → lobby scene name
    - GRE ConnectResp → "InGame"
    - gameOver: true → "PostGame"
    Last-writer-wins.
    """
    log_path = Path.home() / "Library/Logs/Wizards of the Coast/MTGA/Player.log"
    if not log_path.exists():
        return None
    size = log_path.stat().st_size
    offset = max(0, size - _TAIL_BYTES)
    with open(log_path, "r", errors="replace") as f:
        if offset > 0:
            f.seek(offset)
            f.readline()  # discard partial line
        current = None
        for line in f:
            m = _SCENE_RE.search(line)
            if m:
                try:
                    raw = json.loads(m.group(1))
                    current = raw.get("toSceneName")
                except (json.JSONDecodeError, ValueError):
                    continue
            elif _CONNECT_RE.search(line):
                current = "InGame"
            elif _GAME_OVER_RE.search(line) or _RESULT_RE.search(line):
                current = "PostGame"
    return current


def poll_scene(target: str, timeout_ms: int) -> bool:
    """Poll Player.log for a SceneChange to target scene."""
    deadline = time.time() + timeout_ms / 1000
    while time.time() < deadline:
        scene = get_current_scene()
        if scene and scene.lower() == target.lower():
            return True
        time.sleep(0.5)
    return False


def poll_state(condition: str, timeout_ms: int) -> bool:
    field, value = condition.split("=", 1)
    deadline = time.time() + timeout_ms / 1000
    while time.time() < deadline:
        body = fetch_api("/api/state")
        if body and f'"{field}"' in body and value.lower() in body.lower():
            return True
        time.sleep(0.2)
    return False


# ---------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------


def cmd_launch(args: list[str]) -> None:
    width, height, kill = 1920, 1080, False
    it = iter(args)
    for a in it:
        if a == "--width":
            width = int(next(it))
        elif a == "--height":
            height = int(next(it))
        elif a == "--kill":
            kill = True
        else:
            die(f"Unknown arg: {a}")

    if kill:
        run("osascript", "-e", 'tell application "MTGA" to quit')
        time.sleep(2)

    code, _, stderr = run(
        "open",
        "-a",
        "MTGA",
        "--args",
        "-screen-width",
        str(width),
        "-screen-height",
        str(height),
        "-screen-fullscreen",
        "0",
    )
    if code != 0:
        die(f"Failed to launch MTGA: {stderr}")
    print(f"MTGA launched ({width}x{height} windowed)")


def cmd_capture(args: list[str]) -> None:
    out = "/tmp/arena/screen.png"
    resolution = 1280

    it = iter(args)
    for a in it:
        if a == "--out":
            out = next(it)
        elif a == "--resolution":
            resolution = int(next(it))
        else:
            die(f"Unknown flag: {a}")

    Path(out).parent.mkdir(parents=True, exist_ok=True)

    full = "/tmp/arena/_capture_full.png"
    bounds = capture_window(full)
    if bounds is None:
        die("MTGA window not found")

    _, _, bw, _ = bounds
    if resolution < bw:
        sips("--resampleWidth", str(resolution), full, "--out", out)
        try:
            os.remove(full)
        except OSError:
            pass
    else:
        os.rename(full, out)

    size_kb = Path(out).stat().st_size // 1024
    print(f"{out} ({size_kb}KB, {resolution}px)")


def cmd_ocr(args: list[str]) -> None:
    find_text = None
    fmt = False
    hires = True  # default: retina resolution for better text recognition
    it = iter(args)
    for a in it:
        if a == "--find":
            find_text = next(it)
        elif a == "--fmt":
            fmt = True
        elif a == "--hires":
            hires = True
        elif a == "--lores":
            hires = False
        elif a in ("--json", "--no-json"):
            pass
        else:
            die(f"Unknown flag: {a}")

    img = "/tmp/arena/_ocr_capture.png"
    Path(img).parent.mkdir(parents=True, exist_ok=True)

    bounds = capture_window(img, hires=hires)
    if bounds is None:
        die("MTGA window not found")

    ocr_args: list[str] = []
    if find_text is not None:
        ocr_args += ["--find", find_text]

    code, stdout, stderr = ocr(img, *ocr_args)

    # Measure capture pixel width BEFORE deleting the file (needed for coord scaling)
    capture_pixel_width = 0
    if hires:
        _, sips_out, _ = run("sips", "-g", "pixelWidth", img)
        m_sips = re.search(r"pixelWidth:\s*(\d+)", sips_out)
        if m_sips:
            capture_pixel_width = int(m_sips.group(1))
    _try_remove(img)

    if code != 0:
        if find_text:
            die(f'"{find_text}" not found')
        else:
            die(f"OCR failed: {stderr}")

    if hires and capture_pixel_width > 0:
        # Normalize OCR coords to REFERENCE_WIDTH (960) space.
        # This works on both 1x and 2x displays:
        #   2x Retina: capture=1920px → scale=2 → 960-space coords
        #   1x:        capture=1920px → scale=2 → 960-space coords
        # cmd_click scales back to actual window-relative when clicking.
        scale = max(1, round(capture_pixel_width / REFERENCE_WIDTH))
        items = json.loads(stdout)
        if scale > 1:
            for item in items:
                for key in ("cx", "cy", "x", "y", "w", "h"):
                    if key in item:
                        item[key] = item[key] // scale
        stdout = json.dumps(items)

    if fmt:
        items = json.loads(stdout) if isinstance(stdout, str) else stdout
        for item in items:
            print(f"{item['text']:40s} ({item['cx']},{item['cy']})")
    else:
        print(stdout)


def cmd_detect(args: list[str]) -> None:
    """Run card detection on current MTGA window. Outputs JSON array of detections."""
    threshold = 0.3
    it = iter(args)
    for a in it:
        if a == "--threshold":
            threshold = float(next(it))

    img = "/tmp/arena/_detect_capture.png"
    Path(img).parent.mkdir(parents=True, exist_ok=True)

    bounds = capture_window(img)
    if bounds is None:
        die("MTGA window not found")

    code, stdout, stderr = run(
        "swift",
        f"{PROJECT_DIR}/bin/detect.swift",
        img,
        "--threshold",
        str(threshold),
        timeout=15,
    )
    _try_remove(img)

    if code != 0:
        die(f"Detection failed: {stderr}")

    print(stdout)


def cmd_move(args: list[str]) -> None:
    """Move cursor to window-relative coords. Usage: arena move <x>,<y>"""
    if not args:
        die("Usage: arena move <x>,<y>")
    m = re.fullmatch(r"(\d+),(\d+)", args[0])
    if not m:
        die("Usage: arena move <x>,<y>")
    ref_x, ref_y = int(m.group(1)), int(m.group(2))
    bounds = mtga_window_bounds()
    if bounds is None:
        die("MTGA window not found")
    scale = bounds[2] / REFERENCE_WIDTH if REFERENCE_WIDTH > 0 else 1.0
    wx, wy = int(ref_x * scale), int(ref_y * scale)
    sx, sy = bounds[0] + wx, bounds[1] + wy
    code, _, stderr = click_screen(sx, sy, "move")
    if code != 0:
        die(f"move failed: {stderr}")
    print(f"moved to ({ref_x},{ref_y}) → screen ({sx},{sy})")


def cmd_click(args: list[str]) -> None:
    if not args:
        die("Usage: arena click <target> [--double] [--right] [--retry N] [--exact]")

    target = args[0]
    action = (
        "double" if "--double" in args else "right" if "--right" in args else "click"
    )
    exact = "--exact" in args

    max_retries = 0
    if "--retry" in args:
        idx = args.index("--retry")
        if idx + 1 < len(args):
            max_retries = int(args[idx + 1])

    # Coordinate click: x,y (coords expected in REFERENCE_WIDTH=960 space)
    m = re.fullmatch(r"(\d+),(\d+)", target)
    if m:
        ref_x, ref_y = int(m.group(1)), int(m.group(2))
        bounds = mtga_window_bounds()
        if bounds is None:
            die("MTGA window not found")
        # Scale from 960-space to actual window-relative coords
        win_w = bounds[2]
        scale = win_w / REFERENCE_WIDTH if REFERENCE_WIDTH > 0 else 1.0
        wx = int(ref_x * scale)
        wy = int(ref_y * scale)
        sx, sy = bounds[0] + wx, bounds[1] + wy
        code, _, stderr = click_screen(sx, sy, action)
        if code != 0:
            die(f"click failed: {stderr}")
        if scale != 1.0:
            print(
                f"clicked ({ref_x},{ref_y}) ×{scale:.1f} → ({wx},{wy}) → screen ({sx},{sy})"
            )
        else:
            print(f"clicked ({wx},{wy}) → screen ({sx},{sy})")
        return

    # Text click with retry
    img = "/tmp/arena/_click_capture.png"
    Path(img).parent.mkdir(parents=True, exist_ok=True)

    for attempt in range(max_retries + 1):
        if attempt > 0:
            print(f'retry {attempt}/{max_retries} for "{target}"...', file=sys.stderr)
            time.sleep(0.5)

        bounds = capture_window(img)
        if bounds is None:
            if attempt == max_retries:
                die("MTGA window not found")
            continue

        ocr_args = ["--find", target]
        if exact:
            ocr_args.append("--exact")
        code, stdout, _ = ocr(img, *ocr_args)
        if code != 0 or not stdout.strip():
            if attempt == max_retries:
                _try_remove(img)
                die(f'"{target}" not found on screen')
            continue

        matches = json.loads(stdout)
        if not matches:
            if attempt == max_retries:
                _try_remove(img)
                die(f'"{target}" not found on screen')
            continue

        _try_remove(img)
        first = matches[0]
        cx = int(float(first["cx"]))
        cy = int(float(first["cy"]))
        sx = bounds[0] + cx
        sy = bounds[1] + cy

        code, _, stderr = click_screen(sx, sy, action)
        if code != 0:
            die(f"click failed: {stderr}")
        print(f'clicked "{target}" at ({cx}, {cy}) → screen ({sx}, {sy})')
        return


def _do_drag(fr: tuple[int, int], to: tuple[int, int]) -> None:
    """Low-level drag between coords in REFERENCE_WIDTH (960) space."""
    bounds = mtga_window_bounds()
    if bounds is None:
        die("MTGA window not found")
    scale = bounds[2] / REFERENCE_WIDTH if REFERENCE_WIDTH > 0 else 1.0
    sx1 = bounds[0] + int(fr[0] * scale)
    sy1 = bounds[1] + int(fr[1] * scale)
    sx2 = bounds[0] + int(to[0] * scale)
    sy2 = bounds[1] + int(to[1] * scale)
    code, _, stderr = drag_screen(sx1, sy1, sx2, sy2)
    if code != 0:
        die(f"drag failed: {stderr}")


def _verified_drag(
    fr: tuple[int, int],
    to: tuple[int, int],
    instance_id: int | None = None,
    max_attempts: int = 3,
) -> bool:
    """Drag with retry + verification via debug API zone change.

    If instance_id is provided, polls the debug API to confirm the card
    left its original zone. Retries with jitter on failure.
    """
    import random

    for attempt in range(max_attempts):
        # Get pre-drag zone if we have an instance to track
        pre_zone = None
        if instance_id is not None:
            pre_zone = _get_card_zone(instance_id)

        # Apply jitter on retries (±5px)
        jitter = 0 if attempt == 0 else random.randint(-5, 5)
        src = (fr[0] + jitter, fr[1] + jitter)

        _do_drag(src, to)

        if instance_id is None:
            return True  # no verification possible

        # Poll for zone change (up to 2s)
        for _ in range(10):
            time.sleep(0.2)
            new_zone = _get_card_zone(instance_id)
            if new_zone != pre_zone:
                return True

        if attempt < max_attempts - 1:
            print(f"drag attempt {attempt + 1} failed, retrying...", file=sys.stderr)

    return False


def _get_card_zone(instance_id: int) -> str | None:
    """Get current zone for a card by instanceId.

    Tries debug API first, falls back to scry (Player.log parser).
    """
    # Primary: debug API
    raw = fetch_api("/api/id-map?active=true")
    if raw:
        entries = json.loads(raw)
        if isinstance(entries, dict):
            entries = entries.get("data", [])
        for e in entries:
            if e.get("instanceId") == instance_id:
                return e.get("forgeZone")

    # Fallback: scry state
    state = _scry_state()
    if state is None:
        return None
    for zone in state.get("zones", []):
        for obj in zone.get("objects", []):
            obj_id = obj.get("id") if isinstance(obj, dict) else obj
            if obj_id == instance_id:
                return _normalize_zone(zone.get("type", ""))
    return None


def cmd_drag(args: list[str]) -> None:
    if len(args) < 2:
        die("Usage: arena drag <from_x>,<from_y> <to_x>,<to_y> [--verify <instanceId>]")

    fr = _parse_coord(args[0])
    to = _parse_coord(args[1])
    if fr is None:
        die(f"Invalid from coord: {args[0]} (expected x,y)")
    if to is None:
        die(f"Invalid to coord: {args[1]} (expected x,y)")

    instance_id = None
    if "--verify" in args:
        idx = args.index("--verify")
        if idx + 1 < len(args):
            instance_id = int(args[idx + 1])

    if instance_id is not None:
        ok = _verified_drag(fr, to, instance_id)
        if ok:
            print(f"dragged ({fr[0]},{fr[1]}) → ({to[0]},{to[1]}) ✓ verified")
        else:
            die(f"drag failed after 3 attempts: card {instance_id} did not move")
    else:
        _do_drag(fr, to)
        print(f"dragged ({fr[0]},{fr[1]}) → ({to[0]},{to[1]})")


BATTLEFIELD_DROP = (480, 300)  # center of our battlefield area
HAND_MIN_CY = 490  # detections below this are hand cards, above are hover previews


def _find_hand_card(
    name: str,
) -> tuple[tuple[int, int], int] | None:
    """Find a hand card's screen coords and instanceId.

    Uses debug API for card identity + detection for screen position.
    Falls back to scry (Player.log parser) when debug API is unavailable.
    Returns ((cx, cy), instanceId) or None.
    """
    hand_cards: list[dict] = []

    # 1. Get hand — try debug API first, fall back to scry
    raw = fetch_api("/api/id-map?active=true")
    if raw:
        entries = json.loads(raw)
        if isinstance(entries, dict):
            entries = entries.get("data", [])
        hand_cards = [
            e
            for e in entries
            if e.get("forgeZone") == "Hand" and e.get("ownerSeatId") == 1
        ]
    else:
        # Fallback: scry state
        state = _scry_state()
        if state:
            for card in state.get("hand", []):
                # Normalize to same shape as debug API entries
                hand_cards.append(
                    {
                        "instanceId": card.get("id"),
                        "cardName": card.get("name", ""),
                    }
                )

    if not hand_cards:
        return None

    # Find card by name (case-insensitive partial match)
    target = None
    name_lower = name.lower()
    for card in hand_cards:
        card_name = (card.get("cardName") or "").lower()
        if card_name == name_lower or name_lower in card_name:
            target = card
            break

    if target is None:
        return None

    instance_id = target.get("instanceId", 0)

    # 2. Get screen coords via detection
    dets = _board_detect()
    hand_dets = sorted(
        [d for d in dets if d["label"] == "hand-card" and d["cy"] > HAND_MIN_CY],
        key=lambda d: d["cx"],
    )

    if not hand_dets:
        # Fallback: estimate position from card count
        count = len(hand_cards)
        idx = next(
            (i for i, c in enumerate(hand_cards) if c.get("instanceId") == instance_id),
            0,
        )
        spacing = min(80, 400 // max(count, 1))
        center_x = 480 - (count - 1) * spacing // 2 + idx * spacing
        return ((center_x, 530), instance_id)

    # 3. Match: if detection count == hand count, use index; otherwise best-effort
    idx = next(
        (i for i, c in enumerate(hand_cards) if c.get("instanceId") == instance_id),
        0,
    )
    if len(hand_dets) == len(hand_cards) and idx < len(hand_dets):
        det = hand_dets[idx]
    elif idx < len(hand_dets):
        det = hand_dets[idx]
    else:
        det = hand_dets[-1]  # last detection as fallback

    return ((det["cx"], det["cy"]), instance_id)


def cmd_play(args: list[str]) -> None:
    """Play a card from hand by name.

    Usage: arena play <card-name> [--to x,y]
    """
    if not args:
        die("Usage: arena play <card-name> [--to x,y]")

    # Parse card name (everything before flags)
    name_parts = []
    drop_to = BATTLEFIELD_DROP
    i = 0
    while i < len(args):
        if args[i] == "--to" and i + 1 < len(args):
            coord = _parse_coord(args[i + 1])
            if coord:
                drop_to = coord
            i += 2
            continue
        if not args[i].startswith("--"):
            name_parts.append(args[i])
        i += 1

    card_name = " ".join(name_parts)
    if not card_name:
        die("No card name provided")

    result = _find_hand_card(card_name)
    if result is None:
        die(f"Card '{card_name}' not found in hand")

    (cx, cy), instance_id = result
    print(f"Playing {card_name} (id={instance_id}) from ({cx},{cy})")

    ok = _verified_drag((cx, cy), drop_to, instance_id)
    if ok:
        print(f"✓ {card_name} played successfully")
    else:
        die(f"✗ Failed to play {card_name} after 3 attempts")


def cmd_state(args: list[str]) -> None:
    state = fetch_api("/api/state")
    if state is None:
        if not args or "--json" in args:
            print('{"source":"unavailable"}')
        else:
            print("Debug API unavailable (is the server running?)")
        return
    print(state)


def cmd_errors(args: list[str]) -> None:
    """Show client errors from scry (Player.log parser)."""
    import subprocess

    scry = Path(__file__).parent / "scry"
    result = subprocess.run(
        [str(scry), "state", "--no-cards"], capture_output=True, text=True
    )
    print(result.stdout if result.stdout else "{}")


def cmd_scene(args: list[str]) -> None:
    """Show current lobby screen from Player.log scene changes."""
    scene = get_current_scene()
    print(json.dumps({"current": scene}))


def detect_screen() -> str | None:
    """Identify current screen using scene (GRE + lobby) + OCR.

    Signal priority:
      1. Synthetic scene from GRE (InGame/PostGame) — authoritative for match state
      2. OCR anchors — discriminate between lobby sub-screens
      3. Lobby scene from Player.log — fallback

    Returns screen name from the state machine, or None if unrecognized.
    """
    from arena_screens import SCREENS

    scene = get_current_scene()

    # InGame/PostGame are synthetic scenes from GRE. They tell us we're
    # "in match context" but we still need OCR to discriminate sub-states
    # (Mulligan, ConcedMenu, Result) that share the same scene.
    # Fall through to OCR — InGame is the default if no sub-state matches.

    # OCR snapshot for lobby discrimination
    img = "/tmp/arena/_detect_screen.png"
    Path(img).parent.mkdir(parents=True, exist_ok=True)
    bounds = capture_window(img)
    if bounds is None:
        if scene:
            for name, s in SCREENS.items():
                if s.get("scene") == scene and not s.get("ocr_anchors"):
                    return name
            if scene == "Home":
                return "Home"
        return None

    code, stdout, _ = ocr(img)
    _try_remove(img)
    if code != 0 or not stdout.strip():
        ocr_texts: list[str] = []
    else:
        try:
            ocr_items = json.loads(stdout)
            ocr_texts = [item.get("text", "") for item in ocr_items]
        except (json.JSONDecodeError, ValueError):
            ocr_texts = []

    all_text = " ".join(ocr_texts).lower()

    def _has(anchor: str) -> bool:
        return anchor.lower() in all_text

    def _has_any(anchors: list[str]) -> bool:
        return any(_has(a) for a in anchors)

    # Score each screen by OCR match quality
    candidates: list[tuple[str, int]] = []
    for name, s in SCREENS.items():
        score = 0
        anchors = s.get("ocr_anchors", [])
        reject = s.get("ocr_reject", [])
        require_any = s.get("ocr_require_any", [])

        if reject and _has_any(reject):
            continue

        if anchors:
            if all(_has(a) for a in anchors):
                score += len(anchors) * 3
            else:
                continue

        if require_any:
            if _has_any(require_any):
                score += 2
            else:
                continue

        # Scene match bonus (works for both real and synthetic scenes)
        if s.get("scene") and s.get("scene") == scene:
            score += 1

        if score > 0:
            candidates.append((name, score))

    if not candidates:
        # Fall back to scene-only screens (no OCR anchors defined)
        if scene == "InGame":
            return "InGame"
        if scene == "PostGame":
            return "Result"
        if scene == "Home":
            return "Home"
        if scene == "EventLanding":
            return "EventLanding"
        return None

    candidates.sort(key=lambda c: c[1], reverse=True)
    return candidates[0][0]


def cmd_where(args: list[str]) -> None:
    """Detect and print current screen name."""
    screen = detect_screen()
    print(json.dumps({"screen": screen}))


_MAX_REROUTES = 3


def _try_dismiss_popups() -> str | None:
    """Check for and dismiss any popup overlay.

    Returns the popup name if one was dismissed, None otherwise.
    """
    from arena_screens import POPUPS

    img = "/tmp/arena/_popup_check.png"
    Path(img).parent.mkdir(parents=True, exist_ok=True)
    bounds = capture_window(img)
    if bounds is None:
        return None

    code, stdout, _ = ocr(img)
    _try_remove(img)
    if code != 0 or not stdout.strip():
        return None

    try:
        ocr_items = json.loads(stdout)
        ocr_texts = [item.get("text", "") for item in ocr_items]
    except (json.JSONDecodeError, ValueError):
        return None

    all_text = " ".join(ocr_texts).lower()

    def _has(anchor: str) -> bool:
        return anchor.lower() in all_text

    def _has_any(anchors: list[str]) -> bool:
        return any(_has(a) for a in anchors)

    for popup in POPUPS:
        anchors = popup.get("ocr_anchors", [])
        reject = popup.get("ocr_reject", [])
        require_any = popup.get("ocr_require_any", [])

        if reject and _has_any(reject):
            continue
        if not all(_has(a) for a in anchors):
            continue
        if require_any and not _has_any(require_any):
            continue

        # Matched — dismiss it
        name = popup["name"]
        print(f"  popup detected: {name}, dismissing...")
        for step in popup["dismiss"]:
            _exec_step(step)
        return name

    return None


def _exec_step(step: str) -> None:
    """Execute a single transition step (click, sleep, etc.)."""
    if step.startswith("sleep "):
        time.sleep(float(step.split()[1]))
        return
    step_args = _split_step(step)
    cmd_name = step_args[0]
    cmd_rest = step_args[1:]
    handler = COMMANDS.get(cmd_name)
    if handler is None:
        die(f"Unknown step command: {cmd_name}")
    handler(cmd_rest)


def _wait_condition(wait: str, timeout_sec: int) -> bool:
    """Evaluate a wait condition string. Returns True if matched."""
    if wait.startswith("scene="):
        return poll_scene(wait.removeprefix("scene="), timeout_sec * 1000)
    elif wait.startswith("text="):
        return _poll_ocr(
            wait.removeprefix("text="),
            present=True,
            timeout_ms=timeout_sec * 1000,
        )
    elif "=" in wait:
        return poll_state(wait, timeout_sec * 1000)
    return False


def cmd_navigate(args: list[str]) -> None:
    """Navigate to a target screen using the state machine graph.

    Robust: dismisses popups, re-detects after each transition,
    re-routes if landed on unexpected screen. Gives up after
    MAX_REROUTES failed attempts.

    Usage: arena navigate <target_screen>
    """
    from arena_screens import find_path

    if not args:
        die(
            "Usage: arena navigate <screen>\nScreens: Home, Play, FindMatch, Events, EventLanding, ..."
        )

    target = args[0]
    dry_run = "--dry-run" in args

    # Dismiss any popups before detecting
    _try_dismiss_popups()

    current = detect_screen()
    if current is None:
        die("Cannot detect current screen. Run 'arena ocr' to check.")

    if current == target:
        print(f"Already on {target}")
        return

    path = find_path(current, target)
    if path is None:
        die(f"No path from {current} to {target}")

    print(f"Navigating: {current} -> {' -> '.join(t['to'] for t in path)}")

    if dry_run:
        for t in path:
            print(f"  {t['from']} -> {t['to']}: {t.get('steps', [])}")
        return

    reroutes = 0

    while path:
        t = path[0]
        steps = t.get("steps", [])
        wait = t.get("wait")
        wait_timeout = t.get("wait_timeout", 10)

        # Execute transition steps
        for step in steps:
            _exec_step(step)

        # Wait for expected condition
        ok = True
        if wait:
            ok = _wait_condition(wait, wait_timeout)

        if ok:
            print(f"  {t['from']} -> {t['to']} ok")
            path = path[1:]
            # Dismiss any popups that appeared after transition
            _try_dismiss_popups()
            continue

        # Wait failed — re-detect and try to recover
        reroutes += 1
        if reroutes > _MAX_REROUTES:
            die(
                f"Navigation failed: exceeded {_MAX_REROUTES} reroutes. "
                f"Last attempt: {t['from']} -> {t['to']}, wait '{wait}' timed out"
            )

        # Check for popups first
        popup = _try_dismiss_popups()
        if popup:
            print(f"  dismissed popup {popup}, retrying...")

        actual = detect_screen()
        if actual is None:
            die(
                f"Navigation stuck: cannot detect screen after "
                f"{t['from']} -> {t['to']} failed (wait '{wait}' timed out)"
            )

        if actual == target:
            print(f"  already at {target} (detected after reroute)")
            break

        # Re-plan from actual position
        new_path = find_path(actual, target)
        if new_path is None:
            die(f"Navigation stuck: no path from {actual} to {target}")

        print(
            f"  rerouting: expected {t['to']}, got {actual}. "
            f"New path: {actual} -> {' -> '.join(tt['to'] for tt in new_path)}"
        )
        path = new_path

    print(f"Arrived at {target}")


def _split_step(step: str) -> list[str]:
    """Split a step string into args, respecting quoted strings."""
    import shlex

    return shlex.split(step)


def cmd_board(args: list[str]) -> None:
    """Unified board state from debug API + OCR + card detection."""
    HAND_Y_CENTER, HAND_X_MIN, HAND_X_MAX = 530, 60, 720

    with_ocr = "--no-ocr" not in args
    with_detect = "--detect" in args
    detect_threshold = 0.3
    if "--detect-threshold" in args:
        idx = args.index("--detect-threshold")
        if idx + 1 < len(args):
            detect_threshold = float(args[idx + 1])

    # 1. Fetch match state
    state_raw = fetch_api("/api/state")
    if state_raw is None:
        die("Debug API unavailable")

    state = json.loads(state_raw)
    match_id = state.get("matchId")
    match_info = {
        "phase": state.get("phase"),
        "turn": state.get("turn", 0),
        "activePlayer": state.get("activePlayer"),
        "gameOver": state.get("gameOver", False),
    }

    if match_id is None:
        print(json.dumps({"match": match_info}, indent=2))
        return

    # 2. Fetch objects from id-map (bridge accumulator — always current)
    idmap_raw = fetch_api("/api/id-map?active=true")
    objects: list[dict] = []
    if idmap_raw:
        entries = json.loads(idmap_raw)
        if isinstance(entries, dict):
            entries = entries.get("data", [])
        for e in entries:
            objects.append(
                {
                    "instanceId": e.get("instanceId", 0),
                    "grpId": e.get("grpId", 0),
                    "name": e.get("cardName"),
                    "ownerSeatId": e.get("ownerSeatId", 0),
                    "controllerSeatId": e.get("ownerSeatId", 0),
                    "zoneId": e.get("protoZoneId", 0),
                    "forgeZone": e.get("forgeZone"),
                }
            )

    # 3. Fetch actions + life from latest game state snapshot
    actions: list[dict] = []
    players: dict[int, int] = {}
    gs_raw = fetch_api("/api/game-states")
    if gs_raw:
        snapshots = json.loads(gs_raw).get("data", [])
        # Life: last snapshot with players
        for snap in reversed(snapshots):
            if snap.get("players"):
                for p in snap["players"]:
                    players[p["seatId"]] = p["life"]
                break
        # Actions: always from latest snapshot
        if snapshots:
            for act in snapshots[-1].get("actions", []):
                actions.append(
                    {
                        "actionType": act.get("actionType", ""),
                        "instanceId": act.get("instanceId", 0),
                        "grpId": act.get("grpId", 0),
                        "name": act.get("name"),
                    }
                )

    # 4. Optionally get OCR
    ocr_items: list[dict] = []
    if with_ocr:
        ocr_items = _board_ocr()

    # 5. Group by zone using forgeZone strings from id-map
    our_seat, opp_seat = 1, 2
    actionable_ids = {a["instanceId"] for a in actions}

    our_hand = [
        o for o in objects if o["forgeZone"] == "Hand" and o["ownerSeatId"] == our_seat
    ]
    opp_hand = [
        o for o in objects if o["forgeZone"] == "Hand" and o["ownerSeatId"] == opp_seat
    ]
    bf = [o for o in objects if o["forgeZone"] == "Battlefield"]
    our_bf = [o for o in bf if o["controllerSeatId"] == our_seat]
    opp_bf = [o for o in bf if o["controllerSeatId"] == opp_seat]
    stack_cards = [o for o in objects if o["forgeZone"] == "Stack"]
    our_grave = [
        o
        for o in objects
        if o["forgeZone"] == "Graveyard" and o["ownerSeatId"] == our_seat
    ]
    opp_grave = [
        o
        for o in objects
        if o["forgeZone"] == "Graveyard" and o["ownerSeatId"] == opp_seat
    ]
    exile_cards = [o for o in objects if o["forgeZone"] == "Exile"]

    # 6. Correlate hand cards with OCR x-positions
    hand_ocr = [
        item
        for item in ocr_items
        if item["cy"] > 490 and HAND_X_MIN <= item["cx"] <= HAND_X_MAX
    ]
    if hand_ocr and our_hand:
        xs = sorted(item["cx"] for item in hand_ocr)
        clusters = [xs[0]]
        for x in xs[1:]:
            if x - clusters[-1] > 25:
                clusters.append(x)
            else:
                clusters[-1] = (clusters[-1] + x) // 2
        centers = clusters
    elif our_hand:
        count = len(our_hand)
        spacing = (HAND_X_MAX - HAND_X_MIN) // (count + 1)
        centers = [HAND_X_MIN + (i + 1) * spacing for i in range(count)]
    else:
        centers = []

    for i, card in enumerate(our_hand):
        if i < len(centers):
            card["estimatedX"] = centers[i]
        else:
            last_x = centers[-1] if centers else 400
            card["estimatedX"] = last_x + (i - len(centers) + 1) * 40
        card["estimatedY"] = HAND_Y_CENTER
        card["hasAction"] = card["instanceId"] in actionable_ids

    for card in our_bf:
        card["screenRegion"] = "our_battlefield"
        card["hasAction"] = card["instanceId"] in actionable_ids

    for card in opp_bf:
        card["screenRegion"] = "opp_battlefield"

    # 7. Card detection — correlate detected bboxes with protocol cards
    if with_detect:
        dets = _board_detect(threshold=detect_threshold)
        if dets:
            _correlate_detections(dets, our_hand, our_bf, opp_bf, stack_cards)

    board = {
        "match": match_info,
        "life": {"ours": players.get(our_seat, 0), "theirs": players.get(opp_seat, 0)},
        "hand": our_hand,
        "our_battlefield": our_bf,
        "opp_battlefield": opp_bf,
        "stack": stack_cards,
        "our_graveyard": {
            "count": len(our_grave),
            "cards": [c.get("name", "?") for c in our_grave],
        },
        "opp_graveyard": {
            "count": len(opp_grave),
            "cards": [c.get("name", "?") for c in opp_grave],
        },
        "exile": {
            "count": len(exile_cards),
            "cards": [c.get("name", "?") for c in exile_cards],
        },
        "opp_hand_count": len(opp_hand),
        "our_library_count": sum(
            1
            for o in objects
            if o["forgeZone"] == "Library" and o["ownerSeatId"] == our_seat
        ),
        "opp_library_count": sum(
            1
            for o in objects
            if o["forgeZone"] == "Library" and o["ownerSeatId"] == opp_seat
        ),
        "actions": actions,
    }

    # Quick OCR for the action button text (~888,505)
    if with_ocr:
        btn_items = [
            item
            for item in ocr_items
            if 860 < item["cx"] < 920 and 490 < item["cy"] < 520
        ]
        board["button"] = btn_items[0]["text"] if btn_items else None

    print(json.dumps(board, indent=2))


def _correlate_detections(
    dets: list[dict],
    our_hand: list[dict],
    our_bf: list[dict],
    opp_bf: list[dict],
    stack_cards: list[dict],
) -> None:
    """Correlate detected card bboxes with protocol-known cards.

    Strategy: partition detections by label into zones, then assign to protocol
    cards left-to-right (battlefield) or by existing estimatedX (hand).
    Mutates card dicts in-place, adding screenX/screenY/screenW/screenH.
    """
    # Partition detections by zone
    hand_dets = sorted(
        [d for d in dets if d["label"] == "hand-card"],
        key=lambda d: d["cx"],
    )
    our_bf_dets = sorted(
        [
            d
            for d in dets
            if d["label"] in ("battlefield-untapped", "battlefield-tapped")
        ],
        key=lambda d: d["cx"],
    )
    opp_bf_dets = sorted(
        [d for d in dets if d["label"] in ("opponent-untapped", "opponent-tapped")],
        key=lambda d: d["cx"],
    )
    stack_dets = [d for d in dets if d["label"] == "stack-item"]

    # Hand: if detection count matches protocol count, assign 1:1 left-to-right.
    # Otherwise, assign by nearest x to estimatedX.
    if hand_dets and our_hand:
        if len(hand_dets) == len(our_hand):
            for card, det in zip(our_hand, hand_dets):
                _apply_det(card, det)
        else:
            _match_nearest_x(our_hand, hand_dets)

    # Our battlefield: assign detections to cards left-to-right
    if our_bf_dets and our_bf:
        if len(our_bf_dets) == len(our_bf):
            for card, det in zip(our_bf, our_bf_dets):
                _apply_det(card, det)
        else:
            _match_nearest_x(our_bf, our_bf_dets)

    # Opponent battlefield
    if opp_bf_dets and opp_bf:
        if len(opp_bf_dets) == len(opp_bf):
            for card, det in zip(opp_bf, opp_bf_dets):
                _apply_det(card, det)
        else:
            _match_nearest_x(opp_bf, opp_bf_dets)

    # Stack: just attach first detection
    if stack_dets and stack_cards:
        for card, det in zip(stack_cards, stack_dets):
            _apply_det(card, det)


def _apply_det(card: dict, det: dict) -> None:
    """Add screen coordinates from a detection to a protocol card."""
    card["screenX"] = det["x"]
    card["screenY"] = det["y"]
    card["screenW"] = det["w"]
    card["screenH"] = det["h"]
    card["screenCX"] = det["cx"]
    card["screenCY"] = det["cy"]
    card["detectConfidence"] = det["confidence"]
    card["detectLabel"] = det["label"]


def _match_nearest_x(cards: list[dict], dets: list[dict]) -> None:
    """Greedy nearest-x matching: for each card, find closest unmatched detection."""
    used: set[int] = set()
    for card in cards:
        ref_x = card.get("estimatedX") or card.get("screenCX") or 0
        best_i, best_dist = -1, float("inf")
        for i, det in enumerate(dets):
            if i in used:
                continue
            dist = abs(det["cx"] - ref_x)
            if dist < best_dist:
                best_dist = dist
                best_i = i
        if best_i >= 0:
            used.add(best_i)
            _apply_det(card, dets[best_i])


def _board_ocr() -> list[dict]:
    """Capture + OCR for board command. Returns list of {text, cx, cy}."""
    try:
        img = "/tmp/arena/_board_ocr.png"
        Path(img).parent.mkdir(parents=True, exist_ok=True)
        if capture_window(img) is None:
            return []
        code, stdout, _ = ocr(img)
        _try_remove(img)
        if code != 0 or not stdout.strip():
            return []
        items = json.loads(stdout)
        return [
            {"text": item["text"], "cx": int(item["cx"]), "cy": int(item["cy"])}
            for item in items
        ]
    except Exception:
        return []


def _board_detect(threshold: float = 0.3) -> list[dict]:
    """Capture + card detection. Returns list of {label, x, y, w, h, cx, cy, confidence}."""
    try:
        img = "/tmp/arena/_board_detect.png"
        Path(img).parent.mkdir(parents=True, exist_ok=True)
        if capture_window(img) is None:
            return []
        code, stdout, _ = run(
            "swift",
            f"{PROJECT_DIR}/bin/detect.swift",
            img,
            "--threshold",
            str(threshold),
            timeout=15,
        )
        _try_remove(img)
        if code != 0 or not stdout.strip():
            return []
        return json.loads(stdout)
    except Exception:
        return []


def cmd_wait(args: list[str]) -> None:
    if not args:
        die(
            'Usage: arena wait <condition> [--timeout 30]\nConditions: phase=MAIN1, turn=3, text="Play", no-text="Loading"'
        )

    condition = args[0]
    timeout_sec = 30
    it = iter(args[1:])
    for a in it:
        if a == "--timeout":
            timeout_sec = int(next(it))

    timeout_ms = timeout_sec * 1000
    start = time.time()

    if condition.startswith("text="):
        matched = _poll_ocr(
            condition.removeprefix("text="), present=True, timeout_ms=timeout_ms
        )
    elif condition.startswith("no-text="):
        matched = _poll_ocr(
            condition.removeprefix("no-text="), present=False, timeout_ms=timeout_ms
        )
    elif condition.startswith("scene="):
        matched = poll_scene(condition.removeprefix("scene="), timeout_ms)
    elif "=" in condition:
        matched = poll_state(condition, timeout_ms)
    else:
        die(f"Unknown condition format: {condition}")

    elapsed = time.time() - start
    if matched:
        print(f"matched {condition} ({elapsed:.1f}s)")
    else:
        print(f"timeout waiting for {condition} ({timeout_sec}s)", file=sys.stderr)
        raise SystemExit(1)


def _poll_ocr(text: str, present: bool, timeout_ms: int) -> bool:
    deadline = time.time() + timeout_ms / 1000
    img = "/tmp/arena/_wait_capture.png"
    Path(img).parent.mkdir(parents=True, exist_ok=True)
    last_size = -1

    while time.time() < deadline:
        if capture_window(img) is None:
            time.sleep(0.5)
            continue

        size = Path(img).stat().st_size
        if size == last_size:
            time.sleep(0.5)
            continue
        last_size = size

        code, stdout, _ = ocr(img, "--find", text)
        found = code == 0 and stdout.strip() and len(json.loads(stdout)) > 0

        if found == present:
            _try_remove(img)
            return True
        time.sleep(0.5)

    _try_remove(img)
    return False


def cmd_issues(args: list[str]) -> None:
    days = 1
    if args:
        try:
            days = int(args[0])
        except ValueError:
            pass

    sessions_dir = Path("/tmp/arena/sessions")
    if not sessions_dir.exists():
        print("No session logs found")
        return

    issues: dict[str, dict] = {}
    total_commands = 0
    total_errors = 0
    total_sessions = 0

    cutoff = time.time() - days * 86400
    for date_dir in sorted(sessions_dir.iterdir(), reverse=True):
        if not date_dir.is_dir():
            continue
        for log_file in sorted(date_dir.iterdir(), reverse=True):
            if not log_file.name.endswith(".jsonl"):
                continue
            if log_file.stat().st_mtime < cutoff:
                continue
            total_sessions += 1
            for line in log_file.read_text().splitlines():
                if not line.strip():
                    continue
                total_commands += 1
                if '"error":true' in line:
                    total_errors += 1
                    try:
                        entry = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    cmd = entry.get("cmd", "?")
                    cmd_args = json.dumps(entry.get("args", []))
                    stderr = entry.get("stderr", "")
                    key = f"{cmd}|{cmd_args}"
                    if key in issues:
                        issues[key]["count"] += 1
                    else:
                        issues[key] = {
                            "cmd": cmd,
                            "args": cmd_args,
                            "stderr": stderr,
                            "count": 1,
                        }

    print(f"=== Arena Session Summary (last {days}d) ===")
    print(
        f"Sessions: {total_sessions} | Commands: {total_commands} | Errors: {total_errors}"
    )
    if not issues:
        print("No issues found.")
        return
    print()
    print("Issues (by frequency):")
    for issue in sorted(issues.values(), key=lambda i: i["count"], reverse=True):
        print(f"  {issue['count']}x  {issue['cmd']} {issue['args']}")
        if issue["stderr"]:
            print(f"       → {issue['stderr'][:120]}")


# ---------------------------------------------------------------------------
# Session logging
# ---------------------------------------------------------------------------

_session_start = time.time()


def _session_log_path() -> Path:
    date = datetime.now().strftime("%Y-%m-%d")
    ts = datetime.now().strftime("%H%M%S")
    p = Path(f"/tmp/arena/sessions/{date}/{ts}.jsonl")
    p.parent.mkdir(parents=True, exist_ok=True)
    return p


# Lazily initialized so the timestamp reflects first use
_log_file: Path | None = None


def _get_log_file() -> Path:
    global _log_file
    if _log_file is None:
        _log_file = _session_log_path()
    return _log_file


def session_log(
    command: str,
    args: list[str],
    exit_code: int,
    stdout: str,
    stderr: str,
    duration_ms: int,
) -> None:
    elapsed_ms = int((time.time() - _session_start) * 1000)
    entry: dict = {
        "t": elapsed_ms,
        "ts": datetime.now(timezone.utc).isoformat(),
        "cmd": command,
        "args": args,
        "exit": exit_code,
        "ms": duration_ms,
    }
    if exit_code != 0:
        entry["error"] = True
        if stderr:
            entry["stderr"] = stderr[:500]
    if stdout and len(stdout) < 200:
        entry["out"] = stdout
    try:
        with open(_get_log_file(), "a") as f:
            f.write(json.dumps(entry) + "\n")
    except OSError:
        pass  # logging should never break the command


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def die(msg: str) -> NoReturn:
    print(msg, file=sys.stderr)
    raise SystemExit(1)


def _try_remove(path: str) -> None:
    try:
        os.remove(path)
    except OSError:
        pass


def _parse_coord(s: str) -> tuple[int, int] | None:
    m = re.fullmatch(r"(\d+),(\d+)", s)
    if not m:
        return None
    return int(m.group(1)), int(m.group(2))


# ---------------------------------------------------------------------------
# Health check
# ---------------------------------------------------------------------------


def cmd_health(args: list[str]) -> None:
    """Pre-flight check: server, client, OCR, coord space."""
    checks: list[tuple[str, bool, str]] = []

    # 1. Server up?
    state = fetch_api("/api/state")
    server_ok = state is not None
    checks.append(
        (
            "Server (:8090)",
            server_ok,
            "ok" if server_ok else "not responding — run: just serve",
        )
    )

    # 2. Port listening?
    code, out, _ = run("lsof", "-i", ":30010", "-sTCP:LISTEN")
    listen_ok = code == 0 and "LISTEN" in out
    checks.append(
        ("Port :30010 LISTEN", listen_ok, "ok" if listen_ok else "not listening")
    )

    # 3. Client connected?
    code, out, _ = run("lsof", "-i", ":30010", "-sTCP:ESTABLISHED")
    conn_ok = code == 0 and "ESTABLISHED" in out
    checks.append(
        (
            "Client connected",
            conn_ok,
            "ok" if conn_ok else "MTGA not connected — run: arena launch",
        )
    )

    # 4. MTGA window found?
    bounds = mtga_window_bounds()
    win_ok = bounds is not None
    win_msg = (
        f"ok ({bounds[2]}x{bounds[3]} at {bounds[0]},{bounds[1]})"
        if bounds
        else "MTGA window not found"
    )
    checks.append(("MTGA window", win_ok, win_msg))

    # 5. OCR working?
    ocr_ok = False
    ocr_count = 0
    if win_ok:
        img = "/tmp/arena/_health_capture.png"
        cap_bounds = capture_window(img)
        if cap_bounds:
            code, stdout, _ = ocr(img)
            if code == 0 and stdout.strip():
                items = json.loads(stdout)
                ocr_count = len(items)
                ocr_ok = ocr_count > 0
        _try_remove(img)
    checks.append(("OCR", ocr_ok, f"ok ({ocr_count} items)" if ocr_ok else "failed"))

    # 6. Display scale
    scale_msg = "unknown"
    if win_ok:
        img = "/tmp/arena/_health_hires.png"
        capture_window(img, hires=True)
        _, sips_out, _ = run("sips", "-g", "pixelWidth", str(img))
        m = re.search(r"pixelWidth:\s*(\d+)", sips_out)
        if m and bounds:
            img_w = int(m.group(1))
            ratio = img_w / bounds[2] if bounds[2] > 0 else 0
            scale_msg = f"{ratio:.0f}x (image {img_w}px, window {bounds[2]} logical)"
        _try_remove(img)
    checks.append(("Display scale", True, scale_msg))

    # Print results
    all_ok = all(ok for _, ok, _ in checks)
    for name, ok, msg in checks:
        status = "PASS" if ok else "FAIL"
        print(f"  [{status}] {name}: {msg}")

    if all_ok:
        print("\nAll checks passed. Ready for automation.")
    else:
        print("\nSome checks failed. Fix before automating.")
        raise SystemExit(1)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


COMMANDS = {
    "launch": cmd_launch,
    "capture": cmd_capture,
    "ocr": cmd_ocr,
    "click": cmd_click,
    "move": cmd_move,
    "drag": cmd_drag,
    "play": cmd_play,
    "state": cmd_state,
    "errors": cmd_errors,
    "scene": cmd_scene,
    "where": cmd_where,
    "navigate": cmd_navigate,
    "wait": cmd_wait,
    "board": cmd_board,
    "detect": cmd_detect,
    "issues": cmd_issues,
    "health": cmd_health,
}


def main() -> None:
    if len(sys.argv) < 2:
        print("Usage: arena <command> [args...]", file=sys.stderr)
        print("Commands: " + ", ".join(COMMANDS), file=sys.stderr)
        sys.exit(1)

    command = sys.argv[1]
    cmd_args = sys.argv[2:]

    if command == "issues":
        cmd_issues(cmd_args)
        return

    handler = COMMANDS.get(command)
    if handler is None:
        print(f"Unknown command: {command}", file=sys.stderr)
        sys.exit(1)

    # Capture stdout/stderr for session logging
    import io

    captured_out = io.StringIO()
    captured_err = io.StringIO()

    class Tee:
        def __init__(self, original: object, capture: io.StringIO):
            self._orig = original
            self._cap = capture

        def write(self, s: str) -> int:
            self._orig.write(s)  # type: ignore
            self._cap.write(s)
            return len(s)

        def flush(self) -> None:
            self._orig.flush()  # type: ignore
            self._cap.flush()

    orig_out, orig_err = sys.stdout, sys.stderr
    sys.stdout = Tee(orig_out, captured_out)  # type: ignore
    sys.stderr = Tee(orig_err, captured_err)  # type: ignore

    start_ms = time.time()
    exit_code = 0
    try:
        handler(cmd_args)
    except SystemExit as e:
        exit_code = e.code if isinstance(e.code, int) else 1
    except Exception:
        exit_code = 1
    finally:
        sys.stdout = orig_out
        sys.stderr = orig_err
        duration_ms = int((time.time() - start_ms) * 1000)
        session_log(
            command,
            cmd_args,
            exit_code,
            captured_out.getvalue().strip(),
            captured_err.getvalue().strip(),
            duration_ms,
        )

    if exit_code != 0:
        sys.exit(exit_code)


if __name__ == "__main__":
    main()
