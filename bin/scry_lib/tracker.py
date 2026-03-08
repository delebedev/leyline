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

    def to_dict(self) -> dict:
        state = self.current_state
        if state is None:
            return {
                "match_id": self.current_match_id,
                "state": None,
            }

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

        zones: dict[str, dict] = {}
        for z in state.zones:
            if z.object_ids:
                zones[z.type] = {
                    "zone_id": z.zone_id,
                    "owner": z.owner_seat_id,
                    "objects": list(z.object_ids),
                }

        return {
            "match_id": self.current_match_id,
            "game_state_id": state.game_state_id,
            "turn_info": turn_info,
            "players": players,
            "zones": zones,
            "object_count": len(state.objects),
            "completed_games": len(self.completed_games),
        }
