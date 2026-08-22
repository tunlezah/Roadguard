# Building Roadguard

## 1. Requirements

| | Version | Note |
| --- | --- | --- |
| JDK | **21** | Gradle runs on 21; the app compiles to JVM bytecode 17 |
| Android SDK platform | **37** (`compileSdk`) | `platforms;android-37.1` |
| Android build tools | **37.0.0** | apksigner and aapt2 come from here |
| Gradle | **9.7.1** | supplied by the wrapper, SHA-256 pinned |
| Android Gradle Plugin | **9.3.1** | |
| Kotlin | **2.4.10** | **ships inside AGP 9** — see §3 |

Nothing else. No local Kotlin install, no NDK, no Python for a normal build (the `tools/`
scripts are for regenerating assets, not for building).

```bash
git clone https://github.com/tunlezah/Roadguard.git
cd Roadguard
./gradlew :app:assembleDebug
```

The wrapper pins its own distribution checksum:

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.7.1-bin.zip
distributionSha256Sum=acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a
```

so a tampered or truncated Gradle download fails loudly rather than silently building something
else.

## 2. Commands

```bash
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:assembleRelease        # release APK (minified, resource-shrunk)
./gradlew :app:testDebugUnitTest      # 338 JVM tests, including 67 Compose UI tests
./gradlew :app:lintDebug              # Android Lint, abortOnError = true
./gradlew :app:lintVitalRelease       # the release-blocking lint subset
```

Outputs land in `app/build/outputs/apk/{debug,release}/`.

### Serialise your builds

`tools/gradle-serial.sh` wraps `./gradlew` in an `mkdir`-based lock at
`/tmp/roadguard-build.lock`. Use it if anything in your environment might run two Gradle
invocations at once.

This is not superstition. Two concurrent builds in this project corrupted Kotlin's incremental
compilation cache (`Storage corrupted … source-to-classes.tab_i`), and the symptom was not a
cache error — it was **dozens of bogus "unresolved reference" errors in untouched files**, which
is an hour of debugging the wrong thing. Recovery is `rm -rf app/build/kotlin`.

## 3. Toolchain notes worth knowing before you change a version

Each of these cost a build failure to discover, so they are recorded rather than left as folklore.

* **Do not apply `org.jetbrains.kotlin.android`.** Under AGP 9 it fails with *"no longer required
  for Kotlin support since AGP 9.0"*. Kotlin support is built into AGP; only
  `org.jetbrains.kotlin.plugin.compose` is applied explicitly, and it **must match AGP's bundled
  Kotlin version** (2.4.10).
* **`compileSdk` must be 37.** Compose 1.12.0 (from BOM 2026.08.00) refuses to compile against
  anything lower, and requires AGP ≥ 9.1.0.
* **No `jvmToolchain(17)` block.** Only JDK 21 is present in the reference environment, so a
  toolchain request for 17 fails with *"Cannot find a Java installation… languageVersion=17"*.
  `compileOptions { sourceCompatibility/targetCompatibility = VERSION_17 }` on JDK 21 does the
  right thing.
* **`android.defaults.buildfeatures.buildconfig` was removed in AGP 9.** Set
  `buildFeatures { buildConfig = true }` in the module instead.
* **`--` is illegal inside a generated XML comment.** aapt2 rejects it (*"The string \"--\" is
  not permitted within comments"*), which matters when a generator writes provenance comments
  into resource XML.
* **`android:Theme.DeviceDefault.DayNight.NoActionBar` does not exist.** Use `DayNight` with
  `windowActionBar=false` and `windowNoTitle=true`.

Every version in `gradle/libs.versions.toml` was checked against the publishing repository's
`maven-metadata.xml` (`dl.google.com/dl/android/maven2` or `repo1.maven.org`) and then proven by
assembling both APKs and running the test suite.

## 4. targetSdk 36 against compileSdk 37 — deliberate

`targetSdk = 36` (Android 16), `compileSdk = 37`. This combination means Roadguard opts into
Android 16 behaviour changes, which have been reasoned about, while compiling against API 37 so
newer APIs can be used behind version checks — without inheriting API 37's behaviour changes
untested. Lint's two `OldTargetApi` warnings about this are in the baseline with that
explanation.

`minSdk = 34` (Android 14), as the specification requires.

## 5. Signing

**No signing key is committed, and none ever should be.** `.gitignore` covers
`keystore.properties`, `*.jks` and `*.keystore`.

### Signing a real release

Either drop a git-ignored `keystore.properties` in the repository root:

```properties
storeFile=/absolute/path/to/roadguard-release.jks
storePassword=…
keyAlias=roadguard
keyPassword=…
```

or set the environment:

```bash
export ROADGUARD_KEYSTORE_FILE=/absolute/path/to/roadguard-release.jks
export ROADGUARD_KEYSTORE_PASSWORD=…
export ROADGUARD_KEY_ALIAS=roadguard
export ROADGUARD_KEY_PASSWORD=…
./gradlew :app:assembleRelease
```

### When no key is supplied

`assembleRelease` **falls back to the Android debug key** and stamps the version name
`1.0.0-unsigned-release`. This is deliberate: CI must produce an APK you can actually sideload,
and a build that silently emits an unsigned, uninstallable APK is worse than one that says what
it did.

> **Signing status of any artifact from CI, stated plainly:** unless
> `ROADGUARD_KEYSTORE_BASE64` and its companion secrets are configured on the repository, the
> release APK is **signed with the public Android debug key**. It installs and runs. It is
> **not** suitable for distribution, and Play would reject it. `versionName` says so, and the CI
> log emits a `::notice::` saying so.

In CI, supply the keystore as a base64 secret:

```bash
base64 -w0 roadguard-release.jks   # → repository secret ROADGUARD_KEYSTORE_BASE64
```

plus `ROADGUARD_KEYSTORE_PASSWORD`, `ROADGUARD_KEY_ALIAS`, `ROADGUARD_KEY_PASSWORD`.

## 6. Installing

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

The debug build uses `applicationId` suffix `.debug`, so debug and release install side by side.

Both APKs are universal, carrying `arm64-v8a`, `armeabi-v7a` and `x86_64` native libraries —
MapLibre ships native renderers, and a single universal APK keeps sideloading simple. `x86_64`
is included so the app can be exercised on an emulator.

## 7. CI

`.github/workflows/android.yml` runs on every push and pull request:

1. Android Lint (`abortOnError = true`)
2. Unit tests
3. Debug APK
4. Release APK
5. **APK verification** — `apksigner verify --print-certs` on both, then `aapt2 dump badging`
   assertions that the package name is right, `minSdk` really is 34, the label really is
   "Roadguard", and `CAMERA`, `ACCESS_FINE_LOCATION` and `FOREGROUND_SERVICE_CAMERA` are all
   declared
6. **Forbidden-dependency check** — the release runtime classpath is dumped and the build fails
   if Firebase, Crashlytics, Sentry, Amplitude, Mixpanel, Segment, AppsFlyer, the Facebook SDK or
   Play Services Analytics appears. See `docs/privacy.md` §2
7. Artifact upload: both APKs, plus lint and test reports

A second, opt-in job (`workflow_dispatch` only) boots an API 34 emulator for instrumentation
tests. It is opt-in because emulator runs are slow and cannot exercise the camera or GNSS paths
meaningfully. **Note that there are currently no instrumentation tests for it to run** — see
`docs/testing.md`.

`.github/workflows/build-offline-map.yml` is a separate manual workflow that builds and publishes
the offline map archive. See `docs/offline-maps.md` §5.

## 8. Reproducibility

* Gradle wrapper pinned by version **and SHA-256**.
* Every dependency pinned to an exact version in one version catalogue — no ranges, no
  `latest.release`, no dynamic versions.
* `dependencyResolutionManagement` in `settings.gradle.kts` declares the repositories
  explicitly, in order.
* CI pins JDK 21, SDK platform 37.1 and build tools 37.0.0 by exact version.
* The generated assets — launcher icons, Material icon drawables, map glyphs and sprites, map
  styles — are produced by scripts in `tools/`, committed, and re-derivable:

  ```bash
  python3 tools/generate_icons.py         # launcher icons from icon.png
  python3 tools/fetch_material_icons.py   # the 62 referenced Material icons, verbatim
  python3 tools/check_material_icons.py   # fails if referenced/bundled/listed sets diverge
  python3 tools/fetch_map_assets.py       # glyphs and sprites
  python3 tools/generate_map_styles.py    # day and night styles
  ```

* `tools/build_australia_pmtiles.sh` builds the map archive deterministically enough to be worth
  a checksum, and emits one.

Byte-identical APK reproducibility is **not** claimed: R8 and the resource shrinker are not
bit-reproducible across environments, and no attempt has been made to verify otherwise. What is
claimed is that the same inputs are pinned, so a build from a clean checkout produces
functionally identical output.

## 9. Regenerating the launcher icon

`tools/generate_icons.py` derives the adaptive icon, the legacy per-density mipmaps and a 512 px
store icon from the repository's `icon.png`. The artwork is used as supplied — it is placed as a
badge at exactly 72 dp inside the 108 dp adaptive canvas over black, and is **not** redrawn,
recoloured or reinterpreted.

No monochrome layer is generated. The artwork is photographic; an automatically derived
silhouette would misrepresent it, and hand-drawing one would mean inventing competing artwork.
Themed icons therefore fall back to the full-colour adaptive icon, and lint's
`MonochromeLauncherIcon` warning is baselined with that reasoning.

## 10. Lint baseline

`app/lint-baseline.xml` holds **four** categories and nothing else, so a *new* lint error still
fails the build:

| Category | Count | Why |
| --- | --- | --- |
| `UnsafeOptInUsageError` | 15 | `@file:OptIn` declarations Android lint cannot see through (a known lint limitation) — Camera2 interop for hardware level and focal lengths, and media3's Compose playback surface. Both opt-ins are explicit in source and confined to one file each |
| `OldTargetApi` | 2 | targetSdk 36 against compileSdk 37, deliberately — see §4 |
| `IconLauncherShape` | 5 | the legacy PNGs fill their square because the supplied artwork *is* a squircle badge on black; every device at minSdk 34 uses the adaptive icon instead |
| `MonochromeLauncherIcon` | 2 | see §9 |

An earlier baseline swallowed 11 categories including 64 `UnusedResources`. It was deleted, the
real warnings were fixed, and it was regenerated — which is the right way to use a baseline.

## 11. Verified build state

Reproduced from a clean invocation of the commands in §2 in this environment:

| | |
| --- | --- |
| Unit tests | **338 tests, 0 failures, 0 errors, 0 skipped** |
| Lint | clean against the baseline in §10 |
| Debug APK | builds; `apksigner verify` passes |
| Release APK | builds; `apksigner verify` passes |
| `minSdk` in the built APK | 34 |
| `targetSdk` in the built APK | 36 |
| Label | Roadguard |
| ABIs | arm64-v8a, armeabi-v7a, x86_64 |

Exact byte sizes are in `docs/benchmarking.md` §1, with the caveat that they change with every
dependency bump.

**Not verified:** the app has never been installed on a physical device or an emulator in this
work. See `docs/testing.md`.
