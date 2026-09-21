# Trips and GPX tracks

Roadguard groups its clips into **trips** — one per drive — names each trip from the offline map
already on the phone, and writes a **GPX track** of every trip that can be opened in a map app
such as CoMaps or Organic Maps. Nothing here needs a network, a new permission or a new
download.

---

## 1. What a trip is

A trip is a run of clips with no long silence between them. The rule is the one a person would
apply to the timestamps, stated once in `trip/TripAssembler.kt` and used everywhere:

> A clip belongs to the previous trip if it starts within **2 minutes** of that trip's last clip
> ending. A longer gap starts a new trip.

A segment rollover is milliseconds, a crash and relaunch is seconds, and a fuel stop with
recording off is many minutes, so the limit sits comfortably between them. The same function
decides live (a recording that starts within the gap **continues** the previous trip, so a
stop-and-restart or a relaunch after a crash is one drive) and at start-up (clips recorded before
trips existed are grouped by it).

Each trip row (`data/Entities.kt`, `TripEntity`) holds:

| Field | Set when |
| --- | --- |
| start and end time | start when the trip opens; end advanced every time a clip finalises, so a process death leaves an end at most one clip stale |
| start and end position | the fix when the trip opened (or the first fix afterwards) and the fix when it closed |
| start and end names, fine and coarse | when the trip closes, from the offline map; retried by the gallery if no map was installed at the time |
| track file, point count, distance | as the track is written |

Every clip carries its `tripId`, and the fix seen when it finalised (`endLatitude`,
`endLongitude`), alongside the start fix it already stored.

## 2. How a trip gets its name

The product rule: **suburb names by default, city names when the trip moves between two
different cities.** A run across town reads *Harrison → Braddon* with *Canberra* underneath; a run
down the highway reads *Canberra → Sydney* with *Harrison → Surry Hills* underneath; a run in the
country reads *Gundaroo → Goulburn* and nothing more, because the towns are already the broadest
honest label.

Two lookups happen per end of the trip (`map/PlaceRanking.kt`):

| Level | Rule |
| --- | --- |
| **fine** | the nearest suburb within 3 km, else the nearest town, village or hamlet within 8 km |
| **coarse** | a city within 20 km, chosen by distance discounted by population, so Canberra wins over Queanbeyan from a northern suburb while somebody actually in Queanbeyan still gets Queanbeyan |

`trip/TripNaming.kt` then combines the two ends: different coarse names → the coarse names are
the title and the fine ones the detail; the same coarse name (or none) → the fine names are the
title and the shared city the detail. Both ends in the same suburb reads *Around Harrison*. A
trip with no fixes at all is named by its time span, and says so.

The radii and the gap are named constants with tests. They were chosen against real archive data
and are the first thing to adjust if a drive disagrees with them.

### Where the names come from

The archive Roadguard downloads for the moving map carries a `places` layer: suburbs, towns,
villages and cities, each with a `kind`, a `kind_detail` and a `population`. This was verified by
**decoding real tiles from the published archives**, not by reading a schema:

| Test point | Whole-of-Australia archive (max zoom 12) | State archive (max zoom 14) |
| --- | --- | --- |
| Harrison ACT | Harrison · neighbourhood/suburb · 0.1 km | same |
| Braddon ACT | Braddon · neighbourhood/suburb · 0.1 km | same |
| Gundaroo NSW | Gundaroo · locality/village · 0.4 km | same |
| Hume Hwy at Marulan | Marulan · locality/town · 0.6 km | same |
| Goulburn NSW | Goulburn · locality/city · 0.2 km, population 23,963 | same |
| Surry Hills NSW | Surry Hills · neighbourhood/suburb · 0.2 km | same |
| Canberra region at zoom 8 | Canberra · locality/city · population 466,566 | same |

The important result is the first column: the default whole-of-Australia archive stops at zoom 12,
yet its top-zoom tiles still carry the suburb points (their `min_zoom` is 13, and planetiler keeps
them in the deepest tile it writes), so suburb-level naming works without switching packages.

A lookup (`map/OfflinePlaceLookup.kt`) reads the nine tiles around the point at the archive's
deepest zoom for the fine name and at zoom 8 for the city, through Roadguard's own PMTiles reader
(`map/PmtilesReader.kt`: header, root and leaf directories, Hilbert tile ids, gzip) and a minimal
vector-tile decoder that reads only the `places` layer (`map/VectorTileDecoder.kt`). The file is
the one the map renders from, opened read-only; MapLibre is not involved. Each lookup is a few
tens of milliseconds of decode, done twice per trip, off the recorder's thread.

## 3. The GPX track

`location/GpxWriter.kt` writes GPX 1.1 incrementally and crash-safely: the file always ends with
the closing tags, and each append seeks back over them, writes the point, and writes them again,
so the file on disk is a valid document at every moment. Speed is written as the Garmin
`gpxtpx:speed` extension, which Garmin, Strava and most desktop tools read.

What changed to make it a running feature rather than a class nobody called:

* **One track per trip.** `location/TrackRecorder.kt` owns the writer for the trip being
  recorded. A relaunch that continues a trip reopens and appends to the same file.
