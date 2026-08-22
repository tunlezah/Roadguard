# Storage

A dashcam writes continuously for hours and must never fill the phone. Roadguard's storage
design has three jobs, in this order: **do not lose footage**, **do not fill the device**, and
**survive being killed at the worst possible moment**.

---

## 1. Where files live

Everything sits under the app's own external files directory —
`Android/data/io.github.tunlezah.roadguard/files/…` — reached through
`Context.getExternalFilesDirs()`.

| Directory | Contents |
| --- | --- |
| `recordings/` | loop segments (`.mp4`) and protection sidecars (`.protected.json`) |
| `quarantine/` | files the inspector could not verify — kept, never deleted |
| `tracks/` | GPX tracks, when the user has enabled track storage |
| `diagnostics/` | exported diagnostics reports |
| `maps/` | the installed offline map archive |
| `.nomedia` | keeps thousands of loop segments out of the user's gallery |

Why this location and not `MediaStore` or a user-chosen SAF tree:

* **no storage permission** is needed, on any supported API level;
* the recorder's write path is a plain `File`, with no SAF round trips — on a low-end device
  those round trips are a real cost on a hot path;
* it can be placed on a **removable volume** just by choosing a different
  `getExternalFilesDirs()` entry, which is how the Moto G04's microSD slot is used;
* thousands of three-minute clips do not pollute the user's photo gallery, while remaining
  reachable over USB/MTP and shareable through the app's `FileProvider`.

The trade-off is real and is stated in the app as well as here: **uninstalling Roadguard
deletes the footage.** Export anything you want to keep first.

## 2. The reserve — how Roadguard avoids filling the phone

Two hard facts set the floor:

* CameraX's `Recorder` **aborts a recording with `ERROR_INSUFFICIENT_STORAGE` once free space
  falls below 50 MiB** (`docs/research/camera-pipeline.md` §13). Anything near that number is
  already too late.
* Android's own low-storage warning fires in the same neighbourhood as a few percent of the
  volume, and a device in that state misbehaves in ways that have nothing to do with Roadguard.

So the reserve — space Roadguard will never spend, whatever the user's settings say — is:

```
reserve = clamp(4% of volume, min = 1 GiB, max = 4 GiB)
```

The cap exists so a 512 GB card does not lose 20 GB to headroom. The floor exists because 4% of
a 16 GB phone is 640 MB, which is not enough room for Android, the map archive and the app's own
database.

## 3. The budget

`StorageBudget.evaluate` computes, from the user's requested loop size (default **5 GB**):

```
ceiling          = loopUsed + free − reserve
effectiveBudget  = min(requested, ceiling)
trimTrigger      = 0.97 × effectiveBudget
trimTarget       = 0.90 × effectiveBudget
```

* Protected footage and map data are excluded automatically: they are not free space, so they
  never appear in `ceiling`.
* `budgetLimitedByDevice` is set when the device has less room than the user asked for, and the
  Storage screen says so rather than silently under-delivering.
* The 0.97 → 0.90 gap means trimming runs **in batches** rather than deleting one file every
  segment. Continuous small deletes on low-end eMMC are exactly the kind of write amplification
  worth avoiding.

State is `Ok`, `Warning` (budget limited by the device, or free space under twice the reserve)
or `Critical` (free space under the reserve, or an effective budget under
`MIN_VIABLE_LOOP_BYTES = 256 MiB`). `Critical` means recording will not start.

Where a recording rate has been measured, the assessment also reports **how many hours of
history the loop holds** and **how long until deletion begins** — both tagged `[measured]` or
`[inferred]` in Diagnostics, never presented as a specification.

## 4. What gets deleted, and what never does

`StorageBudget.planCleanup` takes unprotected, **complete** segments oldest first and deletes
until `bytesToFree` is satisfied. Two guards:

* **Protected segments are not candidates.** Ever. Loop pressure never deletes footage attached
  to an event or marked by the user.
* **`keepNewest = 2`.** The two most recent unprotected segments are never deleted, no matter
  what the arithmetic says. A pathological budget cannot delete the footage recorded seconds
  ago — which, in a crash, is the only footage that matters.

When protected footage alone exceeds `protectedWarningBytes` (default 2 GB), the user is warned
that protected files are consuming the volume. Roadguard does not resolve that for them: it is
their evidence, and deleting it is their decision.

## 5. Protection is never a move

**No video file is ever renamed or moved after it is written.** Protected footage stays in
`recordings/`, marked in two independent places:

