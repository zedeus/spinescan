#!/usr/bin/env python3
"""Build games.db from the BGG data file.

Usage:
    uv run python -I build_db.py <path-to-bgg_GameItem.jl> [output.db]

Streams the file line by line; never loads it all into memory.
"""

import json
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from normalize import normalize

SCHEMA = """
CREATE TABLE IF NOT EXISTS games (
    bgg_id        INTEGER PRIMARY KEY,
    name          TEXT NOT NULL,
    name_norm     TEXT NOT NULL,
    year          INTEGER,
    is_expansion  INTEGER NOT NULL DEFAULT 0,
    base_game_id  INTEGER,
    min_players   INTEGER,
    max_players   INTEGER,
    min_players_best INTEGER,
    max_players_best INTEGER,
    min_players_rec  INTEGER,
    max_players_rec  INTEGER,
    min_time      INTEGER,
    max_time      INTEGER,
    min_age       INTEGER,
    min_age_rec   INTEGER,
    complexity    REAL,
    avg_rating    REAL,
    bayes_rating  REAL,
    rank          INTEGER,
    num_votes     INTEGER,
    stddev_rating REAL,
    image_url     TEXT
);

CREATE TABLE IF NOT EXISTS names (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    bgg_id    INTEGER NOT NULL REFERENCES games(bgg_id),
    name      TEXT NOT NULL,
    name_norm TEXT NOT NULL,
    is_primary INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS tags (
    id     INTEGER PRIMARY KEY AUTOINCREMENT,
    bgg_id INTEGER NOT NULL REFERENCES games(bgg_id),
    kind   TEXT NOT NULL,
    name   TEXT NOT NULL,
    ref_id INTEGER
);

CREATE TABLE IF NOT EXISTS people (
    id     INTEGER PRIMARY KEY AUTOINCREMENT,
    bgg_id INTEGER NOT NULL REFERENCES games(bgg_id),
    role   TEXT NOT NULL,
    name   TEXT NOT NULL,
    ref_id INTEGER
);

CREATE INDEX IF NOT EXISTS idx_names_bgg_id ON names(bgg_id);
CREATE INDEX IF NOT EXISTS idx_names_norm ON names(name_norm);
CREATE INDEX IF NOT EXISTS idx_tags_bgg_id ON tags(bgg_id);
CREATE INDEX IF NOT EXISTS idx_tags_kind ON tags(kind);
CREATE INDEX IF NOT EXISTS idx_people_bgg_id ON people(bgg_id);
CREATE INDEX IF NOT EXISTS idx_people_role ON people(role);
CREATE INDEX IF NOT EXISTS idx_games_rank ON games(rank);
CREATE INDEX IF NOT EXISTS idx_games_num_votes ON games(num_votes);
CREATE INDEX IF NOT EXISTS idx_games_complexity ON games(complexity);
CREATE INDEX IF NOT EXISTS idx_games_min_players ON games(min_players);
CREATE INDEX IF NOT EXISTS idx_games_max_players ON games(max_players);
CREATE INDEX IF NOT EXISTS idx_games_min_time ON games(min_time);
CREATE INDEX IF NOT EXISTS idx_games_max_time ON games(max_time);
"""


def parse_tagged(value: str) -> tuple[str, int | None]:
    """Parse 'Name:id' strings, splitting on the last colon."""
    idx = value.rfind(":")
    if idx < 0:
        return value, None
    name = value[:idx]
    try:
        ref_id = int(value[idx + 1:])
    except ValueError:
        return value, None
    return name, ref_id


def int_or_none(obj: dict, key: str) -> int | None:
    v = obj.get(key)
    if v is None or v == "":
        return None
    try:
        return int(v)
    except (ValueError, TypeError):
        return None


def float_or_none(obj: dict, key: str) -> float | None:
    v = obj.get(key)
    if v is None or v == "":
        return None
    try:
        return float(v)
    except (ValueError, TypeError):
        return None


def build_expansion_map(data_path: str) -> dict[int, int]:
    """First pass: scan expansion lists to build child_id → parent_id map.

    The BGG data's expansion field is bidirectional: a base game lists its
    expansions AND each expansion lists its base game. To tell which is the
    expansion, collect all links and then pick the direction where the parent
    has more votes (the base game is almost always more popular).
    """
    # Collect all bidirectional links: (a, b) means a's expansion list has b
    links: list[tuple[int, int]] = []
    votes: dict[int, int] = {}
    with open(data_path) as f:
        for line in f:
            obj = json.loads(line)
            game_id = obj["bgg_id"]
            votes[game_id] = obj.get("num_votes") or 0
            for exp_str in obj.get("expansion") or []:
                _, other_id = parse_tagged(exp_str)
                if other_id is not None:
                    links.append((game_id, other_id))

    # For each pair (a, b) where a lists b and b lists a, the one with
    # fewer votes is the expansion. For one-way links, the listed game
    # is the expansion.
    link_set = set(links)
    child_to_parent: dict[int, int] = {}
    seen_pairs: set[tuple[int, int]] = set()

    for a, b in links:
        pair = (min(a, b), max(a, b))
        if pair in seen_pairs:
            continue
        seen_pairs.add(pair)

        reverse = (b, a) in link_set

        if reverse:
            # Bidirectional: more votes = base game
            a_votes = votes.get(a, 0)
            b_votes = votes.get(b, 0)
            if a_votes >= b_votes:
                parent, child = a, b
            else:
                parent, child = b, a
        else:
            # One-way: a lists b, so b is the expansion of a
            parent, child = a, b

        if child not in child_to_parent:
            child_to_parent[child] = parent

    return child_to_parent


