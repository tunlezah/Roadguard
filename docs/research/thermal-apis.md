# Roadguard — Android Thermal APIs, Thermal Headroom, and What Actually Heats a Phone Recording Video

**Scope:** platform thermal signal acquisition, its reliability on our baseline hardware, a ranked model of heat generation during dashcam operation, and a concrete mitigation ladder split by "safe to change mid-recording" vs "requires a new recording session".
**Targets:** Motorola Moto G04 / Android 14 / Unisoc T606 (baseline) and Motorola Edge 60 Fusion (mid-range MediaTek). `minSdk = 34`, must run to API 36.
**Retrieved:** 2026-08-22.

---

## Bottom line

Build one `ThermalMonitor` abstraction behind a Kotlin interface, feed it from **three independent signal sources** — `PowerManager.getCurrentThermalStatus()` (API 29, always present but frequently a lie on low-end OEM devices), `PowerManager.getThermalHeadroom(0)` and `getThermalHeadroom(30)` (API 30, the *primary* signal, but returns `NaN` forever on devices whose thermal HAL exposes no SKIN sensor with a SEVERE hot threshold), and a **fallback battery-temperature estimator** from `ACTION_BATTERY_CHANGED`/`EXTRA_TEMPERATURE` — and drive a 5-state mitigation ladder with hysteresis and minimum dwell times. Poll headroom on a **single dedicated thread every 5 s** (not 10 s: the AOSP sampler self-terminates after 10 s of no calls, destroying the forecast history), gate `getThermalHeadroomThresholds()` behind `SDK_INT >= 35` with a hardcoded 0.85/0.95/1.00 fallback ladder taken from Google's own ADPF guidance, and gate `addThermalHeadroomListener()` behind `SDK_INT >= 36`. Crucially, **order the mitigation ladder so that every step above "reduce encoder bitrate" is achievable without stopping the recording**: display brightness, screen off, map render rate, overlay compositing, preview attach/detach, and GNSS rate are all runtime-mutable, whereas CameraX resolution/bitrate/fps/stream-use-case changes are `Recorder.Builder`/`VideoCapture.Builder` options and therefore require building a new `Recorder`, rebinding use cases, and starting a **new segment** — which is acceptable only because we already cut segments every 3 minutes, so schedule encoder-level changes to land on a segment boundary rather than mid-segment. Heat is dominated by the camera+ISP+encoder pipeline and the display; the single biggest thing we control on the baseline device is **not binding `ImageAnalysis` alongside `Preview` + `VideoCapture`** (CameraX documents that on `FULL`-or-lower hardware this forces stream sharing with "reduced battery life"), and the single biggest thing we do *not* control is fast charging on a windscreen in the sun.

---

## Evidence key

| Tag | Meaning |
|---|---|
| `[DOCUMENTED]` | Stated in official Android/AOSP/AndroidX/OEM documentation or read directly out of AOSP source. URL and the retrieval-relevant detail (API level, signature, constant value) given inline. |
| `[INFERRED]` | Not stated anywhere; derived by reasoning from `[DOCUMENTED]` facts. The reasoning chain is written out so it can be checked or rejected. |
| `[UNVERIFIED]` | Plausible and consistent with the docs, but I could not confirm it. Treat as a hypothesis, not a fact. |
| `[MEASURED]` | **Not used in this document.** No measurement was performed in this session. Anything that needs a number from real hardware is listed in *Open questions / must-measure-on-device*. |

---

## 1. Target-device thermal context

