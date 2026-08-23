#!/usr/bin/env python3
"""Generate Roadguard's offline MapLibre styles into app/src/main/assets/map/.

Two styles are produced, day and night, over the **Protomaps Basemap** vector schema — the schema
of the PMTiles archives named in `app/src/main/assets/map_packages.json`. They are generated rather
than hand-written so the two palettes cannot drift apart, and so the layer budget stays visible in
one place.

Design notes that matter more than they look:

* **Eighteen layers, not two hundred.** MapLibre issues draw calls per visible layer per tile per
  frame, and the baseline device has a single-shader-core Mali-G57. Published basemap styles run
  200-324 layers, most of them POI icons and land-use fills a driver does not need. Roadguard draws
  land, land cover, water, the road network, boundaries, road and place labels, and a vehicle puck.

* **The whole road network is two layers.** One casing layer plus one fill layer, with width and
  colour selected by a `match` on `kind`, renders the hierarchy correctly. That replaces a dozen
  per-class layer pairs with two.

* **Water is painted twice, deliberately.** In this schema `earth` is the landmass polygon and
  `water` carries both the ocean and inland water. So the background is the water colour, `earth`
  paints land on top of it, and `water` paints coastline and lakes back over that. Any tile gap
  therefore reads as sea rather than as a hole.

* **Rail and ferries live inside the `roads` layer** in this schema, as `kind` values, rather than
  in layers of their own. They are filtered out of the road layers and drawn separately.

* **Archive zoom tops out at 14 (per-state) or 12 (whole-country); the map displays up to 18.**
  MapLibre overzooms vector geometry, which is exactly right for driving: no storage is spent on
  zoom levels whose only extra detail is sub-building geometry nobody reads at 100 km/h.

* **`__PMTILES_URI__`** is substituted at runtime with `pmtiles://file:///<abs path>`, because the
  archive's absolute path is only known on the device.

* Glyphs and sprites are `asset://`, so a style load performs no network I/O whatsoever.

Every `kind` value filtered on below was read out of the actual published archives by decoding
tiles, not taken from a schema document -- see docs/offline-maps.md.

Run from the repository root:  python3 tools/generate_map_styles.py
"""

from __future__ import annotations

import json
import os
import sys

OUT = os.path.join("app", "src", "main", "assets", "map")
SOURCE = "roadguard"
SCHEMA = "Protomaps Basemap"
ATTRIBUTION = (
    '<a href="https://www.openstreetmap.org/copyright" target="_blank">'
    "&copy; OpenStreetMap contributors</a>"
)

DAY = {
    "name": "Roadguard Day",
    "background": "#AFD3E8",
    "earth": "#EEF1F4",
    "water": "#AFD3E8",
    "water_line": "#8FBFDA",
    "forest": "#CFE0C3",
    "green": "#D8E8CD",
    "farmland": "#E8E7D2",
    "sand": "#EFE6CF",
    "urban": "#E5E3E0",
    "wetland": "#CFE0DE",
    "building": "#DCDFE3",
    "road_fill_highway": "#FFFFFF",
    "road_fill_major": "#FFF6D9",
    "road_fill_medium": "#FFFDF2",
    "road_fill_minor": "#FFFFFF",
    "road_casing_highway": "#E1A15A",
    "road_casing_major": "#D8B76A",
    "road_casing_medium": "#D3D8DC",
    "road_casing_minor": "#C9CFD4",
    "rail": "#B0B7BD",
    "ferry": "#7FA9C4",
    "boundary": "#9AA3AA",
    "label": "#22282C",
    "label_halo": "#FFFFFFDD",
    "label_minor": "#4A5257",
    "vehicle": "#0E6E96",
    "vehicle_halo": "#33CEFB",
}

NIGHT = {
    "name": "Roadguard Night",
    "background": "#08151D",
    "earth": "#0E1215",
    "water": "#0C2431",
    "water_line": "#123245",
    "forest": "#122019",
    "green": "#14231A",
    "farmland": "#191A15",
    "sand": "#1E1D17",
    "urban": "#171A1D",
    "wetland": "#10201F",
    "building": "#1A2024",
    "road_fill_highway": "#6C7B85",
    "road_fill_major": "#5C6A73",
    "road_fill_medium": "#414B52",
    "road_fill_minor": "#333C42",
    "road_casing_highway": "#8A6A3A",
    "road_casing_major": "#5E5540",
    "road_casing_medium": "#232B30",
    "road_casing_minor": "#1E252A",
    "rail": "#2C3439",
    "ferry": "#2A4A5C",
    "boundary": "#3A444B",
    "label": "#DDE4E9",
    "label_halo": "#000000CC",
    "label_minor": "#A9B2B8",
    "vehicle": "#33CEFB",
    "vehicle_halo": "#0E6E96",
}

