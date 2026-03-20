"""Card name resolution from Arena's SQLite database."""

from __future__ import annotations

import glob
import sqlite3
from pathlib import Path

_ARENA_DB_DIR = Path.home() / "Library/Application Support/com.wizards.mtga/Downloads/Raw"
_LOOKUP_SQL = """
    SELECT c.GrpId, l.Loc
    FROM Cards c
    JOIN Localizations_enUS l ON c.TitleId = l.LocId
    WHERE c.GrpId IN ({placeholders})
"""


def find_arena_db() -> Path | None:
    """Find the Arena card database on disk. Returns None if not installed."""
    matches = sorted(_ARENA_DB_DIR.glob("Raw_CardDatabase_*.mtga"))
    return matches[-1] if matches else None


class CardResolver:
    """Resolves grpId → card name via Arena's SQLite DB. Caches lookups."""

    def __init__(self, db_path: Path) -> None:
        self._conn = sqlite3.connect(str(db_path))
        self._cache: dict[int, str] = {}

    def resolve(self, grp_id: int) -> str | None:
        """Resolve a single grpId to card name. Returns None if unknown."""
        if grp_id in self._cache:
            return self._cache[grp_id]
        result = self.resolve_batch([grp_id])
        return result.get(grp_id)

    def resolve_batch(self, grp_ids: list[int]) -> dict[int, str]:
        """Resolve multiple grpIds at once. Returns {grpId: name} for found cards."""
        # Check cache first
        uncached = [gid for gid in grp_ids if gid not in self._cache]
        if uncached:
            placeholders = ",".join("?" * len(uncached))
            sql = _LOOKUP_SQL.format(placeholders=placeholders)
            for row in self._conn.execute(sql, uncached):
                self._cache[row[0]] = row[1]

        return {gid: self._cache[gid] for gid in grp_ids if gid in self._cache}
