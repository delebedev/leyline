from __future__ import annotations

import sqlite3
import tempfile
from pathlib import Path

import pytest
from scry_lib.cards import CardResolver, find_arena_db


# ---------------------------------------------------------------------------
# DB discovery
# ---------------------------------------------------------------------------

class TestFindArenaDb:
    def test_returns_path_or_none(self):
        result = find_arena_db()
        # On CI without MTGA installed, this returns None — that's fine
        if result is not None:
            assert result.exists()
            assert result.suffix == ".mtga"


# ---------------------------------------------------------------------------
# CardResolver with real DB (skipped if MTGA not installed)
# ---------------------------------------------------------------------------

@pytest.fixture()
def resolver():
    db_path = find_arena_db()
    if db_path is None:
        pytest.skip("Arena DB not found")
    return CardResolver(db_path)


class TestCardResolverReal:
    def test_resolve_known_card(self, resolver: CardResolver):
        # Scoured Barrens — grpId 72032, always in Arena
        name = resolver.resolve(72032)
        assert name == "Scoured Barrens"

    def test_resolve_unknown_returns_none(self, resolver: CardResolver):
        assert resolver.resolve(999999999) is None

    def test_resolve_batch(self, resolver: CardResolver):
        names = resolver.resolve_batch([72032, 93636, 93645])
        assert names[72032] == "Scoured Barrens"
        assert names[93636] == "Hinterland Sanctifier"
        assert names[93645] == "Inspiring Overseer"

    def test_resolve_batch_skips_unknown(self, resolver: CardResolver):
        names = resolver.resolve_batch([72032, 999999999])
        assert 72032 in names
        assert 999999999 not in names

    def test_caches_lookups(self, resolver: CardResolver):
        resolver.resolve(72032)
        resolver.resolve(72032)
        # No assertion on cache internals — just verify it doesn't error


# ---------------------------------------------------------------------------
# CardResolver with synthetic DB
# ---------------------------------------------------------------------------

@pytest.fixture()
def fake_db(tmp_path):
    db_path = tmp_path / "test.mtga"
    conn = sqlite3.connect(db_path)
    conn.execute("CREATE TABLE Cards (GrpId INTEGER, TitleId INTEGER)")
    conn.execute("CREATE TABLE Localizations_enUS (LocId INTEGER, Loc TEXT, Formatted INTEGER)")
    conn.execute("INSERT INTO Cards VALUES (100, 1)")
    conn.execute("INSERT INTO Cards VALUES (200, 2)")
    conn.execute("INSERT INTO Localizations_enUS VALUES (1, 'Lightning Bolt', 1)")
    conn.execute("INSERT INTO Localizations_enUS VALUES (2, 'Grizzly Bears', 1)")
    conn.commit()
    conn.close()
    return db_path


class TestCardResolverSynthetic:
    def test_resolve(self, fake_db):
        r = CardResolver(fake_db)
        assert r.resolve(100) == "Lightning Bolt"
        assert r.resolve(200) == "Grizzly Bears"

    def test_resolve_missing(self, fake_db):
        r = CardResolver(fake_db)
        assert r.resolve(999) is None

    def test_batch(self, fake_db):
        r = CardResolver(fake_db)
        result = r.resolve_batch([100, 200, 999])
        assert result == {100: "Lightning Bolt", 200: "Grizzly Bears"}

    def test_cache_avoids_repeated_queries(self, fake_db):
        r = CardResolver(fake_db)
        r.resolve(100)
        # Close the underlying connection to prove cache works
        r._conn.close()
        # Should still resolve from cache
        assert r.resolve(100) == "Lightning Bolt"
