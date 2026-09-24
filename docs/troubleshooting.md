# Troubleshooting

Start here: **Settings → Diagnostics**. It reports what the device actually said about itself —
camera and encoder capabilities, thermal signals, storage, the active recording profile with the
full reasoning behind it, and recent recorder events. Every value carries a provenance tag:

| Tag | Meaning |
| --- | --- |
| *(none)* | read straight from an Android API |
| `[measured]` | Roadguard measured it on this device |
| `[inferred]` | derived from other values |
| `[simulated]` | produced by the thermal test harness — **not a measurement** |
| `[not reported]` | the platform did not answer |

The **Export** button writes a text report to `diagnostics/`. It is never sent anywhere; sharing
it is your action.

---

## Recording

### Recording will not start

1. **Permissions.** Camera is required. Settings → Apps → Roadguard → Permissions.
2. **Storage.** If the Storage screen shows `Critical`, free space is below the reserve
   (`max(1 GiB, 4% of the volume)`, capped at 4 GiB) or the usable loop is under 256 MB.
   Roadguard will not start recording into a volume that has no headroom — see `docs/storage.md`.
3. **Another app has the camera.** Only one app can hold it. Close any other camera app.
4. **Diagnostics → Camera.** If no camera reports supported qualities, CameraX has not
   initialised; restart the app and check for a camera-service error in the recent events list.

### Recording did not start when I opened the app

**Settings → Startup → Start recording automatically** starts a recording when Roadguard is opened,
after the configured start-up delay. If it is on and nothing happens:

1. **Camera permission.** Auto-start is skipped silently when the camera permission is not held,
   so grant it first (Settings → Apps → Roadguard → Permissions).
2. **It starts once per launch.** Auto-start fires when the app is brought to the foreground on a
   fresh launch, not every time you return from another screen, and not after you have manually
   stopped a recording in the same session. Reopen the app to arm it again.
3. **It cannot start while the app is in the background** — see *Recording did not start on boot*
   below for why a camera service can only be promoted from a visible screen.

### Recording stops on its own

Check Diagnostics → recent recorder events first; the reason is recorded there.

| Cause | What you will see | Fix |
| --- | --- | --- |
| Storage exhausted and nothing left to trim | an insufficient-storage finalise error, and a Storage screen full of protected files | unprotect or export protected footage |
| Removable volume ejected | a source/volume error | re-seat the card. Roadguard deliberately does **not** silently fail over to internal storage — that would scatter one drive's footage across two devices |
| Five consecutive failures | recording stopped after backoff | the underlying error is in recent events; this is a guard against thrashing, not the cause |
| Android killed the process | recording simply ended | see "recording stops when the screen turns off" below |

Heat is **not** on this list. Roadguard never stops recording for thermal reasons — it reduces
quality instead. If recording stopped and Diagnostics shows a high thermal level, look for a
different cause.

### The Stop button is stuck on “Stopping”

Pressing Stop shows **Stopping** while the current clip is finalised, then returns to **Not
recording**. If it ever appeared to hang on *Stopping* forever, that was a state-machine defect:
the finished clip was already saved, but the recorder was parked in the transient *Stopping*
state and the chip and notification never cleared. It is fixed — a normal stop now settles on
*Not recording*.

The reason it mattered beyond cosmetics: a user who believes Stop did not work is likely to
force-stop the app, and force-stopping mid-clip is exactly what truncates the current segment
(see the next entry). A Stop that visibly completes avoids that.

### I recorded, but the files are not there

First, where the files are: Roadguard writes to its own app storage,
`Android/data/io.github.tunlezah.roadguard/files/recordings/` (`…roadguard.debug/…` for a debug
build). A `.nomedia` marker keeps thousands of clips out of the Photos/Gallery app, and on
Android 11+ many third-party file managers cannot browse `Android/data/` at all. **Browse the
recordings inside Roadguard** (the Recordings screen), over USB/MTP from a computer, or through
the app's Share action — not the phone's gallery.

If the in-app Recordings list itself is empty or missing a drive:

