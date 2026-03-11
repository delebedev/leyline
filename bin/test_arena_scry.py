"""Tests for arena.py scry fallback paths."""

from __future__ import annotations

import json
import subprocess
import unittest
from unittest.mock import MagicMock, patch

# Import the module under test
import arena as arena_mod


class TestNormalizeZone(unittest.TestCase):
    def test_strips_prefix(self):
        assert arena_mod._normalize_zone("ZoneType_Hand") == "Hand"
        assert arena_mod._normalize_zone("ZoneType_Battlefield") == "Battlefield"
        assert arena_mod._normalize_zone("ZoneType_Graveyard") == "Graveyard"

    def test_no_prefix_passthrough(self):
        assert arena_mod._normalize_zone("Hand") == "Hand"
        assert arena_mod._normalize_zone("Battlefield") == "Battlefield"
        assert arena_mod._normalize_zone("") == ""


class TestScryState(unittest.TestCase):
    def setUp(self):
        # Clear cache between tests
        arena_mod._scry_cache = None
        arena_mod._scry_cache_ts = 0.0

    @patch("arena.subprocess.run")
    def test_parses_json_output(self, mock_run):
        state = {"hand": [{"id": 42, "name": "Forest"}], "zones": []}
        mock_run.return_value = MagicMock(
            returncode=0, stdout=json.dumps(state), stderr=""
        )
        result = arena_mod._scry_state()
        assert result == state
        mock_run.assert_called_once()

    @patch("arena.subprocess.run")
    def test_returns_none_on_failure(self, mock_run):
        mock_run.return_value = MagicMock(returncode=1, stdout="", stderr="error")
        assert arena_mod._scry_state() is None

    @patch("arena.subprocess.run")
    def test_returns_none_on_empty_stdout(self, mock_run):
        mock_run.return_value = MagicMock(returncode=0, stdout="", stderr="")
        assert arena_mod._scry_state() is None

    @patch("arena.subprocess.run")
    def test_returns_none_on_bad_json(self, mock_run):
        mock_run.return_value = MagicMock(returncode=0, stdout="not json", stderr="")
        assert arena_mod._scry_state() is None

    @patch("arena.subprocess.run")
    def test_returns_none_on_timeout(self, mock_run):
        mock_run.side_effect = subprocess.TimeoutExpired(cmd="scry", timeout=10)
        assert arena_mod._scry_state() is None

    @patch("arena.subprocess.run")
    def test_caches_result(self, mock_run):
        state = {"hand": [], "zones": []}
        mock_run.return_value = MagicMock(
            returncode=0, stdout=json.dumps(state), stderr=""
        )
        arena_mod._scry_state()
        arena_mod._scry_state()
        # Should only call subprocess once due to caching
        mock_run.assert_called_once()


class TestGetCardZoneScryFallback(unittest.TestCase):
    def setUp(self):
        arena_mod._scry_cache = None
        arena_mod._scry_cache_ts = 0.0

    @patch("arena._scry_state")
    @patch("arena.fetch_api", return_value=None)
    def test_finds_card_in_zone_dict_objects(self, _fetch, mock_scry):
        mock_scry.return_value = {
            "zones": [
                {
                    "zone_id": 1,
                    "type": "ZoneType_Hand",
                    "owner": 1,
                    "objects": [{"id": 42, "name": "Forest"}],
                },
                {
                    "zone_id": 2,
                    "type": "ZoneType_Battlefield",
                    "owner": 1,
                    "objects": [{"id": 99, "name": "Mountain"}],
                },
            ]
        }
        assert arena_mod._get_card_zone(42) == "Hand"
        assert arena_mod._get_card_zone(99) == "Battlefield"

    @patch("arena._scry_state")
    @patch("arena.fetch_api", return_value=None)
    def test_finds_card_in_zone_int_objects(self, _fetch, mock_scry):
        mock_scry.return_value = {
            "zones": [
                {
                    "zone_id": 1,
                    "type": "ZoneType_Graveyard",
                    "owner": 1,
                    "objects": [10, 20, 30],
                },
            ]
        }
        assert arena_mod._get_card_zone(20) == "Graveyard"

    @patch("arena._scry_state")
    @patch("arena.fetch_api", return_value=None)
    def test_returns_none_when_not_found(self, _fetch, mock_scry):
        mock_scry.return_value = {
            "zones": [
                {
                    "zone_id": 1,
                    "type": "ZoneType_Hand",
                    "owner": 1,
                    "objects": [{"id": 42, "name": "Forest"}],
                },
            ]
        }
        assert arena_mod._get_card_zone(999) is None

    @patch("arena._scry_state", return_value=None)
    @patch("arena.fetch_api", return_value=None)
    def test_returns_none_when_scry_fails(self, _fetch, _scry):
        assert arena_mod._get_card_zone(42) is None

    @patch("arena.fetch_api")
    def test_prefers_debug_api(self, mock_fetch):
        mock_fetch.return_value = json.dumps(
            [{"instanceId": 42, "forgeZone": "Hand"}]
        )
        assert arena_mod._get_card_zone(42) == "Hand"


class TestFindHandCardScryFallback(unittest.TestCase):
    def setUp(self):
        arena_mod._scry_cache = None
        arena_mod._scry_cache_ts = 0.0

    @patch("arena._board_detect", return_value=[])
    @patch("arena._scry_state")
    @patch("arena.fetch_api", return_value=None)
    def test_finds_card_via_scry(self, _fetch, mock_scry, _detect):
        mock_scry.return_value = {
            "hand": [
                {"id": 10, "name": "Forest"},
                {"id": 11, "name": "Grizzly Bears"},
                {"id": 12, "name": "Mountain"},
            ]
        }
        result = arena_mod._find_hand_card("Grizzly Bears")
        assert result is not None
        (cx, cy), iid = result
        assert iid == 11
        assert cy == 530  # fallback estimated position

    @patch("arena._board_detect", return_value=[])
    @patch("arena._scry_state")
    @patch("arena.fetch_api", return_value=None)
    def test_partial_name_match(self, _fetch, mock_scry, _detect):
        mock_scry.return_value = {
            "hand": [{"id": 10, "name": "Grizzly Bears"}]
        }
        result = arena_mod._find_hand_card("grizzly")
        assert result is not None
        _, iid = result
        assert iid == 10

    @patch("arena._board_detect", return_value=[])
    @patch("arena._scry_state")
    @patch("arena.fetch_api", return_value=None)
    def test_returns_none_when_not_in_hand(self, _fetch, mock_scry, _detect):
        mock_scry.return_value = {
            "hand": [{"id": 10, "name": "Forest"}]
        }
        assert arena_mod._find_hand_card("Mountain") is None

    @patch("arena._scry_state", return_value=None)
    @patch("arena.fetch_api", return_value=None)
    def test_returns_none_when_both_fail(self, _fetch, _scry):
        assert arena_mod._find_hand_card("Forest") is None

    @patch("arena._board_detect", return_value=[])
    @patch("arena.fetch_api")
    def test_prefers_debug_api(self, mock_fetch, _detect):
        mock_fetch.return_value = json.dumps(
            {
                "data": [
                    {
                        "instanceId": 42,
                        "cardName": "Forest",
                        "forgeZone": "Hand",
                        "ownerSeatId": 1,
                    }
                ]
            }
        )
        result = arena_mod._find_hand_card("Forest")
        assert result is not None
        _, iid = result
        assert iid == 42


if __name__ == "__main__":
    unittest.main()
