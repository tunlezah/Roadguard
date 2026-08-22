# Privacy

Roadguard records where you drive, how fast, and what your camera sees. That is among the most
sensitive data a phone can hold, and the app is built on one rule:

> **It stays on your device.**

No accounts. No cloud. No analytics. No crash reporting. No telemetry. Not off by default —
absent.

---

## 1. Everything that leaves the device

There are exactly two outbound requests in the entire application, and this list is complete
because only two files in the codebase touch the network at all
(`map/MapDownloader.kt` and `weather/OpenMeteoWeatherSource.kt`).

### 1.1 The offline map download — once

| | |
| --- | --- |
| When | First run, or when the user re-installs the map |
| To | The map archive URL in `assets/map_packages.json` (a GitHub release asset) |
| Sent | An HTTP GET, a `Range` header when resuming, and the User-Agent `Roadguard/1.0 (offline dashcam; +https://github.com/tunlezah/Roadguard)` |
| Not sent | Nothing else. No identifier, no location, no device details beyond what any HTTP client reveals |
| After | Never again. The map renders from the local file; no tile server, glyph server or sprite server is ever contacted |

### 1.2 Weather — only if you turn it on

Weather is **off by default**. When enabled:

| | |
| --- | --- |
| When | At most every 15 minutes while recording, stretching to every 120 minutes when the device is hot |
| To | `https://api.open-meteo.com/v1/forecast` |
| Sent | **Your position rounded to two decimal places** (about 1.1 km) and a list of field names |
| Not sent | No identifier. No session. No track. No history. No timestamp beyond the request itself. No locale |

The rounding is the point. Two decimal places cannot identify a street, let alone a house, and
it is far finer than any weather model's own grid — so nothing is lost meteorologically and a
great deal is protected. See [`research/weather-australia.md`](research/weather-australia.md)
for why Open-Meteo (no key, no account, no payment) and why the Bureau of Meteorology could not
be used.

### 1.3 That is the whole list

**Never sent, anywhere, under any setting:** video, audio, GPS coordinates, GPX tracks, speed,
event records, diagnostics, crash data, device identifiers, usage statistics, settings, or
anything derived from them.

## 2. Enforced, not just promised

A privacy claim that rests on nobody adding a dependency later is not a claim. So:

* **CI fails the build** if any dependency matching `com.google.firebase`,
  `play-services-analytics`, `com.crashlytics`, `io.sentry`, `com.amplitude`, `com.mixpanel`,
  `com.segment`, `com.appsflyer` or `com.facebook.android` appears on the release runtime
  classpath. See `.github/workflows/android.yml`.
* **There is no upload code to disable.** No account system, no sync service, no backup client,
  no queue of pending uploads. The absence is structural.
* `android:allowBackup="false"` and `android:fullBackupContent="false"` — footage and settings
  are not swept into a cloud backup by Android itself.
* `ACCESS_WIFI_STATE`, contributed by MapLibre's manifest for its own connectivity detection, is
  **removed from the merged manifest** with `tools:node="remove"`. Roadguard's map reads only
  local files, so the permission buys nothing and is not shipped unused.

## 3. Permissions, and why each one

| Permission | Why | When asked |
| --- | --- | --- |
| `CAMERA` | It is a dashcam | First run |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Speed and position overlay, map following, event context | First run; the app works without it |
| `RECORD_AUDIO` | Microphone recording | **Only when you turn the microphone on.** It is off by default and the permission is never requested otherwise |
| `POST_NOTIFICATIONS` | The recording notification | First run |
| `FOREGROUND_SERVICE` + `_CAMERA` / `_LOCATION` / `_MICROPHONE` | Android 14 requires a declared type per capability | Install-time |
| `WAKE_LOCK` | A camera FGS keeps the process important but not the CPU awake; video-only recording needs a partial wake lock | Install-time |
| `VIBRATE` | Event confirmation you can feel without looking | Install-time |
| `INTERNET`, `ACCESS_NETWORK_STATE` | The two requests in §1 | Install-time |