1. a sidecar file, `<name>.mp4.protected.json`, and
2. a row in the Room index.

The sidecar is written **after** the video file is closed and **before** the index is updated.
The failure windows this produces are all benign:

| Crash point | Result | Recovery |
| --- | --- | --- |
| Before the sidecar | protected file with no marks | the event row is still there; reconciler closes it with whatever exists |
| After the sidecar, before the index | file marked, index unaware | reconciler re-applies protection from the sidecar |
| After both | consistent | nothing to do |

There is deliberately **no window in which a half-completed move loses the footage an event was
trying to protect** — which is the failure mode of every "move protected files to a safe folder"
design. It also means protection survives total loss of the database.

## 6. Start-up reconciliation

Roadguard assumes the last run ended badly, because sooner or later it did.
`StorageReconciler` runs once at start-up, before the recorder can index anything, and repairs
five defined divergences:

| Situation | Cause | Repair |
| --- | --- | --- |
| Row marked incomplete | killed mid-recording | inspect the file; index it if playable, quarantine it if not |
| Row with no file | user deleted it, or the card was swapped | drop the row |
| File with no row | crash between muxer finalise and index insert | inspect and adopt it |
| File with a protection sidecar but an unprotected row | crash between marking and indexing | re-apply protection |
| Event stuck awaiting post-roll | killed just after an impact | close it with whatever footage exists |

**The bias throughout is to keep footage.** A file that cannot be verified is moved to
`quarantine/` and reported — never deleted. The truncated segment may be exactly the one the
user needs, and a human with a repair tool can do more with it than Roadguard can.

`ReconcileReport` is surfaced in Diagnostics, so a user who lost power mid-drive can see
precisely what was repaired.

## 7. Verifying a file

`Mp4Inspector` performs a **top-level box scan** — it walks the MP4 box structure looking for
`ftyp`, `moov` and `mdat` — and returns one of:

| Verdict | Meaning |
| --- | --- |
| `Playable` | structure is sound; duration, dimensions and rotation extracted |
| `TruncatedNoIndex` | `mdat` present but no `moov` — the muxer never wrote the index |
| `Empty` / `Malformed` | not a usable MP4 |
| `Missing` | the file is gone |

`TruncatedNoIndex` is the interesting one, and it is named honestly: the video data is probably
all there, but without a `moov` atom no ordinary player will open it. **Roadguard does not claim
to repair such files.** It quarantines them, reports the verdict verbatim, and leaves them
intact for a tool like `untrunc` or `ffmpeg`. Writing a half-working MP4 repairer into the hot
path of a dashcam would be a worse outcome than saying "this file is damaged, here it is".

## 8. Failure modes and what happens

| Failure | Behaviour |
| --- | --- |
| Card removed mid-recording | the finalise error is classified, the recording stops, and the UI reports the volume is gone. Roadguard does not silently switch volumes — that would scatter a drive's footage across two devices |
| Volume full despite the reserve | trim, then retry; if the trim frees nothing (all protected), report and stop rather than thrash |
| Free space below the reserve at start-up | recording does not start; the Storage screen explains what to free |
| Write error mid-segment | classified by `handleFinalizeError`, segment closed, new segment started, backoff after repeated failures (max 5 consecutive) |
| Database corrupt or deleted | the reconciler adopts every file it finds and re-applies protection from sidecars. No footage is lost |

## 9. Storage per hour

Storage consumption depends on the codec and bitrate the *device* chooses, which Roadguard
cannot control (see `docs/architecture.md` §9). The Storage screen therefore reports a
**measured** rate from the files this device has actually written, and shows loop coverage
derived from it — not a table of nominal figures.

`docs/benchmarking.md` gives the arithmetic and the ranges to expect, and is explicit that
those figures are calculated, not measured on the target hardware.

## 10. What has been verified

| Claim | Status |
| --- | --- |
| Reserve, budget, trim trigger/target arithmetic, including edge cases at zero and at the cap | **Verified** — `StorageBudgetTest`, 32 JVM tests, passing |
| `keepNewest` prevents deleting the newest segments under any budget | **Verified** — unit tested |
| Protected segments are never cleanup candidates | **Verified** — unit tested |
| Reconciler repair table | **Implemented and reviewed; not covered by an end-to-end test.** Reconciliation needs a real filesystem and a real Room database, which is an instrumentation test — see `docs/testing.md` |
| Behaviour when a microSD card is physically removed mid-recording | **Not verified.** No device was available |
| Sustained write behaviour on low-end eMMC over hours | **Not verified.** No device was available |
