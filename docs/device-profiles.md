# Device profiles

Roadguard has **no device database, no model whitelist and no per-phone special case.** There
is no line of code anywhere that reads "if this is a Moto G04, do X". Everything about how the
app configures itself comes from facts it probes at runtime.

That is a specification requirement (§8: *"Do not hard-code Moto G04 = profile X"*) and it is
also the only design that survives contact with the Android device population.

---

## 1. What is probed

`DeviceCapabilityProbe` collects, at start-up:

| Group | Facts |
| --- | --- |
| Platform | API level, release name, manufacturer, model, device, SoC model and manufacturer |
| CPU / memory | core count, highest per-core `cpufreq` maximum in GHz, total and available RAM, `ActivityManager.isLowRamDevice()` |
| Cameras | per camera: lens facing, Camera2 hardware level, supported resolutions, CameraX quality names, maximum frame rate, focal lengths, stabilisation support |
| Encoders | per `MediaCodecInfo`: MIME type, **whether it is hardware-accelerated**, supported sizes and frame rates |
| Concurrency | the camera-id pairs the platform says may run simultaneously |
| Thermal | which of `getCurrentThermalStatus`, `getThermalHeadroom` and battery temperature actually answer |
| Sensors | accelerometer, gyroscope, their maximum rates |
| Display | size, density, refresh rate |
| Measured | a short deterministic CPU probe, in work units per millisecond |

The SoC and model strings are collected **for the Diagnostics display and for bug reports
only**. Nothing branches on them.

## 2. How a tier is earned

`DeviceTierScorer` awards up to seven points:

| Signal | Points | Threshold |
| --- | --- | --- |
| `isLowRamDevice()` | — | **Forces `Baseline` immediately.** An explicit platform statement that the device is resource-constrained |
| Total RAM | +2 / +1 | ≥ 7 GiB / ≥ 5 GiB |
| `cpufreq` ceiling | +2 / +1 | ≥ 2.3 GHz / ≥ 1.9 GHz |
| Camera2 hardware level | +1 | `FULL` or `LEVEL_3` |
| Hardware encoder at 1080p30 | +1 | **Absent ⇒ forces `Baseline`** |
| CPU probe | +1 | ≥ 1200 units/ms — a tiebreaker (see §5) |

Then: `Capable` at ≥ 6, `Standard` at ≥ 3, otherwise `Baseline`.

### Why these signals and not others

* **Core count is actively misleading.** An 8× Cortex-A55 cluster at 1.6 GHz and a 4× A78 +
  4× A55 at 2.5 GHz are both "octa-core", and the second one will sustain video encoding the
  first one will not. The `cpufreq` ceiling separates them; core count does not.
* **RAM matters because of the map.** Continuous encoding plus a vector renderer is memory
  hungry. Under 4 GiB there is no headroom for the map at all, which is why RAM contributes
  before anything else.
* **A hardware 1080p encoder is a veto, not a bonus.** Roadguard will not sustain hours of
  software encoding, so without one 1080p is off the table regardless of everything else.
* **`LEGACY`/`LIMITED` camera hardware levels** are more likely to force stream sharing and
  extra GPU copies, which is a thermal and reliability cost.

### The tier is explained, not asserted

`DeviceTierAssessment` carries the reasons as strings — "RAM 3.7 GiB is tight for map plus
encoder", "CPU tops out at 1.6 GHz", "hardware encoder handles 1080p30" — and Diagnostics shows
them verbatim. A user who wants to know why Auto picked 720p can read the actual reasoning on
their own phone.

## 3. What each tier gates

| | `Baseline` | `Standard` | `Capable` |
| --- | --- | --- | --- |
| Auto resolution ceiling | **720p** | **1080p** | **1080p** |
| Auto frame rate | 30 | 30 | 30 |
| Map rendering | allowed, shed early | allowed | allowed |
| Video stabilisation on `Auto` | no | no | **yes** (if the camera supports it) |
| Night assist | no | yes | yes |
| Dual camera | no | no | yes (and only if the platform reports a concurrent pair) |
| HDR | no | no | no — never chosen automatically, on any tier |

Two rows deserve explanation.

**`Capable` is also capped at 1080p on Auto.** This is not an oversight. 4K recording on a
phone is a well-documented thermal cliff, and the specification is explicit that a slightly
lower-quality reliable recording beats a theoretically better one that overheats. 4K remains
available by selecting it manually, with a warning attached. Auto's job is *sustainable*
quality, and a ceiling it can hold for three hours is worth more than a peak it can hold for
twenty minutes.

**Stabilisation defaults off below `Capable`.** A cradle-mounted phone barely benefits from EIS
— the cradle already removes hand shake — and EIS crops the frame and costs power. `Auto`
therefore enables it only on a device fast enough not to care; `On` still respects the camera's
declared support.

## 4. The two named target devices

The specification names two devices. Roadguard does not special-case either, so this section is
about **expectations**, and every figure in it comes from published specifications, not from
measurement.

### Motorola Moto G04 — the floor (Android 14)

