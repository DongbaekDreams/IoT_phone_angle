#!/usr/bin/env python3
"""Aggregate a federated round from files in the repo (laptop-side alternative to the in-app button).

Typical use after phones have pushed updates through the GitHub transport:

    git pull
    python tools/aggregate_from_git.py            # aggregates latest+1
    python tools/aggregate_from_git.py --commit   # ...and commits + pushes global.json / latest.json

Updates are FedAvg-averaged weighted by each client's sampleCount. Incompatible updates
(featureDim, classes, sensorConfig, preprocessingVersion) are rejected.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "server"))

import fedlib  # noqa: E402


def git(*args: str) -> None:
    subprocess.run(["git", "-C", str(REPO), *args], check=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--round", type=int, help="round to aggregate (default: latest + 1)")
    parser.add_argument("--commit", action="store_true", help="git add/commit/push the result")
    parser.add_argument("--root", type=Path, default=REPO)
    args = parser.parse_args()

    root = args.root.resolve()
    before = fedlib.latest_round(root)
    try:
        result = fedlib.aggregate_round(root, args.round)
    except fedlib.FedError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    print(
        f"round {result['round']}: aggregated {len(result['contributors'])} client(s) "
        f"{result['contributors']} ({result['sampleCount']} samples). latest was {before}."
    )
    if args.commit:
        git("add", "federation")
        git("commit", "-m", f"federation: round {result['round']} global")
        git("push")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
