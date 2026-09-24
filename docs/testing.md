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

**610 tests. 0 failures. 0 errors. 0 skipped.** About 50 seconds of test execution on a warm
build.

| Suite | Tests | What it holds in place |
| --- | --- | --- |
| `RecordingProfileSelectorTest` | 54 | The whole Auto decision table: tier ceilings, thermal step-down, camera and encoder support, frame-rate caps, bitrate scaling, dual-camera and stabilisation gating, and the rationale strings; the battery-safe 720p30 ceiling; and the safe fallbacks a refused camera configuration drops to |
| `SettingsValidationTest` | 36 | Every numeric setting clamps; no persisted value can put the recorder in an impossible state; the removed GPS modes map onto today's two settings |
| `StorageBudgetTest` | 32 | Reserve arithmetic at both bounds, trim trigger and target, `keepNewest`, protected exclusion, zero and pathological budgets |
| `ThermalPolicyTest` | 25 | Immediate escalation, the 90 s de-escalation hold, single-step descent, signal priority, battery fallback only when nothing better exists |
| `ProtectionPlannerTest` | 25 | Overlap-not-containment, boundary-straddling events claiming both segments, in-progress segments counting only to *now*, crash-interrupted recovery |
| `PlaceRankingTest` | 14 | Which suburb, town or city a coordinate is named after, against the places decoded from the real archives around Canberra: the 3/8/20 km radii, population settling Canberra over Queanbeyan, towns never standing in for cities, duplicate collapse |
| `MainChromeUiTest` | 27 | **Compose UI.** Start/stop mapping to recorder state — including Stop during the start-up countdown and while reconnecting — protect enabled across a rollover and while reconnecting, every control's content description, status chips appearing and disappearing correctly |
| `PreviewFitTest` | 19 | Auto fill-to-panel, the 1.35× ceiling, road bias, crop and letterbox reporting, degenerate inputs |
| `ImpactDetectorTest` | 19 | Every detector stage: windowing, features, each discriminator, confidence arithmetic, cooldown |
| `SegmentPlannerTest` | 18 | Rollover reason priority, the 20-second minimum, queued reconfiguration |
| `RecordingControllerStopTest` | 5 | A user-initiated stop settles on *Idle*, never parking in the transient *Stopping* state; a second stop is harmless; a Stop pressed straight after Start wins; a start with no recording service fails visibly; a shutdown with nothing recording changes nothing |
| `PowerPolicyTest` | 24 | The low-battery stop and its charging exemption, the power-connect and disconnect behaviours, when battery-safe mode applies, that it only ever tightens the thermal plan and leaves the overlay alone, and the one-minute debounce against a flickering charger |
| `RecoveryPolicyTest` | 16 | Recovery never gives up: the fast-then-slow retry schedule, forced rebinds, the five-minute wake-lock budget, the 30-second alert, and what each kind of failure asks for |
| `RecordingNotificationContentTest` | 9 | What the recording notification says and offers in every state, including *reconnecting* and *stopped*, and that progress alone never re-posts it |
| `RecordingUiStateTest` | 7 | When the wake lock is held (until the last file is closed), when a session counts as active, and when Protect is offered |
| `ForegroundServiceTypesTest` | 5 | The service claims location and microphone only with both the setting and the permission, so a declined permission can never stop recording |
| `MapWorkBudgetTest` | 5 | The thermal and battery-safe map budgets really reduce map work: no animation when reduced, off screen when frozen |
| `SessionJournalTest` | 4 | **Robolectric.** An interrupted session survives a process restart and produces exactly one resume prompt; a deliberate stop produces none |
| `SettingsComponentsUiTest` | 16 | **Compose UI.** Disabled rows still explaining themselves, the picker showing unsupported options greyed with a reason, sliders announcing values in words |
| `SensorTraceTest` | 16 | Synthetic pothole / speed-bump / handling / braking / impact traces classifying as intended |
| `DeviceTierScorerTest` | 16 | Every scoring combination, and both vetoes (`isLowRamDevice`, no hardware 1080p encoder) |
| `VideoOverlayChromeUiTest` | 16 | **Compose UI.** The display-only guarantee (§5) in both the caption and the accessibility description, and blocker messaging with and without an action |
| `SettingsRepositoryTest` | 12 | Round-tripping through DataStore, defaults, migration of absent keys |
| `PreviewFitTransformTest` | 14 | The preview fit as the viewfinder applies it: absolute uniform scale, the Auto ceiling, centring in the non-overflowing axis, the road bias only where there *is* vertical overflow, and the landscape geometry that used to clip the top and band the bottom |
| `LocationRequestsTest` | 16 | Shared ownership of the GNSS receiver: shortest interval wins, releasing one client leaves the others running, and thermal throttling of the recorder cannot slow the map down |
| `OverlayLayoutTest` | 18 | The burned-in overlay never draws one label over another: every ladder resolution in both orientations, all 63 non-empty field combinations, two font metrics, hostile strings, and the arrangement/shrink fallbacks |
| `TripAssemblerTest` | 10 | The 2-minute gap rule that groups clips into trips and continues a trip after a relaunch, at the boundary, out of order, and with clocks that disagree |
| `GalleryTripUiTest` | 10 | **Compose UI.** A trip card names the drive and its counts, hides its clips until asked, offers the track buttons only when a track exists, protects a whole trip from its menu, and still shows clips no trip has claimed |
| `PmtilesReaderTest` | 9 | PMTiles tile ids per the spec, tile coordinates, and reading tiles through root and leaf directories and run-length entries of a hand-built archive, then decoding its `places` layer |
| `GpxWriterTest` | 9 | The track is a valid GPX document before any point and after each one, reopens for appending, renames both `<name>` elements atomically, and reads back thinned for the route thumbnail |
| `TripNamingTest` | 9 | Suburbs by default, cities between cities, towns alone in the country, loops, unknown ends and the time-based fallback |
| `TrackPointFilterTest` | 8 | Five-metre movement, the 30 s stationary heartbeat that counts no distance, poor fixes ignored |
| `RouteSketchTest` | 4 | Track points fitted into the unit square with their real proportions |
| `RoadguardMigrationTest` | 2 | The 1→2 migration validated against the exported schemas, keeping an existing clip and adding the trip columns |
| `PmtilesArchiveTest` | 18 | PMTiles v3 header parsing, and rejection of wrong-schema, raster, truncated, unsupported-version and too-coarse archives — each with a stated reason |
| `MapAssetTest` | 12 | The shipped styles and map catalogue: the style/installer source-layer contract, asset-only glyphs and sprites, the layer budget, day/night structural parity, and every catalogue entry's size, zoom, URL and licence |
| `ThemeUiTest` | 11 | **Compose UI.** All four themes; OLED being true black in every surface role and still true black under dynamic colour |
| `RoadguardWindowInfoTest` | 7 | The pane-arrangement rule, including ties, one-dp differences, freeform and foldable windows |
| `RoadguardColorContrastTest` | 5 | Status colours meeting contrast against their own backgrounds, in every palette |

