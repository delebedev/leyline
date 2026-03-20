"""Arena screen graph helpers."""

from __future__ import annotations

from collections import deque

from .screens_data import POPUPS, SCREENS, TRANSITIONS


def _build_adjacency() -> dict[str, list[dict]]:
    adj: dict[str, list[dict]] = {}
    for transition in TRANSITIONS:
        adj.setdefault(transition["from"], []).append(transition)
    return adj


_ADJ = _build_adjacency()


def find_path(src: str, dst: str) -> list[dict] | None:
    """BFS shortest path from src to dst. Returns list of transitions, or None."""
    if src == dst:
        return []

    queue: deque[tuple[str, list[dict]]] = deque([(src, [])])
    visited = {src}
    while queue:
        node, path = queue.popleft()
        for transition in _ADJ.get(node, []):
            target = transition["to"]
            if target == dst:
                return path + [transition]
            if target not in visited:
                visited.add(target)
                queue.append((target, path + [transition]))
    return None
