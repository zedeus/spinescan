#!/usr/bin/env python3
"""Build the slim pack file from games.db.

The pack contains the top N games by num_votes with all their names
and display fields. Format: SQLite database (versioned).

Usage:
    uv run python -I build_pack.py [games.db] [pack.db] [--extras extras.txt]

The extras file is an optional list of extra BGG IDs (one per line)
to include regardless of rank. Nothing consumes it until issue #9.
"""

import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from normalize import normalize, strip_edition_words

PACK_VERSION = 1
PACK_SIZE = 10_000

PACK_SCHEMA = """
CREATE TABLE IF NOT EXISTS pack_meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS games (
    bgg_id        INTEGER PRIMARY KEY,
    name          TEXT NOT NULL,
    year          INTEGER,
    is_expansion  INTEGER NOT NULL DEFAULT 0,
    base_game_id  INTEGER,
    min_players   INTEGER,
    max_players   INTEGER,
    min_players_best INTEGER,
    max_players_best INTEGER,
    min_time      INTEGER,
    max_time      INTEGER,
    complexity    REAL,
    avg_rating    REAL,
    num_votes     INTEGER NOT NULL,
    rank          INTEGER,
    image_url     TEXT
);

CREATE TABLE IF NOT EXISTS names (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    bgg_id    INTEGER NOT NULL REFERENCES games(bgg_id),
    name      TEXT NOT NULL,
    name_norm TEXT NOT NULL,
    name_stripped TEXT NOT NULL,
    is_primary INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_pack_names_bgg_id ON names(bgg_id);
CREATE INDEX IF NOT EXISTS idx_pack_names_norm ON names(name_norm);
"""


def build_pack(
    db_path: str,
    pack_path: str,
    extra_ids: set[int] | None = None,
) -> dict:
    """Build pack from games.db. Returns summary stats."""
    source = sqlite3.connect(db_path)
    source.row_factory = sqlite3.Row

    # Get top N games by num_votes
    rows = source.execute(
        "SELECT bgg_id FROM games WHERE num_votes > 0 ORDER BY num_votes DESC LIMIT ?",
        (PACK_SIZE,),
    ).fetchall()
    pack_ids = {r["bgg_id"] for r in rows}

    if extra_ids:
        pack_ids |= extra_ids

    pack = sqlite3.connect(pack_path)
    pack.execute("PRAGMA journal_mode=WAL")
    pack.executescript(PACK_SCHEMA)

    pack.execute(
        "INSERT OR REPLACE INTO pack_meta VALUES (?, ?)",
        ("version", str(PACK_VERSION)),
    )
    pack.execute(
        "INSERT OR REPLACE INTO pack_meta VALUES (?, ?)",
        ("size", str(len(pack_ids))),
    )

    stats = {"games": 0, "names": 0}

    for row in source.execute(
        f"SELECT * FROM games WHERE bgg_id IN ({','.join('?' * len(pack_ids))})",
        list(pack_ids),
    ):
        pack.execute(
            """INSERT OR REPLACE INTO games VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            )""",
            (
                row["bgg_id"],
                row["name"],
                row["year"],
                row["is_expansion"],
                row["base_game_id"],
                row["min_players"],
                row["max_players"],
                row["min_players_best"],
                row["max_players_best"],
                row["min_time"],
                row["max_time"],
                row["complexity"],
                row["avg_rating"],
                row["num_votes"],
                row["rank"],
                row["image_url"],
            ),
        )
        stats["games"] += 1

    # Copy names for pack games
    for row in source.execute(
        f"SELECT * FROM names WHERE bgg_id IN ({','.join('?' * len(pack_ids))})",
        list(pack_ids),
    ):
        norm = row["name_norm"]
        stripped, _ = strip_edition_words(norm)
        pack.execute(
            "INSERT INTO names (bgg_id, name, name_norm, name_stripped, is_primary) VALUES (?, ?, ?, ?, ?)",
            (row["bgg_id"], row["name"], norm, stripped, row["is_primary"]),
        )
        stats["names"] += 1

    pack.commit()

    # Print summary
    pack_size = Path(pack_path).stat().st_size
    print(f"\n=== pack summary ===")
    print(f"Version: {PACK_VERSION}")
    print(f"Size: {pack_size / 1024:.0f} KB")
    print(f"Games: {stats['games']:,}")
    print(f"Names: {stats['names']:,}")

    min_votes = pack.execute(
        "SELECT MIN(num_votes) FROM games"
    ).fetchone()[0]
    print(f"Min votes (cutoff): {min_votes:,}")

    source.close()
    pack.close()
    return stats


def main():
    db_path = sys.argv[1] if len(sys.argv) > 1 else "games.db"
    pack_path = sys.argv[2] if len(sys.argv) > 2 else "pack.db"

    extra_ids = None
    if "--extras" in sys.argv:
        idx = sys.argv.index("--extras")
        if idx + 1 < len(sys.argv):
            extras_file = sys.argv[idx + 1]
            extra_ids = set()
            with open(extras_file) as f:
                for line in f:
                    line = line.strip()
                    if line and not line.startswith("#"):
                        extra_ids.add(int(line))
            print(f"Extra IDs: {len(extra_ids)}")

    if not Path(db_path).exists():
        print(f"Error: {db_path} not found")
        sys.exit(1)

    print(f"Building {pack_path} from {db_path}...")
    build_pack(db_path, pack_path, extra_ids)


if __name__ == "__main__":
    main()
