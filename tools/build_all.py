#!/usr/bin/env python3
"""One command to build games.db and the pack.

Usage:
    uv run python -I build_all.py <path-to-bgg_GameItem.jl> [--extras extras.txt]

Outputs games.db and pack.db in the project root (parent of tools/).
"""

import subprocess
import sys
from pathlib import Path

TOOLS_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = TOOLS_DIR.parent


def main():
    if len(sys.argv) < 2:
        print("Usage: uv run python -I build_all.py <bgg_GameItem.jl> [--extras extras.txt]")
        sys.exit(1)

    data_path = sys.argv[1]
    db_path = str(PROJECT_ROOT / "games.db")
    pack_path = str(PROJECT_ROOT / "pack.db")

    extras_args = []
    if "--extras" in sys.argv:
        idx = sys.argv.index("--extras")
        if idx + 1 < len(sys.argv):
            extras_args = ["--extras", sys.argv[idx + 1]]

    # Build games.db
    print("=" * 60)
    print("  Step 1: Building games.db")
    print("=" * 60)
    result = subprocess.run(
        [sys.executable, "-I", str(TOOLS_DIR / "build_db.py"), data_path, db_path],
        cwd=str(TOOLS_DIR),
    )
    if result.returncode != 0:
        sys.exit(result.returncode)

    # Build pack
    print()
    print("=" * 60)
    print("  Step 2: Building pack.db")
    print("=" * 60)
    result = subprocess.run(
        [sys.executable, "-I", str(TOOLS_DIR / "build_pack.py"), db_path, pack_path] + extras_args,
        cwd=str(TOOLS_DIR),
    )
    if result.returncode != 0:
        sys.exit(result.returncode)

    print()
    print("=" * 60)
    print("  Done!")
    print("=" * 60)


if __name__ == "__main__":
    main()