| What happened | Why | What you see |
| --- | --- | --- |
| The app was killed mid-clip | battery optimisation, a vendor "app sleep" list, swiping the app away on some launchers, or a power cut at ignition-off. An MP4 only becomes playable when its index is written **at the end** of the clip, so a clip cut off in the middle has no index | the interrupted clip is moved to `quarantine/` on the next start — the quarantine count in **Diagnostics → storage** rises — rather than shown as a normal recording. A drive shorter than one segment (default 3 min) that ends in a kill can leave *nothing* in the list |
| The encoder failed repeatedly | a device whose hardware encoder rejects the stream | each failed clip is quarantined and the reason is in the recent events list |
| A microSD card was chosen and is not mounted | the card was ejected or slow to mount | Roadguard stands down rather than dropping the index or scattering footage onto internal storage; re-seat the card |

Why a *long* drive can also lose everything: once the app is killed, recording does **not**
resume on its own. A camera service can only be started from a visible screen (the same reason
there is no boot auto-start), so after a kill Roadguard sits idle until you reopen it, even
though a foreground-service notification may still be showing. If the kill lands in the first
few minutes, before the first segment finalises, a 30-minute drive can end with a single
quarantined clip and nothing in the list. Reopen the app (or leave it in the foreground) and
confirm the notification says *recording*, not *not recording*, partway through the drive.

**To tell which of these is happening, open Settings → Diagnostics** and read three things:

* **Recordings folder** and **Recordings on disk** — the exact path clips are written to, and how
  many `.mp4` files and how many bytes are actually there. If this is non-zero but the in-app
  list is short, the files exist and the index lost them; if it is zero, nothing was written to
  that folder.
* **Segments indexed** — the database's count. A gap between this and *Recordings on disk* points
  at the index, not the recorder.
* **Startup reconciliation** — what the last start repaired, dropped or quarantined, and the
  per-clip verdict for each quarantined file. A verdict of *truncated: N bytes of video with no
  index* means that clip was being written normally and the app was killed mid-clip. An empty
  recordings folder with nothing quarantined and nothing dropped means recording never wrote
  anything — a different fault entirely (it never started, or wrote to another volume).

Export that report (the **Export** button) after a drive that lost footage; it is the fastest
way to say which fault you are looking at.

**The single most effective fix** for the first row — by far the most common — is to stop the
platform killing the app: Settings → Apps → Roadguard → Battery → **Unrestricted**, and remove
Roadguard from every vendor "deep sleep"/"app sleep" list. See *Recording stops when the screen
turns off* below; the same platform behaviour is behind both symptoms. Shorter segments (Settings
→ Recording) also shrink how much a single interruption can cost, because only the clip in
progress is ever at risk.

A quarantined clip is **not** deleted — the video data is usually all there, just without its
index. It can be recovered on a computer with a tool such as `untrunc` or `ffmpeg`. Roadguard
deliberately does not attempt that repair itself; doing it inside the recorder that is about to
record again would be riskier than keeping the file intact and saying so.

### Recording stops when the screen turns off

Roadguard is designed to keep recording with the screen off, and holds a partial wake lock to do
it. If it stops anyway, the platform is killing the process:

1. **Battery optimisation.** Settings → Apps → Roadguard → Battery → **Unrestricted**. This is
   the single most common cause on Motorola, Xiaomi, Samsung and Oppo devices. Roadguard
   deliberately does not request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — it explains where the
   switch is instead.
2. **Vendor "app sleep" / "deep sleep" lists.** Motorola, Samsung and Xiaomi all keep separate
   lists from Android's own. Remove Roadguard from all of them.
3. **Do not swipe the app away from Recents while recording.** The service is
   `stopWithTask="false"`, so it survives — but some vendor launchers kill the process anyway.
   Use the Home button.
4. Confirm the notification is still present. No notification means the foreground service is
   gone, which is a platform kill, not an app bug.

### Recording did not start on boot

It cannot, and that is a platform restriction rather than a missing feature. On targetSdk 35+ a
camera foreground service can only be promoted from a **visible Activity**; the camera grant is
latched at that moment. So "auto-start" means "start when you open the app", plus "start when
power is connected **while the app is open**".

