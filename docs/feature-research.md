# Feature research and defaults

This document answers two questions: **what does a dashcam need to do**, and **where does each
of Roadguard's defaults come from**.

> **Provenance note, stated up front.** The background research agent assigned to survey
> commercial dashcam behaviour (`docs/research/dashcam-feature-survey.md`) died before producing
> anything, so this document does **not** rest on a systematic survey of Nextbase, Viofo,
> BlackVue, Garmin or the Android dashcam apps. Every default below is labelled with its actual
> basis: **specification** (the product brief mandated it), **platform** (Android or CameraX
> left no choice), or **reasoned** (a judgement made here, with the reasoning given). Nothing is
> attributed to a survey that was not done.

---

## 1. The feature set, and Roadguard's position on each

### Implemented

| Feature | Notes |
| --- | --- |
| Segmented loop recording | 1 / 3 / 5 / 10-minute segments, oldest-first deletion |
| Automatic quality selection | from a runtime capability probe, never a model name |
| Correct orientation | portrait phone → portrait video, landscape → landscape |
| Burned-in overlays | date/time, speed, coordinates, weather — each independently toggleable |
| On-screen HUD | separate from the burned-in overlay; costs nothing |
| Moving offline map | whole-of-Australia, no network after install |
| Impact detection | multi-stage, three sensitivities |
| Hard-braking detection | classified separately from impact, and described as such |
| Manual protect | one tap, bypasses every classifier |
| Pre/post-event protection | 30 s / 60 s by default, configurable |
| Thermal management | a four-level ladder with its own engine |
| Storage safety | a reserve the loop cannot spend, and a floor under deletion |
| Start-up repair | five defined divergence cases, biased toward keeping footage |
| In-app gallery and player | Media3, with protect/unprotect and share |
| GPX track export | off in the default GPS mode |
| Diagnostics with provenance | every value tagged measured / inferred / simulated / not reported |
| Four themes | Light, Dark, System, OLED-black |
| Power-event actions | start on power connected; four choices on disconnect |
| Battery-safe threshold | reduce work below 15 % |
| Microphone recording | **off by default**, permission requested only when enabled |
| Preview zoom | display-only, 1.0×–2.0×, default Auto |
| Recording zoom | separate, default 1.0×, warns about lost field of view |
| Weather | optional, off by default, Open-Meteo |

### Deliberately not implemented

| Feature | Why not |
| --- | --- |
| **Cloud upload / backup / accounts** | Forbidden by the specification, and structurally absent — there is no upload code to disable. See `docs/privacy.md` |
| **Analytics, crash reporting, telemetry** | Same. CI fails the build if such a dependency appears |
| **Parking mode** | Requires the phone to wake on motion with the screen off and the app not running. On targetSdk 35+ there is no reliable way to start a camera foreground service from a background trigger (`docs/research/android-platform-restrictions.md`), so a "parking mode" would be a feature that silently does not work. Shipping nothing beats shipping a promise |
| **Auto-start on boot** | Same root cause. §1.4 of `docs/research.md`: the camera grant is latched from a *visible* Activity |
| **Speed-camera or red-light alerts** | Needs a maintained database Roadguard has no lawful, free, offline source for. An out-of-date alert is worse than none |
| **Lane-departure / forward-collision warning** | A real-time vision model on a Mali-G57 MP1, competing with the encoder for bandwidth, would put recording reliability at risk for a feature that would not work well. Priority 1 is recording |
| **Codec selection** | CameraX 1.6 provides no mechanism. See `docs/architecture.md` §9 |
| **HDR recording** | Narrows encoder support and complicates playback of evidence footage for no evidential gain |
| **Time-lapse / hyperlapse** | Not evidence. Out of scope |
| **Cloud-free "emergency SMS on crash"** | Would need contacts, SMS permission and a reliability guarantee Roadguard cannot make. Roadguard is not a crash-notification system and says so |

## 2. Where each default comes from

### Recording

| Setting | Default | Basis |
| --- | --- | --- |
| Quality | **Auto** | specification (§8) |
| Frame rate | **Auto** (→ 30 fps) | reasoned: 30 fps is the universal hardware-encoder sweet spot; 60 fps doubles heat and storage for no evidential gain, and 24 fps risks legibility of number plates on a moving vehicle |
| Segment length | **3 minutes** | specification (§7) |
| Camera | Rear | reasoned: it is a dashcam |
| Dual camera | Off | reasoned: needs a `Capable` tier *and* platform-reported concurrency; doubles the encode load |
| Stabilisation | Auto (→ off below `Capable`) | reasoned: a cradle already removes hand shake, and EIS crops the frame and costs power |
| Night assist | Auto | reasoned |
| Recording zoom | **1.0×** | specification (§6) — anything else permanently narrows the recorded field of view |
| Microphone | **Off** | specification (§29), reinforced by Australian audio-recording law varying by state |

### Preview and UI

