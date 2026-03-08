from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class TurnInfo:
    turn_number: int | None = None
    phase: str | None = None
    step: str | None = None
    active_player: int | None = None
    priority_player: int | None = None
    decision_player: int | None = None

    @classmethod
    def from_raw(cls, raw: dict) -> TurnInfo:
        return cls(
            turn_number=raw.get("turnNumber"),
            phase=raw.get("phase"),
            step=raw.get("step"),
            active_player=raw.get("activePlayer"),
            priority_player=raw.get("priorityPlayer"),
            decision_player=raw.get("decisionPlayer"),
        )


@dataclass
class Player:
    seat_number: int = 0
    life_total: int = 0
    max_hand_size: int = 0
    team_id: int = 0
    controller_seat_id: int = 0
    starting_life_total: int = 0
    status: str | None = None
    pending_message_type: str | None = None

    @classmethod
    def from_raw(cls, raw: dict) -> Player:
        return cls(
            seat_number=raw.get("systemSeatNumber", 0),
            life_total=raw.get("lifeTotal", 0),
            max_hand_size=raw.get("maxHandSize", 0),
            team_id=raw.get("teamId", 0),
            controller_seat_id=raw.get("controllerSeatId", 0),
            starting_life_total=raw.get("startingLifeTotal", 0),
            status=raw.get("status"),
            pending_message_type=raw.get("pendingMessageType"),
        )


@dataclass
class Zone:
    zone_id: int = 0
    type: str = ""
    visibility: str = ""
    owner_seat_id: int | None = None
    object_ids: list[int] = field(default_factory=list)

    @classmethod
    def from_raw(cls, raw: dict) -> Zone:
        return cls(
            zone_id=raw.get("zoneId", 0),
            type=raw.get("type", ""),
            visibility=raw.get("visibility", ""),
            owner_seat_id=raw.get("ownerSeatId"),
            object_ids=list(raw.get("objectInstanceIds", [])),
        )


@dataclass
class GameObject:
    instance_id: int = 0
    grp_id: int = 0
    type: str = ""
    zone_id: int = 0
    owner_seat_id: int = 0
    controller_seat_id: int = 0
    card_types: list[str] = field(default_factory=list)
    subtypes: list[str] = field(default_factory=list)
    colors: list[str] = field(default_factory=list)
    power: int | None = None
    toughness: int | None = None
    is_tapped: bool = False
    name: int = 0

    @classmethod
    def from_raw(cls, raw: dict) -> GameObject:
        power_raw = raw.get("power")
        toughness_raw = raw.get("toughness")
        return cls(
            instance_id=raw.get("instanceId", 0),
            grp_id=raw.get("grpId", 0),
            type=raw.get("type", ""),
            zone_id=raw.get("zoneId", 0),
            owner_seat_id=raw.get("ownerSeatId", 0),
            controller_seat_id=raw.get("controllerSeatId", 0),
            card_types=list(raw.get("cardTypes", [])),
            subtypes=list(raw.get("subtypes", [])),
            colors=list(raw.get("color", [])),
            power=power_raw["value"] if power_raw else None,
            toughness=toughness_raw["value"] if toughness_raw else None,
            is_tapped=raw.get("isTapped", False),
            name=raw.get("name", 0),
        )


@dataclass
class GameState:
    game_state_id: int = 0
    type: str = ""
    prev_game_state_id: int | None = None
    turn_info: TurnInfo | None = None
    players: list[Player] = field(default_factory=list)
    zones: list[Zone] = field(default_factory=list)
    objects: dict[int, GameObject] = field(default_factory=dict)
    annotations: list[dict] = field(default_factory=list)
    actions: list[dict] = field(default_factory=list)
    game_info: dict | None = None
    diff_deleted_instance_ids: list[int] = field(default_factory=list)

    @classmethod
    def from_raw(cls, raw: dict) -> GameState:
        turn_raw = raw.get("turnInfo")
        objects: dict[int, GameObject] = {}
        for obj_raw in raw.get("gameObjects", []):
            go = GameObject.from_raw(obj_raw)
            objects[go.instance_id] = go

        return cls(
            game_state_id=raw.get("gameStateId", 0),
            type=raw.get("type", ""),
            prev_game_state_id=raw.get("prevGameStateId"),
            turn_info=TurnInfo.from_raw(turn_raw) if turn_raw else None,
            players=[Player.from_raw(p) for p in raw.get("players", [])],
            zones=[Zone.from_raw(z) for z in raw.get("zones", [])],
            objects=objects,
            annotations=list(raw.get("annotations", [])),
            actions=list(raw.get("actions", [])),
            game_info=raw.get("gameInfo"),
            diff_deleted_instance_ids=list(raw.get("diffDeletedInstanceIds", [])),
        )

    @property
    def is_full(self) -> bool:
        return self.type == "GameStateType_Full"

    @property
    def is_diff(self) -> bool:
        return self.type == "GameStateType_Diff"

    def zones_by_type(self, zone_type: str) -> list[Zone]:
        return [z for z in self.zones if z.type == zone_type]
