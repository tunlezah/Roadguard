# Offline Australia-wide Mapping for Roadguard

**Research date: 2026-08-22.** Target devices: Motorola Moto G04 (Unisoc T606, Mali-G57 MP1, 4 GB RAM class, Android 14) as the floor; Motorola Edge 60 Fusion (MediaTek Dimensity) as the "must feel good" device. `minSdk = 34`, must run to API 36.

## Bottom line

Ship **MapLibre Native Android (`org.maplibre.gl:android-sdk-opengl`, currently 13.5.1)** rendering a **single whole-of-Australia PMTiles v3 archive in the Shortbread 1.0 schema**, referenced from a runtime-generated style JSON as `pmtiles://file:///<abs path>/australia.pmtiles`, with **glyphs and sprites bundled in the APK under `asset://`** so the style has zero network dependencies. PMTiles is not a workaround here — MapLibre Native has had a first-class `pmtiles://` file source since Android **11.8.0**, it accepts `pmtiles://file://` absolute paths, and it reads the archive with byte-range reads through `LocalFileSource`, so nothing is copied into the ambient-cache database. The format choice is worth roughly **2.9×** on disk: the identical Australia Shortbread tileset is **978,786,994 B (933 MiB) as PMTiles** versus **2,828,152,832 B (2.70 GiB) as MBTiles**, because PMTiles deduplicates identical tiles (Australia is mostly empty ocean and desert) and carries no SQLite B-tree index. A ~933 MiB single file is sane on a 64/128 GB Moto G04 and does **not** need state-by-state partitioning; partition later only if telemetry-free user complaints or the size arithmetic in §6 says otherwise. For distribution, do **not** hotlink Protomaps, Geofabrik, BBBike or Mapsforge from the app — all four either explicitly discourage it or are donation-funded — instead build the archive yourself with `planetiler`/`tilemaker` + `pmtiles`, and deliver it either as a **Play on-demand asset pack** (limit 1.5 GB per pack, so 933 MiB fits) or from **your own bucket** with `Range`-resumable download plus a SHA-256 manifest. Reject Mapsforge/VTM (Australia `.map` is 1.56 GiB, official server says "not suitable for mass downloads", and CPU-side raster tile rendering competes with the H.264/HEVC encode for exactly the CPU headroom recording needs), and reject osmdroid (raster only, no maintained release since 2024). Under thermal pressure, degrade the map — never the recorder — via `MapView.setMaximumFps()`, `MapLibreMapOptions.pixelRatio()`, `setPrefetchZoomDelta(0)`, and finally detaching the `MapView` entirely.

## Evidence key

- **[DOCUMENTED]** — stated in official documentation, a vendor spec, published source code, or read directly out of the published artifact over HTTP on 2026-08-22 (I state the method when it is the latter). URL cited.
- **[INFERRED]** — a conclusion derived from documented facts; the reasoning chain is spelled out so you can check it.
- **[UNVERIFIED]** — plausible but not confirmed by a source I actually read. Treat as a hypothesis.
- **NOT VERIFIED — needs on-device measurement** — cannot be settled from documents at all; the exact test that would settle it is named.
- No claim in this document is tagged `[MEASURED]`: **no code was run on a Moto G04 or any other Android device during this research.** Every performance/thermal number below is either documented by a vendor or must be measured (see final section).

---

## 1. Decision summary

| Concern | Decision | Why (short) |
|---|---|---|
| Renderer | `org.maplibre.gl:android-sdk-opengl:13.5.1` | GPU vector rendering keeps CPU free for the encoder; OpenGL flavour avoids a hard `uses-feature` Vulkan gate and is 2.0 MB smaller per ABI (§2.2, §2.3) |
| Tile container | **PMTiles v3**, local `file://` | Native support since 11.8.0; 2.9× smaller than MBTiles for the same Australian data (§3.4, §4) |
| Tile schema | **Shortbread 1.0** (z0–14, overzoom above) | Open CC-0 schema, three independent producers, lean layer set (§4.2) |
| Coverage unit | One whole-of-Australia archive (~933 MiB) | Fits comfortably; partitioning adds an install-flow UX cost for little gain (§6) |
| Style | Purpose-built minimal dashcam style, seeded from VersaTiles Neutrino (CC-0) | 207 layers is already a lot for Mali-G57 MP1; Colorful's 324 layers is more (§7.1) |
| Glyphs/sprites | Bundled in APK under `asset://`, ~2.6 MiB total | The classic offline-breakage point; `asset://` is checked first in the file-source waterfall (§3.2, §7.2) |
| Delivery | Play on-demand asset pack, or own bucket + `Range` resume + SHA-256 | Every upstream free mirror either forbids or cannot afford per-install traffic (§5) |
| Rejected | Mapsforge, VTM, osmdroid, Organic Maps | §2.5–§2.8 |

---

## 2. Renderer candidates

### 2.1 MapLibre Native Android — artifacts and versions

All coordinates below were read from Maven Central's own `maven-metadata.xml` and directory listing on 2026-08-22. **[DOCUMENTED]** https://repo1.maven.org/maven2/org/maplibre/gl/

| Artifact | Latest | Rendering backend |
|---|---|---|
| `org.maplibre.gl:android-sdk` | **13.5.1** | **Vulkan** since 13.0.0 |
| `org.maplibre.gl:android-sdk-opengl` | **13.5.1** | OpenGL ES |
| `org.maplibre.gl:android-sdk-vulkan` | **13.5.1** | Vulkan |
| `org.maplibre.gl:android-sdk-vulkan-opengl` | **13.5.1** | multiBackend — both `.so`s, runtime switch |
| `org.maplibre.gl:android-sdk-opengl-debug` / `-vulkan-debug` | 13.5.1 | debug builds |
| `org.maplibre.gl:android-plugin-annotation-v9` | 3.0.2 | markers/lines plugin |
| `org.maplibre.gl:android-plugin-offline-v9` | 3.0.2 | offline region download helper |
| `org.maplibre.gl:android-sdk-ktx-v7` | 3.0.2 | Kotlin extensions |

**Breaking change you must not walk into:** the changelog entry for 13.0.0 reads verbatim *"💥 **Breaking:** Use Vulkan as rendering backend for the `org.maplibre.gl:android-sdk` package. You can still use OpenGL ES with the `org.maplibre.gl:android-sdk-opengl` package."* **[DOCUMENTED]** https://github.com/maplibre/maplibre-native/blob/main/platform/android/CHANGELOG.md — so writing `implementation("org.maplibre.gl:android-sdk:13.5.1")` silently opts you into Vulkan.

`multiBackend` (runtime OpenGL/Vulkan switching) landed in **13.5.0** via PR #4288 and is published as `android-sdk-vulkan-opengl`. **[DOCUMENTED]** (changelog + Maven directory listing).

minSdk of the library is **23** (raised from 21 in 12.0.0, PR #3849); library `compileSdk = 34`, `targetSdk = 33`. **[DOCUMENTED]** https://raw.githubusercontent.com/maplibre/maplibre-native/main/platform/android/MapLibreAndroid/build.gradle.kts. Our `minSdk = 34` is far above the floor.

Transitive dependencies of `android-sdk-opengl:13.5.1`, read from its POM **[DOCUMENTED]** https://repo1.maven.org/maven2/org/maplibre/gl/android-sdk-opengl/13.5.1/android-sdk-opengl-13.5.1.pom:

```
org.maplibre.gl:android-sdk-geojson:6.0.1            (compile)
org.maplibre.gl:maplibre-android-gestures:0.0.4      (compile)
org.jetbrains.kotlin:kotlin-stdlib:2.2.10            (compile)
org.maplibre.gl:android-sdk-turf:6.0.1               (runtime)
androidx.annotation:annotation:1.8.2                 (runtime)
androidx.fragment:fragment:1.8.9                     (runtime)
com.squareup.okhttp3:okhttp:4.12.0                   (runtime)
com.jakewharton.timber:timber:5.0.1                  (runtime)
androidx.interpolator:interpolator:1.0.0             (runtime)
org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2 (runtime)
```

OkHttp 4.12.0 comes for free — reuse it for the map download rather than adding Ktor/another client.

**No API key is needed.** `MapLibre.getInstance(Context)` delegates to `getInstance(context, null, WellKnownTileServer.MapLibre)` — apiKey is nullable and defaults to null. **[DOCUMENTED]** https://raw.githubusercontent.com/maplibre/maplibre-native/main/platform/android/MapLibreAndroid/src/main/java/org/maplibre/android/MapLibre.java

**No telemetry.** MapLibre Native forked from mapbox-gl-native and removed the Mapbox telemetry stack; a code search of `maplibre/maplibre-native` for `events.mapbox.com` returned nothing, and the Android source tree contains no telemetry uploader. **[DOCUMENTED]** (GitHub code search, 2026-08-22). Belt-and-braces lever below.

### 2.2 APK size impact — real `.so` sizes

I downloaded the AARs from Maven Central and read the ZIP central directory. **[DOCUMENTED]** (direct read of the published AAR, 2026-08-22).

| Artifact 13.5.1 | AAR total | `arm64-v8a/libmaplibre.so` uncompressed | in-AAR compressed |
|---|---|---|---|
| `android-sdk-opengl` | 15,592,360 B | **10,843,424 B (10.34 MiB)** | 3,784,077 B |
| `android-sdk` / `android-sdk-vulkan` | 18,437,469 B | **12,818,584 B (12.22 MiB)** | 4,494,034 B |
| `android-sdk-vulkan-opengl` | 33,163,316 B | 12,818,584 + 10,843,424 = **23,662,008 B** | 8,278,111 B |

`armeabi-v7a` equivalents: 7,914,880 B (OpenGL) / 9,459,184 B (Vulkan). `classes.jar` is 805,207 B (OpenGL) / 780,807 B (Vulkan) / 813,588 B (multiBackend). All four ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) are present in every AAR.

**Actionable:** publish an Android App Bundle so Play emits per-ABI splits automatically; both target devices are arm64, so the per-user native payload is one `libmaplibre.so`. If you must ship a universal APK, add `splits { abi { enable = true; reset(); include("arm64-v8a", "armeabi-v7a") } }` — `x86`/`x86_64` are 22 MB of dead weight for phones. Choosing `-opengl` over the Vulkan default saves **1,975,160 B** of installed native code per device and avoids the manifest gate in §2.3. 13.5.1 also carries *"android: reduce Android AAR size with header-only Prefab (#4483)"*, so these numbers are already post-shrink. **[DOCUMENTED]** (changelog 13.5.1).

### 2.3 The Vulkan manifest gate — a real distribution risk

The Vulkan product flavour ships its own manifest fragment, verbatim **[DOCUMENTED]** https://raw.githubusercontent.com/maplibre/maplibre-native/main/platform/android/MapLibreAndroid/src/vulkan/AndroidManifest.xml:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- Vulkan 1.0 required -->
    <uses-feature
        android:name="android.hardware.vulkan.version"
        android:version="0x400003"
        android:required="true" />
