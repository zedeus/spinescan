#!/usr/bin/env python3
"""Experiment 2: Fuzzy lookup — trigram index vs FTS5 trigram.

Builds a top-10k pack from the BGG data, then tests fuzzy matching
with OCR-like noise. Compares:
  A) In-memory trigram index with similarity scoring
  B) SQLite FTS5 trigram tokenizer with OR-of-trigrams + rescoring

Reports top-1 and top-3 accuracy, wrong greens, and lookup time.
Uses a tune/holdout split.

Usage:
    uv run python 02_fuzzy_lookup.py ../board-game-data/scraped/bgg_GameItem.jl
"""

import json
import math
import os
import random
import sqlite3
import sys
import time
from collections import Counter
from pathlib import Path

from normalize import normalize, strip_edition_words


# --- Trigram index (in-memory) ---

def trigrams(s: str) -> list[str]:
    """Generate character trigrams from a string, with padding."""
    padded = f"  {s} "
    return [padded[i:i+3] for i in range(len(padded) - 2)]


class TrigramIndex:
    def __init__(self):
        self.entries: list[tuple[int, str, str, int]] = []  # (bgg_id, raw_name, norm_name, popularity)
        self.index: dict[str, list[int]] = {}  # trigram -> [entry indices]

    def add(self, bgg_id: int, raw_name: str, norm_name: str, popularity: int):
        idx = len(self.entries)
        self.entries.append((bgg_id, raw_name, norm_name, popularity))
        for tri in set(trigrams(norm_name)):
            self.index.setdefault(tri, []).append(idx)

    def lookup(self, query: str, top_n: int = 5) -> list[tuple[int, str, float]]:
        """Return top_n (bgg_id, name, score) sorted by score desc, popularity as tiebreak."""
        query_tris = trigrams(query)
        query_tri_set = set(query_tris)
        if not query_tri_set:
            return []

        # Count how many query trigrams each entry shares
        candidate_counts: Counter[int] = Counter()
        for tri in query_tri_set:
            for idx in self.index.get(tri, []):
                candidate_counts[idx] += 1

        results = []
        for idx, shared in candidate_counts.most_common(top_n * 3):
            bgg_id, raw_name, norm_name, popularity = self.entries[idx]
            entry_tri_set = set(trigrams(norm_name))
            # Jaccard-like similarity: shared / union
            union = len(query_tri_set | entry_tri_set)
            score = shared / union if union > 0 else 0.0
            results.append((bgg_id, raw_name, score, popularity))

        # Sort by score desc, then popularity desc
        results.sort(key=lambda x: (-x[2], -x[3]))
        return [(bgg_id, name, score) for bgg_id, name, score, _ in results[:top_n]]


# --- FTS5 trigram (SQLite) ---

