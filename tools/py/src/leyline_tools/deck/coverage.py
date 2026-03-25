"""Mechanic-density scoring and deck building for recording sessions.

Queries Arena's local SQLite card database to find cards with the highest
mechanic density across given sets, then builds 60-card decks optimized
for maximum mechanic variety per game.
"""

from __future__ import annotations

import re
import sqlite3
from dataclasses import dataclass, field

from leyline_tools.scry.cards import find_arena_db

_HTML_TAG_RE = re.compile(r"<[^>]+>")

# ── Mechanic definitions ────────────────────────────────────────────
# Three detection methods:
# 1. DIRECT_ABILITY: ability ID appears directly in card's AbilityIds
# 2. BASE_ID: card has an ability whose BaseId = this value (e.g. "Flashback {2R}" → BaseId 35)
# 3. ABILITY_WORD: card has an ability with AbilityWord enum = this value

# Method 1: Direct ability IDs on cards (keywords, category 3 in Abilities table)
DIRECT_ABILITY_MECHANICS: dict[str, int] = {
    "flash": 7,
    "flying": 8,
    "haste": 9,
    "lifelink": 12,
    "deathtouch": 1,
    "defender": 2,
    "double_strike": 3,
    "first_strike": 6,
    "hexproof": 10,
    "indestructible": 104,
    "menace": 142,
    "reach": 13,
    "trample": 14,
    "vigilance": 15,
    "ward": 211,
    "convoke": 52,
    "equip": 5,
    "kicker": 34,
    "prowess": 137,
    "saga": 166,
    "tiered": 365,
    "job_select": 364,
}

# Method 2: BaseId chain (card has ability X, X.BaseId = this value)
BASE_ID_MECHANICS: dict[str, int] = {
    "flashback": 35,
    "kicker": 34,  # specific kicker costs chain to BaseId 34
}

# Method 3: AbilityWord enum on abilities
ABILITY_WORD_MECHANICS: dict[str, int] = {
    "landfall": 21,
    "raid": 27,
    "threshold": 33,
}

# Mechanics detected structurally (not via abilities).
STRUCTURAL_MECHANICS = {"transform", "token_maker", "modal"}

# Weight multiplier: rare/missing mechanics in leyline score higher.
MECHANIC_WEIGHT: dict[str, float] = {
    "flashback": 5.0,
    "threshold": 4.0,
    "tiered": 4.0,
    "job_select": 4.0,
    "saga": 4.0,
    "transform": 5.0,
    "kicker": 3.0,
    "modal": 2.0,
    "token_maker": 2.0,
    "landfall": 2.0,
    "raid": 2.0,
    "prowess": 1.5,
    "convoke": 2.0,
}

# Color bitmask in Arena DB Colors column (comma-separated ints).
COLOR_NAMES = {1: "W", 2: "U", 3: "B", 4: "R", 5: "G"}


@dataclass
class ScoredCard:
    grp_id: int
    name: str
    colors: list[str]
    types: list[int]
    mechanics: list[str] = field(default_factory=list)
    score: float = 0.0
    is_land: bool = False


def _parse_colors(color_str: str) -> list[str]:
    if not color_str:
        return []
    return [COLOR_NAMES.get(int(c.strip()), "?") for c in color_str.split(",") if c.strip()]


def _parse_int_list(s: str) -> list[int]:
    if not s:
        return []
    return [int(x.strip()) for x in s.split(",") if x.strip()]


def _build_base_id_index(conn: sqlite3.Connection) -> dict[int, int]:
    """Map AbilityId → BaseId for abilities that chain to mechanics we track."""
    target_bases = set(BASE_ID_MECHANICS.values())
    if not target_bases:
        return {}
    rows = conn.execute(
        "SELECT Id, BaseId FROM Abilities WHERE BaseId IN ({})".format(
            ",".join("?" * len(target_bases))
        ),
        list(target_bases),
    ).fetchall()
    return {row[0]: row[1] for row in rows}


def _build_ability_word_index(conn: sqlite3.Connection) -> dict[int, int]:
    """Map AbilityId → AbilityWord for abilities with tracked ability words."""
    target_words = set(ABILITY_WORD_MECHANICS.values())
    if not target_words:
        return {}
    rows = conn.execute(
        "SELECT Id, AbilityWord FROM Abilities WHERE AbilityWord IN ({})".format(
            ",".join("?" * len(target_words))
        ),
        list(target_words),
    ).fetchall()
    return {row[0]: row[1] for row in rows}


def _has_modal(conn: sqlite3.Connection, ability_ids: list[int]) -> bool:
    """Check if any ability has modal children."""
    if not ability_ids:
        return False
    rows = conn.execute(
        "SELECT Id FROM Abilities WHERE Id IN ({}) AND ModalChildIds <> ''".format(
            ",".join("?" * len(ability_ids))
        ),
        ability_ids,
    ).fetchall()
    return len(rows) > 0


