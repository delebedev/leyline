from __future__ import annotations

import tempfile
from pathlib import Path

import pytest
from scry_lib.tail import find_last_full_offset, tail_log

FIXTURES = Path(__file__).parent / "fixtures"


# ---------------------------------------------------------------------------
# find_last_full_offset
# ---------------------------------------------------------------------------

class TestScanBackwardForFull:
    def test_game_start_fixture_finds_full(self):
        path = FIXTURES / "game_start.jsonl"
        offset = find_last_full_offset(path)
        assert offset is not None
        # The Full is in the first GRE block (line 1 header, line 2 JSON).
        # Offset should be 0 — the header is the very first line.
        assert offset == 0

    def test_game_start_offset_points_to_header(self):
        path = FIXTURES / "game_start.jsonl"
        offset = find_last_full_offset(path)
        assert offset is not None
        with open(path) as f:
            f.seek(offset)
            line = f.readline()
        assert "GreToClientEvent" in line

    def test_mid_session_has_no_full(self):
        """mid_session_catchup.jsonl has only Diff states, no Full."""
        path = FIXTURES / "mid_session_catchup.jsonl"
        offset = find_last_full_offset(path)
        assert offset is None

    def test_empty_file(self):
        with tempfile.NamedTemporaryFile(mode="w", suffix=".jsonl", delete=False) as f:
            f.flush()
            result = find_last_full_offset(Path(f.name))
        assert result is None

    def test_no_full_in_diffs_only(self):
        with tempfile.NamedTemporaryFile(mode="w", suffix=".jsonl", delete=False) as f:
            f.write("[UnityCrossThreadLogger]08/03/2026 10:21:24: Match to abc-123: GreToClientEvent\n")
            f.write('{ "greToClientEvent": { "greToClientMessages": [{"type": "GREMessageType_GameStateMessage", "gameStateMessage": {"type": "GameStateType_Diff"}}] } }\n')
            f.flush()
            result = find_last_full_offset(Path(f.name))
        assert result is None

    def test_returns_last_full_header(self):
        """When there are two Fulls, return the header offset of the last one."""
        with tempfile.NamedTemporaryFile(mode="w", suffix=".jsonl", delete=False) as f:
            # First Full
            f.write("[UnityCrossThreadLogger]08/03/2026 10:21:24: Match to abc-123: GreToClientEvent\n")
            f.write('{ "greToClientEvent": { "greToClientMessages": [{"type": "GREMessageType_GameStateMessage", "gameStateMessage": {"type": "GameStateType_Full"}}] } }\n')
            second_header_offset = f.tell()
            # Second Full
            f.write("[UnityCrossThreadLogger]08/03/2026 10:22:00: Match to abc-123: GreToClientEvent\n")
            f.write('{ "greToClientEvent": { "greToClientMessages": [{"type": "GREMessageType_GameStateMessage", "gameStateMessage": {"type": "GameStateType_Full"}}] } }\n')
            f.flush()
            result = find_last_full_offset(Path(f.name))
        assert result == second_header_offset


# ---------------------------------------------------------------------------
# tail_log — non-follow mode only
# ---------------------------------------------------------------------------

class TestTailLog:
    def test_reads_all_lines(self):
        with tempfile.NamedTemporaryFile(mode="w", suffix=".log", delete=False) as f:
            f.write("line1\nline2\nline3\n")
            f.flush()
            lines = list(tail_log(Path(f.name)))
        assert lines == ["line1\n", "line2\n", "line3\n"]

    def test_empty_file(self):
        with tempfile.NamedTemporaryFile(mode="w", suffix=".log", delete=False) as f:
            f.flush()
            lines = list(tail_log(Path(f.name)))
        assert lines == []

    def test_start_offset(self):
        with tempfile.NamedTemporaryFile(mode="w", suffix=".log", delete=False) as f:
            f.write("line1\n")
            offset = f.tell()
            f.write("line2\nline3\n")
            f.flush()
            lines = list(tail_log(Path(f.name), start_offset=offset))
        assert lines == ["line2\n", "line3\n"]

    def test_start_offset_beyond_eof(self):
        """If offset > file size (truncation), should reset to 0."""
        with tempfile.NamedTemporaryFile(mode="w", suffix=".log", delete=False) as f:
            f.write("short\n")
            f.flush()
            lines = list(tail_log(Path(f.name), start_offset=9999))
        assert lines == ["short\n"]

    def test_fixture_with_offset(self):
        """Read game_start.jsonl from a known offset, get fewer lines."""
        path = FIXTURES / "game_start.jsonl"
        all_lines = list(tail_log(path))
        # Read from offset of last line pair
        with open(path, "rb") as f:
            content = f.read()
        # Find the last header
        last_header_pos = content.rfind(b"[UnityCrossThreadLogger]")
        partial_lines = list(tail_log(path, start_offset=last_header_pos))
        assert len(partial_lines) < len(all_lines)
        assert len(partial_lines) >= 1
