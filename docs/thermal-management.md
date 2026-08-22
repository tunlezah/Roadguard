# Thermal management

A phone recording video continuously in a windscreen cradle, in the sun, is close to a
worst-case thermal load: the camera sensor, the ISP, the video encoder, the display, the GPU
(map and overlays), the GNSS receiver and — usually — a charger are all working at once, inside
a sealed plastic and glass case with no airflow.

Roadguard treats this as a first-class subsystem with its own engine, its own policy ladder and
its own test harness, rather than as a few `if (hot)` checks.

> **Validation status, stated up front.** No Roadguard thermal figure in this document or in
> the app has been measured on a physical Motorola Moto G04, a Motorola Edge 60 Fusion, or any
> other phone. No physical device was available. Every threshold is a reasoned starting point;
> every number the app displays carries a provenance tag; and §7 below is the procedure for
> replacing reasoning with measurement. See also `docs/testing.md`.

---

## 1. Signals, and how much each is trusted

| Signal | API | Trust | Used for |
| --- | --- | --- | --- |
| Thermal status | `PowerManager.getCurrentThermalStatus()` + `addThermalStatusListener` | High when present. Coarse (7 values) and **lags**: by the time it says `SEVERE` the platform is already throttling. | Escalation |
| Thermal headroom | `PowerManager.getThermalHeadroom(forecastSeconds)` | High when present, and *leading* rather than lagging. Not implemented by every vendor; can return `NaN`. | **Primary early warning** |
| Battery temperature | `ACTION_BATTERY_CHANGED` → `EXTRA_TEMPERATURE` | Low. Lags SoC temperature by minutes and is dominated by charging, not by encoding. | Fallback **only** when neither of the above is available |

The last row matters. Battery temperature is a genuinely bad proxy for SoC temperature: a phone
on a fast charger reads hot while its SoC is idle, and a phone encoding 1080p can throttle
while its battery is still cool. So `ThermalPolicy.classify` consults battery temperature
**only** when `reading.status == null` and `reading.headroom` is null-or-NaN. Otherwise it is
recorded for display and ignored for decisions.

`ThermalMonitor` records which signals produced each reading in `ThermalReading.sources`, and
that set is carried all the way to the Diagnostics screen and the exported report.

## 2. Levels

Four levels, deliberately coarser than the platform's seven, because several mitigations can
only be applied at a segment boundary and the recorder needs a small number of *stable*
operating points:

| Roadguard level | Entered when | Meaning |
| --- | --- | --- |
| `Normal` | headroom < 0.80, status ≤ `LIGHT` | Nothing to do |
| `Elevated` | headroom ≥ 0.80, or status `MODERATE` | Warm. Shed optional work — **do not touch recording quality** |
| `High` | headroom ≥ 0.92, or status `SEVERE` | Hot. Reduce recording cost and stop rendering the map |
| `Critical` | headroom ≥ 0.99, or status ≥ `CRITICAL` | Throttling. Preserve a valid recording above all else |

Battery-temperature fallback thresholds, used only when nothing better exists: 41 °C, 44 °C,
47 °C.

**Headroom thresholds are set below 1.0 on purpose.** `getThermalHeadroom()` returns a
normalised value where 1.0 means throttling is imminent. Acting at 0.80 gives the recorder time
to change profile *at the next segment boundary* instead of being throttled mid-segment, which
is what produces dropped frames and truncated files.

## 3. The ladder

Every field of a `ThermalPlan` is a **ceiling, not a command**: the recorder combines the plan
with the user's settings and the device profile, and only ever reduces.

| Mitigation | Normal | Elevated | High | Critical |
| --- | --- | --- | --- | --- |
| Preview frame-rate cap | — | 24 fps | 15 fps | 10 fps |
| Map rendering | Full | Reduced | **Frozen** | **Off** |
| Burned-in video overlays | on | on | on | **off** |
| Second camera | allowed | off | off | off |
| Video stabilisation | allowed | off | off | off |
| Night assist | allowed | allowed | off | off |
| Location interval | 1 s | 2 s | 2 s | 5 s |
| Weather refresh | 15 min | 30 min | 60 min | 120 min |
| **Resolution step-down** | 0 | **0** | 1 | 2 |
| **Frame-rate cap (recording)** | — | **—** | 30 fps | 24 fps |
| **Bitrate scale** | 1.00 | **1.00** | 0.85 | 0.70 |
| Reduced UI animation | no | yes | yes | yes |
| User warned | no | no | **yes** | **yes** |
| **Recording continues** | yes | yes | yes | **yes** |

Read the `Elevated` column carefully: it is entirely free of recording changes. That is the
design. The display, the map, the preview, stabilisation and the second camera all cost real
power and contribute nothing to the evidence, so they are spent first. Recording quality is the
*last* thing Roadguard gives up, and recording itself is never given up at all.

The two user-visible messages are:

* **High** — "Device is hot. Roadguard has reduced recording quality and paused the map to keep
  recording."
* **Critical** — "Device is very hot. Roadguard is recording at the lowest quality it can and
  has turned off the map, overlays and preview to stay running. Improve airflow or shade the
  phone."

## 4. Hysteresis

Rebinding the camera costs a segment boundary, so flapping between levels is itself a
reliability problem.