Any dashcam app claiming true boot auto-start on a modern Android version is either using an
older targetSdk or not doing what it says.

### Quality is lower than expected

**Diagnostics → Recording profile → rationale** gives the whole chain in plain English: the
device tier and every reason for it, what Auto chose, whether thermal stepped it down, whether
the camera or encoder could not do what was asked, and any frame-rate cap.

Common answers:

* **Auto tops out at 1080p on every tier**, and at 720p on `Baseline`. This is deliberate:
  sustainable quality, not peak quality. Select a resolution manually to override it.
* **Thermal step-down.** `High` steps down one rung and caps 30 fps; `Critical` steps down two
  and caps 24 fps.
* **The camera or encoder said no.** The rationale names which.

### The video is sideways, or the wrong shape

Roadguard records the way the phone is held: portrait phone → portrait video, landscape phone →
landscape video, exactly like the stock camera app. There is no angle setting, by design.

* Rotation changes are debounced for 700 ms, so a wobble on a bumpy road does not flip the
  output.
* The rotation is latched at the **start of each segment**. Rotating the phone mid-segment
  applies from the next segment; that is CameraX's contract and it is why rotating never
  interrupts a recording.
* If the *preview* looks cropped but the *file* does not, that is correct — see the next entry.

### The preview looks cropped or zoomed

That is preview zoom, and it is display-only. The video panel is about half the window, so its
aspect ratio rarely matches the camera's; Auto scales the image to fill the panel up to 1.35×
and then stops rather than discarding more of the road scene, biasing what it keeps slightly
downward (a windscreen mount wastes the top of the frame on sky).

**None of it reaches the recording.** If you want the recorded frame narrowed, that is the
separate *recording* zoom under advanced settings, which defaults to 1.0× and warns you.

### There is a gap between segments

There is, and it is small but not zero. Rolling over requires stopping the video encoder and
starting a new file that begins with a fresh keyframe. Roadguard uses the minimum-gap path
CameraX offers (`stop()` then an immediate same-thread `start()`, which the `Recorder` queues),
but the gap is a property of the encoder, not of the app.

**The gap has not been measured on hardware.** No device was available. See `docs/testing.md`.

---

## Storage

### The loop is smaller than 5 GB

The Storage screen will say "limited by available space". The effective budget is
`min(your setting, loopUsed + free − reserve)`. Free space or export footage to get the full
budget.

### Protected footage is filling the volume

Protected files are never deleted by the loop — that is the point of them. Above 2 GB Roadguard
warns you; it will not resolve it for you, because that is your evidence. Gallery → select →
unprotect, or export and delete.

### A file will not play

Check Diagnostics → reconciliation report. If the file is in `quarantine/`, the MP4 inspector
found it structurally incomplete — almost always `TruncatedNoIndex`, meaning the video data is
probably all there but the muxer never wrote the `moov` index, so no ordinary player will open
it.

Roadguard **keeps** such files rather than deleting them, and does not attempt repair. Recovery
is possible with `untrunc` or with `ffmpeg`'s error-resilient demuxers on a desktop. The cause
is nearly always power loss or a process kill mid-write.

### Footage disappeared after an update or reinstall

Recordings live in app-specific external storage, so **uninstalling deletes them**. An in-place
update does not. Export anything you need to keep.

### How do I use the microSD card?

Settings → Storage → Volume. Roadguard lists what
`Context.getExternalFilesDirs()` reports; a card must be mounted as portable storage to appear.
Recordings already written stay where they are — Roadguard never moves video files.

---

## Map

### The map says the offline map is not installed

Installation needs a connection **once**. Settings → Map → Install. After that the map works
with no SIM, no mobile data and no Wi-Fi, forever.

| Message | Meaning |
| --- | --- |
| "needs an internet connection the first time" | connect and retry |
| "not available at the moment" | that region's file could not be fetched. Try another region; retrying the same one may not help |
| "not enough free space" | the archive is about 1.1 GB, and Roadguard checks the recorder's reserve first |
| "download could not be completed" | resumable; retry |
| "data was incomplete or corrupt" | the partial is discarded and re-downloaded |

