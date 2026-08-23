# Offline maps

Roadguard shows a moving map with **no SIM, no mobile data and no Wi-Fi**, anywhere in
Australia. The user is never asked to find, download or copy a map file: the app installs the
map itself on first run, shows progress, survives interruption, and after that never contacts a
tile server again.

The full research trail is in [`research/offline-maps.md`](research/offline-maps.md) (917
lines). This document is the shipped design.

---

## 1. The stack

| Layer | Choice |
| --- | --- |
| Renderer | **MapLibre Native** — `org.maplibre.gl:android-sdk-opengl:13.5.1` |
| Tile container | **PMTiles v3**, a single file, read directly |
| Tile schema | **Protomaps Basemap** (9 vector layers) |
| Data | OpenStreetMap, built with planetiler |
| Source | release assets of `github.com/tunlezah/DashCam`, tag `map-data-v1` |
| Coverage | whole of Australia (zoom 12), or any single state/territory (zoom 14) |
| Glyphs and sprites | **bundled in the APK** as assets |
| Style | **bundled in the APK**, day and night, 18 layers each |

### Why PMTiles and not MBTiles

MapLibre's shipped `libmaplibre.so` contains a complete `PMTilesFileSource` — verified by
`strings` on the actual `.so` in the AAR, which lists the supported URI schemes as `asset://`,
`file://`, `mbtiles://` and `pmtiles://`. PMTiles is a single file with an internal directory,
which means:

* one download, one file, one rename to install — no SQLite database to open, migrate or
  corrupt;
* no per-tile row overhead on a 1.1 GB archive;
* clustered layout, so reading a tile is one seek and one read.

The runtime URI is `pmtiles://file:///<absolute path>`. That is the exact form MapLibre's own
code expects, and `MapStyleProvider` builds it from the installed archive's real path.

### Why the style is bundled

Glyph and sprite requests are the sneaky way an "offline" map stays online: a style that names
`https://…/glyphs/{fontstack}/{range}.pbf` will silently need the network the first time it
draws a label. Roadguard's style therefore names:

```
"glyphs": "asset://map/glyphs/{fontstack}/{range}.pbf"
"sprite": [{ "id": "basics", "url": "asset://map/sprites/basics/sprites" }]
```

Bundled: **24 glyph PBFs** (`noto_sans_regular` and `noto_sans_bold`, 12 Unicode ranges each)
and 4 sprite files, 2.54 MiB in total, fetched verbatim by `tools/fetch_map_assets.py` with
their licences recorded in `app/src/main/assets/map/LICENCES.txt`.

`OfflineManager.setMaximumAmbientCacheSize(0)` and `setPrefetchZoomDelta(0)` complete the
picture: no ambient HTTP cache, no speculative tile fetching.

## 2. Only 18 layers, on purpose

Published vector styles run to 200–324 layers. Roadguard's day and night styles have **18
each**:

```
background  earth  landcover  landuse  water  water-lines  buildings
roads-casing  roads  rail  ferries
boundary-region  boundary-country
road-labels  place-labels-major  place-labels-minor
vehicle-halo  vehicle-dot
```

The reason is the baseline device. The Moto G04's Mali-G57 is an **MP1** part — a *single*
shader core. Every style layer is at least one draw call per tile per frame, and a 300-layer
style on an MP1 GPU is a slideshow that also competes with the video encoder for memory
bandwidth, which is a recording-reliability problem, not just a smoothness problem.

The whole road network collapses into **two** layers (`roads-casing` and `roads`) using a `match`
expression on the schema's `kind` attribute, so width and colour select the hierarchy without a
layer per class.

Two details of this schema shape the style, and both were found by **decoding real tiles from the
published archives**, not by reading a spec:

* **`rail` and `ferry` are `kind` values inside the `roads` layer**, not layers of their own. They
  are filtered out of the two road layers and drawn separately.
* **`earth` is the landmass polygon and `water` carries the ocean.** So the background is painted
  water-colour, `earth` paints land over it, and `water` paints coastline and lakes back over that.
  A missing tile therefore reads as sea rather than as a hole.

The `kind` values the style filters on, counted from a Sydney CBD tile at zoom 14:

| Layer | Values present |
| --- | --- |
| `roads` | `path` 281, `minor_road` 161, `major_road` 52, `highway` 37, `rail` 30, `ferry` 20, `other` 15 |
| `water` | `water`, `river`, `lake`, `bay`, `stream`, `ocean` |
| `earth` | `earth`, `cliff` |
| `landuse` | `park`, `residential`, `commercial`, `industrial`, `grass`, `wood`, `scrub`, … (24 kinds) |
| `places` | `city`, `suburb`, `locality`, `neighbourhood`, `region` |
| `boundaries` | `country`, `region`, `county` |