# `roads.kind` values, verified present in the published archives by tile decoding.
HIGHWAY = ["highway"]
MAJOR = ["major_road"]
MEDIUM = ["medium_road"]
MINOR = ["minor_road"]
# Drawn separately, so excluded from the two road layers.
NON_ROAD_KINDS = ["rail", "ferry"]

# Road width by zoom, with a per-class multiplier chosen by `kind`. The stops are deliberately
# sparse: MapLibre interpolates between them, and every extra stop is arithmetic per vertex.
ROAD_WIDTH = [
    "interpolate", ["linear"], ["zoom"],
    5, ["match", ["get", "kind"], HIGHWAY, 0.7, MAJOR, 0.4, 0.0],
    9, ["match", ["get", "kind"], HIGHWAY, 1.6, MAJOR, 1.1, MEDIUM, 0.7, MINOR, 0.5, 0.3],
    13, ["match", ["get", "kind"], HIGHWAY, 5.0, MAJOR, 4.0, MEDIUM, 2.8, MINOR, 2.0, 1.4],
    17, ["match", ["get", "kind"], HIGHWAY, 17.0, MAJOR, 13.0, MEDIUM, 10.0, MINOR, 7.5, 6.0],
]

ROAD_CASING_WIDTH = [
    "interpolate", ["linear"], ["zoom"],
    9, ["match", ["get", "kind"], HIGHWAY, 2.8, MAJOR, 2.2, MEDIUM, 1.6, MINOR, 1.1, 0.9],
    13, ["match", ["get", "kind"], HIGHWAY, 7.4, MAJOR, 6.2, MEDIUM, 4.6, MINOR, 3.4, 2.8],
    17, ["match", ["get", "kind"], HIGHWAY, 22.0, MAJOR, 18.0, MEDIUM, 14.0, MINOR, 11.0, 9.0],
]

# Everything except rail and ferries, which get their own layers.
ROAD_FILTER = ["!", ["in", ["get", "kind"], ["literal", NON_ROAD_KINDS]]]

MAJOR_PLACE_KINDS = ["country", "region", "city", "suburb"]


def road_colour(palette: dict[str, str], casing: bool) -> list:
    suffix = "casing" if casing else "fill"
    return [
        "match", ["get", "kind"],
        HIGHWAY, palette[f"road_{suffix}_highway"],
        MAJOR, palette[f"road_{suffix}_major"],
        MEDIUM, palette[f"road_{suffix}_medium"],
        palette[f"road_{suffix}_minor"],
    ]


