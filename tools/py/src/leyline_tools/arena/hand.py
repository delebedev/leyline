from __future__ import annotations

import json
import math
import re
from pathlib import Path

from PIL import Image

from .common import die, try_remove
from .debug_api import fetch_api
from .macos import capture_window, ocr
from .paths import REFERENCE_WIDTH
from .scry_bridge import _scry_state
from .shell import run


HAND_MIN_CY = 490

_HAND_Y_RATIO = 0.78
_HAND_CY_CENTER = 530
_HAND_ARC_DROP = 35
_HAND_X_MIN = 120
_HAND_X_MAX = 800
_HAND_X_CENTER = 500
_HAND_CX_BASE_SHIFT = 20
_HAND_CX_NUDGE = 20
_OCR_TARGET_WIDTH = 3840


def cmd_ocr_hand() -> None:
    known_names: list[str] = []
    state = _scry_state()
    if state:
        known_names = [
            c.get("name", "") for c in state.get("hand", []) if c.get("name")
        ]

    if not known_names:
        raw = fetch_api("/api/id-map?active=true")
        if raw:
            entries = json.loads(raw)
            if isinstance(entries, dict):
                entries = entries.get("data", [])
            known_names = [
                e.get("cardName", "")
                for e in entries
                if e.get("forgeZone") == "Hand"
                and e.get("ownerSeatId") == 1
                and e.get("cardName")
            ]

    if not known_names:
        die("No hand cards found (scry and debug API both empty)")

    result = _ocr_hand_strip()
    if result is None:
        die("Hand OCR failed (capture or OCR returned no results)")
    items, ocr_to_960 = result

    print(f"Known hand ({len(known_names)}): {known_names}")
    print(f"OCR detections ({len(items)}):")
    for item in items:
        cx_960 = int(item["cx"] * ocr_to_960)
        print(
            f"  {item['text']:30s} cx={cx_960:4d}  conf={item.get('confidence', 0):.2f}"
        )

    matched: dict[str, tuple[float, int]] = {}
    for item in items:
        cx_960 = int(item["cx"] * ocr_to_960)
        if cx_960 < _HAND_X_MIN or cx_960 > _HAND_X_MAX:
            continue
        for name in known_names:
            score = _fuzzy_card_match(item["text"], name)
            if score >= 0.4:
                prev = matched.get(name)
                if prev is None or score > prev[0]:
                    matched[name] = (score, cx_960)

    print(f"\nMatched ({len(matched)}/{len(known_names)}):")
    for name in known_names:
        if name in matched:
            score, cx = matched[name]
            print(f"  {name:30s} cx={cx:4d}  score={score:.2f}")
        else:
            print(f"  {name:30s} NOT FOUND")


def _hand_adjust(ocr_cx: int) -> tuple[int, int]:
    offset_ratio = (_HAND_X_CENTER - ocr_cx) / (_HAND_X_CENTER - _HAND_X_MIN)
    cx = ocr_cx + _HAND_CX_BASE_SHIFT + int(_HAND_CX_NUDGE * offset_ratio)
    dx = (cx - _HAND_X_CENTER) / (_HAND_X_MAX - _HAND_X_MIN)
    arc_offset = _HAND_ARC_DROP * (1 - math.cos(dx * math.pi))
    cy = _HAND_CY_CENTER + int(arc_offset)
    return (cx, cy)


def _ocr_upscale_factor(capture_px_width: int) -> int:
    if capture_px_width <= 0:
        return 2
    return max(2, round(_OCR_TARGET_WIDTH / capture_px_width))


def _ocr_hand_strip() -> tuple[list[dict], float] | None:
    tmp = "/tmp/arena/_hand_ocr"
    Path(tmp).mkdir(parents=True, exist_ok=True)

    img = f"{tmp}/capture.png"
    bounds = capture_window(img, hires=True)
    if bounds is None:
        return None

    _, sips_out, _ = run("sips", "-g", "pixelWidth", "-g", "pixelHeight", img)
    w_m = re.search(r"pixelWidth:\s*(\d+)", sips_out)
    h_m = re.search(r"pixelHeight:\s*(\d+)", sips_out)
    if not w_m or not h_m:
        try_remove(img)
        return None

    cap_w = int(w_m.group(1))
    cap_h = int(h_m.group(1))

    scale = _ocr_upscale_factor(cap_w)
    crop_top = int(cap_h * _HAND_Y_RATIO)
    pil_img = Image.open(img)
    strip_pil = pil_img.crop((0, crop_top, cap_w, cap_h))
    strip_up = f"{tmp}/strip_up.png"
    strip_pil = strip_pil.resize(
        (cap_w * scale, strip_pil.height * scale), Image.LANCZOS
    )
    strip_pil.save(strip_up)
    pil_img.close()
    strip_pil.close()
    try_remove(img)

    code, stdout, _ = ocr(strip_up, "--min-confidence", "0.15")
    try_remove(strip_up)
    if code != 0 or not stdout.strip():
        return None

    items = json.loads(stdout)
    ocr_to_960 = REFERENCE_WIDTH / (cap_w * scale)
    return (items, ocr_to_960)