`medium_road` is filtered for too. It appears in the schema but in none of the tiles sampled, and
including a kind that is absent costs nothing while omitting one that appears would silently drop
roads.

`tools/generate_map_styles.py` generates both styles from one description, so day and night
cannot drift apart.

## 3. Installation

```
first run ─► catalogue (assets/map_packages.json)
                │
                ├─ storage budget check (same reserve the recorder uses)
                │
                ├─ download to <name>.part  ── resumable, progress, pause, cancel
                │       └─ SHA-256 verified when the catalogue publishes one
                │
                ├─ structural verify: plausible size, published size, magic bytes,
                │       PMTiles v3 header, vector tile type, usable zoom range, and
                │       every source-layer the bundled style draws
                │
                ├─ install: rename into a fresh directory
                │
                └─ write .roadguard-installed marker LAST
```

Properties that follow from that order:

* **The download only ever writes a `.part` file.** A crash leaves either the previous good
  install or a resumable partial — never a half-installed map the renderer would fail on.
* **The marker is written last**, and the marker is what `MapRepository` treats as "installed".
  There is no state in which the app believes it has a map it does not have.
* **`fd.sync()` before the file is considered complete**, so an unflushed tail cannot fail
  verification on the next start for a reason the user could not act on.
* **The storage budget is checked first**, using the same reserve the recorder respects. A map
  download can never eat the recording headroom.
* Progress reporting is throttled to whole percents and a minimum interval, so a 250 MB download
  does not spend its time emitting state.
* **A wrong-schema archive is rejected here, not discovered on a drive.** `PmtilesArchive` reads
  the container header and the embedded `vector_layers` list and requires every source-layer the
  style draws (`MapStyleProvider.REQUIRED_SOURCE_LAYERS`) to be present. Without that check a
  valid archive in another schema would install cleanly and render a blank map — the one failure
  mode that looks like an app bug and stays invisible until someone drives somewhere. The failure
  detail names the missing layers.

### Failure reasons are distinguished

Because "it didn't work" is not actionable:

| Reason | Message |
| --- | --- |
| `NoNetwork` | Map installation needs an internet connection the first time |
| `NotPublished` | That region's map file is not available at the moment |
| `InsufficientStorage` | There is not enough free space to install the map |
| `DownloadFailed` | The map download could not be completed |
| `VerificationFailed` | The downloaded map data was incomplete or corrupt |
| `NotConfigured` | No offline map package is configured for this build |
| `Cancelled` | Map installation was cancelled |

`NotPublished` exists separately from `DownloadFailed` because retrying the *same* region will not
help — but choosing a different one may.

### Corruption recovery

A failed checksum deletes the partial and starts again. A failed structural verify does the
same. An installed archive that the renderer cannot read is re-installable from the Storage
screen, which deletes the directory and marker and repeats the flow.

### Recording never waits for the map

`MapRepository` runs on the application scope. If the first run happens with no connection, the
app says so plainly and **carries on recording** — the recorder has no dependency on the map at
all. The install retries later.

## 4. Thermal subordination

`MapWorkBudget` is set by the thermal engine, and the ladder is: `Full` → `Reduced` (position
updates throttled) → `Frozen` (last frame kept, nothing new drawn) → `Disabled` (the `MapView`
is torn down and replaced with a placeholder). Plus `MapView.setMaximumFps()` under pressure.

The map is always subordinate to recording, and there is no code path by which the map can stop
or degrade the recorder.

## 5. Where the archives come from

The app downloads PMTiles archives published as release assets of
`github.com/tunlezah/DashCam`, tag `map-data-v1`. Eight packages are offered:

| Package | Size | Max zoom | Detail |
| --- | --- | --- | --- |
| All of Australia *(default)* | 231 MiB | 12 | highways and main roads, not every suburban street |
| New South Wales and ACT | 348 MiB | 14 | street level |
| Queensland | 250 MiB | 14 | street level |
| Victoria | 228 MiB | 14 | street level |
| Western Australia | 176 MiB | 14 | street level |
| South Australia | 94 MiB | 14 | street level |
| Tasmania | 55 MiB | 14 | street level |
| Northern Territory | 45 MiB | 14 | street level |

Every size in that table is the HTTP `content-length` of the actual asset, and every zoom range was
read from the archive's own PMTiles v3 header — not from a build log.

