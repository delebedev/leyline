#!/usr/bin/env python3
"""Annotate an MTGA screenshot with game zones and numbered hand cards.

Usage:
    arena-annotate [screenshot.png] [--out output.png] [--open]

If no screenshot given, runs `arena capture` to take one.
Output defaults to /tmp/arena-annotated.png.
"""
import argparse
import json
import subprocess
import sys
from pathlib import Path

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    print("pip install Pillow", file=sys.stderr)
    sys.exit(1)

# Fixed game zones in 960x568 logical coords (arena capture output)
ZONES = [
    ("Opp Life",        (440, 90, 520, 135),  "cyan"),
    ("Opp Battlefield", (0, 135, 960, 255),   "red"),
    ("River / Stack",   (0, 255, 960, 295),   "white"),
    ("Our Battlefield", (0, 295, 960, 445),   "orange"),
    ("Our Life",        (440, 455, 540, 505), "magenta"),
    ("Our Hand",        (0, 500, 800, 568),   "lime"),
    ("Action Button",   (830, 475, 950, 535), "yellow"),
]

HAND_Y = 520  # approximate y-center of hand cards
HAND_X_MIN = 60
HAND_X_MAX = 720


def load_font(size):
    for path in ["/System/Library/Fonts/Helvetica.ttc", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"]:
        try:
            return ImageFont.truetype(path, size)
        except (IOError, OSError):
            continue
    return ImageFont.load_default()


def run_ocr():
    """Run arena ocr, return parsed JSON list."""
    result = subprocess.run(
        [str(Path(__file__).parent / "arena"), "ocr"],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        return []
    try:
        return json.loads(result.stdout.strip())
    except json.JSONDecodeError:
        return []


def capture(out_path):
    """Run arena capture, return path."""
    subprocess.run(
        [str(Path(__file__).parent / "arena"), "capture", "--out", out_path, "--resolution", "1920"],
        check=True,
    )
    return out_path


def estimate_hand_cards(ocr_items):
    """Estimate hand card center positions from OCR items in hand zone."""
    hand_items = [
        item for item in ocr_items
        if item["cy"] > 490 and HAND_X_MIN < item["cx"] < HAND_X_MAX
    ]
    if hand_items:
        # Use OCR x-positions as card centers, cluster nearby ones
        xs = sorted(item["cx"] for item in hand_items)
        centers = [xs[0]]
        for x in xs[1:]:
            if x - centers[-1] > 25:
                centers.append(x)
        return [(x, HAND_Y) for x in centers]

    # Fallback: no OCR in hand, estimate from typical card spread
    # Cards fan out roughly 350-620, spaced ~40px
    return [(x, HAND_Y) for x in range(370, 640, 40)]


def annotate(img_path, out_path, ocr_items, number_cards=False):
    img = Image.open(img_path)
    draw = ImageDraw.Draw(img)
    label_font = load_font(13)
    num_font = load_font(12)

    # Draw zones
    for label, (x1, y1, x2, y2), color in ZONES:
        draw.rectangle([x1, y1, x2, y2], outline=color, width=2)
        draw.text((x1 + 4, y1 + 2), label, fill=color, font=label_font)

    # Mark OCR elements with dots
    for item in ocr_items:
        cx, cy = item["cx"], item["cy"]
        if cy < 490:  # skip hand area (numbered separately)
            draw.ellipse([cx - 2, cy - 2, cx + 2, cy + 2], fill="white")

    # Number hand cards (only with --cards flag)
    if number_cards:
        hand = estimate_hand_cards(ocr_items)
        for i, (cx, cy) in enumerate(hand, 1):
            r = 10
            draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill="red", outline="white", width=1)
            draw.text((cx - 4, cy - 6), str(i), fill="white", font=num_font)
        draw.text((10, 505), f"Hand: {len(hand)} cards", fill="lime", font=label_font)

    img.save(out_path)
    print(out_path)


def main():
    parser = argparse.ArgumentParser(description="Annotate MTGA screenshot with game zones")
    parser.add_argument("screenshot", nargs="?", help="Path to screenshot (default: capture live)")
    parser.add_argument("--out", default="/tmp/arena-annotated.png", help="Output path")
    parser.add_argument("--open", action="store_true", help="Open result in Preview")
    parser.add_argument("--cards", action="store_true", help="Number cards in hand")
    args = parser.parse_args()

    if args.screenshot:
        img_path = args.screenshot
    else:
        img_path = "/tmp/arena-capture-for-annotate.png"
        capture(img_path)

    ocr_items = run_ocr()
    annotate(img_path, args.out, ocr_items, number_cards=args.cards)

    if args.open:
        subprocess.run(["open", args.out])


if __name__ == "__main__":
    main()
