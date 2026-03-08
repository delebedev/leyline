from __future__ import annotations

import pytest
from scry_lib.models import TurnInfo, Player, Zone, GameObject, GameState


# ---------------------------------------------------------------------------
# TurnInfo
# ---------------------------------------------------------------------------

class TestTurnInfo:
    def test_from_raw_full(self):
        raw = {
            "turnNumber": 1,
            "phase": "Phase_Beginning",
            "step": "Step_Upkeep",
            "activePlayer": 2,
            "priorityPlayer": 1,
            "decisionPlayer": 1,
        }
        ti = TurnInfo.from_raw(raw)
        assert ti.turn_number == 1
        assert ti.phase == "Phase_Beginning"
        assert ti.step == "Step_Upkeep"
        assert ti.active_player == 2
        assert ti.priority_player == 1
        assert ti.decision_player == 1

    def test_from_raw_minimal(self):
        """Early game states may only have decisionPlayer."""
        raw = {"decisionPlayer": 2}
        ti = TurnInfo.from_raw(raw)
        assert ti.decision_player == 2
        assert ti.turn_number is None
        assert ti.phase is None
        assert ti.step is None
        assert ti.active_player is None
        assert ti.priority_player is None

    def test_from_raw_empty(self):
        ti = TurnInfo.from_raw({})
        assert ti.turn_number is None
        assert ti.phase is None


# ---------------------------------------------------------------------------
# Player
# ---------------------------------------------------------------------------

class TestPlayer:
    def test_from_raw(self):
        raw = {
            "systemSeatNumber": 1,
            "lifeTotal": 20,
            "maxHandSize": 7,
            "teamId": 1,
            "controllerSeatId": 1,
            "startingLifeTotal": 20,
            "status": "PlayerStatus_InGame",
        }
        p = Player.from_raw(raw)
        assert p.seat_number == 1
        assert p.life_total == 20
        assert p.max_hand_size == 7
        assert p.team_id == 1
        assert p.controller_seat_id == 1
        assert p.starting_life_total == 20
        assert p.status == "PlayerStatus_InGame"
        assert p.pending_message_type is None

    def test_from_raw_with_pending(self):
        raw = {
            "systemSeatNumber": 2,
            "lifeTotal": 20,
            "maxHandSize": 7,
            "teamId": 2,
            "controllerSeatId": 2,
            "startingLifeTotal": 20,
            "status": "PlayerStatus_InGame",
            "pendingMessageType": "ClientMessageType_MulliganResp",
        }
        p = Player.from_raw(raw)
        assert p.seat_number == 2
        assert p.pending_message_type == "ClientMessageType_MulliganResp"


# ---------------------------------------------------------------------------
# Zone
# ---------------------------------------------------------------------------

class TestZone:
    def test_from_raw_with_objects(self):
        raw = {
            "zoneId": 32,
            "type": "ZoneType_Library",
            "visibility": "Visibility_Hidden",
            "ownerSeatId": 1,
            "objectInstanceIds": [100, 101, 102],
        }
        z = Zone.from_raw(raw)
        assert z.zone_id == 32
        assert z.type == "ZoneType_Library"
        assert z.visibility == "Visibility_Hidden"
        assert z.owner_seat_id == 1
        assert z.object_ids == [100, 101, 102]

    def test_from_raw_empty_zone(self):
        raw = {
            "zoneId": 28,
            "type": "ZoneType_Battlefield",
            "visibility": "Visibility_Public",
        }
        z = Zone.from_raw(raw)
        assert z.zone_id == 28
        assert z.owner_seat_id is None
        assert z.object_ids == []

    def test_object_ids_default_not_shared(self):
        """Mutable default must not be shared across instances."""
        z1 = Zone.from_raw({"zoneId": 1, "type": "t", "visibility": "v"})
        z2 = Zone.from_raw({"zoneId": 2, "type": "t", "visibility": "v"})
        z1.object_ids.append(999)
        assert 999 not in z2.object_ids


# ---------------------------------------------------------------------------
# GameObject
# ---------------------------------------------------------------------------