### Why whole-of-Australia is the default

Because it means a user who makes no decision at all still gets a working map, and because a driver
crossing a state border does not watch it blank out. The cost is honest and stated on screen: zoom
12 shows the road network but not every suburban street, so anyone who mostly drives in one state is
better off picking that state. The picker appears during first-run setup and again on the Storage
screen, and each row says both the size and whether it carries street-level detail.

Switching region **replaces** the installed archive rather than accumulating a second one. That is a
deliberate choice on a phone where hundreds of megabytes matter, and the Storage screen says so.

### Why not self-host

Roadguard originally built its own whole-of-Australia archive, because no source appeared to exist
that could be downloaded per-install without freeloading on someone else's donation-funded server.
That reasoning still holds for third-party tile servers — Protomaps discourages hotlinking outright,
and Mapsforge's server is labelled "not suitable for mass downloads" — but it does not apply to
assets published by the same owner as this app, which is what these are.

The self-hosting path is therefore no longer on the critical path, and
`.github/workflows/build-offline-map.yml` is **not currently usable**: it builds Shortbread 1.0
tiles, and the styles now target Protomaps Basemap. Its own schema-compatibility gate will fail if
it is run, which is the correct outcome — it stops the workflow publishing an archive the app would
reject at install time. Making it useful again means pointing it at a Protomaps-basemap build, or
regenerating the styles for Shortbread. The file carries the same warning at the top.

For the record, the archive that path produced and verified earlier was 1,133,229,927 bytes,
sha256 `e5f65fad26e753c62ea13fda37b4ae90e5b79af52e1c841e30deb80194b0dfd8`, PMTiles v3, mvt, z0–14,
clustered, `pmtiles verify` clean.

### Why no checksum is pinned

The catalogue ships `sha256: ""` for every package, deliberately. The assets are rebuilt in place by
their own workflow, so a pinned hash would eventually make Roadguard reject a perfectly good map —
a self-inflicted outage with no upside.

Verification is structural instead, and it is *stronger* for the failure that actually matters. See
§3: a checksum answers "are these the bytes I expected"; it cannot answer "will the style draw
anything from this file". A complete, valid archive in the wrong schema renders a blank map with
every layer silently matching nothing, and that is the case `PmtilesArchive` rejects at install
time — naming the missing layers in the failure detail.

## 6. Attribution and licensing

| Component | Licence |
| --- | --- |
| Map data | © OpenStreetMap contributors, **ODbL 1.0** |
| Protomaps Basemap schema | BSD-3-Clause |
| Noto Sans glyphs | SIL Open Font License 1.1 |
| Sprite set | as recorded in `app/src/main/assets/map/LICENCES.txt` |
| MapLibre Native | BSD-2-Clause |

Attribution is displayed on the map and repeated on the About screen. ODbL attribution is a
licence condition, not a courtesy.

## 7. What has been verified

| Claim | Status |
| --- | --- |
| `libmaplibre.so` contains a PMTiles source and accepts `pmtiles://file://…` | **Verified** by `strings` on the shipped native library |
| All eight published assets exist and are downloadable | **Verified** — HTTP 200 with a `content-length` for each; the sizes in §5 are those values |
| All eight are PMTiles v3, `mvt`, clustered, with the zoom ranges in §5 | **Verified** by parsing each archive's header over HTTP range requests |
| Every `source-layer` the styles draw exists in **every** one of the eight archives | **Verified** — checked against all eight; this is the check that would otherwise be a blank map |
| The `kind` values the style filters on are really present | **Verified** by decoding MVT tiles from the archives (Sydney z14, Wagga z13, Dubbo z12, Albury z6) and counting features per value |
| Styles are valid JSON with 18 layers, asset-relative glyphs and sprites, and the source-layer set the installer enforces | **Verified** — `MapAssetTest`, 12 JVM tests, reading the shipped assets |
| A wrong-schema, raster, truncated, or too-coarse archive is rejected with a stated reason | **Verified** — `PmtilesArchiveTest`, 18 JVM tests |
| The style renders acceptably on a Mali-G57 MP1 | **Not verified.** No device was available. The 18-layer budget is reasoning about draw calls, not a measured frame rate |
| Install/resume/cancel/corruption paths behave as described | **Implemented and reviewed; the verification step is now unit tested, the rest is not.** The download and install paths need a real filesystem and network — an instrumentation test, see `docs/testing.md` |
| The map renders on a phone after a real download | **Not verified.** No device was available. The archives, the schema match and the style are all verified independently; nothing has drawn a pixel |
