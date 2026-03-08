from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from typing import Iterable, Iterator

_HEADER_RE = re.compile(
    r"\[UnityCrossThreadLogger\]"
    r"(\d{2}/\d{2}/\d{4} \d{2}:\d{2}:\d{2}): "
    r"Match to ([0-9a-f-]+): GreToClientEvent"
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
            m.get("type") == "GREMessageType_GameStateMessage"
            for m in self.messages
        )

    def game_state_messages(self) -> list[dict]:
        return [
            m for m in self.messages
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
        if not m:
            continue

        timestamp = m.group(1)
        match_id = m.group(2)

        # Next line should be the JSON payload
        payload_line = next(it, None)
        if payload_line is None:
            break

        try:
            data = json.loads(payload_line)
        except (json.JSONDecodeError, ValueError):
            continue

        messages = (
            data
            .get("greToClientEvent", {})
            .get("greToClientMessages", [])
        )

        yield GREBlock(
            messages=messages,
            timestamp=timestamp,
            match_id=match_id,
            raw_json=data,
        )