One further permission appears in the built APK that is not in the source manifest:
`io.github.tunlezah.roadguard.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. AndroidX Core adds it
automatically so that runtime-registered broadcast receivers cannot be reached by other apps. It
is a signature-level permission scoped to Roadguard's own package and grants nothing to anyone.

### Deliberately not requested

| Permission | Why not |
| --- | --- |
| `ACCESS_BACKGROUND_LOCATION` | A location-typed foreground service covers while-in-use access. Background location is a large privacy ask for no benefit |
| `WRITE_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE` | Recordings live in app-specific external storage |
| `READ_MEDIA_IMAGES` / `_VIDEO` / `_AUDIO` | Roadguard never reads your media |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | The app explains how to exempt itself in Android's own settings rather than prompting for it |
| `HIGH_SAMPLING_RATE_SENSORS` | The impact detector samples at 100 Hz, under the 200 Hz threshold that would require it |
| `READ_PHONE_STATE`, `GET_ACCOUNTS`, `AD_ID`, anything identifying | No use |

The microphone row deserves emphasis, because it is a specification requirement (§29) and
because in-car audio is a legal minefield: **`RECORD_AUDIO` is declared so it can be requested,
and is requested only if you enable microphone recording.** Leave it off and Roadguard never
asks.

## 4. What is stored, where, and how to get rid of it

Everything lives under `Android/data/io.github.tunlezah.roadguard/files/`.

| Data | Location | Controlled by |
| --- | --- | --- |
| Video segments | `recordings/*.mp4` | the loop budget; protected files are kept until you delete them |
| Protection marks | `recordings/*.protected.json` | deleted with the segment |
| GPS coordinates burned into video | inside the `.mp4` pixels | the "coordinates" overlay toggle — **off by default** |
| GPS in video metadata | `.mp4` metadata | the GPS storage setting |
| GPX tracks | `tracks/*.gpx` | the GPS storage setting — track writing is **off** in the default mode |
| Segment and event index | Room database | deleted with the app |
| Settings | DataStore | deleted with the app |
| Diagnostics exports | `diagnostics/` | only created when you export one |
| Offline map | `maps/` | removable from the Storage screen |

The **GPS storage setting** is a single control with six positions, from `None` ("do not store")
through overlay-only, metadata-only, track-only, overlay+metadata (the default) to all three.
Coordinates burned into the video are **off** by default; speed is on, because speed without a
position is not identifying.

Uninstalling Roadguard deletes all of it. That is the flip side of using app-specific storage,
and it means "how do I remove everything" has a one-step answer — but also that you must export
anything you want to keep first. The app says so.

## 5. Sharing is always your action

* Sharing a clip or a diagnostics report goes through a `FileProvider` with
  `android:exported="false"` and `grantUriPermissions="true"`, scoped by `xml/file_paths` to
  Roadguard's own directories. Nothing outside them is reachable.
* Nothing is ever shared automatically.
* A shared video contains whatever overlays you enabled — including coordinates, if you turned
  them on. Check before you send footage to anyone.

## 6. Diagnostics reports

The Diagnostics screen can export a text report for troubleshooting. It contains device model,
Android version, camera and encoder capabilities, thermal readings, storage figures, the active
recording profile and recent recorder events. Every value carries a provenance tag
(`[measured]`, `[inferred]`, `[simulated]`, `[not reported]`) so nothing in it can be
misread.

It is written to a local file. **Roadguard never sends it anywhere.** If you want to attach it
to a bug report, that is your decision and your share action.

## 7. Legal notes for Australian users

Not legal advice — but worth knowing, because the app cannot know your situation:

* **Audio is the sensitive part.** Recording a conversation you are party to is treated
  differently across Australian states and territories, and recording passengers without telling
  them may not be lawful where you are. This is why the microphone is off by default and why
  Roadguard never asks for the permission until you enable it.
* Video recording from a vehicle in public is generally accepted in Australia, but publishing
  footage of identifiable people or number plates raises separate questions.
* If you use footage in an insurance or police matter, keep the original file. Re-encoding or
  trimming it weakens it as evidence.

## 8. Verifying these claims yourself

You do not have to take any of this on trust:

```bash
# 1. Every network call in the app. Two files, and you can read both.
grep -rln "okhttp3\|HttpURLConnection\|java.net.Socket" app/src/main/java/

# 2. Every URL the app can reach.
grep -rn "https\?://" app/src/main/java/ --include='*.kt'

# 3. The full permission list, from the built APK rather than the source.
aapt2 dump badging app/build/outputs/apk/release/*.apk | grep uses-permission

# 4. The release dependency tree, to check for telemetry SDKs.
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

The CI job runs the equivalent of (4) on every push and fails if the answer changes.
