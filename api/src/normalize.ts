/**
 * Name normalisation — TypeScript port of core/Normalize.kt (which ports
 * tools/normalize.py). Single definition, tested against the shared fixture
 * file fixtures/normalize.csv.
 *
 * Steps:
 * 1. Unicode NFKD decomposition, strip combining marks (accents)
 * 2. Lowercase
 * 3. Remove all punctuation (keep letters, digits, whitespace)
 * 4. Collapse whitespace
 * 5. Strip leading/trailing whitespace
 */

/** Edition words set aside during matching, not dropped from display. */
export const EDITION_WORDS = new Set([
  "anniversary", "classic", "collector", "collectors", "deluxe",
  "edition", "essential", "giant", "limited", "master", "mega",
  "mini", "pocket", "premium", "revised", "second", "third",
  "travel", "ultimate",
]);

// \w and \s here are ASCII-oriented, matching the Kotlin matcher the phone
// runs — parity with the phone wins over parity with Python.
const PUNCT_RE = /[^\w\s]/g;
const MULTI_SPACE_RE = /\s+/g;
const COMBINING_RE = /\p{Mn}/gu;

export function normalize(name: string): string {
  const decomposed = name.normalize("NFKD").replace(COMBINING_RE, "");
  return decomposed
    .toLowerCase()
    .replace(PUNCT_RE, " ")
    .replace(MULTI_SPACE_RE, " ")
    .trim();
}

/** Remove edition words from a normalised name. Returns [stripped, found]. */
export function stripEditionWords(normalized: string): [string, string[]] {
  const kept: string[] = [];
  const found: string[] = [];
  for (const w of normalized.split(" ")) {
    if (EDITION_WORDS.has(w)) found.push(w);
    else kept.push(w);
  }
  return [kept.join(" "), found];
}
