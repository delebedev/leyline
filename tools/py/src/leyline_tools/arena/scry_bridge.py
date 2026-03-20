from __future__ import annotations

import json
import re
import time
from pathlib import Path

from leyline_tools.scry.cards import CardResolver, find_arena_db
from leyline_tools.scry.errors import ClientError
from leyline_tools.scry.models import SceneChange
from leyline_tools.scry.parser import GREBlock, parse_log
from leyline_tools.scry.tail import find_last_full_offset, tail_log
from leyline_tools.scry.tracker import GameTracker


_scry_cache: dict | None = None
_scry_cache_ts: float = 0.0
_SCRY_TTL = 1.0
_scry_resolver = None
_scry_resolver_init = False

_SCENE_RE = re.compile(r"\[UnityCrossThreadLogger\]Client\.SceneChange\s+(\{.+\})")
_LOGIN_RE = re.compile(r"Wotc\.Mtga\.Login\.LoginScene")
_CONNECT_RE = re.compile(r'"type"\s*:\s*"GREMessageType_ConnectResp"')
_GAME_OVER_RE = re.compile(r'"gameOver"\s*:\s*true')
_RESULT_RE = re.compile(r'"ResultType_WinLoss"')
_TAIL_BYTES = 1024 * 1024


def _get_resolver():
    global _scry_resolver, _scry_resolver_init
    if _scry_resolver_init:
        return _scry_resolver
    _scry_resolver_init = True
    try:
        db = find_arena_db()
        if db:
            _scry_resolver = CardResolver(db)
    except Exception:
        pass
    return _scry_resolver


def _scry_cache_clear() -> None:
    global _scry_cache, _scry_cache_ts
    _scry_cache = None
    _scry_cache_ts = 0.0


def _scry_state() -> dict | None:
    global _scry_cache, _scry_cache_ts
    now = time.time()
    if _scry_cache is not None and now - _scry_cache_ts < _SCRY_TTL:
        return _scry_cache

    try:
        log_path = Path.home() / "Library/Logs/Wizards of the Coast/MTGA/Player.log"
        if not log_path.exists():
            return None

        offset = find_last_full_offset(log_path)
        start = offset if offset is not None else 0
        lines = list(tail_log(log_path, follow=False, start_offset=start))

        tracker = GameTracker()
        for event in parse_log(lines):
            if isinstance(event, GREBlock):
                tracker.feed(event)
            elif isinstance(event, ClientError):
                tracker.feed_error(event)
            elif isinstance(event, SceneChange):
                tracker.feed_scene(event)

        data = tracker.to_dict(card_resolver=_get_resolver())
        _scry_cache = data
        _scry_cache_ts = time.time()
        return data
    except (FileNotFoundError, OSError, json.JSONDecodeError, ValueError, KeyError):
        return None


def _normalize_zone(zone_type: str) -> str:
    if zone_type.startswith("ZoneType_"):
        return zone_type[len("ZoneType_") :]
    return zone_type


def get_current_scene() -> str | None:
    log_path = Path.home() / "Library/Logs/Wizards of the Coast/MTGA/Player.log"
    if not log_path.exists():
        return None
    size = log_path.stat().st_size
    offset = max(0, size - _TAIL_BYTES)
    with open(log_path, "r", errors="replace") as f:
        if offset > 0:
            f.seek(offset)
            f.readline()
        current = None
        for line in f:
            m = _SCENE_RE.search(line)
            if m:
                try:
                    raw = json.loads(m.group(1))
                    current = raw.get("toSceneName")
                except (json.JSONDecodeError, ValueError):
                    continue
            elif _LOGIN_RE.search(line):
                current = "Login"
            elif _CONNECT_RE.search(line):
                current = "InGame"
            elif _GAME_OVER_RE.search(line) or _RESULT_RE.search(line):
                current = "PostGame"
    return current


def poll_scene(target: str, timeout_ms: int) -> bool:
    deadline = time.time() + timeout_ms / 1000
    while time.time() < deadline:
        scene = get_current_scene()
        if scene and scene.lower() == target.lower():
            return True
        time.sleep(0.5)
    return False
