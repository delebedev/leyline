from __future__ import annotations

from pathlib import Path

import pytest
from scry_lib.accumulator import Accumulator
from scry_lib.models import GameState, GameObject
from scry_lib.parser import parse_gre_blocks

FIXTURES = Path(__file__).parent / "fixtures"


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------

def _full_state(**overrides) -> GameState:
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
        "annotations": [{"id": 49, "type": ["AnnotationType_NewTurnStarted"]}],
        "actions": [{"seatId": 1, "action": {"actionType": "ActionType_Pass"}}],
        "gameInfo": {"matchID": "test", "gameNumber": 1},
    }
    base.update(overrides)
    return GameState.from_raw(base)


def _diff_state(**overrides) -> GameState:
    base: dict = {"type": "GameStateType_Diff", "gameStateId": 2}
    base.update(overrides)
    return GameState.from_raw(base)


# ---------------------------------------------------------------------------
# Full state
# ---------------------------------------------------------------------------

class TestFullState:
    def test_full_sets_current(self):
        acc = Accumulator()
        full = _full_state()
        acc.apply(full)
        assert acc.current is not None
        assert acc.current.game_state_id == 1
        assert acc.current.is_full

    def test_second_full_replaces_current(self):
        acc = Accumulator()
        acc.apply(_full_state(gameStateId=1))
        acc.apply(_full_state(gameStateId=5,
                              gameInfo={"matchID": "new", "gameNumber": 2}))
        assert acc.current.game_state_id == 5
        assert acc.current.game_info["matchID"] == "new"


# ---------------------------------------------------------------------------
# Diff merge
# ---------------------------------------------------------------------------

class TestDiffMerge:
    @pytest.fixture()
    def acc(self) -> Accumulator:
        a = Accumulator()
        a.apply(_full_state())
        return a

    def test_diff_merges_turn_info(self, acc: Accumulator):
        acc.apply(_diff_state(
            turnInfo={"turnNumber": 2, "phase": "Phase_Combat"},
        ))
        assert acc.current.turn_info.turn_number == 2
        assert acc.current.turn_info.phase == "Phase_Combat"

    def test_diff_keeps_base_turn_info_when_absent(self, acc: Accumulator):
        acc.apply(_diff_state())
        assert acc.current.turn_info is not None
        assert acc.current.turn_info.turn_number == 1

    def test_diff_merges_players_by_seat(self, acc: Accumulator):
        acc.apply(_diff_state(
            players=[{"systemSeatNumber": 1, "lifeTotal": 17,
                       "maxHandSize": 7, "teamId": 1,
                       "controllerSeatId": 1, "startingLifeTotal": 20}],
        ))
        p1 = next(p for p in acc.current.players if p.seat_number == 1)
        p2 = next(p for p in acc.current.players if p.seat_number == 2)
        assert p1.life_total == 17
        assert p2.life_total == 20  # unchanged

    def test_diff_merges_zones_by_zone_id(self, acc: Accumulator):
        acc.apply(_diff_state(
            zones=[{"zoneId": 31, "type": "ZoneType_Hand",
                    "visibility": "Visibility_Private", "ownerSeatId": 1,
                    "objectInstanceIds": [151, 152]}],
        ))
        hand = next(z for z in acc.current.zones if z.zone_id == 31)
        assert hand.object_ids == [151, 152]

    def test_diff_preserves_unchanged_zones(self, acc: Accumulator):
        acc.apply(_diff_state(
            zones=[{"zoneId": 31, "type": "ZoneType_Hand",
                    "visibility": "Visibility_Private", "ownerSeatId": 1,
                    "objectInstanceIds": [151, 152]}],
        ))
        bf = next(z for z in acc.current.zones if z.zone_id == 28)
        assert bf.type == "ZoneType_Battlefield"

    def test_diff_adds_new_objects(self, acc: Accumulator):
        acc.apply(_diff_state(
            gameObjects=[
                {"instanceId": 200, "grpId": 99, "type": "GameObjectType_Card",
                 "zoneId": 28, "ownerSeatId": 1, "controllerSeatId": 1,
                 "cardTypes": ["CardType_Creature"], "name": 1,
                 "power": {"value": 2}, "toughness": {"value": 2}},
            ],
        ))
        assert 200 in acc.current.objects
        assert acc.current.objects[200].grp_id == 99
        # original object preserved
        assert 151 in acc.current.objects

    def test_diff_updates_existing_objects_field_merge(self, acc: Accumulator):
        """Diff updates zone_id on existing object; grp_id stays from base."""
        acc.apply(_diff_state(
            gameObjects=[
                {"instanceId": 151, "zoneId": 28},
            ],
        ))
        obj = acc.current.objects[151]
        assert obj.zone_id == 28         # updated by diff
        assert obj.grp_id == 72032       # kept from base

    def test_diff_deletes_objects(self, acc: Accumulator):
        acc.apply(_diff_state(diffDeletedInstanceIds=[151]))
        assert 151 not in acc.current.objects

    def test_diff_annotations_actions_not_cumulative(self, acc: Accumulator):
        """Diff annotations/actions replace, not append."""
        acc.apply(_diff_state(
            annotations=[{"id": 100, "type": ["AnnotationType_ResolutionComplete"]}],
            actions=[],
        ))
        assert len(acc.current.annotations) == 1
        assert acc.current.annotations[0]["id"] == 100
        assert acc.current.actions == []

    def test_diff_game_info_replaces_when_present(self, acc: Accumulator):
        acc.apply(_diff_state(gameInfo={"matchID": "new", "gameNumber": 2}))
        assert acc.current.game_info["matchID"] == "new"

    def test_diff_game_info_kept_when_absent(self, acc: Accumulator):
        acc.apply(_diff_state())
        assert acc.current.game_info["matchID"] == "test"


