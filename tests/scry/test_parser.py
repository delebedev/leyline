from __future__ import annotations

import json
from pathlib import Path

import pytest
from scry_lib.parser import GREBlock, parse_gre_blocks

FIXTURES = Path(__file__).parent / "fixtures"


# ---------------------------------------------------------------------------
# GREBlock unit tests
# ---------------------------------------------------------------------------

class TestGREBlock:
    def test_has_game_state_true(self):
        block = GREBlock(
            messages=[{"type": "GREMessageType_GameStateMessage"}],
            timestamp="08/03/2026 10:21:24",
            match_id="abc-123",
        )
        assert block.has_game_state is True

    def test_has_game_state_false(self):
        block = GREBlock(
            messages=[{"type": "GREMessageType_ConnectResp"}],
            timestamp="08/03/2026 10:21:24",
            match_id="abc-123",
        )
        assert block.has_game_state is False

    def test_game_state_messages(self):
        gsm = {"type": "GREMessageType_GameStateMessage", "gameStateMessage": {"gameStateId": 1}}
        other = {"type": "GREMessageType_ConnectResp"}
        block = GREBlock(messages=[other, gsm])
        result = block.game_state_messages()
        assert result == [gsm]

    def test_game_state_messages_empty(self):
        block = GREBlock(messages=[{"type": "GREMessageType_ConnectResp"}])
        assert block.game_state_messages() == []


# ---------------------------------------------------------------------------
# parse_gre_blocks
# ---------------------------------------------------------------------------

class TestParseGREBlocks:
    def test_fixture_block_count(self):
        lines = (FIXTURES / "game_start.jsonl").read_text().splitlines()
        blocks = list(parse_gre_blocks(lines))
        assert len(blocks) == 3

    def test_first_block_has_connect_resp(self):
        lines = (FIXTURES / "game_start.jsonl").read_text().splitlines()
        blocks = list(parse_gre_blocks(lines))
        types = [m["type"] for m in blocks[0].messages]
        assert "GREMessageType_ConnectResp" in types

    def test_finds_game_state_messages(self):
        lines = (FIXTURES / "game_start.jsonl").read_text().splitlines()
        blocks = list(parse_gre_blocks(lines))
        gsm_blocks = [b for b in blocks if b.has_game_state]
        assert len(gsm_blocks) >= 2  # blocks 1 and 2 have GSMs

    def test_extracts_timestamp(self):
        lines = (FIXTURES / "game_start.jsonl").read_text().splitlines()
        blocks = list(parse_gre_blocks(lines))
        assert blocks[0].timestamp == "08/03/2026 10:21:24"

    def test_extracts_match_id(self):
        lines = (FIXTURES / "game_start.jsonl").read_text().splitlines()
        blocks = list(parse_gre_blocks(lines))
        assert blocks[0].match_id == "9da3ee9f-0d6a-4b18-a3e0-c9e315d2475b"

    def test_raw_json_preserved(self):
        lines = (FIXTURES / "game_start.jsonl").read_text().splitlines()
        blocks = list(parse_gre_blocks(lines))
        assert blocks[0].raw_json is not None
        assert "greToClientEvent" in blocks[0].raw_json

    def test_skips_non_gre_lines(self):
        lines = [
            "some random log line",
            "[UnityCrossThreadLogger]08/03/2026 10:21:24: Match to abc-123: GreToClientEvent",
            '{ "greToClientEvent": { "greToClientMessages": [{"type": "GREMessageType_ConnectResp"}] } }',
            "another random line",
        ]
        blocks = list(parse_gre_blocks(lines))
        assert len(blocks) == 1

    def test_handles_malformed_json(self):
        lines = [
            "[UnityCrossThreadLogger]08/03/2026 10:21:24: Match to abc-123: GreToClientEvent",
            "{ this is not valid json }}}",
            "[UnityCrossThreadLogger]08/03/2026 10:21:25: Match to abc-123: GreToClientEvent",
            '{ "greToClientEvent": { "greToClientMessages": [{"type": "GREMessageType_ConnectResp"}] } }',
        ]
        blocks = list(parse_gre_blocks(lines))
        assert len(blocks) == 1
        assert blocks[0].messages[0]["type"] == "GREMessageType_ConnectResp"

    def test_empty_input(self):
        blocks = list(parse_gre_blocks([]))
        assert blocks == []

    def test_header_without_payload(self):
        """Header at end of input with no following line."""
        lines = [
            "[UnityCrossThreadLogger]08/03/2026 10:21:24: Match to abc-123: GreToClientEvent",
        ]
        blocks = list(parse_gre_blocks(lines))
        assert blocks == []

    def test_third_block_has_mulligan_req(self):
        lines = (FIXTURES / "game_start.jsonl").read_text().splitlines()
        blocks = list(parse_gre_blocks(lines))
        types = [m["type"] for m in blocks[2].messages]
        assert "GREMessageType_MulliganReq" in types
