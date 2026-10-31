# Pack format

The pack is a SQLite database (`pack.db`) containing the top 10,000 games by number of ratings, with all their names and the fields the library shows. Loaded by `core/PackLoader.kt` on the phone and in the harness.

## Schema

### pack_meta
Key-value metadata:
- `version`: pack format version (currently `1`)
- `size`: number of games

### games
One row per game:
- `bgg_id` (PK), `name`, `year`, `is_expansion`, `base_game_id`
- `min_players`, `max_players`, `min_players_best`, `max_players_best`
- `min_time`, `max_time`, `complexity`, `avg_rating`, `num_votes`, `rank`
- `image_url`

### names
All names (primary + alternates) for each game:
- `bgg_id`, `name`, `name_norm` (normalised), `name_stripped` (edition words removed), `is_primary`

## Building

```sh
cd tools
uv run python -I build_db.py ../board-game-data/scraped/bgg_GameItem.jl ../games.db
uv run python -I build_pack.py ../games.db ../pack.db
```

Optional: `--extras extras.txt` adds extra BGG IDs to the pack.

## Size

~8 MB for 10,000 games and ~46,000 names.
