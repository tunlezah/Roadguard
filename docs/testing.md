# Testing

> **The most important sentence in this document:** Roadguard has never been run on a physical
> phone or on an Android emulator. No device was available during this work. Everything below is
> either a test that genuinely executes on the JVM — and the counts and results are real — or a
> manual procedure that somebody with hardware still has to perform.
>
> Nothing has been claimed as tested that was not. §5 lists, explicitly, what is untested.

---

## 1. What runs, and what it found

```bash
./gradlew :app:testDebugUnitTest
```

**416 tests. 0 failures. 0 errors. 0 skipped.** About 35 seconds of test execution on a warm
build.

| Suite | Tests | What it holds in place |
| --- | --- | --- |
| `RecordingProfileSelectorTest` | 44 | The whole Auto decision table: tier ceilings, thermal step-down, camera and encoder support, frame-rate caps, bitrate scaling, dual-camera and stabilisation gating, and the rationale strings |
| `SettingsValidationTest` | 33 | Every numeric setting clamps; no persisted value can put the recorder in an impossible state |
| `StorageBudgetTest` | 32 | Reserve arithmetic at both bounds, trim trigger and target, `keepNewest`, protected exclusion, zero and pathological budgets |
| `ThermalPolicyTest` | 25 | Immediate escalation, the 90 s de-escalation hold, single-step descent, signal priority, battery fallback only when nothing better exists |
| `ProtectionPlannerTest` | 25 | Overlap-not-containment, boundary-straddling events claiming both segments, in-progress segments counting only to *now*, crash-interrupted recovery |
| `MainChromeUiTest` | 24 | **Compose UI.** Start/stop mapping to recorder state, protect enabled across a rollover, every control's content description, status chips appearing and disappearing correctly |
| `PreviewFitTest` | 19 | Auto fill-to-panel, the 1.35× ceiling, road bias, crop and letterbox reporting, degenerate inputs |
| `ImpactDetectorTest` | 19 | Every detector stage: windowing, features, each discriminator, confidence arithmetic, cooldown |
| `SegmentPlannerTest` | 18 | Rollover reason priority, the 20-second minimum, queued reconfiguration |
| `SettingsComponentsUiTest` | 16 | **Compose UI.** Disabled rows still explaining themselves, the picker showing unsupported options greyed with a reason, sliders announcing values in words |
| `SensorTraceTest` | 16 | Synthetic pothole / speed-bump / handling / braking / impact traces classifying as intended |
| `DeviceTierScorerTest` | 16 | Every scoring combination, and both vetoes (`isLowRamDevice`, no hardware 1080p encoder) |
| `VideoOverlayChromeUiTest` | 16 | **Compose UI.** The display-only guarantee (§5) in both the caption and the accessibility description, and blocker messaging with and without an action |
| `SettingsRepositoryTest` | 12 | Round-tripping through DataStore, defaults, migration of absent keys |
| `PreviewFitTransformTest` | 14 | The preview fit as the viewfinder applies it: absolute uniform scale, the Auto ceiling, centring in the non-overflowing axis, the road bias only where there *is* vertical overflow, and the landscape geometry that used to clip the top and band the bottom |
| `LocationRequestsTest` | 16 | Shared ownership of the GNSS receiver: shortest interval wins, releasing one client leaves the others running, and thermal throttling of the recorder cannot slow the map down |
| `OverlayLayoutTest` | 18 | The burned-in overlay never draws one label over another: every ladder resolution in both orientations, all 63 non-empty field combinations, two font metrics, hostile strings, and the arrangement/shrink fallbacks |
| `PmtilesArchiveTest` | 18 | PMTiles v3 header parsing, and rejection of wrong-schema, raster, truncated, unsupported-version and too-coarse archives — each with a stated reason |
| `MapAssetTest` | 12 | The shipped styles and map catalogue: the style/installer source-layer contract, asset-only glyphs and sprites, the layer budget, day/night structural parity, and every catalogue entry's size, zoom, URL and licence |
| `ThemeUiTest` | 11 | **Compose UI.** All four themes; OLED being true black in every surface role and still true black under dynamic colour |
| `RoadguardWindowInfoTest` | 7 | The pane-arrangement rule, including ties, one-dp differences, freeform and foldable windows |
| `RoadguardColorContrastTest` | 5 | Status colours meeting contrast against their own backgrounds, in every palette |

## 2. Why the policy layer is testable at all

`ThermalPolicy`, `StorageBudget`, `RecordingProfileSelector`, `DeviceTierScorer`,
`ImpactDetector`, `ProtectionPlanner`, `SegmentPlanner`, `PreviewFit`, `SpeedFilter` and
`PowerPolicy` import **nothing** from `android.*`. They take value types in and return value types
out.