def build_db(data_path: str, db_path: str) -> dict:
    """Build games.db from the BGG data file. Returns summary stats."""
    expansion_map = build_expansion_map(data_path)

    db = sqlite3.connect(db_path)
    db.execute("PRAGMA journal_mode=WAL")
    db.execute("PRAGMA synchronous=NORMAL")
    db.executescript(SCHEMA)

    stats = {"games": 0, "names": 0, "tags": 0, "people": 0, "expansions": 0}

    with open(data_path) as f:
        for line in f:
            obj = json.loads(line)
            bgg_id = obj["bgg_id"]
            primary_name = obj["name"]
            is_expansion = 1 if bgg_id in expansion_map else 0
            base_game_id = expansion_map.get(bgg_id)

            image_urls = obj.get("image_url") or []
            image_url = image_urls[0] if image_urls else None

            db.execute(
                """INSERT OR REPLACE INTO games VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                )""",
                (
                    bgg_id,
                    primary_name,
                    normalize(primary_name),
                    int_or_none(obj, "year"),
                    is_expansion,
                    base_game_id,
                    int_or_none(obj, "min_players"),
                    int_or_none(obj, "max_players"),
                    int_or_none(obj, "min_players_best"),
                    int_or_none(obj, "max_players_best"),
                    int_or_none(obj, "min_players_rec"),
                    int_or_none(obj, "max_players_rec"),
                    int_or_none(obj, "min_time"),
                    int_or_none(obj, "max_time"),
                    int_or_none(obj, "min_age"),
                    int_or_none(obj, "min_age_rec"),
                    float_or_none(obj, "complexity"),
                    float_or_none(obj, "avg_rating"),
                    float_or_none(obj, "bayes_rating"),
                    int_or_none(obj, "rank"),
                    int_or_none(obj, "num_votes"),
                    float_or_none(obj, "stddev_rating"),
                    image_url,
                ),
            )
            stats["games"] += 1
            if is_expansion:
                stats["expansions"] += 1

            # Names: all alt_names (which includes the primary)
            alt_names = obj.get("alt_name") or [primary_name]
            for name in alt_names:
                is_primary = 1 if name == primary_name else 0
                db.execute(
                    "INSERT INTO names (bgg_id, name, name_norm, is_primary) VALUES (?, ?, ?, ?)",
                    (bgg_id, name, normalize(name), is_primary),
                )
                stats["names"] += 1

            # Tags: mechanic, category, family
            for kind in ("mechanic", "category", "family"):
                for tagged in obj.get(kind) or []:
                    name, ref_id = parse_tagged(tagged)
                    db.execute(
                        "INSERT INTO tags (bgg_id, kind, name, ref_id) VALUES (?, ?, ?, ?)",
                        (bgg_id, kind, name, ref_id),
                    )
                    stats["tags"] += 1

            # People: designer, artist, publisher
            for role in ("designer", "artist", "publisher"):
                for tagged in obj.get(role) or []:
                    name, ref_id = parse_tagged(tagged)
                    db.execute(
                        "INSERT INTO people (bgg_id, role, name, ref_id) VALUES (?, ?, ?, ?)",
                        (bgg_id, role, name, ref_id),
                    )
                    stats["people"] += 1

    db.commit()
    db.close()
    return stats


def main():
    if len(sys.argv) < 2:
        print("Usage: uv run python -I build_db.py <bgg_GameItem.jl> [output.db]")
        sys.exit(1)

    data_path = sys.argv[1]
    db_path = sys.argv[2] if len(sys.argv) > 2 else "games.db"

    if not Path(data_path).exists():
        print(f"Error: {data_path} not found")
        sys.exit(1)

    print(f"Building {db_path} from {data_path}...")
    stats = build_db(data_path, db_path)

    # Print summary
    db = sqlite3.connect(db_path)
    db_size = Path(db_path).stat().st_size
    print(f"\n=== games.db summary ===")
    print(f"Size: {db_size / 1024 / 1024:.1f} MB")
    for table in ("games", "names", "tags", "people"):
        count = db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
        print(f"{table}: {count:,} rows")

    exp_count = db.execute(
        "SELECT COUNT(*) FROM games WHERE is_expansion = 1"
    ).fetchone()[0]
    print(f"Expansions: {exp_count:,}")

    rated = db.execute(
        "SELECT COUNT(*) FROM games WHERE num_votes > 0"
    ).fetchone()[0]
    print(f"Rated games: {rated:,}")

    db.close()


if __name__ == "__main__":
    main()
