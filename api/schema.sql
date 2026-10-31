-- D1 schema for the game-data API.
-- Loaded locally by tests (fixture rows) and remotely by tools/build_d1.py
-- (real rows from games.db plus descriptions from the dump — never committed).

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
    min_time      INTEGER,
    max_time      INTEGER,
    complexity    REAL,
    avg_rating    REAL,
    rank          INTEGER,
    num_votes     INTEGER NOT NULL DEFAULT 0,
    image_url     TEXT,
    description   TEXT
);

CREATE TABLE IF NOT EXISTS names (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    bgg_id    INTEGER NOT NULL REFERENCES games(bgg_id),
    name      TEXT NOT NULL,
    name_norm TEXT NOT NULL,
    is_primary INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_names_bgg_id ON names(bgg_id);

CREATE TABLE IF NOT EXISTS tags (
    bgg_id INTEGER NOT NULL REFERENCES games(bgg_id),
    kind   TEXT NOT NULL,
    name   TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tags_bgg_id ON tags(bgg_id);

-- The compressed pack, chunked to stay under D1 value limits.
CREATE TABLE IF NOT EXISTS pack_meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS pack_chunks (
    seq  INTEGER PRIMARY KEY,
    data BLOB NOT NULL
);
