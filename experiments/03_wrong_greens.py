#!/usr/bin/env python3
"""Experiment 3: Wrong greens — junk text and out-of-pack games.

Tests how often non-game text and games outside the pack get a
confident wrong match against the top-10k trigram index.

Usage:
    uv run python 03_wrong_greens.py ../board-game-data/scraped/bgg_GameItem.jl
"""

import json
import random
import sys
from pathlib import Path

from normalize import normalize


def trigrams(s: str) -> list[str]:
    padded = f"  {s} "
    return [padded[i:i+3] for i in range(len(padded) - 2)]


class TrigramIndex:
    def __init__(self):
        self.entries: list[tuple[int, str, str, int]] = []
        self.index: dict[str, list[int]] = {}

    def add(self, bgg_id: int, raw_name: str, norm_name: str, popularity: int):
        idx = len(self.entries)
        self.entries.append((bgg_id, raw_name, norm_name, popularity))
        for tri in set(trigrams(norm_name)):
            self.index.setdefault(tri, []).append(idx)

    def lookup(self, query: str, top_n: int = 5) -> list[tuple[int, str, float]]:
        query_tris = trigrams(query)
        query_tri_set = set(query_tris)
        if not query_tri_set:
            return []
        from collections import Counter
        candidate_counts: Counter[int] = Counter()
        for tri in query_tri_set:
            for idx in self.index.get(tri, []):
                candidate_counts[idx] += 1
        results = []
        for idx, shared in candidate_counts.most_common(top_n * 3):
            bgg_id, raw_name, norm_name, popularity = self.entries[idx]
            entry_tri_set = set(trigrams(norm_name))
            union = len(query_tri_set | entry_tri_set)
            score = shared / union if union > 0 else 0.0
            results.append((bgg_id, raw_name, score, popularity))
        results.sort(key=lambda x: (-x[2], -x[3]))
        return [(bgg_id, name, score) for bgg_id, name, score, _ in results[:top_n]]


# Junk text that might appear on spines but isn't a game title
JUNK_TEXTS = [
    # Publisher names
    "asmodee", "hasbro gaming", "ravensburger", "z man games",
    "fantasy flight games", "rio grande games", "days of wonder",
    "czech games edition", "stonemaier games", "cmon", "plan b games",
    "space cowboys", "iello", "repos production", "kosmos",
    "schmidt spiele", "hans im gluck", "queen games", "pegasus spiele",
    # Common spine text
    "2 4 players", "ages 10 and up", "ages 14", "30 60 min",
    "1 5 players", "for 2 players", "playing time 90 minutes",
    # Random short text
    "the", "game", "board game", "card game", "dice",
    "new", "fun", "play", "strategy", "family",
    # Numbers and years
    "2024", "2023", "2019", "vol 1", "vol 2", "no 3",
    "13", "42", "101", "7",
    # Mixed junk
    "made in china", "contents 1 board 200 cards",
    "do not eat", "keep away from children under 3",
    "recyclable packaging",
]


