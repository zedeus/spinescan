/**
 * Builds the in-memory match index from D1 on first use and caches it for
 * the isolate's lifetime (see docs/decisions.md: lazy load, revisit if cold
 * starts hurt).
 */
import { stripEditionWords } from "./normalize";
import { TrigramIndex } from "./trigram";

let cached: { db: D1Database; index: Promise<TrigramIndex> } | null = null;

export function matchIndex(db: D1Database): Promise<TrigramIndex> {
  if (cached && cached.db === db) return cached.index;
  const index = buildIndex(db);
  cached = { db, index };
  // Never cache a failed build: a transient D1 error would otherwise
  // 500 every match call for the rest of the isolate's life.
  index.catch(() => {
    if (cached?.index === index) cached = null;
  });
  return index;
}

async function buildIndex(db: D1Database): Promise<TrigramIndex> {
  const index = new TrigramIndex();
  const { results } = await db
    .prepare(
      `SELECT n.bgg_id AS bgg_id, n.name AS name, n.name_norm AS name_norm,
              g.num_votes AS num_votes
       FROM names n JOIN games g ON g.bgg_id = n.bgg_id`,
    )
    .all<{ bgg_id: number; name: string; name_norm: string; num_votes: number }>();
  for (const row of results) {
    const [stripped] = stripEditionWords(row.name_norm);
    index.add(row.bgg_id, row.name, row.name_norm, stripped, row.num_votes ?? 0);
  }
  return index;
}
