from __future__ import annotations

from collections import deque
from dataclasses import dataclass, field

from .accumulator import Accumulator
from .annotations import Annotation
from .errors import ClientError
from .models import GameState, SceneChange
from .parser import GREBlock


@dataclass
class CompletedGame:
    match_id: str
    final_state: GameState | None = None


class GameTracker:
    """Sits above Accumulator — handles multi-game sessions."""

    def __init__(
        self, max_history: int = 5, max_errors: int = 200, max_scenes: int = 50
    ) -> None:
        self._max_history = max_history
        self._accumulator = Accumulator()
        self.current_match_id: str | None = None
        self.completed_games: list[CompletedGame] = []
        self.errors: deque[ClientError] = deque(maxlen=max_errors)
        self.error_count: int = 0
        self.current_scene: str | None = None
        self.scene_history: deque[SceneChange] = deque(maxlen=max_scenes)

    @property
    def current_state(self) -> GameState | None:
        return self._accumulator.current

    def feed(self, block: GREBlock) -> None:
        has_connect_resp = any(
            m.get("type") == "GREMessageType_ConnectResp" for m in block.messages
        )

        if has_connect_resp and self.current_match_id is not None:
            self._archive_current()

        # Synthesize scene from GRE events — ConnectResp = match started
        if has_connect_resp:
            self.current_scene = "InGame"

        # Extract real match ID from gameInfo inside GSMs (header match_id
        # is the session/connection ID, not the actual match ID).
        for msg in block.messages:
            gi = msg.get("gameStateMessage", {}).get("gameInfo", {})
            mid = gi.get("matchID")
            if mid:
                self.current_match_id = mid
                break
        else:
            # Fallback to header match_id if no gameInfo found
            if block.match_id is not None and self.current_match_id is None:
                self.current_match_id = block.match_id

        for msg in block.game_state_messages():
            gsm = msg.get("gameStateMessage", {})
            if not gsm:
                continue
            gs = GameState.from_raw(gsm)
            self._accumulator.apply(gs)

            # Synthesize PostGame scene
            if gs.game_over:
                self.current_scene = "PostGame"

    def feed_error(self, error: ClientError) -> None:
        self.errors.append(error)
        self.error_count += 1

    def feed_scene(self, scene: SceneChange) -> None:
        self.current_scene = scene.to_scene
        self.scene_history.append(scene)

    def _archive_current(self) -> None:
        self.completed_games.append(
            CompletedGame(
                match_id=self.current_match_id or "",
                final_state=self._accumulator.current,
            ),
        )
        if len(self.completed_games) > self._max_history:
            self.completed_games = self.completed_games[-self._max_history :]
        self._accumulator.reset()
        self.current_match_id = None

    def to_dict(self, card_resolver=None) -> dict:
        state = self.current_state
        if state is None:
            return {
                "match_id": self.current_match_id,
                "state": None,
                "scene": self._scene_dict(),
                "error_count": self.error_count,
                "recent_errors": self._errors_list(),
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

        players = [{"seat": p.seat_number, "life": p.life_total} for p in state.players]

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

        # Build hand summary for our seat (seat 1).
        # NOTE: zone order ≠ visual order. Arena sorts cards visually by mana cost
        # (lands first, then by CMC). We can't predict screen positions from zone order.
        hand = []
        for z in state.zones:
            if z.type == "ZoneType_Hand" and z.owner_seat_id == 1:
                for iid in z.object_ids:
                    obj = state.objects.get(iid)
                    card_name = None
                    if obj and obj.grp_id:
                        card_name = names.get(obj.grp_id)
                    entry: dict = {
                        "id": iid,
                        "name": card_name or f"?({iid})",
                    }
                    hand.append(entry)
                break

        # Annotations from latest GSM
        annotations = [Annotation.from_raw(a).to_dict() for a in state.annotations]

        # Persistent annotations (active set across GSMs)
        persistent = [
            a.to_dict() for a in self._accumulator.persistent_annotations.values()
        ]

        # Available actions from latest GSM (what the player can do right now)
        # Raw format: {"seatId": 1, "action": {"actionType": "...", "instanceId": N, "manaCost": [...]}}
        actions = []
        for a in state.actions:
            inner = a.get("action", a)  # unwrap nested action, fallback to flat
            action_type = inner.get("actionType", a.get("actionType", "?"))
            iid = inner.get("instanceId", a.get("instanceId"))
            grp = inner.get("grpId", a.get("grpId"))
            seat = a.get("seatId")

            entry: dict = {"actionType": action_type}
            if iid is not None:
                entry["instanceId"] = iid
                # Resolve card name from objects
                obj = state.objects.get(iid)
                if obj and obj.grp_id and names.get(obj.grp_id):
                    entry["name"] = names[obj.grp_id]
                elif grp and names.get(grp):
                    entry["name"] = names[grp]
            if grp is not None:
                entry["grpId"] = grp
            if seat is not None:
                entry["seatId"] = seat
            # Include mana cost if present (helps agent know what's affordable)
            mana = inner.get("manaCost")
            if mana:
                entry["manaCost"] = mana
            actions.append(entry)

        return {
            "match_id": self.current_match_id,
            "game_state_id": state.game_state_id,
            "turn_info": turn_info,
            "players": players,
            "hand": hand,
            "zones": zones,
            "object_count": len(state.objects),
            "actions": actions,
            "annotations": annotations,
            "persistent_annotations": persistent,
            "completed_games": len(self.completed_games),
            "scene": self._scene_dict(),
            "error_count": self.error_count,
            "recent_errors": self._errors_list(),
        }

    def _scene_dict(self) -> dict:
        return {
            "current": self.current_scene,
            "history": [s.to_dict() for s in self.scene_history],
        }

    def _errors_list(self, limit: int = 10) -> list[dict]:
        recent = list(self.errors)[-limit:]
        return [
            {
                "type": e.exception_type,
                "message": e.message,
            }
            for e in recent
        ]