* **Escalation is immediate.** Heat is a real risk and waiting makes it worse.
* **De-escalation requires the cooler reading to hold for `DEESCALATE_HOLD_MS = 90 s`**, and
  then steps down **one level at a time**. A brief cool patch — a tunnel, a cloud, a moment in
  shade — cannot jump from `Critical` straight back to `Normal`.

`ThermalPolicyTest` (25 tests) covers this directly: immediate escalation, no de-escalation
before the hold expires, single-step de-escalation after it, and the interaction of the three
signal sources.

## 5. What is applied when

| Mitigation | Applied |
| --- | --- |
| Preview frame rate, map budget, UI animation, location interval, weather interval | **Immediately.** None of them touch the encoder. |
| Resolution, recording frame rate, bitrate, stabilisation, overlay burn-in, second camera | **At the next segment boundary only.** These are bind-time properties of the CameraX session; changing them mid-segment means truncating the file. |

`ThermalPlan.requiresRebindFrom(previous)` is what decides which bucket a change falls into,
and `SegmentPlanner` schedules the boundary. This is the single most important rule in the
thermal design: **no thermal event ever cuts a recording short.**

## 6. The test harness

`SimulatedThermalSource` implements the same `ThermalSource` interface as
`AndroidThermalSource` and can be swapped in at runtime from the Diagnostics screen. It offers
five named scenarios:

| Scenario | Status | Headroom | Battery | Expected level |
| --- | --- | --- | --- | --- |
| Normal | `NONE` | 0.20 | 28 °C | `Normal` |
| Warm | `LIGHT` | 0.65 | 36 °C | `Normal` |
| Elevated | `MODERATE` | 0.84 | 41 °C | `Elevated` |
| Throttling | `SEVERE` | 0.95 | 44 °C | `High` |
| Severe | `CRITICAL` | 1.00 | 48 °C | `Critical` |

### Simulated is never allowed to look measured

This is a specification requirement (§35) and it is enforced in three places:

1. `ThermalReading.sources` contains `ThermalSignalSource.Simulated`.
2. `DiagnosticsCollector` maps that to `Provenance.Simulated`, whose display suffix is
   `[simulated]` — and the Diagnostics screen renders that tag in the *critical* colour, loudly,
   while `[measured]` and `[inferred]` are rendered quietly.
3. The exported diagnostics report begins with the line: *"Values marked [simulated] came from
   the developer thermal harness and are not measurements."*

The provenance vocabulary is used throughout Diagnostics, not just for thermal:
`PlatformReported` (read from an Android API), `Measured` (Roadguard measured it on this
device), `Inferred` (derived from other values), `Simulated` (the harness), `Unavailable` (the
platform did not answer).

## 7. How to replace reasoning with measurement

This is the procedure a maintainer with hardware should follow. Until someone does, the
thresholds stay as documented starting points.

**Rig.** Phone in the windscreen cradle it will actually live in. Charger connected, as it will
actually be. Ambient temperature recorded. An Australian summer afternoon is the case that
matters; a bench at 22 °C proves nothing.

**Procedure, per device and per profile (Auto, 1080p30, 1080p60, 4K if offered):**

1. Note ambient temperature and starting battery level.
2. Start recording with the map visible and overlays on — the realistic configuration.
3. Every 60 s, capture from the Diagnostics export: thermal status, headroom, battery
   temperature, current `ThermalLevel`, the active `RecordingProfile`, and segment file sizes.
4. Run for **90 minutes minimum.** Thermal steady state on a phone takes 20–40 minutes; a
   10-minute test measures nothing but the heat capacity of the case.
5. Record the wall-clock time of the first escalation to each level, and whether any segment
   was dropped, truncated or short.
6. Repeat with the screen off after 2 minutes — the display is a large share of the load, and
   this is how the app will usually be used.

**What to conclude:**

* If escalation to `Elevated` happens within a few minutes on every run, `HEADROOM_ELEVATED`
  is too low for that device and is costing map quality for nothing.
* If frames are dropped or a segment is truncated *before* `High` is reached, escalation is too
  late — lower the thresholds, do not add mitigations.
* If `Critical` is reached and the device still throttles, the ladder is not deep enough:
  consider a `Baseline`-tier resolution floor rather than a further bitrate cut.
* Record the numbers in a new `docs/measurements/` file with device, ambient temperature, date
  and cradle, and update the constants in `ThermalPolicy` in the same commit.

## 8. What has actually been verified

| Claim | Status |
| --- | --- |
| The ladder escalates and de-escalates as specified, including the 90 s hold and single-step descent | **Verified** — `ThermalPolicyTest`, 25 JVM tests, passing |
| Battery temperature is ignored when a platform signal exists | **Verified** — unit tested |
| A thermal change never rebinds the camera outside a segment boundary | **Verified in policy** — `SegmentPlannerTest` and `ThermalPlan.requiresRebindFrom`; **not verified on hardware** |
| Simulated values are tagged as simulated everywhere they surface | **Verified** by code inspection and by the provenance type |
| The thresholds are correct for a Moto G04 in an Australian summer | **Not verified.** No device was available. This is reasoning, not measurement |
| Recording survives `Critical` on real hardware | **Not verified.** No device was available |