class FTS5TrigramIndex:
    def __init__(self):
        self.conn = sqlite3.connect(":memory:")
        self.conn.execute("CREATE VIRTUAL TABLE names_fts USING fts5(norm_name, tokenize='trigram')")
        self.conn.execute("""
            CREATE TABLE names(
                rowid INTEGER PRIMARY KEY,
                bgg_id INTEGER,
                raw_name TEXT,
                norm_name TEXT,
                popularity INTEGER
            )
        """)
        self._count = 0

    def add(self, bgg_id: int, raw_name: str, norm_name: str, popularity: int):
        self._count += 1
        self.conn.execute(
            "INSERT INTO names(rowid, bgg_id, raw_name, norm_name, popularity) VALUES (?, ?, ?, ?, ?)",
            (self._count, bgg_id, raw_name, norm_name, popularity),
        )
        self.conn.execute(
            "INSERT INTO names_fts(rowid, norm_name) VALUES (?, ?)",
            (self._count, norm_name),
        )

    def finish(self):
        self.conn.commit()

    def lookup_substring(self, query: str, top_n: int = 5) -> list[tuple[int, str, float]]:
        """FTS5 trigram MATCH (substring matching)."""
        if len(query) < 3:
            return []
        try:
            rows = self.conn.execute(
                """SELECT n.bgg_id, n.raw_name, n.norm_name, n.popularity,
                          rank
                   FROM names_fts f
                   JOIN names n ON n.rowid = f.rowid
                   WHERE f.norm_name MATCH ?
                   ORDER BY rank
                   LIMIT ?""",
                (f'"{query}"', top_n),
            ).fetchall()
        except Exception:
            return []
        return [(r[0], r[1], 1.0 / (1.0 - r[4]) if r[4] < 0 else 0.5) for r in rows]

    def lookup_or_trigrams(self, query: str, top_n: int = 5) -> list[tuple[int, str, float]]:
        """FTS5 queried with OR of query's own trigrams, then rescored."""
        query_tris = trigrams(query)
        if not query_tris:
            return []

        # Build OR query from trigrams (each must be 3 chars, quoted)
        terms = []
        for tri in set(query_tris):
            escaped = tri.replace('"', '""')
            terms.append(f'"{escaped}"')
        if not terms:
            return []
        fts_query = " OR ".join(terms)

        try:
            rows = self.conn.execute(
                """SELECT n.bgg_id, n.raw_name, n.norm_name, n.popularity
                   FROM names_fts f
                   JOIN names n ON n.rowid = f.rowid
                   WHERE f.norm_name MATCH ?
                   LIMIT ?""",
                (fts_query, top_n * 3),
            ).fetchall()
        except Exception:
            return []

        # Rescore with Jaccard
        query_tri_set = set(query_tris)
        results = []
        for bgg_id, raw_name, norm_name, popularity in rows:
            entry_tri_set = set(trigrams(norm_name))
            union = len(query_tri_set | entry_tri_set)
            shared = len(query_tri_set & entry_tri_set)
            score = shared / union if union > 0 else 0.0
            results.append((bgg_id, raw_name, score, popularity))

        results.sort(key=lambda x: (-x[2], -x[3]))
        return [(bgg_id, name, score) for bgg_id, name, score, _ in results[:top_n]]


# --- Noise simulation ---

CONFUSIONS = {
    "o": "0", "0": "o",
    "l": "1", "1": "l",
    "i": "1",
    "s": "5", "5": "s",
    "b": "6", "6": "b",
    "g": "9", "9": "g",
    "z": "2", "2": "z",
}


def apply_noise(name: str, error_rate: float, rng: random.Random) -> str:
    """Apply OCR-like noise to a name."""
    chars = list(name)
    result = []
    for ch in chars:
        r = rng.random()
        if r < error_rate * 0.4:
            # Character confusion
            result.append(CONFUSIONS.get(ch, ch))
        elif r < error_rate * 0.7:
            # Drop character
            pass
        elif r < error_rate:
            # Insert random character
            result.append(ch)
            result.append(rng.choice("abcdefghijklmnopqrstuvwxyz"))
        else:
            result.append(ch)
    return "".join(result)


def truncate_left(name: str, frac: float) -> str:
    """Cut text from the left (spine edge)."""
    cut = int(len(name) * frac)
    return name[cut:]


def truncate_right(name: str, frac: float) -> str:
    """Cut text from the right (spine edge)."""
    cut = int(len(name) * frac)
    return name[:len(name) - cut] if cut > 0 else name


def longest_word(name: str) -> str:
    """Return only the longest word."""
    words = name.split()
    return max(words, key=len) if words else name


def add_junk(name: str, rng: random.Random) -> str:
    """Append junk text like publisher names."""
    junk = rng.choice([
        "2 4 players", "ages 10", "asmodee", "hasbro", "ravensburger",
        "z man games", "fantasy flight", "rio grande", "days of wonder",
    ])
    return f"{name} {junk}"


# --- Test case generation ---