* **Not every fix is a point.** `location/TrackPointFilter.kt` writes a fix when the vehicle has
  moved at least 5 m since the last written point, or every 30 s while stationary so a stop is
  visible; distance is only counted for movement, so GNSS wander while parked does not add
  kilometres. Fixes worse than 75 m accuracy are ignored.
* **Not every point is an fsync.** The writer flushes to storage at most every 5 s (and on
  close), rather than after every point: one fix a second with an fsync each would be 3,600
  flushes an hour on the same eMMC the video encoder is writing to. A power cut loses a few
  seconds of track, never the file.
* **The file is named after the trip.** It opens as *Roadguard trip 19 Sep 2026 08:12* and, once
  the trip is closed and named, its two `<name>` elements are rewritten to
  *Harrison → Braddon · 19 Sep 2026 08:12* through a temporary file and an atomic rename, so a
  map app shows the drive rather than a timestamp.

Tracks live in `tracks/` under Roadguard's own storage, beside `recordings/`. Sizes: about 200
bytes a point, so a one-hour drive is a few hundred kilobytes.

### Deletion

**A track lives exactly as long as its trip's clips.** When the last clip of a trip leaves the
loop, is deleted from the gallery, or is deleted from the Storage screen, the trip row and its
track go with it (`StorageManager.pruneEmptyTrips`). The trip being recorded is never a
candidate. Protected clips keep their trip, and therefore its track, for as long as they exist.

## 4. Opening a track in another app

The gallery's *Open GPX track in a map app* fires an `ACTION_VIEW` intent carrying a
`FileProvider` URI and the MIME type `application/gpx+xml`. CoMaps' and Organic Maps' Android
manifests both declare view filters for `application/gpx+xml`, `application/gpx` and any path
ending in `.gpx` (checked in their repositories on 21 September 2026), so either appears in the
chooser. If no installed app answers, the share sheet is offered instead of an empty chooser; the
manifest's `<queries>` block for that one intent is what lets Roadguard ask the question, and it
grants no access to anything.

*Share GPX track* is an `ACTION_SEND` with the same file. Both are a tap on a button: Roadguard
never sends a track anywhere by itself. See `docs/privacy.md`.

## 5. Settings

| Setting | Default | Notes |
| --- | --- | --- |
| Location in the video | Overlay and video metadata | the four positions that used to share a control with the track |
| Save a GPX track of each trip | **On** | disabled, with the reason shown, when location is off; turning it off mid-drive stops the track at once, turning it on starts one for the current trip |

The former six-position control had *GPX track only* and *Overlay, metadata and GPX track*
positions that no code ever honoured. A stored value from either is read as the video mode it
implied plus the track switched on (`SettingsRepository.legacyGpsStorage`), so an upgrade changes
nothing the user chose.

## 6. Start-up reconciliation

`StorageReconciler` gained four steps, each guarded so a failure in trips can never undo the
footage repairs before it:

| Situation | Repair |
| --- | --- |
| clips with no trip (recorded before trips existed, or adopted from disk) | group by the gap rule; a group within the gap of the latest trip joins it, every other becomes a closed trip |
| a trip left `Recording` | close it; its end is the last clip that finalised |
| a trip with no clips | drop the row and its track |
| a GPX file no trip refers to | delete it |

Trips created this way have no names until the gallery is opened with a map installed, at which
point they are named once and marked resolved.

## 7. The gallery

Recordings are grouped by day, then by trip, newest first; the clips inside a trip run oldest
first, which is the order they play in. A trip card shows the title and detail line, the time
span, duration, clip count and distance, a thumbnail of the route drawn from the track points
alone (no tiles, so a long list stays cheap on the baseline phone), the incident and protected
counts, and the track buttons. Its menu can protect every clip of the trip at once (one sidecar
per clip, no invented incidents) or delete the unprotected ones. The player shows which trip a
clip belongs to, which clip of how many it is, and steps to the previous or next clip of the trip
without going back to the list.

## 8. What has been verified

| Claim | Status |
| --- | --- |
| The published archives carry suburb, town and city points with kind and population, at the zooms the app installs | **Verified** by decoding real tiles over HTTP range requests from both archive kinds, 21 September 2026 |
| The PMTiles reader and vector-tile decoder read those tiles | **Verified** against a hand-built archive, `PmtilesReaderTest`; the same code decoded the published tiles in the research step |
| The ranking rules produce the names above for the test points | **Verified**, `PlaceRankingTest`, `TripNamingTest` |
| The gap rule groups and continues trips as described | **Verified**, `TripAssemblerTest` |
| The GPX file is valid after every append, reopens, renames and reads back | **Verified**, `GpxWriterTest` |
| The 1→2 migration matches the exported schema and keeps existing clips | **Verified**, `RoadguardMigrationTest` |
| CoMaps and Organic Maps declare GPX view filters | **Verified** by reading their manifests; **not verified** on a phone, like every other share path in this app |
| A trip is recorded, named and opened end to end on a device | **Not verified.** No device was available |
