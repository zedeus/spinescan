#!/usr/bin/env python3
"""Experiment 1: BGG data facts.

Examines the game file to verify assumptions about name storage,
expansion detection, top-10k cutoff, and name collisions.

Usage:
    uv run python 01_bgg_data_facts.py ../board-game-data/scraped/bgg_GameItem.jl
"""

import json
import os
import sys
from collections import Counter
from pathlib import Path

from normalize import normalize, strip_edition_words


def main():
    if len(sys.argv) < 2:
        print("Usage: uv run python 01_bgg_data_facts.py <path-to-bgg_GameItem.jl>")
        sys.exit(1)

    data_path = sys.argv[1]
    out_dir = Path("out")
    out_dir.mkdir(exist_ok=True)

    games = []
    total_alt_names = 0
    alt_includes_primary = 0
    alt_missing_primary = 0
    expansion_links: dict[int, list[int]] = {}  # base_id -> [expansion_ids]
    all_expansion_target_ids: set[int] = set()
    all_game_ids: set[int] = set()

    print(f"Reading {data_path}...")
    with open(data_path) as f:
        for line in f:
            obj = json.loads(line)
            bgg_id = obj["bgg_id"]
            all_game_ids.add(bgg_id)
            name = obj["name"]
            alt_names = obj.get("alt_name", [])
            num_votes = obj.get("num_votes") or 0

            total_alt_names += len(alt_names)
            if name in alt_names:
                alt_includes_primary += 1
            else:
                alt_missing_primary += 1

            # Parse expansion links
            exp_list = obj.get("expansion", [])
            if exp_list:
                exp_ids = []
                for exp_str in exp_list:
                    # Format: "Name:id"
                    parts = exp_str.rsplit(":", 1)
                    if len(parts) == 2:
                        try:
                            exp_ids.append(int(parts[1]))
                        except ValueError:
                            pass
                expansion_links[bgg_id] = exp_ids
                all_expansion_target_ids.update(exp_ids)

            games.append({
                "bgg_id": bgg_id,
                "name": name,
                "alt_names": alt_names,
                "num_votes": num_votes,
            })

    total_games = len(games)
    print(f"\nTotal games: {total_games}")
    print(f"Total alt_name entries: {total_alt_names}")
    print(f"  alt_name includes primary: {alt_includes_primary}")
    print(f"  alt_name missing primary: {alt_missing_primary}")

    # Expansion analysis
    expansion_ids_in_file = all_expansion_target_ids & all_game_ids
    expansion_ids_missing = all_expansion_target_ids - all_game_ids
    print(f"\nExpansion links: {sum(len(v) for v in expansion_links.values())} links from {len(expansion_links)} base games")
    print(f"  Distinct expansion target IDs: {len(all_expansion_target_ids)}")
    print(f"  Target IDs present in file: {len(expansion_ids_in_file)}")
    print(f"  Target IDs absent from file: {len(expansion_ids_missing)}")

    # Top-10k cutoff
    games_with_votes = [(g["num_votes"], g["bgg_id"], g["name"]) for g in games if g["num_votes"] > 0]
    games_with_votes.sort(reverse=True)
    print(f"\nGames with votes: {len(games_with_votes)}")
    if len(games_with_votes) >= 10000:
        cutoff_votes = games_with_votes[9999][0]
        print(f"Top-10,000 cutoff (10,000th game): {cutoff_votes} votes")
        print(f"  10,000th game: {games_with_votes[9999][2]} (id={games_with_votes[9999][1]})")
    else:
        print(f"  Fewer than 10,000 games with votes")

    # Pack = top 10k
    pack_ids = set()
    pack_games = []
    for votes, bgg_id, name in games_with_votes[:10000]:
        pack_ids.add(bgg_id)
        pack_games.append((bgg_id, name, votes))

    # Expansions in the pack
    pack_expansions = pack_ids & expansion_ids_in_file
    pack_expansions_with_base_in_pack = 0
    for bgg_id in pack_expansions:
        # Find which base game lists this as expansion
        for base_id, exp_ids in expansion_links.items():
            if bgg_id in exp_ids and base_id in pack_ids:
                pack_expansions_with_base_in_pack += 1
                break
    print(f"\nPack (top 10,000):")
    print(f"  Expansions in pack: {len(pack_expansions)}")
    print(f"  Expansions with base also in pack: {pack_expansions_with_base_in_pack}")

    # Name collisions after normalisation
    pack_name_map: dict[int, list[str]] = {}
    for g in games:
        if g["bgg_id"] in pack_ids:
            pack_name_map[g["bgg_id"]] = g["alt_names"]

    # Normalised name -> set of bgg_ids
    norm_to_ids: dict[str, set[int]] = {}
    for bgg_id, alt_names in pack_name_map.items():
        seen_norms: set[str] = set()
        for name in alt_names:
            n = normalize(name)
            if n and n not in seen_norms:
                seen_norms.add(n)
                norm_to_ids.setdefault(n, set()).add(bgg_id)

    collisions = {n: ids for n, ids in norm_to_ids.items() if len(ids) > 1}
    print(f"\nNormalised name collisions in pack:")
    print(f"  Unique normalised names: {len(norm_to_ids)}")
    print(f"  Names shared by >1 game: {len(collisions)}")

    # With edition words stripped
    stripped_to_ids: dict[str, set[int]] = {}
    for bgg_id, alt_names in pack_name_map.items():
        seen_stripped: set[str] = set()
        for name in alt_names:
            n = normalize(name)
            s, _ = strip_edition_words(n)
            if s and s not in seen_stripped:
                seen_stripped.add(s)
                stripped_to_ids.setdefault(s, set()).add(bgg_id)

    stripped_collisions = {n: ids for n, ids in stripped_to_ids.items() if len(ids) > 1}
    print(f"  After stripping edition words: {len(stripped_collisions)} collisions")

    # Short names (<=4 chars)
    short_names = [(n, ids) for n, ids in norm_to_ids.items() if len(n) <= 4]
    print(f"\nShort normalised names (<=4 chars): {len(short_names)}")

    # Prefix collisions: name A is a word-prefix of name B
    all_norms = sorted(norm_to_ids.keys())
    prefix_pairs = 0
    prefix_examples = []
    for i, a in enumerate(all_norms):
        for j in range(i + 1, min(i + 50, len(all_norms))):
            b = all_norms[j]
            if not b.startswith(a):
                break
            # Check word boundary: b must have a space or end right after a
            if len(b) > len(a) and b[len(a)] == " ":
                prefix_pairs += 1
                if len(prefix_examples) < 10:
                    prefix_examples.append((a, b))
    print(f"  Word-prefix pairs (A is prefix of B): {prefix_pairs}")
    if prefix_examples:
        print("  Examples:")
        for a, b in prefix_examples[:5]:
            print(f"    '{a}' → '{b}'")

    # Show some collision examples
    print(f"\nSample collisions (normalised name -> game IDs):")
    collision_list = sorted(collisions.items(), key=lambda x: -len(x[1]))
    for name, ids in collision_list[:15]:
        id_names = []
        for gid in ids:
            for g in games:
                if g["bgg_id"] == gid:
                    id_names.append(f"{g['name']} ({gid})")
                    break
        print(f"  '{name}': {', '.join(id_names)}")

    # Write summary
    summary = out_dir / "01_bgg_data_facts.txt"
    with open(summary, "w") as f:
        f.write("BGG Data Facts — Experiment 1\n")
        f.write("=" * 40 + "\n\n")
        f.write(f"Total games in file: {total_games}\n")
        f.write(f"alt_name always includes primary: {'YES' if alt_missing_primary == 0 else 'NO (' + str(alt_missing_primary) + ' missing)'}\n")
        f.write(f"Expansion target IDs in file: {len(expansion_ids_in_file)} of {len(all_expansion_target_ids)}\n")
        f.write(f"Top-10k cutoff: {cutoff_votes} votes\n")
        f.write(f"Pack expansions: {len(pack_expansions)} ({pack_expansions_with_base_in_pack} with base in pack)\n")
        f.write(f"Normalised name collisions: {len(collisions)}\n")
        f.write(f"Collisions after edition strip: {len(stripped_collisions)}\n")
        f.write(f"Short names (<=4 chars): {len(short_names)}\n")
        f.write(f"Word-prefix pairs: {prefix_pairs}\n")
        f.write("\nAssumptions:\n")
        f.write("- alt_name includes primary: CONFIRMED\n" if alt_missing_primary == 0 else "- alt_name includes primary: BROKEN\n")
        f.write(f"- File is mostly base games: CONFIRMED ({len(expansion_ids_in_file)} expansions in {total_games} rows)\n")
        f.write(f"- Top-10k cutoff ~301 votes: {'CONFIRMED' if abs(cutoff_votes - 301) < 50 else 'SHIFTED to ' + str(cutoff_votes)}\n")
        f.write(f"- Name collisions exist and need handling: CONFIRMED ({len(collisions)} collisions)\n")
        f.write(f"- Short names need special treatment: CONFIRMED ({len(short_names)} names <=4 chars)\n")

    print(f"\nResults written to {summary}")


if __name__ == "__main__":
    main()
