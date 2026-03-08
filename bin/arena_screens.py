"""Arena screen state machine — graph definition.

Mirrors docs/arena-screens.yaml as Python data.
Used by arena.py for screen detection and navigation.
"""

from __future__ import annotations


# --- Popup definitions ---
# Popups are modal overlays that block the current screen.
# Each popup: OCR anchors to detect it, dismiss steps to clear it.
# Checked before/after every navigation transition.

POPUPS: list[dict] = [
    {
        "name": "RewardDialog",
        "ocr_anchors": ["Claim"],
        "ocr_require_any": ["Daily", "Weekly", "Quest", "Reward"],
        "dismiss": ['click "Claim" --retry 3', "sleep 1"],
    },
    {
        "name": "LevelUp",
        "ocr_anchors": ["Level Up"],
        "dismiss": ["click 210,482", "sleep 1"],
    },
    {
        "name": "PatchNotes",
        "ocr_anchors": ["Patch Notes"],
        "dismiss": ['click "X" --retry 3', "sleep 1"],
    },
    {
        "name": "WildcardTracker",
        "ocr_anchors": ["Wildcard"],
        "ocr_require_any": ["Track", "Open"],
        "dismiss": ["click 210,482", "sleep 1"],
    },
    {
        "name": "DailyDeals",
        "ocr_anchors": ["Daily Deals"],
        "dismiss": ["click 210,482", "sleep 1"],
    },
    {
        "name": "GenericModal",
        "ocr_anchors": ["Okay"],
        "ocr_reject": ["Banned"],  # BannedCards is a real screen, not a popup
        "dismiss": ['click "Okay" --retry 3', "sleep 1"],
    },
]


# --- Screen definitions ---
# Each screen: detection signals (scene from Player.log, OCR anchors)

