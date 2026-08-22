# Event detection and footage protection

> **Roadguard is not a certified crash detector.** It does not call emergency services, it is
> not an eCall system, and its thresholds have not been validated against real collisions. It
> is a mechanism for keeping footage that would otherwise be overwritten by the loop. Treat it
> as that and nothing more.

---

## 1. Why a threshold is not enough

The naive dashcam design is "if acceleration exceeds N g, protect the footage". In a car that
does not work, and the reason is measurable: **a pothole at 60 km/h and a low-speed collision
produce peaks of the same order at a windscreen mount.** A phone in a suction cradle is a mass
on a spring; the cradle amplifies short, sharp inputs and the phone is nowhere near the crash
structure the g-force figures in collision literature refer to.

Pick a high threshold and you miss the low-speed impact in a car park that is exactly the
footage an insurer wants. Pick a low one and every speed bump on the way to work protects three
minutes of video until the loop is full of nothing.

So Roadguard extracts *features* and combines them, and accepts that this is a
signal-classification problem rather than a comparison.

## 2. The stages

```
sample (100 Hz)
   │
   ├─► 1. rolling history (4 s ring buffer) ─────────────────────────┐
   │                                                                │
   ├─► 2. candidate window: peak > threshold opens it,               │
   │      closes 350 ms after the peak                               │
   │                                                                │
   ├─► 3. features over the window:                                  │
   │        peak (g)                                                 │
   │        energy (g·s — the integral of magnitude)                 │
   │        duration above half peak (ms)                            │
   │        horizontal fraction of energy                            │
   │                                                                │
   ├─► 4. discrimination:                                            │
   │        vertical-dominated  → road surface, reject               │
   │        too brief           → single-sample spike, reject        │
   │        gravity moved before the peak → phone was handled ◄──────┘
   │                                          (needs the history)
   ├─► 5. context: GNSS speed before, and speed change
   │        stationary → reject
   │        no fix → raise the bar rather than skip the check
   │
   └─► 6. weighted confidence vs the sensitivity's bar, then cooldown
```

### Stage 1 — history

Every sample enters a ring buffer covering `HISTORY_SECONDS = 4`. **Nothing is ever decided
from an instantaneous value.** The history is also what makes stage 4's handling check possible,
and it can be dumped to a trace file for offline analysis.

Sampling is at 100 Hz — deliberately under the 200 Hz threshold that would require
`HIGH_SAMPLING_RATE_SENSORS`.

### Stage 2 — candidate window

A sample above the sensitivity's `peakThresholdG` opens a window that closes
`WINDOW_AFTER_MS = 350` after the peak. Features are computed over the window, not the sample.

### Stage 3 — features

| Feature | What it distinguishes |
| --- | --- |
| **Peak (g)** | magnitude. On its own, ambiguous — see §1 |
| **Energy (g·s)** | a broad collision signature from a sharp single-spike road input. This is the feature that actually separates a pothole from a shunt |
| **Duration above half peak** | a genuine impact is not one sample |
| **Horizontal fraction** | how the energy divides between the gravity axis and the road plane |

### Stage 4 — discrimination

* **Vertical-dominated events are road surface.** A pothole, an expansion joint or a speed bump
  drives the phone along gravity. A collision — being hit, hitting something, being shunted —
  puts most of its energy in the road plane. `minHorizontalFraction` is the gate.
* **Handling rejection needs no gyroscope.** A phone clipped into a cradle keeps a
  near-constant gravity *direction*; a phone being picked up, adjusted, knocked or dropped
  swings it. `handlingScore` measures the angular spread of the normalised gravity vector over
  the `HANDLING_LOOKBACK_MS = 2000` **before** the peak, via mean resultant length. Above
  `HANDLING_REJECT_FRACTION = 0.85` of the reference spread the candidate is rejected outright;
  below it, the score still applies a proportional penalty of up to
  `HANDLING_PENALTY_WEIGHT = 0.60`.

  This matters because the largest g-force most dashcams ever see is the driver picking the
  phone up.

### Stage 5 — context

GNSS speed before the peak, and the change across it. Stationary (below `minSpeedKmh`) rejects:
a phone being mounted in a parked car is not a collision. **With no fix the bar is raised, not
skipped** — `NEUTRAL_SPEED_TERM = 0.45` is deliberately just under the mid-point, so a
no-GNSS event has to be more convincing on its other features.

### Stage 6 — confidence

```
confidence = (0.35·peak + 0.25·energy + 0.20·horizontal + 0.20·speed)
             × (1 − 0.60·handling)
```

and then × `NO_GYRO_CONFIDENCE_SCALE = 0.92` on a device with no gyroscope, because the
accelerometer-only path is real but has one fewer independent signal.

Each term is normalised against a reference: the peak against the threshold itself, energy
against `energyReferenceGSeconds`, speed against `SPEED_REFERENCE_KMH = 40` and
`DELTA_SPEED_REFERENCE_KMH = 20`.

A cooldown (12–20 s by sensitivity) stops one collision producing a burst of events.

## 3. Sensitivity settings

| | Low | **Medium (default)** | High |
| --- | --- | --- | --- |
| Peak threshold | 3.5 g | **2.5 g** | 1.8 g |
| Confidence bar | 0.62 | **0.50** | 0.40 |
| Hard-braking bar | 0.70 | **0.60** | 0.50 |
| Min horizontal fraction | 0.45 | **0.35** | 0.28 |
| Min duration above half peak | 30 ms | **20 ms** | 15 ms |
| Stationary below | 8 km/h | **5 km/h** | 3 km/h |
| Cooldown | 20 s | **15 s** | 12 s |
| Energy reference | 0.50 g·s | **0.45 g·s** | 0.40 g·s |

