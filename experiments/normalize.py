"""Name normalisation — single definition shared with Kotlin (via fixture file)."""

import re
import unicodedata

# Edition words set aside during matching, not dropped from display.
# Kept sorted for binary search in Kotlin; tested via shared fixture.
EDITION_WORDS = sorted([
    "anniversary", "classic", "collector", "collectors", "deluxe",
    "edition", "essential", "giant", "limited", "master", "mega",
    "mini", "pocket", "premium", "revised", "second", "third",
    "travel", "ultimate",
])

_PUNCT_RE = re.compile(r"[^\w\s]", re.UNICODE)
_MULTI_SPACE_RE = re.compile(r"\s+")


def normalize(name: str) -> str:
    """Normalise a game name for matching.

    Steps:
    1. Unicode NFKD decomposition, strip combining marks (accents)
    2. Lowercase
    3. Remove all punctuation (keep letters, digits, whitespace)
    4. Collapse whitespace
    5. Strip leading/trailing whitespace

    Edition words are NOT removed here — the caller sets them aside
    when needed (so "Catan: Deluxe Edition" normalises to
    "catan deluxe edition" and the caller can split off "deluxe edition").
    """
    # NFKD decompose, drop combining marks
    decomposed = unicodedata.normalize("NFKD", name)
    without_accents = "".join(
        ch for ch in decomposed if not unicodedata.combining(ch)
    )
    lower = without_accents.lower()
    no_punct = _PUNCT_RE.sub(" ", lower)
    collapsed = _MULTI_SPACE_RE.sub(" ", no_punct).strip()
    return collapsed


def strip_edition_words(normalized: str) -> tuple[str, list[str]]:
    """Remove edition words from a normalised name.

    Returns (name_without_edition_words, list_of_found_edition_words).
    """
    words = normalized.split()
    found = []
    kept = []
    for w in words:
        if w in EDITION_WORDS:
            found.append(w)
        else:
            kept.append(w)
    return " ".join(kept), found