class TestGameObject:
    def test_creature_from_raw(self):
        raw = {
            "instanceId": 152,
            "grpId": 93636,
            "type": "GameObjectType_Card",
            "zoneId": 31,
            "ownerSeatId": 1,
            "controllerSeatId": 1,
            "cardTypes": ["CardType_Creature"],
            "subtypes": ["SubType_Rabbit", "SubType_Cleric"],
            "color": ["CardColor_White"],
            "power": {"value": 1},
            "toughness": {"value": 2},
            "name": 823098,
        }
        obj = GameObject.from_raw(raw)
        assert obj.instance_id == 152
        assert obj.grp_id == 93636
        assert obj.type == "GameObjectType_Card"
        assert obj.zone_id == 31
        assert obj.owner_seat_id == 1
        assert obj.controller_seat_id == 1
        assert obj.card_types == ["CardType_Creature"]
        assert obj.subtypes == ["SubType_Rabbit", "SubType_Cleric"]
        assert obj.colors == ["CardColor_White"]
        assert obj.power == 1
        assert obj.toughness == 2
        assert obj.name == 823098
        assert obj.is_tapped is False

    def test_land_from_raw(self):
        raw = {
            "instanceId": 151,
            "grpId": 72032,
            "type": "GameObjectType_Card",
            "zoneId": 31,
            "ownerSeatId": 1,
            "controllerSeatId": 1,
            "cardTypes": ["CardType_Land"],
            "name": 46190,
        }
        obj = GameObject.from_raw(raw)
        assert obj.instance_id == 151
        assert obj.power is None
        assert obj.toughness is None
        assert obj.subtypes == []
        assert obj.colors == []
        assert obj.is_tapped is False

    def test_tapped_object(self):
        raw = {
            "instanceId": 200,
            "grpId": 1,
            "type": "GameObjectType_Card",
            "zoneId": 28,
            "ownerSeatId": 1,
            "controllerSeatId": 1,
            "cardTypes": ["CardType_Land"],
            "isTapped": True,
            "name": 1,
        }
        obj = GameObject.from_raw(raw)
        assert obj.is_tapped is True

    def test_power_toughness_none_when_missing(self):
        raw = {
            "instanceId": 1,
            "grpId": 1,
            "type": "GameObjectType_Card",
            "zoneId": 1,
            "ownerSeatId": 1,
            "controllerSeatId": 1,
            "cardTypes": ["CardType_Instant"],
            "name": 1,
        }
        obj = GameObject.from_raw(raw)
        assert obj.power is None
        assert obj.toughness is None

    def test_mutable_defaults_not_shared(self):
        raw = {
            "instanceId": 1,
            "grpId": 1,
            "type": "t",
            "zoneId": 1,
            "ownerSeatId": 1,
            "controllerSeatId": 1,
            "cardTypes": [],
            "name": 1,
        }
        a = GameObject.from_raw(raw)
        b = GameObject.from_raw(raw)
        a.card_types.append("x")
        assert "x" not in b.card_types


# ---------------------------------------------------------------------------
# GameState
# ---------------------------------------------------------------------------

