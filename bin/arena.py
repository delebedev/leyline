#!/usr/bin/env python3
"""
Arena CLI — MTGA window automation.

Commands: launch, capture, ocr, click, drag, state, errors, wait, issues
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


def capture_window(out_path: str) -> tuple[int, int, int, int] | None:
    """Capture MTGA window rect. Activates first, then screencapture -R at window bounds.

    Resizes to logical size so OCR coords = window-relative click coords.
    """
    _activate_mtga()
    bounds = mtga_window_bounds()
    if bounds is None:
        return None

    x, y, w, h = bounds
    code, _, _ = run("screencapture", "-R", f"{x},{y},{w},{h}", "-x", out_path)
    if code != 0:
        return None

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
    it = iter(args)
    for a in it:
        if a == "--find":
            find_text = next(it)
        elif a in ("--json", "--no-json"):
            pass
        else:
            die(f"Unknown flag: {a}")

    img = "/tmp/arena/_ocr_capture.png"
    Path(img).parent.mkdir(parents=True, exist_ok=True)

    bounds = capture_window(img)
    if bounds is None:
        die("MTGA window not found")

    ocr_args: list[str] = []
    if find_text is not None:
        ocr_args += ["--find", find_text]

    code, stdout, stderr = ocr(img, *ocr_args)
    try:
        os.remove(img)
    except OSError:
        pass

    if code != 0:
        if find_text:
            die(f'"{find_text}" not found')
        else:
            die(f"OCR failed: {stderr}")

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

    # Coordinate click: x,y
    m = re.fullmatch(r"(\d+),(\d+)", target)
    if m:
        wx, wy = int(m.group(1)), int(m.group(2))
        bounds = mtga_window_bounds()
        if bounds is None:
            die("MTGA window not found")
        sx, sy = bounds[0] + wx, bounds[1] + wy
        code, _, stderr = click_screen(sx, sy, action)
        if code != 0:
            die(f"click failed: {stderr}")
        print(f"clicked ({wx}, {wy}) → screen ({sx}, {sy})")
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


def cmd_drag(args: list[str]) -> None:
    if len(args) < 2:
        die("Usage: arena drag <from_x>,<from_y> <to_x>,<to_y>")

    fr = _parse_coord(args[0])
    to = _parse_coord(args[1])
    if fr is None:
        die(f"Invalid from coord: {args[0]} (expected x,y)")
    if to is None:
        die(f"Invalid to coord: {args[1]} (expected x,y)")

    bounds = mtga_window_bounds()
    if bounds is None:
        die("MTGA window not found")

    sx1, sy1 = bounds[0] + fr[0], bounds[1] + fr[1]
    sx2, sy2 = bounds[0] + to[0], bounds[1] + to[1]

    code, _, stderr = drag_screen(sx1, sy1, sx2, sy2)
    if code != 0:
        die(f"drag failed: {stderr}")
    print(f"dragged ({fr[0]},{fr[1]}) → ({to[0]},{to[1]})")


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
    body = fetch_api("/api/client-errors")
    print(body if body else "[]")


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
# Main
# ---------------------------------------------------------------------------

COMMANDS = {
    "launch": cmd_launch,
    "capture": cmd_capture,
    "ocr": cmd_ocr,
    "click": cmd_click,
    "drag": cmd_drag,
    "state": cmd_state,
    "errors": cmd_errors,
    "wait": cmd_wait,
    "board": cmd_board,
    "detect": cmd_detect,
    "issues": cmd_issues,
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
