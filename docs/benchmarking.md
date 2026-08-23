# Benchmarking

> **Read this first.** Every performance figure in this document is either (a) something
> measured on the **build machine** — an x86-64 Linux container — or (b) **arithmetic**. None of
> it was measured on a Motorola Moto G04, a Motorola Edge 60 Fusion, an Android emulator, or any
> phone. No physical device was available during this work.
>
> Nothing here is presented as device performance. §5 is the procedure for producing real
> figures, and the tables that need them say so.

---

## 1. Build and artefact measurements *(measured, build machine)*

| Metric | Value |
| --- | --- |
| Unit test suite | **368 tests, 0 failures, 0 errors, 0 skipped** (301 plain JVM, 67 Compose UI) |
| Unit test wall clock | ~31 s of test execution (warm Gradle, configuration cache hit) |
| Debug APK | 83,986,408 bytes (80.1 MiB) |
| Release APK | 38,392,577 bytes (36.6 MiB) |
| ABIs in both | arm64-v8a, armeabi-v7a, x86_64 |
| Bundled map assets | 2.54 MiB (24 glyph PBFs, 4 sprite files) |
| Offline map archive | 231 MiB whole-of-Australia, or 45-348 MiB per state; downloaded once |

The debug/release gap is almost entirely MapLibre's three sets of native renderers plus
Compose tooling; R8 and the resource shrinker account for the rest. A single-ABI release APK
would be roughly a third of the size, but universal APKs are the right call for sideloading,
which is how this app is distributed.

Sizes move with every dependency bump. Treat them as a snapshot, not a budget.

## 2. Storage per hour *(calculated, not measured)*

Roadguard cannot choose the codec or the bitrate — CameraX 1.6 derives both from the device's
own encoder profiles (see `docs/architecture.md` §9). So these are the figures the app's own
bitrate model predicts, using bits-per-pixel-per-frame: **0.10 bpp for H.264** and **0.07 bpp
for HEVC**, both mid-range values for "legible detail in a moving scene".

### H.264 at 0.10 bpp

| Profile | Bitrate | Per hour | 5 GB loop holds |
| --- | --- | --- | --- |
| 480p30 | 1.04 Mbps | 0.47 GB | 11.5 h |
| **720p30** *(Auto on `Baseline`)* | 2.76 Mbps | 1.24 GB | **4.3 h** |
| **1080p30** *(Auto on `Standard`/`Capable`)* | 6.22 Mbps | 2.80 GB | **1.9 h** |
| 1080p60 | 12.44 Mbps | 5.60 GB | 1.0 h |
| 2160p30 | 24.88 Mbps | 11.20 GB | 0.5 h |

### HEVC at 0.07 bpp

| Profile | Bitrate | Per hour | 5 GB loop holds |
| --- | --- | --- | --- |
| 480p30 | 0.73 Mbps | 0.33 GB | 16.4 h |
| 720p30 | 1.94 Mbps | 0.87 GB | 6.2 h |
| 1080p30 | 4.35 Mbps | 1.96 GB | 2.7 h |
| 1080p60 | 8.71 Mbps | 3.92 GB | 1.4 h |
| 2160p30 | 17.42 Mbps | 7.84 GB | 0.7 h |

**Real device bitrates will differ, possibly by a factor of two.** Vendor `CamcorderProfile`
figures are frequently more generous than 0.10 bpp — 1080p30 at 17–20 Mbps is common — which
would cut the 5 GB loop from 1.9 hours to under 40 minutes.

That is precisely why the app does not ship this table. The Storage screen reports a
**measured** bytes-per-second from the files *this* device actually wrote, and derives loop
coverage from that, tagged `[measured]`. The arithmetic above exists to explain the design
(and to justify the 5 GB default being on the small side of comfortable), not to make a promise.

## 3. What the CPU probe measures — and what it does not

`PerformanceProbe` runs a short arithmetic loop at start-up and reports units/ms. It feeds
`DeviceTierScorer` as a **tiebreaker only**, worth one point out of a possible seven.

