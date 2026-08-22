#!/usr/bin/env python3
"""Fetch the standard Material Icons that Roadguard needs and emit Android vector drawables.

Roadguard deliberately depends only on ``androidx.compose.material:material-icons-core``
(49 icons) instead of ``material-icons-extended`` (several MB of APK). Every additional
symbol the UI needs is a *standard* Material Icon downloaded here from Google's official
``google/material-design-icons`` repository (Apache-2.0) and converted verbatim to an
Android vector drawable -- so Roadguard never invents iconography for a standard action.

Run from the repository root:  python3 tools/fetch_material_icons.py
"""

from __future__ import annotations

import json
import os
import re
import sys
import urllib.request

RAW = "https://raw.githubusercontent.com/google/material-design-icons/master"
INDEX = f"{RAW}/update/current_versions.json"
OUT = os.path.join("app", "src", "main", "res", "drawable")

# The exact set of standard Material Icon names the Roadguard UI references. Kept in step with
# the code by tools/check_material_icons.py, which fails if the two ever diverge -- an unused
# icon is dead weight in the APK and a missing one is a crash.
ICONS = [
    "bolt", "brightness_high", "bug_report", "cameraswitch", "check_circle", "chevron_right",
    "cloud", "cloud_off", "content_copy", "contrast", "delete_sweep", "device_thermostat",
    "download_for_offline", "error", "error_outline", "fiber_manual_record", "flip_camera_android", "folder",
    "fullscreen", "gps_fixed", "gps_not_fixed", "gps_off", "hd", "help_outline",
    "high_quality", "history", "location_searching", "lock", "lock_open", "map",
    "mic", "mic_off", "more_horiz", "movie", "my_location", "near_me",
    "nights_stay", "north", "notifications_active", "palette", "pause", "pin_drop",
    "power_off", "privacy_tip", "report_problem", "restore", "schedule", "screen_lock_portrait",
    "sd_card", "sensors", "speed", "stop", "storage", "thermostat",
    "tune", "vibration", "video_library", "videocam", "videocam_off", "visibility_off",
    "warning", "zoom_out_map",
]
VECTOR = """<!--
  Standard Material Icon "{name}" from google/material-design-icons ({category}),
  licensed under the Apache License 2.0. Downloaded and converted verbatim by
  tools/fetch_material_icons.py. Do not hand-edit; re-run the script instead.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
{paths}
</vector>
"""

PATH = '    <path\n        android:fillColor="#FFFFFFFF"\n        android:pathData="{d}" />'


def path_data(svg: str) -> list[str]:
    """Extract every filled shape from a Material Icon SVG as vector-drawable path data.

    The classic 24x24 icons are almost always a single <path>, but a few are expressed as
    <circle>, <polygon> or <rect>; those are converted to equivalent path data rather than
    redrawn. The leading transparent <path fill="none"> bounding box is dropped.
    """
    out: list[str] = []
    for match in re.finditer(r"<(path|circle|polygon|rect|ellipse)\b([^>]*?)/?>", svg):
        kind, attrs = match.group(1), match.group(2)
        if 'fill="none"' in attrs:
            continue

        def attr(name: str, default: float | None = None) -> float:
            found = re.search(rf'\b{name}="([-0-9.eE]+)"', attrs)
            if found:
                return float(found.group(1))
            if default is None:
                raise ValueError(f"missing {name} on <{kind}>")
            return default

        if kind == "path":
            found = re.search(r'\bd="([^"]+)"', attrs)
            if found:
                out.append(found.group(1))
        elif kind in ("circle", "ellipse"):
            cx, cy = attr("cx"), attr("cy")
            rx = attr("r", 0.0) or attr("rx")
            ry = attr("r", 0.0) or attr("ry")
            # Two arcs make a full ellipse in SVG path syntax.
            out.append(
                f"M{cx - rx},{cy} a{rx},{ry} 0 1,0 {2 * rx},0 a{rx},{ry} 0 1,0 {-2 * rx},0 Z"
            )
        elif kind == "polygon":
            found = re.search(r'\bpoints="([^"]+)"', attrs)
            if not found:
                continue
            nums = [float(v) for v in re.split(r"[,\s]+", found.group(1).strip()) if v]
            pts = list(zip(nums[0::2], nums[1::2]))
            body = " ".join(f"L{x},{y}" for x, y in pts[1:])
            out.append(f"M{pts[0][0]},{pts[0][1]} {body} Z")
        elif kind == "rect":
            x, y, w, h = attr("x", 0.0), attr("y", 0.0), attr("width"), attr("height")
            out.append(f"M{x},{y} h{w} v{h} h{-w} Z")
    return out


def fetch(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=60) as response:
        return response.read()


def main() -> None:
    index = json.loads(fetch(INDEX))
    by_name: dict[str, str] = {}
    for key in index:
        category, _, name = key.partition("::")
        # The "symbols" pseudo-category is the Material *Symbols* font and has no
        # src/ tree of classic 24x24 SVGs, so it can never be a download source.
        if category == "symbols":
            continue
        by_name.setdefault(name, category)

    os.makedirs(OUT, exist_ok=True)
    missing, written = [], 0
    for name in sorted(set(ICONS)):
        category = by_name.get(name)
        if category is None:
            missing.append(name)
            continue
        url = f"{RAW}/src/{category}/{name}/materialicons/24px.svg"
        try:
            svg = fetch(url).decode("utf-8")
        except Exception as exc:  # noqa: BLE001 - developer tooling
            missing.append(f"{name} ({exc})")
            continue
        if 'viewBox="0 0 24 24"' not in svg:
            missing.append(f"{name} (unexpected viewBox)")
            continue
        paths = [PATH.format(d=d) for d in path_data(svg)]
        if not paths:
            missing.append(f"{name} (no path data)")
            continue
        target = os.path.join(OUT, f"ic_{name}.xml")
        with open(target, "w", encoding="utf-8") as handle:
            handle.write(VECTOR.format(name=name, category=category, paths="\n".join(paths)))
        written += 1

    print(f"wrote {written} vector drawables to {OUT}")
    if missing:
        print("NOT FOUND (fix the name list):", ", ".join(missing), file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
