#!/usr/bin/env python3
"""Fail if the bundled Material Icon set and the icons the code references have diverged.

Roadguard depends only on `material-icons-core` and ships every other symbol as a vector drawable
fetched from Google's official set. That is only cheaper than `material-icons-extended` if the
bundled set stays trimmed to what is actually used, and it is only *correct* if every referenced
icon is actually bundled -- a missing drawable is a crash at inflate time, not a compile error.

Run from the repository root:  python3 tools/check_material_icons.py
"""

from __future__ import annotations

import os
import re
import sys

SOURCE_ROOT = os.path.join("app", "src", "main", "java")
DRAWABLE_DIR = os.path.join("app", "src", "main", "res", "drawable")
FETCHER = os.path.join("tools", "fetch_material_icons.py")


def referenced() -> set[str]:
    names: set[str] = set()
    for root, _, files in os.walk(SOURCE_ROOT):
        for name in files:
            if not name.endswith(".kt"):
                continue
            with open(os.path.join(root, name), encoding="utf-8") as handle:
                names.update(re.findall(r"R\.drawable\.(ic_[a-z0-9_]+)", handle.read()))
    return names


def bundled() -> set[str]:
    return {
        name[:-4]
        for name in os.listdir(DRAWABLE_DIR)
        if name.startswith("ic_") and name.endswith(".xml")
    }


def in_fetcher() -> set[str]:
    with open(FETCHER, encoding="utf-8") as handle:
        body = handle.read()
    block = re.search(r"ICONS = \[(.*?)\n\]", body, re.S)
    if block is None:
        sys.exit(f"could not find the ICONS list in {FETCHER}")
    return {f"ic_{name}" for name in re.findall(r'"(\w+)"', block.group(1))}


def main() -> None:
    used, present, listed = referenced(), bundled(), in_fetcher()
    problems = []

    missing = sorted(used - present)
    if missing:
        problems.append(f"referenced but not bundled (would crash at runtime): {missing}")

    unused = sorted(present - used)
    if unused:
        problems.append(f"bundled but never referenced (dead weight in the APK): {unused}")

    drifted = sorted(present ^ listed)
    if drifted:
        problems.append(f"res/drawable and the fetcher's ICONS list disagree: {drifted}")

    if problems:
        for problem in problems:
            print(f"ERROR: {problem}", file=sys.stderr)
        print(
            "\nFix by updating the ICONS list in tools/fetch_material_icons.py and re-running it.",
            file=sys.stderr,
        )
        sys.exit(1)

    print(f"{len(used)} Material Icons referenced, bundled and listed - all in agreement")


if __name__ == "__main__":
    main()
