from __future__ import annotations

import json
from pathlib import Path

from .common import die, try_remove
from .debug_api import fetch_api
from .macos import capture_window, ocr
from .paths import SWIFT_DIR
from .shell import run


def cmd_board(args: list[str]) -> None:
    hand_y_center, hand_x_min, hand_x_max = 530, 60, 720

    with_ocr = "--no-ocr" not in args
    with_detect = "--detect" in args
    detect_threshold = 0.3
    if "--detect-threshold" in args:
        idx = args.index("--detect-threshold")
        if idx + 1 < len(args):
            detect_threshold = float(args[idx + 1])

    state_raw = fetch_api("/api/state")
    if state_raw is None:
        die("Debug API unavailable")

    state = json.loads(state_raw)
    match_id = state.get("matchId")
    match_info = {
        "phase": state.get("phase"),
        "turn": state.get("turn", 0),
        "activePlayer": state.get("activePlayer"),
        "gameOver": state.get("gameOver", False),
    }

    if match_id is None:
        print(json.dumps({"match": match_info}, indent=2))
        return

    idmap_raw = fetch_api("/api/id-map?active=true")
    objects: list[dict] = []
    if idmap_raw:
        entries = json.loads(idmap_raw)
        if isinstance(entries, dict):
            entries = entries.get("data", [])
        for e in entries:
            objects.append(
                {
                    "instanceId": e.get("instanceId", 0),
                    "grpId": e.get("grpId", 0),
                    "name": e.get("cardName"),
                    "ownerSeatId": e.get("ownerSeatId", 0),
                    "controllerSeatId": e.get("ownerSeatId", 0),
                    "zoneId": e.get("protoZoneId", 0),
                    "forgeZone": e.get("forgeZone"),
                }
            )

    actions: list[dict] = []
    players: dict[int, int] = {}
    gs_raw = fetch_api("/api/game-states")
    if gs_raw:
        snapshots = json.loads(gs_raw).get("data", [])
        for snap in reversed(snapshots):
            if snap.get("players"):
                for p in snap["players"]:
                    players[p["seatId"]] = p["life"]
                break
        if snapshots:
            for act in snapshots[-1].get("actions", []):
                actions.append(
                    {
                        "actionType": act.get("actionType", ""),
                        "instanceId": act.get("instanceId", 0),
                        "grpId": act.get("grpId", 0),
                        "name": act.get("name"),
                    }
                )

    ocr_items: list[dict] = []
    if with_ocr:
        ocr_items = _board_ocr()

    our_seat, opp_seat = 1, 2
    actionable_ids = {a["instanceId"] for a in actions}

    our_hand = [
        o for o in objects if o["forgeZone"] == "Hand" and o["ownerSeatId"] == our_seat
    ]
    opp_hand = [
        o for o in objects if o["forgeZone"] == "Hand" and o["ownerSeatId"] == opp_seat
    ]
    bf = [o for o in objects if o["forgeZone"] == "Battlefield"]
    our_bf = [o for o in bf if o["controllerSeatId"] == our_seat]
    opp_bf = [o for o in bf if o["controllerSeatId"] == opp_seat]
    stack_cards = [o for o in objects if o["forgeZone"] == "Stack"]
    our_grave = [
        o
        for o in objects
        if o["forgeZone"] == "Graveyard" and o["ownerSeatId"] == our_seat
    ]
    opp_grave = [
        o
        for o in objects
        if o["forgeZone"] == "Graveyard" and o["ownerSeatId"] == opp_seat
    ]
    exile_cards = [o for o in objects if o["forgeZone"] == "Exile"]

    hand_ocr = [
        item
        for item in ocr_items
        if item["cy"] > 490 and hand_x_min <= item["cx"] <= hand_x_max
    ]
    if hand_ocr and our_hand:
        xs = sorted(item["cx"] for item in hand_ocr)
        clusters = [xs[0]]
        for x in xs[1:]:
            if x - clusters[-1] > 25:
                clusters.append(x)
            else:
                clusters[-1] = (clusters[-1] + x) // 2
        centers = clusters
    elif our_hand:
        count = len(our_hand)
        spacing = (hand_x_max - hand_x_min) // (count + 1)
        centers = [hand_x_min + (i + 1) * spacing for i in range(count)]
    else:
        centers = []

    for i, card in enumerate(our_hand):
        if i < len(centers):
            card["estimatedX"] = centers[i]
        else:
            last_x = centers[-1] if centers else 400
            card["estimatedX"] = last_x + (i - len(centers) + 1) * 40
        card["estimatedY"] = hand_y_center
        card["hasAction"] = card["instanceId"] in actionable_ids

    for card in our_bf:
        card["screenRegion"] = "our_battlefield"
        card["hasAction"] = card["instanceId"] in actionable_ids

    for card in opp_bf:
        card["screenRegion"] = "opp_battlefield"

    if with_detect:
        dets = _board_detect(threshold=detect_threshold)
        if dets:
            _correlate_detections(dets, our_hand, our_bf, opp_bf, stack_cards)

    board = {
        "match": match_info,
        "life": {"ours": players.get(our_seat, 0), "theirs": players.get(opp_seat, 0)},
        "hand": our_hand,
        "our_battlefield": our_bf,
        "opp_battlefield": opp_bf,
        "stack": stack_cards,
        "our_graveyard": {
            "count": len(our_grave),
            "cards": [c.get("name", "?") for c in our_grave],
        },
        "opp_graveyard": {
            "count": len(opp_grave),
            "cards": [c.get("name", "?") for c in opp_grave],
        },
        "exile": {
            "count": len(exile_cards),
            "cards": [c.get("name", "?") for c in exile_cards],
        },
        "opp_hand_count": len(opp_hand),
        "our_library_count": sum(
            1
            for o in objects
            if o["forgeZone"] == "Library" and o["ownerSeatId"] == our_seat
        ),
        "opp_library_count": sum(
            1
            for o in objects
            if o["forgeZone"] == "Library" and o["ownerSeatId"] == opp_seat
        ),
        "actions": actions,
    }

    if with_ocr:
        btn_items = [
            item
            for item in ocr_items
            if 860 < item["cx"] < 920 and 490 < item["cy"] < 520
        ]
        board["button"] = btn_items[0]["text"] if btn_items else None

    print(json.dumps(board, indent=2))