SCREENS: dict[str, dict] = {
    # --- Lobby ---
    "Home": {
        "scene": "Home",
        "ocr_anchors": ["Play"],
        "ocr_reject": ["Keep", "Concede", "Find Match", "Recently Played"],
    },
    # --- Play blade tabs (overlay on Home, scene stays "Home") ---
    "RecentlyPlayed": {
        "scene": "Home",
        "ocr_anchors": ["Recently Played"],
        "ocr_reject": ["Keep", "Edit Deck"],
    },
    "FindMatch": {
        "scene": "Home",
        "ocr_anchors": ["Find Match"],
        "ocr_reject": ["Keep", "Recently Played"],
    },
    "DeckSelected": {
        "scene": "Home",
        "ocr_anchors": ["Edit Deck"],
    },
    "Events": {
        "scene": "Home",
        "ocr_anchors": ["Events"],
        "ocr_require_any": ["All", "In Progress", "Limited", "Constructed"],
        "ocr_reject": ["Recently Played"],
    },
    "EventLanding": {
        "scene": "EventLanding",
        "ocr_require_any": ["Start", "Resume", "Build Your Deck"],
        "ocr_reject": ["Resign", "Loss"],
    },
    "EventLobby": {
        "scene": "EventLanding",
        "ocr_require_any": ["Play", "Resign"],
        "ocr_anchors": ["Loss"],
    },
    "SealedBoosterOpen": {
        "scene": "SealedBoosterOpen",
        "ocr_require_any": ["Open", "Continue"],
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
        "scene": "InGame",
    },
    "ConcedMenu": {
        "scene": None,
        "ocr_anchors": ["Concede"],
    },
    "Result": {
        "scene": None,
        "ocr_require_any": ["DEFEAT", "VICTORY"],
        "ocr_reject": ["Click to Continue"],
    },
    "EventResult": {
        "scene": None,
        "ocr_anchors": ["Click to Continue"],
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
    # --- Play blade: Home opens to RecentlyPlayed by default ---
    {
        "from": "Home",
        "to": "RecentlyPlayed",
        "steps": ["click 866,533"],
        "wait": 'text="Recently Played"',
        "wait_timeout": 5,
    },
    # --- Tab switching within play blade ---
    {
        "from": "RecentlyPlayed",
        "to": "FindMatch",
        "steps": ['click "Find Match" --retry 3'],
        "wait": 'text="Bot Match"',
        "wait_timeout": 5,
    },
    {
        "from": "RecentlyPlayed",
        "to": "Events",
        "steps": ['click "Events" --retry 3'],
        "wait": 'text="All"',
        "wait_timeout": 5,
    },
    {
        "from": "FindMatch",
        "to": "RecentlyPlayed",
        "steps": ['click "Recently Played" --retry 3'],
        "wait": 'text="Recently Played"',
        "wait_timeout": 5,
    },
    {
        "from": "FindMatch",
        "to": "Events",
        "steps": ['click "Events" --retry 3'],
        "wait": 'text="All"',
        "wait_timeout": 5,
    },
    {
        "from": "Events",
        "to": "RecentlyPlayed",
        "steps": ['click "Recently Played" --retry 3'],
        "wait": 'text="Recently Played"',
        "wait_timeout": 5,
    },
    {
        "from": "Events",
        "to": "FindMatch",
        "steps": ['click "Find Match" --retry 3'],
        "wait": 'text="Bot Match"',
        "wait_timeout": 5,
    },
    # --- FindMatch → DeckSelected (click format, then deck appears) ---
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
    # --- Starting a game ---
    {
        "from": "RecentlyPlayed",
        "to": "InGame",
        "steps": ["click 866,533"],
        "wait": "scene=InGame",
        "wait_timeout": 10,
        "note": "Starts match with last-used deck. Mulligan may or may not appear.",
    },
    {
        "from": "DeckSelected",
        "to": "InGame",
        "steps": ["click 866,533"],
        "wait": "scene=InGame",
        "wait_timeout": 10,
        "note": "Mulligan may or may not appear.",
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
            "click 210,482",
            "sleep 2",
            "click 210,482",
            "sleep 2",
            "click 210,482",
        ],
        "wait": "scene=Home",
        "wait_timeout": 15,
    },
    # --- Events → EventLanding (click event tile — coords vary) ---
    {
        "from": "Events",
        "to": "EventLanding",
        "steps": ['click "Sealed" --retry 3'],
        "wait": "scene=EventLanding",
        "wait_timeout": 5,
        "note": "Clicks Sealed tile. For other events, caller must click the right tile.",
    },
    # --- Events path (generic) ---
    {
        "from": "EventLanding",
        "to": "DeckBuilder",
        "steps": ['click "Build Your Deck" --retry 3'],
        "wait": 'text="Cards"',
        "wait_timeout": 10,
    },
    {
        "from": "DeckBuilder",
        "to": "EventLobby",
        "steps": ['click "Done" --retry 3'],
        "wait": "scene=EventLanding",
        "wait_timeout": 5,
    },
    {
        "from": "EventLanding",
        "to": "InGame",
        "steps": ["click 866,533"],
        "wait": "scene=InGame",
        "wait_timeout": 10,
    },
    {
        "from": "EventLobby",
        "to": "InGame",
        "steps": ["click 866,533"],
        "wait": "scene=InGame",
        "wait_timeout": 10,
    },
    # --- Sealed flow ---
    {
        "from": "EventLanding",
        "to": "SealedBoosterOpen",
        "steps": ["click 866,533"],
        "wait": "scene=SealedBoosterOpen",
        "wait_timeout": 10,
        "note": "Click Start on pre-deck EventLanding.",
    },
    {
        "from": "SealedBoosterOpen",
        "to": "DeckBuilder",
        "steps": [
            "click 480,533",
            "sleep 2",
            "click 867,532",
        ],
        "wait": "scene=DeckBuilder",
        "wait_timeout": 10,
        "note": "Open packs → rares reveal → Continue → DeckBuilder.",
    },
    # --- Event result (defeat with Click to Continue) ---
    {
        "from": "ConcedMenu",
        "to": "EventResult",
        "steps": ['click "Concede" --retry 3'],
        "wait": 'text="DEFEAT"',
        "wait_timeout": 10,
    },
    {
        "from": "EventResult",
        "to": "EventLobby",
        "steps": ["click 478,551"],
        "wait": "scene=EventLanding",
        "wait_timeout": 10,
        "note": "Click to Continue → back to event lobby with updated loss count.",
    },
    # --- Event lobby back nav ---
    {
        "from": "EventLobby",
        "to": "Home",
        "steps": ["click 53,57"],
        "wait": "scene=Home",
        "wait_timeout": 5,
    },
    # --- Back navigation (blade tabs → Home) ---
    {
        "from": "RecentlyPlayed",
        "to": "Home",
        "steps": ["click 746,93"],
        "wait": 'no-text="Recently Played"',
        "wait_timeout": 5,
        "note": "X button closes the play blade.",
    },
    {
        "from": "FindMatch",
        "to": "Home",
        "steps": ["click 746,93"],
        "wait": 'no-text="Find Match"',
        "wait_timeout": 5,
    },
    {
        "from": "DeckSelected",
        "to": "Home",
        "steps": ["click 746,93"],
        "wait": 'no-text="Edit Deck"',
        "wait_timeout": 5,
    },
    {
        "from": "Events",
        "to": "Home",
        "steps": ["click 746,93"],
        "wait": 'no-text="All"',
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
