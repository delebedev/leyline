"""Session card manifest — extracts cards + ability text from md-frames.jsonl + Arena DB."""

import glob as globmod
import json
import os
import sqlite3
import sys


def _arena_db():
    """Open the Arena card database (sqlite)."""
    arena_db_dir = os.path.join(
        os.environ.get("HOME", "/tmp"),
        "Library/Application Support/com.wizards.mtga/Downloads/Raw",
    )
    dbs = globmod.glob(os.path.join(arena_db_dir, "Raw_CardDatabase_*.mtga"))
    if not dbs:
        print(f"error: Arena card DB not found in {arena_db_dir}", file=sys.stderr)
        sys.exit(1)
    return sqlite3.connect(dbs[0])


def _resolve_cards(conn, grpids):
    """Resolve grpIds to card info dicts via Arena DB.

    Returns {grpId: {name, types, subtypes, abilities: [{id, text, category}]}}.
    """
    if not grpids:
        return {}

    placeholders = ",".join("?" * len(grpids))
    rows = conn.execute(
        f"SELECT c.GrpId, l.Loc, c.Types, c.Subtypes, c.AbilityIds, c.IsToken, c.Power, c.Toughness "
        f"FROM Cards c "
        f"JOIN Localizations_enUS l ON c.TitleId = l.LocId "
        f"WHERE l.Formatted = 1 AND c.GrpId IN ({placeholders})",
        list(grpids),
    ).fetchall()

    # Enum lookups for types/subtypes
    type_map = dict(
        conn.execute(
            "SELECT e.Value, l.Loc FROM Enums e "
            "JOIN Localizations_enUS l ON e.LocId = l.LocId "
            "WHERE e.Type = 'CardType' AND l.Formatted = 1"
        ).fetchall()
    )
    subtype_map = dict(
        conn.execute(
            "SELECT e.Value, l.Loc FROM Enums e "
            "JOIN Localizations_enUS l ON e.LocId = l.LocId "
            "WHERE e.Type = 'SubType' AND l.Formatted = 1"
        ).fetchall()
    )

    import re
    def _strip_html(s):
        return re.sub(r"<[^>]+>", "", s) if s else s

    result = {}
    for grp_id, name, types_str, subtypes_str, ability_ids_str, is_token, power, toughness in rows:
        name = _strip_html(name)
        # Parse types (pipe-separated ints)
        types = []
        if types_str:
            for t in types_str.split(","):
                t = t.strip()
                if t:
                    types.append(type_map.get(int(t), t))

        subtypes = []
        if subtypes_str:
            for st in subtypes_str.split(","):
                st = st.strip()
                if st:
                    subtypes.append(subtype_map.get(int(st), st))

        # Parse abilities (format: "abilityId:locId,abilityId:locId")
        abilities = []
        if ability_ids_str:
            for pair in ability_ids_str.split(","):
                pair = pair.strip()
                if ":" not in pair:
                    continue
                aid_str, _ = pair.split(":", 1)
                aid = int(aid_str)
                ab_row = conn.execute(
                    "SELECT TextId, Category FROM Abilities WHERE Id = ?", (aid,)
                ).fetchone()
                if ab_row:
                    text_row = conn.execute(
                        "SELECT Loc FROM Localizations_enUS WHERE LocId = ? AND Formatted = 1",
                        (ab_row[0],),
                    ).fetchone()
                    abilities.append({
                        "id": aid,
                        "text": _strip_html(text_row[0]) if text_row else None,
                        "category": ab_row[1],
                    })

        result[grp_id] = {
            "name": name,
            "types": types,
            "subtypes": subtypes,
            "isToken": bool(is_token),
            "power": power,
            "toughness": toughness,
            "abilities": abilities,
        }

    return result


def _extract_grpids(session_path):
    """Extract unique grpIds and per-card metadata from md-frames.jsonl.

    Returns {grpId: {cardTypes: set, zoneIds: set, instanceIds: set, owner: int|None}}.
    """
    md_path = os.path.join(session_path, "md-frames.jsonl")
    if not os.path.isfile(md_path):
        return {}

    cards = {}
    with open(md_path) as f:
        for line in f:
            obj = json.loads(line)
            for o in obj.get("objects", []):
                g = o.get("grpId", 0)
                if g <= 0:
                    continue
                if g not in cards:
                    cards[g] = {
                        "cardTypes": set(),
                        "zoneIds": set(),
                        "instanceIds": set(),
                        "owner": None,
                    }
                for ct in o.get("cardTypes", []):
                    cards[g]["cardTypes"].add(ct)
                cards[g]["zoneIds"].add(o.get("zoneId", 0))
                cards[g]["instanceIds"].add(o.get("instanceId", 0))
                if o.get("owner"):
                    cards[g]["owner"] = o["owner"]
    return cards


def cmd_cards(session=None, recordings_dir="recordings"):
    """Generate card manifest for a session. Writes cards.json."""
    from sessions import resolve_session

    path = resolve_session(session, recordings_dir)
    if not path:
        print(f"error: session not found: {session}", file=sys.stderr)
        sys.exit(1)

    name = os.path.basename(path.rstrip("/"))
    frame_cards = _extract_grpids(path)
    if not frame_cards:
        print(f"No cards found in {name} (no md-frames.jsonl or no objects)")
        return

    conn = _arena_db()
    db_cards = _resolve_cards(conn, set(frame_cards.keys()))
    conn.close()

    BASICS = {"Plains", "Island", "Swamp", "Mountain", "Forest"}

    manifest = []
    for grp_id in sorted(frame_cards.keys()):
        info = db_cards.get(grp_id)
        fc = frame_cards[grp_id]
        entry = {
            "grpId": grp_id,
            "name": info["name"] if info else None,
            "types": info["types"] if info else list(fc["cardTypes"]),
            "subtypes": info["subtypes"] if info else [],
            "isToken": info["isToken"] if info else False,
            "power": info["power"] if info else None,
            "toughness": info["toughness"] if info else None,
            "abilities": info["abilities"] if info else [],
            "owner": fc["owner"],
            "instanceCount": len(fc["instanceIds"]),
        }
        manifest.append(entry)

    out_path = os.path.join(path, "cards.json")
    with open(out_path, "w") as f:
        json.dump({"session": name, "cards": manifest}, f, indent=2)

    # Print summary
    resolved = [c for c in manifest if c["name"]]
    unresolved = [c for c in manifest if not c["name"]]
    nonland = [c for c in resolved if c["name"] not in BASICS]
    tokens = [c for c in resolved if c["isToken"]]

    print(f"Session: {name}")
    print(f"  {len(manifest)} grpIds ({len(resolved)} resolved, {len(unresolved)} unknown)")
    print(f"  {len(nonland)} nonland cards, {len(tokens)} tokens")
    print(f"  Written: {out_path}")

    # Show nonland cards grouped by owner
    for owner in sorted({c["owner"] for c in nonland if c["owner"]}):
        player_cards = [c for c in nonland if c["owner"] == owner]
        print(f"\n  Seat {owner}:")
        for c in player_cards:
            ab_summary = ""
            ab_texts = [a["text"] for a in c["abilities"] if a.get("text")]
            if ab_texts:
                # Join and truncate
                joined = " // ".join(ab_texts).replace("CARDNAME", c["name"])
                if len(joined) > 100:
                    joined = joined[:97] + "..."
                ab_summary = f"  — {joined}"
            pt = ""
            if c["power"] is not None and c["toughness"] is not None:
                pt = f" {c['power']}/{c['toughness']}"
            print(f"    {c['name']}{pt}{ab_summary}")
