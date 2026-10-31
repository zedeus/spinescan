#!/usr/bin/env python3
"""Generate SQL files that load the game-data API's D1 database.

Reads games.db (games, names, tags), descriptions from the raw dump (they
are API-only and never go into games.db or the pack), and the built pack
file. Writes numbered .sql files for `wrangler d1 execute --file` into an
output directory, plus a summary with the estimated database size against
the D1 free-plan limit (500 MB).

Everything here is private data: the output directory must stay local
(it is gitignored along with the inputs).

Usage:
    uv run python -I build_d1.py <games.db> <bgg_GameItem.jl> <pack.db> <out_dir>

Then, per file (local D1 first, --remote to deploy):
    cd api && npx wrangler d1 execute spinescan --file <out_dir>/NNNN_*.sql
"""

import gzip
import hashlib
import json
import sqlite3
import sys
from pathlib import Path

ROWS_PER_INSERT = 50
STATEMENTS_PER_FILE = 2000
# D1 caps a single SQL statement at 100 KB; stay well under it, both for
# multi-row INSERTs (descriptions are big) and for hex-encoded pack chunks
# (hex doubles the size: 40 KB of blob -> ~80 KB of statement).
MAX_SQL_BYTES = 80_000
PACK_CHUNK_BYTES = 40_000

GAME_COLUMNS = [
    "bgg_id", "name", "name_norm", "year", "is_expansion", "base_game_id",
    "min_players", "max_players", "min_players_best", "max_players_best",
    "min_time", "max_time", "complexity", "avg_rating", "rank", "num_votes",
    "image_url",
]


def sql_value(v) -> str:
    if v is None:
        return "NULL"
    if isinstance(v, (int, float)):
        return str(v)
    return "'" + str(v).replace("'", "''") + "'"


class SqlWriter:
    """Writes statements into numbered files, STATEMENTS_PER_FILE per file."""

    def __init__(self, out_dir: Path):
        self.out_dir = out_dir
        self.file_no = 0
        self.statements = 0
        self.fh = None
        self.files: list[Path] = []

    def write(self, stmt: str) -> None:
        if self.fh is None or self.statements >= STATEMENTS_PER_FILE:
            if self.fh:
                self.fh.close()
            self.file_no += 1
            path = self.out_dir / f"{self.file_no:04d}_data.sql"
            self.fh = open(path, "w")
            self.files.append(path)
            self.statements = 0
        self.fh.write(stmt.rstrip(";\n") + ";\n")
        self.statements += 1

    def close(self) -> None:
        if self.fh:
            self.fh.close()


def insert_rows(writer: SqlWriter, table: str, columns: list[str], rows: list):
    """Multi-row INSERTs, split at ROWS_PER_INSERT rows or MAX_SQL_BYTES."""
    prefix = f"INSERT OR REPLACE INTO {table} ({', '.join(columns)}) VALUES\n"
    values: list[str] = []
    size = len(prefix)
    for r in rows:
        v = "(" + ", ".join(sql_value(r[c]) for c in columns) + ")"
        vlen = len(v.encode()) + 2
        if values and (len(values) >= ROWS_PER_INSERT or size + vlen > MAX_SQL_BYTES):
            writer.write(prefix + ",\n".join(values))
            values = []
            size = len(prefix)
        values.append(v)
        size += vlen
    if values:
        writer.write(prefix + ",\n".join(values))