| Setting | Default | Basis |
| --- | --- | --- |
| Preview zoom | **Auto** | specification (§5) |
| Map visible | On | reasoned: the specification's 50/50 layout implies the map is the point |
| Theme | Follow system | reasoned |
| Dynamic colour | **Off** | reasoned: a wallpaper-derived palette can produce poor contrast, and this app is read at a glance while driving. Available, not default |
| Orientation | Follow device | specification (§3) |
| Keep screen on | On | reasoned: a dashcam whose screen sleeps looks broken; the thermal ladder handles the cost |
| Screen-off dimming | On | reasoned |

### Overlays

| Setting | Default | Basis |
| --- | --- | --- |
| Date/time | **On** | reasoned: the single most valuable thing to have burned into evidence footage |
| Speed | **On** | reasoned: high value, and not identifying on its own |
| Coordinates | **Off** | reasoned: burning a position into every frame is irreversible, and a shared clip then carries it forever. High privacy cost, situational value |
| Weather | Off | reasoned: decoration, and it is the only overlay that requires a network call |

### Events

| Setting | Default | Basis |
| --- | --- | --- |
| Detection enabled | On | specification |
| Sensitivity | **Medium** | reasoned: see `docs/event-detection.md` §3 for the reasoning behind each threshold |
| Pre-event | **30 s** | specification (§25) |
| Post-event | **60 s** | specification (§25) |

### Storage

| Setting | Default | Basis |
| --- | --- | --- |
| Loop budget | **5 GB** | specification (§27) |
| Protected-footage warning | 2 GB | reasoned: enough to notice before protected files crowd the loop |
| Volume | Default (built-in) | reasoned: removable is offered, not assumed |

At 5 GB the loop holds roughly 4 hours at 720p30 or 2 hours at 1080p30 by the app's bitrate
model — which is a comfortable commute and a thin road trip. `docs/benchmarking.md` §2 gives the
arithmetic and is explicit that real device bitrates may halve those figures, which is why the
Storage screen reports a *measured* rate instead.

### Location, power, map

| Setting | Default | Basis |
| --- | --- | --- |
| Location | On | reasoned; the app works fully without the permission |
| Speed unit | km/h | reasoned: Australia |
| GPS storage | Overlay + metadata | reasoned: keeps position with the footage, no separate track file to leak, no coordinates burned into pixels |
| On power connected | Start recording | reasoned: matches how a dashcam is used — cradle it, plug it in, drive |
| On power disconnected | Keep recording | reasoned: a loose cable must not end a recording. Three alternatives offered |
| Stop delay | 300 s | reasoned |
| Battery-safe below | 15 % | reasoned: matches Android's own low-battery point |
| Map follows vehicle | On | reasoned |
| North-up | Off | reasoned: heading-up is easier to read while driving |
| Auto-download map | On | specification (§16–18) — *"Do NOT assume the user will add map files"* |
| Startup delay | 3 s | reasoned: long enough to cancel by accident-proofing, short enough not to lose the start of a drive |

## 3. Priorities, and what they cost

The specification's priority order settled several arguments before they started. Where a
trade-off existed, the higher priority won, visibly:

| Priority | Where it wins something else |
| --- | --- |
| 1. Recording reliability | Auto caps at 1080p even on fast hardware; no parking mode rather than a broken one; no vision features competing with the encoder |
| 2. Thermal management | the map is frozen and then destroyed before recording quality is touched; `Elevated` costs the user nothing |
| 3. Storage safety | 1 GiB minimum reserve; `keepNewest = 2`; a map download checks the recorder's reserve first |
| 4. Event protection | overlap-not-containment segment selection; protection survives loss of the database; manual protect outranks every classifier |
| 5. Correct orientation | no invented angle system — `snapToSurfaceRotation` and nothing more |
| 6. Offline operation | glyphs and sprites in the APK, no ambient cache, no prefetch; recording never waits for the network |
| 7. Low-end hardware | 18-layer map style; tier vetoes; software encoding never predicted |
| 8. Professional UI | stock Material icons, four themes, adaptive layout from real window metrics |
| 9. Automatic optimisation | every profile decision carries its reasoning to Diagnostics |
| 10. Reproducible builds and docs | pinned wrapper checksum, one version catalogue, generated assets re-derivable from scripts |

## 4. What a real survey would still be worth

The commercial-dashcam survey is the one piece of missing research whose absence would most
change this document. Specifically, it would settle:

* whether 3 minutes is the right segment default, or whether 1 minute (easier to share, more
  files) or 5 (fewer boundaries) is the industry norm for a reason;
* what g-force thresholds shipping dashcams actually use, and at what mount stiffness;
* whether users expect protected footage to be *moved* to a separate folder (Roadguard
  deliberately does not move it — see `docs/storage.md` §5) and whether that expectation matters;
* how commercial devices present the "audio is off" decision, which is a legal question as much
  as a UX one.

None of those is a blocker. All of them are worth knowing, and none of them is claimed to be
known here.
