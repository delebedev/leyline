from __future__ import annotations

import json
from pathlib import Path

import pytest
from scry_lib.parser import GREBlock, parse_gre_blocks, parse_log

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
        gsm = {
            "type": "GREMessageType_GameStateMessage",
            "gameStateMessage": {"gameStateId": 1},
        }
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


# ---------------------------------------------------------------------------
# Standalone GRE lines (no header)
# ---------------------------------------------------------------------------


class TestStandaloneGRE:
    def test_standalone_gre_parsed_by_parse_log(self):
        """GRE JSON without a header line should be parsed as a GREBlock."""
        line = '{ "greToClientEvent": { "greToClientMessages": [{"type": "GREMessageType_ConnectResp"}] } }'
        blocks = [e for e in parse_log([line]) if isinstance(e, GREBlock)]
        assert len(blocks) == 1
        assert blocks[0].messages[0]["type"] == "GREMessageType_ConnectResp"
        assert blocks[0].timestamp is None

    def test_standalone_extracts_match_id_from_game_info(self):
        line = json.dumps(
            {
                "greToClientEvent": {
                    "greToClientMessages": [
                        {
                            "type": "GREMessageType_GameStateMessage",
                            "gameStateMessage": {
                                "type": "GameStateType_Full",
                                "gameStateId": 2,
                                "gameInfo": {"matchID": "standalone-match-123"},
                            },
                        }
                    ],
                },
            }
        )
        blocks = [e for e in parse_log([line]) if isinstance(e, GREBlock)]
        assert len(blocks) == 1
        assert blocks[0].match_id == "standalone-match-123"

    def test_standalone_no_match_id_when_absent(self):
        line = '{ "greToClientEvent": { "greToClientMessages": [{"type": "GREMessageType_ActionsAvailableReq"}] } }'
        blocks = [e for e in parse_log([line]) if isinstance(e, GREBlock)]
        assert len(blocks) == 1
        assert blocks[0].match_id is None

    def test_mixed_header_and_standalone(self):
        """Header-style block (2 lines) followed by standalone GRE line."""
        lines = [
            "[UnityCrossThreadLogger]08/03/2026 10:21:24: Match to 9da3ee9f-0d6a-4b18-a3e0-c9e315d2475b: GreToClientEvent",
            '{ "greToClientEvent": { "greToClientMessages": [{"type": "GREMessageType_ConnectResp"}] } }',
            "some other log line",
            '{ "greToClientEvent": { "greToClientMessages": [{"type": "GREMessageType_ActionsAvailableReq"}] } }',
        ]
        blocks = [e for e in parse_log(lines) if isinstance(e, GREBlock)]
        # Line 0+1 = header-style block, line 3 = standalone
        assert len(blocks) == 2
        assert blocks[0].match_id == "9da3ee9f-0d6a-4b18-a3e0-c9e315d2475b"
        assert blocks[0].timestamp == "08/03/2026 10:21:24"
        assert blocks[1].match_id is None
        assert blocks[1].timestamp is None