| Published | |
| --- | --- |
| SoC | Unisoc T606 (2× Cortex-A75 ~1.6 GHz + 6× Cortex-A55) |
| GPU | Mali-G57 **MP1** — one shader core |
| RAM | 4 GB class |
| Storage | eMMC, with a microSD slot |

**Expected tier: `Baseline`.** Reasoning: RAM at 4 GB scores nothing, a ~1.6 GHz ceiling scores
nothing, the camera is unlikely to report `FULL`, and a hardware 1080p encoder is present on
this class of part, giving roughly 1 point out of 7. Auto would therefore choose **720p30**.

If the device reports `isLowRamDevice()` true, it is `Baseline` immediately regardless.

The Mali-G57 MP1 is why the map style is cut to 18 layers rather than the 200-plus of published
styles: with one shader core, draw-call count is the binding constraint, and GPU work competes
with the encoder for memory bandwidth. See `docs/offline-maps.md` §2.

The microSD slot is why `StorageLayout` takes a `getExternalFilesDirs()` entry rather than a
fixed path.

### Motorola Edge 60 Fusion — the "must feel good" device (Android 15)

| Published | |
| --- | --- |
| SoC | MediaTek Dimensity 7400 |
| RAM | 8–12 GB |
| Display | OLED |

**Expected tier: `Capable`.** Reasoning: 8 GB+ RAM scores 2, a >2.3 GHz ceiling scores 2, a
`FULL`-level camera scores 1, a hardware 1080p encoder scores 1 — six points before the probe.
Auto would still choose **1080p30**, by the ceiling above; the difference from the G04 is not
the resolution but the headroom: full map rendering, stabilisation available, `Elevated`
reached later and less often, and 4K available manually with a real chance of sustaining it.

The OLED panel is why an **OLED-black theme** exists as a distinct option from Dark: true black
costs no backlight on OLED, which matters for an app that runs for hours.

> **Neither expectation has been verified.** No physical device was available. Both are
> predictions from published specifications and from the scoring table in §2. The Diagnostics
> screen on a real device will say what actually happened, and that is the figure to trust.

## 5. Calibration a maintainer with hardware should do

| What | Why | How |
| --- | --- | --- |
| `CPU_PROBE_STRONG = 1200f` | Calibrated against the x86-64 build machine, which is not representative of either target. Currently a deliberately weak tiebreaker | Run the probe on both devices, record the values, set the threshold between the two clusters |
| `RAM_STANDARD_BYTES = 5 GiB`, `RAM_CAPABLE_BYTES = 7 GiB` | Chosen to sit either side of the common 4/6/8 GB tiers, not measured | Confirm the map plus encoder actually fits at 6 GB before trusting `Standard` |
| `CLOCK_STANDARD_GHZ = 1.9`, `CLOCK_CAPABLE_GHZ = 2.3` | Chosen to separate A55-class from A7x-class clusters | Check what `cpufreq` actually reports on both devices — some vendors report boost clocks, some do not |
| The `AUTO_CEILING` map | 1080p for `Capable` is a thermal judgement, not a measurement | Run the two-hour thermal test in `docs/thermal-management.md` §7 at 1080p and at 4K, and move the ceiling only if 4K genuinely sustains |

Record the results in `docs/measurements/` and change the constants in the same commit, so the
next reader can see what each number is based on.

## 6. Graceful degradation when a probe fails

Every probe can fail, and none of them failing is allowed to stop a recording.

| Missing | Behaviour |
| --- | --- |
| `cpufreq` unreadable (SELinux, unusual kernel) | scores 0 points, reason recorded as "CPU clock ceiling not readable" |
| Camera has not reported supported qualities yet | a conservative default is requested and the rationale says so |
| No hardware encoder reported at all | `Baseline`, and the rationale says the recorder's own choice will be shown once a file exists |
| No thermal status **and** no headroom | falls back to battery temperature at 41/44/47 °C, tagged as a fallback |
| No gyroscope | event detection continues on the accelerometer with confidence scaled by 0.92 |
| No GNSS fix | speed and coordinate overlays are hidden; event detection raises its bar rather than skipping the speed check |
| Concurrent camera pairs empty | dual camera is unavailable, and says why |

## 7. What has been verified

| Claim | Status |
| --- | --- |
| The scoring table produces the intended tier for every combination, including the two vetoes | **Verified** — `DeviceTierScorerTest`, 16 JVM tests, passing |
| Profile selection honours tier, thermal step-down, camera support, encoder support and frame-rate caps in the right order | **Verified** — `RecordingProfileSelectorTest`, 44 JVM tests, passing |
| No code path branches on a device model name | **Verified** by inspection — `grep -rn "moto\|Moto\|G04\|Unisoc\|Dimensity" app/src/main/java/` returns only comments and KDoc |
| The Moto G04 lands in `Baseline` and the Edge 60 Fusion in `Capable` | **Not verified.** Prediction from published specifications; no device was available |
| The thresholds in §2 are the right ones | **Not verified.** Reasoned starting points — see §5 |