# ---------------------------------------------------------------------------
# is_tapped untap scenario
# ---------------------------------------------------------------------------

class TestIsTappedUntap:
    def test_untap_via_diff(self):
        """Base: tapped card. Diff sets isTapped:false. Card must untap.

        This is the Go mtg-lab bug scenario — false is a zero value in Go
        so it wouldn't overwrite. We handle it via raw-dict merge.
        """
        acc = Accumulator()
        full = GameState.from_raw({
            "type": "GameStateType_Full",
            "gameStateId": 1,
            "gameObjects": [
                {"instanceId": 300, "grpId": 72032,
                 "type": "GameObjectType_Card", "zoneId": 28,
                 "ownerSeatId": 1, "controllerSeatId": 1,
                 "cardTypes": ["CardType_Land"], "name": 1,
                 "isTapped": True},
            ],
        })
        acc.apply(full)
        assert acc.current.objects[300].is_tapped is True

        diff = GameState.from_raw({
            "type": "GameStateType_Diff",
            "gameStateId": 2,
            "gameObjects": [
                {"instanceId": 300, "isTapped": False},
            ],
        })
        acc.apply(diff)
        assert acc.current.objects[300].is_tapped is False
        # grp_id preserved from base
        assert acc.current.objects[300].grp_id == 72032

    def test_tap_via_diff(self):
        """Confirm tapping also works."""
        acc = Accumulator()
        acc.apply(GameState.from_raw({
            "type": "GameStateType_Full",
            "gameStateId": 1,
            "gameObjects": [
                {"instanceId": 300, "grpId": 72032,
                 "type": "GameObjectType_Card", "zoneId": 28,
                 "ownerSeatId": 1, "controllerSeatId": 1,
                 "cardTypes": ["CardType_Land"], "name": 1},
            ],
        }))
        assert acc.current.objects[300].is_tapped is False

        acc.apply(GameState.from_raw({
            "type": "GameStateType_Diff",
            "gameStateId": 2,
            "gameObjects": [
                {"instanceId": 300, "isTapped": True},
            ],
        }))
        assert acc.current.objects[300].is_tapped is True


# ---------------------------------------------------------------------------
# Diff with no base
# ---------------------------------------------------------------------------

class TestDiffNoBase:
    def test_diff_with_no_base_treated_as_full(self):
        acc = Accumulator()
        diff = _diff_state(
            gameObjects=[
                {"instanceId": 500, "grpId": 1, "type": "GameObjectType_Card",
                 "zoneId": 28, "ownerSeatId": 1, "controllerSeatId": 1,
                 "cardTypes": ["CardType_Land"], "name": 1},
            ],
        )
        acc.apply(diff)
        assert acc.current is not None
        assert 500 in acc.current.objects


# ---------------------------------------------------------------------------
# History
# ---------------------------------------------------------------------------

class TestHistory:
    def test_stores_states(self):
        acc = Accumulator()
        acc.apply(_full_state(gameStateId=1))
        acc.apply(_diff_state(gameStateId=2))
        assert acc.get_state(1) is not None
        assert acc.get_state(2) is not None
        assert acc.get_state(99) is None

    def test_evicts_oldest_beyond_limit(self):
        acc = Accumulator()
        acc.apply(_full_state(gameStateId=1))
        for i in range(2, 11):
            acc.apply(_diff_state(gameStateId=i))
        # 10 states applied, limit is 8 → oldest 2 evicted
        assert acc.get_state(1) is None
        assert acc.get_state(2) is None
        assert acc.get_state(3) is not None
        assert acc.get_state(10) is not None

    def test_history_is_independent_copy(self):
        acc = Accumulator()
        acc.apply(_full_state(gameStateId=1))
        hist = acc.get_state(1)
        hist.game_state_id = 999
        assert acc.get_state(1).game_state_id == 1


