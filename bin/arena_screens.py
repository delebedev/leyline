"""Arena screen state machine — graph definition.

Mirrors docs/arena-screens.yaml as Python data.
Used by arena.py for screen detection and navigation.
"""

from __future__ import annotations


# --- Screen definitions ---
# Each screen: detection signals (scene from Player.log, OCR anchors)

SCREENS: dict[str, dict] = {
    # --- Lobby ---
    "Home": {
        "scene": "Home",
        "ocr_anchors": ["Play"],
        "ocr_reject": ["Keep", "Concede", "Find Match", "Bot Match"],
    },
    "Play": {
        "scene": "Home",
        "ocr_anchors": ["Find Match"],
        "ocr_reject": ["Keep", "Bot Match", "Edit Deck"],
    },
    "FindMatch": {
        "scene": "Home",
        "ocr_anchors": ["Bot Match"],
        "ocr_reject": ["Keep", "Edit Deck"],
    },
    "DeckSelected": {
        "scene": "Home",
        "ocr_anchors": ["Edit Deck"],
    },
    "Events": {
        "scene": "Home",
        "ocr_anchors": ["Events"],
        "ocr_require_any": ["All", "In Progress", "Limited", "Constructed"],
    },
    "EventLanding": {
        "scene": "EventLanding",
        "ocr_require_any": ["Start", "Resume", "Build Your Deck", "Resign"],
    },
    # --- Nav bar screens ---
    "Profile": {
        "scene": "Profile",
        "ocr_anchors": ["Profile"],
    },
    "Decks": {
        "scene": "DeckListViewer",
        "ocr_anchors": ["All Decks"],
    },
    "DeckBuilder": {
        "scene": "DeckBuilder",
        "ocr_anchors": ["Cards"],
        "ocr_require_any": ["Done"],
    },
    "Packs": {
        "scene": "BoosterChamber",
    },
    "Mastery": {
        "scene": "RewardTrack",
    },
    "Achievements": {
        "scene": "Achievements",
    },
    # --- Draft ---
    "DraftPick": {
        "scene": None,
        "ocr_anchors": ["Confirm Pick"],
    },
    # --- In-Game ---
    "BannedCards": {
        "scene": "Home",
        "ocr_anchors": ["Banned"],
        "ocr_require_any": ["Okay"],
    },
    "Mulligan": {
        "scene": None,
        "ocr_anchors": ["Keep"],
    },
    "InGame": {
        "scene": None,
        "detect": "debug_api",
    },
    "ConcedMenu": {
        "scene": None,
        "ocr_anchors": ["Concede"],
    },
    "Result": {
        "scene": None,
        "ocr_require_any": ["DEFEAT", "VICTORY"],
    },
    "Reconnecting": {
        "scene": None,
        "ocr_anchors": ["Waiting for Server"],
    },
}


# --- Transitions ---
# (from, to) -> action steps. Each step is an arena CLI command string.
# "wait" is the confirmation condition. "wait_timeout" in seconds.

