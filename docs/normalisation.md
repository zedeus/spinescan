# Name normalisation

Single definition in Python (`tools/normalize.py`) and Kotlin (`core/.../Normalize.kt`), tested against the shared fixture file `fixtures/normalize.csv`.

## Steps

1. Unicode NFKD decomposition, strip combining marks (accents)
2. Lowercase
3. Remove all punctuation (keep letters, digits, whitespace)
4. Collapse whitespace
5. Strip leading/trailing whitespace

Edition words (anniversary, classic, deluxe, edition, etc.) are **not** removed by `normalize()`. The caller sets them aside with `strip_edition_words()` when needed, so "Catan: Deluxe Edition" normalises to `catan deluxe edition` and the caller can split off `deluxe edition`.

## Adding cases

Add rows to `fixtures/normalize.csv` and run both:
- `cd tools && uv run pytest tests/test_normalize.py`
- `./gradlew :core:test --tests "spinescan.core.NormalizeTest"`