That is the deliberate architectural choice that makes 264 pure-policy tests possible without a
device, and it means the questions a dashcam actually gets wrong — *when* does it delete, *when*
does it reduce quality, *which* segments does an event protect, *is* that spike a collision — are
all answered by code that is exhaustively exercised on every push.

## 3. Compose UI tests that run on the JVM

67 of the 416 are real Compose UI tests: they compose the production composables, read the
semantics tree, and perform clicks. They live in `src/test` under Robolectric rather than in
`src/androidTest`, which is a deliberate trade:

**What that buys.** They run on every CI push, in seconds, with no emulator. UI regressions in
the parts that matter — a stop button that says "start", a lost content description, a caption
that stops saying "recording is not cropped" — are caught by the same job that runs the unit
tests.

**What it costs.** Robolectric draws nothing. There are no real font metrics, no real pixels, no
real gesture timing and no GPU. So these tests verify **semantics, structure and behaviour**, not
appearance. A layout could be visually broken and still pass.

Three specification requirements are pinned by these tests in particular:

* **§5, the display-only guarantee.** `VideoOverlayChromeUiTest` asserts that the caption reads
  "display only, recording is not cropped" whether or not the preview is currently cropping, and
  that the zoom control's content description also says "Display only". This is the requirement
  most likely to rot silently, because nothing breaks when the wording drifts.
* **§41/accessibility.** Status chips deliberately collapse to a single accessibility node
  (`clearAndSetSemantics`), which means the content description *is* the contract. Every chip's
  description is asserted verbatim.
* **§42, four themes.** `ThemeUiTest` asserts OLED is `Color.Black` in every surface role, that
  it keeps Dark's content colours and status palette, and that it stays black even with dynamic
  colour on — so OLED cannot decay into "dark with different greys".

## 4. Lint

```bash
./gradlew :app:lintDebug        # abortOnError = true
./gradlew :app:lintVitalRelease
```

Clean against a baseline of exactly four reviewed categories, so a *new* lint error fails the
build. `docs/build.md` §10 lists each category and why it is there.

Five `RestrictedApi` errors were found during development and **fixed rather than suppressed** —
each was a genuine API-hygiene bug that would have broken on a CameraX upgrade
(`cameraInfo.cameraState` in place of the restricted `addCameraStateListener`,
`setSurfaceProvider(null)` in place of a restricted preview-disable path, and the removal of
`setRequiredFreeStorageBytes`).

## 5. What is **not** tested

### 5.1 No instrumentation tests exist

`src/androidTest` is empty. The CI workflow has an opt-in emulator job
(`.github/workflows/android.yml`, `workflow_dispatch` only) that runs
`connectedDebugAndroidTest` — **and it currently has nothing to run.** That is stated here rather
than left to be discovered.

Instrumentation tests are the right tool for the paths that need a real filesystem, a real Room
database or real hardware, and those paths are consequently unverified end to end:

| Path | Why it needs a device or emulator |
| --- | --- |
| `StorageReconciler`'s five repair cases | needs a real filesystem and a real Room database |
| `Mp4Inspector` against real files | needs files a real muxer wrote, including a genuinely truncated one |
| Map download → install → render | needs the network, the filesystem and a GPU. The *verification* step is now unit tested (`PmtilesArchiveTest`); the download, install and render steps are not |
| `RecordingController`'s segment loop | needs a camera |
| The foreground service surviving screen-off | needs a device; an emulator does not model vendor process-killing |
| GPX writing, `FileProvider` sharing | needs a filesystem and another app to share to |

### 5.2 Nothing was run on hardware or an emulator

Not once. In particular, **none** of the following has been observed:

* the app launching;
* a single frame being recorded;
* a segment rollover, or the size of its gap;
* the loop deleting an old segment;
* an event being detected and footage protected;
* the map rendering from the PMTiles archive;
* recording continuing with the screen off;
* the thermal ladder responding to real heat;
* any battery, frame-rate or throughput figure.

`docs/benchmarking.md` §4 lists these as gaps and offers **no estimates** for any of them.

### 5.3 No screenshots

`docs/screenshots/` contains a procedure, not images. Fabricating a screenshot of an app that has
never been run would be a straightforward lie about the product, so none exists. See
`docs/screenshots/README.md`.

### 5.4 Thermal figures are simulated, never measured

