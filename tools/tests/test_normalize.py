"""Test normalisation against the shared fixture file."""

import csv
from pathlib import Path

import pytest

# Add parent to path for normalize import
import sys
sys.path.insert(0, str(Path(__file__).parent.parent))

from normalize import normalize, strip_edition_words

FIXTURE_PATH = Path(__file__).parent.parent.parent / "fixtures" / "normalize.csv"


def load_fixture() -> list[dict]:
    with open(FIXTURE_PATH) as f:
        return list(csv.DictReader(f))


@pytest.fixture
def fixture_rows():
    return load_fixture()


def test_fixture_file_exists():
    assert FIXTURE_PATH.exists(), f"Fixture file not found: {FIXTURE_PATH}"


@pytest.mark.parametrize("row", load_fixture(), ids=lambda r: r["input"])
def test_normalize(row):
    assert normalize(row["input"]) == row["normalized"]


@pytest.mark.parametrize("row", load_fixture(), ids=lambda r: r["input"])
def test_strip_edition_words(row):
    norm = normalize(row["input"])
    stripped, found = strip_edition_words(norm)
    assert stripped == row["stripped"]
    expected_words = row["edition_words"].split() if row["edition_words"] else []
    assert sorted(found) == sorted(expected_words)