def load_descriptions(dump_path: str, wanted_ids: set[int]) -> dict[int, str]:
    descriptions = {}
    with open(dump_path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            d = json.loads(line)
            desc = d.get("description")
            bgg_id = d.get("bgg_id")
            if desc and bgg_id is not None and int(bgg_id) in wanted_ids:
                descriptions[int(bgg_id)] = desc
    return descriptions


def build_d1(db_path: str, dump_path: str, pack_path: str, out_dir: str) -> dict:
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    for old in out.glob("*.sql"):
        old.unlink()

    schema = (Path(__file__).resolve().parent.parent / "api" / "schema.sql").read_text()
    (out / "0000_schema.sql").write_text(schema)

    source = sqlite3.connect(db_path)
    source.row_factory = sqlite3.Row

    print("Reading descriptions from the dump...")
    wanted_ids = {r[0] for r in source.execute("SELECT bgg_id FROM games")}
    descriptions = load_descriptions(dump_path, wanted_ids)

    writer = SqlWriter(out)

    # Re-running a load must not duplicate rows: names and tags have no
    # natural key for INSERT OR REPLACE to land on, so clear them first
    # (children before games, for foreign keys).
    writer.write("DELETE FROM names")
    writer.write("DELETE FROM tags")
    writer.write("DELETE FROM games")

    stats = {"games": 0, "names": 0, "tags": 0, "desc_bytes": 0}

    cols = GAME_COLUMNS + ["description"]
    batch = []
    for row in source.execute(f"SELECT {', '.join(GAME_COLUMNS)} FROM games"):
        r = dict(row)
        # games.db allows NULL num_votes; the D1 column is NOT NULL.
        r["num_votes"] = r["num_votes"] or 0
        desc = descriptions.get(r["bgg_id"])
        r["description"] = desc
        stats["desc_bytes"] += len(desc.encode()) if desc else 0
        batch.append(r)
        stats["games"] += 1
        if len(batch) >= ROWS_PER_INSERT:
            insert_rows(writer, "games", cols, batch)
            batch = []
    insert_rows(writer, "games", cols, batch)

    for table, cols, key in (
        ("names", ["bgg_id", "name", "name_norm", "is_primary"], "names"),
        ("tags", ["bgg_id", "kind", "name"], "tags"),
    ):
        batch = []
        for row in source.execute(f"SELECT {', '.join(cols)} FROM {table}"):
            batch.append(dict(row))
            stats[key] += 1
            if len(batch) >= ROWS_PER_INSERT:
                insert_rows(writer, table, cols, batch)
                batch = []
        insert_rows(writer, table, cols, batch)
    source.close()

    # Pack: gzip and chunk. The sha256 of the gzipped pack doubles as its
    # content version; the pack format version lives inside the pack file.
    pack_gz = gzip.compress(Path(pack_path).read_bytes(), mtime=0)
    sha256 = hashlib.sha256(pack_gz).hexdigest()
    writer.write("DELETE FROM pack_chunks")
    for seq in range(0, len(pack_gz), PACK_CHUNK_BYTES):
        chunk = pack_gz[seq : seq + PACK_CHUNK_BYTES]
        writer.write(
            f"INSERT OR REPLACE INTO pack_chunks (seq, data) "
            f"VALUES ({seq // PACK_CHUNK_BYTES}, X'{chunk.hex()}')"
        )
    writer.write(
        f"INSERT OR REPLACE INTO pack_meta (key, value) VALUES ('sha256', '{sha256}')"
    )
    writer.close()

    stats["pack_gz_bytes"] = len(pack_gz)
    stats["sql_bytes"] = sum(f.stat().st_size for f in out.glob("*.sql"))
    stats["files"] = 1 + len(writer.files)

    est_db = Path(db_path).stat().st_size + stats["desc_bytes"] + len(pack_gz)
    print("\n=== D1 load summary ===")
    print(f"Games: {stats['games']:,}  Names: {stats['names']:,}  Tags: {stats['tags']:,}")
    print(f"Descriptions: {stats['desc_bytes'] / 2**20:.0f} MB")
    n_chunks = (len(pack_gz) + PACK_CHUNK_BYTES - 1) // PACK_CHUNK_BYTES
    print(f"Pack (gzip): {len(pack_gz) / 2**20:.1f} MB in {n_chunks} chunks")
    print(f"SQL files: {stats['files']} ({stats['sql_bytes'] / 2**20:.0f} MB)")
    print(f"Estimated D1 size: ~{est_db / 2**20:.0f} MB (free plan limit: 500 MB)")
    return stats


def main():
    if len(sys.argv) != 5:
        print("Usage: uv run python -I build_d1.py <games.db> <bgg_GameItem.jl> <pack.db> <out_dir>")
        sys.exit(1)
    build_d1(*sys.argv[1:5])


if __name__ == "__main__":
    main()