def score_set(
    conn: sqlite3.Connection,
    set_code: str,
    base_id_index: dict[int, int],
    ability_word_index: dict[int, int],
) -> list[ScoredCard]:
    """Score all non-token primary cards in a set by mechanic density."""
    # Reverse maps: value → mechanic name
    direct_reverse = {v: k for k, v in DIRECT_ABILITY_MECHANICS.items()}
    base_reverse = {v: k for k, v in BASE_ID_MECHANICS.items()}
    aword_reverse = {v: k for k, v in ABILITY_WORD_MECHANICS.items()}

    rows = conn.execute(
        """
        SELECT c.GrpId, l.Loc, c.Colors, c.Types, c.AbilityIds,
               c.LinkedFaceGrpIds, c.AbilityIdToLinkedTokenGrpId
        FROM Cards c
        JOIN Localizations_enUS l ON c.TitleId = l.LocId
        WHERE c.ExpansionCode = ?
          AND c.IsToken = 0
          AND c.IsPrimaryCard = 1
          AND l.Formatted = 1
        """,
        (set_code,),
    ).fetchall()

    cards: list[ScoredCard] = []
    for grp_id, name, colors, types_str, ability_ids_str, linked_face, token_links in rows:
        name = _HTML_TAG_RE.sub("", name)  # strip <nobr> etc.
        types = _parse_int_list(types_str)
        card = ScoredCard(
            grp_id=grp_id,
            name=name,
            colors=_parse_colors(colors),
            types=types,
            is_land=(5 in types),  # type 5 = Land
        )

        # Parse ability IDs from "id:locId,id:locId,..." format
        ability_ids: list[int] = []
        if ability_ids_str:
            for pair in ability_ids_str.split(","):
                parts = pair.split(":")
                if parts[0].strip().isdigit():
                    ability_ids.append(int(parts[0].strip()))

        # Detect mechanics via all three methods
        seen: set[str] = set()

        for aid in ability_ids:
            # Method 1: direct ability ID match
            if aid in direct_reverse:
                mech = direct_reverse[aid]
                if mech not in seen:
                    card.mechanics.append(mech)
                    seen.add(mech)

            # Method 2: BaseId chain
            base = base_id_index.get(aid)
            if base and base in base_reverse:
                mech = base_reverse[base]
                if mech not in seen:
                    card.mechanics.append(mech)
                    seen.add(mech)

            # Method 3: AbilityWord enum
            aword = ability_word_index.get(aid)
            if aword and aword in aword_reverse:
                mech = aword_reverse[aword]
                if mech not in seen:
                    card.mechanics.append(mech)
                    seen.add(mech)

        # Structural: transform
        if linked_face:
            card.mechanics.append("transform")
            seen.add("transform")

        # Structural: token maker
        if token_links:
            card.mechanics.append("token_maker")
            seen.add("token_maker")

        # Structural: modal
        if _has_modal(conn, ability_ids):
            card.mechanics.append("modal")
            seen.add("modal")

        # Score
        card.score = sum(MECHANIC_WEIGHT.get(m, 1.0) for m in card.mechanics)

        cards.append(card)

    return cards


def build_coverage_deck(
    cards: list[ScoredCard],
    deck_size: int = 60,
    land_count: int = 25,
    max_copies: int = 4,
    owned_copies: dict[int, int] | None = None,
    colors: set[str] | None = None,
) -> list[ScoredCard]:
    """Greedy deck builder: maximize mechanic variety.

    Picks highest-scoring cards first, caps at max_copies per card
    (or owned count if collection provided), fills remaining with lands.
    If colors is set, only include cards whose colors are a subset.
    """
    def color_ok(card: ScoredCard) -> bool:
        if colors is None:
            return True
        # Colorless cards fit any deck
        if not card.colors:
            return True
        return all(c in colors for c in card.colors)

    # Sort all nonland cards by score, then fill with zero-score if needed
    nonland = sorted(
        [c for c in cards if not c.is_land and color_ok(c)],
        key=lambda c: (-c.score, -len(c.mechanics), c.name),
    )

    spell_slots = deck_size - land_count
    deck: list[ScoredCard] = []
    counts: dict[int, int] = {}
    covered_mechanics: set[str] = set()

    for card in nonland:
        if len(deck) >= spell_slots:
            break
        limit = max_copies
        if owned_copies is not None:
            limit = min(limit, owned_copies.get(card.grp_id, 0))
        if limit <= 0:
            continue
        copies = min(limit, spell_slots - len(deck))
        # Only 1 copy if all its mechanics are already covered
        if all(m in covered_mechanics for m in card.mechanics):
            copies = min(copies, 1)
        counts[card.grp_id] = counts.get(card.grp_id, 0) + copies
        for _ in range(copies):
            deck.append(card)
            if len(deck) >= spell_slots:
                break
        covered_mechanics.update(card.mechanics)

    return deck