The thermal harness produces five named scenarios, and everything it produces is tagged
`[simulated]` from the reading through the diagnostics screen to the exported report — where the
report's first line says so in words. **No physical thermal validation was performed.**
`docs/thermal-management.md` §7 is the procedure for doing it.

### 5.5 Event thresholds have no real drive data

`SensorTraceTest`'s traces are synthetic — constructed from the physics the discriminators are
designed around. They prove the classifier behaves as designed on inputs shaped like the ones it
expects. They do not prove those shapes match a real car, a real cradle or a real collision.
`docs/event-detection.md` §9 is the procedure.

## 6. Manual test plan for someone with a device

In priority order. Items 1–4 are the ones that would find a real bug fastest.

### 6.1 Recording reliability

1. **Two-hour continuous recording** on Auto, map on, screen on, on a charger, in the sun.
   Success is: no gap in the segment sequence, no truncated file, no stall. Capture a Diagnostics
   export every 5 minutes.
2. **Same with the screen off** after two minutes. This is the realistic case and the one where
   the wake lock and the FGS camera grant are load-bearing.
3. **Segment continuity.** Record a stopwatch or a flashing LED across several rollovers and count
   the missing frames.
4. **Rotate the phone mid-recording** repeatedly. Expect: the rotation applies from the next
   segment, no interruption, no truncated file, correct orientation in every file.
5. **Kill the app while recording** (`adb shell am force-stop`). Restart. Expect: the in-progress
   file is either indexed as playable or quarantined with a stated verdict, never silently lost.
6. **Pull the charger** while recording, with each of the four disconnect behaviours.

### 6.2 Preview versus recording

7. Cycle every preview-zoom value while recording. **Open the resulting files on a desktop and
   confirm the framing is identical across all of them.** This is the §5 guarantee, and it is the
   one test only a device can perform.
8. Set recording zoom to 1.5×, record, and confirm the file *is* narrower — the two controls must
   be visibly different things.
9. Check the preview in both orientations, with the map on and off, and confirm the caption
   matches what is on screen.

### 6.3 Storage

10. Fill the volume to just above the reserve and start recording. Expect a refusal with a clear
    reason, not a failure mid-recording.
11. Let the loop reach its budget and confirm the oldest unprotected segment is deleted and the
    newest two never are.
12. Protect footage until it exceeds 2 GB and confirm the warning.
13. **Eject a microSD card mid-recording.** Expect a clear error and no fail-over to internal
    storage.
14. Delete the Room database file and restart. Expect every file adopted and protection restored
    from sidecars.

### 6.4 Events

15. Firm taps on the cradle at each sensitivity, watching the near-miss list — this calibrates
    intuition for what the detector sees.
16. Speed bumps at normal road speed. Expect rejections labelled "vertical-dominated".
17. Pick the phone up while recording. Expect a rejection labelled "phone appears to have been
    handled".
18. Manual protect during a rollover, then verify both adjacent segments are protected.

### 6.5 Thermal

19. The 90-minute procedure in `docs/thermal-management.md` §7, per device and per profile.
20. Drive the harness through all five scenarios and confirm every mitigation applies, that
    recording never stops, and that reconfiguration only lands on a segment boundary.

### 6.6 Offline map

21. First-run install on Wi-Fi. Then **aeroplane mode, with the SIM removed**, and confirm the map
    still renders and follows the vehicle.
22. Kill the app mid-download and restart. Expect a resume, not a restart.
23. Corrupt the installed archive and restart. Expect a clear failure and a re-install path.

### 6.7 Permissions and privacy

24. Deny every permission and confirm each blocker message is specific and offers its action.
25. **Confirm the microphone permission is never requested until microphone recording is enabled.**
26. Capture the app's traffic with a proxy for an hour of recording with weather off. Expect
    **zero** requests. With weather on, expect only rounded-coordinate calls to
    `api.open-meteo.com`.

### 6.8 UI

27. Every screen in portrait and landscape, in all four themes, at the largest system font scale
    and with display size at maximum.
28. TalkBack over the whole driving screen.
29. Rotate on every screen and confirm no state is lost.

## 7. How to add a test

* **A decision** — a threshold, a ladder, an arithmetic rule — goes in the pure policy layer and
  gets a JVM unit test. If it is hard to test, it is in the wrong layer.
* **A UI contract** — a content description, a state-to-control mapping, a caption that must not
  drift — goes in `src/test` as a Robolectric Compose test, following the four existing files.
  Note that Compose's test rule allows **one `setContent` per test**; compose all the states you
  need in a single composition, or split the test.
* **Anything needing a real filesystem, database, camera or GPU** goes in `src/androidTest`. The
  emulator job is already wired and waiting for its first test.
