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
| Tile schema | **Shortbread 1.0** |
| Tile builder | **planetiler** (`tools/build_australia_pmtiles.sh`) |
| Data | OpenStreetMap, via a Geofabrik area extract |
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
background  ocean  land  water  water-lines  buildings
streets-casing  streets  rail  ferries
boundary-state  boundary-country
street-labels  place-labels-major  place-labels-minor  boundary-labels
vehicle-halo  vehicle-dot
```

The reason is the baseline device. The Moto G04's Mali-G57 is an **MP1** part — a *single*
shader core. Every style layer is at least one draw call per tile per frame, and a 300-layer
style on an MP1 GPU is a slideshow that also competes with the video encoder for memory
bandwidth, which is a recording-reliability problem, not just a smoothness problem.

The whole road network collapses into **two** layers (`streets-casing` and `streets`) using a
`match` expression on Shortbread's `kind` attribute. This is safe because **Shortbread
guarantees z-order within a tile**: features arrive in the order they should be drawn, so
motorways do not need their own layer to sit above residential streets.

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
                ├─ structural verify: plausible size, published size, magic bytes
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
* Progress reporting is throttled to whole percents and a minimum interval, so a 1.1 GB download
  does not spend its time emitting state.

### Failure reasons are distinguished

Because "it didn't work" is not actionable:

| Reason | Message |
| --- | --- |
| `NoNetwork` | Map installation needs an internet connection the first time |
| `NotPublished` | The offline map package has not been published for this build yet |
| `InsufficientStorage` | There is not enough free space to install the map |
| `DownloadFailed` | The map download could not be completed |
| `VerificationFailed` | The downloaded map data was incomplete or corrupt |
| `NotConfigured` | No offline map package is configured for this build |
| `Cancelled` | Map installation was cancelled |

`NotPublished` exists separately from `DownloadFailed` because it is not something the user can
fix by retrying.

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

## 5. Building the archive

`tools/build_australia_pmtiles.sh` runs planetiler against a Geofabrik area extract and emits
`australia-shortbread.pmtiles` plus a `.sha256`.

**Built and verified in this work:**

| Property | Value |
| --- | --- |
| Size | 1,133,229,927 bytes (1.06 GiB) |
| SHA-256 | `e5f65fad26e753c62ea13fda37b4ae90e5b79af52e1c841e30deb80194b0dfd8` |
| `pmtiles verify` | clean |
| Format | PMTiles v3, `mvt`, zoom 0–14, clustered |

Zoom 14 is the Shortbread maximum and is ample for driving: MapLibre over-zooms vector tiles
without visible loss, so z14 data renders cleanly at z17+.

### Why Roadguard self-hosts

The OpenStreetMap data is ODbL and free to redistribute. The problem is not licensing, it is
bandwidth: Geofabrik, BBBike, Protomaps and VersaTiles are either donation funded or explicitly
discourage per-install traffic (Protomaps discourages hotlinking outright; Mapsforge's server is
labelled "not suitable for mass downloads"). Pointing an app's first-run download at any of them
would make somebody else's server pay for Roadguard's installs.

So: build once, host once, attribute properly. `© OpenStreetMap contributors`, ODbL 1.0, shown
on the map and on the About screen.

### Publishing it

`.github/workflows/build-offline-map.yml` (manual `workflow_dispatch`) builds the archive, runs
`pmtiles verify` and `pmtiles show`, **checks that every `source-layer` the style references
actually exists in the archive's metadata**, creates or updates the release, uploads the asset
and its checksum, and commits the real size and SHA-256 back into
`app/src/main/assets/map_packages.json`.

That last step is why the catalogue currently ships with `sizeBytes: null` and `sha256: ""`: the
app skips checksum verification when the catalogue publishes none (structural verification still
runs), and the workflow fills both in from the artifact it actually produced. Hard-coding the
local build's checksum would be wrong — a later planetiler run against fresher OSM data produces
a different, equally valid archive, and the app would reject it.

> **The release asset has not been published.** This environment has no way to create a GitHub
> release or upload an asset — the available GitHub tools do not include release creation, and
> direct API access is not available. The workflow is complete and ready; someone with repository
> access needs to run **Actions → Build offline map → Run workflow** once. Until then the app
> reports `NotPublished` and everything except the map works normally.

## 6. Attribution and licensing

| Component | Licence |
| --- | --- |
| Map data | © OpenStreetMap contributors, **ODbL 1.0** |
| Shortbread schema | CC0 |
| Noto Sans glyphs | SIL Open Font License 1.1 |
| Sprite set | as recorded in `app/src/main/assets/map/LICENCES.txt` |
| MapLibre Native | BSD-2-Clause |

Attribution is displayed on the map and repeated on the About screen. ODbL attribution is a
licence condition, not a courtesy.

## 7. What has been verified

| Claim | Status |
| --- | --- |
| `libmaplibre.so` contains a PMTiles source and accepts `pmtiles://file://…` | **Verified** by `strings` on the shipped native library |
| The archive builds, verifies and is a valid PMTiles v3 file | **Verified** — built here; `pmtiles verify` clean |
| Every `source-layer` the style uses exists in the archive | **Verified** by the workflow step; also checked against the locally built archive |
| Styles are valid JSON with 18 layers, asset-relative glyphs and sprites | **Verified** by inspection and by the generator |
| The style renders acceptably on a Mali-G57 MP1 | **Not verified.** No device was available. The 18-layer budget is reasoning about draw calls, not a measured frame rate |
| Install/resume/cancel/corruption paths behave as described | **Implemented and reviewed; not covered by an automated test.** These need a real filesystem and network — an instrumentation test, see `docs/testing.md` |
| Download of the published asset works end to end | **Not verified.** The asset has not been published — see §5 |