def generate_test_cases(
    pack_names: list[tuple[int, str, str]],  # (bgg_id, raw_name, norm_name)
    n_per_type: int,
    rng: random.Random,
) -> list[tuple[str, int, str]]:
    """Generate (query, expected_bgg_id, noise_type) tuples."""
    cases = []
    sample = rng.sample(pack_names, min(n_per_type * 2, len(pack_names)))

    for bgg_id, raw_name, norm_name in sample[:n_per_type]:
        # Exact (normalised)
        cases.append((norm_name, bgg_id, "exact"))

    for bgg_id, raw_name, norm_name in sample[:n_per_type]:
        # Light noise (8%)
        cases.append((apply_noise(norm_name, 0.08, rng), bgg_id, "noise_8pct"))

    for bgg_id, raw_name, norm_name in sample[:n_per_type]:
        # Heavy noise (20%)
        cases.append((apply_noise(norm_name, 0.20, rng), bgg_id, "noise_20pct"))

    for bgg_id, raw_name, norm_name in sample[:n_per_type]:
        # Truncated left 25%
        cases.append((truncate_left(norm_name, 0.25), bgg_id, "trunc_left"))

    for bgg_id, raw_name, norm_name in sample[:n_per_type]:
        # Truncated right 25%
        cases.append((truncate_right(norm_name, 0.25), bgg_id, "trunc_right"))

    for bgg_id, raw_name, norm_name in sample[:n_per_type]:
        # Longest word only
        cases.append((longest_word(norm_name), bgg_id, "longest_word"))

    for bgg_id, raw_name, norm_name in sample[:n_per_type]:
        # Junk appended
        cases.append((add_junk(norm_name, rng), bgg_id, "junk_appended"))

    return cases


