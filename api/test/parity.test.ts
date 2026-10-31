/**
 * The TypeScript matcher must agree with the Kotlin matcher's top candidate
 * on the shared fixtures (fixtures/match-cases.json, also run by
 * core MatchParityTest), and normalisation must agree with
 * fixtures/normalize.csv (also run in Kotlin and Python).
 */
import { describe, expect, it } from "vitest";
import matchCases from "../../fixtures/match-cases.json";
import normalizeCsv from "../../fixtures/normalize.csv?raw";
import { normalize, stripEditionWords } from "../src/normalize";
import { TrigramIndex } from "../src/trigram";

describe("match parity with the Kotlin matcher", () => {
  const index = new TrigramIndex();
  for (const game of matchCases.games) {
    for (const n of game.names) {
      const norm = normalize(n.name);
      const [stripped] = stripEditionWords(norm);
      index.add(game.bgg_id, n.name, norm, stripped, game.num_votes);
    }
  }

  for (const c of matchCases.cases) {
    it(`top candidate for "${c.query}" is ${c.top_bgg_id}`, () => {
      const results = index.lookup(normalize(c.query));
      expect(results.length).toBeGreaterThan(0);
      expect(results[0].ids).toContain(c.top_bgg_id);
    });
  }
});

describe("normalisation parity with the shared fixture", () => {
  // Minimal CSV parsing: fields are quoted only when they contain commas.
  function parseLine(line: string): string[] {
    const fields: string[] = [];
    let cur = "";
    let inQuotes = false;
    for (let i = 0; i < line.length; i++) {
      const ch = line[i];
      if (inQuotes) {
        if (ch === '"') inQuotes = false;
        else cur += ch;
      } else if (ch === '"') {
        inQuotes = true;
      } else if (ch === ",") {
        fields.push(cur);
        cur = "";
      } else {
        cur += ch;
      }
    }
    fields.push(cur);
    return fields;
  }

  const lines = normalizeCsv.trim().split("\n").slice(1);
  for (const line of lines) {
    const [input, expectedNorm, expectedStripped, editionWords] = parseLine(line);
    it(`normalises "${input}"`, () => {
      const norm = normalize(input);
      expect(norm).toBe(expectedNorm);
      const [stripped, found] = stripEditionWords(norm);
      expect(stripped).toBe(expectedStripped);
      expect(found.join(" ")).toBe(editionWords ?? "");
    });
  }
});
