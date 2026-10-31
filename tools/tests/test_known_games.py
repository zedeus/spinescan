"""Test 20 well-known games match the BGG source.

Runs against the real games.db if it exists; skipped otherwise.
Checks alternate-language names, year, players, expansion versus base.
"""

import sqlite3
from pathlib import Path

import pytest

DB_PATH = Path(__file__).parent.parent.parent / "games.db"

pytestmark = pytest.mark.skipif(
    not DB_PATH.exists(),
    reason="games.db not built yet; run build_db.py first",
)


@pytest.fixture(scope="module")
def db():
    conn = sqlite3.connect(str(DB_PATH))
    conn.row_factory = sqlite3.Row
    yield conn
    conn.close()


# (bgg_id, name, year, is_expansion, base_game_id, alt_name_substring)
KNOWN_GAMES = [
    (13, "Catan", 1995, False, None, "Die Siedler von Catan"),
    (174430, "Gloomhaven", 2017, False, None, None),
    (167791, "Terraforming Mars", 2016, False, None, None),
    (169786, "Scythe", 2016, False, None, None),
    (230802, "Azul", 2017, False, None, None),
    (68448, "7 Wonders", 2010, False, None, "7 Чудес"),
    (9209, "Ticket to Ride", 2004, False, None, "Les Aventuriers du Rail"),
    (14996, "Ticket to Ride: Europe", 2005, False, None, None),
    (325, "Catan: Seafarers", 1997, True, 13, "Catan: Seefahrer"),
    (926, "Catan: Cities & Knights", 1998, True, 13, None),
    (161936, "Pandemic Legacy: Season 1", 2015, False, None, None),
    (224517, "Brass: Birmingham", 2018, False, None, None),
    (12333, "Twilight Struggle", 2005, False, None, None),
    (3076, "Puerto Rico", 2002, False, None, None),
    (120677, "Terra Mystica", 2012, False, None, None),
    (28720, "Brass: Lancashire", 2007, False, None, None),
    (187645, "Star Wars: Rebellion", 2016, False, None, None),
    (164928, "Orléans", 2014, False, None, None),
    (256226, "Azul: Stained Glass of Sintra", 2018, False, None, None),
    (266192, "Wingspan", 2019, False, None, None),
]


@pytest.mark.parametrize(
    "bgg_id,name,year,is_expansion,base_game_id,alt_name",
    KNOWN_GAMES,
    ids=[g[1] for g in KNOWN_GAMES],
)
def test_known_game(db, bgg_id, name, year, is_expansion, base_game_id, alt_name):
    row = db.execute(
        "SELECT * FROM games WHERE bgg_id = ?", (bgg_id,)
    ).fetchone()
    assert row is not None, f"Game {bgg_id} ({name}) not found"
    assert row["name"] == name
    assert row["year"] == year
    assert row["is_expansion"] == (1 if is_expansion else 0)
    if base_game_id is not None:
        assert row["base_game_id"] == base_game_id

    if alt_name:
        names = db.execute(
            "SELECT name FROM names WHERE bgg_id = ?", (bgg_id,)
        ).fetchall()
        name_list = [n["name"] for n in names]
        assert alt_name in name_list, (
            f"Expected alt name '{alt_name}' for {name}, got: {name_list[:5]}"
        )


@pytest.mark.parametrize(
    "bgg_id,name",
    [(g[0], g[1]) for g in KNOWN_GAMES],
    ids=[g[1] for g in KNOWN_GAMES],
)
def test_has_players(db, bgg_id, name):
    """Well-known games should have player counts."""
    row = db.execute(
        "SELECT min_players, max_players FROM games WHERE bgg_id = ?",
        (bgg_id,),
    ).fetchone()
    assert row["min_players"] is not None, f"{name} missing min_players"
    assert row["max_players"] is not None, f"{name} missing max_players"