def _correlate_detections(
    dets: list[dict],
    our_hand: list[dict],
    our_bf: list[dict],
    opp_bf: list[dict],
    stack_cards: list[dict],
) -> None:
    hand_dets = sorted(
        [d for d in dets if d["label"] == "hand-card"],
        key=lambda d: d["cx"],
    )
    our_bf_dets = sorted(
        [
            d
            for d in dets
            if d["label"] in ("battlefield-untapped", "battlefield-tapped")
        ],
        key=lambda d: d["cx"],
    )
    opp_bf_dets = sorted(
        [d for d in dets if d["label"] in ("opponent-untapped", "opponent-tapped")],
        key=lambda d: d["cx"],
    )
    stack_dets = [d for d in dets if d["label"] == "stack-item"]

    if hand_dets and our_hand:
        if len(hand_dets) == len(our_hand):
            for card, det in zip(our_hand, hand_dets):
                _apply_det(card, det)
        else:
            _match_nearest_x(our_hand, hand_dets)

    if our_bf_dets and our_bf:
        if len(our_bf_dets) == len(our_bf):
            for card, det in zip(our_bf, our_bf_dets):
                _apply_det(card, det)
        else:
            _match_nearest_x(our_bf, our_bf_dets)

    if opp_bf_dets and opp_bf:
        if len(opp_bf_dets) == len(opp_bf):
            for card, det in zip(opp_bf, opp_bf_dets):
                _apply_det(card, det)
        else:
            _match_nearest_x(opp_bf, opp_bf_dets)

    if stack_dets and stack_cards:
        for card, det in zip(stack_cards, stack_dets):
            _apply_det(card, det)


def _apply_det(card: dict, det: dict) -> None:
    card["screenX"] = det["x"]
    card["screenY"] = det["y"]
    card["screenW"] = det["w"]
    card["screenH"] = det["h"]
    card["screenCX"] = det["cx"]
    card["screenCY"] = det["cy"]
    card["detectConfidence"] = det["confidence"]
    card["detectLabel"] = det["label"]


def _match_nearest_x(cards: list[dict], dets: list[dict]) -> None:
    used: set[int] = set()
    for card in cards:
        ref_x = card.get("estimatedX") or card.get("screenCX") or 0
        best_i, best_dist = -1, float("inf")
        for i, det in enumerate(dets):
            if i in used:
                continue
            dist = abs(det["cx"] - ref_x)
            if dist < best_dist:
                best_dist = dist
                best_i = i
        if best_i >= 0:
            used.add(best_i)
            _apply_det(card, dets[best_i])


def _board_ocr() -> list[dict]:
    try:
        img = "/tmp/arena/_board_ocr.png"
        Path(img).parent.mkdir(parents=True, exist_ok=True)
        if capture_window(img) is None:
            return []
        code, stdout, _ = ocr(img)
        try_remove(img)
        if code != 0 or not stdout.strip():
            return []
        items = json.loads(stdout)
        return [
            {"text": item["text"], "cx": int(item["cx"]), "cy": int(item["cy"])}
            for item in items
        ]
    except Exception:
        return []


def _board_detect(threshold: float = 0.3) -> list[dict]:
    try:
        img = "/tmp/arena/_board_detect.png"
        Path(img).parent.mkdir(parents=True, exist_ok=True)
        if capture_window(img) is None:
            return []
        code, stdout, _ = run(
            "swift",
            f"{SWIFT_DIR}/detect.swift",
            img,
            "--threshold",
            str(threshold),
            timeout=15,
        )
        try_remove(img)
        if code != 0 or not stdout.strip():
            return []
        return json.loads(stdout)
    except Exception:
        return []
