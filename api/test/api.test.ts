import { createExecutionContext, env, waitOnExecutionContext } from "cloudflare:test";
import { beforeAll, describe, expect, it } from "vitest";
import worker from "../src/index";
import { AUTH, seed, seedPack, sha256Hex } from "./seed";

async function call(
  path: string,
  init: RequestInit = {},
  headers: Record<string, string> = AUTH,
): Promise<Response> {
  const request = new Request(`https://api.test${path}`, {
    ...init,
    headers: { ...headers, ...(init.headers ?? {}) },
  });
  const ctx = createExecutionContext();
  const response = await worker.fetch(request, env, ctx);
  await waitOnExecutionContext(ctx);
  return response;
}

function matchCall(texts: unknown): Promise<Response> {
  return call("/v1/match", {
    method: "POST",
    body: JSON.stringify({ texts }),
    headers: { "Content-Type": "application/json" },
  });
}

beforeAll(async () => {
  await seed();
});

describe("auth", () => {
  it("rejects a missing token", async () => {
    const res = await call("/v1/games?ids=1", {}, {});
    expect(res.status).toBe(401);
  });

  it("rejects a wrong token", async () => {
    const res = await call("/v1/games?ids=1", {}, { Authorization: "Bearer nope" });
    expect(res.status).toBe(401);
  });
});

describe("POST /v1/match", () => {
  it("matches an alternate foreign-language title to the right game", async () => {
    // "Die Hafenmeister" is only an alternate name — never in the phone's
    // names-only pack — and must still find Harbour Masters (id 1).
    const res = await matchCall(["Die Hafenmeister"]);
    expect(res.status).toBe(200);
    const body = (await res.json()) as any;
    expect(body.results).toHaveLength(1);
    expect(body.results[0].text).toBe("Die Hafenmeister");
    expect(body.results[0].candidates[0].ids).toContain(1);
  });

  it("returns top 3 candidates with scores", async () => {
    const res = await matchCall(["harbour"]);
    const body = (await res.json()) as any;
    const candidates = body.results[0].candidates;
    expect(candidates.length).toBeLessThanOrEqual(3);
    expect(candidates.length).toBeGreaterThan(0);
    for (const c of candidates) {
      expect(c.score).toBeGreaterThan(0);
      expect(c.score).toBeLessThanOrEqual(1);
      expect(c.ids.length).toBeGreaterThan(0);
    }
  });

  it("handles a batch of texts", async () => {
    const res = await matchCall(["Harbour Masters", "Quiet Meadow", "zzz qq xx"]);
    const body = (await res.json()) as any;
    expect(body.results).toHaveLength(3);
    expect(body.results[0].candidates[0].ids).toContain(1);
    expect(body.results[1].candidates[0].ids).toContain(4);
  });

  it("rejects 51 texts", async () => {
    const res = await matchCall(Array(51).fill("x"));
    expect(res.status).toBe(400);
  });

  it("accepts exactly 50 texts", async () => {
    const res = await matchCall(Array(50).fill("harbour"));
    expect(res.status).toBe(200);
  });

  it("rejects an over-long text", async () => {
    const res = await matchCall(["x".repeat(201)]);
    expect(res.status).toBe(400);
  });

  it("handles punctuation-only and empty texts harmlessly", async () => {
    const res = await matchCall(["!!!", "", "¿¿¿"]);
    expect(res.status).toBe(200);
    const body = (await res.json()) as any;
    expect(body.results).toHaveLength(3);
  });

  it("rejects a malformed body", async () => {
    const res = await call("/v1/match", { method: "POST", body: "not json{{" });
    expect(res.status).toBe(400);
  });

  it("rejects texts that are not strings", async () => {
    const res = await matchCall([1, 2, 3]);
    expect(res.status).toBe(400);
  });

  it("rejects a body without texts", async () => {
    const res = await call("/v1/match", { method: "POST", body: "{}" });
    expect(res.status).toBe(400);
  });
});

describe("GET /v1/games", () => {
  it("returns full records including description, names and tags", async () => {
    const res = await call("/v1/games?ids=1,3");
    expect(res.status).toBe(200);
    const body = (await res.json()) as any;
    expect(body.games).toHaveLength(2);
    const g = body.games[0];
    expect(g.bgg_id).toBe(1);
    expect(g.name).toBe("Harbour Masters");
    expect(g.description).toContain("Harbour Masters");
    expect(g.names.map((n: any) => n.name)).toContain("Les Maîtres du Port");
    expect(g.mechanics).toContain("Tile Placement");
    expect(g.categories).toContain("Family");
    expect(g.min_players).toBe(2);
  });

  it("skips unknown ids", async () => {
    const res = await call("/v1/games?ids=1,999999");
    const body = (await res.json()) as any;
    expect(body.games).toHaveLength(1);
  });

  it("rejects 51 ids", async () => {
    const ids = Array.from({ length: 51 }, (_, i) => i + 1).join(",");
    const res = await call(`/v1/games?ids=${ids}`);
    expect(res.status).toBe(400);
  });

  it("rejects malformed ids", async () => {
    for (const bad of ["abc", "1,,2", "1,-5", "1,2.5", ""]) {
      const res = await call(`/v1/games?ids=${bad}`);
      expect(res.status, `ids=${bad}`).toBe(400);
    }
  });
});

describe("GET /v1/pack", () => {
  it("404s when no pack is loaded", async () => {
    const res = await call("/v1/pack");
    expect(res.status).toBe(404);
  });

  it("serves the pack with its checksum, reassembled from chunks", async () => {
    const bytes = crypto.getRandomValues(new Uint8Array(1500));
    await seedPack(bytes);

    const res = await call("/v1/pack");
    expect(res.status).toBe(200);

    const body = new Uint8Array(await res.arrayBuffer());
    expect(body).toEqual(bytes);
    expect(res.headers.get("X-Pack-Sha256")).toBe(await sha256Hex(body));
  });
});

describe("unknown route", () => {
  it("404s", async () => {
    const res = await call("/v1/nope");
    expect(res.status).toBe(404);
  });
});
