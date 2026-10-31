"""Round-trip test for the D1 SQL generator on hand-made inputs."""

import gzip
import hashlib
import json
import sqlite3
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent.parent))

from build_d1 import build_d1
from build_db import SCHEMA
from normalize import normalize


@pytest.fixture(scope="module")
def d1(tmp_path_factory):
    tmp = tmp_path_factory.mktemp("d1")

    # Hand-made games.db
    db_path = tmp / "games.db"
    db = sqlite3.connect(db_path)
    db.executescript(SCHEMA)  # the real games.db schema, so the test input can't drift
    db.execute(
        "INSERT INTO games (bgg_id, name, name_norm, year, min_players, max_players,"
        " min_players_best, max_players_best, min_time, max_time, complexity,"
        " avg_rating, rank, num_votes, image_url)"
        " VALUES (1, 'O''Malley''s Harbour', ?, 1998,"
        " 2, 4, 3, 3, 30, 60, 2.1, 7.5, 123, 50000, 'https://example.com/x.png')",
        (normalize("O'Malley's Harbour"),),
    )
    db.execute(
        # num_votes NULL on purpose: games.db allows it, D1's column is NOT NULL.
        "INSERT INTO games (bgg_id, name, name_norm, year, min_players, max_players,"
        " min_players_best, max_players_best, min_time, max_time, complexity,"
        " avg_rating, rank, num_votes, image_url)"
        " VALUES (2, 'Quiet Meadow', ?, 2015,"
        " 1, 5, 2, 4, 20, 40, 1.5, 6.9, 456, NULL, NULL)",
        (normalize("Quiet Meadow"),),
    )
    name_sql = "INSERT INTO names (bgg_id, name, name_norm, is_primary) VALUES (?, ?, ?, ?)"
    db.execute(name_sql, (1, "O'Malley's Harbour", normalize("O'Malley's Harbour"), 1))
    db.execute(name_sql, (1, "Le Port d'O'Malley", normalize("Le Port d'O'Malley"), 0))
    db.execute(name_sql, (2, "Quiet Meadow", normalize("Quiet Meadow"), 1))
    db.execute("INSERT INTO tags (bgg_id, kind, name) VALUES (1, 'mechanic', 'Set Collection')")
    db.commit()
    db.close()

    # Hand-made dump with descriptions (only game 1 has one)
    dump_path = tmp / "dump.jl"
    dump_path.write_text(
        json.dumps({"bgg_id": 1, "description": "A game about O'Malley's harbour."})
        + "\n"
        + json.dumps({"bgg_id": 99, "description": "Not in games.db."})
        + "\n"
    )

    # A fake pack file, content irrelevant to the loader
    pack_path = tmp / "pack.db"
    pack_bytes = b"fake sqlite pack content " * 100
    pack_path.write_bytes(pack_bytes)

    out_dir = tmp / "out"
    build_d1(str(db_path), str(dump_path), str(pack_path), str(out_dir))

    # Execute every generated file against a fresh SQLite database — twice,
    # because reloading must not duplicate rows (names/tags have no natural key).
    target = sqlite3.connect(":memory:")
    for _ in range(2):
        for f in sorted(out_dir.glob("*.sql")):
            target.executescript(f.read_text())
    target.row_factory = sqlite3.Row
    return target, pack_bytes


def test_games_round_trip(d1):
    target, _ = d1
    rows = target.execute("SELECT * FROM games ORDER BY bgg_id").fetchall()
    assert len(rows) == 2
    assert rows[0]["name"] == "O'Malley's Harbour"
    assert rows[0]["description"] == "A game about O'Malley's harbour."
    assert rows[0]["min_players"] == 2
    assert rows[1]["description"] is None
    assert rows[1]["image_url"] is None
    assert rows[1]["num_votes"] == 0  # NULL in games.db, NOT NULL in D1


def test_names_and_tags(d1):
    target, _ = d1
    names = target.execute("SELECT name FROM names WHERE bgg_id = 1 ORDER BY is_primary").fetchall()
    assert [n["name"] for n in names] == ["Le Port d'O'Malley", "O'Malley's Harbour"]
    tags = target.execute("SELECT kind, name FROM tags").fetchall()
    assert (tags[0]["kind"], tags[0]["name"]) == ("mechanic", "Set Collection")


def test_pack_chunks_reassemble(d1):
    target, pack_bytes = d1
    chunks = target.execute("SELECT data FROM pack_chunks ORDER BY seq").fetchall()
    gz = b"".join(c["data"] for c in chunks)
    assert gzip.decompress(gz) == pack_bytes

    meta = dict(target.execute("SELECT key, value FROM pack_meta"))
    assert meta["sha256"] == hashlib.sha256(gz).hexdigest()
