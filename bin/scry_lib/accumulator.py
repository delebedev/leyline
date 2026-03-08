from __future__ import annotations

import copy
from collections import OrderedDict
from dataclasses import dataclass, field

from scry_lib.annotations import Annotation
from scry_lib.models import GameState, GameObject, Player, TurnInfo, Zone

_MAX_HISTORY = 8


@dataclass
class Accumulator:
    """Maintains current game state by applying Full and Diff updates."""

    current: GameState | None = None
    persistent_annotations: dict[int, Annotation] = field(default_factory=dict)
    _history: OrderedDict[int, GameState] = field(
        default_factory=OrderedDict, repr=False,
    )

    def apply(self, state: GameState) -> None:
        """Apply a Full or Diff state update."""
        if state.is_full or self.current is None:
            merged = copy.deepcopy(state)
            self.persistent_annotations.clear()
        else:
            merged = self._merge_diff(self.current, state)
            # Remove deleted persistent annotations
            for aid in state.diff_deleted_annotation_ids:
                self.persistent_annotations.pop(aid, None)

        # Add new persistent annotations from this GSM
        for raw_annot in state.annotations:
            a = Annotation.from_raw(raw_annot)
            if a.is_persistent and a.id is not None:
                self.persistent_annotations[a.id] = a

        self.current = merged
        self._record(merged)

    def reset(self) -> None:
        self.current = None
        self.persistent_annotations.clear()
        self._history.clear()

    def get_state(self, gsid: int) -> GameState | None:
        st = self._history.get(gsid)
        return copy.deepcopy(st) if st is not None else None

    # ------------------------------------------------------------------
    # internals
    # ------------------------------------------------------------------

    def _record(self, state: GameState) -> None:
        gsid = state.game_state_id
        if gsid in self._history:
            self._history.move_to_end(gsid)
        else:
            self._history[gsid] = copy.deepcopy(state)
            if len(self._history) > _MAX_HISTORY:
                self._history.popitem(last=False)

    @staticmethod
    def _merge_diff(base: GameState, diff: GameState) -> GameState:
        # turn_info
        turn_info = diff.turn_info if diff.turn_info is not None else (
            copy.deepcopy(base.turn_info)
        )

        # players — merge by seat_number, diff replaces entire player
        player_map: dict[int, Player] = {
            p.seat_number: copy.deepcopy(p) for p in base.players
        }
        for p in diff.players:
            player_map[p.seat_number] = copy.deepcopy(p)
        players = list(player_map.values())

        # zones — merge by zone_id, diff replaces entire zone
        zone_map: dict[int, Zone] = {
            z.zone_id: copy.deepcopy(z) for z in base.zones
        }
        for z in diff.zones:
            zone_map[z.zone_id] = copy.deepcopy(z)
        zones = list(zone_map.values())

        # objects — raw-dict merge for correct is_tapped handling
        objects: dict[int, GameObject] = {}
        for iid, obj in base.objects.items():
            objects[iid] = copy.deepcopy(obj)
        for iid, diff_obj in diff.objects.items():
            if iid in objects:
                # merge raw dicts: base keys + diff keys (diff wins)
                merged_raw = dict(objects[iid]._raw)
                merged_raw.update(diff_obj._raw)
                objects[iid] = GameObject.from_raw(merged_raw)
            else:
                objects[iid] = copy.deepcopy(diff_obj)

        # deletions
        for iid in diff.diff_deleted_instance_ids:
            objects.pop(iid, None)

        # annotations + actions: NOT cumulative, use diff's values
        annotations = list(diff.annotations)
        actions = list(diff.actions)

        # game_info
        game_info = (
            diff.game_info if diff.game_info is not None
            else copy.deepcopy(base.game_info)
        )

        return GameState(
            game_state_id=diff.game_state_id,
            type=diff.type,
            prev_game_state_id=diff.prev_game_state_id,
            turn_info=turn_info,
            players=players,
            zones=zones,
            objects=objects,
            annotations=annotations,
            actions=actions,
            game_info=game_info,
            diff_deleted_instance_ids=[],
        )
