from __future__ import annotations

from pathlib import Path

from scry_lib.models import GameState
from scry_lib.parser import GREBlock, parse_gre_blocks
from scry_lib.tracker import CompletedGame, GameTracker

FIXTURES = Path(__file__).parent / "fixtures"


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------

def _gsm_block(
    gsm_raw: dict,
    match_id: str = "match-1",
    extra_messages: list[dict] | None = None,
) -> GREBlock:
    """Build a GREBlock with a single GameStateMessage (+ optional extras)."""
    messages = list(extra_messages or [])
    messages.append({
        "type": "GREMessageType_GameStateMessage",
        "gameStateMessage": gsm_raw,
    })
    return GREBlock(messages=messages, match_id=match_id)


def _connect_block(
    match_id: str = "match-1",
    gsm_raw: dict | None = None,
) -> GREBlock:
    """Block containing a ConnectResp (+ optional GameStateMessage)."""
    messages: list[dict] = [{"type": "GREMessageType_ConnectResp"}]
    if gsm_raw is not None:
        messages.append({
            "type": "GREMessageType_GameStateMessage",
            "gameStateMessage": gsm_raw,
        })
    return GREBlock(messages=messages, match_id=match_id)


def _full_gsm(**overrides) -> dict:
    base = {
        "type": "GameStateType_Full",
        "gameStateId": 1,
        "turnInfo": {"turnNumber": 1, "phase": "Phase_Beginning"},
        "players": [
            {"systemSeatNumber": 1, "lifeTotal": 20, "maxHandSize": 7,
             "teamId": 1, "controllerSeatId": 1, "startingLifeTotal": 20},
            {"systemSeatNumber": 2, "lifeTotal": 20, "maxHandSize": 7,
             "teamId": 2, "controllerSeatId": 2, "startingLifeTotal": 20},
        ],
        "zones": [
            {"zoneId": 28, "type": "ZoneType_Battlefield",
             "visibility": "Visibility_Public"},
            {"zoneId": 31, "type": "ZoneType_Hand",
             "visibility": "Visibility_Private", "ownerSeatId": 1,
             "objectInstanceIds": [151]},
        ],
        "gameObjects": [
            {"instanceId": 151, "grpId": 72032, "type": "GameObjectType_Card",
             "zoneId": 31, "ownerSeatId": 1, "controllerSeatId": 1,
             "cardTypes": ["CardType_Land"], "name": 46190},
        ],
    }
    base.update(overrides)
    return base


# ---------------------------------------------------------------------------
# Single game tracking
# ---------------------------------------------------------------------------

class TestSingleGame:
    def test_feed_sets_match_id(self):
        t = GameTracker()
        t.feed(_gsm_block(_full_gsm(), match_id="abc-123"))
        assert t.current_match_id == "abc-123"

    def test_feed_updates_state(self):
        t = GameTracker()
        t.feed(_gsm_block(_full_gsm()))
        assert t.current_state is not None
        assert t.current_state.game_state_id == 1

    def test_no_completed_games_initially(self):
        t = GameTracker()
        t.feed(_gsm_block(_full_gsm()))
        assert t.completed_games == []


# ---------------------------------------------------------------------------
# Multi-game (ConnectResp boundary)
# ---------------------------------------------------------------------------

class TestMultiGame:
    def test_connect_resp_archives_previous(self):
        t = GameTracker()
        # Game 1
        t.feed(_gsm_block(_full_gsm(gameStateId=1), match_id="match-1"))
        # Game 2 starts — ConnectResp with new full state
        t.feed(_connect_block(
            match_id="match-1",
            gsm_raw=_full_gsm(gameStateId=10),
        ))
        assert len(t.completed_games) == 1
        assert t.completed_games[0].match_id == "match-1"
        assert t.completed_games[0].final_state is not None
        assert t.completed_games[0].final_state.game_state_id == 1
        # Current state is from game 2
        assert t.current_state.game_state_id == 10

    def test_first_connect_resp_no_archive(self):
        """First ConnectResp (no prior game) should not archive."""
        t = GameTracker()
        t.feed(_connect_block(
            match_id="match-1",
            gsm_raw=_full_gsm(gameStateId=1),
        ))
        assert len(t.completed_games) == 0
        assert t.current_state.game_state_id == 1


