from __future__ import annotations

import json
import sys
import time
from pathlib import Path

from .common import die, parse_coord, try_remove
from .macos import click_screen, drag_screen, mtga_window_bounds, ocr, capture_window
from .paths import REFERENCE_WIDTH
from .scry_bridge import _normalize_zone, _scry_cache_clear, _scry_state


def cmd_move(args: list[str]) -> None:
    if not args:
        die("Usage: arena move <x>,<y>")
    parsed = parse_coord(args[0])
    if parsed is None:
        die("Usage: arena move <x>,<y>")
    ref_x, ref_y = parsed
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

    parsed = parse_coord(target)
    if parsed is not None:
        ref_x, ref_y = parsed
        bounds = mtga_window_bounds()
        if bounds is None:
            die("MTGA window not found")
        win_w = bounds[2]
        scale = win_w / REFERENCE_WIDTH if REFERENCE_WIDTH > 0 else 1.0
        wx = int(ref_x * scale)
        wy = int(ref_y * scale)
        sx = bounds[0] + wx
        sy = bounds[1] + wy
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
                try_remove(img)
                die(f'"{target}" not found on screen')
            continue

        matches = json.loads(stdout)
        if not matches:
            if attempt == max_retries:
                try_remove(img)
                die(f'"{target}" not found on screen')
            continue

        try_remove(img)
        first = matches[0]
        cx = int(float(first["cx"]))
        cy = int(float(first["cy"]))
        win_w = bounds[2]
        scale = win_w / REFERENCE_WIDTH if REFERENCE_WIDTH > 0 else 1.0
        sx = bounds[0] + int(cx * scale)
        sy = bounds[1] + int(cy * scale)

        code, _, stderr = click_screen(sx, sy, action)
        if code != 0:
            die(f"click failed: {stderr}")
        print(f'clicked "{target}" at ({cx}, {cy}) → screen ({sx}, {sy})')
        return


def _click_960(ref_x: int, ref_y: int) -> None:
    bounds = mtga_window_bounds()
    if bounds is None:
        die("MTGA window not found")
    scale = bounds[2] / REFERENCE_WIDTH if REFERENCE_WIDTH > 0 else 1.0
    sx = bounds[0] + int(ref_x * scale)
    sy = bounds[1] + int(ref_y * scale)
    click_screen(sx, sy)


def _do_drag(fr: tuple[int, int], to: tuple[int, int]) -> None:
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


def _scry_hand_count() -> int | None:
    state = _scry_state()
    if state is None:
        return None
    return len(state.get("hand", []))


def _verified_drag(
    fr: tuple[int, int],
    to: tuple[int, int],
    instance_id: int | None = None,
    max_attempts: int = 3,
) -> bool:
    import random

    for attempt in range(max_attempts):
        pre_count = _scry_hand_count()
        jitter = 0 if attempt == 0 else random.randint(-5, 5)
        src = (fr[0] + jitter, fr[1] + jitter)

        _do_drag(src, to)

        if pre_count is None:
            return True

        _scry_cache_clear()
        for _ in range(15):
            time.sleep(0.2)
            post_count = _scry_hand_count()
            if post_count is not None and post_count < pre_count:
                return True

        if attempt < max_attempts - 1:
            print(f"drag attempt {attempt + 1} failed, retrying...", file=sys.stderr)

    return False


def _get_card_zone(instance_id: int) -> str | None:
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

    fr = parse_coord(args[0])
    to = parse_coord(args[1])
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
