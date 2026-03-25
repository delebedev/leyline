"""Extract player card collection from Arena sources.

Sources (in priority order):
1. FD recording fd-frames.jsonl — CmdType 551 (Card_GetAllCards) response
2. Player.log — same payload logged by Unity
3. Fallback: all cards × 4 (local server mode)
"""

from __future__ import annotations

import json
import re
from pathlib import Path

_PLAYER_LOG_PATH = (
    Path.home() / "Library/Logs/Wizards Of The Coast/MTGA/Player.log"
)
_PLAYER_LOG_PREV = _PLAYER_LOG_PATH.with_name("Player-prev.log")


def from_fd_frames(jsonl_path: Path) -> dict[int, int] | None:
    """Extract collection from a proxy recording's fd-frames.jsonl.

    The collection is a S2C response with {"cacheVersion":N,"cards":{...}}.
    The response frame has cmdTypeName=None (server responses don't echo it),
    so we match on the payload shape instead.
    """
    if not jsonl_path.exists():
        return None

    with open(jsonl_path) as f:
        for line in f:
            frame = json.loads(line)
            if frame.get("dir") != "S2C":
                continue
            payload = frame.get("jsonPayload", "")
            if isinstance(payload, str) and len(payload) > 1000:
                try:
                    payload = json.loads(payload)
                except (json.JSONDecodeError, ValueError):
                    continue
            if isinstance(payload, dict) and "cards" in payload and "cacheVersion" in payload:
                cards = payload["cards"]
                if isinstance(cards, dict) and len(cards) > 50:
                    return {int(k): v for k, v in cards.items()}
    return None


def from_player_log(log_path: Path | None = None) -> dict[int, int] | None:
    """Extract collection from Player.log.

    The client logs the Card_GetAllCards response as a JSON blob.
    Format: {"cacheVersion":N,"cards":{"<grpId>":<count>,...}}

    Also handles the raw inventory format where cards appear as
    a large JSON object with numeric string keys → int values.
    """
    paths = []
    if log_path:
        paths.append(log_path)
    else:
        paths.extend([_PLAYER_LOG_PATH, _PLAYER_LOG_PREV])

    for p in paths:
        if not p.exists():
            continue
        result = _scan_log(p)
        if result:
            return result
    return None


def _scan_log(log_path: Path) -> dict[int, int] | None:
    """Scan a single log file for collection data."""
    with open(log_path, errors="replace") as f:
        for line in f:
            stripped = line.strip()

            # Match: Card_GetAllCards response logged by Unity
            if "Card_GetAllCards" in stripped or "CardGetAllCards" in stripped:
                cards = _try_extract_cards(stripped)
                if cards:
                    return cards

            # Match: raw JSON with "cards" key containing grpId map
            if '"cards":{' in stripped and '"cacheVersion"' in stripped:
                cards = _try_extract_cards(stripped)
                if cards:
                    return cards

            # Match: large grpId→count object (fallback heuristic)
            # Arena sometimes logs collection as standalone {"12345":4,"12346":2,...}
            if stripped.startswith("{") and len(stripped) > 10000:
                cards = _try_parse_collection_blob(stripped)
                if cards:
                    return cards

    return None


def _try_extract_cards(line: str) -> dict[int, int] | None:
    """Try to extract cards dict from a log line."""
    # Find JSON objects in the line
    for match in re.finditer(r'\{[^{}]*"cards"\s*:\s*\{', line):
        start = match.start()
        obj = _extract_json_object(line, start)
        if obj and "cards" in obj:
            cards = obj["cards"]
            if isinstance(cards, dict) and len(cards) > 100:
                return {int(k): v for k, v in cards.items()}
    return None


def _try_parse_collection_blob(line: str) -> dict[int, int] | None:
    """Try to parse a standalone {grpId: count} blob."""
    try:
        d = json.loads(line)
    except (json.JSONDecodeError, ValueError):
        return None

    if not isinstance(d, dict) or len(d) < 500:
        return None

    # Heuristic: all keys look like grpIds (numeric strings), all values are small ints
    sample = list(d.items())[:20]
    if all(k.isdigit() and isinstance(v, int) and 0 < v <= 99 for k, v in sample):
        return {int(k): v for k, v in d.items()}
    return None


def _extract_json_object(text: str, start: int) -> dict | None:
    """Extract a balanced JSON object starting at position `start`."""
    if text[start] != "{":
        return None
    depth = 0
    for i in range(start, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                try:
                    return json.loads(text[start : i + 1])
                except (json.JSONDecodeError, ValueError):
                    return None
    return None


_RECORDINGS_DIR = Path(__file__).resolve().parents[5] / "recordings"


def _find_latest_fd_frames() -> Path | None:
    """Find fd-frames.jsonl in recordings/latest/ or most recent recording."""
    latest = _RECORDINGS_DIR / "latest" / "capture" / "fd-frames.jsonl"
    if latest.exists():
        return latest
    # Fall back to most recent by directory name
    dirs = sorted(_RECORDINGS_DIR.glob("2*/capture/fd-frames.jsonl"), reverse=True)
    return dirs[0] if dirs else None


def load_collection(
    fd_frames: Path | None = None,
    player_log: Path | None = None,
) -> dict[int, int]:
    """Load collection from best available source.

    Priority: explicit fd-frames → auto-detect latest recording → Player.log.
    Returns {grpId: count}. Raises SystemExit if no source found.
    """
    # 1. Explicit FD recording
    if fd_frames:
        result = from_fd_frames(fd_frames)
        if result:
            return result

    # 2. Auto-detect latest proxy recording
    auto_fd = _find_latest_fd_frames()
    if auto_fd:
        result = from_fd_frames(auto_fd)
        if result:
            return result

    # 3. Player.log
    result = from_player_log(player_log)
    if result:
        return result

    raise SystemExit(
        "No collection found. Options:\n"
        "  1. Run `just serve-proxy` session to capture collection\n"
        "  2. Connect Arena to real servers (collection in Player.log)\n"
        "  3. Use --no-collection to skip filtering (use all cards)"
    )
