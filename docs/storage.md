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
| `tracks/` | one GPX track per trip, while the GPX switch is on (the default); deleted with the trip's last clip — see [`trips-and-tracks.md`](trips-and-tracks.md) |
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
* **A sidecar outranks the index.** Before deleting a clip, cleanup checks for its
  `.protected.json` sidecar. If one exists the clip is protected whatever its row says: the row
  is re-protected and the file kept. That closes the second crash window in §5 for the loop as
  well as for the reconciler.

Finalising a clip updates only its duration, size, end position and completion flag. It used to
write back a whole copy of the row read before the clip closed, which could silently undo a
protection applied to that clip moments earlier — an impact near the end of a segment — and
leave the footage for the loop to delete.

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
ten defined divergences:

| Situation | Cause | Repair |
| --- | --- | --- |
| Row marked incomplete | killed mid-recording | inspect the file; index it if playable, quarantine it if not |
| Newest finished clip with no index | power lost before the file reached the disk | quarantine it |
| Row with no file | user deleted it, or the card was swapped | drop the row — only while other recordings are present |
| File with no row | crash between muxer finalise and index insert | inspect and adopt it |
| File with a protection sidecar but an unprotected row | crash between marking and indexing | re-apply protection |
| Event stuck awaiting post-roll | killed just after an impact | close it with whatever footage exists |
| Clip with no trip | recorded before trips existed, or adopted above | group by the 2-minute gap rule and assign |
| Trip left recording | killed mid-drive | close it; its end is the last clip that finalised |
| Trip with no clips | its footage left the loop while the app was not running | drop the row and its GPX track |
| GPX file with no trip | leftover of a dropped trip | delete it |

**The bias throughout is to keep footage.** A file that cannot be verified is moved to
`quarantine/` and reported — never deleted. The truncated segment may be exactly the one the
user needs, and a human with a repair tool can do more with it than Roadguard can.

**The index is never emptied on the strength of an empty folder.** Every repair compares the index
with a directory listing, and that listing is only meaningful when the folder is the one the rows
were written into and it is readable. A card still mounting after boot, shared storage not yet
served after unlock, or a volume switched in Settings all produce an empty listing, and the pass
used to drop every row on it — and with the rows the trips, the tracks and the protection marks —
for footage that was on the disk the whole time. A folder that cannot be listed now stops the
pass; a folder holding no earlier recording keeps every row whose file is missing, shown as
missing in the list and counted in the report, until a start-up that can tell the difference.

**A finished clip is on the medium before its row says so.** The muxer closes a clip without
syncing it, so its last seconds — and the index at its very end — can sit in the kernel's write
cache for up to half a minute. A phone that loses power in that window used to be left with a row
saying "complete" for a file that was not. The recorder now flushes each clip to the storage
before marking its row, and the reconciler checks the newest finished clips for an index at every
start, quarantining one that has none. Only the structure is read for that check, so a passing
metadata failure cannot send a good clip to quarantine.

`ReconcileReport` is surfaced in Diagnostics, so a user who lost power mid-drive can see
precisely what was repaired.

Reconciliation only ever judges what an *earlier* run left behind. The recorder waits up to ten
seconds for it before writing anything, and files this process created are skipped regardless: an in-progress
MP4 has no index yet, so it looks exactly like a truncated one and would otherwise be quarantined
out from under the recorder.

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
| Card removed mid-recording | the recorder reports the volume is gone and keeps retrying; recording resumes by itself when the card is back. Roadguard does not silently switch volumes — that would scatter a drive's footage across two devices |
| Volume full despite the reserve | trim, then retry; if the trim frees nothing (all protected), report it and retry once a minute rather than thrash |
| Free space below the reserve at start-up | recording does not start; the Storage screen explains what to free |
| Write error mid-segment | classified by `handleFinalizeError`; the file is inspected and kept if playable, and recording is restarted with backoff for as long as the session lasts (`docs/architecture.md` §3.2) |
| Phone switched off while recording | the shutdown broadcast closes the current file first |
| Battery flat while recording | recording stops cleanly at 3 % (not charging) and the clip is closed; a phone that dies sooner loses only the clip in progress, which is quarantined. A session cannot be started on a battery already at the floor |
| Chosen volume not mounted at start | recording refuses with a "storage volume is not available" blocker rather than writing onto another volume, and reconciliation stands down |
| App killed while recording | the clip in progress is repaired or quarantined on the next start, and a notification offers to resume recording with one tap |
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
| A trip and its GPX track are removed only once its last clip is gone, never while recording | **Implemented and reviewed;** the pruning runs inside the same cleanup and delete paths as the footage, and needs a real Room database to test end to end |
| Protected segments are never cleanup candidates | **Verified** — unit tested |
| Reconciler repair table | **Implemented and reviewed; not covered by an end-to-end test.** Reconciliation needs a real filesystem and a real Room database, which is an instrumentation test — see `docs/testing.md` |
| Behaviour when a microSD card is physically removed mid-recording | **Not verified.** No device was available |
| Sustained write behaviour on low-end eMMC over hours | **Not verified.** No device was available |
