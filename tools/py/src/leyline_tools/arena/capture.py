from __future__ import annotations

import json
import re
from pathlib import Path

from .common import die, try_remove
from .hand import cmd_ocr_hand
from .macos import capture_window, ocr
from .paths import REFERENCE_WIDTH, SWIFT_DIR
from .shell import run, sips


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
        try_remove(full)
    else:
        Path(full).rename(out)

    size_kb = Path(out).stat().st_size // 1024
    print(f"{out} ({size_kb}KB, {resolution}px)")


def cmd_ocr(args: list[str]) -> None:
    find_text = None
    fmt = False
    hires = True
    hand_mode = False
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
        elif a == "--hand":
            hand_mode = True
        elif a in ("--json", "--no-json"):
            pass
        else:
            die(f"Unknown flag: {a}")

    if hand_mode:
        cmd_ocr_hand()
        return

    img = "/tmp/arena/_ocr_capture.png"
    Path(img).parent.mkdir(parents=True, exist_ok=True)

    bounds = capture_window(img, hires=hires)
    if bounds is None:
        die("MTGA window not found")

    ocr_args: list[str] = []
    if find_text is not None:
        ocr_args += ["--find", find_text]

    code, stdout, stderr = ocr(img, *ocr_args)

    capture_pixel_width = 0
    if hires:
        _, sips_out, _ = run("sips", "-g", "pixelWidth", img)
        m_sips = re.search(r"pixelWidth:\s*(\d+)", sips_out)
        if m_sips:
            capture_pixel_width = int(m_sips.group(1))
    try_remove(img)

    if code != 0:
        if find_text:
            die(f'"{find_text}" not found')
        die(f"OCR failed: {stderr}")

    if hires and capture_pixel_width > 0:
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
        f"{SWIFT_DIR}/detect.swift",
        img,
        "--threshold",
        str(threshold),
        timeout=15,
    )
    try_remove(img)

    if code != 0:
        die(f"Detection failed: {stderr}")

    print(stdout)