def main():
    if len(sys.argv) < 2:
        print("Usage: uv run python 02_fuzzy_lookup.py <path-to-bgg_GameItem.jl>")
        sys.exit(1)

    data_path = sys.argv[1]
    out_dir = Path("out")
    out_dir.mkdir(exist_ok=True)
    rng = random.Random(42)

    # Load top-10k pack
    print("Loading games...")
    games = []
    with open(data_path) as f:
        for line in f:
            obj = json.loads(line)
            num_votes = obj.get("num_votes") or 0
            if num_votes > 0:
                games.append({
                    "bgg_id": obj["bgg_id"],
                    "name": obj["name"],
                    "alt_names": obj.get("alt_name", []),
                    "num_votes": num_votes,
                })

    games.sort(key=lambda g: -g["num_votes"])
    pack = games[:10000]
    print(f"Pack size: {len(pack)} games")

    # Build indices
    print("Building trigram index...")
    tri_index = TrigramIndex()
    fts_index = FTS5TrigramIndex()

    pack_names: list[tuple[int, str, str]] = []  # (bgg_id, raw, norm)
    for g in pack:
        for name in g["alt_names"]:
            norm = normalize(name)
            if norm:
                tri_index.add(g["bgg_id"], name, norm, g["num_votes"])
                fts_index.add(g["bgg_id"], name, norm, g["num_votes"])
                pack_names.append((g["bgg_id"], name, norm))

    fts_index.finish()
    print(f"Total names indexed: {len(pack_names)}")

    # Split into tune (60%) and holdout (40%)
    rng.shuffle(pack_names)
    split = int(len(pack_names) * 0.6)
    tune_names = pack_names[:split]
    holdout_names = pack_names[split:]

    n_per_type = 150  # per noise type

    for label, names_subset in [("tune", tune_names), ("holdout", holdout_names)]:
        print(f"\n{'=' * 60}")
        print(f"  {label.upper()} SET ({len(names_subset)} names)")
        print(f"{'=' * 60}")

        cases = generate_test_cases(names_subset, n_per_type, rng)
        print(f"Generated {len(cases)} test cases")

        for method_name, lookup_fn in [
            ("trigram_inmemory", lambda q: tri_index.lookup(q, 5)),
            ("fts5_substring", lambda q: fts_index.lookup_substring(q, 5)),
            ("fts5_or_trigrams", lambda q: fts_index.lookup_or_trigrams(q, 5)),
        ]:
            print(f"\n--- {method_name} ---")
            by_type: dict[str, dict[str, int]] = {}

            total_time = 0.0
            total_queries = 0

            for query, expected_id, noise_type in cases:
                if noise_type not in by_type:
                    by_type[noise_type] = {
                        "total": 0, "top1": 0, "top3": 0,
                        "wrong_green": 0, "no_result": 0,
                    }
                stats = by_type[noise_type]
                stats["total"] += 1

                t0 = time.perf_counter()
                results = lookup_fn(query)
                total_time += time.perf_counter() - t0
                total_queries += 1

                if not results:
                    stats["no_result"] += 1
                    continue

                top1_id, top1_name, top1_score = results[0]
                top3_ids = [r[0] for r in results[:3]]

                if top1_id == expected_id:
                    stats["top1"] += 1
                elif top1_score >= 0.5:  # Would be at least orange
                    stats["wrong_green"] += 1

                if expected_id in top3_ids:
                    stats["top3"] += 1

            avg_us = (total_time / total_queries * 1e6) if total_queries > 0 else 0
            print(f"  Avg lookup: {avg_us:.0f} µs")
            print(f"  {'Type':<15} {'Total':>5} {'Top-1':>5} {'Top-3':>5} {'Wrong':>5} {'None':>5}  {'Top-1%':>6} {'Top-3%':>6}")
            for noise_type in sorted(by_type.keys()):
                s = by_type[noise_type]
                t1_pct = s["top1"] / s["total"] * 100 if s["total"] else 0
                t3_pct = s["top3"] / s["total"] * 100 if s["total"] else 0
                print(f"  {noise_type:<15} {s['total']:>5} {s['top1']:>5} {s['top3']:>5} {s['wrong_green']:>5} {s['no_result']:>5}  {t1_pct:>5.1f}% {t3_pct:>5.1f}%")

    # Green/lead threshold sweep on tune set
    print(f"\n{'=' * 60}")
    print("  THRESHOLD SWEEP (tune set, trigram_inmemory)")
    print(f"{'=' * 60}")

    tune_cases = generate_test_cases(tune_names, 200, rng)
    green_thresholds = [0.7, 0.8, 0.85, 0.9, 0.95]
    lead_thresholds = [0.05, 0.1, 0.15, 0.2]

    print(f"  {'Green':>5} {'Lead':>5} {'Greens':>7} {'Wrong':>6} {'Correct':>7}")
    for green_t in green_thresholds:
        for lead_t in lead_thresholds:
            total_greens = 0
            wrong_greens = 0
            correct_greens = 0
            for query, expected_id, noise_type in tune_cases:
                results = tri_index.lookup(query, 5)
                if not results:
                    continue
                top_id, _, top_score = results[0]
                runner_score = results[1][2] if len(results) > 1 else 0.0
                lead = top_score - runner_score
                if top_score >= green_t and lead >= lead_t:
                    total_greens += 1
                    if top_id == expected_id:
                        correct_greens += 1
                    else:
                        wrong_greens += 1
            print(f"  {green_t:>5.2f} {lead_t:>5.2f} {total_greens:>7} {wrong_greens:>6} {correct_greens:>7}")

    # Write summary
    summary = out_dir / "02_fuzzy_lookup.txt"
    with open(summary, "w") as f:
        f.write("Fuzzy Lookup — Experiment 2\n")
        f.write("=" * 40 + "\n\n")
        f.write("See console output above for detailed results.\n\n")
        f.write("Key findings to fill in after running:\n")
        f.write("- In-memory trigram vs FTS5: [speed and accuracy comparison]\n")
        f.write("- FTS5 substring MATCH: [does it work for fuzzy matching?]\n")
        f.write("- Recommended approach: [which method]\n")
        f.write("- Starting green threshold: [value]\n")
        f.write("- Starting lead threshold: [value]\n")

    print(f"\nResults written to {summary}")


if __name__ == "__main__":
    main()