# ---------------------------------------------------------------------------
# History cap
# ---------------------------------------------------------------------------

class TestHistoryCap:
    def test_completed_games_capped(self):
        t = GameTracker(max_history=2)
        # Play 3 games (each ConnectResp archives previous)
        t.feed(_gsm_block(_full_gsm(gameStateId=1), match_id="m1"))
        t.feed(_connect_block("m2", _full_gsm(gameStateId=2)))
        t.feed(_connect_block("m3", _full_gsm(gameStateId=3)))
        t.feed(_connect_block("m4", _full_gsm(gameStateId=4)))
        # 3 archives, capped at 2 → oldest dropped
        assert len(t.completed_games) == 2
        assert t.completed_games[0].match_id == "m2"
        assert t.completed_games[1].match_id == "m3"

    def test_default_max_history_is_5(self):
        t = GameTracker()
        assert t._max_history == 5


# ---------------------------------------------------------------------------
# to_dict
# ---------------------------------------------------------------------------

class TestToDict:
    def test_with_state(self):
        t = GameTracker()
        t.feed(_gsm_block(_full_gsm(gameStateId=7), match_id="m-1"))
        d = t.to_dict()
        assert d["match_id"] == "m-1"
        assert d["game_state_id"] == 7
        assert d["turn_info"]["turn_number"] == 1
        assert d["turn_info"]["phase"] == "Phase_Beginning"
        assert len(d["players"]) == 2
        assert d["players"][0] == {"seat": 1, "life": 20}
        assert d["object_count"] == 1
        assert d["completed_games"] == 0
        # Only non-empty zones
        assert "ZoneType_Hand" in d["zones"]
        assert "ZoneType_Battlefield" not in d["zones"]  # no objects

    def test_zones_include_owner_and_objects(self):
        t = GameTracker()
        t.feed(_gsm_block(_full_gsm(), match_id="m-1"))
        hand = t.to_dict()["zones"]["ZoneType_Hand"]
        assert hand["zone_id"] == 31
        assert hand["owner"] == 1
        assert hand["objects"] == [151]

    def test_with_no_state(self):
        t = GameTracker()
        t.current_match_id = "pending"
        d = t.to_dict()
        assert d == {"match_id": "pending", "state": None}

    def test_with_no_match_and_no_state(self):
        t = GameTracker()
        d = t.to_dict()
        assert d == {"match_id": None, "state": None}

    def test_completed_games_count_in_dict(self):
        t = GameTracker()
        t.feed(_gsm_block(_full_gsm(gameStateId=1), match_id="m1"))
        t.feed(_connect_block("m2", _full_gsm(gameStateId=2)))
        d = t.to_dict()
        assert d["completed_games"] == 1

    def test_turn_info_none_when_absent(self):
        gsm = _full_gsm()
        del gsm["turnInfo"]
        t = GameTracker()
        t.feed(_gsm_block(gsm))
        assert t.to_dict()["turn_info"] is None


# ---------------------------------------------------------------------------
# Integration: real fixture data
# ---------------------------------------------------------------------------

class TestRealData:
    def test_game_start_fixture(self):
        t = GameTracker()
        with open(FIXTURES / "game_start.jsonl") as f:
            lines = f.readlines()
        for block in parse_gre_blocks(lines):
            t.feed(block)
        assert t.current_state is not None
        assert t.current_match_id is not None
        assert len(t.completed_games) == 0

    def test_multi_game_fixture(self):
        """multi_game.jsonl has game-end states then a ConnectResp for game 2."""
        t = GameTracker()
        with open(FIXTURES / "multi_game.jsonl") as f:
            lines = f.readlines()
        for block in parse_gre_blocks(lines):
            t.feed(block)
        # Should have archived game 1
        assert len(t.completed_games) == 1
        assert t.completed_games[0].final_state is not None
        # Current state should be from game 2
        assert t.current_state is not None

    def test_multi_game_to_dict(self):
        t = GameTracker()
        with open(FIXTURES / "multi_game.jsonl") as f:
            lines = f.readlines()
        for block in parse_gre_blocks(lines):
            t.feed(block)
        d = t.to_dict()
        assert d["completed_games"] == 1
        assert d["match_id"] is not None
        assert d["game_state_id"] > 0