# ---------------------------------------------------------------------------
# Reset
# ---------------------------------------------------------------------------

class TestReset:
    def test_reset_clears_everything(self):
        acc = Accumulator()
        acc.apply(_full_state(gameStateId=1))
        acc.reset()
        assert acc.current is None
        assert acc.get_state(1) is None


# ---------------------------------------------------------------------------
# Integration: real fixture data
# ---------------------------------------------------------------------------

class TestRealData:
    def test_game_start_plus_early_turns(self):
        """Process game_start.jsonl + early_turns.jsonl through parser + accumulator."""
        acc = Accumulator()
        gs_count = 0

        for fixture in ["game_start.jsonl", "early_turns.jsonl"]:
            with open(FIXTURES / fixture) as f:
                lines = f.readlines()
            for block in parse_gre_blocks(lines):
                for msg in block.game_state_messages():
                    gsm = msg.get("gameStateMessage", {})
                    if not gsm:
                        continue
                    gs = GameState.from_raw(gsm)
                    acc.apply(gs)
                    gs_count += 1

        assert gs_count > 0, "Should have processed at least one game state"
        assert acc.current is not None
        assert acc.current.game_state_id > 0

        # Should have players after accumulation
        assert len(acc.current.players) >= 2

        # Should have zones
        assert len(acc.current.zones) > 0

        # History should have entries (capped at 8)
        history_count = sum(1 for i in range(100) if acc.get_state(i) is not None)
        assert 0 < history_count <= 8


# ---------------------------------------------------------------------------
# Persistent annotation tracking
# ---------------------------------------------------------------------------

class TestPersistentAnnotations:
    def test_full_state_captures_persistent(self):
        acc = Accumulator()
        acc.apply(_full_state(
            gameStateId=1,
            annotations=[
                {"id": 10, "type": ["AnnotationType_Counter"], "affectedIds": [100]},
                {"id": 11, "type": ["AnnotationType_ZoneTransfer"], "affectedIds": [200]},
            ],
        ))
        # Counter is persistent, ZoneTransfer is not
        assert 10 in acc.persistent_annotations
        assert 11 not in acc.persistent_annotations

    def test_diff_adds_new_persistent(self):
        acc = Accumulator()
        acc.apply(_full_state(gameStateId=1))
        acc.apply(_diff_state(
            gameStateId=2,
            annotations=[
                {"id": 20, "type": ["AnnotationType_LayeredEffect"], "affectedIds": [300],
                 "details": [{"key": "effect_id", "type": "KeyValuePairValueType_int32", "valueInt32": [7007]}]},
            ],
        ))
        assert 20 in acc.persistent_annotations
        a = acc.persistent_annotations[20]
        assert a.details.get("effect_id") == 7007

    def test_diff_deleted_removes_persistent(self):
        acc = Accumulator()
        acc.apply(_full_state(
            gameStateId=1,
            annotations=[
                {"id": 10, "type": ["AnnotationType_Counter"], "affectedIds": [100]},
            ],
        ))
        assert 10 in acc.persistent_annotations

        # Diff deletes the persistent annotation
        diff_raw = {
            "type": "GameStateType_Diff",
            "gameStateId": 2,
            "prevGameStateId": 1,
            "diffDeletedPersistentAnnotationIds": [10],
        }
        acc.apply(GameState.from_raw(diff_raw))
        assert 10 not in acc.persistent_annotations

    def test_reset_clears_persistent(self):
        acc = Accumulator()
        acc.apply(_full_state(
            gameStateId=1,
            annotations=[
                {"id": 10, "type": ["AnnotationType_Counter"], "affectedIds": [100]},
            ],
        ))
        acc.reset()
        assert len(acc.persistent_annotations) == 0

    def test_full_state_resets_persistent(self):
        acc = Accumulator()
        acc.apply(_full_state(
            gameStateId=1,
            annotations=[
                {"id": 10, "type": ["AnnotationType_Counter"], "affectedIds": [100]},
            ],
        ))
        # New Full state — persistent set rebuilds from scratch
        acc.apply(_full_state(
            gameStateId=5,
            annotations=[
                {"id": 20, "type": ["AnnotationType_LayeredEffect"], "affectedIds": [200]},
            ],
        ))
        assert 10 not in acc.persistent_annotations
        assert 20 in acc.persistent_annotations