def style(palette: dict[str, str]) -> dict:
    return {
        "version": 8,
        "name": palette["name"],
        "metadata": {
            "roadguard:note": "Generated by tools/generate_map_styles.py. Do not hand-edit.",
            "roadguard:schema": SCHEMA,
        },
        "glyphs": "asset://map/glyphs/{fontstack}/{range}.pbf",
        "sprite": [{"id": "basics", "url": "asset://map/sprites/basics/sprites"}],
        "sources": {
            SOURCE: {
                "type": "vector",
                "url": "__PMTILES_URI__",
                "attribution": ATTRIBUTION,
            },
        },
        "layers": [
            # The background is water, so a missing tile reads as sea rather than as a void.
            {
                "id": "background",
                "type": "background",
                "paint": {"background-color": palette["background"]},
            },
            {
                "id": "earth",
                "type": "fill",
                "source": SOURCE,
                "source-layer": "earth",
                "paint": {"fill-color": palette["earth"]},
            },
            # Coarse cover, present only to zoom 7 in the archives; it stops the country looking
            # like a blank sheet when the map is zoomed out.
            {
                "id": "landcover",
                "type": "fill",
                "source": SOURCE,
                "source-layer": "landcover",
                "maxzoom": 8,
                "paint": {
                    "fill-color": [
                        "match", ["get", "kind"],
                        ["forest"], palette["forest"],
                        ["farmland"], palette["farmland"],
                        ["urban_area"], palette["urban"],
                        ["sand", "barren", "glacier"], palette["sand"],
                        palette["green"],
                    ],
                    "fill-opacity": 0.55,
                },
            },
            {
                "id": "landuse",
                "type": "fill",
                "source": SOURCE,
                "source-layer": "landuse",
                "minzoom": 8,
                "paint": {
                    "fill-color": [
                        "match", ["get", "kind"],
                        ["wood", "forest", "nature_reserve"], palette["forest"],
                        ["park", "garden", "grass", "grassland", "pitch", "playground",
                         "golf_course", "cemetery", "zoo"], palette["green"],
                        ["farmland", "allotments", "orchard", "vineyard"], palette["farmland"],
                        ["sand", "beach", "bare_rock", "scree", "quarry"], palette["sand"],
                        ["wetland", "marsh", "swamp"], palette["wetland"],
                        ["residential", "commercial", "industrial", "retail", "railway",
                         "military", "school", "university", "hospital", "kindergarten",
                         "pedestrian", "platform", "pier"], palette["urban"],
                        palette["green"],
                    ],
                    "fill-opacity": 0.7,
                },
            },
            {
                "id": "water",
                "type": "fill",
                "source": SOURCE,
                "source-layer": "water",
                "paint": {"fill-color": palette["water"]},
            },
            {
                "id": "water-lines",
                "type": "line",
                "source": SOURCE,
                "source-layer": "water",
                "minzoom": 9,
                "filter": ["in", ["get", "kind"], ["literal", ["river", "stream", "canal", "ditch"]]],
                "paint": {
                    "line-color": palette["water_line"],
                    "line-width": ["interpolate", ["linear"], ["zoom"], 9, 0.5, 16, 3.0],
                },
            },
            {
                "id": "buildings",
                "type": "fill",
                "source": SOURCE,
                "source-layer": "buildings",
                "minzoom": 15,
                "paint": {"fill-color": palette["building"], "fill-opacity": 0.7},
            },
            {
                "id": "roads-casing",
                "type": "line",
                "source": SOURCE,
                "source-layer": "roads",
                "minzoom": 9,
                "filter": ROAD_FILTER,
                "layout": {"line-cap": "round", "line-join": "round"},
                "paint": {
                    "line-color": road_colour(palette, casing=True),
                    "line-width": ROAD_CASING_WIDTH,
                },
            },
            {
                "id": "roads",
                "type": "line",
                "source": SOURCE,
                "source-layer": "roads",
                "filter": ROAD_FILTER,
                "layout": {"line-cap": "round", "line-join": "round"},
                "paint": {
                    "line-color": road_colour(palette, casing=False),
                    "line-width": ROAD_WIDTH,
                    # Tunnels are drawn faded rather than hidden, so a driver can still see that
                    # the road continues underneath.
                    "line-opacity": ["case", ["==", ["get", "is_tunnel"], True], 0.45, 1.0],
                },
            },
            {
                "id": "rail",
                "type": "line",
                "source": SOURCE,
                "source-layer": "roads",
                "minzoom": 11,
                "filter": ["==", ["get", "kind"], "rail"],
                "paint": {
                    "line-color": palette["rail"],
                    "line-width": ["interpolate", ["linear"], ["zoom"], 11, 0.6, 16, 2.0],
                    "line-dasharray": [3, 3],
                },
            },
            {
                "id": "ferries",
                "type": "line",
                "source": SOURCE,
                "source-layer": "roads",
                "minzoom": 10,
                "filter": ["==", ["get", "kind"], "ferry"],
                "paint": {
                    "line-color": palette["ferry"],
                    "line-width": 1.2,
                    "line-dasharray": [2, 3],
                },
            },
            {
                "id": "boundary-region",
                "type": "line",
                "source": SOURCE,
                "source-layer": "boundaries",
                "minzoom": 4,
                "filter": ["in", ["get", "kind"], ["literal", ["region", "county"]]],
                "paint": {
                    "line-color": palette["boundary"],
                    "line-width": 0.8,
                    "line-dasharray": [4, 3],
                },
            },
            {
                "id": "boundary-country",
                "type": "line",
                "source": SOURCE,
                "source-layer": "boundaries",
                "filter": ["==", ["get", "kind"], "country"],
                "paint": {
                    "line-color": palette["boundary"],
                    "line-width": ["interpolate", ["linear"], ["zoom"], 2, 0.6, 8, 1.6],
                },
            },
            # Road names come from the road geometry itself in this schema; there is no separate
            # label layer, so placement is along the line.
            {
                "id": "road-labels",
                "type": "symbol",
                "source": SOURCE,
                "source-layer": "roads",
                "minzoom": 13,
                "filter": ["all", ROAD_FILTER, ["has", "name"]],
                "layout": {
                    "symbol-placement": "line",
                    "text-field": ["coalesce", ["get", "name"], ["get", "ref"]],
                    "text-font": ["noto_sans_regular"],
                    "text-size": ["interpolate", ["linear"], ["zoom"], 13, 10, 18, 14],
                    "text-max-angle": 30,
                    "text-padding": 4,
                },
                "paint": {
                    "text-color": palette["label"],
                    "text-halo-color": palette["label_halo"],
                    "text-halo-width": 1.4,
                },
            },
            {
                "id": "place-labels-major",
                "type": "symbol",
                "source": SOURCE,
                "source-layer": "places",
                "filter": ["in", ["get", "kind"], ["literal", MAJOR_PLACE_KINDS]],
                "layout": {
                    "text-field": ["get", "name"],
                    "text-font": ["noto_sans_bold"],
                    "text-size": ["interpolate", ["linear"], ["zoom"], 3, 11, 8, 16, 13, 20],
                    "text-max-width": 8,
                },
                "paint": {
                    "text-color": palette["label"],
                    "text-halo-color": palette["label_halo"],
                    "text-halo-width": 1.6,
                },
            },
            {
                "id": "place-labels-minor",
                "type": "symbol",
                "source": SOURCE,
                "source-layer": "places",
                "minzoom": 9,
                "filter": ["!", ["in", ["get", "kind"], ["literal", MAJOR_PLACE_KINDS]]],
                "layout": {
                    "text-field": ["get", "name"],
                    "text-font": ["noto_sans_regular"],
                    "text-size": ["interpolate", ["linear"], ["zoom"], 9, 10, 14, 15],
                    "text-max-width": 8,
                },
                "paint": {
                    "text-color": palette["label_minor"],
                    "text-halo-color": palette["label_halo"],
                    "text-halo-width": 1.4,
                },
            },
            # The vehicle marker is fed from Roadguard's own location engine, not MapLibre's
            # location component: that keeps the map out of the permission and GNSS business
            # entirely, and means map throttling cannot affect the recorder's location updates.
            {
                "id": "vehicle-halo",
                "type": "circle",
                "source": "vehicle",
                "paint": {
                    "circle-radius": ["interpolate", ["linear"], ["zoom"], 8, 10, 16, 22],
                    "circle-color": palette["vehicle_halo"],
                    "circle-opacity": 0.25,
                    "circle-stroke-width": 0,
                },
            },
            {
                "id": "vehicle-dot",
                "type": "circle",
                "source": "vehicle",
                "paint": {
                    "circle-radius": ["interpolate", ["linear"], ["zoom"], 8, 4.5, 16, 8],
                    "circle-color": palette["vehicle"],
                    "circle-stroke-color": "#FFFFFF",
                    "circle-stroke-width": 2,
                },
            },
        ],
    }


def main() -> None:
    if not os.path.isdir("app/src/main"):
        sys.exit("run from the repository root")
    os.makedirs(OUT, exist_ok=True)
    for palette, name in ((DAY, "style-day.json"), (NIGHT, "style-night.json")):
        document = style(palette)
        # The vehicle source is declared here rather than in `style()` so both styles share it.
        document["sources"]["vehicle"] = {
            "type": "geojson",
            "data": {"type": "FeatureCollection", "features": []},
        }
        path = os.path.join(OUT, name)
        with open(path, "w", encoding="utf-8") as handle:
            json.dump(document, handle, indent=2)
            handle.write("\n")
        print(f"{path}  {os.path.getsize(path)} B  {len(document['layers'])} layers")


if __name__ == "__main__":
    main()
