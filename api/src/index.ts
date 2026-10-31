/**
 * The private game-data API: fuzzy name matching, full game records and the
 * on-device pack, all built from the same games.db as the phone and harness.
 *
 * Auth: one shared bearer token (secret API_TOKEN) for now; per-device
 * enrolment comes later.
 */
import { matchIndex } from "./match";
import { normalize } from "./normalize";

export interface Env {
  DB: D1Database;
  API_TOKEN: string;
}

export const MAX_TEXTS = 50;
export const MAX_TEXT_LENGTH = 200;
export const MAX_IDS = 50;
export const MATCH_TOP_N = 3;

export default {
  async fetch(request: Request, env: Env, _ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);

    const auth = request.headers.get("Authorization");
    if (!env.API_TOKEN || !(await tokenMatches(auth, env.API_TOKEN))) {
      return error(401, "Invalid or missing token");
    }

    try {
      if (url.pathname === "/v1/match" && request.method === "POST") {
        return await handleMatch(request, env);
      }
      if (url.pathname === "/v1/games" && request.method === "GET") {
        return await handleGames(url, env);
      }
      if (url.pathname === "/v1/pack" && request.method === "GET") {
        return await handlePack(env);
      }
      return error(404, "Not found");
    } catch (e) {
      console.log(JSON.stringify({ level: "error", path: url.pathname, message: String(e) }));
      return error(500, "Internal error");
    }
  },
} satisfies ExportedHandler<Env>;

function error(status: number, message: string): Response {
  return Response.json({ error: message }, { status });
}

/** Constant-time token check (compare digests: equal length, no early exit). */
async function tokenMatches(auth: string | null, token: string): Promise<boolean> {
  if (!auth) return false;
  const enc = new TextEncoder();
  const [a, b] = await Promise.all([
    crypto.subtle.digest("SHA-256", enc.encode(auth)),
    crypto.subtle.digest("SHA-256", enc.encode(`Bearer ${token}`)),
  ]);
  return crypto.subtle.timingSafeEqual(a, b);
}

async function handleMatch(request: Request, env: Env): Promise<Response> {
  let texts: unknown;
  try {
    const body = (await request.json()) as { texts?: unknown };
    texts = body.texts;
  } catch {
    return error(400, "Body must be JSON");
  }
  if (
    !Array.isArray(texts) ||
    texts.some((t) => typeof t !== "string")
  ) {
    return error(400, "Body must be {\"texts\": [string, ...]}");
  }
  const list = texts as string[];
  if (list.length > MAX_TEXTS) {
    return error(400, `At most ${MAX_TEXTS} texts per call`);
  }
  if (list.some((t) => t.length > MAX_TEXT_LENGTH)) {
    return error(400, `Texts are capped at ${MAX_TEXT_LENGTH} characters`);
  }

  const index = await matchIndex(env.DB);
  // Look up with the same topN (and so the same scoring pool, topN*5) as the
  // phone's default lookup, then trim: parity on the top candidate requires
  // identical pools, not just identical scoring.
  const results = list.map((text) => ({
    text,
    candidates: index.lookup(normalize(text)).slice(0, MATCH_TOP_N),
  }));
  return Response.json({ results });
}

async function handleGames(url: URL, env: Env): Promise<Response> {
  const idsParam = url.searchParams.get("ids") ?? "";
  const parts = idsParam.split(",");
  if (idsParam.length === 0 || parts.some((s) => s.length === 0)) {
    return error(400, "ids must be a comma-separated list of integers");
  }
  if (parts.length > MAX_IDS) return error(400, `At most ${MAX_IDS} ids per call`);
  const ids = parts.map((s) => Number(s));
  if (ids.some((n) => !Number.isInteger(n) || n <= 0)) {
    return error(400, "ids must be positive integers");
  }

  const marks = ids.map(() => "?").join(",");
  const [games, names, tags] = await Promise.all([
    env.DB.prepare(`SELECT * FROM games WHERE bgg_id IN (${marks})`)
      .bind(...ids)
      .all<Record<string, unknown>>(),
    env.DB.prepare(
      `SELECT bgg_id, name, is_primary FROM names WHERE bgg_id IN (${marks})`,
    )
      .bind(...ids)
      .all<{ bgg_id: number; name: string; is_primary: number }>(),
    env.DB.prepare(
      `SELECT bgg_id, kind, name FROM tags WHERE bgg_id IN (${marks})`,
    )
      .bind(...ids)
      .all<{ bgg_id: number; kind: string; name: string }>(),
  ]);

  const byId = new Map<number, Record<string, unknown>>();
  for (const g of games.results) {
    byId.set(g.bgg_id as number, { ...g, names: [], mechanics: [], categories: [] });
  }
  for (const n of names.results) {
    const g = byId.get(n.bgg_id);
    if (g) (g.names as unknown[]).push({ name: n.name, is_primary: n.is_primary === 1 });
  }
  for (const t of tags.results) {
    const g = byId.get(t.bgg_id);
    if (!g) continue;
    if (t.kind === "mechanic") (g.mechanics as string[]).push(t.name);
    else if (t.kind === "category") (g.categories as string[]).push(t.name);
  }

  return Response.json({ games: ids.flatMap((id) => byId.get(id) ?? []) });
}

async function handlePack(env: Env): Promise<Response> {
  const meta = await env.DB.prepare("SELECT key, value FROM pack_meta").all<{
    key: string;
    value: string;
  }>();
  const kv = new Map(meta.results.map((r) => [r.key, r.value]));
  // The sha256 of the gzipped pack doubles as its content version.
  const sha256 = kv.get("sha256");
  if (!sha256) return error(404, "No pack loaded");

  const chunks = await env.DB.prepare(
    "SELECT data FROM pack_chunks ORDER BY seq",
  ).all<{ data: number[] }>();
  if (chunks.results.length === 0) return error(404, "No pack loaded");

  const parts = chunks.results.map((r) => new Uint8Array(r.data));
  const total = parts.reduce((n, p) => n + p.length, 0);
  const body = new Uint8Array(total);
  let offset = 0;
  for (const p of parts) {
    body.set(p, offset);
    offset += p.length;
  }

  return new Response(body, {
    headers: {
      "Content-Type": "application/gzip",
      "X-Pack-Sha256": sha256,
    },
  });
}