The Medium peak threshold sits at 2.5 g because published telematics and airbag literature puts
even minor collisions well above 2 g **at the vehicle body**, while potholes and speed bumps at
a windscreen mount commonly reach 1–2 g. The design leans on the discriminators rather than on
the threshold: that is the whole point of §2.

## 4. Impact versus hard braking

Both are worth keeping, and describing them honestly is better than calling everything a crash.

| | Impact | Hard braking |
| --- | --- | --- |
| Peak | high | below `HARD_BRAKING_MAX_PEAK_G = 1.2` |
| Duration above half peak | short | at least `HARD_BRAKING_MIN_DURATION_MS = 250` |
| Speed change | any | at least `HARD_BRAKING_MIN_DELTA_KMH = 20` drop |

Hard braking is held to a *higher* confidence bar than impact, because the consequence of a
false positive is the same but the evidential value is lower.

## 5. Near misses are reported

`onSample` returns a `DetectedEvent` even when `accepted` is false, carrying the features, the
confidence and the `rejectionReasons` in plain English — "vertical-dominated: looks like road
surface", "too brief: 8 ms above half peak", "vehicle stationary at 0 km/h", "phone appears to
have been handled".

Diagnostics shows these. It is the only way a user (or a developer with a drive trace) can tell
whether the sensitivity is set sensibly, rather than guessing from the absence of events.

## 6. What gets protected

Defaults: **30 s before** the event, **60 s after** (§25). Both configurable —
pre 10/15/30/45/60 s, post 30/60/90/120 s.

`ProtectionPlanner` maps that window onto segments, and three things about it are deliberate:

1. **Overlap, not containment.** A three-minute segment that merely clips the first second of
   the pre-roll still holds footage the event needs, so it is protected. An impact landing
   microseconds before a rollover protects **both** the closing and the opening segment.
2. **The post-roll usually does not exist yet.** The event stays `AwaitingPostRoll` and claims
   segments as they finalise. An in-progress segment counts as covering only up to *now*, not to
   its eventual end.
3. **The state is reconstructible from the database alone.** If the process dies between the
   impact and the end of the post-roll — which is exactly what a serious collision might do —
   the next start finds the event `AwaitingPostRoll` and closes it with whatever footage exists,
   marking it `Incomplete` rather than pretending it is whole.

Protection itself is a sidecar file plus an index row, and no file is ever moved. See
`docs/storage.md` §5.

## 7. Manual protection

A **Protect** button on the main screen creates an `EventKind.Manual` event at the current
timestamp with the same pre/post window. It bypasses every detector stage — it is the user
saying "keep this", and that judgement outranks any classifier.

## 8. Honest limits

* **Every threshold in §3 is a documented starting point**, derived from published collision and
  telematics figures and from the physics of the discriminators. **None was measured by
  Roadguard on a real vehicle.** They are gathered in one `Tuning` data class precisely so they
  can be replaced wholesale once real drive traces exist.
* The research note that would have collected the published figures in detail
  (`docs/research/event-detection.md`) was not completed — see `docs/research/README.md`.
* A phone in a cradle is not the vehicle body. Cradle stiffness, mount position and phone mass
  all change what the accelerometer sees, and Roadguard cannot know any of them.
* No airbag deployment, no seatbelt pretensioner, no OBD data. Roadguard sees one accelerometer,
  optionally a gyroscope, and GNSS.
* **Roadguard will miss collisions and will protect some non-collisions.** The design bias is
  toward protecting too much, because storage is cheap and lost footage is not.

## 9. How to replace reasoning with measurement

1. Mount the phone as it will be used and drive normally with `historySnapshot()` dumping to a
   trace file (`SensorTraceTest` already replays this format).
2. Collect at least a few hours covering: coarse road surface, speed bumps taken at speed,
   potholes, hard braking, gravel, a car park, and the phone being picked up and re-cradled.
3. Label the trace. Every near-miss the app reported is a labelling candidate.
4. Replay it through `ImpactDetector` at all three sensitivities and count false positives and
   the confidence distribution.
5. Adjust `Tuning`, and commit the trace alongside it as a regression fixture.
6. Genuine collision data is the one thing this procedure cannot produce ethically. Insurance
   telematics datasets are the realistic source; until one is used, §8's first bullet stands.

## 10. What has been verified

| Claim | Status |
| --- | --- |
| Every stage behaves as specified — windowing, feature extraction, each discriminator, the confidence arithmetic, the cooldown | **Verified** — `ImpactDetectorTest`, 19 JVM tests, passing |
| Synthetic traces (pothole, speed bump, handling, braking, impact) classify as intended | **Verified** — `SensorTraceTest`, 16 JVM tests, passing |
| Boundary-straddling events protect both segments; in-progress segments count only to *now*; crash-interrupted events are recoverable | **Verified** — `ProtectionPlannerTest`, 25 JVM tests, passing |
| The thresholds are right for a real vehicle | **Not verified.** No vehicle, no phone, no drive traces. This is reasoning |
| Detection latency and protection under a real impact | **Not verified.** No device was available |
