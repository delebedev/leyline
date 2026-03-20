from __future__ import annotations

import json
import shlex
import sys
import time
from pathlib import Path

from .common import die, poll_state, try_remove
from .macos import capture_window, ocr
from .scry_bridge import get_current_scene, poll_scene
from .screens import POPUPS, SCREENS, find_path

MAX_REROUTES = 3


def cmd_scene(args: list[str]) -> None:
    scene = get_current_scene()
    print(json.dumps({"current": scene}))


def detect_screen() -> str | None:
    scene = get_current_scene()
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
    try_remove(img)
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

        if s.get("scene") and s.get("scene") == scene:
            score += 1

        if score > 0:
            candidates.append((name, score))

    if not candidates:
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
    screen = detect_screen()
    print(json.dumps({"screen": screen}))


def split_step(step: str) -> list[str]:
    return shlex.split(step)


def exec_step(step: str, commands: dict[str, object]) -> None:
    if step.startswith("sleep "):
        time.sleep(float(step.split()[1]))
        return
    step_args = split_step(step)
    cmd_name = step_args[0]
    cmd_rest = step_args[1:]
    handler = commands.get(cmd_name)
    if handler is None:
        die(f"Unknown step command: {cmd_name}")
    handler(cmd_rest)  # type: ignore[misc]


def poll_ocr(text: str, present: bool, timeout_ms: int) -> bool:
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
            try_remove(img)
            return True
        time.sleep(0.5)

    try_remove(img)
    return False


def wait_condition(wait: str, timeout_sec: int) -> bool:
    if wait.startswith("scene="):
        return poll_scene(wait.removeprefix("scene="), timeout_sec * 1000)
    if wait.startswith("text="):
        return poll_ocr(
            wait.removeprefix("text="),
            present=True,
            timeout_ms=timeout_sec * 1000,
        )
    if "=" in wait:
        return poll_state(wait, timeout_sec * 1000)
    return False


def try_dismiss_popups(commands: dict[str, object]) -> str | None:
    img = "/tmp/arena/_popup_check.png"
    Path(img).parent.mkdir(parents=True, exist_ok=True)
    bounds = capture_window(img)
    if bounds is None:
        return None

    code, stdout, _ = ocr(img)
    try_remove(img)
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

        name = popup["name"]
        print(f"  popup detected: {name}, dismissing...")
        for step in popup["dismiss"]:
            exec_step(step, commands)
        return name

    return None


def cmd_navigate(args: list[str], commands: dict[str, object]) -> None:
    if not args:
        die(
            "Usage: arena navigate <screen>\nScreens: Home, Play, FindMatch, Events, EventLanding, ..."
        )

    target = args[0]
    dry_run = "--dry-run" in args

    try_dismiss_popups(commands)

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

        for step in steps:
            exec_step(step, commands)

        ok = True
        if wait:
            ok = wait_condition(wait, wait_timeout)

        if ok:
            print(f"  {t['from']} -> {t['to']} ok")
            path = path[1:]
            try_dismiss_popups(commands)
            continue

        reroutes += 1
        if reroutes > MAX_REROUTES:
            die(
                f"Navigation failed: exceeded {MAX_REROUTES} reroutes. "
                f"Last attempt: {t['from']} -> {t['to']}, wait '{wait}' timed out"
            )

        popup = try_dismiss_popups(commands)
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

        new_path = find_path(actual, target)
        if new_path is None:
            die(f"Navigation stuck: no path from {actual} to {target}")

        print(
            f"  rerouting: expected {t['to']}, got {actual}. "
            f"New path: {actual} -> {' -> '.join(tt['to'] for tt in new_path)}"
        )
        path = new_path

    print(f"Arrived at {target}")


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
        matched = poll_ocr(
            condition.removeprefix("text="),
            present=True,
            timeout_ms=timeout_ms,
        )
    elif condition.startswith("no-text="):
        matched = poll_ocr(
            condition.removeprefix("no-text="),
            present=False,
            timeout_ms=timeout_ms,
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
