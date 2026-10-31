"""Test the names-only pack builder on a hand-made games.db."""

import sqlite3
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent.parent))

from build_db import SCHEMA
from build_pack import PACK_VERSION, build_pack
from normalize import normalize

# Hand-invented rows, not from the real dump.
GAMES = [
    # bgg_id, name, year, num_votes
    (1, "Harbour Masters", 1998, 50000),
    (2, "Harbour Masters: Deluxe Edition", 2005, 1200),
    (3, "Söldner der Nebelstadt", 2011, 300),
    (4, "Quiet Meadow", None, 0),
]

ALT_NAMES = [
    # bgg_id, alternate name
    (1, "Les Maîtres du Port"),
    (3, "Mercenaries of Mist City"),
]


@pytest.fixture(scope="module")
def pack(tmp_path_factory):
    tmp = tmp_path_factory.mktemp("pack")
    db_path = tmp / "games.db"
    pack_path = tmp / "pack.db"

    db = sqlite3.connect(db_path)
    db.executescript(SCHEMA)  # the real games.db schema, so the test input can't drift
    for bgg_id, name, year, votes in GAMES:
        db.execute(
            "INSERT INTO games (bgg_id, name, name_norm, year, num_votes) VALUES (?, ?, ?, ?, ?)",
            (bgg_id, name, normalize(name), year, votes),
        )
        db.execute(
            "INSERT INTO names (bgg_id, name, name_norm, is_primary) VALUES (?, ?, ?, 1)",
            (bgg_id, name, normalize(name)),
        )
    for bgg_id, alt in ALT_NAMES:
        db.execute(
            "INSERT INTO names (bgg_id, name, name_norm, is_primary) VALUES (?, ?, ?, 0)",
            (bgg_id, alt, normalize(alt)),
        )
    db.commit()
    db.close()

    build_pack(str(db_path), str(pack_path))
    conn = sqlite3.connect(pack_path)
    conn.row_factory = sqlite3.Row
    yield conn
    conn.close()


def test_meta(pack):
    meta = dict(pack.execute("SELECT key, value FROM pack_meta"))
    assert meta["version"] == str(PACK_VERSION)
    assert meta["size"] == str(len(GAMES))


def test_all_games_present_once(pack):
    rows = pack.execute("SELECT bgg_id, name FROM games ORDER BY bgg_id").fetchall()
    assert [(r["bgg_id"], r["name"]) for r in rows] == [
        (g[0], g[1]) for g in GAMES
    ]


def test_no_alternate_names(pack):
    """The pack holds exactly one primary name per game — no names table,
    no alternate rows anywhere."""
    tables = {
        r[0]
        for r in pack.execute("SELECT name FROM sqlite_master WHERE type='table'")
    }
    assert "names" not in tables
    names = {r[0] for r in pack.execute("SELECT name FROM games")}
    for _, alt in ALT_NAMES:
        assert alt not in names


def test_no_descriptions_or_library_fields(pack):
    cols = {r[1] for r in pack.execute("PRAGMA table_info(games)")}
    assert cols == {"bgg_id", "name", "name_norm", "name_stripped", "year", "num_votes"}


def test_normalised_columns(pack):
    row = pack.execute("SELECT * FROM games WHERE bgg_id = 2").fetchone()
    assert row["name_norm"] == "harbour masters deluxe edition"
    assert row["name_stripped"] == "harbour masters"
    row = pack.execute("SELECT * FROM games WHERE bgg_id = 3").fetchone()
    assert row["name_norm"] == "soldner der nebelstadt"