def main():
    if len(sys.argv) < 2:
        print("Usage: uv run python 03_wrong_greens.py <path-to-bgg_GameItem.jl>")
        sys.exit(1)

    data_path = sys.argv[1]
    out_dir = Path("out")
    out_dir.mkdir(exist_ok=True)
    rng = random.Random(42)

    # Load all games, split into pack and non-pack
    print("Loading games...")
    games = []
    with open(data_path) as f:
        for line in f:
            obj = json.loads(line)
            num_votes = obj.get("num_votes") or 0
            games.append({
                "bgg_id": obj["bgg_id"],
                "name": obj["name"],
                "alt_names": obj.get("alt_name", []),
                "num_votes": num_votes,
            })

    games.sort(key=lambda g: -g["num_votes"])
    pack = [g for g in games if g["num_votes"] > 0][:10000]
    pack_ids = {g["bgg_id"] for g in pack}

    # Non-pack games (outside top 10k, with at least some votes)
    non_pack = [g for g in games if g["num_votes"] > 0 and g["bgg_id"] not in pack_ids]

    # Build trigram index on pack
    print("Building trigram index...")
    tri_index = TrigramIndex()
    for g in pack:
        for name in g["alt_names"]:
            norm = normalize(name)
            if norm:
                tri_index.add(g["bgg_id"], name, norm, g["num_votes"])

    green_threshold = 0.9
    lead_threshold = 0.1
    orange_threshold = 0.6

    results_lines = []

    def test_category(label: str, queries: list[str]):
        print(f"\n--- {label} ({len(queries)} queries) ---")
        greens = 0
        oranges = 0
        reds = 0
        green_examples = []

        for query in queries:
            results = tri_index.lookup(query, 5)
            if not results:
                reds += 1
                continue
            top_score = results[0][2]
            runner_score = results[1][2] if len(results) > 1 else 0.0
            lead = top_score - runner_score

            if top_score >= green_threshold and lead >= lead_threshold:
                greens += 1
                if len(green_examples) < 5:
                    green_examples.append((query, results[0][1], top_score))
            elif top_score >= orange_threshold:
                oranges += 1
            else:
                reds += 1

        print(f"  Green (wrong): {greens} ({greens/len(queries)*100:.1f}%)")
        print(f"  Orange: {oranges} ({oranges/len(queries)*100:.1f}%)")
        print(f"  Red/none: {reds} ({reds/len(queries)*100:.1f}%)")
        if green_examples:
            print(f"  Wrong green examples:")
            for q, matched, score in green_examples:
                print(f"    '{q}' → '{matched}' (score={score:.3f})")

        results_lines.append(f"{label}: {greens} wrong greens / {len(queries)} ({greens/len(queries)*100:.1f}%)")

    # 1. Junk text
    test_category("Junk text", [normalize(t) for t in JUNK_TEXTS])

    # 2. Out-of-pack game names
    oop_sample = rng.sample(non_pack, min(500, len(non_pack)))
    oop_queries = [normalize(g["name"]) for g in oop_sample]
    test_category("Out-of-pack games", oop_queries)

    # 3. Random character sequences
    random_queries = []
    for _ in range(200):
        length = rng.randint(3, 15)
        random_queries.append("".join(rng.choices("abcdefghijklmnopqrstuvwxyz ", k=length)).strip())
    test_category("Random strings", random_queries)

    # 4. Partial publisher + game fragments
    fragments = []
    for _ in range(200):
        pub = rng.choice(["hasbro", "asmodee", "ravensburger", "kosmos", "iello"])
        word = rng.choice(["game", "play", "fun", "new", "box", "set", "edition", "deluxe"])
        fragments.append(f"{pub} {word}")
    test_category("Publisher + word", fragments)

    # 5. Very short reads (1-3 chars)
    short_reads = [rng.choice("abcdefghijklmnopqrstuvwxyz0123456789") for _ in range(100)]
    short_reads += ["".join(rng.choices("abcdefghijklmnopqrstuvwxyz", k=2)) for _ in range(100)]
    short_reads += ["".join(rng.choices("abcdefghijklmnopqrstuvwxyz", k=3)) for _ in range(100)]
    test_category("Very short reads (1-3 chars)", short_reads)

    # Write summary
    summary = out_dir / "03_wrong_greens.txt"
    with open(summary, "w") as f:
        f.write("Wrong Greens — Experiment 3\n")
        f.write("=" * 40 + "\n\n")
        f.write(f"Thresholds: green >= {green_threshold}, lead >= {lead_threshold}, orange >= {orange_threshold}\n\n")
        for line in results_lines:
            f.write(f"  {line}\n")
        f.write("\nAssumptions:\n")
        f.write("- Starting thresholds keep wrong greens rare for junk: [RESULT]\n")
        f.write("- Out-of-pack games don't false-green: [RESULT]\n")
        f.write("- Short reads are handled: [RESULT]\n")

    print(f"\nResults written to {summary}")


if __name__ == "__main__":
    main()