| Property | Moto G04 (baseline) | Edge 60 Fusion | Evidence |
|---|---|---|---|
| SoC | Unisoc T606, 2×Cortex-A75 @1.6 GHz + 6×Cortex-A55 @1.6 GHz | MediaTek Dimensity 7400, 4×A78 @2.5 GHz + 4×A55 @2.0 GHz | `[DOCUMENTED]` Motorola support: [G04 specs](https://en-us.support.motorola.com/app/answers/detail/a_id/178144/), [Edge 60 Fusion specs (AU)](https://en-au.support.motorola.com/app/answers/detail/a_id/187360/~/specifications---motorola-edge-60-fusion) |
| GPU | ARM Mali-G57 MP1 @650 MHz (**one** shader core) | ARM Mali-G615 MC2 (two cores) | `[DOCUMENTED]` same pages |
| Display | 6.56" **IPS LCD**, HD+ 1612×720, 269 ppi, 90 Hz | 6.67" **pOLED**, 2712×1220 (1.5K), 446 ppi, 120 Hz, HBM 1400 nits / HDR peak 4500 nits | `[DOCUMENTED]` same pages |
| RAM / storage | 4 GB (+ up to 8 GB "RAM expansion"), 64 GB | 8/12 GB LPDDR4X, 256 GB UFS 2.2 | `[DOCUMENTED]` same pages |
| Battery / charge | 5000 mAh, **15 W (5 V/3 A)** | 5500 mAh, **68 W TurboPower** | `[DOCUMENTED]` same pages |
| Ships with | Android 14 | Android 15 | `[DOCUMENTED]` same pages |

Notes that matter:

- **Region-variant discrepancy.** The Motorola AU support page for the Edge 60 Fusion states Dimensity **7400** / **5500 mAh**; other regional Motorola marketing pages surfaced in search list Dimensity **7300** / **5200 mAh**. `[UNVERIFIED]` which variant the physical test device is. Read `Build.SOC_MODEL` (API 31) / `Build.HARDWARE` on the actual unit before hardcoding anything SoC-specific. This does not change any API decision in this document.
- **The G04's GPU is a single Mali-G57 shader core.** `[INFERRED]` Continuous map rendering + Compose overlay compositing + a camera preview surface all contend for that one core, so GPU work is a first-class thermal lever on the baseline device in a way it would not be on the Edge. This is reasoning from the documented core count, not a measurement.
- **The G04 is LCD, the Edge is pOLED.** `[INFERRED]` LCD backlight power is roughly content-independent and scales with brightness; OLED power scales with per-pixel emission, so a dark map theme saves real power on the Edge and almost none on the G04. Reasoning from display technology, not from an Android doc.
- **15 W vs 68 W charging is the single largest uncontrollable variable.** See §11.
- G04 rear-camera video capability: the Motorola page documents **front** camera video as FHD 30 / HD 30 and does not state rear-camera video modes. `[UNVERIFIED]` — must be enumerated on-device via `Recorder.getVideoCapabilities(...)` / `QualitySelector`. Third-party sites claim T606 does 1080p60 encode; I found no unisoc.com primary source, so treat that as `[UNVERIFIED]`.

---

## 2. The platform thermal API surface — exact signatures and API gates

All from `android.os.PowerManager`, [reference page](https://developer.android.com/reference/android/os/PowerManager) `[DOCUMENTED]`.

| API | Exact signature | Added | Usable on our baseline (API 34)? |
|---|---|---|---|
| Status constants | `PowerManager.THERMAL_STATUS_NONE` = 0, `_LIGHT` = 1, `_MODERATE` = 2, `_SEVERE` = 3, `_CRITICAL` = 4, `_EMERGENCY` = 5, `_SHUTDOWN` = 6 | **29** | Yes |
| Poll status | `public int getCurrentThermalStatus()` | **29** | Yes |
| Status listener (main thread) | `public void addThermalStatusListener(PowerManager.OnThermalStatusChangedListener listener)` | **29** | Yes |
| Status listener (executor) | `public void addThermalStatusListener(Executor executor, PowerManager.OnThermalStatusChangedListener listener)` | **29** | Yes — **use this overload**, pass our own single-thread executor |
| Remove status listener | `public void removeThermalStatusListener(PowerManager.OnThermalStatusChangedListener listener)` | **29** | Yes |
| Callback interface | `interface PowerManager.OnThermalStatusChangedListener { void onThermalStatusChanged(int status); }` | **29** | Yes |
| Headroom | `public float getThermalHeadroom(int forecastSeconds)` | **30** | Yes |
| Headroom thresholds | `public Map<Integer, Float> getThermalHeadroomThresholds()` | **35** | **No — must be gated** |
| Headroom listener (main thread) | `public void addThermalHeadroomListener(PowerManager.OnThermalHeadroomChangedListener listener)` | **36** | **No — must be gated** |
| Headroom listener (executor) | `public void addThermalHeadroomListener(Executor executor, PowerManager.OnThermalHeadroomChangedListener listener)` | **36** | **No — must be gated** |
| Remove headroom listener | `public void removeThermalHeadroomListener(PowerManager.OnThermalHeadroomChangedListener listener)` | **36** | **No — must be gated** |
| Headroom callback interface | `void onThermalHeadroomChanged(float headroom, float forecastHeadroom, int forecastSeconds, Map<Integer, Float> thresholds)` | **36** | **No — must be gated** |

Adjacent APIs worth knowing, same reference tree, `[DOCUMENTED]`:

| API | Signature | Added | Note |
|---|---|---|---|
| CPU headroom | `SystemHealthManager.getCpuHeadroom(CpuHeadroomParams params)` → `float` in `[0, 100]`, `NaN` if unavailable | **36** | 0 = "no more cpu resources can be granted". Does ≥1 synchronous binder txn >1 ms — never call on a critical thread. [ref](https://developer.android.com/reference/android/os/health/SystemHealthManager) |
| GPU headroom | `SystemHealthManager.getGpuHeadroom(GpuHeadroomParams params)` → `float` in `[0, 100]`, `NaN` if unavailable; throws `UnsupportedOperationException` if unsupported | **36** | same page |
| Poll floors | `getCpuHeadroomMinIntervalMillis()`, `getGpuHeadroomMinIntervalMillis()` → `long` | **36** | Docs: calling more often "may return cached result" |
| On-device power rails | `SystemHealthManager.getSupportedPowerMonitors(Executor, OutcomeReceiver<List<PowerMonitor>, RuntimeException>)`, `getPowerMonitorReadings(List<PowerMonitor>, Executor, OutcomeReceiver<PowerMonitorReadings, RuntimeException>)` | **35** | `PowerMonitorReadings.getConsumedEnergy(PowerMonitor)` → energy **since boot in microwatt-seconds**; `PowerMonitor.POWER_MONITOR_TYPE_MEASUREMENT` = 0 is a real ODPM rail, `POWER_MONITOR_TYPE_CONSUMER` = 1 is a modelled consumer ("GPU", "MODEM"). Rail names are device-specific and meaningless across models. [ref](https://developer.android.com/reference/android/os/PowerMonitor) |

**Decision for Roadguard:** the *runtime-required* surface is API-29-only (`getCurrentThermalStatus` + executor listener) plus API-30 `getThermalHeadroom`. Everything newer (35 thresholds, 36 headroom listener, 36 CPU/GPU headroom, 35 power monitors) is a **progressive enhancement behind `Build.VERSION.SDK_INT` checks**, never a requirement. The API-35/36 pieces should be wired for the Edge and for our own bench diagnostics, and the API-35 `PowerMonitor` path in particular belongs in a debug-only build variant — `[INFERRED]` a 4 GB / low-end Unisoc part is very unlikely to expose ODPM rails, so it cannot be a product dependency.

### Recommended construction

```kotlin
// One executor for ALL thermal callbacks and polling. The docs warn that calling
// getThermalHeadroom from multiple threads makes call-rate control hard.
private val thermalExecutor = Executors.newSingleThreadScheduledExecutor { r ->
    Thread(r, "roadguard-thermal").apply { priority = Thread.NORM_PRIORITY - 1 }
}

private val statusListener = PowerManager.OnThermalStatusChangedListener { status ->
    // status is one of PowerManager.THERMAL_STATUS_*
    onPlatformStatus(status)
}

powerManager.addThermalStatusListener(thermalExecutor, statusListener) // API 29
// ... and in teardown:
powerManager.removeThermalStatusListener(statusListener)
```

`[DOCUMENTED]` The `Executor` overload's parameter doc: *"Callback and listener events are dispatched through this Executor, providing an easy way to control which thread is used."* Do not use the single-argument overload — its doc says the callback is *"enqueued tasks on the main thread"*, and we must not put thermal work on the UI thread of a recording app.

---

## 3. What each thermal status means — system side and app side

`[DOCUMENTED]` AOSP, [Thermal mitigation → *Use thermal status codes*](https://source.android.com/docs/core/power/thermal-mitigation). The AOSP wording is the authoritative statement of platform intent; the `PowerManager` reference wording is the terse app-facing version.

| Status (value) | AOSP: what the platform does | `PowerManager` reference wording | Roadguard action |
|---|---|---|---|
| `NONE` (0x0) | *"No throttling. Use this status to implement protective actions, such as detecting the start of the time period (t0 to t1) from THERMAL_STATUS_NONE (0) to THERMAL_STATUS_LIGHT (1)."* | "Not under throttling." | Full quality. Record `t0` timestamp — time-to-LIGHT is our best single field metric. |
| `LIGHT` (0x1) | *"Light throttling, UX isn't impacted. Use gentle device mitigation for this stage. For example, skip boosting or using inefficient frequencies, but only on big cores."* | "Light throttling where UX is not impacted." | Start polling headroom aggressively. Apply the free mitigations (§10 tier 1). Do **not** touch the encoder. |
| `MODERATE` (0x2) | *"Moderate throttling, UX isn't greatly impacted. Thermal mitigation impacts foreground activities, so apps should reduce power immediately."* | "Moderate throttling where UX is not largely impacted." | Apply §10 tiers 1–2 (display, map, overlay, GNSS). Queue an encoder step-down for the next segment boundary. |
| `SEVERE` (0x3) | *"Severe throttling; UX is largely impacted. In this stage, device thermal mitigation should limit the system capacity. This state might cause side effects, such as display jank and audio jitter."* | "Severe throttling where UX is largely impacted." | Apply tiers 1–3. Cut the current segment early and rebind at reduced resolution/fps. Expect dropped frames if we don't. |
| `CRITICAL` (0x4) | *"Platform has done everything to reduce power. The device thermal mitigation software has placed all components to run at their lowest capacity."* | "Platform has done everything to reduce power." | Minimum viable recording: lowest supported resolution/fps, screen off, map stopped, GNSS at lowest rate. Warn the user. |
| `EMERGENCY` (0x5) | *"Key components in the platform are shutting down due to thermal conditions and device functionality is limited. This status code represents the last warning before device shutdown. In this state, some functions, such as the modem and cellular data, are turned off completely."* | "Key components in platform are shutting down due to thermal condition. Device functionalities will be limited." | **Finalise and flush the current segment immediately** and stop recording cleanly. Never try to ride this out — the goal is a valid MP4 on disk, not one more minute of footage. |
| `SHUTDOWN` (0x6) | *"Shut down immediately. Due to the severity of this stage, apps might not be able to receive this notification."* | "Need shutdown immediately." | Same as EMERGENCY. Assume we will never see this callback — the AOSP note that apps "might not be able to receive this notification" means EMERGENCY handling must already have produced a valid file. |

Two design consequences:

1. **`EMERGENCY` is a "finalise now" trigger, not a "reduce quality" trigger.** `[INFERRED]` from the AOSP statement that `SHUTDOWN` may not be deliverable: any state machine that only escalates mitigation on EMERGENCY risks losing the in-flight segment to a hard power-off. Escalating to *clean stop* is the only reliability-preserving choice. This is a reasoning step, not a documented instruction.
2. **The platform's own mitigation is what causes our frame drops.** `[DOCUMENTED]` AOSP at SEVERE: *"device thermal mitigation should limit the system capacity … might cause side effects, such as display jank and audio jitter."* `[INFERRED]` For a camera+encoder pipeline, "limit the system capacity" means CPU/GPU/ISP clock caps, which manifest as encoder input-queue backpressure and dropped camera frames. Reaching SEVERE at all is already a recording-reliability failure, so the ladder must be *predictive* (headroom forecast), not *reactive* (status callback).

### What the thermal HAL actually reports (why "camera heat" is a real, named thing)

`[DOCUMENTED]` The AOSP `ThermalManagerService` shell command maps these `android.os.Temperature.TYPE_*` names: `UNKNOWN, CPU, GPU, BATTERY, SKIN, USB_PORT, POWER_AMPLIFIER, BCL_VOLTAGE, BCL_CURRENT, BCL_PERCENTAGE, NPU, TPU, DISPLAY, MODEM, SOC, WIFI, CAMERA, FLASHLIGHT, SPEAKER, AMBIENT, POGO` — read from [`ThermalManagerService.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/power/ThermalManagerService.java) (`runInjectTemperature()`).

`[DOCUMENTED]` AOSP also mandates: *"Anything that throttles device performance, including battery power constraints, must be reported through the thermal HAL."* — so charging-current throttling is, by policy, supposed to show up in the same status stream we're reading.

---

## 4. `getThermalHeadroom(int)` — documented contract *and* the AOSP implementation truth

### 4.1 The documented contract

`[DOCUMENTED]` verbatim from the [`PowerManager` reference](https://developer.android.com/reference/android/os/PowerManager):

- Signature: `public float getThermalHeadroom(int forecastSeconds)`, **added API 30**.
- *"Provides an estimate of how much thermal headroom the device currently has before hitting severe throttling."*
- *"this only attempts to track the headroom of slow-moving sensors, such as the skin temperature sensor. This means that there is no benefit to calling this function more frequently than about once per second, and attempts to call significantly more frequently may result in the function returning NaN."*
- *"the system does not attempt to forecast until it has multiple temperature samples from which to extrapolate. This should only take a few seconds from the time of the first call, but during this time, no forecasting will occur, and the current headroom will be returned regardless of the value of forecastSeconds."*
- Return: *"a non-negative float that represents how much of the thermal envelope is in use … A value of 1.0 indicates that the device is (or will be) throttled at THERMAL_STATUS_SEVERE … Values may exceed 1.0, but there is no implied mapping to specific thermal status levels beyond that point … A value of 0.0 corresponds to a fixed distance from 1.0, but does not correspond to any particular thermal status or temperature. Values on (0.0, 1.0] may be expected to scale linearly with temperature … Negative values will be clamped to 0.0 before returning."*
- `forecastSeconds`: **"Value is between 0 and 60 inclusive"**. Return annotation: **"Value is 0.0f or greater"**. *"Returns NaN if the device does not support this functionality."*

`[DOCUMENTED]` The NDK mirror ([`AThermal_getThermalHeadroom`](https://developer.android.com/ndk/reference/group/thermal)) states the same and adds: *"Returns NaN if … this function is called significantly faster than once per second."* The NDK entry point requires `AThermal_acquireManager()` (API 30) and must be released with `AThermal_releaseManager()`. **We have no reason to use the NDK path** — we are a Kotlin app, and the Java API is the same service.

### 4.2 The ADPF page's contradicting rate guidance

`[DOCUMENTED]` The [ADPF Thermal API page](https://developer.android.com/games/optimize/adpf/thermal) (last updated 2026-02-26) says something stricter than the reference: *"Don't call the GetThermalHeadroom() API too frequently. If you do so, the API returns NaN. You shouldn't call it more than once every 10 seconds."* and *"Avoid calling from multiple threads, it is harder to ensure the calling frequency and may cause the API returning NaN."*

So: reference says "~1 Hz is fine", ADPF says "≤ 0.1 Hz". These conflict.

### 4.3 What the AOSP implementation actually does — and why "every 10 s" is the wrong choice

Read directly from `ThermalManagerService.java` on three branches; **all of these constants are byte-identical on `android14-release`, `android15-release`, and `main`** `[DOCUMENTED]`:

| Constant | Value | Source |
|---|---|---|
| `MIN_FORECAST_SEC` | `0` | `ThermalManagerService` |
| `MAX_FORECAST_SEC` | `60` | ditto (matches the public "between 0 and 60 inclusive" annotation) |
| `TemperatureWatcher.RING_BUFFER_SIZE` | `30` | ditto |
| `TemperatureWatcher.MINIMUM_SAMPLE_COUNT` | `3` | ditto |
| `TemperatureWatcher.INACTIVITY_THRESHOLD_MILLIS` | `10000` | ditto |
| `TemperatureWatcher.DEGREES_BETWEEN_ZERO_AND_ONE` | `30.0f` | ditto |
| `DEFAULT_FORECAST_SECONDS` (API 36 callback) | `10` | `main` only |
| `HEADROOM_CALLBACK_MIN_INTERVAL_MILLIS` | `5000` | `main` only |
| `HEADROOM_CALLBACK_MIN_DIFFERENCE` | `0.03f` | `main` only |
| `HEADROOM_THRESHOLD_CALLBACK_MIN_DIFFERENCE` | `0.01f` | `main` only |

Branch URLs: [main](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/power/ThermalManagerService.java), and the same path on `refs/heads/android14-release` and `refs/heads/android15-release`.

**The headroom → temperature conversion is exact and linear.** `[DOCUMENTED]` `normalizeTemperature(temperature, severeThreshold)`:

```java
private static final float DEGREES_BETWEEN_ZERO_AND_ONE = 30.0f;

static float normalizeTemperature(float temperature, float severeThreshold) {
    float zeroNormalized = severeThreshold - DEGREES_BETWEEN_ZERO_AND_ONE;
    if (temperature <= zeroNormalized) return 0.0f;
    float delta = temperature - zeroNormalized;
    return delta / DEGREES_BETWEEN_ZERO_AND_ONE;
}
```

with the in-source comment *"headroom to temperature conversion: 3C every 0.1 headroom difference"*. Therefore:

- `headroom == 1.0` ⇔ skin temp == the HAL's **SEVERE hot-throttling threshold for a `TYPE_SKIN` sensor**.
- `headroom == 0.0` ⇔ skin temp ≤ (SEVERE threshold − **30 °C**).
- **Every 0.1 of headroom is exactly 3.0 °C of skin temperature.** This is enormously useful: it means a headroom *slope* is directly a °C/min figure, and it means the practical operating band is narrow. If a device's SEVERE skin threshold is, say, 48 °C, then headroom 0.85 is 43.5 °C and headroom 0.6 is 36 °C. `[INFERRED]` a windscreen-mounted phone in Australian summer can plausibly start a drive already at headroom 0.5–0.7 before we render a single frame — which is why the ladder must be able to run in a *permanently degraded* mode, not just transiently.

**The sampler is demand-driven and self-terminating.** `[DOCUMENTED]` `getAndUpdateTemperatureSamples()`:

```java
if (SystemClock.elapsedRealtime() - mLastForecastCallTimeMillis < mInactivityThresholdMillis) {
    mHandler.postDelayed(this::getAndUpdateTemperatureSamples, 1000);   // resample at 1 Hz
} else {
    mSamples.clear();       // ← 10 s with no getThermalHeadroom() call
    mCachedHeadrooms.clear();
    return;
}
```

`mLastForecastCallTimeMillis` is set inside `getForecast(...)`, i.e. by **our** `getThermalHeadroom()` call. Consequences:

- The service samples skin temperature at **1 Hz, only while an app is polling**. The ring buffer holds **30 samples ≈ 30 s of history**.
- If we poll at exactly the ADPF-recommended 10 s interval, we are sitting on the `INACTIVITY_THRESHOLD_MILLIS = 10000` boundary. Any scheduling jitter that pushes an interval past 10 000 ms **wipes the sample buffer**, and the next call falls back to a single fresh sample — below `MINIMUM_SAMPLE_COUNT = 3`, so *no forecast is produced* and `getThermalHeadroom(30)` silently degrades to `getThermalHeadroom(0)`.
- **`[INFERRED]` Recommendation: poll every 5 000 ms.** Chain: (a) the sampler needs a call within 10 000 ms to stay alive `[DOCUMENTED]`; (b) forecasts need ≥3 samples `[DOCUMENTED]`; (c) on `main` there is a per-`forecastSeconds` cache cleared once per 1 Hz tick `[DOCUMENTED]`, so sub-second calls are cheap-ish rather than fatal; (d) 5 s gives 2× margin against jitter while staying far above 1 Hz. 5 s also aligns with the API-36 callback's own `HEADROOM_CALLBACK_MIN_INTERVAL_MILLIS = 5000`.

**Where `NaN` really comes from.** `[DOCUMENTED]` In `getForecast(int)` on `android14-release` there are exactly two `return Float.NaN` paths, and *neither is rate-limiting*:

```java
if (mSamples.isEmpty())          { Slog.e(TAG, "No temperature samples found");    return Float.NaN; }
if (mSevereThresholds.isEmpty()) { Slog.e(TAG, "No temperature thresholds found"); return Float.NaN; }
```

and `mSevereThresholds` is populated **only** by `updateTemperatureThresholdLocked()` from `mHalWrapper.getTemperatureThresholds(true, Temperature.TYPE_SKIN)`, which bails out early if `threshold.hotThrottlingThresholds.length <= ThrottlingSeverity.SEVERE` or if `hotThrottlingThresholds[SEVERE]` is `NaN`.

**This is the key operational fact for Roadguard.** `[INFERRED]` from that code path: on Android 14, a permanent `NaN` from `getThermalHeadroom()` means *the OEM's thermal HAL does not expose a `TYPE_SKIN` sensor carrying a SEVERE hot-throttling threshold* — it is a **static device property, not a transient condition, and not a rate-limit symptom**. So:

- One `NaN` at startup is not proof of anything (the sample buffer may just be cold — `mSamples.isEmpty()` on the very first call triggers a synchronous sample fetch, but a HAL hiccup can still yield empty).
- **Probe policy:** call `getThermalHeadroom(0)` once per second for the first ~5 s of the recording session. If **every** call is `NaN`, mark headroom permanently unsupported for this device+boot and fall back to the §8 signal stack. Do not keep retrying every 5 s forever.
- `android14-release` has **no headroom cache at all** (`mCachedHeadrooms` does not exist on that branch — it was added later). So on the Moto G04, every call walks the full ring buffer and does a linear regression. `[INFERRED]` this is cheap (≤30 samples) but it is a binder round trip, which is why it belongs on our background executor and not on the camera or UI thread.

### 4.4 Forecast semantics we will actually use

`[DOCUMENTED]` ADPF: *"if `getThermalHeadroom(30)` returns 0.8, it indicates that in 30 seconds, the headroom is expected to reach 0.8, where there is 0.2 distance away from severe throttling, or 1.0."* The forecast is a **linear regression over the sample ring buffer** — `[DOCUMENTED]` the AOSP `getSlopeOf(List<Sample>)` javadoc: *"Calculates the trend using a linear regression. As the samples are degrees Celsius with associated timestamps in milliseconds, the slope is in degrees Celsius per millisecond."*

**Roadguard reads two values every 5 s cycle:**

| Call | Purpose |
|---|---|
| `getThermalHeadroom(0)` | Current state → drives the mitigation tier. |
| `getThermalHeadroom(30)` | 30 s look-ahead → drives *pre-emptive* escalation and, critically, the decision to cut the current segment early so an encoder change lands on a clean boundary. |

`[INFERRED]` 30 s is the right forecast horizon for us specifically because our segment length is 180 s: a 30 s warning is long enough to finalise the current MP4 and rebind CameraX (which takes hundreds of ms), and short enough that a linear extrapolation over a 30 s sample window is not being asked to predict beyond its own data. Asking for 60 s (the documented maximum) extrapolates 2× beyond the buffer length and the reference explicitly warns *"forecasts from further in the future will likely be less accurate"*.

---

## 5. `getThermalHeadroomThresholds()` (API 35) and headroom → status mapping

`[DOCUMENTED]` [`PowerManager.getThermalHeadroomThresholds()`](https://developer.android.com/reference/android/os/PowerManager), **added API 35**, `public Map<Integer, Float> getThermalHeadroomThresholds()`:

- *"Gets the thermal headroom thresholds for all available thermal throttling status above THERMAL_STATUS_NONE."*
- *"A thermal status key in the returned map is only set if the device manufacturer has the corresponding threshold defined for at least one of its sensors."*
- Worked example from the doc: *"if the headroom threshold for THERMAL_STATUS_LIGHT is 0.7, and a headroom prediction in 10s returns 0.75 (or getThermalHeadroom(10)=0.75), one can expect that in 10 seconds the system could be in lightly throttled state if the workload remains the same."*
- Multi-sensor caveat: *"for older devices with multiple sensors reporting different threshold values, the minimum threshold is taken to be conservative on predictions."* Directionality guarantee: *"it's always guaranteed that the device won't be throttled heavier than the unmet threshold's state, so a real-time headroom of 0.75 will never come with THERMAL_STATUS_MODERATE but lower, and 0.65 will never come with THERMAL_STATUS_LIGHT but THERMAL_STATUS_NONE."*
- Throws **`IllegalStateException`** *"if the thermal service is not ready"* and **`UnsupportedOperationException`** *"if the feature is not enabled"*. Confirmed in AOSP `ThermalManagerService.getThermalHeadroomThresholds()`: `IllegalStateException("Thermal HAL connection is not initialized")` when `!mHalReady`, `UnsupportedOperationException("Thermal headroom thresholds not enabled")` when the `allowThermalHeadroomThresholds` feature flag is off `[DOCUMENTED]`.
- Mutability: *"Starting at `Build.VERSION_CODES.BAKLAVA` the returned map of thresholds can change between calls to this function"* — i.e. **API 36+ must re-read, and may register `addThermalHeadroomListener`**. Before 36, AOSP caches on first query.
- AOSP hardcodes `mHeadroomThresholds[SEVERE] = 1.0f` `[DOCUMENTED]`, consistent with the headroom definition.

**Both exceptions must be caught.** `[INFERRED]` `IllegalStateException` is explicitly a *"not ready"* condition, so it is retryable; `UnsupportedOperationException` is a build-flag condition, so it is permanent for this boot. Treat them differently or we will either crash a recording or hammer a dead API.

### The mapping ladder Roadguard will use

```kotlin
/** Headroom value at which we consider ourselves at each throttling tier. */
data class HeadroomLadder(val light: Float, val moderate: Float, val severe: Float) {
    companion object {
        /** Google's own published heuristic for devices without threshold data. */
        val FALLBACK = HeadroomLadder(light = 0.85f, moderate = 0.95f, severe = 1.00f)
    }
}

fun readLadder(pm: PowerManager): HeadroomLadder =
    if (Build.VERSION.SDK_INT >= 35) {
        try {
            val m = pm.thermalHeadroomThresholds   // Map<Integer, Float>
            HeadroomLadder(
                light    = m[PowerManager.THERMAL_STATUS_LIGHT]    ?: HeadroomLadder.FALLBACK.light,
                moderate = m[PowerManager.THERMAL_STATUS_MODERATE] ?: HeadroomLadder.FALLBACK.moderate,
                severe   = m[PowerManager.THERMAL_STATUS_SEVERE]   ?: HeadroomLadder.FALLBACK.severe,
            )
        } catch (e: UnsupportedOperationException) { HeadroomLadder.FALLBACK }  // permanent
          catch (e: IllegalStateException)        { HeadroomLadder.FALLBACK }  // retry later
    } else HeadroomLadder.FALLBACK
```

The 0.85 / 0.95 / 1.00 fallback numbers are `[DOCUMENTED]` — they are Google's published pseudocode on the [ADPF Thermal API page](https://developer.android.com/games/optimize/adpf/thermal), under *Device limitations of the Thermal API*:

> *"If `getThermalHeadroom()` returns a value of > 1.0, the status could actually be `THERMAL_STATUS_SEVERE` or higher, reduce workload immediately … If `getThermalHeadroom()` returns a value of 0.95, the status could actually be `THERMAL_STATUS_MODERATE` or higher … If `getThermalHeadroom()` returns a value of 0.85, the status could actually be `THERMAL_STATUS_LIGHT`, keep the watchout and reduce workload if possible."*

**Do not hardcode any absolute °C.** `[DOCUMENTED]` ADPF: *"The same thermalHeadroom value may be mapped to a certain thermalStatus on one device model but a different thermalStatus on another device."* And `[DOCUMENTED]` AOSP thermal-mitigation explicitly permits OEMs to report fictional temperatures: *"The temperature value returned from a sensor reading doesn't have to be the actual temperature, as long as it accurately reflects the corresponding severity threshold … you might decide to return 72°C as your critical temperature threshold, when the actual temperature is 65°C."* Headroom is the only portable currency.

### API 36 headroom listener

`[DOCUMENTED]` `PowerManager.OnThermalHeadroomChangedListener.onThermalHeadroomChanged(float headroom, float forecastHeadroom, int forecastSeconds, Map<Integer, Float> thresholds)`, added API 36. Its own doc is unusually explicit that it is **not** a replacement for polling:

> *"This may not be used to fully replace the PowerManager.getThermalHeadroom(int) API as it will only notify on one of the conditions below … 1. thermal throttling events: when the skin temperature has cross any of the thresholds … 2. skin temperature threshold change events … So periodically polling against PowerManager.getThermalHeadroom(int) API should still be used to actively monitor temperature forecast in advance."*

Also: *"By API version 36, it provides a forecast in the same call for developer's convenience based on a forecastSeconds defined by the device, which can be static or dynamic varied by OEM."* — AOSP `main` uses `DEFAULT_FORECAST_SECONDS = 10` `[DOCUMENTED]`, so `forecastSeconds` will typically arrive as 10 and **must be read from the parameter, not assumed**.

**Decision:** on API ≥ 36, register the headroom listener *in addition to* the 5 s poll, and use it only to (a) refresh the threshold ladder, and (b) trigger an immediate out-of-cycle evaluation. Never replace the poll with it.

---

## 6. Android Dynamic Performance Framework — `PerformanceHintManager`. Is it useful to us?

`[DOCUMENTED]` API surface, [`PerformanceHintManager`](https://developer.android.com/reference/android/os/PerformanceHintManager) and [`PerformanceHintManager.Session`](https://developer.android.com/reference/android/os/PerformanceHintManager.Session):

| Member | Signature | Added |
|---|---|---|
| Manager | `PerformanceHintManager.Session createHintSession(int[] tids, long initialTargetWorkDurationNanos)` | 31 |
| Manager | `long getPreferredUpdateRateNanos()` | 31 |
| Session | `void reportActualWorkDuration(long actualDurationNanos)` | 31 |
| Session | `void updateTargetWorkDuration(long targetDurationNanos)` | 31 |
| Session | `void close()` (implements `Closeable`) | 31 |
| Session | `void setThreads(int[] tids)` | 34 |
| Session | `void reportActualWorkDuration(WorkDuration workDuration)` | 35 |
| Session | `void setPreferPowerEfficiency(boolean enabled)` | **35** |

Documented semantics `[DOCUMENTED]`:

- *"A Session represents a group of threads with an inter-related workload such that hints for their performance should be considered as a unit. The threads in a given session should be long-lived and not created or destroyed dynamically."*
- *"The work duration API can be used with periodic workloads to dynamically adjust thread performance and keep the work on schedule while optimizing the available power budget."*
- *"The system will attempt to adjust the core placement of the threads within the thread group and/or the frequency of the core on which they are run to bring the actual duration close to the target duration."*
- *"All timings should be in `SystemClock.uptimeNanos()`."*
- *"Any call in this class will change its internal data, so you must do your own thread safety to protect from racing."*
- `setThreads(int[] tids)`: threads *"must be part of this app's thread group"*; throws `SecurityException` *"if any thread id doesn't belong to the application"* and `IllegalStateException` *"if the hint session is not in the foreground"*.
- `setPreferPowerEfficiency(boolean)`: *"This tells the session that these threads can be safely scheduled to prefer power efficiency over performance."*

**Honest assessment — it is aimed at games, and it cannot help the part of our workload that produces most of the heat.**

`[DOCUMENTED]` The framing is explicitly game-first. The [ADPF overview](https://developer.android.com/games/optimize/adpf) says *"The focus is on games, but you can also use the features for other performance-intensive apps."* The Thermal API page's entire mitigation vocabulary is *"the number of worker threads, worker-thread affinity for big and small cores, GPU fidelity options, and framebuffer resolutions"* and it routes readers to Unity Quality Settings / Unreal Scalability Settings.

`[INFERRED]` — reasoning chain, please check it:
1. A hint session only governs TIDs *"part of this app's thread group"* `[DOCUMENTED]`.
2. Camera sensor readout, ISP processing, and hardware video encoding run in the vendor camera provider process and in fixed-function hardware blocks, **not** in our thread group. (Supported by the platform architecture and by the existence of separate `TYPE_CAMERA` thermal zones `[DOCUMENTED]`.)
3. Therefore `PerformanceHintManager` **cannot** raise or lower the power drawn by the camera or the encoder. It is not a thermal lever for our dominant heat source.
4. It *can* govern our own CPU threads: the map render/tile-decode thread, the Compose overlay, GNSS/track processing, MP4 muxing bookkeeping.
5. Since our own CPU work is a real but secondary heat contributor (§9), and since these threads are periodic (per-frame map render), a hint session is *legitimately applicable* — just to a minority of the heat.

**Roadguard decision.** Do **not** build a hint session in v1. Justification: `createHintSession` requires accurate per-cycle `reportActualWorkDuration()` on a long-lived periodic thread; getting that wrong biases the governor *upward* (it will boost clocks to hit a target we mis-declared), which is the opposite of what a thermally-constrained dashcam wants. The one piece worth taking early is the **inverse** call: on API ≥ 35, if we do create a session for the map render thread, call `setPreferPowerEfficiency(true)` whenever the thermal tier is ≥ LIGHT and `false` at NONE. That is a low-risk, documented "please put me on little cores" request. `[UNVERIFIED]` whether Unisoc T606 / Dimensity 7400 implement the power-efficiency hint meaningfully at all — the API is a hint with no documented guarantee.

**Do not declare Roadguard as a game to get Game Mode interventions.** `[DOCUMENTED]` `GameManager` (API 31) returns `GAME_MODE_UNSUPPORTED` for non-games, and app-category-based Game Mode interventions are described as *"game specific optimizations set by original equipment manufacturers (OEMs) to improve the performance of games that are no longer being updated by developers"* ([Game Mode API](https://developer.android.com/games/optimize/adpf/gamemode/about-API-and-interventions)). Mis-declaring `android:appCategory="game"` to grab an OEM FPS-throttling intervention would be an abuse that also mis-categorises us in Play and in battery attribution UI.

---

## 7. Reliability of thermal signals on low-end and OEM devices

### 7.1 `getCurrentThermalStatus()` lies on some devices — documented by Google

`[DOCUMENTED]` [ADPF Thermal API](https://developer.android.com/games/optimize/adpf/thermal):

> *"Some devices might not fully support this technology yet and return `THERMAL_STATUS_NONE` regardless of the actual value of the thermal headroom and throttling state. For this reason we recommend using `getThermalHeadroom` instead."*

and, as a cross-check heuristic:

> *"If `GetThermalHeadroom()` returns a high value (e.g: 0.85 or more) and `GetCurrentThermalStatus()` still returns `THERMAL_STATUS_NONE`, the status is likely not updated. Use heuristics to estimate the correct thermal throttling status or just use `getThermalHeadroom()` without `getCurrentThermalStatus()`."*

`[INFERRED]` Roadguard should implement exactly that consistency check and log a one-shot device-capability verdict (`STATUS_TRUSTED` / `STATUS_STUCK_AT_NONE`) into our local diagnostics. On the Moto G04 in particular I would **assume `getCurrentThermalStatus()` is untrustworthy until proven otherwise** — it is a sub-$150 Unisoc device, exactly the class Google is warning about. `[UNVERIFIED]` whether the G04's status actually updates; this is a one-line on-device test (§13).

### 7.2 `getThermalHeadroom()` returning `NaN` — what to do

Decision table:

| Observation | Meaning | Action |
|---|---|---|
| First call is `NaN`, later calls return a float | Cold sample buffer / HAL just came up | Normal. Continue. |
| **All** calls `NaN` for the first 5 s (5 attempts at 1 Hz) | `[INFERRED]` No `TYPE_SKIN` sensor with a SEVERE hot threshold in this device's thermal HAL → permanent for this boot (from the AOSP `mSevereThresholds.isEmpty()` path) | Set `headroomSupported = false`. Stop calling. Fall to §7.3 stack. Surface a quiet "reduced thermal protection on this device" note in settings/diagnostics. |
| Returns exactly `0.0f` on the first call | `[DOCUMENTED]` ADPF: *"If the initial value of GetThermalHeadroom() is NaN, the API is not available on the device"* and their `isAPISupported()` pseudocode treats **`0` OR `NaN`** as unsupported | Treat first-call `0.0f` as suspicious, not conclusive: re-probe 3× at 1 Hz. `[INFERRED]` a genuinely cold device *can* legitimately read 0.0 (per §4.3, that means skin temp is ≥30 °C below the SEVERE threshold, plausible on a cold morning), so Google's `== 0 → unsupported` shortcut would wrongly disable protection on a cold start. Prefer: unsupported only if it never leaves 0.0 while headroom-relevant work is running. |
| Sporadic `NaN` interleaved with floats | `[UNVERIFIED]` — either a HAL read failure or the documented rate-limit behaviour | Hold last good value, do not escalate or de-escalate on a `NaN`. Count `NaN` rate as a health metric. |

Implementation rule: **`NaN` must never be compared numerically.** In Kotlin, `Float.NaN > 0.85f` is `false`, so a naive `if (headroom > ladder.light)` silently means "everything is fine" on an unsupported device. Use `headroom.isNaN()` as an explicit early branch.

### 7.3 Fallback signals — what an ordinary app can *actually* read on Android 14

| Source | Can a normal app use it on Android 14? | Evidence |
|---|---|---|
| `PowerManager.getCurrentThermalStatus()` / `addThermalStatusListener()` | **Yes.** No permission. | `[DOCUMENTED]` API 29, no `@RequiresPermission` on the reference page |
| `PowerManager.getThermalHeadroom(int)` | **Yes.** No permission. | `[DOCUMENTED]` API 30 |
| `Intent.ACTION_BATTERY_CHANGED` + `BatteryManager.EXTRA_TEMPERATURE` | **Yes.** Sticky broadcast; register a receiver with a `null` receiver to read it synchronously, or a real receiver for updates. No permission. | `[DOCUMENTED]` `EXTRA_TEMPERATURE` = `"temperature"`, API 5 |
| `BatteryManager.getIntProperty(BATTERY_PROPERTY_*)` | **Yes.** No permission. | `[DOCUMENTED]` API 21 |
| `HardwarePropertiesManager.getDeviceTemperatures(type, source)` | **NO.** | `[DOCUMENTED]` *"Throws SecurityException if something other than the device owner or the current VR service tries to retrieve information provided by this service."* Same for `getCpuUsages()` and `getFanSpeeds()`. [ref](https://developer.android.com/reference/android/os/HardwarePropertiesManager) |
| Reading `/sys/class/thermal/thermal_zone*/temp` | **NO.** | `[DOCUMENTED]` AOSP SELinux policy — see below |
| Reading `/sys/class/power_supply/*` | **NO.** | `[DOCUMENTED]` same policy analysis |
| `SystemHealthManager.getPowerMonitorReadings(...)` | API ≥ 35 only, and only if the device exposes rails | `[DOCUMENTED]` API 35 |

**The sysfs question, settled with primary evidence.** `[DOCUMENTED]` From AOSP `system/sepolicy`:

- `public/file.te` line 171: `type sysfs_thermal, sysfs_type, fs_type;` — thermal sysfs has its own dedicated label. (Line 104: `type sysfs_batteryinfo, fs_type, sysfs_type;`.)
- `private/untrusted_app_all.te` grants third-party apps **exactly two** sysfs rules, lines 116–117:
  ```
  allow untrusted_app_all sysfs_hwrandom:dir search;
  allow untrusted_app_all sysfs_hwrandom:file r_file_perms;
  ```
  There is **no** `sysfs_thermal` or `sysfs_batteryinfo` allow rule.
- `private/app_neverallows.te` lines 117–124:
  ```
  # Do not allow any write access to files in /sys
  neverallow all_untrusted_apps sysfs_type:file { no_w_file_perms no_x_file_perms };

  # Apps may never access the default sysfs label.
  neverallow all_untrusted_apps sysfs:file no_rw_file_perms;
  ```

Sources: [`public/file.te`](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/public/file.te), [`private/untrusted_app_all.te`](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/untrusted_app_all.te), [`private/app_neverallows.te`](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/app_neverallows.te).

`[INFERRED]` Conclusion: `/sys/class/thermal` is unreadable by `untrusted_app` on any policy-compliant Android — writes are `neverallow`ed outright, and reads are simply not granted (SELinux default-denies). **Do not write sysfs-scraping code, not even as a fallback, not even in a try/catch.** Any success on a specific device would be a non-compliant vendor policy we must not depend on, and the attempt generates SELinux audit noise. This matches the reported denial signature `avc: denied { search } … tcontext=u:object_r:sysfs_thermal:s0 tclass=dir` circulating on the android-platform list (`[UNVERIFIED]` as to that specific log, but it is consistent with the policy above).

**Battery temperature — precise units, with a documentation caveat.**

- `[DOCUMENTED]` `BatteryManager.EXTRA_TEMPERATURE` = `"temperature"` (API 5). Its public javadoc says only *"integer containing the current battery temperature"* — **the units are NOT documented in the public API reference.** Do not claim otherwise in code comments.
- `[DOCUMENTED]` The underlying HAL field is unambiguous. `hardware/interfaces/health/aidl/android/hardware/health/HealthInfo.aidl`: *"Instantaneous battery temperature in tenths of degrees Celsius"* → `int batteryTemperatureTenthsCelsius;` ([source](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/health/aidl/android/hardware/health/HealthInfo.aidl)).
- `[INFERRED]` Therefore `EXTRA_TEMPERATURE / 10.0f` is degrees Celsius, sourced from the HAL field above. Chain: BatteryService populates the broadcast from `HealthInfo`; the HAL field is documented as tenths of °C. Reasonable and near-certain, but formally an inference because the public doc omits the unit.

```kotlin
// Sticky broadcast: passing null as the receiver returns the last Intent immediately.
val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
val tenthsC = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
val batteryC: Float? = tenthsC
    ?.takeIf { it != Int.MIN_VALUE }
    ?.let { it / 10.0f }
    ?.takeIf { it > -30f && it < 100f }   // sanity-gate obviously bogus HAL values
```

Other useful extras from the same broadcast `[DOCUMENTED]`: `EXTRA_PLUGGED` (`"plugged"`, API 5; 0 = on battery, else `BATTERY_PLUGGED_AC` = 1, `BATTERY_PLUGGED_USB` = 2, `BATTERY_PLUGGED_WIRELESS` = 4, `BATTERY_PLUGGED_DOCK` = 8 (API 33)), `EXTRA_STATUS` (`"status"`), `EXTRA_HEALTH` (`"health"`, values include `BATTERY_HEALTH_OVERHEAT`), `EXTRA_VOLTAGE`, `EXTRA_LEVEL`/`EXTRA_SCALE`, plus `EXTRA_CHARGING_STATUS` and `EXTRA_CYCLE_COUNT` (both API **34**) and `EXTRA_CAPACITY_LEVEL` (API **36**).

**Battery current for charge/discharge sensing** `[DOCUMENTED]`, all `BatteryManager` API 21 unless noted:

| Property | Constant value | Documented meaning |
|---|---|---|
| `BATTERY_PROPERTY_CURRENT_NOW` | 2 | *"Instantaneous battery current in microamperes … Positive values indicate net current entering the battery from a charge source, negative values indicate net current discharging"* |
| `BATTERY_PROPERTY_CURRENT_AVERAGE` | 3 | *"Average battery current in microamperes … The time period over which the average is computed may depend on the fuel gauge hardware and its configuration."* |
| `BATTERY_PROPERTY_CHARGE_COUNTER` | 1 | *"Battery capacity in microampere-hours"* |
| `BATTERY_PROPERTY_ENERGY_COUNTER` | 5 | *"Battery remaining energy in nanowatt-hours"* (long) |
| `BATTERY_PROPERTY_CAPACITY` | 4 | integer percentage |
| `BATTERY_PROPERTY_STATUS` | 6 (API 26) | a `BATTERY_STATUS_*` value |

`[DOCUMENTED]` `getIntProperty(int)` returns *"`Integer.MIN_VALUE` if targetSdkVersion >= VERSION_CODES.P"* when unsupported; `getLongProperty(int)` returns `Long.MIN_VALUE`. We target API 34+, so **always check for `Int.MIN_VALUE`**.

`[UNVERIFIED]` The documented sign convention for `CURRENT_NOW` is widely reported to be inverted on some OEM fuel-gauge integrations. Do not build logic on the *sign*; build it on `EXTRA_PLUGGED != 0` for "is charging", and use `abs(CURRENT_NOW)` as a magnitude-only heat proxy. Settle this with the test in §14.

### 7.4 The composite fallback estimator (used only when headroom is `NaN`)

`[INFERRED]` design — this is engineering, not documentation:

```
if headroom is supported:  tier = ladder(headroom(0), headroom(30))
else:
   tier = max(
     tierFromPlatformStatus(getCurrentThermalStatus()),   // may be stuck at NONE
     tierFromBatteryTemp(batteryC),                       // device-calibrated, see below
     tierFromElapsedRecordingTime(minutes)                // last-resort open-loop guard
   )
```

- `tierFromBatteryTemp` **cannot** use portable absolute thresholds. `[INFERRED]` Battery temperature is not skin temperature — it lags, sits behind different thermal mass, and rises sharply while charging even when the SoC is cool. So this must be **relative**: baseline the battery temperature over the first 60 s of a session, then escalate on ΔT and on dT/dt, with device-specific constants recorded from the on-device tests in §14. Absolute battery-temperature limits like "escalate at 45 °C" are guesses and are explicitly *not* recommended here.
- `tierFromElapsedRecordingTime` is a pure open-loop safety net for the worst case (headroom `NaN` **and** status stuck at NONE **and** battery temp unavailable): step down one tier at fixed elapsed-time milestones. `[INFERRED]` this is deliberately crude; it exists so that "no thermal telemetry at all" degrades to "records reliably at lower quality" rather than "records at full quality until the device shuts down".

---

## 8. What actually generates heat during dashcam operation — ranked

**Method.** There is no Android document that ranks heat sources for a dashcam workload. The closest authoritative quantitative anchor is AOSP's [power_profile.xml *Measure power values*](https://source.android.com/docs/core/power/values) table, which gives **example** current draws that OEMs are expected to replace with measured values for their own hardware. AOSP states *"power profile values are given in current (amps)"* and each row's number is in an explicit **"Example value"** column. So these are **illustrative reference-device figures, not measurements of the Moto G04 or Edge 60 Fusion**, and I use them only to establish *order of magnitude and ranking*, never as our devices' numbers.

Relevant rows, verbatim `[DOCUMENTED]`:

| `power_profile.xml` key | Documented description | Example value |
|---|---|---|
| `camera.avg` | *"Average power use by the camera subsystem for a typical camera app"* — note: *"Intended as a rough estimate for an app running a preview and capturing approximately 10 full-resolution pictures per minute."* | **600 mA** |
| `screen.on` | *"Additional power used when screen is turned on at minimum brightness."* Includes touch controller and display backlight. | **200 mA** |
| `screen.full` | *"Additional power used when screen is at maximum brightness, compared to screen at minimum brightness."* *"A fraction of this value (based on screen brightness) is added to the screen.on value"* | **100–300 mA** |
| `ambient.on` | *"Additional power used when screen is in doze/ambient/always-on mode instead of off."* | ~100 mA |
| `cpu.active` | *"Additional power used by CPUs when running at different speeds."* | 100, 120, 140, 160, 200 mA (per cluster, per speed) |
| `cpu.awake` | *"Additional power used when CPUs are in scheduling idle state … system isn't in system suspend state."* | 50 mA |
| `cpu.idle` | *"Total power drawn by the system when CPUs (and the SoC) are in system suspend state."* | 3 mA |
| `radio.active` | *"Additional power used when cellular radio is transmitting/receiving."* | **100–300 mA** |
| `radio.scanning` | *"Additional power used when cellular radio is paging the tower."* | 1.2 mA |
| `gps.on` | *"Additional power used when GPS is acquiring a signal."* | **50 mA** |
| `gps.signalqualitybased` | *"Additional power used by GPS based on signal strength … one per signal strength, from weakest to strongest."* | 30 mA, 10 mA |
| `wifi.on` / `wifi.active` / `wifi.scan` | Wi-Fi idle / TX-RX / scanning | 2 / 31 / **100** mA |
| `video` | *"Additional power used when video **decoding** via DSP."* | ~50 mA |
| `audio` | *"Additional power used when audio decoding/encoding via DSP."* | ~10 mA |
| `camera.flashlight` | flash module on | 200 mA |
| `battery.capacity` | total battery capacity | 3000 mAh |

**Critical gap in that table:** there is **no `video.encode` key**. `[DOCUMENTED]` the only `video` row is described as *decoding*. So AOSP's power model does not separately attribute hardware video **encode**. `[INFERRED]` encode power is therefore folded into `camera.avg` and/or the CPU/SoC rails in AOSP's model, which means the 600 mA `camera.avg` figure — itself defined for *"a preview and ~10 stills per minute"*, a far lighter load than continuous 1080p30 encode — **understates** our actual camera-path draw. Treat camera+ISP+encoder as one inseparable block for ranking purposes.

### The ranking

| # | Heat source | Why it ranks here | Evidence class |
|---|---|---|---|
| **1** | **Camera sensor + ISP + hardware video encoder (as one block)** | Largest single documented subsystem draw (`camera.avg` 600 mA example) and it runs 100% of the time we are recording, with no duty cycle. The example figure is for a *lighter* load than ours. It also has its own dedicated thermal zone type (`Temperature.TYPE_CAMERA`) in the thermal HAL, i.e. OEMs specifically instrument it. | `[DOCUMENTED]` magnitude + zone type; `[INFERRED]` that continuous encode exceeds the example |
| **2** | **Display** | `screen.on` 200 mA at *minimum* brightness plus up to 300 mA more at maximum, and a dashcam runs with the screen on and bright (windscreen, daylight). Combined worst case (~500 mA example) is comparable to the whole camera block. Sunlight readability forces high brightness precisely when ambient heat is worst. | `[DOCUMENTED]` figures; `[INFERRED]` that a windscreen use case pins brightness high |
| **3** | **Physical situation: windscreen mount in direct sun** | Not an electrical load at all — it raises the *ambient* term, i.e. it lowers the temperature delta available for dissipation and directly shifts our starting headroom. Per §4.3, 0.1 headroom = 3 °C, so a 15 °C cabin-vs-room difference is ~0.5 headroom consumed before we render a frame. Also: a phone against glass has one convective face blocked. | `[INFERRED]` from the documented 3 °C-per-0.1 conversion + basic heat transfer. Not in any Android doc. |
| **4** | **Charging, especially fast charging** | 68 W on the Edge vs 15 W on the G04 (`[DOCUMENTED]` Motorola). Charge inefficiency is dissipated as heat inside the phone, right next to the battery, and AOSP explicitly says *"Anything that throttles device performance, **including battery power constraints**, must be reported through the thermal HAL"* — i.e. the platform treats charge-related limits as thermal events. And we have **zero control** (§11). | `[DOCUMENTED]` wattages + AOSP statement; `[INFERRED]` the magnitude of the resulting heat |
| **5** | **GPU: map rendering + Compose overlay compositing** | The G04 has a **single** Mali-G57 shader core `[DOCUMENTED]`. Continuous vector-map redraw plus an alpha-blended overlay plus preview surface composition contends for it. AOSP's power profile has GPU rails but publishes no example value, so no magnitude anchor. Ranks below display because it is *fully under our control* and can be reduced to near zero (§10). | `[DOCUMENTED]` core count; `[INFERRED]` contention and ranking |
| **6** | **CPU: our own app work (Compose recomposition, track processing, MP4 muxing, tile decode)** | `cpu.active` example is 100–200 mA per cluster/speed vs `cpu.awake` 50 mA `[DOCUMENTED]`. Real but an order below the camera block — unless we do something pathological like per-frame bitmap work or a busy `ImageAnalysis`. | `[DOCUMENTED]` figures; `[INFERRED]` ranking |
| **7** | **GNSS** | `gps.on` example 50 mA `[DOCUMENTED]`. Meaningful for battery over hours, small as a heat source, and we genuinely need position continuously. Lowest-value target on the ladder despite being easy to reduce. | `[DOCUMENTED]` |
| **8** | **Cellular / Wi-Fi radios** | `radio.active` 100–300 mA is *large*, but Roadguard is offline-first: no uploads, no telemetry, no analytics. `radio.scanning` is only 1.2 mA. So in steady state this is near zero **for us**, though the *user's* other apps and a poor-signal cell search are outside our control. | `[DOCUMENTED]` figures |
| **9** | **On-device ML / image processing** (if ever added) | Not present in v1. Would land at #1–2 if added — it stacks an NPU/GPU/CPU pass on top of an already-saturated camera path. There is a `Temperature.TYPE_NPU` zone `[DOCUMENTED]`. | `[INFERRED]` |
| — | **Flashlight / torch** | 200 mA example `[DOCUMENTED]`. Must be **permanently off** in a dashcam: it is useless through a windscreen (reflects straight back) and pure heat. |  |

### The one CameraX-specific heat trap, documented

`[DOCUMENTED]` [CameraX architecture](https://developer.android.com/media/camera/camerax/architecture):

> *"On devices with camera hardware level `FULL` or lower, combining `Preview`, `VideoCapture`, and either `ImageCapture` or `ImageAnalysis` may force CameraX to duplicate the camera's `PRIV` stream for `Preview` and `VideoCapture`. This duplication, called stream sharing, enables the simultaneous use of these features but comes at the cost of increased processing demands. You might experience slightly higher latency and reduced battery life as a result."*

`[INFERRED]` The Moto G04 is almost certainly `INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED` (a 16 MP single-camera budget device) — `[UNVERIFIED]`, must be read from `CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL` on-device. If so, **binding `ImageAnalysis` alongside `Preview` + `VideoCapture` triggers stream sharing and pays a documented battery/latency penalty on our worst device.** Hard rule for Roadguard: **`Preview` + `VideoCapture` only.** Any feature that wants frames (motion detect, ML, parking-mode trigger) must be redesigned to not need a third stream, or must accept that it cannot run concurrently with recording on the baseline device.

`[DOCUMENTED]` Also relevant: [Camera2 capture sessions](https://developer.android.com/media/camera/camera2/capture-sessions-requests) documents the stream use case **`VIDEO_CALL`** as *"Recommended for long-running camera uses where power drain is a concern."* That is the closest thing to official "dashcam" guidance that exists. Stream use cases were introduced in **Android 13 / API 33** (`Build.VERSION_CODES.TIRAMISU`), so they are available on our entire range. Exposed in CameraX as `Camera2Interop.Extender.setStreamUseCase(long)`, `@RequiresApi(33)` `[DOCUMENTED]` — with the doc's own warning: *"No app should call this without double-checking the supported list first, or at least `REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE` capability."* Check `CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES` first. `[UNVERIFIED]` what quality/resolution penalty `VIDEO_CALL` imposes — it is a real candidate for the deepest mitigation tier, but only after measuring what it does to our recorded video (§14).

---

## 9. Mitigation menu, cheapest-first — and what requires restarting the recording

**Reliability rule that shapes this whole table:** recording continuity is priority #1. So the ladder is ordered so that **every step that does not require rebinding CameraX comes first**, and every step that *does* require rebinding is deferred to a segment boundary (which we already produce every 180 s).

### 9.1 The ladder

| Tier | Action | Expected thermal effect | Cost to the product | Restart recording? |
|---|---|---|---|---|
| **1a** | Drop screen brightness to a floor (e.g. 0.15) via `WindowManager.LayoutParams.screenBrightness` | Up to most of the `screen.full` term (example 100–300 mA) | Poor sunlight readability | **No** |
| **1b** | Cap window refresh rate: `WindowManager.LayoutParams.preferredRefreshRate = 60f` (or 30f) | Reduces display + GPU + compositor work; 90→60 Hz on G04, 120→60 Hz on Edge | Slightly less smooth UI | **No** |
| **1c** | Stop map animation; redraw only on meaningful position change | Removes most of the GPU term (#5) | Map looks "steppy" | **No** |
| **1d** | Drop map render rate to 1 Hz / 0.5 Hz | Nearly eliminates GPU term | Map lags reality | **No** |
| **1e** | Remove the overlay GPU pass — bake speed/time text into a single small `Text` layer, drop blur/shadow/alpha layers, avoid `Modifier.graphicsLayer` compositing | Removes an extra full-screen alpha blend per frame | Plainer overlay | **No** |
| **2a** | **Turn the screen off entirely** — clear `FLAG_KEEP_SCREEN_ON` and let the display time out; keep recording in the foreground service | Removes `screen.on` (200 mA example) **and** `screen.full` **and** all map/overlay GPU/CPU work at once. Largest single controllable win. | User can't see preview or map | **No** — *but see §9.3, this is the one that needs care* |
| **2b** | Detach the `Preview` use case (stop the preview surface) while keeping `VideoCapture` bound | Removes one camera output stream; on `LIMITED` hardware may also remove a stream-sharing duplication | No live view | **Yes if done by rebinding** — see §9.2 |
| **2c** | GNSS: raise `LocationRequest.Builder.setIntervalMillis()` (e.g. 1 s → 3 s) and add `setMaxUpdateDelayMillis()` batching | Small (`gps.on` 50 mA example) | Coarser track | **No** |
| **3a** | Lower encoder bitrate: `Recorder.Builder.setTargetVideoEncodingBitRate(int)` | Reduces encoder + storage-write work | Lower video quality | **YES — new `Recorder`, rebind, new segment** |
| **3b** | Lower resolution: `Recorder.Builder.setQualitySelector(QualitySelector.from(Quality.HD))` (1080p → 720p) | Large — cuts ISP + encode + write pixel rate ~2.25× | Lower detail; plate legibility risk | **YES** |
| **3c** | Lower frame rate: `VideoCapture.Builder.setTargetFrameRate(Range(24,24))` etc. | Proportional to fps reduction across ISP + encode | Choppier footage | **YES** (builder option) — *runtime alternative in §9.2* |
| **3d** | Disable video stabilisation: `VideoCapture.Builder.setVideoStabilizationEnabled(false)` | Removes an EIS pass (ISP/GPU) | Shakier footage | **YES** |
| **3e** | Switch stream use case to `VIDEO_CALL` via `Camera2Interop.Extender.setStreamUseCase(...)` (`@RequiresApi(33)`) | Documented as the low-power long-running option | Unknown quality cost — measure first | **YES** |
| **4** | Never available to reduce: second camera, ML, torch — because none of them are ever enabled in v1 | — | — | — |
| **5** | **`EMERGENCY`: finalise and stop.** Flush the muxer, close the file, release camera, post a notification. | Stops all our load | Recording ends | **Stop, cleanly** |

### 9.2 CameraX: exactly what is mutable on a live session vs what needs a rebind

`[DOCUMENTED]`, AndroidX camera reference (latest stable **1.6.1**, released 2026-05-06; latest alpha 1.7.0-alpha03, 2026-08-12 — [release notes](https://developer.android.com/jetpack/androidx/releases/camera)).

**Mutable while recording, no rebind:**

| API | Added | Note |
|---|---|---|
| `Recording.pause()` / `resume()` / `stop()` / `close()` / `mute(boolean)` | 1.1.0 | *"A `Recorder` supports one `Recording` object at a time."* |
| `VideoCapture.setTargetRotation(int)` | 1.3.0 | *"allows the target rotation to be set dynamically … without re-creating the use case."* **But**: *"For a Recorder output, calling this method has no effect on the ongoing recording, but will affect recordings started after calling this method."* → rotation changes land at the next segment. |
| `CameraControl.setZoomRatio` / `setLinearZoom` / `enableTorch` / `startFocusAndMetering` / `setExposureCompensationIndex` | 1.0.0+ | Live camera controls |
| `Camera2CameraControl.setCaptureRequestOptions(CaptureRequestOptions)` / `addCaptureRequestOptions(...)` | 1.0.0 | **This is the live back door.** *"Sets a CaptureRequestOptions and updates the session with the options it contains… The values will be submitted with every repeating and single capture requests issued by CameraX"* — returns a `ListenableFuture<Void>` that *"completes when the repeating CaptureResult shows the options have be submitted completely."* |
| `VideoCapture.setMirrorMode(int)` | 1.7.0-alpha03 | Not in stable 1.6.1 |

**Requires a new builder + `bindToLifecycle()` (i.e. a new segment):**

| API | Added | Why |
|---|---|---|
| `Recorder.Builder.setQualitySelector(QualitySelector)` | 1.1.0 | Builder-only. `Recorder.Builder.build()` *"can be called multiple times, generating a new Recorder instance each time."* |
| `Recorder.Builder.setTargetVideoEncodingBitRate(int)` | 1.3.0 | Builder-only. Doc: *"Additional checks will be performed on the requested bitrate … sometimes the passed bitrate will be changed internally"* — so the value we ask for is not necessarily what we get. |
| `Recorder.Builder.setAspectRatio(int)` | 1.3.0 | Builder-only, and *"If no resolution with the settings can be found, it will fail to bind VideoCapture."* |
| `Recorder.Builder.setVideoCapabilitiesSource(int)` | 1.4.0 | `VIDEO_CAPABILITIES_SOURCE_CAMCORDER_PROFILE` (default) vs `..._CODEC_CAPABILITIES` |
| `Recorder.Builder.setVideoMimeType(String)` / `setAudioMimeType(String)` | 1.7.0-alpha03 | Not in 1.6.1 |
| `VideoCapture.Builder.setTargetFrameRate(Range<Integer>)` | 1.3.0 | Read back via `VideoCapture.getTargetFrameRate()` |
| `VideoCapture.Builder.setVideoStabilizationEnabled(boolean)` | 1.3.0 | Read back via `isVideoStabilizationEnabled()` |
| `VideoCapture.Builder.setDynamicRange(DynamicRange)` | 1.3.0 | Keep SDR. HDR costs ISP/encode. |
| `Camera2Interop.Extender.setStreamUseCase(long)` | — | `@RequiresApi(33)` |

`[DOCUMENTED]` `VideoCapture.getResolutionInfo()` (1.5.0): *"The resolution information may change if: The use case is unbound and then rebound. `setTargetRotation` is called to change the target rotation."* And `getSelectedQuality()` (1.5.0): *"The selected Quality may change if the use case is unbound and then rebound."* Both confirm that quality/resolution are bind-time properties.

**The frame-rate special case.** `[INFERRED]` Because `Camera2CameraControl.setCaptureRequestOptions()` updates the *live repeating request*, setting `CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE` through it should change the actual sensor frame rate mid-recording without a rebind — which would let us drop 30 → 24 fps without cutting a segment. Chain: (a) the doc says values are *"submitted with every repeating and single capture requests issued by CameraX"* `[DOCUMENTED]`; (b) AE target FPS range is a repeating-request key that controls sensor readout rate. **However**, the same doc warns *"which may result in unexpected behavior depending on the values being applied"* and that overrides *"will overwrite"* CameraX's own values. `[UNVERIFIED]` and **flagged as risky**: whether the encoder and muxer handle a mid-recording frame-rate change without timestamp artefacts, whether the OEM honours the range, and whether CameraX's own 3A logic fights it. **Do not ship this without the test in §14.** The safe default is to treat fps as a rebind-only parameter (tier 3c).

### 9.3 Screen-off is the biggest win and needs the most care

`[DOCUMENTED]` Android 14 (API 34) requires a foreground service type for every FGS, and a camera FGS needs both `android:foregroundServiceType="camera"` and the `FOREGROUND_SERVICE_CAMERA` permission ([Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [FGS types required](https://developer.android.com/about/versions/14/changes/fgs-types-required)). `[DOCUMENTED]` The platform checks at FGS-creation time that the app *currently* holds the runtime permission for the type and throws `SecurityException` otherwise — and while-in-use permissions (camera, location, microphone) are only held while the app is in the foreground, so **the camera FGS must be started while we are still visibly foreground**, never lazily after the screen goes off.

`[INFERRED]` Roadguard sequencing: start the `camera`+`location`-typed foreground service **before** releasing `FLAG_KEEP_SCREEN_ON`, verify the service is running, and only then let the display sleep. Getting this order wrong turns our best thermal mitigation into a recording-stopping `SecurityException` — exactly the failure mode we least want. `[UNVERIFIED]` whether Motorola's power management adds extra restrictions on a screen-off camera FGS on the G04; this needs a real overnight/long-drive test (§14).

### 9.4 Display APIs, precisely

`[DOCUMENTED]` `WindowManager.LayoutParams`:

| Field / constant | Added | Doc |
|---|---|---|
| `public float screenBrightness` | 3 | *"This can be used to override the user's preferred brightness of the screen. A value of less than 0, the default, means to use the preferred screen brightness. 0 to 1 adjusts the brightness from dark to full bright."* |
| `BRIGHTNESS_OVERRIDE_NONE` | 8 | `-1.0` — "not overridden for this window" |
| `BRIGHTNESS_OVERRIDE_OFF` | 8 | `0.0` — "lowest value when this window is in front" |
| `public float preferredRefreshRate` | 21 | *"Starting API 34, this value is not limited to the supported refresh rates … The OS will select the refresh rate that best matches"*. Equivalent to `Surface.setFrameRate(rate, FRAME_RATE_COMPATIBILITY_DEFAULT, CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)`. *"This value is ignored if preferredDisplayModeId is set."* |
| `public int preferredDisplayModeId` | 23 | Must be a `Display.Mode.getModeId()` from `Display.getSupportedModes()`; *"treated as a preference and may be ignored"* |
| `FLAG_KEEP_SCREEN_ON` | 1 | `0x80` — *"as long as this window is visible to the user, keep the device's screen turned on and bright"* |

**Important limitation on `Surface.setFrameRate`.** `[DOCUMENTED]` `Surface.setFrameRate(float, int)` API **30**; `Surface.setFrameRate(float, int, int)` API **31**. Its own doc: *"Usage of this API won't introduce frame rate throttling, or affect other aspects of the application's frame production pipeline."* And [Frame Rate API](https://developer.android.com/media/optimize/performance/frame-rate): *"there is no guarantee that your app will get the frame rate you request."* `[INFERRED]` So `setFrameRate` is **not** a way to make our map render less — it only hints the *display* refresh rate. To actually reduce our own GPU/CPU work we must throttle our own draw loop (tier 1c/1d). Getting this backwards is an easy mistake: setting `setFrameRate(30)` and assuming the map now costs a third as much would be wrong.

Constants for completeness `[DOCUMENTED]`: `Surface.FRAME_RATE_COMPATIBILITY_DEFAULT`, `FRAME_RATE_COMPATIBILITY_FIXED_SOURCE` (*"For video apps; indicates fixed source frame rate"*), `FRAME_RATE_COMPATIBILITY_AT_LEAST`; `CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS`, `CHANGE_FRAME_RATE_ALWAYS` (non-seamless switches need the user's *"Match content frame rate"* opt-in / `DisplayManager.MATCH_CONTENT_FRAMERATE_ALWAYS`).

### 9.5 GNSS, precisely

`[DOCUMENTED]` `android.location.LocationRequest.Builder` (API 31): `setIntervalMillis(long)`, `setMinUpdateIntervalMillis(long)`, `clearMinUpdateIntervalMillis()`, `setMaxUpdateDelayMillis(long)`, `setMinUpdateDistanceMeters(float)`, `setQuality(int)`, `setMaxUpdates(int)`, `setDurationMillis(long)`.

`[DOCUMENTED]` [Optimize location for battery](https://developer.android.com/develop/sensors-and-location/location/battery): *"Pass the largest possible value when using setIntervalMillis()… Reserve intervals of a few seconds for foreground use cases."* And on batching: *"This setting delays location delivery, and multiple location updates may be delivered in batches. These two changes help minimize battery consumption."*

`[INFERRED]` A dashcam legitimately needs ~1 Hz for a usable speed/track overlay, so this is a *shallow* lever: 1 s → 3 s at MODERATE, 1 s → 5 s at SEVERE, and never batch aggressively (batching would break the live speed readout, which is a user-visible feature, for a documented-small power saving). This is the one place where the mitigation ladder should be deliberately conservative.

---

## 10. Charging behaviour

**Is fast charging documented as a major heat source?** Not in those words. What *is* documented:

- `[DOCUMENTED]` AOSP thermal mitigation: *"Anything that throttles device performance, **including battery power constraints**, must be reported through the thermal HAL."* → the platform's own model treats charge/battery-power limits as belonging to the thermal severity stream. That is an official acknowledgement that charging participates in thermal mitigation.
- `[DOCUMENTED]` The thermal HAL has dedicated zone types for exactly this: `Temperature.TYPE_BATTERY`, `TYPE_USB_PORT`, `TYPE_BCL_VOLTAGE`, `TYPE_BCL_CURRENT`, `TYPE_BCL_PERCENTAGE` (BCL = battery current limiter) — read from AOSP `ThermalManagerService`. OEMs instrument the charge path as a thermal zone.
- `[DOCUMENTED]` `BatteryManager.BATTERY_HEALTH_OVERHEAT` exists as a reportable health state (API 5).
- `[DOCUMENTED]` Our two devices differ by **4.5×** in charge power: 15 W (5 V/3 A) on the G04 vs 68 W TurboPower on the Edge 60 Fusion.

`[INFERRED]` Fast charging is a major heat source for us: charge power is dissipated with some inefficiency inside a sealed phone body, in parallel with the camera/encoder load and with an elevated ambient. A 68 W input is of the same order as, or larger than, the entire recording workload. This is reasoning from the documented wattages and from the fact that OEMs instrument the charge path thermally — I have no measurement.

**Can an app influence the charging rate? No.**

`[DOCUMENTED]` The complete public method surface of `android.os.BatteryManager` is: `computeChargeTimeRemaining()`, `getIntProperty(int)`, `getLongProperty(int)`, `getStringProperty(int)` (API 35), `isCharging()`. **There is not a single setter.** Everything else in the class is constants. `[INFERRED]` therefore no ordinary app can throttle, cap, or pause charging through public Android APIs. (Charge-limiting apps that exist do so via root, Magisk modules, sysfs writes — which §7.3 shows are `neverallow`ed for apps — or undocumented OEM intents. **None of these are options for Roadguard**: they require root or non-compliant policy, and one of them is writing to `/sys`, which AOSP forbids by neverallow rule.)

**What Roadguard will actually do:**

1. **Detect and attribute.** Read `EXTRA_PLUGGED` from `ACTION_BATTERY_CHANGED`. Record whether we were charging when a thermal escalation occurred, and at what `BATTERY_PROPERTY_CURRENT_NOW` magnitude, so our own diagnostics can tell "the car charger cooked us" apart from "the app cooked us".
2. **Escalate mitigation earlier while charging.** `[INFERRED]` If `EXTRA_PLUGGED != 0` **and** battery level is above a comfortable floor (say ≥ 80%), shift the whole headroom ladder down by one tier — because charging heat is heat we cannot reduce, so the only remaining budget is our own workload.
3. **Advise the user, honestly and specifically.** This is the highest-leverage "feature" in this whole document, because it is the only way to reduce the #4 heat source:
   - Use a **low-power (5 V / 1 A–2 A) charger or cable**, not the phone's fast charger. On the Edge this is the difference between 68 W and ~10 W of charge heat.
   - Once the battery is near full, **unplug** — a 5000/5500 mAh battery will run a recording session for hours; continuous charging at 100% is pure heat.
   - **Don't mount behind the rear-view mirror against the glass** if avoidable; get airflow from a vent, and prefer a metal or open-frame mount over a sealed plastic cradle.
   - Take the **case off**.
   - Point a cabin vent at the phone.
4. **Never claim we can control it.** No settings toggle that implies charge-rate control, because we cannot deliver one.

`[UNVERIFIED]` Whether some Motorola builds surface an OEM "charge optimisation" toggle that a user could enable, and whether it is reachable via a documented intent. Worth a look on the physical devices, but must not become a dependency.

---

## 11. The thermal state machine — hysteresis and dwell

`[INFERRED]` This section is design, not documentation. The documented inputs are in §2–§5.

```
tier ∈ { NORMAL, WATCH, REDUCED, MINIMAL, STOPPING }
```

| Tier | Entry condition (headroom mode) | Actions |
|---|---|---|
| `NORMAL` | `h(0) < light − 0.05` and `h(30) < light` | Full quality |
| `WATCH` | `h(0) ≥ light − 0.05` **or** `h(30) ≥ light` | Tier 1a–1e |
| `REDUCED` | `h(0) ≥ moderate − 0.05` **or** `h(30) ≥ moderate` | + tier 2a–2c; **queue** encoder step-down for next segment boundary |
| `MINIMAL` | `h(0) ≥ severe` (i.e. ≥ 1.0) **or** `status ≥ SEVERE` | + tier 3a–3e; cut segment early and rebind now |
| `STOPPING` | `status ≥ EMERGENCY` **or** `h(0) ≥ severe + 0.15` sustained | Finalise segment, release camera, notify |

Rules that matter for reliability:

- **Escalate immediately, de-escalate slowly.** Minimum dwell before stepping *down* a tier: **120 s**. Rationale `[INFERRED]`: from §4.3, 0.1 headroom = 3 °C of *skin* temperature, and skin temperature is explicitly the *"slow-moving"* sensor `[DOCUMENTED]`. A brief dip in headroom does not mean the SoC has cooled. Rebinding CameraX on a transient dip would cut a segment for nothing, and segment churn is itself a reliability risk.
- **Asymmetric thresholds (0.05 of hysteresis).** Prevents ladder oscillation right at a threshold.
- **Escalate on `h(30)`, de-escalate only on `h(0)`.** The forecast is allowed to make us cautious; it is never allowed to make us optimistic.
- **Encoder changes only at segment boundaries**, except at `MINIMAL`/`STOPPING` where we cut early. Since segments are 180 s and the forecast horizon is 30 s, `REDUCED` will usually get its encoder change applied within one segment without any early cut.
- **Never de-escalate below the tier implied by "we are charging and ≥80%"** (§10.2).
- **A `NaN` reading is not a de-escalation event.** Hold the last tier.

---

## 12. Building a thermal simulation / test harness

### 12.1 What to abstract (this is the whole trick)

`[DOCUMENTED]` Robolectric shadows `getCurrentThermalStatus()`, `addThermalStatusListener(...)`, `removeThermalStatusListener(...)` and exposes `setCurrentThermalStatus(int)` + `getThermalStatusListeners()` — read from [`ShadowPowerManager.java`](https://github.com/robolectric/robolectric/blob/master/shadows/framework/src/main/java/org/robolectric/shadows/ShadowPowerManager.java) (`setCurrentThermalStatus` validates the range and then loops the registered listeners calling `onThermalStatusChanged(thermalStatus)`).

`[DOCUMENTED]` **`getThermalHeadroom` is NOT shadowed** — no occurrence of `getThermalHeadroom` in that file, nor of `getThermalHeadroomThresholds`. `[INFERRED]` Therefore a Robolectric unit test that reaches `PowerManager.getThermalHeadroom()` will hit unshadowed framework code and behave unpredictably. **This is the concrete reason the abstraction is mandatory, not stylistic** — you cannot fake our primary thermal signal with Robolectric alone.

```kotlin
/** The ONLY thing the mitigation logic is allowed to see. Zero android.os imports. */
interface ThermalSource {
    /** Current headroom, or NaN if unsupported/unavailable. */
    fun headroom(forecastSeconds: Int): Float
    /** One of PowerManager.THERMAL_STATUS_* — but as our own enum, not a raw int. */
    fun platformStatus(): PlatformThermalStatus
    /** Threshold ladder; already resolved from API 35 map or the 0.85/0.95/1.00 fallback. */
    fun ladder(): HeadroomLadder
    /** Battery temperature in °C, or null. */
    fun batteryCelsius(): Float?
    /** True if any power source is attached (EXTRA_PLUGGED != 0). */
    fun isPlugged(): Boolean
}

enum class PlatformThermalStatus(val platformValue: Int) {
    NONE(0), LIGHT(1), MODERATE(2), SEVERE(3), CRITICAL(4), EMERGENCY(5), SHUTDOWN(6), UNKNOWN(-1);
    companion object { fun from(v: Int) = entries.firstOrNull { it.platformValue == v } ?: UNKNOWN }
}

/** The pure decision function. No Android, no clock, no coroutines. */
fun interface ThermalPolicy {
    fun evaluate(reading: ThermalReading, current: ThermalTier, nowMs: Long): ThermalDecision
}
```

Two implementations: `PlatformThermalSource` (wraps `PowerManager` + the battery receiver, holds all the `SDK_INT` gates and all the `NaN` / `SecurityException` / `UnsupportedOperationException` / `IllegalStateException` handling) and `FakeThermalSource` (a scripted list of readings, plus an injectable clock). The policy function and the tier state machine are then **pure and fully unit-testable with no Robolectric at all**.

### 12.2 Test cases the harness must cover

| Case | Scripted input | Assert |
|---|---|---|
| Happy ramp | headroom 0.40 → 0.90 → 0.97 → 1.05 → 0.80 | tiers NORMAL → WATCH → REDUCED → MINIMAL, then **no** de-escalation before 120 s dwell |
| Unsupported device | `headroom()` always `NaN`, `platformStatus()` always `NONE` | falls to battery-temp/elapsed path; **never** silently sits at NORMAL |
| Status stuck at NONE | headroom 1.10, status `NONE` | tier reaches MINIMAL from headroom alone (the documented ADPF heuristic) |
| Thresholds unsupported | `getThermalHeadroomThresholds()` throws `UnsupportedOperationException` | ladder == 0.85/0.95/1.00; **no retry storm** |
| Thermal service not ready | throws `IllegalStateException` | ladder == fallback; **retries** later (distinct from the case above) |
| `NaN` interleaving | 0.9, `NaN`, 0.9, `NaN` | tier held, no oscillation, `NaN` counted |
| Ladder oscillation | headroom oscillating ±0.01 around 0.85 | **at most one** tier transition |
| Segment-boundary discipline | REDUCED entered 20 s into a 180 s segment | encoder change applied at the boundary, **not** at t+20 s |
| EMERGENCY | status jumps `NONE` → `EMERGENCY` | recording finalised; a valid file exists; camera released |
| Charging bias | plugged, level 92%, headroom 0.80 | tier is one step above what headroom alone would give |
| Cold start zero | headroom 0.0 for first 3 probes then 0.4 | **not** marked unsupported (guards against Google's `==0 → unsupported` shortcut) |

### 12.3 Real-device injection — exact adb commands, per API level

`[DOCUMENTED]` from AOSP `ThermalManagerService.ThermalShellCommand.onHelp()`. **The available commands differ by platform version** — read from the three branches:

**Android 14 (`android14-release`) — only two commands exist:**

```
Thermal service (thermalservice) commands:
  help
  override-status STATUS
    sets and locks the thermal status of the device to STATUS.
    status code is defined in android.os.Temperature.
  reset
    unlocks the thermal status of the device.
```

```bash
adb shell cmd thermalservice override-status 3   # lock to SEVERE
adb shell cmd thermalservice reset               # unlock
adb shell dumpsys thermalservice                 # inspect zones/thresholds
```

**Android 15+ (`android15-release`, `main`) — two more appear:**

```
  inject-temperature TYPE STATUS NAME [VALUE]
    injects a new temperature sample for the specified device.
    type and status strings follow the names in android.os.Temperature.
  headroom FORECAST_SECONDS
    gets the thermal headroom forecast in specified seconds, from [0,60].
```

```bash
# Android 15+: drive the SKIN sensor directly, which is what headroom is computed from
adb shell cmd thermalservice inject-temperature SKIN SEVERE skin_therm 55.0
adb shell cmd thermalservice headroom 30
adb shell cmd thermalservice reset
```

`TYPE` accepts: `UNKNOWN, CPU, GPU, BATTERY, SKIN, USB_PORT, POWER_AMPLIFIER, BCL_VOLTAGE, BCL_CURRENT, BCL_PERCENTAGE, NPU, TPU, DISPLAY, MODEM, SOC, WIFI, CAMERA, FLASHLIGHT, SPEAKER, AMBIENT, POGO`. `STATUS` accepts `NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN`. `VALUE` defaults to `28.0f` if omitted. All `[DOCUMENTED]` from `runInjectTemperature()`.

**Consequence for our test plan:** `[INFERRED]` On the **Moto G04 (Android 14) we can only override the thermal *status*, not the headroom.** `override-status` sets `mIsStatusOverride = true`, which the AOSP code uses to suppress HAL-driven status updates — but `getThermalHeadroom()` reads the *real* skin temperature samples and is completely unaffected by it. So on the baseline device, an integration test can exercise the status-driven half of the ladder but **cannot** synthetically exercise the headroom-driven half. That half must be tested either (a) in unit tests via `FakeThermalSource`, (b) on an Android 15+ device with `inject-temperature`, or (c) by genuinely heating the G04 (see §14). Do not assume `override-status` on Android 14 gives headroom coverage — it does not.

`[DOCUMENTED]` AOSP also notes OEMs *"can use `emul_temp` from the kernel sysfs interface to simulate temperature changes"* — that is a platform/root-level facility, not available to us as an app or over plain adb on a production device.

### 12.4 Instrumented soak test

`[INFERRED]` The unit tests prove the state machine; only a soak test proves the product. Minimum viable:

1. Record continuously for 60 minutes, screen on, brightness max, map rendering, plugged into the device's own fast charger, in a warm enclosure.
2. Log every 5 s: `getThermalHeadroom(0)`, `getThermalHeadroom(30)`, `getCurrentThermalStatus()`, battery °C, `CURRENT_NOW`, our tier, actual encoder fps and bitrate, dropped-frame count, segment index.
3. Assert: every segment file on disk is a valid, playable MP4 with a sane duration. **This is the only assertion that actually matters.**
4. Repeat with no charger, then with a 5 W charger, to isolate the charging term.

---

## 13. Quick reference — the API-level gate table for implementation

| Feature | Gate | Behaviour below the gate |
|---|---|---|
| `getCurrentThermalStatus`, status listener (`Executor` overload) | none (API 29) | n/a — always available at minSdk 34 |
| `getThermalHeadroom(int)` | none (API 30) | n/a — but may return `NaN` on any device; handle it |
| `getThermalHeadroomThresholds()` | `SDK_INT >= 35` | hardcode `HeadroomLadder(0.85f, 0.95f, 1.00f)` from ADPF guidance |
| `addThermalHeadroomListener` / `OnThermalHeadroomChangedListener` | `SDK_INT >= 36` | rely on the 5 s poll only (which the docs say to keep doing anyway) |
| Thresholds may change between calls | `SDK_INT >= 36` (`BAKLAVA`) | read once and cache |
| `SystemHealthManager.getCpuHeadroom` / `getGpuHeadroom` | `SDK_INT >= 36` | omit; diagnostics only |
| `SystemHealthManager` power monitors (`getConsumedEnergy` µW·s) | `SDK_INT >= 35` | omit; debug variant only |
| `PerformanceHintManager.Session.setPreferPowerEfficiency(boolean)` | `SDK_INT >= 35` | omit |
| `Camera2Interop.Extender.setStreamUseCase(long)` | `SDK_INT >= 33` | always available at minSdk 34; still check `SCALER_AVAILABLE_STREAM_USE_CASES` |
| `cmd thermalservice inject-temperature` / `headroom` | device on Android 15+ | Android 14 test devices get `override-status` / `reset` only |

---

## Open questions / must-measure-on-device

Everything below is unresolved. Each item names the test that would settle it.

**Thermal signal availability (blocking — the whole design branches on these)**

1. **Does `getThermalHeadroom(0)` return a real float or `NaN` on the Moto G04?** This is the single most important unknown in this document; if `NaN`, the fallback estimator in §7.4 becomes the product's primary thermal defence rather than a backstop. *Test:* minimal app, `PowerManager.getThermalHeadroom(0)` once per second for 60 s, log every value; then repeat after 10 minutes of recording to confirm it moves.
2. **Does `getCurrentThermalStatus()` ever leave `THERMAL_STATUS_NONE` on the G04 under real load?** *Test:* record 1080p30 for 45 min, plugged into the 15 W charger, in a closed warm box; log status every 5 s. If it never leaves NONE while headroom exceeds 0.85, mark the device `STATUS_STUCK_AT_NONE` and rely on headroom only (Google's documented heuristic).
3. **What is the G04's SEVERE skin threshold in absolute °C, and what headroom does a hot windscreen alone produce?** Derivable: `headroom == 1.0` ⇔ skin == SEVERE threshold, and 0.1 headroom == 3.0 °C exactly (AOSP `DEGREES_BETWEEN_ZERO_AND_ONE = 30.0f`). *Test:* log headroom + battery °C simultaneously across a cold-start-to-hot ramp and fit the relation; on an Android 15+ device cross-check with `cmd thermalservice inject-temperature SKIN … <value>` + `cmd thermalservice headroom 0`.
4. **Does `getThermalHeadroomThresholds()` return a populated map on the Edge 60 Fusion (Android 15), or throw?** *Test:* call it, log the map or the exception class. Determines whether the fallback ladder is the real ladder in practice.
5. **Does polling headroom every 5 s actually keep the AOSP sampler alive and produce a differing `h(30)` vs `h(0)`?** The whole "predictive" design rests on this. *Test:* log `h(0)` and `h(30)` every 5 s for 20 min during a thermal ramp and confirm `h(30) > h(0)` while heating and the two converge when stable. If `h(30) == h(0)` always, the sampler is being starved (or the device has <3 samples) and the poll interval must drop.

**CameraX behaviour (blocking for the mitigation ladder)**

6. **What is `CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL` on the G04?** Determines whether the documented stream-sharing penalty applies. *Test:* one-line read at startup, logged. If `LIMITED` or `LEGACY`, the "never bind `ImageAnalysis`" rule becomes a hard architectural constraint, not a preference.
7. **What rear-camera video qualities and frame-rate ranges does the G04 actually offer?** *Test:* enumerate `Recorder.getVideoCapabilities(cameraInfo)` / `QualitySelector` supported qualities and `CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`. Motorola documents front-camera video only; rear is unstated.
8. **How long does a full "new `Recorder` + rebind + start new `Recording`" cycle take on the G04, and how many frames of footage are lost?** This is the cost of every tier-3 mitigation. *Test:* instrument the rebind path, measure wall-clock gap between last frame of segment N and first frame of segment N+1, 20 repetitions. If the gap is large, tier 3 must be used far more sparingly.
9. **Is changing `CONTROL_AE_TARGET_FPS_RANGE` mid-recording via `Camera2CameraControl.setCaptureRequestOptions()` safe?** *Test:* record 3 minutes, switch 30→24 fps at t=60 s, then inspect the resulting MP4 for timestamp discontinuities, duration error, and playback artefacts in at least two players plus `ffprobe -show_frames`. **Treat as unsafe until this passes.** If it passes, fps becomes a no-rebind lever and the ladder gets meaningfully cheaper.
10. **What does `SCALER_AVAILABLE_STREAM_USE_CASES` contain on each device, and what does `VIDEO_CALL` cost in recorded quality?** It is the only officially "recommended for long-running camera uses where power drain is a concern" option. *Test:* enumerate the list; record the same scene with `VIDEO_RECORD` vs `VIDEO_CALL` and compare resolution, bitrate, and legibility of a number plate at 10 m.

**Heat attribution (needed to prioritise correctly)**

11. **Actual ranking of display vs camera+encoder on our two devices.** My §9 ranking is documented-example-based and inferred, not measured. *Test:* four 20-minute runs at fixed ambient, measuring battery discharge (`BATTERY_PROPERTY_CHARGE_COUNTER` delta) and headroom slope: (a) recording + screen on max, (b) recording + screen off, (c) preview only, no recording, screen on, (d) idle, screen on max. The (a)−(b) difference is the display term; (a)−(c) approximates the encode term.
12. **How much heat does the map+overlay GPU pass cost on the single-core Mali-G57?** *Test:* recording + screen on, map rendering at 60 Hz vs map static vs map hidden; compare headroom slope.
13. **Charging heat, quantified.** *Test:* identical 30-minute recording runs at 68 W, at ~10 W (5 V/2 A), and unplugged on the Edge; and 15 W vs unplugged on the G04. Compare headroom slope and time-to-first-throttle. This decides how strongly to word the in-app advice.
14. **Does `BATTERY_PROPERTY_CURRENT_NOW` follow the documented sign convention on each device?** *Test:* read it plugged and unplugged, compare signs to the doc ("positive = entering the battery"). If inverted, use magnitude only.
15. **Does the Edge 60 Fusion expose ODPM rails via `SystemHealthManager.getSupportedPowerMonitors()` (API 35)?** If yes, we get microwatt-second-accurate per-rail attribution for free in a debug build, which would replace most of the inference in §9 with real numbers. *Test:* call it and log the returned `PowerMonitor` names and types.

**Screen-off and service reliability (blocking for the biggest mitigation)**

16. **Does recording survive the screen turning off on the G04 for 60+ minutes with a `camera`-typed foreground service?** *Test:* start FGS while foreground, release `FLAG_KEEP_SCREEN_ON`, let the display sleep, record 60 min, verify every segment file. Also check Motorola-specific battery-optimisation settings do not kill it.
17. **Does Motorola's OEM power management on either device throttle or kill a long-running camera FGS?** *Test:* 3-hour soak, screen off, unplugged, then plugged; check for service death, camera-disconnect callbacks, or segment gaps.

**Test-harness gaps**

18. **Confirm `cmd thermalservice inject-temperature` and `headroom` are genuinely absent on the shipping G04 build** (AOSP says they arrived after Android 14, but OEMs backport). *Test:* `adb shell cmd thermalservice help` on the real device. If present, our Android 14 integration tests get much better headroom coverage than §12.3 assumes.
19. **Confirm Robolectric's current version still lacks a `getThermalHeadroom` shadow** at whatever version we pin. *Test:* a unit test that calls it through `PlatformThermalSource` under Robolectric and asserts we handle whatever comes back. If Robolectric later adds a shadow, we can widen unit coverage.

---

## Sources actually retrieved (2026-08-22)

**Android API reference**
- https://developer.android.com/reference/android/os/PowerManager
- https://developer.android.com/reference/android/os/PowerManager.OnThermalHeadroomChangedListener
- https://developer.android.com/reference/android/os/PerformanceHintManager
- https://developer.android.com/reference/android/os/PerformanceHintManager.Session
- https://developer.android.com/reference/android/os/HardwarePropertiesManager
- https://developer.android.com/reference/android/os/BatteryManager
- https://developer.android.com/reference/android/os/health/SystemHealthManager
- https://developer.android.com/reference/android/os/PowerMonitor
- https://developer.android.com/reference/android/os/PowerMonitorReadings
- https://developer.android.com/reference/android/view/Surface
- https://developer.android.com/reference/android/view/WindowManager.LayoutParams
- https://developer.android.com/reference/android/location/LocationRequest.Builder
- https://developer.android.com/ndk/reference/group/thermal

**AndroidX / CameraX reference (stable 1.6.1)**
- https://developer.android.com/reference/androidx/camera/video/Recorder.Builder
- https://developer.android.com/reference/androidx/camera/video/VideoCapture
- https://developer.android.com/reference/androidx/camera/camera2/interop/Camera2CameraControl
- https://developer.android.com/reference/androidx/camera/camera2/interop/Camera2Interop.Extender
- https://developer.android.com/jetpack/androidx/releases/camera
- https://developer.android.com/media/camera/camerax/architecture
- https://developer.android.com/media/camera/camerax/video-capture
- https://developer.android.com/media/camera/camera2/capture-sessions-requests

**Guides**
- https://developer.android.com/games/optimize/adpf/thermal
- https://developer.android.com/games/optimize/adpf
- https://developer.android.com/games/optimize/adpf/best-practices-adpf
- https://developer.android.com/games/optimize/adpf/gamemode/about-API-and-interventions
- https://developer.android.com/media/optimize/performance/frame-rate
- https://developer.android.com/develop/sensors-and-location/location/battery
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/about/versions/14/changes/fgs-types-required

**AOSP documentation**
- https://source.android.com/docs/core/power/thermal-mitigation
- https://source.android.com/docs/core/power/values
- https://source.android.com/docs/core/camera/stream-config

**AOSP source (read directly)**
- `frameworks/base/services/core/java/com/android/server/power/ThermalManagerService.java` on `refs/heads/main`, `refs/heads/android14-release`, `refs/heads/android15-release`
- `system/sepolicy/public/file.te` (line 104 `sysfs_batteryinfo`, line 171 `sysfs_thermal`)
- `system/sepolicy/private/untrusted_app_all.te` (lines 116–117)
- `system/sepolicy/private/app_neverallows.te` (lines 117–124)
- `hardware/interfaces/health/aidl/android/hardware/health/HealthInfo.aidl`

**Other**
- https://github.com/robolectric/robolectric/blob/master/shadows/framework/src/main/java/org/robolectric/shadows/ShadowPowerManager.java
- https://en-us.support.motorola.com/app/answers/detail/a_id/178144/ (moto g04 specs)
- https://en-au.support.motorola.com/app/answers/detail/a_id/187360/~/specifications---motorola-edge-60-fusion
