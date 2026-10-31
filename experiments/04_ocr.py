#!/usr/bin/env python3
"""Experiment 4: OCR — PP-OCR via rapidocr-onnxruntime.

Tests text recognition on:
  A) Rendered sideways spine text (synthetic)
  B) Downloaded box-cover images (real, if available)

Checks: rotation handling, speed, read quality, and whether reads
reach green/orange through the trigram index.

Usage:
    uv run python 04_ocr.py ../board-game-data/scraped/bgg_GameItem.jl

Box-cover images (optional): place JPGs in experiments/covers/ (gitignored).
If absent, only synthetic tests run.
"""

import json
import os
import random
import sys
import time
from pathlib import Path

from normalize import normalize

try:
    from rapidocr_onnxruntime import RapidOCR
except ImportError:
    print("ERROR: rapidocr-onnxruntime not installed. Run: uv sync")
    sys.exit(1)

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    print("ERROR: Pillow not installed. Run: uv sync")
    sys.exit(1)


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
        from collections import Counter
        query_tris = trigrams(query)
        query_tri_set = set(query_tris)
        if not query_tri_set:
            return []
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


def render_spine_text(text: str, rotation: int = 0, font_size: int = 28) -> Image.Image:
    """Render text as it might appear on a game spine.

    rotation: 0 = horizontal, 90 = rotated CW, 270 = rotated CCW
    """
    # Use a default font (no external font file needed)
    try:
        font = ImageFont.truetype("/usr/share/fonts/TTF/DejaVuSans.ttf", font_size)
    except (OSError, IOError):
        try:
            font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", font_size)
        except (OSError, IOError):
            font = ImageFont.load_default()

    # Measure text
    dummy = Image.new("RGB", (1, 1))
    draw = ImageDraw.Draw(dummy)
    bbox = draw.textbbox((0, 0), text, font=font)
    text_w = bbox[2] - bbox[0] + 20
    text_h = bbox[3] - bbox[1] + 20

    # Draw text horizontally first
    img = Image.new("RGB", (text_w, text_h), color=(245, 240, 230))
    draw = ImageDraw.Draw(img)
    draw.text((10, 5), text, fill=(30, 30, 30), font=font)

    # Rotate if needed
    if rotation == 90:
        img = img.rotate(-90, expand=True)
    elif rotation == 270:
        img = img.rotate(90, expand=True)

    return img


def main():
    if len(sys.argv) < 2:
        print("Usage: uv run python 04_ocr.py <path-to-bgg_GameItem.jl>")
        sys.exit(1)

    data_path = sys.argv[1]
    out_dir = Path("out")
    out_dir.mkdir(exist_ok=True)
    covers_dir = Path("covers")
    rng = random.Random(42)

    # Load top-10k for index
    print("Loading pack...")
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
    pack_ids = {g["bgg_id"] for g in pack}

    # Build index
    tri_index = TrigramIndex()
    for g in pack:
        for name in g["alt_names"]:
            norm = normalize(name)
            if norm:
                tri_index.add(g["bgg_id"], name, norm, g["num_votes"])

    # Init OCR
    print("Initializing RapidOCR...")
    ocr = RapidOCR()

    # Sample titles for synthetic tests
    sample = rng.sample(pack, min(50, len(pack)))
    tune_sample = sample[:30]
    holdout_sample = sample[30:]

    green_threshold = 0.9
    lead_threshold = 0.1
    orange_threshold = 0.6

    results_all = []

    for label, test_sample in [("tune", tune_sample), ("holdout", holdout_sample)]:
        print(f"\n{'=' * 60}")
        print(f"  SYNTHETIC SPINE TEXT — {label.upper()} ({len(test_sample)} titles)")
        print(f"{'=' * 60}")

        for rotation in [0, 90, 270]:
            rot_label = f"rot_{rotation}"
            correct_reads = 0
            green_correct = 0
            orange_correct = 0
            green_wrong = 0
            total = 0
            total_time = 0.0

            for g in test_sample:
                title = g["name"]
                expected_id = g["bgg_id"]
                total += 1

                img = render_spine_text(title, rotation=rotation)

                t0 = time.perf_counter()
                result, _ = ocr(img)
                elapsed = time.perf_counter() - t0
                total_time += elapsed

                if not result:
                    continue

                # Concatenate all detected text
                ocr_text = " ".join(line[1] for line in result)
                norm_read = normalize(ocr_text)

                if not norm_read:
                    continue

                # Check if OCR read matches the title
                norm_title = normalize(title)
                if norm_read == norm_title:
                    correct_reads += 1

                # Look up in index
                matches = tri_index.lookup(norm_read, 5)
                if matches:
                    top_id, top_name, top_score = matches[0]
                    runner_score = matches[1][2] if len(matches) > 1 else 0.0
                    lead = top_score - runner_score

                    if top_score >= green_threshold and lead >= lead_threshold:
                        if top_id == expected_id:
                            green_correct += 1
                        else:
                            green_wrong += 1
                            print(f"    WRONG GREEN: '{title}' → OCR '{ocr_text}' → matched '{top_name}' (score={top_score:.3f})")
                    elif top_score >= orange_threshold:
                        if top_id == expected_id:
                            orange_correct += 1

            avg_ms = (total_time / total * 1000) if total > 0 else 0
            print(f"\n  Rotation {rotation}°:")
            print(f"    Exact OCR reads: {correct_reads}/{total} ({correct_reads/total*100:.1f}%)")
            print(f"    Green correct: {green_correct}/{total} ({green_correct/total*100:.1f}%)")
            print(f"    Green WRONG: {green_wrong}/{total}")
            print(f"    Orange correct: {orange_correct}/{total} ({orange_correct/total*100:.1f}%)")
            print(f"    Avg OCR time: {avg_ms:.0f} ms")
            results_all.append(f"{label} rot{rotation}: exact={correct_reads}/{total}, green_ok={green_correct}, green_wrong={green_wrong}, orange_ok={orange_correct}, avg={avg_ms:.0f}ms")

    # Box cover images (optional)
    if covers_dir.exists() and list(covers_dir.glob("*.jpg")) + list(covers_dir.glob("*.png")):
        cover_files = sorted(covers_dir.glob("*.jpg")) + sorted(covers_dir.glob("*.png"))
        print(f"\n{'=' * 60}")
        print(f"  BOX COVER IMAGES ({len(cover_files)} files)")
        print(f"{'=' * 60}")

        for img_path in cover_files[:30]:
            img = Image.open(img_path)
            t0 = time.perf_counter()
            result, _ = ocr(img)
            elapsed = time.perf_counter() - t0

            if result:
                texts = [line[1] for line in result]
                print(f"  {img_path.name}: {elapsed*1000:.0f}ms → {texts[:5]}")
            else:
                print(f"  {img_path.name}: {elapsed*1000:.0f}ms → (no text)")
    else:
        print(f"\nNo box covers in {covers_dir}/ — skipping. Place JPGs there to test.")

    # Write summary
    summary = out_dir / "04_ocr.txt"
    with open(summary, "w") as f:
        f.write("OCR — Experiment 4\n")
        f.write("=" * 40 + "\n\n")
        for line in results_all:
            f.write(f"  {line}\n")
        f.write("\nAssumptions:\n")
        f.write("- PP-OCR reads horizontal text well: [RESULT]\n")
        f.write("- PP-OCR handles 90°/270° rotation: [RESULT]\n")
        f.write("- OCR speed is acceptable for per-frame use: [RESULT]\n")
        f.write("- OCR reads feed through lookup to green: [RESULT]\n")

    print(f"\nResults written to {summary}")


if __name__ == "__main__":
    main()
