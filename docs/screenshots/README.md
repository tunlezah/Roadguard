# Screenshots

**There are no screenshots in this directory, and that is deliberate.**

Roadguard has never been run on a physical device or an emulator — no device was available during
this work. A screenshot is a claim about what the product looks like when it runs. Producing one
by rendering mock-ups, compositing Compose previews, or generating an image would be a
straightforward misrepresentation of an app that has not been seen running, so none exists.

The specification asks for screenshots and also forbids fabricating them. Where those two collide,
the second wins.

---

## What exists instead

* **The UI is fully implemented** and reviewed: `ui/main/`, `ui/settings/`, `ui/gallery/`,
  `ui/diagnostics/`, `ui/storage/`, `ui/firstrun/`, `ui/about/`.
* **67 Compose UI tests** run on every push and assert the semantics, structure and behaviour of
  the driving screen, the settings primitives, the on-video chrome and all four themes. They do
  not assert appearance — Robolectric draws nothing — but they do prove the composables compose
  and behave. See `docs/testing.md` §3.
* **`@Preview` composables** exist alongside the screens, so the layouts can be inspected in Android
  Studio's preview pane without a device.

## Capturing the real thing

Anyone with a device can produce the full set in about fifteen minutes.

### Prepare

```bash
./gradlew :app:installDebug
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1030
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged true
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi hide -e mobile hide
```

The demo-mode calls give a clean, reproducible status bar. Restore it afterwards with
`adb shell am broadcast -a com.android.systemui.demo -e command exit`.

### Capture

```bash
capture() { adb exec-out screencap -p > "docs/screenshots/$1.png"; }
```

### The set worth having

| File | Screen | Set up |
| --- | --- | --- |
| `01-first-run.png` | First run | Fresh install, before granting permissions |
| `02-map-install.png` | Map install progress | Mid-download, so the progress and rate are visible |
| `03-main-portrait.png` | Driving screen, portrait | Recording, GPS fix, map following. The 50/50 split is the point |
| `04-main-landscape.png` | Driving screen, landscape | Same, rotated — video left, map right |
| `05-main-preview-zoom.png` | Preview zoom caption | Zoom at 2.0×, so "display only, recording is not cropped" is legible |
| `06-main-thermal.png` | Thermal warning | Drive the harness to `High` from Diagnostics |
| `07-settings-recording.png` | Settings, recording section | Show Auto and the quality picker |
| `08-settings-privacy.png` | Settings, overlays and GPS | Show the microphone off and coordinates off |
| `09-diagnostics.png` | Diagnostics | Scrolled to the recording profile rationale — the "why did Auto pick this" answer |
| `10-diagnostics-simulated.png` | Diagnostics, harness active | So the loud `[simulated]` tag is visible |
| `11-storage.png` | Storage | Loop full, some protected footage, measured rate showing |
| `12-gallery.png` | Gallery | A mix of protected and unprotected segments |
| `13-player.png` | Player | A protected clip playing, with its event details |
| `14-event-detail.png` | Event detail | An impact with its confidence and features |
| `15-theme-light.png` | Driving screen, Light | |
| `16-theme-oled.png` | Driving screen, OLED | Should be visibly blacker than Dark |
| `17-about.png` | About | Shows the OpenStreetMap and Open-Meteo attribution |

### Rules for whoever captures them

1. **Real footage only.** No mock-ups, no composites, no retouching beyond cropping the status
   bar. If a screen cannot be reached, leave it out and say so.
2. **Blur or synthesise location data.** A real screenshot of the driving screen with coordinates
   enabled shows where the photographer was. Use a fixed test location, or blur it.
3. **Say which device and Android version** each shot came from, in this file, when you add them.
4. **Note anything that looks wrong.** A screenshot that reveals a layout bug is more valuable
   than one that hides it.

### When they exist

Add them here, list them in a table in this file with device and OS version, and link the two or
three most useful from the root `README.md` — replacing the note that currently explains their
absence.