def format_deck_list(
    deck: list[ScoredCard], land_count: int = 25
) -> str:
    """Format deck as Arena-importable list."""
    # Count copies
    counts: dict[int, tuple[ScoredCard, int]] = {}
    for card in deck:
        if card.grp_id in counts:
            counts[card.grp_id] = (card, counts[card.grp_id][1] + 1)
        else:
            counts[card.grp_id] = (card, 1)

    lines: list[str] = []
    lines.append("Deck")
    for card, count in sorted(counts.values(), key=lambda x: (-x[0].score, x[0].name)):
        lines.append(f"{count} {card.name}")

    # Basic lands — distribute evenly across deck colors
    color_set: set[str] = set()
    for card in deck:
        color_set.update(card.colors)
    if not color_set:
        color_set = {"W", "U", "B", "R", "G"}

    land_names = {"W": "Plains", "U": "Island", "B": "Swamp", "R": "Mountain", "G": "Forest"}
    colors = sorted(color_set)
    per_color = land_count // len(colors)
    remainder = land_count % len(colors)
    for i, c in enumerate(colors):
        n = per_color + (1 if i < remainder else 0)
        if n > 0:
            lines.append(f"{n} {land_names[c]}")

    return "\n".join(lines)


def mechanic_summary(deck: list[ScoredCard]) -> dict[str, int]:
    """Count how many cards in the deck have each mechanic."""
    summary: dict[str, int] = {}
    for card in deck:
        for m in card.mechanics:
            summary[m] = summary.get(m, 0) + 1
    return dict(sorted(summary.items(), key=lambda x: -x[1]))


def pick_best_colors(
    set_codes: list[str],
    collection: dict[int, int] | None = None,
    n: int = 2,
) -> set[str]:
    """Pick the N colors with highest total mechanic score across sets.

    Scores each color by summing card scores for all owned cards of that color.
    """
    db_path = find_arena_db()
    if not db_path:
        return {"R", "G"}  # fallback

    conn = sqlite3.connect(str(db_path))
    base_id_index = _build_base_id_index(conn)
    ability_word_index = _build_ability_word_index(conn)

    color_scores: dict[str, float] = {}
    for code in set_codes:
        for card in score_set(conn, code, base_id_index, ability_word_index):
            if collection is not None and card.grp_id not in collection:
                continue
            if card.is_land or card.score == 0:
                continue
            for c in card.colors:
                color_scores[c] = color_scores.get(c, 0.0) + card.score

    conn.close()

    if not color_scores:
        return {"R", "G"}

    ranked = sorted(color_scores.items(), key=lambda x: -x[1])
    picked = {c for c, _ in ranked[:n]}
    return picked


def run(
    set_codes: list[str],
    deck_size: int = 60,
    land_count: int = 25,
    collection: dict[int, int] | None = None,
    colors: set[str] | None = None,
) -> None:
    """Main entry point: score sets, build deck, print results.

    If collection is provided, only cards the player owns are included.
    If colors is provided, only cards in those colors (+ colorless) are used.
    """
    db_path = find_arena_db()
    if not db_path:
        raise SystemExit("Arena card database not found.")

    conn = sqlite3.connect(str(db_path))
    base_id_index = _build_base_id_index(conn)
    ability_word_index = _build_ability_word_index(conn)

    all_cards: list[ScoredCard] = []
    for code in set_codes:
        scored = score_set(conn, code, base_id_index, ability_word_index)
        if collection is not None:
            owned = [c for c in scored if c.grp_id in collection]
            print(f"\n=== {code}: {len(scored)} total, {len(owned)} owned, "
                  f"{sum(1 for c in owned if c.score > 0)} with mechanics ===")
            scored = owned
        else:
            print(f"\n=== {code}: {len(scored)} cards, "
                  f"{sum(1 for c in scored if c.score > 0)} with mechanics ===")

        top = sorted(scored, key=lambda c: -c.score)[:15]
        for c in top:
            tags = ", ".join(c.mechanics) if c.mechanics else "-"
            copies = f" x{collection[c.grp_id]}" if collection and c.grp_id in collection else ""
            print(f"  {c.score:4.1f}  {c.name:<35s}  [{tags}]{copies}")
        all_cards.extend(scored)

    max_copies_map = collection if collection else None
    deck = build_coverage_deck(all_cards, deck_size=deck_size, land_count=land_count,
                               owned_copies=max_copies_map, colors=colors)
    summary = mechanic_summary(deck)

    color_label = "".join(sorted(colors)) if colors else "all"
    print(f"\n=== Coverage Deck ({len(deck)} spells + {land_count} lands, colors: {color_label}) ===")
    print(f"Mechanics covered: {len(summary)}")
    for mech, count in summary.items():
        print(f"  {mech:<20s} {count} cards")

    print(f"\n--- Arena Import ---")
    print(format_deck_list(deck, land_count))

    conn.close()
