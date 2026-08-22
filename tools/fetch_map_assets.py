#!/usr/bin/env python3
"""Fetch the glyph and sprite assets the offline map style needs, into app/src/main/assets/map/.

Roadguard's map must render with **no network at all** once installed. Vector tiles alone are not
enough for that: MapLibre fetches label glyphs and icon sprites from the URLs named in the style, so
a style that points at `https://` renders roads with no names on a phone with no SIM. These assets are
therefore bundled in the APK and referenced as `asset://`.

Only the 12 Unicode ranges Australian place and street names actually need are fetched (Latin, Latin
Extended and the punctuation/symbol blocks MapLibre asks for), for two fontstacks -- about 2.3 MiB,
against 48 MB for the whole Noto Sans family. A missing range is not fatal in MapLibre: the affected
labels simply do not draw, so a subset is safe.

Sources and licences (recorded in app/src/main/assets/map/LICENCES.txt by this script):
  * glyphs and sprites: tiles.versatiles.org asset service, whose tooling is MIT and whose typefaces
    are SIL OFL 1.1; the sprite artwork derives from CC-0/MIT icon sets.
  * both are redistributable in an application provided the notices travel with them.

Run from the repository root:  python3 tools/fetch_map_assets.py
"""

from __future__ import annotations

import os
import sys
import urllib.request

BASE = "https://tiles.versatiles.org/assets"
OUT = os.path.join("app", "src", "main", "assets", "map")

FONTSTACKS = ["noto_sans_regular", "noto_sans_bold"]

# The ranges MapLibre requests for Latin-script place names, plus the punctuation and symbol blocks
# it asks for when a label contains a dash, quote or arrow glyph.
RANGES = [
    "0-255",
    "256-511",
    "512-767",
    "768-1023",
    "1024-1279",
    "7680-7935",
    "7936-8191",
    "8192-8447",
    "8448-8703",
    "8704-8959",
    "9472-9727",
    "11264-11519",
]

SPRITES = [
    "sprites/basics/sprites.json",
    "sprites/basics/sprites.png",
    "sprites/basics/sprites@2x.json",
    "sprites/basics/sprites@2x.png",
]

LICENCES = """Offline map assets bundled with Roadguard
=========================================

Glyphs (app/src/main/assets/map/glyphs/**)
------------------------------------------
Rendered from the Noto Sans typeface. Noto fonts are licensed under the
SIL Open Font License, Version 1.1 (https://scripts.sil.org/OFL).
The glyph packaging tooling is from the VersaTiles project and is MIT licensed
(https://github.com/versatiles-org/versatiles-fonts).

Sprites (app/src/main/assets/map/sprites/**)
--------------------------------------------
Icon sprite sheets from the VersaTiles "basics" set, published under CC0 1.0
with artwork derived from MIT-licensed icon sets.

Map data
--------
The offline tile archive is built from OpenStreetMap data.
(c) OpenStreetMap contributors, licensed under the Open Database License 1.0
(https://www.openstreetmap.org/copyright).

Vector tile schema
------------------
Shortbread 1.0 (https://shortbread-tiles.org/), documentation licensed CC0.

Roadguard displays the OpenStreetMap attribution in the map pane and on the
About screen, as the ODbL requires for a Produced Work.
"""


def fetch(url: str, target: str) -> int:
    os.makedirs(os.path.dirname(target), exist_ok=True)
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "Roadguard-build/1.0 (+https://github.com/tunlezah/Roadguard)"},
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        payload = response.read()
    with open(target, "wb") as handle:
        handle.write(payload)
    return len(payload)


def main() -> None:
    if not os.path.isdir("app/src/main"):
        sys.exit("run from the repository root")

    total = 0
    for stack in FONTSTACKS:
        for rng in RANGES:
            url = f"{BASE}/glyphs/{stack}/{rng}.pbf"
            target = os.path.join(OUT, "glyphs", stack, f"{rng}.pbf")
            size = fetch(url, target)
            total += size
            print(f"  glyphs/{stack}/{rng}.pbf  {size} B")

    for relative in SPRITES:
        size = fetch(f"{BASE}/{relative}", os.path.join(OUT, relative))
        total += size
        print(f"  {relative}  {size} B")

    with open(os.path.join(OUT, "LICENCES.txt"), "w", encoding="utf-8") as handle:
        handle.write(LICENCES)

    print(f"\nfetched {total} B ({total / (1024 * 1024):.2f} MiB) into {OUT}")


if __name__ == "__main__":
    main()
