/**
 * In-memory trigram index — TypeScript port of core/TrigramIndex.kt.
 *
 * Jaccard similarity on space-padded character trigrams with popularity
 * tiebreak; games sharing a normalised (edition-stripped) title count as one
 * candidate group. The contract with the phone is agreement on the top
 * candidate (see docs/decisions.md), pinned by fixtures/match-cases.json.
 */

export interface Candidate {
  ids: number[];
  name: string;
  score: number;
}

interface Entry {
  bggId: number;
  rawName: string;
  normStripped: string;
  popularity: number;
  trigrams: Set<string>;
}

interface NameGroup {
  bggIds: number[];
  bestName: string;
  bestPopularity: number;
}

export function trigramSet(s: string): Set<string> {
  const padded = `  ${s} `;
  const set = new Set<string>();
  for (let i = 0; i < padded.length - 2; i++) {
    set.add(padded.substring(i, i + 3));
  }
  return set;
}

export class TrigramIndex {
  private entries: Entry[] = [];
  private index = new Map<string, number[]>();
  private groups = new Map<string, NameGroup>();

  add(
    bggId: number,
    rawName: string,
    normName: string,
    normStripped: string,
    popularity: number,
  ): void {
    const idx = this.entries.length;
    const trigrams = trigramSet(normName);
    this.entries.push({ bggId, rawName, normStripped, popularity, trigrams });

    for (const tri of trigrams) {
      let list = this.index.get(tri);
      if (!list) this.index.set(tri, (list = []));
      list.push(idx);
    }

    let group = this.groups.get(normStripped);
    if (!group) {
      this.groups.set(
        normStripped,
        (group = { bggIds: [], bestName: "", bestPopularity: 0 }),
      );
    }
    if (!group.bggIds.includes(bggId)) group.bggIds.push(bggId);
    if (popularity > group.bestPopularity) {
      group.bestPopularity = popularity;
      group.bestName = rawName;
    }
  }

  get size(): number {
    return this.entries.length;
  }

  lookup(query: string, topN = 5): Candidate[] {
    const queryTris = trigramSet(query);
    if (queryTris.size === 0) return [];
    const querySize = queryTris.size;

    // Count shared trigrams per entry. Note: ordering among entries with
    // EQUAL shared counts is not guaranteed to match Kotlin's (its trigram
    // set is a HashSet with arbitrary iteration order), so the take(topN*5)
    // cutoff below can differ across implementations on ties.
    const counts = new Map<number, number>();
    for (const tri of queryTris) {
      const list = this.index.get(tri);
      if (!list) continue;
      for (const idx of list) {
        counts.set(idx, (counts.get(idx) ?? 0) + 1);
      }
    }

    // Score top candidates, deduplicate by normStripped
    const seen = new Map<
      string,
      { score: number; bestName: string; popularity: number }
    >();
    const limit = topN * 5;
    const topEntries = [...counts.entries()]
      .sort((a, b) => b[1] - a[1])
      .slice(0, limit);

    for (const [idx, shared] of topEntries) {
      const entry = this.entries[idx];
      const union = querySize + entry.trigrams.size - shared;
      const score = union > 0 ? shared / union : 0;

      const existing = seen.get(entry.normStripped);
      if (
        !existing ||
        score > existing.score ||
        (score === existing.score && entry.popularity > existing.popularity)
      ) {
        const group = this.groups.get(entry.normStripped);
        seen.set(entry.normStripped, {
          score,
          bestName: group?.bestName ?? entry.rawName,
          popularity: group?.bestPopularity ?? entry.popularity,
        });
      }
    }

    return [...seen.entries()]
      .sort(
        (a, b) =>
          b[1].score - a[1].score || b[1].popularity - a[1].popularity,
      )
      .slice(0, topN)
      .map(([normStripped, scored]) => ({
        ids: this.groups.get(normStripped)?.bggIds.slice() ?? [],
        name: scored.bestName,
        score: scored.score,
      }));
  }
}
