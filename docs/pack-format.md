# Pack format

The pack is a SQLite database (`pack.db`): the on-device names list, one
primary name per game for **all** games, with ID, year and popularity.
No alternate names, no descriptions, no library fields — those live behind
the game-data API (`api/`), which the phone queries for pack misses.
Loaded by `core/PackLoader.kt` on the phone and in the harness.

## Schema (version 2)

### pack_meta
Key-value metadata:
- `version`: pack format version (currently `2`)
- `size`: number of games

### games
One row per game:
- `bgg_id` (PK), `name` (primary name), `name_norm` (normalised),
  `name_stripped` (edition words removed), `year`, `num_votes`

## Building

```sh
cd tools
uv run python -I build_db.py ../board-game-data/scraped/bgg_GameItem.jl ../games.db
uv run python -I build_pack.py ../games.db ../pack.db
```

## Serving

`tools/build_d1.py` gzips the pack, chunks it into D1 (`pack_chunks`) and
records its sha256 + content version in D1's `pack_meta`. The app downloads
it via `GET /v1/pack`, verifies the checksum (`core/api/PackDownloader.kt`)
and swaps it in without disturbing a sweep (assign `Pipeline.pack`).

## Size

~9.9 MB (4.2 MB gzipped) for 135,026 games. Desktop JVM: loads and indexes
in ~0.9 s, lookups ~2 ms. The trigram index heap (~350 MB at 135k names) is
the open problem for the phone: slim the index (interned trigram ids,
primitive arrays) or fall back to a top-N pack when the app work lands.
