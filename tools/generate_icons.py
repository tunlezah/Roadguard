#!/usr/bin/env python3
"""Generate Roadguard's Android launcher icon resources from the supplied artwork.

The repository ships a single square source image (``icon.png``). This script derives
every launcher resource Android needs from it *without* redrawing or reinterpreting the
artwork:

* ``mipmap-anydpi/ic_launcher.xml`` (+ round) reference an adaptive icon whose
  background is the artwork's own surround colour (solid black) and whose foreground is
  the artwork's badge, scaled so that it exactly fills the 72dp guaranteed-visible area
  of the 108dp adaptive canvas. Nothing is cropped by a rounded-square launcher mask, and
  a circular mask only trims the badge's own dark rounded corners.
* Legacy ``mipmap-<density>/ic_launcher.png`` / ``ic_launcher_round.png`` for pre-API-26
  launchers and for launchers that still request the legacy drawable.
* ``ic_launcher-playstore.png`` (512x512) for store listings.

No monochrome layer is generated: the source artwork is photographic, an automatically
derived silhouette would misrepresent it, and hand-drawing one would mean creating
competing artwork. Themed icons therefore fall back to the full-colour adaptive icon.

Run from the repository root:  python3 tools/generate_icons.py
"""

from __future__ import annotations

import os
import sys

try:
    from PIL import Image, ImageDraw
except ImportError:  # pragma: no cover - developer tooling
    sys.exit("Pillow is required: pip3 install Pillow")

SOURCE = "icon.png"
RES = os.path.join("app", "src", "main", "res")

# Android adaptive icon geometry: a 108dp canvas whose central 72dp is guaranteed visible.
ADAPTIVE_CANVAS_DP = 108
ADAPTIVE_SAFE_DP = 72

# density bucket -> px per dp
DENSITIES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}
LEGACY_ICON_DP = 48

BACKGROUND = (0, 0, 0, 255)  # sampled from the artwork's own surround
CONTENT_THRESHOLD = 18  # channel value above which a pixel counts as artwork, not surround


def content_bbox(im: Image.Image) -> tuple[int, int, int, int]:
    """Bounding box of the artwork badge inside its black surround."""
    rgb = im.convert("RGB")
    w, h = rgb.size
    px = rgb.load()
    min_x, min_y, max_x, max_y = w, h, -1, -1
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y]
            if r > CONTENT_THRESHOLD or g > CONTENT_THRESHOLD or b > CONTENT_THRESHOLD:
                if x < min_x:
                    min_x = x
                if x > max_x:
                    max_x = x
                if y < min_y:
                    min_y = y
                if y > max_y:
                    max_y = y
    if max_x < 0:
        raise SystemExit("source artwork appears to be entirely background")
    # Square it off around the centre so the badge is not distorted.
    cx, cy = (min_x + max_x) / 2.0, (min_y + max_y) / 2.0
    half = max(max_x - min_x, max_y - min_y) / 2.0
    left = max(0, int(round(cx - half)))
    top = max(0, int(round(cy - half)))
    right = min(w, int(round(cx + half)))
    bottom = min(h, int(round(cy + half)))
    return left, top, right, bottom


def write(path: str, im: Image.Image) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path, "PNG", optimize=True)
    print(f"  {path}  {im.size[0]}x{im.size[1]}")


def circular(im: Image.Image) -> Image.Image:
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).ellipse((0, 0, im.size[0] - 1, im.size[1] - 1), fill=255)
    out = Image.new("RGBA", im.size, (0, 0, 0, 0))
    out.paste(im, (0, 0), mask)
    return out


def main() -> None:
    if not os.path.exists(SOURCE):
        raise SystemExit(f"{SOURCE} not found -- run from the repository root")
    src = Image.open(SOURCE).convert("RGBA")
    badge = src.crop(content_bbox(src))
    print(f"source {src.size[0]}x{src.size[1]}, badge {badge.size[0]}x{badge.size[1]}")

    print("adaptive foreground (108dp canvas, badge at 72dp):")
    for bucket, scale in DENSITIES.items():
        canvas_px = int(round(ADAPTIVE_CANVAS_DP * scale))
        badge_px = int(round(ADAPTIVE_SAFE_DP * scale))
        fg = Image.new("RGBA", (canvas_px, canvas_px), (0, 0, 0, 0))
        resized = badge.resize((badge_px, badge_px), Image.LANCZOS)
        offset = (canvas_px - badge_px) // 2
        fg.paste(resized, (offset, offset), resized)
        write(os.path.join(RES, f"mipmap-{bucket}", "ic_launcher_foreground.png"), fg)

    print("legacy launcher icons:")
    for bucket, scale in DENSITIES.items():
        size = int(round(LEGACY_ICON_DP * scale))
        square = Image.new("RGBA", (size, size), BACKGROUND)
        # The artwork already carries its own margin, so the legacy icon is simply the
        # full source scaled down.
        square.alpha_composite(src.resize((size, size), Image.LANCZOS))
        write(os.path.join(RES, f"mipmap-{bucket}", "ic_launcher.png"), square)

        round_bg = Image.new("RGBA", (size, size), BACKGROUND)
        # Scale the badge to the inscribed square of the circle so no key content is lost.
        inner = int(round(size / 1.30))
        scaled = badge.resize((inner, inner), Image.LANCZOS)
        off = (size - inner) // 2
        round_bg.alpha_composite(scaled, (off, off))
        write(os.path.join(RES, f"mipmap-{bucket}", "ic_launcher_round.png"), circular(round_bg))

    print("store icon:")
    store = Image.new("RGBA", (512, 512), BACKGROUND)
    store.alpha_composite(src.resize((512, 512), Image.LANCZOS))
    write(os.path.join("app", "ic_launcher-playstore.png"), store)


if __name__ == "__main__":
    main()