Recording is unaffected by all of these. The recorder has no dependency on the map.

### The map is frozen or gone

Deliberate, at thermal level `High` (frozen) and `Critical` (torn down). It comes back on its
own once the device cools, after the 90-second de-escalation hold. Diagnostics shows the current
level and its source.

### The map is stuttering

Expected behaviour on a single-shader-core GPU under load, and the reason the style is cut to 18
layers. If it is bad enough to bother you, turn the map off (Settings → Preview) — the video
panel expands and the encoder gets the bandwidth back.

---

## Location and speed

### No speed shown

* Location permission not granted, or GPS disabled.
* No fix yet. A cold fix can take a minute or more, and longer with no network to supply A-GNSS
  data — which is the normal offline case.
* Windscreen coatings, especially athermal or heated glass, attenuate GNSS badly. Try a
  different mount position.
* Diagnostics → Location shows fix state, satellite count and accuracy.

### Speed jumps around

`SpeedFilter` discards implausible jumps and low-accuracy fixes. If it is still noisy, the fix
itself is poor — check the accuracy figure in Diagnostics.

---

## Events

### Nothing is detected

1. Check event detection is enabled (Settings → Events).
2. **Look at the near-misses in Diagnostics.** Rejected candidates are listed with their
   features, confidence and the reason for rejection — "vertical-dominated: looks like road
   surface", "too brief", "vehicle stationary", "phone appears to have been handled". That tells
   you which stage is rejecting, which is far more useful than guessing.
3. Raise sensitivity to High (1.8 g peak threshold, 0.40 confidence bar).
4. A loose cradle absorbs impacts. A firm mount is worth more than any setting.

### Too many false events

Lower sensitivity to Low (3.5 g, 0.62 bar). If speed bumps are still triggering, the near-miss
list will show a high vertical fraction — which is the case `minHorizontalFraction` exists to
reject, and it means the cradle is transmitting a lot of vertical energy. Check the mount.

### An event protected less footage than expected

`Incomplete` means the post-roll never finished — usually a process kill or a power loss right
after the impact. Roadguard closes the event with whatever footage exists and marks it honestly
rather than claiming a full window.

Note also that Roadguard is **not** a certified crash detector: it will miss some collisions and
protect some non-collisions. See `docs/event-detection.md` §8.

---

## Battery, heat and power

### The phone gets hot

Expected — see `docs/thermal-management.md`. Roadguard reduces work in four steps and never
stops recording for heat.

Practical mitigations: shade the phone, do not leave it in direct sun on the dash, mount it in
airflow from a vent, turn the map off, turn the screen off (dimming is on by default), and
prefer a lower resolution over 4K.

### High battery drain

Continuous video encoding plus a GPU-rendered map plus GNSS plus a lit display is close to the
worst case for a phone. Roadguard is designed to run on a charger. `PowerPolicy` reduces work
below 15 %, and Settings → Power offers four behaviours on disconnect.

### Recording stopped when I unplugged

Check Settings → Power → "On power disconnected". The default is **keep recording** — a loose
cable must not end a recording — but "stop", "stop after a delay" and "battery-safe profile" are
all available and one of them may be selected.

---

## Build and install

Covered in `docs/build.md`. The two things that bite most often:

* **`INSTALL_FAILED_UPDATE_INCOMPATIBLE`** — you have a build signed with a different key.
  `adb uninstall io.github.tunlezah.roadguard` first (this deletes the footage).
* **Bogus "unresolved reference" errors in files you did not touch** — two concurrent Gradle
  builds corrupted Kotlin's incremental cache. `rm -rf app/build/kotlin`, then use
  `tools/gradle-serial.sh`.

---

## Reporting a problem

Include:

1. The **Diagnostics export** (Settings → Diagnostics → Export). It carries the device, the
   capabilities, the active profile with its reasoning, and recent recorder events.
2. What you expected and what happened.
3. Whether the phone was hot, on a charger, and screen-on or screen-off.

Roadguard has no crash reporting and no telemetry, so a report is the only way anything reaches
a developer. That is the trade-off for `docs/privacy.md`.
