/**
 * Seeds the local D1 with the schema and hand-invented fixture rows
 * (never real dump rows). The games mirror fixtures/match-cases.json plus
 * a few extras for the /v1/games tests.
 */
import { env } from "cloudflare:test";
import { normalize } from "../src/normalize";
import schema from "../schema.sql?raw";
import matchCases from "../../fixtures/match-cases.json";

const FIXTURE_GAMES: {
  bgg_id: number;
  name: string;
  year?: number | null;
  num_votes: number;
  names: { name: string; is_primary: boolean }[];
}[] = matchCases.games;

export async function sha256Hex(bytes: Uint8Array): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", bytes.slice().buffer);
  return [...new Uint8Array(digest)]
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

export async function seed(): Promise<void> {
  for (const stmt of schema.split(";")) {
    const sql = stmt.trim();
    if (sql) await env.DB.prepare(sql).run();
  }
  await env.DB.prepare("DELETE FROM names").run();
  await env.DB.prepare("DELETE FROM tags").run();
  await env.DB.prepare("DELETE FROM games").run();
  await env.DB.prepare("DELETE FROM pack_meta").run();
  await env.DB.prepare("DELETE FROM pack_chunks").run();

  for (const g of FIXTURE_GAMES) {
    await env.DB.prepare(
      `INSERT INTO games (bgg_id, name, name_norm, year, num_votes, min_players,
                          max_players, complexity, avg_rating, rank, description)
       VALUES (?, ?, ?, ?, ?, 2, 4, 2.5, 7.2, 100, ?)`,
    )
      .bind(
        g.bgg_id,
        g.name,
        normalize(g.name),
        g.year ?? null,
        g.num_votes,
        `Description of ${g.name}`,
      )
      .run();
    for (const n of g.names) {
      await env.DB.prepare(
        "INSERT INTO names (bgg_id, name, name_norm, is_primary) VALUES (?, ?, ?, ?)",
      )
        .bind(g.bgg_id, n.name, normalize(n.name), n.is_primary ? 1 : 0)
        .run();
    }
    await env.DB.prepare(
      "INSERT INTO tags (bgg_id, kind, name) VALUES (?, 'mechanic', 'Tile Placement'), (?, 'category', 'Family')",
    )
      .bind(g.bgg_id, g.bgg_id)
      .run();
  }
}

export async function seedPack(gzipBytes: Uint8Array): Promise<void> {
  await env.DB.prepare("INSERT OR REPLACE INTO pack_meta VALUES ('sha256', ?)")
    .bind(await sha256Hex(gzipBytes))
    .run();
  // Split into two chunks to exercise reassembly
  const half = Math.ceil(gzipBytes.length / 2);
  await env.DB.prepare("INSERT OR REPLACE INTO pack_chunks VALUES (0, ?)")
    .bind(gzipBytes.slice(0, half).buffer)
    .run();
  await env.DB.prepare("INSERT OR REPLACE INTO pack_chunks VALUES (1, ?)")
    .bind(gzipBytes.slice(half).buffer)
    .run();
}

export const AUTH = { Authorization: "Bearer test-token" };