## 2. Why the policy layer is testable at all

`ThermalPolicy`, `StorageBudget`, `RecordingProfileSelector`, `DeviceTierScorer`,
`ImpactDetector`, `ProtectionPlanner`, `SegmentPlanner`, `PreviewFit`, `SpeedFilter`,
`PowerPolicy` and `RecoveryPolicy` import **nothing** from `android.*`. They take value types in and return value types
out.

That is the deliberate architectural choice that makes 458 plain-JVM tests possible without a
device, and it means the questions a dashcam actually gets wrong — *when* does it delete, *when*
does it reduce quality, *which* segments does an event protect, *is* that spike a collision — are
all answered by code that is exhaustively exercised on every push.

## 3. Compose UI tests that run on the JVM

80 of the 610 are real Compose UI tests: they compose the production composables, read the
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
| `RecordingController`'s segment loop, recovery and stall watchdog | needs a camera. The recovery schedule, the failure classification, the wake-lock rule and the notification wording are unit tested (`RecoveryPolicyTest`, `RecordingUiStateTest`, `RecordingNotificationContentTest`), and so is the ordering of Stop against Start (`RecordingControllerStopTest`) |
| The resume prompt after a process kill, and closing the clip at shutdown | need a real kill and a real shutdown broadcast; the journal behind the prompt is tested (`SessionJournalTest`) |
| The foreground service surviving screen-off | needs a device; an emulator does not model vendor process-killing |
| Opening or sharing a GPX track in another app | needs another app to receive it. The writer itself, the point filter and the renaming are unit tested (`GpxWriterTest`, `TrackPointFilterTest`) |
| `TripRepository`, the reconciler's four trip steps, `StorageManager.pruneEmptyTrips` | need a real Room database and filesystem end to end; the rules they apply (`TripAssembler`, `TripNaming`, `PlaceRanking`) are unit tested |
| Place lookup against the real archive on a phone | the reader and decoder are tested against a hand-built archive, and decoded the published archives during research over HTTP; nobody has run them on a device |

