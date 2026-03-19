"""Tests for current arena/scry integration helpers."""

from __future__ import annotations

import unittest
from unittest.mock import patch

from leyline_tools.arena import interaction as interaction_mod
from leyline_tools.arena import hand as hand_mod
from leyline_tools.arena import scry_bridge


class TestNormalizeZone(unittest.TestCase):
    def test_strips_prefix(self):
        assert scry_bridge._normalize_zone("ZoneType_Hand") == "Hand"
        assert scry_bridge._normalize_zone("ZoneType_Battlefield") == "Battlefield"
        assert scry_bridge._normalize_zone("ZoneType_Graveyard") == "Graveyard"

    def test_no_prefix_passthrough(self):
        assert scry_bridge._normalize_zone("Hand") == "Hand"
        assert scry_bridge._normalize_zone("Battlefield") == "Battlefield"
        assert scry_bridge._normalize_zone("") == ""


class TestGetCardZone(unittest.TestCase):
    @patch("leyline_tools.arena.interaction._scry_state")
    def test_finds_card_in_zone_dict_objects(self, mock_scry):
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
        assert interaction_mod._get_card_zone(42) == "Hand"
        assert interaction_mod._get_card_zone(99) == "Battlefield"

    @patch("leyline_tools.arena.interaction._scry_state")
    def test_finds_card_in_zone_int_objects(self, mock_scry):
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
        assert interaction_mod._get_card_zone(20) == "Graveyard"

    @patch("leyline_tools.arena.interaction._scry_state", return_value=None)
    def test_returns_none_when_scry_unavailable(self, _mock_scry):
        assert interaction_mod._get_card_zone(42) is None


class TestFindHandCard(unittest.TestCase):
    @patch("leyline_tools.arena.hand._find_hand_card_ocr", return_value=(420, 530))
    @patch("leyline_tools.arena.hand._scry_state")
    def test_finds_card_via_scry_and_ocr(self, mock_scry, mock_ocr):
        mock_scry.return_value = {
            "hand": [
                {"id": 10, "name": "Forest"},
                {"id": 11, "name": "Grizzly Bears"},
                {"id": 12, "name": "Mountain"},
            ]
        }
        result = hand_mod._find_hand_card("Grizzly Bears")
        assert result == ((420, 530), 11)
        mock_ocr.assert_called_once_with(
            "Grizzly Bears",
            ["Forest", "Grizzly Bears", "Mountain"],
        )

    @patch("leyline_tools.arena.hand._find_hand_card_ocr", return_value=(400, 520))
    @patch("leyline_tools.arena.hand._scry_state")
    def test_partial_name_match(self, mock_scry, _mock_ocr):
        mock_scry.return_value = {
            "hand": [{"id": 10, "name": "Grizzly Bears"}]
        }
        result = hand_mod._find_hand_card("grizzly")
        assert result == ((400, 520), 10)

    @patch("leyline_tools.arena.hand._find_hand_card_ocr", return_value=None)
    @patch("leyline_tools.arena.hand._scry_state")
    def test_returns_none_when_ocr_cannot_locate(self, mock_scry, _mock_ocr):
        mock_scry.return_value = {
            "hand": [{"id": 10, "name": "Forest"}]
        }
        assert hand_mod._find_hand_card("Forest") is None

    @patch("leyline_tools.arena.hand._scry_state", return_value=None)
    def test_returns_none_when_scry_unavailable(self, _mock_scry):
        assert hand_mod._find_hand_card("Forest") is None


if __name__ == "__main__":
    unittest.main()
