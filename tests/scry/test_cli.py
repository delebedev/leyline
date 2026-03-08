from __future__ import annotations

import json
import os
import subprocess
import tempfile
from pathlib import Path

import pytest

FIXTURES = Path(__file__).parent / "fixtures"
BIN = Path(__file__).parent.parent.parent / "bin"
SCRY = str(BIN / "scry")
ENV = {**os.environ, "PYTHONPATH": str(BIN)}


def run_scry(*args: str, check: bool = False) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["python3", SCRY, *args],
        capture_output=True,
        text=True,
        env=ENV,
        check=check,
        timeout=10,
    )


# ---------------------------------------------------------------------------
# scry state
# ---------------------------------------------------------------------------

class TestStateSubcommand:
    def test_state_with_fixture(self):
        fixture = str(FIXTURES / "game_start.jsonl")
        result = run_scry("state", "--log", fixture, "--no-catchup")
        assert result.returncode == 0
        data = json.loads(result.stdout)
        assert data["match_id"] is not None
        assert data["game_state_id"] is not None

    def test_state_empty_file(self):
        with tempfile.NamedTemporaryFile(mode="w", suffix=".log", delete=False) as f:
            f.write("")
            tmp = f.name
        try:
            result = run_scry("state", "--log", tmp, "--no-catchup")
            assert result.returncode == 0
            data = json.loads(result.stdout)
            assert data["state"] is None
        finally:
            os.unlink(tmp)

    def test_state_nonexistent_log(self):
        result = run_scry("state", "--log", "/tmp/no-such-file-12345.log")
        assert result.returncode == 0
        data = json.loads(result.stdout)
        assert data["state"] is None
        assert "error" in data

    def test_state_catchup_default(self):
        """Default (with catchup) should still produce valid output."""
        fixture = str(FIXTURES / "game_start.jsonl")
        result = run_scry("state", "--log", fixture)
        assert result.returncode == 0
        data = json.loads(result.stdout)
        # With catchup, we scan for Full GSM and replay from there
        assert data["match_id"] is not None


# ---------------------------------------------------------------------------
# scry stream
# ---------------------------------------------------------------------------

class TestStreamSubcommand:
    def test_stream_fixture(self):
        fixture = str(FIXTURES / "game_start.jsonl")
        result = run_scry("stream", "--log", fixture)
        assert result.returncode == 0
        lines = [l for l in result.stdout.strip().split("\n") if l]
        assert len(lines) == 3  # 3 GRE blocks in fixture
        for line in lines:
            obj = json.loads(line)
            assert "match_id" in obj
            assert "timestamp" in obj
            assert "message_types" in obj
            assert "gsids" in obj

    def test_stream_nonexistent(self):
        result = run_scry("stream", "--log", "/tmp/no-such-file-12345.log")
        assert result.returncode == 1
        assert result.stderr.strip()  # error message on stderr

    def test_stream_message_types(self):
        fixture = str(FIXTURES / "game_start.jsonl")
        result = run_scry("stream", "--log", fixture)
        lines = result.stdout.strip().split("\n")
        first = json.loads(lines[0])
        assert "GREMessageType_ConnectResp" in first["message_types"]


# ---------------------------------------------------------------------------
# scry serve
# ---------------------------------------------------------------------------

class TestServeSubcommand:
    def test_serve_placeholder(self):
        result = run_scry("serve")
        assert result.returncode == 0
        assert "not implemented" in result.stdout.lower()


# ---------------------------------------------------------------------------
# scry (no args)
# ---------------------------------------------------------------------------

class TestNoArgs:
    def test_no_args_shows_help(self):
        result = run_scry()
        assert result.returncode == 0
        # Help text should mention subcommands
        combined = result.stdout + result.stderr
        assert "state" in combined.lower() or "usage" in combined.lower()