class TestGameState:
    @pytest.fixture()
    def full_state_raw(self) -> dict:
        return {
            "type": "GameStateType_Full",
            "gameStateId": 1,
            "gameInfo": {
                "matchID": "test-match",
                "gameNumber": 1,
                "stage": "GameStage_Start",
            },
            "players": [
                {
                    "systemSeatNumber": 1,
                    "lifeTotal": 20,
                    "maxHandSize": 7,
                    "teamId": 1,
                    "controllerSeatId": 1,
                    "startingLifeTotal": 20,
                    "status": "PlayerStatus_InGame",
                },
                {
                    "systemSeatNumber": 2,
                    "lifeTotal": 20,
                    "maxHandSize": 7,
                    "teamId": 2,
                    "controllerSeatId": 2,
                    "startingLifeTotal": 20,
                    "status": "PlayerStatus_InGame",
                },
            ],
            "turnInfo": {"decisionPlayer": 2},
            "zones": [
                {
                    "zoneId": 28,
                    "type": "ZoneType_Battlefield",
                    "visibility": "Visibility_Public",
                },
                {
                    "zoneId": 31,
                    "type": "ZoneType_Hand",
                    "visibility": "Visibility_Private",
                    "ownerSeatId": 1,
                    "objectInstanceIds": [151, 152],
                },
                {
                    "zoneId": 35,
                    "type": "ZoneType_Hand",
                    "visibility": "Visibility_Private",
                    "ownerSeatId": 2,
                },
            ],
            "gameObjects": [
                {
                    "instanceId": 151,
                    "grpId": 72032,
                    "type": "GameObjectType_Card",
                    "zoneId": 31,
                    "ownerSeatId": 1,
                    "controllerSeatId": 1,
                    "cardTypes": ["CardType_Land"],
                    "name": 46190,
                },
                {
                    "instanceId": 152,
                    "grpId": 93636,
                    "type": "GameObjectType_Card",
                    "zoneId": 31,
                    "ownerSeatId": 1,
                    "controllerSeatId": 1,
                    "cardTypes": ["CardType_Creature"],
                    "color": ["CardColor_White"],
                    "power": {"value": 1},
                    "toughness": {"value": 2},
                    "name": 823098,
                },
            ],
            "annotations": [
                {"id": 49, "affectorId": 2, "type": ["AnnotationType_NewTurnStarted"]},
            ],
            "actions": [
                {"seatId": 1, "action": {"actionType": "ActionType_Pass"}},
            ],
        }

    def test_full_state_properties(self, full_state_raw: dict):
        gs = GameState.from_raw(full_state_raw)
        assert gs.game_state_id == 1
        assert gs.type == "GameStateType_Full"
        assert gs.is_full is True
        assert gs.is_diff is False
        assert gs.prev_game_state_id is None

    def test_players_parsed(self, full_state_raw: dict):
        gs = GameState.from_raw(full_state_raw)
        assert len(gs.players) == 2
        assert gs.players[0].seat_number == 1
        assert gs.players[1].seat_number == 2

    def test_turn_info_parsed(self, full_state_raw: dict):
        gs = GameState.from_raw(full_state_raw)
        assert gs.turn_info is not None
        assert gs.turn_info.decision_player == 2

    def test_zones_parsed(self, full_state_raw: dict):
        gs = GameState.from_raw(full_state_raw)
        assert len(gs.zones) == 3

    def test_objects_dict_keyed_by_instance_id(self, full_state_raw: dict):
        gs = GameState.from_raw(full_state_raw)
        assert 151 in gs.objects
        assert 152 in gs.objects
        assert gs.objects[151].grp_id == 72032
        assert gs.objects[152].power == 1

    def test_annotations_actions_passthrough(self, full_state_raw: dict):
        gs = GameState.from_raw(full_state_raw)
        assert len(gs.annotations) == 1
        assert gs.annotations[0]["id"] == 49
        assert len(gs.actions) == 1

    def test_game_info(self, full_state_raw: dict):
        gs = GameState.from_raw(full_state_raw)
        assert gs.game_info is not None
        assert gs.game_info["matchID"] == "test-match"

    def test_zones_by_type(self, full_state_raw: dict):
        gs = GameState.from_raw(full_state_raw)
        hands = gs.zones_by_type("ZoneType_Hand")
        assert len(hands) == 2
        bfs = gs.zones_by_type("ZoneType_Battlefield")
        assert len(bfs) == 1
        assert gs.zones_by_type("ZoneType_Graveyard") == []

    def test_diff_state(self):
        raw = {
            "type": "GameStateType_Diff",
            "gameStateId": 7,
            "prevGameStateId": 6,
            "turnInfo": {
                "phase": "Phase_Beginning",
                "step": "Step_Upkeep",
                "turnNumber": 1,
                "activePlayer": 2,
                "priorityPlayer": 2,
                "decisionPlayer": 2,
            },
            "diffDeletedInstanceIds": [100, 101],
        }
        gs = GameState.from_raw(raw)
        assert gs.is_diff is True
        assert gs.is_full is False
        assert gs.prev_game_state_id == 6
        assert gs.diff_deleted_instance_ids == [100, 101]
        assert gs.players == []
        assert gs.zones == []
        assert gs.objects == {}
        assert gs.annotations == []
        assert gs.actions == []
        assert gs.game_info is None

    def test_diff_deleted_defaults_empty(self, full_state_raw: dict):
        gs = GameState.from_raw(full_state_raw)
        assert gs.diff_deleted_instance_ids == []

    def test_no_turn_info(self):
        raw = {
            "type": "GameStateType_Diff",
            "gameStateId": 99,
        }
        gs = GameState.from_raw(raw)
        assert gs.turn_info is None
