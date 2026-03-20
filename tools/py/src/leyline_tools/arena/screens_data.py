"""Arena screen state machine data.

Mirrors docs/arena-screens.yaml as Python data.
Used by arena navigation/detection.
"""

from __future__ import annotations


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
        "ocr_reject": ["Banned"],
        "dismiss": ['click "Okay" --retry 3', "sleep 1"],
    },
]


SCREENS: dict[str, dict] = {
    "Home": {
        "scene": "Home",
        "ocr_anchors": ["Play"],
        "ocr_reject": ["Keep", "Concede", "Find Match", "Recently Played"],
    },
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
    "DraftPick": {
        "scene": None,
        "ocr_anchors": ["Confirm Pick"],
    },
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


TRANSITIONS: list[dict] = [
    {
        "from": "Home",
        "to": "RecentlyPlayed",
        "steps": ['click "Play" --retry 3'],
        "wait": 'text="Recently Played"',
        "wait_timeout": 5,
    },
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
    {
        "from": "FindMatch",
        "to": "DeckSelected",
        "steps": ['click "Bot Match" --retry 3', "sleep 1"],
        "wait": 'text="Edit Deck"',
        "wait_timeout": 5,
        "note": "Caller must click a deck thumbnail after this.",
    },
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
    {
        "from": "Events",
        "to": "EventLanding",
        "steps": ['click "Sealed" --retry 3'],
        "wait": "scene=EventLanding",
        "wait_timeout": 5,
        "note": "Clicks Sealed tile. For other events, caller must click the right tile.",
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
        "steps": ["click 480,533", "sleep 2", "click 867,532"],
        "wait": "scene=DeckBuilder",
        "wait_timeout": 10,
        "note": "Open packs → rares reveal → Continue → DeckBuilder.",
    },
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
    {
        "from": "EventLobby",
        "to": "Home",
        "steps": ["click 53,57"],
        "wait": "scene=Home",
        "wait_timeout": 5,
    },
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
    {
        "from": "BannedCards",
        "to": "Home",
        "steps": ['click "Okay" --retry 3'],
        "wait": "scene=Home",
        "wait_timeout": 10,
    },
]
