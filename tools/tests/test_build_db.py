"""Test games.db builder on hand-made rows."""

import json
import sqlite3
import tempfile
from pathlib import Path

import pytest

import sys
sys.path.insert(0, str(Path(__file__).parent.parent))

from build_db import build_db, parse_tagged


# --- Hand-made test data ---

SAMPLE_GAMES = [
    {
        "bgg_id": 13,
        "name": "Catan",
        "alt_name": ["Catan", "Die Siedler von Catan", "Les Colons de Catane"],
        "year": 1995,
        "min_players": 3,
        "max_players": 4,
        "min_players_best": 3,
        "max_players_best": 4,
        "min_players_rec": 3,
        "max_players_rec": 4,
        "min_time": 60,
        "max_time": 120,
        "min_age": 10,
        "min_age_rec": 10,
        "complexity": 2.32,
        "avg_rating": 7.1,
        "bayes_rating": 7.0,
        "rank": 400,
        "num_votes": 100000,
        "stddev_rating": 1.5,
        "mechanic": ["Dice Rolling:2072", "Hand Management:2040"],
        "category": ["Economic:1021", "Negotiation:1026"],
        "designer": ["Klaus Teuber:11"],
        "artist": [],
        "publisher": ["KOSMOS:37"],
        "expansion": ["Catan: Seafarers:325", "Catan: Cities & Knights:926"],
        "image_url": ["https://example.com/catan.png"],
        "url": "/boardgame/13/catan",
    },
    {
        "bgg_id": 325,
        "name": "Catan: Seafarers",
        "alt_name": ["Catan: Seafarers", "Die Seefahrer von Catan"],
        "year": 1997,
        "min_players": 3,
        "max_players": 4,
        "min_players_best": 3,
        "max_players_best": 4,
        "min_players_rec": 3,
        "max_players_rec": 4,
        "min_time": 90,
        "max_time": 120,
        "min_age": 10,
        "min_age_rec": None,
        "complexity": 2.52,
        "avg_rating": 7.2,
        "bayes_rating": 7.1,
        "rank": 300,
        "num_votes": 40000,
        "stddev_rating": 1.3,
        "mechanic": ["Dice Rolling:2072"],
        "category": ["Expansion for Base-game:1042"],
        "designer": ["Klaus Teuber:11"],
        "artist": [],
        "publisher": ["KOSMOS:37"],
        "expansion": [],
        "image_url": [],
        "url": "/boardgame/325/catan-seafarers",
    },
    {
        "bgg_id": 174430,
        "name": "Gloomhaven",
        "alt_name": ["Gloomhaven", "グルームヘイヴン"],
        "year": 2017,
        "min_players": 1,
        "max_players": 4,
        "min_players_best": 2,
        "max_players_best": 3,
        "min_players_rec": 1,
        "max_players_rec": 4,
        "min_time": 60,
        "max_time": 120,
        "min_age": 14,
        "min_age_rec": 12,
        "complexity": 3.86,
        "avg_rating": 8.6,
        "bayes_rating": 8.4,
        "rank": 1,
        "num_votes": 60000,
        "stddev_rating": 1.1,
        "mechanic": ["Hand Management:2040"],
        "category": ["Adventure:1022"],
        "designer": ["Isaac Childres:69802"],
        "artist": ["Alexandr Elichev:77084"],
        "publisher": ["Cephalofair Games:27425"],
        "expansion": [],
        "image_url": ["https://example.com/gloomhaven.png"],
        "url": "/boardgame/174430/gloomhaven",
    },
]


@pytest.fixture
def sample_db(tmp_path):
    """Build a test DB from hand-made rows."""
    jl_path = tmp_path / "test.jl"
    with open(jl_path, "w") as f:
        for game in SAMPLE_GAMES:
            f.write(json.dumps(game) + "\n")
    db_path = str(tmp_path / "test.db")
    build_db(str(jl_path), db_path)
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    yield conn
    conn.close()


class TestParseTagged:
    def test_normal(self):
        assert parse_tagged("KOSMOS:37") == ("KOSMOS", 37)

    def test_colon_in_name(self):
        assert parse_tagged("Catan: Seafarers:325") == ("Catan: Seafarers", 325)

    def test_no_colon(self):
        assert parse_tagged("Unknown") == ("Unknown", None)


class TestFieldMapping:
    def test_game_count(self, sample_db):
        count = sample_db.execute("SELECT COUNT(*) FROM games").fetchone()[0]
        assert count == 3

    def test_catan_fields(self, sample_db):
        row = sample_db.execute(
            "SELECT * FROM games WHERE bgg_id = 13"
        ).fetchone()
        assert row["name"] == "Catan"
        assert row["year"] == 1995
        assert row["min_players"] == 3
        assert row["max_players"] == 4
        assert row["complexity"] == pytest.approx(2.32)
        assert row["avg_rating"] == pytest.approx(7.1)
        assert row["num_votes"] == 100000
        assert row["is_expansion"] == 0
        assert row["image_url"] == "https://example.com/catan.png"

    def test_seafarers_is_expansion(self, sample_db):
        row = sample_db.execute(
            "SELECT * FROM games WHERE bgg_id = 325"
        ).fetchone()
        assert row["is_expansion"] == 1
        assert row["base_game_id"] == 13

    def test_gloomhaven_not_expansion(self, sample_db):
        row = sample_db.execute(
            "SELECT * FROM games WHERE bgg_id = 174430"
        ).fetchone()
        assert row["is_expansion"] == 0
        assert row["base_game_id"] is None


class TestNames:
    def test_catan_names(self, sample_db):
        rows = sample_db.execute(
            "SELECT * FROM names WHERE bgg_id = 13 ORDER BY name"
        ).fetchall()
        names = [r["name"] for r in rows]
        assert "Catan" in names
        assert "Die Siedler von Catan" in names
        assert "Les Colons de Catane" in names

    def test_primary_flag(self, sample_db):
        row = sample_db.execute(
            "SELECT * FROM names WHERE bgg_id = 13 AND is_primary = 1"
        ).fetchone()
        assert row["name"] == "Catan"

    def test_normalised_names(self, sample_db):
        row = sample_db.execute(
            "SELECT name_norm FROM names WHERE bgg_id = 13 AND is_primary = 1"
        ).fetchone()
        assert row["name_norm"] == "catan"


class TestTags:
    def test_mechanics(self, sample_db):
        rows = sample_db.execute(
            "SELECT * FROM tags WHERE bgg_id = 13 AND kind = 'mechanic'"
        ).fetchall()
        names = {r["name"] for r in rows}
        assert "Dice Rolling" in names
        assert "Hand Management" in names

    def test_categories(self, sample_db):
        rows = sample_db.execute(
            "SELECT * FROM tags WHERE bgg_id = 13 AND kind = 'category'"
        ).fetchall()
        names = {r["name"] for r in rows}
        assert "Economic" in names


class TestPeople:
    def test_designer(self, sample_db):
        row = sample_db.execute(
            "SELECT * FROM people WHERE bgg_id = 13 AND role = 'designer'"
        ).fetchone()
        assert row["name"] == "Klaus Teuber"
        assert row["ref_id"] == 11

    def test_publisher(self, sample_db):
        row = sample_db.execute(
            "SELECT * FROM people WHERE bgg_id = 13 AND role = 'publisher'"
        ).fetchone()
        assert row["name"] == "KOSMOS"