`CPU_PROBE_STRONG = 1200f` is calibrated against **the build machine**, which is an x86-64
container and not remotely representative of a Unisoc T606 or a Dimensity 7400. It is
deliberately weak in the scoring for that reason: `isLowRamDevice`, total RAM, the `cpufreq`
clock ceiling, the Camera2 hardware level and the presence of a hardware 1080p encoder carry
six of the seven points between them, and those are all *reported facts* rather than
measurements.

`docs/device-profiles.md` §5 lists the on-device figures needed to calibrate the probe
properly.

## 4. Figures that are asserted nowhere, because they were not measured

For completeness — these are the numbers a benchmarking document would normally contain, and
Roadguard has none of them:

* time from cold app launch to first recorded frame;
* the segment-rollover gap (the encoder must stop and the next segment needs a fresh keyframe,
  so the gap is non-zero; **how** non-zero is unmeasured);
* frames dropped per hour, at any profile, on any device;
* battery drain per hour, screen on or off;
* map frame rate on a Mali-G57 MP1, at any of the four `MapWorkBudget` levels;
* time to thermal steady state, or to each `ThermalLevel`;
* impact-detection latency;
* GNSS time-to-first-fix, cold or warm;
* sustained write throughput on the Moto G04's eMMC or on a microSD card.

No estimate is offered for any of them. An invented number in a benchmarking document is worse
than a blank, because it will be quoted.

## 5. How to produce real figures

### 5.1 Rig

* The phone in the cradle it will actually live in, on the windscreen, in the sun.
* The charger it will actually use, connected.
* Ambient temperature recorded for every run. A bench at 22 °C measures nothing relevant to an
  Australian summer.
* At least 90 minutes per run: thermal steady state takes 20–40 minutes.

### 5.2 Instrumentation that already exists

* **Diagnostics export** — device facts, camera and encoder capabilities, thermal readings, the
  active `RecordingProfile` with its full rationale, storage figures and recent recorder events,
  every value provenance-tagged.
* **Storage screen** — measured bytes per second, loop coverage, headroom to first deletion.
* **Thermal harness** — five simulated scenarios to exercise the ladder without waiting for real
  heat. Everything it produces is tagged `[simulated]`.
* **Near-miss reporting** — rejected event candidates with features, confidence and rejection
  reasons, which is how event thresholds get calibrated.

### 5.3 Runs worth doing, in priority order

1. **Two hours, Auto, map on, screen on, in the sun.** Answers the only question that matters:
   does it record for two hours without stopping? Capture the Diagnostics export every 5 minutes.
2. **Same, screen off after 2 minutes.** The realistic case, and the one where the wake lock and
   the FGS camera grant are load-bearing.
3. **Measured bitrate per profile.** Record 5 minutes at each of 720p30, 1080p30, 1080p60 and 4K
   if offered; divide file size by duration. This replaces §2 with facts.
4. **Segment-rollover gap.** Record a stopwatch or a flashing LED at 60 fps across a rollover and
   count missing frames between the closing and opening files.
5. **Battery drain.** Charge to 100 %, unplug, record until 20 %, note the wall clock. Repeat
   screen-on and screen-off.
6. **Map frame rate** via `dumpsys gfxinfo` at each `MapWorkBudget` level.
7. **Cold-start to first frame**, from `am start` to the first segment's first frame timestamp.
8. **Drive traces for event detection** — see `docs/event-detection.md` §9.

### 5.4 Where to put the results

A new `docs/measurements/<device>-<date>.md` per run, recording device, Android build, ambient
temperature, cradle, charger and every figure. Then update the constants they inform — the
thermal thresholds, the bitrate model, `CPU_PROBE_STRONG`, the tier thresholds — **in the same
commit as the measurements**, so a future reader can see what the number is based on.

## 6. Honesty checklist for this document

| | |
| --- | --- |
| Any figure presented as device performance? | **No** |
| Any figure measured on a phone or emulator? | **No** — none was available |
| Calculated figures labelled as calculated? | **Yes** — §2 |
| Build-machine figures labelled as such? | **Yes** — §1, §3 |
| Missing measurements listed rather than estimated? | **Yes** — §4 |