</manifest>
```

`0x400003` is Vulkan 1.0.3. Because `android:required="true"`, manifest merging makes **your whole app** require Vulkan 1.0.3, and Google Play will filter it off any device that does not declare `android.hardware.vulkan.version`. **[INFERRED]** from the merge semantics of `uses-feature` + Play's documented feature filtering. Android 14 CDD §7.1.4.2 [C-2-1] says a device without Vulkan 1.0 *"MUST NOT declare any of the Vulkan feature flags"* — so non-Vulkan Android 14 devices are legal and would be excluded. **[DOCUMENTED]** https://source.android.com/docs/compatibility/14/android-14-cdd

Mali-G57 is a first-generation Valhall GPU documented to support OpenGL ES 3.2 and Vulkan up to 1.2, so the Moto G04 itself is almost certainly fine. **[UNVERIFIED]** for this exact SKU/driver — the ARM product page returned HTTP 403 to automated fetch, and the Unisoc T606's shipped Mali driver version is not published. Android 14 CDD §7.1.4.2 [C-1-3] does require any Vulkan-capable Android-14 device to *"fully implement the Vulkan 1.0 Vulkan 1.1 APIs"*, so if the G04 declares Vulkan at all it is at least 1.1. **[DOCUMENTED]** (same CDD URL).

Also note the SDK's *main* manifest, which merges into your app regardless of flavour **[DOCUMENTED]** https://raw.githubusercontent.com/maplibre/maplibre-native/main/platform/android/MapLibreAndroid/src/main/AndroidManifest.xml:

```xml
<uses-feature android:name="android.hardware.wifi" android:required="false"/>
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION"/>
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
```

For an offline-first, no-telemetry product, `ACCESS_WIFI_STATE` in particular reads badly on the Play data-safety form and in the permission list. Strip what you do not need in `app/src/main/AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" tools:node="remove" />
```

Keep `INTERNET` (needed for the one-time map download) and `ACCESS_FINE_LOCATION` (needed anyway for the dashcam GPS overlay). Confirm the final merged manifest with `./gradlew :app:processReleaseManifest` and read `app/build/intermediates/merged_manifest/release/AndroidManifest.xml`. **[INFERRED]** from the merger's documented `tools:node` semantics.

### 2.4 Belt-and-braces: guarantee zero network egress from the renderer

```kotlin
// org.maplibre.android.module.http.HttpRequestUtil
public static void setOkHttpClient(@Nullable Call.Factory client)
```

**[DOCUMENTED]** https://github.com/maplibre/maplibre-native/blob/main/platform/android/MapLibreAndroid/src/main/java/org/maplibre/android/module/http/HttpRequestUtil.java

Install a `Call.Factory` that fails every call with `IOException("offline-first: renderer network disabled")` at app start, *after* your own map downloader has finished. Any accidental `https://` left in a style then fails loudly in logcat instead of silently phoning home. This is the single cheapest way to make "no cloud" enforceable rather than aspirational.

### 2.5 MapLibre Compose (Compose Multiplatform wrapper)

- `org.maplibre.compose:maplibre-compose` — latest release **0.14.0**, snapshots `0.14.1-SNAPSHOT`. **[DOCUMENTED]** https://maplibre.org/maplibre-compose/getting-started/
- Licence **BSD-3-Clause**; feature completeness self-reported as Android 90%, iOS 90%, Desktop 85%, Web 80%. **[DOCUMENTED]** https://github.com/maplibre/maplibre-compose
- Its docs explicitly mention *"MapLibre Android 13 and later"* using the Vulkan renderer by default and list `org.maplibre.gl:android-sdk-opengl:13.0.2` as the OpenGL swap-in. **[DOCUMENTED]** (getting-started page).
- Its documentation says nothing about PMTiles or MBTiles. **[DOCUMENTED]** (absence, same page).

**Recommendation:** do **not** put maplibre-compose on the critical path. Roadguard's map is a static, near-frozen viewport with a follow-me puck; the value of a declarative wrapper is low and the cost is an extra abstraction between you and `MapView.setMaximumFps()`/`pixelRatio` — the exact two levers you need for thermal control. Use `MapView` inside `AndroidView { }` directly. If you later want Compose interop, wrap it yourself:

```kotlin
AndroidView(
  factory = { ctx -> MapView(ctx, mapLibreMapOptions) },
  update  = { /* camera updates */ },
  onReset = { },
  onRelease = { it.onDestroy() }
)
```

and forward the full lifecycle (`onStart/onResume/onPause/onStop/onDestroy/onLowMemory`) — MapLibre's `MapView` is not lifecycle-aware on its own.

### 2.6 Mapsforge — rejected

- Maven Central has **only up to `org.mapsforge:mapsforge-map-android:0.25.0`** (`lastUpdated 2025-04-04`). **[DOCUMENTED]** https://repo1.maven.org/maven2/org/mapsforge/mapsforge-map-android/maven-metadata.xml
- Current upstream is **0.30.0 (2026-08-20)**, distributed via **JitPack only**: `com.github.mapsforge.mapsforge:mapsforge-map-android:0.30.0@jar` plus `com.caverock:androidsvg:1.4`. **[DOCUMENTED]** https://raw.githubusercontent.com/mapsforge/mapsforge/master/docs/Changelog.md and https://raw.githubusercontent.com/mapsforge/mapsforge/master/docs/Integration.md
- Licence: **LGPL v3 with §4(d) and §4(e) waived** — the README states *"you are allowed to include Mapsforge library in your Android application, without making your application open source."* **[DOCUMENTED]** https://raw.githubusercontent.com/mapsforge/mapsforge/master/README.md. So licensing is *not* the blocker.
- Data: `https://download.mapsforge.org/maps/v5/australia-oceania/australia.map` — **1,670,623,747 B (1.56 GiB)**, `Last-Modified: Sun, 08 Feb 2026` (i.e. six months stale at time of writing), `Accept-Ranges: bytes`, **no `.md5`/`.sha1`/`.sha256` companion (all 404)**. **[DOCUMENTED]** (HTTP HEAD + probes, 2026-08-22).
- The download server's own landing page labels itself *"Mapsforge Server (not suitable for mass downloads)"* and points at a university mirror (`ftp-stud.hs-esslingen.de`) as the fast path. **[DOCUMENTED]** https://download.mapsforge.org/
- No state-level Australian splits exist on the official server — the directory contains exactly one `australia.map`. **[DOCUMENTED]** (directory index read 2026-08-22).

**Why it's rejected on thermals, not licence:** Mapsforge renders vector `.map` data to raster bitmaps on the **CPU** through `android.graphics.Canvas`, then composites them as tiles. On the Moto G04 the CPU is 2×Cortex-A75 + 6×Cortex-A55 at 1.6 GHz **[UNVERIFIED — aggregator specs, Motorola's own spec page does not list core topology]**, and that same CPU is servicing camera capture callbacks, MediaCodec input, muxing and fsync for the recorder. Adding a CPU rasteriser that spikes on every pan/zoom is the wrong trade when recording reliability is priority #1 and thermal management is #2. GPU vector rendering (MapLibre) moves that work to a unit the encoder does not contend for. **[INFERRED]** — chain: Mapsforge = CPU raster (documented by its own architecture, `mapsforge-map-android` uses `AndroidGraphicFactory`/`Canvas`), recording = CPU+hardware-encoder bound, therefore CPU contention; the magnitude is **NOT VERIFIED — needs on-device measurement**.

Keep Mapsforge on the shelf as a *fallback* only if MapLibre GPU rendering turns out to be the thermal offender on the G04 (see §10.2).

### 2.7 VTM / oscim — rejected

`mapsforge/vtm` is the maintained fork of OpenScienceMap VTM: OpenGL vector rendering, LGPL v3, and it reads *both* Mapsforge `.map` files **and** "MBTiles vector & raster". **[DOCUMENTED]** https://raw.githubusercontent.com/mapsforge/vtm/master/README.md. Maven Central tops out at `org.mapsforge:vtm-android:0.25.0` (`lastUpdated 2025-04-04`); newer builds are JitPack-only. **[DOCUMENTED]** https://repo1.maven.org/maven2/org/mapsforge/vtm-android/maven-metadata.xml

It is technically the closest competitor to MapLibre (GPU vector, offline-native, MBTiles-capable). It loses on: no PMTiles support (so you carry the 2.9× MBTiles size penalty, §3.4), no MapLibre Style Spec (you write VTM XML render themes instead, so you cannot reuse any of the open Shortbread styles), a much smaller contributor base, and no Vulkan path for future devices. **[DOCUMENTED]** for the feature list; **[INFERRED]** for the conclusion.

### 2.8 osmdroid — rejected