TRANSITIONS: list[dict] = [
    # --- Forward: Home → Play → FindMatch → DeckSelected → Mulligan → InGame ---
    {
        "from": "Home",
        "to": "Play",
        "steps": ['click "Play" --retry 3'],
        "wait": 'text="Find Match"',
        "wait_timeout": 5,
    },
    {
        "from": "Play",
        "to": "FindMatch",
        "steps": ['click "Find Match" --retry 3'],
        "wait": 'text="Bot Match"',
        "wait_timeout": 5,
    },
    {
        "from": "FindMatch",
        "to": "DeckSelected",
        "steps": [
            'click "Bot Match" --retry 3',
            "sleep 1",
        ],
        "wait": 'text="Edit Deck"',
        "wait_timeout": 5,
        "note": "Caller must click a deck thumbnail after this.",
    },
    {
        "from": "DeckSelected",
        "to": "Mulligan",
        "steps": ['click "Play" --retry 3'],
        "wait": 'text="Keep"',
        "wait_timeout": 30,
    },
    {
        "from": "Mulligan",
        "to": "InGame",
        "steps": ['click "Keep" --retry 3'],
        "wait": "phase=PRECOMBAT_MAIN",
        "wait_timeout": 15,
    },
    # --- In-game flow ---
    {
        "from": "InGame",
        "to": "ConcedMenu",
        "steps": ["click 940,42", "sleep 1"],
        "wait": 'text="Concede"',
        "wait_timeout": 3,
    },
    {
        "from": "ConcedMenu",
        "to": "Result",
        "steps": ['click "Concede" --retry 3'],
        "wait": 'text="DEFEAT"',
        "wait_timeout": 10,
    },
    {
        "from": "Result",
        "to": "Home",
        "steps": [
            "click 480,300",
            "sleep 2",
            "click 480,300",
            "sleep 2",
            "click 480,300",
        ],
        "wait": "scene=Home",
        "wait_timeout": 15,
    },
    # --- Events path ---
    {
        "from": "Play",
        "to": "Events",
        "steps": ['click "Events" --retry 3'],
        "wait": 'text="Limited"',
        "wait_timeout": 5,
    },
    {
        "from": "EventLanding",
        "to": "DeckBuilder",
        "steps": ['click "Build Your Deck" --retry 3'],
        "wait": 'text="Cards"',
        "wait_timeout": 10,
    },
    {
        "from": "DeckBuilder",
        "to": "EventLanding",
        "steps": ['click "Done" --retry 3'],
        "wait": "scene=EventLanding",
        "wait_timeout": 5,
    },
    {
        "from": "EventLanding",
        "to": "Mulligan",
        "steps": ['click "Play" --retry 3'],
        "wait": 'text="Keep"',
        "wait_timeout": 30,
    },
    # --- Back navigation ---
    {
        "from": "Play",
        "to": "Home",
        "steps": ['click "Home" --retry 3'],
        "wait": "scene=Home",
        "wait_timeout": 5,
    },
    {
        "from": "FindMatch",
        "to": "Home",
        "steps": ['click "Home" --retry 3'],
        "wait": "scene=Home",
        "wait_timeout": 5,
    },
    {
        "from": "Events",
        "to": "Home",
        "steps": ['click "Home" --retry 3'],
        "wait": "scene=Home",
        "wait_timeout": 5,
    },
    {
        "from": "EventLanding",
        "to": "Home",
        "steps": ['click "Home" --retry 3'],
        "wait": "scene=Home",
        "wait_timeout": 5,
    },
    # --- Nav bar (all screens share the top nav) ---
    {
        "from": "Home",
        "to": "Profile",
        "steps": ["click 108,57"],
        "wait": "scene=Profile",
        "wait_timeout": 5,
    },
    {
        "from": "Home",
        "to": "Decks",
        "steps": ["click 157,57"],
        "wait": "scene=DeckListViewer",
        "wait_timeout": 5,
    },
    {
        "from": "Home",
        "to": "Packs",
        "steps": ["click 207,57"],
        "wait": "scene=BoosterChamber",
        "wait_timeout": 5,
    },
    {
        "from": "Home",
        "to": "Mastery",
        "steps": ["click 307,57"],
        "wait": "scene=RewardTrack",
        "wait_timeout": 5,
    },
    {
        "from": "Home",
        "to": "Achievements",
        "steps": ["click 366,57"],
        "wait": "scene=Achievements",
        "wait_timeout": 5,
    },
    {
        "from": "Profile",
        "to": "Home",
        "steps": ["click 51,57"],
        "wait": "scene=Home",
        "wait_timeout": 5,
    },
    {
        "from": "Decks",
        "to": "Home",
        "steps": ["click 51,57"],
        "wait": "scene=Home",
        "wait_timeout": 5,
    },
    {
        "from": "Packs",
        "to": "Home",
        "steps": ["click 51,57"],
        "wait": "scene=Home",
        "wait_timeout": 5,
    },
    {
        "from": "Mastery",
        "to": "Home",
        "steps": ["click 51,57"],
        "wait": "scene=Home",
        "wait_timeout": 5,
    },
    {
        "from": "Achievements",
        "to": "Home",
        "steps": ["click 51,57"],
        "wait": "scene=Home",
        "wait_timeout": 5,
    },
    # --- Popups ---
    {
        "from": "BannedCards",
        "to": "Home",
        "steps": ['click "Okay" --retry 3'],
        "wait": "scene=Home",
        "wait_timeout": 10,
    },
]


# --- Graph helpers ---


def _build_adjacency() -> dict[str, list[dict]]:
    adj: dict[str, list[dict]] = {}
    for t in TRANSITIONS:
        adj.setdefault(t["from"], []).append(t)
    return adj


_ADJ = _build_adjacency()


def find_path(src: str, dst: str) -> list[dict] | None:
    """BFS shortest path from src to dst. Returns list of transitions, or None."""
    if src == dst:
        return []
    from collections import deque

    queue: deque[tuple[str, list[dict]]] = deque([(src, [])])
    visited = {src}
    while queue:
        node, path = queue.popleft()
        for t in _ADJ.get(node, []):
            target = t["to"]
            if target == dst:
                return path + [t]
            if target not in visited:
                visited.add(target)
                queue.append((target, path + [t]))
    return None
