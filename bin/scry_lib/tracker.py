from __future__ import annotations

from dataclasses import dataclass, field

from scry_lib.accumulator import Accumulator
from scry_lib.models import GameState
from scry_lib.parser import GREBlock


@dataclass
class CompletedGame:
    match_id: str
    final_state: GameState | None = None


class GameTracker:
    """Sits above Accumulator — handles multi-game sessions."""

    def __init__(self, max_history: int = 5) -> None:
        self._max_history = max_history
        self._accumulator = Accumulator()
        self.current_match_id: str | None = None
        self.completed_games: list[CompletedGame] = []

    @property
    def current_state(self) -> GameState | None:
        return self._accumulator.current

    def feed(self, block: GREBlock) -> None:
        has_connect_resp = any(
            m.get("type") == "GREMessageType_ConnectResp"
            for m in block.messages
        )

        if has_connect_resp and self.current_match_id is not None:
            self._archive_current()

        if block.match_id is not None:
            self.current_match_id = block.match_id

        for msg in block.game_state_messages():
            gsm = msg.get("gameStateMessage", {})
            if not gsm:
                continue
            gs = GameState.from_raw(gsm)
            self._accumulator.apply(gs)

    def _archive_current(self) -> None:
        self.completed_games.append(
            CompletedGame(
                match_id=self.current_match_id or "",
                final_state=self._accumulator.current,
            ),
        )
        if len(self.completed_games) > self._max_history:
            self.completed_games = self.completed_games[-self._max_history:]
        self._accumulator.reset()
        self.current_match_id = None

    def to_dict(self, card_resolver=None) -> dict:
        state = self.current_state
        if state is None:
            return {
                "match_id": self.current_match_id,
                "state": None,
            }

        # Resolve card names if resolver provided
        names: dict[int, str] = {}
        if card_resolver is not None:
            grp_ids = [obj.grp_id for obj in state.objects.values() if obj.grp_id]
            names = card_resolver.resolve_batch(grp_ids)

        turn_info = None
        if state.turn_info is not None:
            ti = state.turn_info
            turn_info = {
                "turn_number": ti.turn_number,
                "phase": ti.phase,
                "step": ti.step,
                "active_player": ti.active_player,
                "priority_player": ti.priority_player,
            }

        players = [
            {"seat": p.seat_number, "life": p.life_total}
            for p in state.players
        ]

        # Build object lookup for zone enrichment
        def _obj_summary(iid: int) -> dict | int:
            obj = state.objects.get(iid)
            if obj is None or not names:
                return iid
            name = names.get(obj.grp_id)
            if name is None:
                return iid
            entry: dict = {"id": iid, "name": name}
            if obj.power is not None:
                entry["p/t"] = f"{obj.power}/{obj.toughness}"
            if obj.is_tapped:
                entry["tapped"] = True
            return entry

        zones = [
            {
                "zone_id": z.zone_id,
                "type": z.type,
                "owner": z.owner_seat_id,
                "objects": [_obj_summary(iid) for iid in z.object_ids],
            }
            for z in state.zones
            if z.object_ids
        ]

        return {
            "match_id": self.current_match_id,
            "game_state_id": state.game_state_id,
            "turn_info": turn_info,
            "players": players,
            "zones": zones,
            "object_count": len(state.objects),
            "completed_games": len(self.completed_games),
        }