### 5.2 Nothing was run on hardware or an emulator

Not once. In particular, **none** of the following has been observed:

* the app launching;
* a single frame being recorded;
* a segment rollover, or the size of its gap;
* the loop deleting an old segment;
* an event being detected and footage protected;
* the map rendering from the PMTiles archive;
* recording continuing with the screen off;
* recording recovering after another app takes the camera, or a card is removed and reinserted;
* battery-safe mode's effect on real power draw;
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
13. **Eject a microSD card mid-recording.** Expect *Reconnecting* with the card message, no
    fail-over to internal storage, and recording to resume by itself once the card is reinserted.
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

### 6.9 Trips and tracks

30. Record two short drives with recording stopped for **more than two minutes** between them, and
    a third started **within two minutes** of the second ending. Expect two trip cards, the third
    drive folded into the second.
31. With the offline map installed, expect each trip named from its ends: suburbs across town
    ("Harrison → Braddon" with "Canberra" underneath), cities when the drive crosses between two.
    Note any trip whose name reads wrong, with its start and end coordinates: the radii in
    `PlaceRanking` are the knob.
32. Uninstall the map, record a trip, expect a time-based title and the hint; reinstall the map,
    open Recordings, expect the name to appear without any further action.
33. Tap **Open GPX track** with CoMaps or Organic Maps installed. Expect the chooser to offer it and
    the track to open under the trip's name. Uninstall both and tap again: expect the share sheet
    and the explanatory message, not an empty chooser.
34. Kill the app mid-drive (`adb shell am force-stop`) and relaunch within two minutes. Expect the
    same trip to continue and its GPX file to grow rather than a second file appearing.
35. Turn **Save a GPX track of each trip** off mid-drive and on again. Expect the track to stop
    growing and then resume into the same file.
36. Delete every clip of a trip. Expect the trip card and its `.gpx` file to disappear together;
    delete all but one protected clip and expect both to remain.
37. Upgrade an install that has clips from before this version. Expect them grouped into trips on
    the next start, with names once the map is present, and no clip missing from the list.

### 6.10 Recovery, shutdown and battery

38. Open the stock camera app, or start a video call, for ten seconds while recording, then close
    it. Expect *Reconnecting* at once, recording back within a couple of seconds of the other app
    closing, and the clip from before the interruption playable.
39. Hold the camera the same way for more than 30 seconds. Expect one "Recording interrupted"
    alert, cleared once recording has run cleanly again for about 20 seconds.
40. Leave another app holding the camera for ten minutes, screen off, unplugged. Expect Roadguard
    to stop holding its wake lock after five minutes (`adb shell dumpsys power | grep Roadguard`),
    and to resume within about a minute of the screen coming on with the camera free.
41. Kill the process while recording. For a debug build:
    `adb shell run-as io.github.tunlezah.roadguard.debug kill -9 <pid>`. (`am force-stop` does not
    exercise this, because it also cancels the service restart.) Expect a "Recording was
    interrupted" notification within a few seconds, and a tap on it to reopen the app and start
    recording.
42. Switch the phone off mid-clip. After it restarts, expect that clip to be playable rather than
    quarantined.
43. Turn Battery Saver on while recording. Expect the notification to say *Battery-safe mode*, the
    next clip to be 720p30 or lower, and the screen to be allowed to time out. Then toggle Battery
    Saver every ten seconds for two minutes: expect no profile change at all.
44. Revoke the location permission in system settings, reopen Roadguard with location still on in
    its settings, and start recording. Expect recording to start without location, not a crash
    or a silent failure.

## 7. How to add a test

* **A decision** — a threshold, a ladder, an arithmetic rule — goes in the pure policy layer and
  gets a JVM unit test. If it is hard to test, it is in the wrong layer.
* **A UI contract** — a content description, a state-to-control mapping, a caption that must not
  drift — goes in `src/test` as a Robolectric Compose test, following the five existing files.
  Note that Compose's test rule allows **one `setContent` per test**; compose all the states you
  need in a single composition, or split the test.
* **A schema change** ships with a migration in `RoadguardMigrations` and a case in
  `RoadguardMigrationTest`, which validates it against the JSON Room exports under
  `app/schemas/` (copied into the debug build's assets for exactly this purpose).
* **Anything needing a real filesystem, database, camera or GPU** goes in `src/androidTest`. The
  emulator job is already wired and waiting for its first test.
