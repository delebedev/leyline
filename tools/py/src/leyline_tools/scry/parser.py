from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from typing import Iterable, Iterator

_HEADER_RE = re.compile(
    r"\[UnityCrossThreadLogger\]"
    r"(\d{2}/\d{2}/\d{4} \d{2}:\d{2}:\d{2}): "
    r"Match to ([0-9a-zA-Z_-]+): GreToClientEvent"
)

# Standalone GRE JSON line (no header — written by some server sessions)
_STANDALONE_GRE_RE = re.compile(r'^\{\s*"greToClientEvent":')

_SCENE_CHANGE_RE = re.compile(
    r"\[UnityCrossThreadLogger\]Client\.SceneChange\s+(\{.+\})"
)


@dataclass
class GREBlock:
    """A single GRE-to-client event block extracted from Player.log."""

    messages: list[dict] = field(default_factory=list)
    timestamp: str | None = None
    match_id: str | None = None
    raw_json: dict | None = None

    @property
    def has_game_state(self) -> bool:
        return any(
            m.get("type") == "GREMessageType_GameStateMessage" for m in self.messages
        )

    def game_state_messages(self) -> list[dict]:
        return [
            m
            for m in self.messages
            if m.get("type") == "GREMessageType_GameStateMessage"
        ]


def parse_gre_blocks(lines: Iterable[str]) -> Iterator[GREBlock]:
    """Yield GREBlock instances from Player.log lines.

    Expects a two-line pattern: header line followed by JSON payload line.
    Skips non-GRE lines and malformed JSON silently.
    """
    it = iter(lines)
    for line in it:
        m = _HEADER_RE.search(line)
        if m:
            timestamp = m.group(1)
            match_id = m.group(2)
            payload_line = next(it, None)
            if payload_line is None:
                break
            try:
                data = json.loads(payload_line)
            except (json.JSONDecodeError, ValueError):
                continue
            messages = data.get("greToClientEvent", {}).get("greToClientMessages", [])
            yield GREBlock(
                messages=messages,
                timestamp=timestamp,
                match_id=match_id,
                raw_json=data,
            )
            continue

        # Standalone GRE JSON (no header line)
        if _STANDALONE_GRE_RE.match(line):
            try:
                data = json.loads(line)
            except (json.JSONDecodeError, ValueError):
                continue
            messages = data.get("greToClientEvent", {}).get("greToClientMessages", [])
            mid = None
            for msg in messages:
                gi = msg.get("gameStateMessage", {}).get("gameInfo", {})
                if gi.get("matchID"):
                    mid = gi["matchID"]
                    break
            yield GREBlock(
                messages=messages,
                timestamp=None,
                match_id=mid,
                raw_json=data,
            )
            continue


def parse_log(lines: Iterable[str]) -> Iterator[GREBlock | ClientError | SceneChange]:
    """Yield GREBlocks, ClientErrors, and SceneChanges from Player.log lines.

    Unified stream — processes GRE messages, exception lines, and navigation events.
    """
    from .errors import ClientError, parse_client_error
    from .models import SceneChange

    it = iter(lines)
    for line in it:
        m = _HEADER_RE.search(line)
        if m:
            timestamp = m.group(1)
            match_id = m.group(2)
            payload_line = next(it, None)
            if payload_line is None:
                break
            try:
                data = json.loads(payload_line)
            except (json.JSONDecodeError, ValueError):
                continue
            messages = data.get("greToClientEvent", {}).get("greToClientMessages", [])
            yield GREBlock(
                messages=messages,
                timestamp=timestamp,
                match_id=match_id,
                raw_json=data,
            )
            continue

        # Standalone GRE JSON (no header line — some server sessions)
        if _STANDALONE_GRE_RE.match(line):
            try:
                data = json.loads(line)
            except (json.JSONDecodeError, ValueError):
                continue
            messages = data.get("greToClientEvent", {}).get("greToClientMessages", [])
            # Extract match ID from gameInfo if present
            mid = None
            for msg in messages:
                gi = msg.get("gameStateMessage", {}).get("gameInfo", {})
                if gi.get("matchID"):
                    mid = gi["matchID"]
                    break
            yield GREBlock(
                messages=messages,
                timestamp=None,
                match_id=mid,
                raw_json=data,
            )
            continue

        sm = _SCENE_CHANGE_RE.search(line)
        if sm:
            try:
                raw = json.loads(sm.group(1))
            except (json.JSONDecodeError, ValueError):
                continue
            yield SceneChange.from_raw(raw)
            continue

        err = parse_client_error(line)
        if err is not None:
            yield err