- Latest Maven Central release `org.osmdroid:osmdroid-android:6.1.20`, `lastUpdated 2024-08-18`. **[DOCUMENTED]** https://repo1.maven.org/maven2/org/osmdroid/osmdroid-android/maven-metadata.xml
- osmdroid is a **raster** tile framework. Its offline support covers "osmdroid's flavor of a sqlite database (recommended), osmdroid ZIP, MBTiles, GEMF, Mapsforge, and GeoPackage" — but MBTiles support means *raster* MBTiles; vector `.pbf` tiles in an MBTiles archive are not rendered (tracked as osmdroid issue #610). **[DOCUMENTED]** https://github.com/osmdroid/osmdroid/wiki/Offline-Map-Tiles and issue #610.

A raster Australia at z0–16 would be an order of magnitude larger than 933 MiB of vector tiles and would not restyle for night driving. **[INFERRED]** — chain: raster tiles store rendered pixels per z/x/y with no cross-tile dedup and no client-side theming; vector tiles store geometry once and are re-styled at draw time. Rejected.

### 2.9 Organic Maps — not usable as a library

`organicmaps/api-android` is described by the project as a thin Java wrapper around the Organic Maps **deep-link API**, so a third-party app can ask the *separately installed* Organic Maps app to show a point. It does **not** expose the Drape rendering engine as an embeddable renderer. **[DOCUMENTED]** https://github.com/organicmaps/api-android

Conclusion, stated plainly for the record: **Organic Maps cannot be embedded in Roadguard as a map view.** Using it would mean launching a second app, which breaks the ~50/50 video+map layout requirement outright.

---

## 3. What MapLibre Native actually supports for local tiles

This is the section where most projects get it wrong, so everything here is read from published source rather than from blog posts.

### 3.1 The URL scheme table (from `constants.hpp`)

```cpp
// include/mbgl/util/constants.hpp
constexpr const char* ASSET_PROTOCOL   = "asset://";
constexpr const char* FILE_PROTOCOL    = "file://";
constexpr const char* MBTILES_PROTOCOL = "mbtiles://";
constexpr const char* PMTILES_PROTOCOL = "pmtiles://";
constexpr uint32_t DEFAULT_MAXIMUM_CONCURRENT_REQUESTS = 20;
```

**[DOCUMENTED]** https://github.com/maplibre/maplibre-native/blob/main/include/mbgl/util/constants.hpp — all four schemes are real, first-class file sources compiled into the Android build (`platform/android/android.cmake` lists `local_file_source.cpp`, `mbtiles_file_source.cpp`; `platform/default/src/mbgl/storage/file_source_manager.cpp` registers `FileSourceType::Mbtiles` and `FileSourceType::Pmtiles`).

### 3.2 The resolution waterfall — why local reads never hit the cache DB

`MainResourceLoaderThread::request` resolves in this exact order **[DOCUMENTED]** https://github.com/maplibre/maplibre-native/blob/main/platform/default/src/mbgl/storage/main_resource_loader.cpp:

1. `assetFileSource` (`asset://`)
2. `mbtilesFileSource` (`mbtiles://`)
3. `pmtilesFileSource` (`pmtiles://`)
4. `localFileSource` (`file://`)
5. `databaseFileSource` (ambient cache / offline packs) → network fallback
6. `onlineFileSource`

`PMTilesFileSource` obtains its inner file source as `FileSourceManager::get()->getFileSource(FileSourceType::ResourceLoader, ...)` and re-enters this same waterfall with the inner URL. For `pmtiles://file:///…`, the inner URL is `file:///…`, which is matched at **step 4 — before the database at step 5**.

**Consequence, and it matters a lot for storage safety:** a local PMTiles/MBTiles archive is **never** copied into the ambient-cache SQLite database. Your ~933 MiB file is stored exactly once. **[INFERRED]** — chain: cache insertion happens only in the step-5 `databaseFileSource->forward(...)` path inside `requestFromNetwork`, which local URLs never reach.

The same holds for `asset://` glyphs and sprites (step 1). So a fully-local style produces a **zero-byte** ambient cache. Belt and braces:

```kotlin
OfflineManager.getInstance(context).setMaximumAmbientCacheSize(0L, callback)
FileSource.setResourcesCachePath(File(context.filesDir, "maplibre-cache").absolutePath, callback)
```

`setMaximumAmbientCacheSize(long, FileSourceCallback?)` and `FileSource.setResourcesCachePath(String, ResourcesCachePathChangeCallback)` both exist. **[DOCUMENTED]** https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.offline/-offline-manager/index.html and https://github.com/maplibre/maplibre-native/blob/main/platform/android/MapLibreAndroid/src/main/java/org/maplibre/android/storage/FileSource.java

### 3.3 PMTiles — the precise truth (and the common misconception, corrected)

The widespread claim that "MapLibre Native cannot read PMTiles, you need a plugin like `pmtiles.js`" is **true for MapLibre GL JS and false for MapLibre Native**. The official Android example page states:

> *"Starting MapLibre Android 11.7.0, PMTiles archives are supported as tile sources."*

and gives working Kotlin **[DOCUMENTED]** https://maplibre.org/maplibre-native/android/examples/data/PMTiles/:

```kotlin
val path = getExternalFilesDir(null)?.absolutePath + "/watercolor.pmtiles"
val source = RasterSource(
    "watercolor",
    "pmtiles://file://$path",
    256
)
```

(The changelog attributes the feature to **11.8.0**, PR #2882; the docs page says 11.7.0. Use ≥ 11.8.0 to be safe, and we are on 13.5.1 anyway.) **[DOCUMENTED]** (both sources; the discrepancy is noted, not resolved.)

Facts and hard limits, from the docs page and from `pmtiles_file_source.cpp` **[DOCUMENTED]** https://github.com/maplibre/maplibre-native/blob/main/platform/default/src/mbgl/storage/pmtiles_file_source.cpp:

| Property | Value |
|---|---|
| URL form | `pmtiles://<fully-qualified-inner-URL>` — the inner URL must be complete, unlike GL JS |
| Local file | `pmtiles://file:///absolute/path/archive.pmtiles` — **supported** |
| Remote | `pmtiles://https://host/archive.pmtiles` — supported, uses HTTP `Range` |
| APK assets | `pmtiles://asset://…` — **NOT supported**. Docs: *"`AssetManagerFileSource` does not implement byte-range reads, which PMTiles requires to read its header and metadata."* Confirmed in source: `asset_manager_file_source.cpp` calls `AAssetManager_open(...AASSET_MODE_BUFFER)` + `AAsset_getBuffer` and ignores `resource.dataRange` entirely |
| Byte ranges on `file://` | Supported — `local_file_request.cpp` takes `const std::optional<std::pair<uint64_t,uint64_t>>& dataRange` and calls `util::readFile(path, dataRange)` |
| Header read | `pmtiles::deserialize_header(response.data->substr(0, 127))` at offset 0, length 127 |
| Compression | **Only `COMPRESSION_NONE` and `COMPRESSION_GZIP`** for *both* `internal_compression` and `tile_compression`; anything else throws `"Compression method not supported"`. **Brotli- or Zstd-compressed PMTiles will not load.** |
| Source types | vector, raster, raster-dem — via style JSON or programmatic `VectorSource`/`RasterSource` |
| Offline packs | The docs page states *"PMTiles sources do not support offline pack downloads or caching."* Since 13.3.0 a byte-range ambient cache exists for **remote** PMTiles (PR #4290, merged 2026-06-06, ETag-invalidated), described by its author as still experimental. Irrelevant for us: we hold the whole archive locally |
| Thread | Runs on a dedicated `util::Thread<Impl>` with `platform::EXPERIMENTAL_THREAD_PRIORITY_FILE` |
| Error surface | `Response::Error::Reason::NotFound` with message `"path not found: <path>"` when the `file://` target is missing |

**Consequence for the install flow:** because `pmtiles://asset://` does not work, you **cannot** ship the archive inside the APK's `assets/` and read it in place. It must land as a real file on the filesystem. That is fine — it also means the archive is not duplicated between the APK and extracted storage.

### 3.4 MBTiles — supported, with sharp edges

`MBTilesFileSource` is real and Android-compiled. From its header: *"File source for supporting .mbtiles maps. can only load resource URLS that are absolute paths to local files"*. **[DOCUMENTED]** https://github.com/maplibre/maplibre-native/blob/main/src/mbgl/storage/mbtiles_file_source.hpp

Behaviour read from `mbtiles_file_source.cpp` **[DOCUMENTED]** https://github.com/maplibre/maplibre-native/blob/main/platform/default/src/mbgl/storage/mbtiles_file_source.cpp:

- URL form: `mbtiles:///absolute/path/file.mbtiles`. A relative path is rejected with `"MBTilesFileSource only supports absolute path urls"`. Confirmed by unit test `test/storage/mbtiles_file_source.test.cpp` which asserts `mbtiles://not_absolute` fails.
- `asset://` has **no** equivalent for `mbtiles://` — open feature request organicmaps-style at maplibre-native issue **#3559** ("Load mbtiles from apk assets on Android", opened 2025-06-16, no maintainer decision). The documented workaround is *"Copy the mbtiles from assets into the app's data directory, and load it by file path from there."* **[DOCUMENTED]** https://github.com/maplibre/maplibre-native/issues/3559
- TileJSON is synthesised from `SELECT * from metadata`. The `json` key is parsed as an object and merged; `format`, `minzoom`, `maxzoom`, `bounds`, `scale` get special handling.
- **Performance trap:** if `minzoom` *or* `maxzoom` is absent from `metadata`, the source runs `SELECT MIN(zoom_level),MAX(zoom_level) from tiles` — a full-table aggregate. On a 2.8 GB archive on a Moto G04's UFS 2.2 that is a synchronous stall on the file thread at first style load. **Always verify `minzoom`/`maxzoom` exist in `metadata`.**
- **Bounds trap:** `bounds` is only copied into the TileJSON when `format != "pbf"`. For a vector (`format = "pbf"`) archive the map's source therefore has **no bounds**, and MapLibre will happily let the user pan to Antarctica and render nothing. Constrain the camera yourself with `MapLibreMap.setLatLngBoundsForCameraTarget(LatLngBounds)` (exists at line 1709 of `MapLibreMap.java`). **[DOCUMENTED]** (source read).
- Tile lookup: `SELECT tile_data FROM tiles where zoom_level = z AND tile_column = x AND tile_row = (2^z - 1) - y` — TMS row flip handled internally; `response.expires = Timestamp::max()` so tiles never expire.
- Gzip is auto-detected and inflated: `if (util::is_compressed(*response.data)) response.data = decompress(...)`.
- One `sqlite::Database` per path is cached, opened `ReadOnly`, on the same dedicated file thread.

### 3.5 The size verdict: PMTiles vs MBTiles for identical Australian data

Two independent producers publish the *same* Shortbread 1.0 schema at the *same* zoom range (0–14) for the *same* Australian region. I read both artifacts' real byte sizes over HTTP on 2026-08-22, and for the ZIP-wrapped ones I read the ZIP central directory by `Range` request so the *uncompressed* payload size is exact, not estimated.

| Artifact | Container | Uncompressed bytes | MiB |
|---|---|---|---|
| BBBike `australia.pmtiles` (Shortbread) | PMTiles v3 | **978,786,994** | 933.4 |
| Geofabrik `australia-shortbread-1.0.mbtiles` | MBTiles/SQLite | **2,828,152,832** | 2,697.1 |
| BBBike `australia.mbtiles` (Shortbread) | MBTiles/SQLite | **2,906,783,744** | 2,772.1 |

**PMTiles is 2.89× smaller than Geofabrik's MBTiles and 2.97× smaller than BBBike's own MBTiles of the same data.** **[DOCUMENTED]** (direct artifact reads, 2026-08-22.)

The mechanism, read out of the PMTiles v3 header of the BBBike Australia archive (I inflated the first 200 KB of the ZIP's deflate stream to get bytes 0–126):

```
magic            PMTiles, spec version 3
minzoom 0, maxzoom 14, tile_type mvt
internal_compression gzip, tile_compression gzip, clustered 0
addressed tiles  18,302,279
tile entries      2,438,039
tile contents     1,266,421      <-- 14.45x dedup vs addressed
tile data          971,069,548 bytes
bbox             68.13342, -57.07106  ..  169.0016, 0.0
```

18.3 M addressed tiles collapse to 1.27 M unique tile bodies — a 14.45× deduplication factor, because Australia is dominated by identical empty ocean and empty-desert tiles at high zoom. MBTiles as produced by both vendors stores one row per addressed tile plus a `CREATE UNIQUE INDEX tile_index on tiles (zoom_level, tile_column, tile_row)` B-tree over ~18 M rows. **[DOCUMENTED]** — the Geofabrik schema and metadata were read by parsing the SQLite file's page 1 and its `metadata` table root page over HTTP `Range`:

```sql
CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob)
CREATE UNIQUE INDEX tile_index on tiles (zoom_level, tile_column, tile_row)
CREATE TABLE metadata(name TEXT, value TEXT)
-- page_size 4096, page_count 690,467  (= 2,828,152,832 B exactly)
-- metadata rows:
--   name=Shortbread  type=baselayer  version=1.0  format=pbf
--   minzoom=0  maxzoom=14
--   author='OpenStreetMap contributors, Geofabrik GmbH'
--   license='Open Database License 1.0'
--   bounds=68.13342,-57.07106,169.0016,-8.809565
--   center=118.56751,-32.940312500000005,7
```

Good news for the MBTiles fallback path: Geofabrik **does** populate `minzoom`/`maxzoom`, so the §3.4 full-scan trap does not fire, and the `tile_index` exists so tile lookups are indexed.

**This 1.76 GiB saving on a low-storage phone is, on its own, sufficient reason to choose PMTiles.**

---

## 4. Australia-wide data sources — real URLs, real sizes, real terms

Every size in this section was obtained from the provider's own page text or from an HTTP `HEAD`/`Range` against the published artifact on **2026-08-22**. Sizes drift daily; treat them as the current order of magnitude and re-read at build time.

### 4.1 Ready-to-render vector tile archives (the ones that matter)

| # | Artifact | URL | Size (bytes) | Schema / zooms | Checksums | Range? | Freshness |
|---|---|---|---|---|---|---|---|
| A | **BBBike Australia Shortbread PMTiles** | `https://data.bbbike.org/osm/pmtiles/region/australia-oceania/australia/australia.osm.pmtiles-shortbread.zip` | zip **929,815,593**; inner `australia.pmtiles` **978,786,994** | Shortbread 1.0, z0–14, MVT, gzip | **MD5 inside the zip** (`CHECKSUM.txt` = `1178551fd4055d36ad11a0bd65a73639  australia.pmtiles`) | yes | built 2026-08-17 |
| B | **BBBike Australia Shortbread MBTiles** | `.../osm/mbtiles/region/australia-oceania/australia/australia.osm.mbtiles-shortbread.zip` | zip **1,261,645,789**; inner **2,906,783,744** | Shortbread 1.0, z0–14 | MD5 inside the zip | yes | 2026-08-17 |
| C | **Geofabrik Australia Shortbread MBTiles** | `https://download.geofabrik.de/australia-oceania/australia-shortbread-1.0.mbtiles` | **2,828,152,832** | Shortbread 1.0, z0–14 | **none** (`.md5` → 404) | yes | `Last-Modified: 2026-08-22 02:46 UTC` |
| D | **Protomaps planet PMTiles** (build channel) | `https://build.protomaps.com/{YYYYMMDD}.pmtiles` — e.g. `20260821.pmtiles` | **137,442,546,988** (128.0 GiB) | Protomaps Basemap v4, z0–15, MVT, gzip | **MD5 + BLAKE3** in `https://build-metadata.protomaps.dev/builds.json` | yes (`accept-ranges: bytes`, ETag) | daily |
| E | **VersaTiles planet** `osm.versatiles` | `https://download.versatiles.org/osm.versatiles` | ~62 GB (page text) | Shortbread, z0–14 | `.md5` **and** `.sha256` companions | yes | dated snapshots + per-tileset RSS |

Notes on each:

**A/B (BBBike).** The download page for Australia lists the formats and sizes as page text: *"MB vector tiles shortbread | 1.2GB", "PM vector tiles shortbread | 887M"*. **[DOCUMENTED]** https://data.bbbike.org/osm/region/australia-oceania/australia/ . BBBike also runs a **custom-region** service (`https://extract.bbbike.org/`) whose format list includes `PM vector tiles shortbread` and `MB vector tiles shortbread`, with a documented limit of *"a rectangle or polygon up to 6000 x 4000km large, or 512MB file size"*. **[DOCUMENTED]** https://download.bbbike.org/osm/ . Australia's bbox fits the geometry limit but a whole-Australia Shortbread PMTiles is 933 MiB, i.e. **above the 512 MB custom-extract cap** — so custom extracts are only useful for sub-Australia regions. The pre-built regional file (A) has no such cap. The `README.txt` inside the archive credits *"tilemaker by https://github.com/systemed/tilemaker"* and *"Map style (c) by systemed, BBBike.org"*, and asks for donations: *"We need to raise 20 Euro (25 USD) by the end of the day or 600 Euro (700 USD) per month to cover the server costs."* **[DOCUMENTED]** (read from inside the archive).

**C (Geofabrik).** Geofabrik labels these *"experimental, non-updated shortbread tiles downloads"* on the Shortbread project's own download page. **[DOCUMENTED]** https://shortbread-tiles.org/download/ — despite the "non-updated" wording, the `Last-Modified` header on the Australia file was 2026-08-22 02:46 UTC when I read it. Shortbread MBTiles exist for **`australia` as a whole and for each Oceania island group, but not for Australian states** — I enumerated the `australia-oceania/` and `australia-oceania/australia/` directory listings; the latter contains no `*-shortbread-*` files at all. So Geofabrik cannot give you per-state vector tiles.

**D (Protomaps).** The docs state plainly: *"A full planet file is roughly 120 gigabytes, including zoom levels from 0 to 15. Please note that URLs may change and hotlinking to these downloads are discouraged. Instead, you should copy the tileset to your own Cloud Storage."* **[DOCUMENTED]** https://docs.protomaps.com/basemaps/downloads . The build index at `https://build-metadata.protomaps.dev/builds.json` is a plain JSON array; the newest entry when I read it was:

```json
{"key":"20260821.pmtiles","size":137442546988,
 "md5sum":"v7V13he4cHkATww0pdJKRg==",
 "b3sum":"75cbdc9f1bacdb04e700c26219366105c7e8fcd060e0aabec08d19b7d9f6b729",
 "uploaded":"2026-08-21T09:06:02.061Z","version":"4.15.2"}
```

I confirmed `HEAD https://build.protomaps.com/20260821.pmtiles` → `content-length: 137442546988`, `accept-ranges: bytes`, `etag: "8f393bb61f1c7476026ae8e9665c47f2-513"`, and a `Range: bytes=0-63` request returning `206`. I then read bytes 0–126 and decoded the PMTiles v3 header:

```
spec version 3 | minzoom 0 | maxzoom 15 | tile_type mvt
internal_compression gzip | tile_compression gzip | clustered 1
root dir  offset 127          length 15,565
metadata  offset 137,091,376,932 length 1,178
leaf dirs offset 137,091,378,110 length 351,168,878
tile data offset 16,384        length 137,091,360,548
addressed tiles 1,431,655,765 | tile entries 177,330,800 | tile contents 135,439,926
bbox -180, -85.0511287, 180, 85.0511287
```

**[DOCUMENTED]** (direct artifact read). `clustered = 1` matters: `pmtiles extract` requires a clustered source archive. Retention: *"All builds for the past week. The latest build for each patch version (e.g. 4.3.0)."*

**E (VersaTiles).** *"Every entry there has a Download button that builds the command for you: pick the whole planet or a bounding box, the container format (`.versatiles`, `.pmtiles`, `.mbtiles` or `.tar`), a zoom range…"* and *"Each file has `.md5` and `.sha256` companions to verify the download."* Partial download is done client-side with `versatiles convert --bbox-border 3 --bbox "…" <url> out.pmtiles`. **[DOCUMENTED]** https://docs.versatiles.org/guides/download_tiles.html . The download index carries the notice *"Please use this download service only to download these files."* **[DOCUMENTED]** https://download.versatiles.org/ . Licence: *"OpenStreetMap data is licensed under Open Database License 1.0 and requires attribution to OpenStreetMap contributors. The Shortbread schema is licensed under CC-0 and does not require additional attribution."* **[DOCUMENTED]** https://docs.versatiles.org/basics/tilesets

### 4.2 Why Shortbread 1.0 is the right schema

- *"Shortbread vector tiles have a minzoom of 0 and a maxzoom of 14. Higher client zooms are achieved with overzoomed zoom 14 tiles."* **[DOCUMENTED]** https://shortbread-tiles.org/schema/1.0/
- Schema documentation is CC-0. Producers: Geofabrik, BBBike (tilemaker), VersaTiles (planetiler), plus tilemaker/planetiler/osm2pgsql/BBOX/Tilekiln all ship Shortbread configs. **[DOCUMENTED]** https://shortbread-tiles.org/
- Open MapLibre styles exist for it: VersaTiles **Colorful / Neutrino / Graybeard / Eclipse / Shadow / Satellite**, and a minimal `Shortbread-Demo-MapLibre`. **[DOCUMENTED]** https://shortbread-tiles.org/styles/
- z14 + overzoom is exactly right for a dashcam: at driving speed you display roughly z15–z17, all of which MapLibre renders by overzooming z14 geometry. You pay no storage for z15/z16 and lose nothing visually except sub-building detail nobody needs at 100 km/h. **[INFERRED]** from the documented overzoom behaviour.

The alternative is the **Protomaps Basemap v4** schema (z0–15, richer, styled by the BSD-3-Clause `@protomaps/basemaps` npm package v5.7.2). **[DOCUMENTED]** https://registry.npmjs.org/@protomaps/basemaps/latest . It is a fine schema, but there is no ready-made Australia extract — you must run `pmtiles extract` against the 128 GiB planet, and the extra zoom level roughly doubles size (§6).

### 4.3 Raw OSM extracts (for building your own tiles)

| Source | URL | Size | Checksums | Notes |
|---|---|---|---|---|
| Geofabrik `australia` | `https://download.geofabrik.de/australia-oceania/australia-latest.osm.pbf` | **914 MB** (page text) | `.md5` present (`australia-latest.osm.pbf.md5` → HTTP 200) | daily, ~21:00 CET |
| Geofabrik `australia-oceania` | `https://download.geofabrik.de/australia-oceania-latest.osm.pbf` | **1.5 GB**; dated `australia-oceania-260821.osm.pbf` = **1,558,925,007 B** | `.md5` | daily |
| BBBike `australia` | `https://data.bbbike.org/osm/pbf/region/australia-oceania/australia.osm.pbf` | **1,057,250,773 B** | (not probed) | *"PBF files will be updated daily at 0:00 UTC"* |
| Geofabrik per-state | `https://download.geofabrik.de/australia-oceania/australia/{state}-latest.osm.pbf` | NSW 253 MB · VIC 229 MB · QLD 187 MB · WA 108 MB · SA 63 MB · TAS 51 MB · ACT 17.9 MB · NT 16.9 MB | `.md5` per dated file | also `ashmore-cartier`, `christmas-island`, `cocos-islands`, `coral-sea-islands`, `heard-mcdonald`, `norfolk-island` |

**[DOCUMENTED]** — all read from the Geofabrik region pages (`File size:` text) and the BBBike region page / `HEAD`, 2026-08-22.

Machine-readable Geofabrik index: `https://download.geofabrik.de/index-v1-nogeom.json` — a GeoJSON `FeatureCollection` whose per-region `urls` object carries `pbf`, `bz2`, `shp`, `updates`, `taginfo`. *"The structure is guaranteed to remain stable; if we make changes to the structure we will use a different version number for the name of the file."* **[DOCUMENTED]** https://download.geofabrik.de/technical.html . Note it does **not** list the Shortbread MBTiles URLs — I verified this by loading the JSON and printing the `australia-oceania` entry.

Geofabrik's `robots.txt` disallows crawlers from `*.osm.pbf`, `*.shp.zip`, `*.osc.gz`, `*.md5`, `*updates*` — but **not** `*.mbtiles`. **[DOCUMENTED]** https://download.geofabrik.de/robots.txt . That is a crawler directive, not a licence term, but it signals the operator's intent about automated traffic.

### 4.4 Toolchain versions for building your own archive

| Tool | Version | Evidence |
|---|---|---|
| `planetiler` | **0.9.1** on Maven Central (`com.onthegomap.planetiler:planetiler-core`); README's planet benchmark table references **v0.10.1** | Maven Central search API; https://github.com/onthegomap/planetiler README |
| `pmtiles` CLI (`go-pmtiles`) | **v1.31.2** (2026-07-22) | `https://proxy.golang.org/github.com/protomaps/go-pmtiles/@latest` |
| `tilemaker` | used by BBBike for artifact A | archive `README.txt` |
| `versatiles` CLI | Rust; `versatiles convert --bbox …` | https://docs.versatiles.org/guides/download_tiles.html |

Planetiler supports exactly what we need **[DOCUMENTED]** https://github.com/onthegomap/planetiler:

- `--area=australia` downloads the Geofabrik extract automatically (`--download`, `--only-download`)
- `--output=australia.pmtiles` emits PMTiles directly (*"for example `--output=australia.pmtiles` creates a pmtiles archive named `australia.pmtiles`"*)
- a Shortbread schema config ships in-tree at `planetiler-custommap/src/main/resources/samples/shortbread.yml`

So the reproducible build command is roughly:

```bash
java -Xmx4g -jar planetiler.jar \
  planetiler-custommap/src/main/resources/samples/shortbread.yml \
  --download --area=australia \
  --output=/out/australia-shortbread.pmtiles \
  --maxzoom=14
pmtiles verify /out/australia-shortbread.pmtiles
sha256sum /out/australia-shortbread.pmtiles > /out/australia-shortbread.pmtiles.sha256
```

**[INFERRED]** — assembled from the documented flags; the exact flag set and the resulting file size are **NOT VERIFIED — needs a real build run** (see final section).

### 4.5 Licence and terms-of-use per source — what actually constrains us

| Source | Data licence | Automated / per-install download by an app? |
|---|---|---|
| **OSM data itself** | **ODbL 1.0**; attribution to "OpenStreetMap" required for public Produced Works | Permitted — ODbL does not restrict redistribution |
| Protomaps basemap builds | *"distributed as an Open Database License Produced Work (OpenStreetMap attribution required)"*; Protomaps software is BSD | **Discouraged**: *"hotlinking to these downloads are discouraged. Instead, you should copy the tileset to your own Cloud Storage."* **[DOCUMENTED]** |
| Geofabrik download server | Footer: *"Data: ODbL 1.0"*, *"Map tiles: Creative Commons BY-SA 2.0"*; MBTiles `metadata.license = 'Open Database License 1.0'` | **No published bulk-download policy either way.** Server is offered *"free of charge by Geofabrik GmbH"*. `robots.txt` blocks crawlers from `.osm.pbf`/`.md5`. **[DOCUMENTED]** for the quotes; the permissibility of per-install app traffic is **UNVERIFIED — email info@geofabrik.de before relying on it** |
| BBBike | *"All Data from OpenStreetMap is licensed under the OpenStreetMap License"* | Donation-funded at ~€600/month for server costs, per the README inside the archive. Shipping 933 MiB per install from here would be abusive. **[INFERRED]** from the stated funding position |
| Mapsforge server | *"All map data © OpenStreetMap contributors … licensed under the Open Data Commons Open Database License (ODbL)"* | **Explicitly labelled** *"not suitable for mass downloads"* **[DOCUMENTED]** |
| VersaTiles | ODbL for the OSM data; Shortbread schema CC-0; styles CC-0 (`metadata.license = https://creativecommons.org/publicdomain/zero/1.0/`); fonts OFL | *"Please use this download service only to download these files."* **[DOCUMENTED]** — permits downloading, not per-user app fan-out |

**The operative conclusion:** because OSM data is ODbL and none of these providers own the data, **you are free to redistribute the tiles yourself** — you are not free to make *their* servers pay for your installs. Build once, host once, attribute properly.

---

## 5. Distribution: how the file gets onto the phone, automatically

Requirement restated: *"fully AUTOMATIC first-run installation (the user must never hand-place map files)"*.

### 5.1 Option 1 (recommended) — Google Play on-demand asset pack

Google Play's documented size limits **[DOCUMENTED]** https://support.google.com/googleplay/android-developer/answer/9859372 :

| Limit | Value |
|---|---|
| Base module | 500 MB |
| Individual feature module | 500 MB |
| **Individual asset pack** | **1.5 GB** |
| Cumulative: all modules + install-time asset packs | 4 GB |
| Cumulative: on-demand + fast-follow asset packs | 30 GB |
| Total app bundle compressed download | 34 GB |

A 933 MiB Australia PMTiles fits in **one** asset pack. Delivery modes are `install-time`, `fast-follow`, `on-demand`; *"install-time asset packs require at least two times the size of all the asset packs"* of free disk, whereas *"fast-follow and on-demand packs require only a few hundred extra MBs."* **[DOCUMENTED]** https://developer.android.com/guide/playcore/asset-delivery

Library: `com.google.android.play:asset-delivery:2.3.0` (or `asset-delivery-ktx:2.3.0`). **[DOCUMENTED]** https://dl.google.com/dl/android/maven2/com/google/android/play/group-index.xml

Why this is the best fit for Roadguard:
- Google Play does the resumable download, integrity checking, CDN and retry — code you do not write and cannot get wrong. **[INFERRED]**
- Zero hosting cost and zero abuse of a volunteer mirror.
- `fast-follow` gets the map on the device before first launch in the common case.
- Storage overhead during install is *"a few hundred extra MBs"*, not 2× — critical on a 64 GB device.

Caveats, stated honestly: asset packs are a Play mechanism, so an F-Droid / sideloaded build needs Option 2 as well; and **whether an `on-demand`/`fast-follow` asset pack is exposed to native code as a plain filesystem path suitable for `pmtiles://file://` is NOT VERIFIED — needs on-device measurement.** `AssetPackLocation.assetsPath()` is documented to return a directory for non-install-time packs, which would give a real path; install-time packs are served through `AssetManager` and would be unusable for PMTiles (§3.3). Test on the G04 before committing.

### 5.2 Option 2 — own bucket + resumable HTTP

Every candidate host I probed on 2026-08-22 returned `accept-ranges: bytes` and a stable `ETag`:

| Host | `accept-ranges` | ETag | Range test |
|---|---|---|---|
| `build.protomaps.com` (Cloudflare) | yes | `"8f393bb…-513"` | `206 Partial Content`, correct `content-range` |
| `download.geofabrik.de` (Apache + squid) | yes | `"a8923000-65999c1683581"` | — |
| `data.bbbike.org` | yes | — | used to read the ZIP central directory |
| `download.mapsforge.org` + `ftp-stud.hs-esslingen.de` mirror | yes | `"6393b203-…"` | — |

**[DOCUMENTED]** (HTTP HEAD / Range, 2026-08-22).

Download pipeline (all APIs verified to exist at the stated levels):

1. **Manifest first.** Host a tiny signed JSON next to the archive:
   `{ "version": "2026-08-17", "file": "australia-shortbread-14.pmtiles", "bytes": 978786994, "sha256": "…", "schema": "shortbread-1.0", "minzoom": 0, "maxzoom": 14, "bbox": [112.9,-43.7,153.7,-9.1] }`
   The manifest is what makes the install *verifiable* and the *update* decidable. None of the upstream mirrors give you this, which is another reason to host your own.
2. **Space check before writing.** `StorageManager.getUuidForPath(File)` (API 24), `getAllocatableBytes(UUID)` (API 26), `allocateBytes(UUID, long)` (API 26). The Android guide's own example is exactly this pattern, and `getAllocatableBytes` counts clearable cache, so it reports more than `File.getUsableSpace()`. **[DOCUMENTED]** https://developer.android.com/training/data-storage/app-specific . Require `bytes × 1.15` headroom, not `bytes` — leave the recorder its own margin.
3. **Destination.** `context.getExternalFilesDir(null)` → `…/Android/data/<pkg>/files/maps/`. *"On Android 4.4 (API level 19) or higher, your app doesn't need to request any storage-related permissions to access app-specific directories within external storage"* and *"The files stored in these directories are removed when your app is uninstalled."* **[DOCUMENTED]** (same URL). Use `getExternalFilesDir` rather than `getFilesDir` so the map does not count against the (smaller, quota-managed) internal partition and so users can see the size in Settings → Storage.
4. **Resumable transfer.** WorkManager (`androidx.work:work-runtime-ktx:2.11.2` — latest stable; 2.12.0 is still `-rc01`. **[DOCUMENTED]** https://dl.google.com/dl/android/maven2/androidx/work/group-index.xml) with a `CoroutineWorker` + the OkHttp 4.12.0 you already get transitively. Constraints: `setRequiredNetworkType(UNMETERED)` by default with an explicit user override, `setRequiresStorageNotLow(true)`, `setRequiresBatteryNotLow(true)`. Per-attempt:
   ```
   GET /australia-shortbread-14.pmtiles
   Range: bytes=<current .part length>-
   If-Range: "<etag from manifest fetch>"
   ```
   `206` → append. `200` → the remote changed under you; truncate `.part` and restart. `416` → `.part` is longer than the resource; delete and restart. **[INFERRED]** from RFC 9110 `Range`/`If-Range` semantics; the servers' `accept-ranges: bytes` and `206` behaviour are **[DOCUMENTED]** above.
5. **Never run the recorder and the download at full tilt together.** Gate the worker on `!RecordingService.isRecording` (or at minimum drop the download to a bounded rate). Sustained network + sustained encode is the worst thermal case on the G04. **[INFERRED]**; magnitude **NOT VERIFIED — needs on-device measurement**.
6. **Integrity.** `MessageDigest.getInstance("SHA-256")` streamed over the `.part` file, compared against the manifest. Where the source provides its own digest, use it *in addition*: Protomaps publishes MD5 + BLAKE3 per build in `builds.json`; VersaTiles publishes `.md5` and `.sha256` companions; Geofabrik publishes `.md5` for `.osm.pbf` **but not** for the Shortbread MBTiles; Mapsforge publishes none; BBBike ships an MD5 inside the ZIP. **[DOCUMENTED]** (all probed).
7. **Format self-check** — cheap, catches truncation and wrong-file mistakes that a digest over the wrong bytes would not:
   - PMTiles: first 7 bytes == `PMTiles`; byte 7 == 3; then parse the 127-byte v3 header and assert `tile_type == 1 (mvt)`, `internal_compression ∈ {1,2}`, `tile_compression ∈ {1,2}` (MapLibre rejects anything else, §3.3), `maxzoom == 14`, and `tile_data_offset + tile_data_length <= file length`.
   - MBTiles: first 16 bytes == `SQLite format 3\0`; `PRAGMA quick_check`; `SELECT value FROM metadata WHERE name IN ('format','minzoom','maxzoom')`.
   **[INFERRED]** from the PMTiles v3 spec fields I decoded in §4.1 and the SQLite header I decoded in §3.5.
8. **Atomic install.** Write to `australia.pmtiles.part` **in the same directory** as the destination, `fd.sync()` before rename, then `Files.move(part, dest, StandardCopyOption.ATOMIC_MOVE)` (`java.nio.file` is available from API 26; we are on 34). Same-filesystem rename is atomic, so a power loss can never leave a half-file at the live path. Then `fsync` the directory if you want belt-and-braces (`FileChannel.open(dir, READ).force(true)`). **[INFERRED]** from POSIX rename semantics; **[DOCUMENTED]** that `ATOMIC_MOVE` exists on Android 26+.
9. **Corruption recovery at runtime.** MapLibre's PMTiles source surfaces failures as `Response::Error` — `"Error parsing PMTiles header: …"`, `"path not found: <path>"`, `"Compression method not supported"`, `"Error fetching PMTiles tile: …"`. Subscribe to `MapView.addOnDidFailLoadingMapListener` and to the map-event observers added in 11.x (`onGlyphsError`, `onSpriteError`, `onTileAction`, `onSourceChangedListener`) — if the archive fails validation at load, quarantine it (rename to `.bad`), show "map data needs repair", and re-run the installer. **[DOCUMENTED]** for the error strings and the observer names (changelog PR #2694; `pmtiles_file_source.cpp`).
10. **Storage accounting UI.** Show the user: archive version date, bytes on disk, free space remaining, and a "Delete offline map" button. Roadguard's #3 priority is storage safety; a 933 MiB file the user cannot find or delete is a support burden and a reason to uninstall. Query with `File.length()` and `StorageManager.getAllocatableBytes`.
11. **Update mechanism.** Poll the manifest at most weekly, on unmetered + charging, and only when not recording. Because the whole archive is one immutable blob, an update is a fresh download — there is no delta mechanism for PMTiles. That means an update transiently needs `2 × 933 MiB ≈ 1.83 GiB`. Gate the update on `getAllocatableBytes() > 2.2 GiB` and offer "replace in place (frees the old file first, no rollback)" as the low-storage path. **[INFERRED]**.

**Do not** use `android.app.DownloadManager` for this. It is opaque about resume behaviour, its notification is not yours to style, and you cannot make it defer to the recorder. WorkManager + OkHttp gives you the constraint model you actually need. **[INFERRED]**.

### 5.3 Option 3 — install-time asset pack / bundled in APK: rejected

Bundling in `assets/` is a dead end for PMTiles (`pmtiles://asset://` unsupported, §3.3), and copying a 933 MiB asset out of the APK at first run costs a transient 933 MiB *plus* the APK's own copy — the worst storage profile of the three options. An `install-time` asset pack additionally *"requires at least two times the size of all the asset packs"* free. **[DOCUMENTED]** (asset-delivery guide). Rejected.

---

## 6. Whole-of-Australia vs regional partitioning — the arithmetic

**Baseline: 933.4 MiB (978,786,994 B) for Shortbread z0–14 over Australia + external territories.** **[DOCUMENTED]** (artifact read, §3.5).

### 6.1 Is 933 MiB sane on the Moto G04?

Moto G04 ships in 64 GB and 128 GB variants with UFS 2.2, 4 GB or 8 GB RAM. **[UNVERIFIED — aggregator sources; Motorola's own support page (`en-us.support.motorola.com/app/answers/detail/a_id/178144`) is the authoritative one to confirm the SKU sold in Australia].** Even on the 64 GB SKU with Android 14 + Motorola preload consuming ~15–20 GB, 933 MiB is ~1.5–2% of the device. **[INFERRED]**.

The number that actually threatens us is not the map — it is the dashcam footage. A 3-minute 1080p30 H.264 segment at, say, 10 Mbps is ~225 MB; a loop buffer of even 20 segments is 4.5 GB. **[INFERRED]** arithmetic. So the map is roughly *four segments* of footage. Verdict: **ship the whole country.**

### 6.2 Zoom capping — the real size lever

Protomaps documents the rule: *"Each additional zoom level roughly doubles the size of the file."* **[DOCUMENTED]** https://docs.protomaps.com/basemaps/downloads

| Max zoom | Estimated size | Method |
|---|---|---|
| 14 (baseline) | **933 MiB** | [DOCUMENTED] artifact read |
| 13 | ~467 MiB | [INFERRED] halve per level |
| 12 | ~233 MiB | [INFERRED] |
| 15 (Protomaps schema) | ~1.87 GiB | [INFERRED] |

Capping at z13 halves storage but pushes overzoom to 2× at driving zoom, which visibly coarsens road geometry and drops z14-only labels. **Recommendation: keep z14.** If you ever need a "lite" install for very low-storage devices, z13 is the knob, generated as a second artifact by `pmtiles extract --maxzoom=13` (*"Extracting a full sub-pyramid from 0 to maxzoom is always an efficient operation that makes minimal I/O or network requests to the source archive"*). **[DOCUMENTED]** https://docs.protomaps.com/pmtiles/cli

### 6.3 If you do partition — state-level arithmetic

There are no published per-state Australian vector tile archives (§4.1 note on Geofabrik). You would generate them with `pmtiles extract --region=<state>.geojson` from your own national archive. Geofabrik publishes per-state `.poly`/`.kml` clipping polygons (e.g. `https://download.geofabrik.de/australia-oceania/australia/new-south-wales.poly`) which convert to GeoJSON for `--region`. **[DOCUMENTED]** (directory listing shows `*.poly` and `*.kml` per state).

Size projection, using per-state `.osm.pbf` size as a proxy for data density:

| State | `.osm.pbf` | Share | Projected PMTiles z0–14 |
|---|---|---|---|
| New South Wales | 253 MB | 27.3% | ~255 MiB |
| Victoria | 229 MB | 24.7% | ~231 MiB |
| Queensland | 187 MB | 20.2% | ~189 MiB |
| Western Australia | 108 MB | 11.7% | ~109 MiB |
| South Australia | 63 MB | 6.8% | ~64 MiB |
| Tasmania | 51 MB | 5.5% | ~51 MiB |
| ACT | 17.9 MB | 1.9% | ~18 MiB |
| Northern Territory | 16.9 MB | 1.8% | ~17 MiB |
| **Sum** | **925.8 MB** | 100% | **~934 MiB** |

**[INFERRED]** — chain: per-state `.osm.pbf` sizes are [DOCUMENTED] from Geofabrik region pages; the national PMTiles size is [DOCUMENTED]; I assume tile bytes scale with OSM feature bytes. **This proxy is weak**: PMTiles size is driven by *unique* tile bodies, so a state with lots of empty desert (WA, NT, SA) dedupes far harder than the proxy suggests, while a state that is nearly all populated (ACT) dedupes barely at all. Expect WA/NT/SA to come out *smaller* than the table and ACT *larger*. **NOT VERIFIED — settle it by actually running `pmtiles extract --region=<state>.geojson` on each of the eight polygons and recording `ls -l`.** Note also that per-state sub-pyramids each carry a full z0–z8 overview, so the eight parts will sum to **more** than 934 MiB.

Arguments **against** partitioning, which is why it is not the recommendation:
- Every state boundary becomes a blank-map bug report from someone driving Albury→Wodonga or the Hume. You would need overlapping buffers (Geofabrik's own `.poly` files are *"not country boundaries but a buffer around countries"*), which reinflates the total. **[DOCUMENTED]** for the buffer note; **[INFERRED]** for the consequence.
- MapLibre can hold multiple PMTiles sources in one style, but a style with eight sources means eight sets of layer definitions and eight `pmtiles://` file handles. The maintainers' own advice when asked about multiple offline PMTiles was to use a device-side proxy or *"populate offline database with PMTiles extracts"* rather than list many archives in the style — i.e. this is not a well-trodden path. **[DOCUMENTED]** https://github.com/maplibre/maplibre-native/discussions/3764
- 933 MiB is already less than one twentieth of the loop-recording buffer (§6.1).

**Decision: one national archive. Revisit only if on-device measurement shows PMTiles directory-walk latency scales badly with archive size (see final section).**

---

## 7. Making the style genuinely offline

This is the step that breaks naive setups: the tiles are local, so people declare victory, and then the map renders roads with **no labels and no icons** on a plane because `glyphs` and `sprite` still point at `https://`.

### 7.1 Style choice and layer budget

Open Shortbread-compatible MapLibre styles, with layer counts I read out of the live style JSON on 2026-08-22:

| Style | `layers` count | style.json bytes | Fontstacks used | Licence |
|---|---|---|---|---|
| VersaTiles **Neutrino** | **207** | 101,306 | `noto_sans_regular`, `noto_sans_bold` | CC-0 (`metadata.license`) |
| VersaTiles **Colorful** | 324 | 168,597 | same two | CC-0 |
| VersaTiles **Graybeard** | 324 | 168,312 | same two | CC-0 |
| VersaTiles **Eclipse** | 324 | — | same two | CC-0 |
| VersaTiles **Shadow** | 324 | — | same two | CC-0 |
| `Shortbread-Demo-MapLibre` | *"A very basic MapLibre style"* | — | — | (see repo) |

**[DOCUMENTED]** — counts and `metadata.license` read from `https://tiles.versatiles.org/assets/styles/{name}/style.json`; style list from https://shortbread-tiles.org/styles/

Every one of these declares:

```json
"glyphs": "https://tiles.versatiles.org/assets/glyphs/{fontstack}/{range}.pbf",
"sprite": [{ "id": "basics", "url": "https://tiles.versatiles.org/assets/sprites/basics/sprites" }],
"sources": { "versatiles-shortbread": { "type": "vector",
   "tiles": ["https://tiles.versatiles.org/tiles/osm/{z}/{x}/{y}"],
   "minzoom": 0, "maxzoom": 14,
   "attribution": "© <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors" } }
```

**Recommendation for Roadguard: author your own style, seeded from Neutrino.** A dashcam map needs land, water, the road network with casing, road labels, place labels, and a follow-me puck. It does not need 200+ layers of POI icons, building fills, aerialways, piers, dams or land-use polygons — every one of which is per-frame GPU work on a Mali-G57 **MP1** (a *single* shader core). Target 40–60 layers. Also build a night variant: dark-on-dark at night is a genuine safety issue for a windscreen-mounted device. **[INFERRED]** — chain: MapLibre draws one or more draw calls per visible layer per tile per frame; fewer layers = fewer draw calls and less overdraw; the MP1 core count is the documented single-core Mali-G57 configuration. Magnitude **NOT VERIFIED — needs on-device measurement**.

Caveat if you keep a VersaTiles style as-is: VersaTiles documents **Shortbread Extensions** and a **Low-Zoom Landcover** layer of its own. **[DOCUMENTED]** https://docs.versatiles.org/ (spec sidebar). A style written against those extensions will reference source-layers absent from a plain Shortbread 1.0 archive (e.g. BBBike's or Geofabrik's). Missing source-layers render nothing — the map will not crash, but low-zoom landcover will be blank. **NOT VERIFIED — needs a visual check** against your chosen archive.

### 7.2 Exactly which asset files MapLibre will ask for

Read straight from `Resource::spriteImage` / `Resource::spriteJSON` / `Resource::glyphs` **[DOCUMENTED]** https://github.com/maplibre/maplibre-native/blob/main/src/mbgl/storage/resource.cpp:

```cpp
Resource Resource::spriteImage(const std::string& base, float pixelRatio) {
    util::URL url(base);
    return Resource{Resource::Kind::SpriteImage,
                    base.substr(0, url.path.first + url.path.second) + (pixelRatio > 1 ? "@2x" : "") + ".png" + ...};
}
Resource Resource::spriteJSON(const std::string& base, float pixelRatio) { /* ... + "@2x" ... + ".json" */ }
Resource Resource::glyphs(const std::string& urlTemplate, const FontStack& fontStack,
                          const std::pair<uint16_t, uint16_t>& glyphRange) {
    // {fontstack} -> util::percentEncode(fontStackToString(fontStack))
    // {range}     -> toString(first) + "-" + toString(second)
}
```

Three things follow that you must get right:

1. **`@2x` is chosen by `pixelRatio > 1`.** Both target devices have density > 1, so MapLibre requests `sprites@2x.png` **and** `sprites@2x.json`. If you bundle only the 1× pair, all icons vanish. Bundle **all four**.
2. **`{fontstack}` is percent-encoded.** A style asking for `Noto Sans Regular` produces `asset://glyphs/Noto%20Sans%20Regular/0-255.pbf`. Both `AssetManagerFileSource` and `LocalFileSource` percent-*decode* before opening (`mln::util::percentDecode(url.substr(8))` and `percentDecode(resource.url.substr(len(FILE_PROTOCOL)))` respectively **[DOCUMENTED]**, `asset_manager_file_source.cpp` / `local_file_source.cpp`), so the asset path must literally contain a space. It works, but it is fragile across build tooling. **Prefer fontstack names with no spaces.** VersaTiles already uses `noto_sans_regular` / `noto_sans_bold`; Protomaps uses `Noto Sans Regular`. That alone is a good reason to standardise on the VersaTiles fontstack naming.
3. **A missing glyph range does not break the map.** `glyph_manager.cpp` reports `observer->onGlyphsError(fontStack, range, …)` and returns; the range is simply absent. **[DOCUMENTED]** https://github.com/maplibre/maplibre-native/blob/main/src/mbgl/text/glyph_manager.cpp . So you can ship a *subset* of ranges safely — labels outside your subset just do not draw. Wire `onGlyphsError` into logcat during development so you learn which ranges Australian place names actually need.

### 7.3 The offline asset bundle — exact sizes

Sizes read from `tiles.versatiles.org` on 2026-08-22 **[DOCUMENTED]**:

| Asset | Bytes |
|---|---|
| `sprites/basics/sprites.json` | 9,683 |
| `sprites/basics/sprites.png` | 79,640 |
| `sprites/basics/sprites@2x.json` | 9,725 |
| `sprites/basics/sprites@2x.png` | 198,396 |
| **Sprite subtotal** | **297,444 B (0.28 MiB)** |
| `noto_sans_regular` × 12 ranges (0-255, 256-511, 512-767, 768-1023, 1024-1279, 7680-7935, 7936-8191, 8192-8447, 8448-8703, 8704-8959, 9472-9727, 11264-11519) | 1,149,573 |
| `noto_sans_bold` × same 12 ranges | 1,217,582 |
| **Glyph subtotal** | **2,367,155 B (2.26 MiB)** |
| Style JSON (Neutrino-derived, expect smaller after pruning) | ≤ 101,306 |
| **Total APK asset cost** | **≈ 2.6 MiB** |

Individual ranges run 15,645–157,072 B. For reference, the *whole* Noto Sans family across all Unicode is 48,012,413 B (`noto_sans.tar.gz`) and all VersaTiles families together are 112,985,476 B (`fonts.tar.gz`) — **[DOCUMENTED]** https://github.com/versatiles-org/versatiles-fonts/releases/latest/download/ — so do **not** bundle the tarball; bundle the 24 files you need.

Licences: VersaTiles Fonts repo is MIT, the typefaces themselves are **SIL OFL** (Noto Sans, Fira Sans, Lato, …). **[DOCUMENTED]** https://raw.githubusercontent.com/versatiles-org/versatiles-fonts/main/README.md . For Protomaps' assets instead: fonts under **SIL OFL** (`fonts/OFL.txt`), sprites *"derived from MIT-licensed tangrams/icons"*. **[DOCUMENTED]** https://raw.githubusercontent.com/protomaps/basemaps-assets/main/README.md . Either way: ship the OFL text and the MIT notice in your in-app licences screen.

### 7.4 The offline style, concretely

APK layout:

```
app/src/main/assets/map/
  style-day.json
  style-night.json
  sprites/basics/sprites.json      sprites.png
  sprites/basics/sprites@2x.json   sprites@2x.png
  glyphs/noto_sans_regular/0-255.pbf … 11264-11519.pbf
  glyphs/noto_sans_bold/0-255.pbf   … 11264-11519.pbf
```

`style-day.json` (abridged), with a `__PMTILES_URI__` placeholder because the absolute path is only known at runtime:

```json
{
  "version": 8,
  "name": "Roadguard Day",
  "glyphs": "asset://map/glyphs/{fontstack}/{range}.pbf",
  "sprite": [{ "id": "basics", "url": "asset://map/sprites/basics/sprites" }],
  "sources": {
    "versatiles-shortbread": {
      "type": "vector",
      "url": "__PMTILES_URI__",
      "attribution": "© <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors"
    }
  },
  "layers": [ /* 40-60 layers, source-layer names per Shortbread 1.0 */ ]
}
```

Runtime wiring:

```kotlin
// 1. absolute path -> pmtiles://file:// URI
val archive = File(context.getExternalFilesDir(null), "maps/australia.pmtiles")
val pmtilesUri = "pmtiles://file://${archive.absolutePath}"   // note: three slashes total

// 2. patch the bundled style and hand MapLibre a JSON string (relative URLs are
//    never used, so fromJson is safe here)
val styleJson = context.assets.open("map/style-day.json")
    .bufferedReader().use { it.readText() }
    .replace("__PMTILES_URI__", pmtilesUri)

map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
    map.setLatLngBoundsForCameraTarget(AUSTRALIA_BOUNDS)  // see §3.4 bounds trap
    map.setMinZoomPreference(3.0)
    map.setMaxZoomPreference(18.0)                        // overzoom above 14
    map.setPrefetchZoomDelta(0)                           // see §9
}
```

`Style.Builder.fromUri(String)` and `Style.Builder.fromJson(String)` both exist. **[DOCUMENTED]** https://github.com/maplibre/maplibre-native/blob/main/platform/android/MapLibreAndroid/src/main/java/org/maplibre/android/maps/Style.java

Options object (see §9 for the reasoning):

```kotlin
val options = MapLibreMapOptions.createFromAttributes(context)
    .textureMode(false)            // SurfaceView, not TextureView
    .pixelRatio(1.0f)              // render at 1x; see thermal ladder
    .crossSourceCollisions(false)  // one source only -> no cross-source work
    .localIdeographFontFamily("sans-serif")  // CJK from system, not from glyphs
    .logoEnabled(true)
    .attributionEnabled(true)
```

`textureMode`, `pixelRatio`, `crossSourceCollisions`, `localIdeographFontFamily(String…)`, `renderSurfaceOnTop`, `foregroundLoadColor` are all real fields on `MapLibreMapOptions`. **[DOCUMENTED]** https://github.com/maplibre/maplibre-native/blob/main/platform/android/MapLibreAndroid/src/main/java/org/maplibre/android/maps/MapLibreMapOptions.java

**Verification step you must not skip:** run a build with `HttpRequestUtil.setOkHttpClient { throw IOException("offline") }` (§2.4) and airplane mode on, then confirm the map renders labels and icons. If anything is missing, some `https://` survived in your style.

---

## 8. Attribution and licence obligations to display in-app

### 8.1 What the OSMF actually requires

From the OpenStreetMap Attribution Guideline, adopted by the OSMF board 2021-06-25 **[DOCUMENTED]** https://osmfoundation.org/wiki/Licence/Attribution_Guidelines — quoting the operative sentences:

- *"Attribution must be to 'OpenStreetMap'."*
- *"Attribution must also make it clear that the data is available under the Open Database License. This may be done by making the text 'OpenStreetMap' a link to openstreetmap.org/copyright…"*
- *"The historical forms of attribution '© OpenStreetMap contributors' or '© OpenStreetMap' are acceptable."*
- *"Attribution must be presented to anyone who uses, views, accesses, interacts with, or is otherwise exposed to the map or produced work. The attribution format should not require individuals to interact with the map or produced work to see the attribution."*
- For interactive maps: *"the credit should typically appear in a corner of the map. While the lower right corner is traditional, any corner of the map is acceptable. Alternatively, the attribution may be placed adjacent to the map or on a splash screen or pop-up shown when a user starts the app."*
- Fading/collapsing is permitted *"immediately with a dismiss interaction… automatically on map interaction such as panning, clicking, or zooming… automatically after five seconds."*
- *"If the attribution has been collapsed, the user must still be able to find the licence information if they look for it, for example from an '(i)' button in the corner of the map or an 'About' option in a menu."*
- *"attribution is only necessary when a Produced Work is used Publicly (as defined by the ODbL)."*
- *"If you have rendered OSM data to your own design, you may wish to use 'Map data from OpenStreetMap.'"*

### 8.2 What Roadguard must ship

1. **On the map surface:** `© OpenStreetMap contributors` in a corner of the map pane, tappable, with adequate contrast (the guideline explicitly invokes WCAG). Because the map is only ~50% of the screen, put it in the map pane's own corner, not the screen's.
2. **The tappable target** opens an in-app "Map data & licences" screen (never an external browser — offline-first means the link may not resolve). That screen must state:
   - Map data © OpenStreetMap contributors, available under the **Open Database License (ODbL) 1.0** — include the ODbL text or a verbatim copy, plus the `openstreetmap.org/copyright` URL as text.
   - The tile schema: **Shortbread 1.0 — CC-0**, *"does not require additional attribution"* **[DOCUMENTED]** (VersaTiles tilesets page) — credit it anyway, it is good practice.
   - The tileset producer, if you did not build it yourself: e.g. *"Vector tiles produced by Geofabrik GmbH"* (their MBTiles `metadata.author` is literally `OpenStreetMap contributors, Geofabrik GmbH`) or *"Extract created by BBBike, https://extract.bbbike.org, tiles by tilemaker"* (their `README.txt` asks for exactly this). If you build with planetiler yourself, credit planetiler.
   - Style: your own, plus *"derived from VersaTiles Neutrino (CC0-1.0)"* if you seed from it.
   - Fonts: **SIL Open Font License 1.1** + the Noto Sans copyright line.
   - Sprites: **MIT** (tangrams/icons lineage) or the VersaTiles equivalent.
   - Renderer: **MapLibre Native**, BSD-2-Clause; **OkHttp** Apache-2.0; **Timber** Apache-2.0; and everything else the POM in §2.1 drags in.
3. **The ODbL "Produced Work" position.** The rendered tiles are a Produced Work; you are not distributing a Derivative Database, so §4.6 (share-alike on databases) does not bite — you owe attribution and a notice of the licence, not your source. **[INFERRED]** from the ODbL structure and the OSMF guideline's Produced-Work framing; **not legal advice — have this reviewed if Roadguard is commercial.**
4. **MapLibre's built-in help.** `UiSettings` exposes `setAttributionEnabled(boolean)`, `setAttributionGravity(int)`, `setAttributionMargins(l,t,r,b)`, `setAttributionTintColor(int)`, `setAttributionDialogManager(AttributionDialogManager)`, `setLogoEnabled(boolean)`, `setLogoGravity(int)`, `setLogoMargins(...)`. **[DOCUMENTED]** https://github.com/maplibre/maplibre-native/blob/main/platform/android/MapLibreAndroid/src/main/java/org/maplibre/android/maps/UiSettings.java . The default attribution "ⓘ" reads the `attribution` field off each style source, so setting it in the style JSON (§7.4) makes the built-in dialog correct for free. Supply a custom `AttributionDialogManager` so the dialog is your offline licences screen rather than an intent to a browser.

---

## 9. Thermal and CPU reality — protecting the recorder

Roadguard's priority order is recording > thermal > storage. The map is fourth. So the map must be the thing that degrades.

### 9.1 The signals (exact APIs and levels)

Read from the `PowerManager` reference **[DOCUMENTED]** https://developer.android.com/reference/android/os/PowerManager:

| API | Added | Notes |
|---|---|---|
| `int getCurrentThermalStatus()` | **API 29** | returns `THERMAL_STATUS_*` |
| `void addThermalStatusListener(Executor, PowerManager.OnThermalStatusChangedListener)` | **API 29** | `removeThermalStatusListener` likewise |
| `float getThermalHeadroom(int forecastSeconds)` | **API 30** | `forecastSeconds` 0–60; `1.0` == the SEVERE threshold; **returns `NaN` if unsupported**, and *"there is no benefit to calling this function more frequently than about once per second, and attempts to call significantly more frequently may result in the function returning NaN"* |
| `Map<Integer,Float> getThermalHeadroomThresholds()` | **API 35** | per-status headroom thresholds; a key is present only if the OEM defined it |
| `addThermalHeadroomListener` / `removeThermalHeadroomListener` | **API 36** | push instead of poll |

Constants, all API 29, with the documented meanings: `THERMAL_STATUS_NONE` (*"Not under throttling"*), `_LIGHT` (*"Light throttling where UX is not impacted"*), `_MODERATE` (*"Moderate throttling where UX is not largely impacted"*), `_SEVERE` (*"Severe throttling where UX is largely impacted"*), `_CRITICAL` (*"Platform has done everything to reduce power"*), `_EMERGENCY` (*"Key components in platform are shutting down"*), `_SHUTDOWN` (*"Need shutdown immediately"*). **[DOCUMENTED]**.

API gating for Roadguard: `minSdk 34` means status + `getThermalHeadroom` are unconditionally available; `getThermalHeadroomThresholds()` needs `Build.VERSION.SDK_INT >= 35`; `addThermalHeadroomListener` needs `>= 36`. Always handle `Float.isNaN(headroom)` — the Unisoc T606 may not implement a skin sensor. **NOT VERIFIED — needs on-device measurement on the Moto G04: log `getThermalHeadroom(0)` once per second for 20 minutes of recording and see whether it is ever non-NaN.**

### 9.2 The levers (exact calls, cheapest first)

All verified to exist by source read (`MapView.java` lines 445/458/574; `MapLibreMap.java` lines 338/359/1709; `MapLibreMapOptions.java` fields 81–95). **[DOCUMENTED]**

| Lever | Call | Effect |
|---|---|---|
| Frame rate cap | `mapView.setMaximumFps(int)` (throws `IllegalStateException` if called before the renderer exists) | linear cut in GPU + render-thread CPU work |
| Render resolution | `MapLibreMapOptions.pixelRatio(float)` at construction | quadratic cut in fragment work; `0.75f` ≈ 44% fewer pixels than `1.0f` |
| Tile prefetch | `map.setPrefetchZoomDelta(0)` (default is non-zero; `0` disables) | fewer parallel tile parses on the worker threads |
| Tile cache | `map.setTileCacheEnabled(false)` | changelog 11.2.0: *"This tile cache is used to cache tiles on different zoom levels, disabling it will reduce memory usage"* |
| Cross-source collisions | `MapLibreMapOptions.crossSourceCollisions(false)` | skips cross-source label collision; free win with one source |
| Layer count | your style | fewest draw calls per frame — the biggest single lever, and it is a design decision, not a runtime one |
| Surface type | `MapLibreMapOptions.textureMode(false)` | SurfaceView path; TextureView adds a compositing copy per frame |
| Memory pressure | `mapView.onLowMemory()` from `Activity.onTrimMemory` | drops caches |
| Full stop | detach/destroy the `MapView`, show a static last-frame snapshot or a plain compass/speed panel | zero map cost |

`MapView.queueEvent(Runnable)` lets you post work onto the render thread if you need to touch GL/Vulkan state. **[DOCUMENTED]** (`MapView.java:445`).

### 9.3 The degradation ladder

```kotlin
when (pm.currentThermalStatus) {
  THERMAL_STATUS_NONE, THERMAL_STATUS_LIGHT -> mapView.setMaximumFps(30)
  THERMAL_STATUS_MODERATE                   -> mapView.setMaximumFps(10)   // + stop camera-follow animation
  THERMAL_STATUS_SEVERE                     -> mapView.setMaximumFps(2)    // + hide POI/label layers via setVisibility
  THERMAL_STATUS_CRITICAL,
  THERMAL_STATUS_EMERGENCY,
  THERMAL_STATUS_SHUTDOWN                   -> detachMapAndShowStaticPanel()
}
```

Additionally: when `getThermalHeadroom(30) > 0.85f` (i.e. forecast to hit SEVERE within 30 s), pre-emptively drop one rung. Re-create the `MapView` with a lower `pixelRatio` only on a cool-down transition, since `pixelRatio` is construction-time. Debounce every transition by ≥ 30 s so the map does not flicker between rungs. **[INFERRED]** — the APIs and semantics are [DOCUMENTED]; the specific FPS numbers and headroom threshold are engineering guesses and are **NOT VERIFIED — needs on-device measurement**.

Two more non-negotiables:

- **Never let the map's frame pacing gate the recorder.** Keep MapLibre on its own `SurfaceView`, and keep encoding on its own thread(s). Do not composite camera + map into a single GL surface just to render the UI — that couples the two.
- **Never download while recording** (§5.2 step 5). Network radio + sustained encode is the worst thermal case.

### 9.4 Why the stack choice matters thermally

| Stack | Where the tile rasterisation happens | Contends with the encoder? |
|---|---|---|
| MapLibre Native (GL/Vulkan) | GPU, per frame, from cached vector geometry | Mostly no — GPU is otherwise idle in a dashcam. Vector parsing is CPU but bounded and cacheable |
| Mapsforge | **CPU**, `android.graphics.Canvas`, per tile | **Yes**, directly |
| VTM | GPU | Mostly no |
| osmdroid + raster | CPU decode of PNG/JPEG per tile | Somewhat (image decode) |

**[INFERRED]** — chain: MapLibre/VTM upload geometry to the GPU and shade per frame; Mapsforge rasterises with the CPU 2D pipeline; the H.264/HEVC encoder on the T606 is a fixed-function block fed by the CPU. Therefore CPU-side rasterisation is in direct contention with the feed path, GPU-side is not. **The absolute magnitudes are NOT VERIFIED — needs on-device measurement.**

---

## 10. Recommended architecture

### 10.1 Primary

```
Renderer      org.maplibre.gl:android-sdk-opengl:13.5.1
              (ABI splits; arm64-v8a libmaplibre.so = 10,843,424 B)
              MapView inside AndroidView; MapLibreMapOptions:
                textureMode=false, pixelRatio=1.0, crossSourceCollisions=false
              HttpRequestUtil.setOkHttpClient(failing Call.Factory) after install
              OfflineManager.setMaximumAmbientCacheSize(0)

Tiles         ONE PMTiles v3 archive, Shortbread 1.0, z0-14, MVT + gzip
              ~933 MiB, at getExternalFilesDir(null)/maps/australia.pmtiles
              referenced as pmtiles://file:///…/maps/australia.pmtiles

Build         planetiler (Maven Central 0.9.1) + samples/shortbread.yml
                --download --area=australia --output=…pmtiles --maxzoom=14
              pmtiles verify (go-pmtiles v1.31.2)
              sha256sum -> manifest.json

Delivery      Play on-demand (or fast-follow) asset pack
                com.google.android.play:asset-delivery:2.3.0   [1.5 GB/pack limit]
              + fallback: own bucket, Range/If-Range resume via
                WorkManager 2.11.2 + OkHttp 4.12.0, SHA-256 + PMTiles
                header self-check, ATOMIC_MOVE install

Style         Roadguard day/night styles, 40-60 layers, seeded from
              VersaTiles Neutrino (CC-0)
              glyphs: asset://map/glyphs/{fontstack}/{range}.pbf
              sprite: asset://map/sprites/basics/sprites   (ship 1x AND @2x)
              fontstacks noto_sans_regular / noto_sans_bold (no spaces!)
              ~2.6 MiB of APK assets

Camera        setLatLngBoundsForCameraTarget(AUSTRALIA_BOUNDS)   // mbtiles/pmtiles
              setMinZoomPreference(3.0); setMaxZoomPreference(18.0)
              setPrefetchZoomDelta(0)

Thermal       PowerManager.addThermalStatusListener + getThermalHeadroom(30)
              -> setMaximumFps ladder 30/10/2 -> detach MapView

Attribution   "© OpenStreetMap contributors" in the map pane corner, tappable,
              custom AttributionDialogManager -> offline licences screen
```

### 10.2 Fallback ladder

1. **PMTiles fails on-device for a reason we cannot fix** (e.g. `pmtiles://file://` misbehaves on the G04's filesystem, or directory-walk latency is unacceptable): switch the same Shortbread data to `mbtiles:///abs/path.mbtiles`. Cost: **2.70 GiB instead of 933 MiB** (§3.5), plus the two traps in §3.4 (populate `minzoom`/`maxzoom`; set your own camera bounds). Ready-made source: Geofabrik `australia-shortbread-1.0.mbtiles`, whose metadata I verified already contains `minzoom=0`/`maxzoom=14` and a `tile_index`.
2. **OpenGL rendering is the thermal offender on the G04**: try `org.maplibre.gl:android-sdk-vulkan:13.5.1` on the Edge 60 Fusion and, if the G04's driver behaves, on the G04 too — accepting the `uses-feature … required="true"` gate (§2.3), or ship `android-sdk-vulkan-opengl:13.5.1` and pick at runtime at a cost of +10,843,424 B per install.
3. **GPU vector rendering is fundamentally too hot on Mali-G57 MP1**: pre-render a small set of raster tiles for the driving corridor and draw them with a trivial custom view — or, last resort, Mapsforge/VTM with `com.github.mapsforge.mapsforge:mapsforge-map-android:0.30.0@jar` (JitPack) reading `australia.map` (1.56 GiB, stale since 2026-02-08, no checksums, "not suitable for mass downloads" — so you would mirror it yourself).
4. **Map is not viable at all on the baseline device**: ship the map as an *optional* download, default off on devices reporting `< 4 GB` RAM (`ActivityManager.MemoryInfo.totalMem` / `isLowRamDevice()`), and show a compass + speed + GPS-track panel in the map half instead. This keeps the #1 priority (recording) intact on the worst hardware, which is the correct trade.

---

## Open questions / must-measure-on-device

Nothing below can be settled from documentation. Each item names the test.

**Renderer and thermals (Moto G04 first, Edge 60 Fusion second)**

1. **Does the Moto G04 declare Vulkan, and at what level?** Test: `adb shell pm list features | grep vulkan`; read `android.hardware.vulkan.version`, `android.hardware.vulkan.level`, `android.software.vulkan.deqp.level`. Settles whether the Vulkan flavour's `required="true"` gate (§2.3) would exclude the baseline device.
2. **OpenGL vs Vulkan vs multiBackend: which is cooler while recording?** Test: 30-minute 1080p30 recording with the map visible at driving zoom, for each of `android-sdk-opengl:13.5.1`, `android-sdk-vulkan:13.5.1`. Log once per second: `PowerManager.getCurrentThermalStatus()`, `getThermalHeadroom(0)`, `getThermalHeadroom(30)`, battery temperature, dropped encoder frames, and MapLibre's own `onDidFinishRenderingFrame` cadence. Decide on dropped-frame count, not on °C.
3. **Is `getThermalHeadroom()` implemented on the T606 at all?** Test: log it once per second for 20 minutes; if it is always `NaN`, the whole headroom-forecast branch of §9.3 must be replaced by status-only transitions plus an internal frame-drop heuristic.
4. **What is the real per-frame cost of layer count?** Test: render the same viewport with a 40-layer, a 207-layer (Neutrino) and a 324-layer (Colorful) style; record frame time and GPU utilisation (`dumpsys gpu --gpuwork` is CDD-mandated on Android 14 — [C-0-13]). Sets the layer budget for the Roadguard style.
5. **Does `pixelRatio(0.75f)` look acceptable on a 720×1612 IPS panel?** Test: side-by-side photographs of the map pane at `1.0f` and `0.75f` at driving zoom, in daylight and at night. If acceptable, make it the default on low-RAM devices rather than a thermal-only fallback.
6. **`textureMode(false)` vs `true` under a 50/50 split layout:** does SurfaceView z-ordering fight the camera preview SurfaceView? Test both on the G04 in portrait and landscape, and check for black flashes on rotation.

**PMTiles / storage**

7. **Does `pmtiles://file://` work from `getExternalFilesDir()` on the G04?** Test: place a small `.pmtiles` there and load it; watch logcat for `"path not found: …"`. Then repeat from `getFilesDir()`. Also test with the app's storage on an SD card if the SKU supports one (the T606 SKU is commonly sold with a microSD slot — **UNVERIFIED**), where the FUSE/emulated-storage layer may make byte-range reads much slower.
8. **What is the cold-start latency of the first tile from a 933 MiB PMTiles archive?** Test: instrument `onDidFinishRenderingMap` / `onTileAction` from process start. The concern is the PMTiles directory walk (`getTileAddress` recurses through leaf directories, one range read per level) on UFS 2.2. If it is > ~1.5 s, consider a clustered archive (BBBike's Australia archive has `clustered = 0`; a planetiler-produced one is clustered) or a warm-up read of the root directory at app start.
9. **Does `clustered = 1` measurably improve tile-fetch latency on-device?** Test: build two archives from the same data, one clustered one not, and compare median/p95 tile-fetch time while panning along a highway.
10. **Actual PMTiles size from your own planetiler build.** Test: run the §4.4 command and `ls -l`. Everything in this document's size arithmetic keys off 933 MiB; if planetiler's Shortbread output is materially different from tilemaker's, re-derive §6.
11. **Per-state `pmtiles extract` sizes.** Test: `pmtiles extract australia.pmtiles nsw.pmtiles --region=nsw.geojson` for all eight polygons; record sizes and their sum. Settles §6.3, whose proxy-based numbers are explicitly weak.
12. **Play asset-pack path usability.** Test: put a `.pmtiles` in an `on-demand` asset pack, call `AssetPackLocation.assetsPath()`, and try to open the resulting path with `pmtiles://file://`. If the pack is served through `AssetManager` rather than a real path, Option 1 in §5.1 collapses and Option 2 becomes primary.
13. **Transient storage during update.** Test: with 2.5 GB free, attempt a full re-download + atomic swap; confirm the `getAllocatableBytes` gate fires rather than filling the disk mid-recording.

**Style completeness**

14. **Does the chosen style reference source-layers absent from the chosen archive?** Test: enable `map.setDebugActive(true)`, load the style against the real archive, and diff the style's `source-layer` values against the archive's `metadata`/TileJSON `vector_layers` list. Catches the VersaTiles-Shortbread-extensions mismatch flagged in §7.1.
15. **Which glyph ranges do Australian labels actually need?** Test: pan the whole country at label zooms with `onGlyphsError` logged; the errors name the missing ranges. Trim or extend the 12-range set in §7.3 accordingly.
16. **Airplane-mode proof.** Test: install the failing `Call.Factory` (§2.4), enable airplane mode, cold start, and confirm labels, icons and tiles all render. Any missing element is a surviving `https://` in the style.

**Legal / operational**

17. **Geofabrik's position on automated per-install downloads** (if you ever consider hotlinking them rather than self-hosting). Action: email `info@geofabrik.de`. Their site publishes no bulk-download policy either way, and `robots.txt` only speaks to crawlers.
18. **ODbL Produced-Work review.** Action: have §8 reviewed by someone qualified if Roadguard is distributed commercially. The attribution guideline is a safe harbour, not the licence.
