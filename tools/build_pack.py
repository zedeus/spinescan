#!/usr/bin/env python3
"""Build the slim names-only pack file from games.db.

The pack is the on-device names list: one primary name per game for ALL
games, with BGG ID, year and popularity. No alternate names, no
descriptions, no library fields — those live behind the API.
Format: SQLite database (versioned).

Usage:
    uv run python -I build_pack.py [games.db] [pack.db]
"""

import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from normalize import strip_edition_words

PACK_VERSION = 2

PACK_SCHEMA = """
CREATE TABLE IF NOT EXISTS pack_meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS games (
    bgg_id        INTEGER PRIMARY KEY,
    name          TEXT NOT NULL,
    name_norm     TEXT NOT NULL,
    name_stripped TEXT NOT NULL,
    year          INTEGER,
    num_votes     INTEGER NOT NULL DEFAULT 0
);
"""


def build_pack(db_path: str, pack_path: str) -> dict:
    """Build names-only pack from games.db. Returns summary stats."""
    source = sqlite3.connect(db_path)
    source.row_factory = sqlite3.Row

    Path(pack_path).unlink(missing_ok=True)
    pack = sqlite3.connect(pack_path)
    pack.executescript(PACK_SCHEMA)

    stats = {"games": 0}
    for row in source.execute(
        "SELECT bgg_id, name, name_norm, year, num_votes FROM games"
    ):
        norm = row["name_norm"]
        stripped, _ = strip_edition_words(norm)
        pack.execute(
            "INSERT INTO games (bgg_id, name, name_norm, name_stripped, year, num_votes)"
            " VALUES (?, ?, ?, ?, ?, ?)",
            (
                row["bgg_id"],
                row["name"],
                norm,
                stripped,
                row["year"],
                row["num_votes"] or 0,
            ),
        )
        stats["games"] += 1

    pack.execute(
        "INSERT OR REPLACE INTO pack_meta VALUES (?, ?)",
        ("version", str(PACK_VERSION)),
    )
    pack.execute(
        "INSERT OR REPLACE INTO pack_meta VALUES (?, ?)",
        ("size", str(stats["games"])),
    )
    pack.commit()

    pack_size = Path(pack_path).stat().st_size
    print("\n=== pack summary ===")
    print(f"Version: {PACK_VERSION}")
    print(f"Size: {pack_size / 1024:.0f} KB")
    print(f"Games: {stats['games']:,}")

    source.close()
    pack.close()
    return stats


def main():
    db_path = sys.argv[1] if len(sys.argv) > 1 else "games.db"
    pack_path = sys.argv[2] if len(sys.argv) > 2 else "pack.db"

    if not Path(db_path).exists():
        print(f"Error: {db_path} not found")
        sys.exit(1)

    print(f"Building {pack_path} from {db_path}...")
    build_pack(db_path, pack_path)


if __name__ == "__main__":
    main()