def _fuzzy_card_match(ocr_text: str, card_name: str) -> float:
    ot = ocr_text.lower().strip()
    for ch in "''\u2019()[].,":
        ot = ot.replace(ch, "")
    ot = ot.strip()
    cn = card_name.lower().strip().replace("'", "").replace("\u2019", "")

    if not ot or not cn:
        return 0.0
    if ot == cn:
        return 1.0
    if cn in ot:
        return 0.9
    if ot in cn and len(ot) >= 3:
        return 0.7 * len(ot) / len(cn)

    best = 0.0
    cn_words = cn.split()
    ot_words = ot.split()
    if len(cn_words) > 1 and ot_words:
        hits = 0
        for w in cn_words:
            if len(w) <= 2:
                continue
            for ow in ot_words:
                if w in ow or ow in w or _levenshtein(w, ow) <= 1:
                    hits += 1
                    break
        if hits > 0:
            best = max(best, 0.6 * hits / len(cn_words))

    if len(cn) <= 8 and len(ot) <= 12:
        dist = _levenshtein(ot, cn)
        if dist <= 1:
            best = max(best, 0.8)
        elif dist <= 2 and len(cn) >= 5:
            best = max(best, 0.5)

    if abs(len(ot) - len(cn)) <= 3 and len(cn) >= 6:
        dist = _levenshtein(ot, cn)
        if dist <= 2:
            best = max(best, 0.7)
        elif dist <= 3 and len(cn) >= 10:
            best = max(best, 0.5)

    return best


def _levenshtein(a: str, b: str) -> int:
    if len(a) < len(b):
        return _levenshtein(b, a)
    if not b:
        return len(a)
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a):
        curr = [i + 1]
        for j, cb in enumerate(b):
            curr.append(
                min(prev[j + 1] + 1, curr[j] + 1, prev[j] + (0 if ca == cb else 1))
            )
        prev = curr
    return prev[-1]


def _find_hand_card_ocr(
    card_name: str, known_names: list[str]
) -> tuple[int, int] | None:
    result = _ocr_hand_strip()
    if result is None:
        return None

    items, ocr_to_960 = result
    if not items:
        return None

    card_matches: dict[str, list[tuple[float, int]]] = {}
    for item in items:
        cx_960 = int(item["cx"] * ocr_to_960)
        if cx_960 < _HAND_X_MIN or cx_960 > _HAND_X_MAX:
            continue
        for name in known_names:
            score = _fuzzy_card_match(item["text"], name)
            if score >= 0.4:
                card_matches.setdefault(name, []).append((score, cx_960))

    resolved: dict[str, int] = {}
    used_cx: set[int] = set()
    for name in sorted(
        card_matches, key=lambda n: max(s for s, _ in card_matches[n]), reverse=True
    ):
        candidates = sorted(card_matches[name], key=lambda t: -t[0])
        for score, cx in candidates:
            if not any(abs(cx - ux) < 30 for ux in used_cx):
                resolved[name] = cx
                used_cx.add(cx)
                break
        if name not in resolved and candidates:
            resolved[name] = candidates[0][1]

    target_lower = card_name.lower()
    for name, ocr_cx in resolved.items():
        if name.lower() == target_lower or target_lower in name.lower():
            return _hand_adjust(ocr_cx)

    if len(resolved) == len(known_names) - 1:
        count = len(known_names)
        spacing = min(80, 400 // max(count, 1))
        all_positions = [
            480 - (count - 1) * spacing // 2 + i * spacing for i in range(count)
        ]
        for pos in all_positions:
            if not any(abs(pos - ocr_cx) < 30 for ocr_cx in resolved.values()):
                return _hand_adjust(pos)

    return None


def _find_hand_card(name: str) -> tuple[tuple[int, int], int] | None:
    hand_cards: list[dict] = []
    state = _scry_state()
    if state:
        for card in state.get("hand", []):
            hand_cards.append(
                {
                    "instanceId": card.get("id"),
                    "cardName": card.get("name", ""),
                }
            )

    if not hand_cards:
        return None

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
    known_names = [c.get("cardName") or "" for c in hand_cards if c.get("cardName")]
    ocr_pos = _find_hand_card_ocr(name, known_names)
    if ocr_pos is not None:
        return (ocr_pos, instance_id)

    # Fallback: detection model (hand-card bounding boxes)
    from .board import _board_detect

    idx = next(
        (i for i, c in enumerate(hand_cards) if c.get("instanceId") == instance_id),
        0,
    )
    dets = _board_detect()
    hand_dets = sorted(
        [d for d in dets if d["label"] == "hand-card" and d["cy"] > HAND_MIN_CY],
        key=lambda d: d["cx"],
    )

    if hand_dets:
        if len(hand_dets) == len(hand_cards) and idx < len(hand_dets):
            det = hand_dets[idx]
        elif idx < len(hand_dets):
            det = hand_dets[idx]
        else:
            det = hand_dets[-1]
        return ((det["cx"], det["cy"]), instance_id)

    # Last resort: estimate position from card count
    count = len(hand_cards)
    spacing = min(80, 400 // max(count, 1))
    center_x = 480 - (count - 1) * spacing // 2 + idx * spacing
    return (_hand_adjust(center_x), instance_id)
