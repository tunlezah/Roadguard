# Android Platform Restrictions for Roadguard (Foreground Services, Background Camera, Screen-Off Recording — API 34/35/36)

**Bottom line.** Roadguard must run its recorder in a *single* `Service` declared
`android:foregroundServiceType="camera|location"` (adding `|microphone` only when the user enables audio),
promoted with `ServiceCompat.startForeground(service, id, notification, typeBitmask)` **from a resumed, visible
Activity** — that visibility at promotion time is what latches the process's while-in-use camera/mic/location
capability for the whole life of the service, and it survives the Activity being destroyed and the screen turning
off. Screen state is *not* part of any camera-access policy: the platform gate is "is the UID active / does the
process hold `PROCESS_CAPABILITY_FOREGROUND_CAMERA`", so a camera FGS legally keeps the camera open with the
display off on Android 14, 15 and 16, and neither `camera` nor `location` nor `microphone` FGS types have a
runtime time limit (only `shortService`, `dataSync` and `mediaProcessing` do). Two things will bite us hard and
must be designed for from day one: (1) **on `targetSdk` 35+ a `BOOT_COMPLETED` receiver may not start a `camera`
FGS at all** (`ForegroundServiceStartNotAllowedException`), so "resume recording after reboot" must be a
notification the user taps, not an automatic start; and (2) **nothing in the camera or codec stack holds a wake
lock** (AudioFlinger does, cameraserver and MediaCodec do not), so a *video-only* recording session with the
screen off can be stopped by kernel autosuspend — we need one long-lived `PARTIAL_WAKE_LOCK` held inside the
recording FGS, released the instant recording stops, and we must accept the Android-vitals/battery consequences
of that. Everything else (Doze, App Standby, battery optimisation exemption) we handle by *not* asking for
exemptions and instead telling the user which OEM settings to relax.

**Evidence key.** Every factual claim below carries one of:
`[DOCUMENTED]` = stated in official Android/AOSP/Google Play/OEM documentation or AOSP source, with the URL cited;
`[INFERRED]` = a conclusion reasoned from documented facts, with the reasoning chain given explicitly;
`[UNVERIFIED]` = plausible but not confirmed by a source I read — treat as a hypothesis to test.
No `[MEASURED]` tags appear: no measurement was performed in this session. Where a question can only be settled
on hardware, it is listed in **Open questions / must-measure-on-device** with the exact test.

---

## 0. Target configuration this document assumes

| Setting | Value | Why |
|---|---|---|
| `minSdk` | 34 | Product requirement. Moto G04 ships Android 14 [DOCUMENTED — Motorola spec page lists "Android™ 14", UNISOC T606, 4 GB RAM, 1612×720, video FHD 30 fps rear: https://en-us.support.motorola.com/app/answers/detail/a_id/178144/ ] |
| `compileSdk` | 36 | Needed for API 36 constants |
| `targetSdk` | 36 | **Forced by Google Play**: from 31 Aug 2026 new apps and updates must target API 36; existing apps must target ≥ 35 to stay available to new users on newer OS versions. Extension possible to 1 Nov 2026. [DOCUMENTED https://developer.android.com/google/play/requirements/target-sdk ] |

Consequence: **we cannot dodge any of the API 35 or API 36 behaviour changes by keeping `targetSdk` low.**
Every `@EnabledSince(targetSdkVersion = 35/36)` compat change in this document is live for us.

---

## 1. Foreground services on Android 14+ (API 34)

### 1.1 What Android 14 made mandatory

Apps targeting API 34+ **must** declare a `foregroundServiceType` for every foreground service and **must** hold
the matching `FOREGROUND_SERVICE_*` manifest permission; missing either is fatal at `startForeground()` time.
[DOCUMENTED https://developer.android.com/about/versions/14/changes/fgs-types-required ,
https://developer.android.com/develop/background-work/services/fgs/declare ]

### 1.2 Exact permission strings and protection levels

All verified from the `android.Manifest.permission` reference
[DOCUMENTED https://developer.android.com/reference/android/Manifest.permission ]:

| Permission string | Added in API | Protection level | Notes |
|---|---|---|---|
| `android.permission.FOREGROUND_SERVICE` | 28 | `normal` | Required for *any* FGS |
| `android.permission.FOREGROUND_SERVICE_CAMERA` | 34 | `normal|instant` | Install-time; no runtime prompt |
| `android.permission.FOREGROUND_SERVICE_LOCATION` | 34 | `normal|instant` | Install-time |
| `android.permission.FOREGROUND_SERVICE_MICROPHONE` | 34 | `normal|instant` | Install-time |
| `android.permission.FOREGROUND_SERVICE_SPECIAL_USE` | 34 | `normal|appop|instant` | Not needed by us |
| `android.permission.CAMERA` | 1 | `dangerous` | Runtime prompt |
| `android.permission.RECORD_AUDIO` | 1 | `dangerous` | Runtime prompt, only if audio enabled |
| `android.permission.ACCESS_FINE_LOCATION` | 1 | `dangerous` | Runtime prompt |
| `android.permission.ACCESS_COARSE_LOCATION` | 1 | `dangerous` | Must be requested *with* FINE on API 31+ |
| `android.permission.ACCESS_BACKGROUND_LOCATION` | 29 | `dangerous`, **hard-restricted** | **We do NOT need this — see §7.4** |
| `android.permission.POST_NOTIFICATIONS` | 33 | `dangerous` | Runtime prompt; FGS runs without it (§5.2) |
| `android.permission.WAKE_LOCK` | 1 | `normal` | Install-time |
| `android.permission.RECEIVE_BOOT_COMPLETED` | 1 | `normal` | Install-time |
| `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 23 | `normal` | **Do not ship this — see §6.4** |

Note the FGS type permissions are `normal`, i.e. **granted at install with no user prompt**. Only `CAMERA`,
`RECORD_AUDIO`, `ACCESS_*_LOCATION` and `POST_NOTIFICATIONS` are runtime prompts.

### 1.3 `ServiceInfo` type constants (exact values, for the bitmask)

[DOCUMENTED https://developer.android.com/reference/android/content/pm/ServiceInfo ]

| Constant | Value | Added in API |
|---|---:|---|
| `FOREGROUND_SERVICE_TYPE_MANIFEST` | `-1` | 29 |
| `FOREGROUND_SERVICE_TYPE_NONE` | `0` | 29 |
| `FOREGROUND_SERVICE_TYPE_DATA_SYNC` | `1` | 29 |
| `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` | `2` | 29 |
| `FOREGROUND_SERVICE_TYPE_PHONE_CALL` | `4` | 29 |
| `FOREGROUND_SERVICE_TYPE_LOCATION` | `8` | 29 |
| `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` | `16` | 29 |
| `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` | `32` | 29 |
| `FOREGROUND_SERVICE_TYPE_CAMERA` | `64` | 30 |
| `FOREGROUND_SERVICE_TYPE_MICROPHONE` | `128` | 30 |
| `FOREGROUND_SERVICE_TYPE_HEALTH` | `256` | 34 |
| `FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING` | `512` | 34 |
| `FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED` | `1024` | 34 |
| `FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING` | `8192` | 35 |
| `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` | `1073741824` | 34 |

So our bitmask is `64 | 8 = 72` (video only) or `64 | 8 | 128 = 200` (video + audio).

### 1.4 Prerequisites Android 14 enforces, per type

[DOCUMENTED https://developer.android.com/develop/background-work/services/fgs/service-types ]

| Type | Manifest attr | FGS permission | Runtime prereq checked at `startForeground()` | Subject to while-in-use (WIU) restriction? | Blocked from `BOOT_COMPLETED` (targetSdk 35+)? | Runtime time limit? |
|---|---|---|---|---|---|---|
| `camera` | `camera` | `FOREGROUND_SERVICE_CAMERA` | `CAMERA` granted | **Yes** | **Yes** | **No** |
| `location` | `location` | `FOREGROUND_SERVICE_LOCATION` | `ACCESS_FINE_LOCATION` or `ACCESS_COARSE_LOCATION` granted **and** location services enabled by the user | Yes, unless `ACCESS_BACKGROUND_LOCATION` held | **No** (explicitly allow-listed) | No |
| `microphone` | `microphone` | `FOREGROUND_SERVICE_MICROPHONE` | `RECORD_AUDIO` granted | **Yes** | **Yes** (since Android 14) | No |
| `dataSync` | `dataSync` | `FOREGROUND_SERVICE_DATA_SYNC` | none | No | Yes | **6 h / 24 h** |
| `mediaProcessing` | `mediaProcessing` | `FOREGROUND_SERVICE_MEDIA_PROCESSING` | none | No | No | **6 h / 24 h** |
| `shortService` | `shortService` | *(none beyond `FOREGROUND_SERVICE`)* | none | No | n/a | **~3 min** |

The `location` type's prerequisite "user must have enabled location services" is verified by the system with
`PermissionChecker#checkSelfPermission()` semantics; the docs explicitly warn that
`PermissionChecker.checkSelfPermission()` returns `PERMISSION_GRANTED` even in the background for while-in-use
permissions, so it is *not* a reliable pre-flight check for whether the FGS start will succeed.
[DOCUMENTED https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start ]

**Timeouts, confirmed against AOSP.** The only FGS timeout `DeviceConfig` keys that exist in
`ActivityManagerConstants` are `short_fgs_timeout_duration` (default `3 * 60_000` ms),
`media_processing_fgs_timeout_duration` (default `6 * 60 * 60_000` ms) and `data_sync_fgs_timeout_duration`
(default `6 * 60 * 60_000` ms). There is **no camera/location/microphone timeout key**.
[DOCUMENTED https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/ActivityManagerConstants.java — `DEFAULT_SHORT_FGS_TIMEOUT_DURATION`, `DEFAULT_MEDIA_PROCESSING_FGS_TIMEOUT_DURATION`, `DEFAULT_DATA_SYNC_FGS_TIMEOUT_DURATION` ]
`Service.onTimeout(int startId, int fgsType)` was added in **API 35**; `Service.onTimeout(int startId)` (short
service only) in API 34. [DOCUMENTED https://developer.android.com/reference/android/app/Service ]
→ **Roadguard's recorder FGS is not time-limited.** Do *not* use `dataSync` for anything long-running.

### 1.5 The exception taxonomy — exact class hierarchy

Verified class hierarchies from the API reference
[DOCUMENTED https://developer.android.com/reference/android/app/ForegroundServiceStartNotAllowedException ,
https://developer.android.com/reference/android/app/MissingForegroundServiceTypeException ,
https://developer.android.com/reference/android/app/InvalidForegroundServiceTypeException ]:

```
java.lang.IllegalStateException
 └─ android.app.ServiceStartNotAllowedException                (API 31)
     ├─ android.app.ForegroundServiceStartNotAllowedException  (API 31, final)
     └─ android.app.ForegroundServiceTypeException             (API 34)
         ├─ android.app.MissingForegroundServiceTypeException  (API 34, final)
         └─ android.app.InvalidForegroundServiceTypeException  (API 34, final)
```

| Throwable | Thrown when | Our mitigation |
|---|---|---|
| `MissingForegroundServiceTypeException` | `startForeground()` called with no type declared in the manifest, on targetSdk 34+ | Always declare `camera|location|microphone` in the manifest |
| `InvalidForegroundServiceTypeException` | `startForeground()` called with an *invalid* type | n/a |
| `IllegalArgumentException` | Runtime bitmask contains a type **not** declared in the manifest | Declare the union of all types we will ever pass |
| `SecurityException` | Missing the type's `FOREGROUND_SERVICE_*` manifest permission, or missing the type's runtime permission (`CAMERA` / `RECORD_AUDIO` / location) at `startForeground()` time. Documented outcome: "SecurityException thrown, preventing foreground service startup **or removing existing service from foreground state**" | Gate `startForeground()` on `ContextCompat.checkSelfPermission` for every type in the bitmask; never add `microphone` to the mask before `RECORD_AUDIO` is granted |
| `ForegroundServiceStartNotAllowedException` | Starting an FGS while the app is in the background without an exemption (API 31+); **or** a `BOOT_COMPLETED` receiver starting a restricted type on targetSdk 35+ | Only ever start from a resumed Activity (§1.7); never from `BOOT_COMPLETED` (§5.4) |
| `android.app.RemoteServiceException` ("Context.startForegroundService() did not then call Service.startForeground()") | `startForegroundService()` was called but `startForeground()` was not | Call `startForeground()` synchronously at the top of `onStartCommand()`, before any camera work |
| `android.app.RemoteServiceException` ("A foreground service of type X did not stop within its timeout") | timeout expiry for `dataSync`/`mediaProcessing`/`shortService` | Don't use those types |

**Exact start-to-`startForeground()` budget, from AOSP.** `ActivityManagerConstants` sets
`DEFAULT_SERVICE_START_FOREGROUND_TIMEOUT_MS = 30 * 1000` and
`DEFAULT_SERVICE_START_FOREGROUND_ANR_DELAY_MS = 10 * 1000`, and these values are **identical on the
`android14-release`, `android15-release`, `android16-release` and `main` branches**
[DOCUMENTED https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/am/ActivityManagerConstants.java ].
So the real budget is ~30 s of timeout plus a 10 s ANR grace, both overridable by `DeviceConfig`. Widely repeated
"5 seconds" figures are not in the platform docs — the docs only say "within a few seconds"
[DOCUMENTED https://developer.android.com/develop/background-work/services/fgs/launch ]. Design as if the budget
were 1 s: promote first, open the camera afterwards. [INFERRED — chain: OEMs may override these `DeviceConfig`
values, and Roadguard's cold start on a T606 with 4 GB RAM will be slow, so we should not consume the budget.]

### 1.6 Background-start exemptions (API 31+) vs while-in-use exemptions — these are two different lists

This distinction is the single most misunderstood part of the platform and it is exactly where dashcams break.

**List A — may I *start* an FGS from the background?** (API 31+) Exemptions include: app has a visible
Activity / is transitioning from a user-visible state; app can start an activity from the background;
high-priority FCM; user interaction with a notification/widget/bubble; exact-alarm execution for a
user-requested action; current IME; geofencing or activity-recognition event; `ACTION_BOOT_COMPLETED`,
`ACTION_LOCKED_BOOT_COMPLETED`, `ACTION_MY_PACKAGE_REPLACED`; `ACTION_TIMEZONE_CHANGED`/`ACTION_TIME_CHANGED`/
`ACTION_LOCALE_CHANGED`; NFC `ACTION_TRANSACTION_DETECTED`; device/profile owner; Companion Device Manager
permissions; **battery optimizations turned off by the user**; `SYSTEM_ALERT_WINDOW` (on Android 15+ only with a
*visible* overlay window).
[DOCUMENTED https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start ]

**List B — will my camera/mic/location actually work?** (API 14 = Android 14 rule) The docs state flatly: on
Android 14+ an app **cannot create** an FGS that requires while-in-use permissions (`CAMERA`, `RECORD_AUDIO`,
`ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION`, `BODY_SENSORS`) while the app is running in the background —
**even if it satisfies a List A exemption**. `ACCESS_BACKGROUND_LOCATION` bypasses this for location only.
[DOCUMENTED same page.] The Android 11 wording is the clearest statement of the *effect*: "If your app starts a
foreground service while running in the background, the foreground service cannot access the microphone or
camera. Additionally, the service cannot access location unless your app has background location access."
[DOCUMENTED https://developer.android.com/about/versions/11/privacy/foreground-services ]
Diagnostic logcat line to watch for:
`Foreground service started from background can not have location/camera/microphone access: service <NAME>`
[DOCUMENTED same page — and confirmed verbatim in AOSP `ActiveServices.java`, see below].

**What AOSP actually checks (mechanism, so we can reason about edge cases).** In
`ActiveServices.shouldAllowFgsWhileInUsePermissionLocked(...)` the capability is granted if, at the moment of the
start, any of these hold: caller UID proc-state `<= PROCESS_STATE_TOP`; caller UID has a visible Activity
(`ActivityTaskManagerInternal.isUidForeground` → `REASON_UID_VISIBLE`); background-activity-start privileges are
in effect (`REASON_ACTIVITY_STARTER`, which covers the ~10 s grace after an Activity started/finished);
root/system/NFC/shell UID; a system temp-allowlist (`REASON_TEMP_ALLOWED_WHILE_IN_USE`, typically 10 s, used by
MediaSession/telephony); instrumentation with background-activity-start permission;
`START_ACTIVITIES_FROM_BACKGROUND` permission; a platform package allowlist; device owner. When none apply, the
server logs `"Foreground service started from background can not have location/camera/microphone access: service …"`.
[DOCUMENTED https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/ActiveServices.java ]

Note what is **not** in that AOSP list: "a notification action started the service." The developer docs list
notification-triggered starts as a while-in-use exemption, but I could not find a corresponding reason code in
`shouldAllowFgsWhileInUsePermissionLocked` on `main`. [UNVERIFIED whether a bare notification-action
`PendingIntent` targeting a Service grants the camera capability.] **Design rule: never rely on it.** A
notification tap should open a (possibly transparent, immediately-finishing) Activity, and the Activity starts
the FGS while resumed. That path is guaranteed by `PROCESS_STATE_TOP`.

### 1.7 The one legal start sequence Roadguard should use

```kotlin
// In RecordingActivity (resumed, visible), e.g. from the user's "Record" tap or from onResume auto-start.
private fun startRecording(audioEnabled: Boolean) {
    // 1. Pre-flight EVERY runtime permission that corresponds to a bit we are about to pass.
    require(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PERMISSION_GRANTED)
    // location + microphone checked the same way; drop the bit if not granted.

    // 2. startForegroundService from a resumed Activity => proc state TOP => WIU capability latched.
    ContextCompat.startForegroundService(this, Intent(this, RecorderService::class.java).apply {
        action = RecorderService.ACTION_START
        putExtra(RecorderService.EXTRA_AUDIO, audioEnabled)
    })
}
```

```kotlin
// RecorderService
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // Promote FIRST. Nothing heavy before this line.
    val types = buildTypeBitmask()   // CAMERA (64) | LOCATION (8) [| MICROPHONE (128)]
    try {
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), types)
    } catch (e: ForegroundServiceStartNotAllowedException) {   // API 31+
        // We were not visible. Do not retry blindly: post a "tap to start recording" notification.
        postResumePrompt(); stopSelf(); return START_NOT_STICKY
    } catch (e: SecurityException) {
        // A required runtime permission was revoked between the check and here.
        postPermissionPrompt(); stopSelf(); return START_NOT_STICKY
    }
    acquireWakeLock()        // §3
    startCameraAndEncoder()  // async, off the main thread
    return START_STICKY      // §5.3
}
```

`ServiceCompat.startForeground` exact signature
[DOCUMENTED https://developer.android.com/reference/androidx/core/app/ServiceCompat ]:

```java
public static void startForeground(@NonNull Service service, int id,
                                   @NonNull Notification notification, int foregroundServiceType)
```

Use `androidx.core:core-ktx` ≥ 1.12.0 (the version the FGS-types docs use for `ServiceCompat.startForeground`
with the type bitmask) [DOCUMENTED https://developer.android.com/about/versions/14/changes/fgs-types-required ].
`id` must be a **non-zero positive int** [DOCUMENTED https://developer.android.com/develop/background-work/services/fgs/launch ].

**Adding the microphone bit later.** `startForeground()` may be called repeatedly to add types
[DOCUMENTED https://developer.android.com/about/versions/14/changes/fgs-types-required — "Later, if user enables
audio, add mediaPlayback type … `FOREGROUND_SERVICE_TYPE_LOCATION | FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`"].
But the *capability* is recomputed from the current `foregroundServiceType` bitmask AND-ed with the latched
"is FGS allowed while-in-use" flag — see `OomAdjuster`:
`if (s.isFgsAllowedWiu_forCapabilities()) { … capabilityFromFGS |= (fgsType & FOREGROUND_SERVICE_TYPE_CAMERA) != 0 ? PROCESS_CAPABILITY_FOREGROUND_CAMERA : 0; … }`
[DOCUMENTED https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/OomAdjuster.java ].
So: **if the user enables audio mid-drive while the app is not visible, adding the `microphone` bit gives us the
microphone capability only if the original start was WIU-allowed.** Since our original start is always from a
visible Activity, that holds. [INFERRED — chain: `isFgsAllowedWiu_forCapabilities()` is latched at entering;
`fgsType` is read live; therefore later-added bits inherit the latched allowance.]
**But** the audio toggle lives in our UI, so the app is visible anyway. Simplest correct rule: **toggling audio
restarts the capture session from the Activity.**

---

## 2. Can a camera FGS keep the camera open with the screen OFF? (API 34 / 35 / 36)

### 2.1 The documented rule set

1. **Android 9 (API 28) baseline:** "If your app is running in the background on a device running Android 9, the
   system applies the following restrictions to your app: Your app cannot access the microphone or camera…"
   and the remedy is: "If your app needs to detect sensor events on devices running Android 9, use a foreground
   service." [DOCUMENTED https://developer.android.com/about/versions/pie/android-9.0-changes-all ]
2. **Android 11 (API 30):** camera/microphone use in an FGS requires the `camera`/`microphone` FGS *type*.
   [DOCUMENTED https://developer.android.com/about/versions/11/privacy/foreground-services ]
3. **Android 14 (API 34):** the type is mandatory plus the WIU start restriction of §1.6.
4. **Nothing in any of those rules mentions display state.** The gate is process/UID state.
5. The analogous documented sentence for `location` — the closest the docs come to saying it outright — is:
   "Your app retains access when it's placed in the background, such as when the user presses the **Home** button
   on their device **or turns their device's display off**."
   [DOCUMENTED https://developer.android.com/develop/sensors-and-location/location/permissions ]

**Conclusion:** turning the screen off, or the keyguard appearing, does **not** revoke camera access from a
process holding a camera-type FGS on Android 14/15/16. [INFERRED — chain: (a) the documented policy is expressed
purely in terms of foreground/background *app* state and FGS type, never display state; (b) AOSP's camera gate is
`UidPolicy::isUidActive()` + the `OP_CAMERA` app-op, and a running FGS keeps the UID active and grants the
app-op via `PROCESS_CAPABILITY_FOREGROUND_CAMERA` (see §2.2); (c) locking the screen is not among the documented
`onDisconnected` causes (see §2.3).]

### 2.2 The AOSP mechanism (so we can debug it in logcat)

* `CameraService::validateClientPermissionsLocked` rejects opening with
  `ERROR_DISABLED` and the message
  `Caller "<pkg>" (PID …, UID …) cannot open camera "<id>" from background (calling UID … proc state …)`
  **iff** `mUidPolicy->isUidActive(callingUid, clientName)` is false.
  [DOCUMENTED https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/services/camera/libcameraservice/CameraService.cpp ]
* `CameraService::UidPolicy::onUidIdle(uid)` → `service->blockClientsForUid(uid)` — i.e. the camera is taken away
  the moment the UID goes *idle*, not when the screen goes off. Same file.
* When the `OP_CAMERA` app-op returns `MODE_IGNORED` (soft-denied), `BasicClient::onPermissionResult` computes
  `isUidVisible = (procState <= ActivityManager::PROCESS_STATE_BOUND_TOP)`; if the UID is **active but not
  visible** (its own comment: "Uid may be active, but not visible to the user (e.g.
  `PROCESS_STATE_FOREGROUND_SERVICE`)") it calls `block()` → `notifyError(ERROR_CAMERA_DISABLED)` + `disconnect()`.
  Same file. The path that keeps us alive is therefore the app-op being **allowed**, which is what the
  `PROCESS_CAPABILITY_FOREGROUND_CAMERA` capability produces.
* Capability plumbing: `ActivityManager.PROCESS_CAPABILITY_FOREGROUND_LOCATION = 1 << 0`,
  `PROCESS_CAPABILITY_FOREGROUND_CAMERA = 1 << 1`, `PROCESS_CAPABILITY_FOREGROUND_MICROPHONE = 1 << 2`
  [DOCUMENTED https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/ActivityManager.java ];
  `OomAdjuster` sets those bits from the live FGS type bitmask when `isFgsAllowedWiu_forCapabilities()`
  (see §1.7 quote). The compat gate is
  `CAMERA_MICROPHONE_CAPABILITY_CHANGE_ID = 136219221L` `@EnabledAfter(targetSdkVersion = Q)`, documented in
  source as: "In targetSdkVersion R and above, foreground service has camera and microphone while-in-use
  capability **only when** the `android:foregroundServiceType` is configured as `…_CAMERA` and `…_MICROPHONE`
  respectively in the manifest file." [DOCUMENTED same `OomAdjuster.java` ]

`adb` command to observe our own state while the screen is off:
`adb shell dumpsys activity processes | grep -A3 <our.package>` — the capability field prints as a
three-character `L C M` mask (`OomAdjuster`/`ActivityManager.printCapabilitiesSummary` emits `'L'`, `'C'`, `'M'`
or `'-'`). [DOCUMENTED `ActivityManager.java` printCapabilities* helpers ]

### 2.3 What *does* take the camera away — plan for all of these

| Cause | Callback / error | Documented source |
|---|---|---|
| Higher-priority camera client opens the camera (incoming video call, another camera app, Assistant) | `CameraDevice.StateCallback.onDisconnected(camera)`; a subsequent open may fail with `ERROR_CAMERA_IN_USE = 1` | "The disconnection could be due to a change in security policy or permissions; the physical disconnection of a removable camera device; or the camera being needed for a higher-priority camera API client." [DOCUMENTED https://developer.android.com/reference/android/hardware/camera2/CameraDevice.StateCallback ] |
| Camera runtime permission revoked while running | `onDisconnected` ("change in security policy or permissions"); AOSP: `handlePermissionResult` → `PERMISSION_DENIED` | Same |
| Microphone/Camera **privacy toggle** ("Sensors off" dev tile, or the QS camera toggle) | `onError(camera, ERROR_CAMERA_DISABLED = 3)` and the `CameraDevice` is closed; microphone yields **zero-valued (silent) buffers with no error** | [DOCUMENTED https://source.android.com/docs/core/interaction/sensors/sensors-off ] |
| Device policy (`DevicePolicyManager.setCameraDisabled`) | `ERROR_CAMERA_DISABLED = 3` | [DOCUMENTED `CameraDevice.StateCallback` reference ] |
| Too many open cameras | `ERROR_MAX_CAMERAS_IN_USE = 2` | Same |
| Camera HAL/service death | `ERROR_CAMERA_DEVICE = 4` / `ERROR_CAMERA_SERVICE = 5` | Same |
| User taps **Stop** in the Task Manager | *no callback at all* — the whole app is force-stopped | §5.5 |
| App UID goes idle (FGS stopped/crashed) | `blockClientsForUid` → `ERROR_CAMERA_DISABLED` | `CameraService.cpp` `onUidIdle` |

**Implementation requirement.** `onDisconnected` and `onError` must both drive a *single* recovery state machine
in the service: close the device, finalise the in-flight segment file (never leave a truncated MP4), wait with
backoff, re-check `CameraManager.AvailabilityCallback.onCameraAvailable`, reopen, start a new segment, and surface
a distinct notification state ("Recording paused — camera in use by another app"). Documented advice: "You should
clean up the camera with `CameraDevice.close` after this happens, as it is not recoverable until the camera can be
opened again. For most use cases, this will be when the camera again becomes available."
[DOCUMENTED `CameraDevice.StateCallback` reference ]

### 2.4 Preview surface lifecycle — the *real* screen-off hazard

The platform will not take the camera away when the screen turns off, but **our own `SurfaceView`/`TextureView`
surface is destroyed** when the Activity stops. If the camera capture session's output list includes that
surface, the session must be reconfigured, which drops frames or stops recording.
[INFERRED — chain: `Surface` for a `SurfaceView` is tied to the window; the window is destroyed with the
Activity; `CameraCaptureSession` outputs are fixed at creation, so losing an output forces
`createCaptureSession` again. Not a single-doc claim.]

Design rule for Roadguard: **the service owns the camera and the encoder; the UI is a detachable consumer.**
Concretely: the service configures the session against (a) the encoder input surface and (b) a service-owned
offscreen `SurfaceTexture`/GL texture that never goes away; the Activity's `SurfaceView` is attached/detached as a
*second* GL render target only, so the camera session never changes when the UI comes and goes. Use
`MediaCodec.createPersistentInputSurface()` (added API 23) if the encoder itself must be recreated across
segments without touching the camera session
[DOCUMENTED https://developer.android.com/reference/android/media/MediaCodec — `createPersistentInputSurface()` ].
See `docs/research/camera-pipeline.md` (sibling document) for the pipeline detail; the platform-level point here
is only: **do not put the UI surface in the recording session.**

### 2.5 What is OEM-dependent (not guaranteed by AOSP)

* **Motorola's own battery features.** Motorola documents, at Settings level:
  `Settings > Battery > App standby optimizer` with sub-settings "Standby wakeup optimization",
  "Standby sleep optimization" and "Standby network data"; and `Settings > Battery > Auto launch management`
  with "App auto launch" and "App secondary launch"; and a "Power intensive apps" screen that flags apps that
  "prevent sleep mode". [DOCUMENTED https://help.motorola.com/hc/1814/14/global/en-us/CG2007980805.html ]
  These are OEM additions on top of AOSP and can restrict us; the *effect* on a running camera FGS is
  **NOT VERIFIED — needs on-device measurement** on both the Moto G04 and the Edge 60 Fusion.
* A community bug report claims a Motorola `com.motorola.batterycare` "Improve battery while inactive" feature
  kills background apps hourly regardless of the Settings toggle
  [UNVERIFIED — third-party issue tracker, https://github.com/urbandroid-team/dont-kill-my-app/issues/1142 ;
  also https://dontkillmyapp.com/motorola ]. Treat as a hypothesis to test, not as fact.
* **Moto G04 OS upgrades.** Motorola's own spec page lists Android 14
  [DOCUMENTED https://en-us.support.motorola.com/app/answers/detail/a_id/178144/ ]. Whether it receives
  Android 15 is [UNVERIFIED] — third-party trackers disagree and I found no official Motorola statement. Assume
  the baseline device stays on API 34 and that API 35/36 behaviour matters mainly for the Edge 60 Fusion and
  future devices.

---

## 3. Wake locks vs FGS — what actually keeps the CPU running with the screen off

### 3.1 The facts

* `PowerManager.PARTIAL_WAKE_LOCK` (API 1, value `1`): "Ensures that the CPU is running; the screen and keyboard
  backlight will be allowed to go off. If the user presses the power button, then the screen will be turned off
  but the CPU will be kept on until all partial wake locks have been released."
  [DOCUMENTED https://developer.android.com/reference/android/os/PowerManager ]
* `FULL_WAKE_LOCK` deprecated in **API 17**; use `FLAG_KEEP_SCREEN_ON` instead. `SCREEN_DIM_WAKE_LOCK` /
  `SCREEN_BRIGHT_WAKE_LOCK` likewise superseded. `ON_AFTER_RELEASE` **cannot** be combined with
  `PARTIAL_WAKE_LOCK`. Same reference.
* Creating a wake lock needs no permission; **acquiring/releasing** needs `android.permission.WAKE_LOCK`
  (protection level `normal`). Same reference.
* A foreground service is *not* a wake lock. The official decision guide asks: "Running foreground service with
  screen off? … Would device suspension harm UX? If YES, you might need a wake lock" and explicitly says the FGS
  case still requires you to check "if you're already using an API or doing an action that declares a wake lock
  on your behalf."
  [DOCUMENTED https://developer.android.com/develop/background-work/background-tasks/awake ]
* APIs that hold a wake lock for you, per that page: **audio playback** (the audio system manages it),
  `WorkManager` / `JobScheduler` / `DownloadManager`, `ExoPlayer.setWakeMode()`, wake-up sensors
  (`Sensor.isWakeUpSensor()`), and alarms. **Camera and video encoding are not on that list.**

### 3.2 The decisive AOSP evidence

* `AudioFlinger`'s `ThreadBase::acquireWakeLock_l()` calls
  `mPowerManager->acquireWakeLockAsync(binder, POWERMANAGER_PARTIAL_WAKE_LOCK, …)` — so **audio capture and
  playback threads do hold a partial wake lock**.
  [DOCUMENTED https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/services/audioflinger/Threads.cpp ]
* Greps for `wakelock`/`WakeLock` in `CameraService.cpp`, `MediaCodec.cpp`, `CameraSource.cpp` and
  `MediaRecorderClient.cpp` return **nothing**.
  [DOCUMENTED — https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/services/camera/libcameraservice/CameraService.cpp ,
  .../media/libstagefright/MediaCodec.cpp , .../media/libstagefright/CameraSource.cpp ,
  .../media/libmediaplayerservice/MediaRecorderClient.cpp ]

### 3.3 Recommendation

**Hold exactly one `PARTIAL_WAKE_LOCK` in `RecorderService`, for exactly the duration of an active recording.**

```kotlin
private val wl: PowerManager.WakeLock by lazy {
    getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Roadguard:recording")   // hard-coded tag, no PII
        .apply { setReferenceCounted(false) }
}
private fun acquireWakeLock() { if (!wl.isHeld) wl.acquire() }
private fun releaseWakeLock() { if (wl.isHeld) wl.release() }   // in onDestroy AND on stop-recording
```

Rationale: with audio ON the AudioFlinger record thread would probably keep the CPU up for us, but with audio OFF
(a legitimate, likely-default dashcam configuration) nothing does, and the kernel can autosuspend, stalling the
encoder and corrupting segments. [INFERRED — chain: partial wake locks are the documented mechanism that keeps
the CPU running with the screen off; the camera/codec stack demonstrably takes none; therefore video-only capture
has no protection against suspend.] **NOT VERIFIED — needs on-device measurement** (see §11, test W1: does
video-only recording survive 30 min screen-off *without* the wake lock on a Moto G04?). Ship the wake lock;
measure whether it can be dropped later.

Best practices we must follow [DOCUMENTED https://developer.android.com/develop/background-work/background-tasks/awake/wakelock/best-practices ]:
hard-code the tag (Proguard-safe, aggregatable, no PII, no counters); hold the lock inside a foreground service
that shows a notification ("If a foreground service isn't appropriate for your use case, you probably shouldn't be
using a wake lock either"); keep acquire/release logic trivially simple, no state machines; always release in a
`finally`.

### 3.4 The cost we are accepting

* **Android vitals:** partial wake-lock use is "excessive" when all partial wake locks combined run for **≥ 2
  hours in a 24-hour period**, and if that happens in **> 5 % of app sessions over 28 days** it can affect the
  app's visibility on Play. Crucially: "Android vitals tracks wake lock duration only if the wake lock is held
  when the app is in the background **or is running a foreground service**" — i.e. our case is counted, not
  exempt. Exempted are user-initiated audio, user-initiated location, and JobScheduler user-initiated APIs.
  [DOCUMENTED https://developer.android.com/topic/performance/vitals/excessive-wakelock ]
* **User-facing restriction prompt:** the system prompts the user to restrict an app that holds "1 partial wake
  lock held for an hour when screen is off".
  [DOCUMENTED https://developer.android.com/develop/background-work/background-tasks/bg-work-restrictions ]

Mitigations to build in: (a) release the lock the instant recording stops, including on parking/auto-stop;
(b) offer an explicit "screen-off recording" setting so a user who never needs it never pays;
(c) never hold the lock while merely showing UI (`FLAG_KEEP_SCREEN_ON` covers that case at zero wake-lock cost);
(d) since we are not distributing via Play in the offline-first model, the Play-visibility risk is lower, but the
*user-visible restriction prompt* risk is real on any device.

---

## 4. Screen-off UX: what a normal app may legally do

| Option | API | What it actually does | Effect on camera/encoder | Verdict for Roadguard |
|---|---|---|---|---|
| `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON` (`Window.addFlags` / `View.keepScreenOn`) | 1 | "as long as this window is visible to the user, keep the device's screen turned on and bright" [DOCUMENTED https://developer.android.com/reference/android/view/WindowManager.LayoutParams ] | None; screen stays on, app stays TOP | **Use it** for the normal driving UI. Platform-managed, no permission, released automatically when the window goes away. Also documented as the preferred replacement for `FULL_WAKE_LOCK`. |
| `WindowManager.LayoutParams.screenBrightness = BRIGHTNESS_OVERRIDE_OFF` (`0.0f`, API 8) | 8 | "the screen or button backlight brightness should be set to the **lowest** value when this window is in front" — lowest, **not off** [DOCUMENTED same reference ] | None. The display panel is still powered and refreshing; the app stays TOP | **Use it** for "dark mode / night driving" — an in-app dimmed screen. It saves panel backlight power but *not* display-pipeline power, and it does **not** let the device sleep. Be honest in the UI copy: this is "dim", not "off". |
| Black full-screen overlay Activity (our own "screen off" screen) | any | Purely cosmetic. Device stays awake, GPU still composites | None | Cosmetic only; combine with `screenBrightness = 0f`. Must have an unmistakable tap-to-wake affordance. |
| Let the device actually sleep (drop `FLAG_KEEP_SCREEN_ON`, let the system screen-off timeout fire, or the user presses power) | — | Display off, app leaves TOP, `PROCESS_STATE_FOREGROUND_SERVICE`, kernel may autosuspend unless a wake lock is held | Camera keeps working (§2); encoder needs the wake lock (§3) | **This is the real power-saving mode.** Support it explicitly and hold the `PARTIAL_WAKE_LOCK`. |
| `DevicePolicyManager.lockNow()` / `lockNow(int)` | 8 / 26 | Locks the device immediately. Requires the caller to be an **active device admin that requested `DeviceAdminInfo.USES_POLICY_FORCE_LOCK`**, else `SecurityException`; from Android R the caller must additionally have `LOCK_DEVICE` **or** the device must have the device-admin feature, otherwise the call silently returns. "This API is intended for use only by device admins." [DOCUMENTED https://developer.android.com/reference/android/app/admin/DevicePolicyManager ] | Screen off; camera unaffected by the lock itself | **Do not use.** Requires a `DeviceAdminReceiver` and a user-granted device-admin activation — a hostile onboarding step, heavily policed by Play, and legacy device-admin policies are being deprecated. |
| `PowerManager.goToSleep()` | — | Not public API for third-party apps | — | Unavailable. |
| Change `Settings.System.SCREEN_OFF_TIMEOUT` | 1 | Needs `WRITE_SETTINGS` (special access, `ACTION_MANAGE_WRITE_SETTINGS`) and mutates a *global* user setting | — | Rejected: it changes system state behind the user's back. |

**Also relevant:** `FLAG_TURN_SCREEN_ON` and `FLAG_SHOW_WHEN_LOCKED` were both deprecated in **API 27**; use
`Activity.setTurnScreenOn(boolean)` / `Activity.setShowWhenLocked(boolean)` (or the `android:turnScreenOn` /
`android:showWhenLocked` manifest attributes) "to prevent an unintentional double life-cycle event".
[DOCUMENTED https://developer.android.com/reference/android/view/WindowManager.LayoutParams ]
Roadguard should use `setShowWhenLocked(true)` on the recording Activity so that a tap on the FGS notification
brings the dashcam UI up over the keyguard without an unlock — an important in-car ergonomic.

**Recommended Roadguard screen-off design (three explicit modes, user-selectable):**
1. **Bright** — `FLAG_KEEP_SCREEN_ON`, normal brightness. Highest power/heat.
2. **Dimmed** — `FLAG_KEEP_SCREEN_ON` + `screenBrightness = 0.0f` + dark theme + optional black overlay with a
   small "tap to wake" hint. Display still on.
3. **Display off** — clear `FLAG_KEEP_SCREEN_ON`, let the system time out, hold the `PARTIAL_WAKE_LOCK`, keep
   recording. Lowest power and lowest heat, which matters directly for the #2 priority (thermals) on a T606.

---

## 5. Keeping the process alive

### 5.1 Notification requirements

* Channel required; the notification must be at least `PRIORITY_LOW` / channel importance ≥ `IMPORTANCE_LOW`
  [DOCUMENTED https://developer.android.com/develop/background-work/services/fgs/launch ].
* By default the system **may defer** showing the FGS notification for a short time. Pass
  `Notification.FOREGROUND_SERVICE_IMMEDIATE` to `Notification.Builder.setForegroundServiceBehavior(int)`
  (added **API 31**) "in order to guarantee that visibility is never deferred"; the other values are
  `FOREGROUND_SERVICE_DEFAULT` and `FOREGROUND_SERVICE_DEFERRED`, and deferral is never guaranteed either way.
  [DOCUMENTED https://developer.android.com/reference/android/app/Notification.Builder ]
  → **Roadguard sets `FOREGROUND_SERVICE_IMMEDIATE`**: the user must see "Recording" instantly.
* `setOngoing(true)` (`FLAG_ONGOING_EVENT`): "Ongoing notifications cannot be dismissed by the user on locked
  devices, or by notification listeners" — but on **Android 14 the behaviour changed so that ongoing FGS
  notifications *are* dismissible** by the user on unlocked devices, with exceptions only for `CallStyle`, DPC /
  enterprise packages, media notifications, the default search selector, when the phone is locked, and "Clear
  all".
  [DOCUMENTED https://developer.android.com/reference/android/app/Notification.Builder ,
  https://developer.android.com/about/versions/14/behavior-changes-all ]
  → **Dismissing the notification does not stop the service.** But the user then has no visible handle, so we
  should re-post on state changes and rely on the Task Manager entry (§5.5).
* Content: put the *actionable* state in the notification — segment number, elapsed time, free space, and actions
  "Stop", "Protect current clip". Actions should target a `BroadcastReceiver`/`Service` in our own process (no
  Activity trampoline needed to *stop*; a trampoline **is** needed to *start* — §1.6).

### 5.2 `POST_NOTIFICATIONS` and the FGS

"Apps don't need to request the `POST_NOTIFICATIONS` permission in order to launch a foreground service. However,
apps must include a notification when they start a foreground service." If the user denies it, "they still see
notices related to foreground services in the Task Manager but don't see them in the notification drawer."
[DOCUMENTED https://developer.android.com/develop/ui/views/notifications/notification-permission ]
→ **Recording works with `POST_NOTIFICATIONS` denied.** Do not block onboarding on it; request it once, in
context, explaining that it is how the user sees recording state and storage warnings.

### 5.3 `START_STICKY`, process death, restart

* `START_STICKY`: "if this service's process is killed while it is started (after returning from
  `onStartCommand`), then leave it in the started state but don't retain this delivered intent. Later the system
  will try to re-create the service. Because it is in the started state, it will guarantee to call
  `onStartCommand` after creating the new service instance; if there are not any pending start commands to be
  delivered to the service, it will be called with a **null intent**, so you must take care to check for this."
  [DOCUMENTED https://developer.android.com/reference/android/app/Service ,
  https://developer.android.com/reference/androidx/core/app/ServiceCompat ]
* `START_REDELIVER_INTENT` re-delivers the last intent; `START_FLAG_REDELIVERY` and `START_FLAG_RETRY` flags mark
  those cases. Same reference.

**The trap.** After an LMK/OOM kill, `START_STICKY` re-creates the service with a *null* intent while the app is
in the **background** — so `startForeground(…, CAMERA|…)` at that moment falls foul of the §1.6 while-in-use rule
and the camera capability is denied even though the FGS itself may be allowed to start (the process transition is
system-initiated). [INFERRED — chain: the restart happens with no visible Activity and no BAL grant, so
`shouldAllowFgsWhileInUsePermissionLocked` returns `REASON_DENIED`.] **NOT VERIFIED — needs on-device
measurement** (test P1 in §11).

Therefore the correct restart policy is:

```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent == null) {                       // system-initiated restart after a kill
        // Persist-and-prompt, do NOT silently try to reopen the camera.
        restoreStateFromDisk()                  // finalise/repair the interrupted segment
        if (canStartRecordingLegally()) tryResume() else postResumePrompt()
        return START_STICKY
    }
    …
}
```
plus: persist "recording was active" + the current segment path in `SharedPreferences`/DataStore on every segment
rotation, so a restart can (a) repair the truncated MP4 and (b) know it should offer to resume; and read
`ActivityManager.getHistoricalProcessExitReasons()` → `ApplicationExitInfo.getReason()` on next start to
distinguish `REASON_LOW_MEMORY`, `REASON_USER_REQUESTED`, `REASON_USER_STOPPED`, `REASON_CRASH`,
`REASON_CRASH_NATIVE`, `REASON_ANR`, `REASON_EXCESSIVE_RESOURCE_USAGE`, `REASON_FREEZER`, `REASON_SIGNALED`,
`REASON_DEPENDENCY_DIED`, `REASON_PERMISSION_CHANGE`, `REASON_MEMORY_LIMITER`, `REASON_ANOMALY`,
`REASON_PACKAGE_UPDATED`, `REASON_PACKAGE_STATE_CHANGE`, `REASON_EXIT_SELF`, `REASON_INITIALIZATION_FAILURE`,
`REASON_OTHER`, `REASON_UNKNOWN` (all valid values of `getReason()`, API 30+)
[DOCUMENTED https://developer.android.com/reference/android/app/ApplicationExitInfo ]. This is our only offline,
no-telemetry way to know *why* recording stopped, and it should feed a local, user-visible "reliability log".

### 5.4 Surviving low-memory kills

* A process running a foreground service is **not** eligible for the cached-apps freezer: only *cached* processes
  are frozen (10 s after entering the cached state on Android 14+), and processes with "a bound foreground service
  or foreground status" are explicitly excluded.
  [DOCUMENTED https://source.android.com/docs/core/perf/cached-apps-freezer ]
* An FGS raises the process's `oom_score_adj` well above cached/service levels, so on a 4 GB Moto G04 we are
  unlikely but **not immune** to being killed. [INFERRED from `OomAdjuster` assigning
  `PROCESS_STATE_FOREGROUND_SERVICE` and a correspondingly better adj than `SERVICE_ADJ`/cached; the exact
  numeric adj on Motorola's build is NOT VERIFIED.]
* Practical mitigations (all under our control): one process only, no extra `:remote` processes; keep the
  steady-state Java heap small (avoid caching map tiles or frames in the heap — use `ByteBuffer`s / file-backed
  storage); handle `onTrimMemory(int)` by dropping the map tile cache and any preview-side buffers first, never
  the encoder path; never hold decoded bitmaps of video frames; fsync/finalise each MP4 segment on rotation so a
  kill loses at most the in-flight segment.
* `Service.onTaskRemoved(Intent rootIntent)` (API 14) is called "if the service is currently running and the user
  has removed a task that comes from the service's application" — unless `ServiceInfo.FLAG_STOP_WITH_TASK`
  (`= 1`, API 14) is set, in which case "the service will simply be stopped".
  [DOCUMENTED https://developer.android.com/reference/android/app/Service ,
  https://developer.android.com/reference/android/content/pm/ServiceInfo ]
  → **Do not set `android:stopWithTask="true"`.** Implement `onTaskRemoved` to keep recording and re-post the
  notification, because a driver swiping the app away from Recents almost never means "stop recording".

### 5.5 The Task Manager (Android 13+) — the user can force-stop us

Since Android 13 the notification-drawer Task Manager shows an "Active apps" list with a **Stop** button. Tapping
it **force-stops the entire app**: the app is removed from memory, the back stack is cleared, media playback
stops, the FGS notification is removed. "The system doesn't send your app any callbacks when the user taps the
Stop button. When your app starts back up, it's helpful to check for the `REASON_USER_REQUESTED` reason that's
part of the `ApplicationExitInfo` API." Scheduled jobs and alarms still run at their scheduled times. Testable
with `adb shell cmd activity stop-app <PACKAGE>`.
[DOCUMENTED https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping ]
→ We must treat `REASON_USER_REQUESTED` as "the user deliberately stopped recording" and **not** auto-resume.

### 5.6 Restoring recording after reboot — the API 35 wall

* `RECEIVE_BOOT_COMPLETED` is a `normal` permission; the docs warn it "can have a negative impact on the user
  experience by increasing the amount of time it takes the system to start and allowing applications to have
  themselves running without the user being aware of them. As such, you must explicitly declare your use of this
  facility to make that visible to the user."
  [DOCUMENTED https://developer.android.com/reference/android/Manifest.permission ]
* **Android 15 (targetSdk 35+): `BOOT_COMPLETED` receivers may NOT launch these FGS types —
  `dataSync`, `camera`, `mediaPlayback`, `phoneCall`, `mediaProjection`, `microphone`** (microphone since
  Android 14). "If a `BOOT_COMPLETED` receiver tries to launch any of those types of foreground services, the
  system throws `ForegroundServiceStartNotAllowedException`."
  [DOCUMENTED https://developer.android.com/about/versions/15/behavior-changes-15 ]
* AOSP confirms both the gate and the allowlist. `ActiveServices.FGS_BOOT_COMPLETED_RESTRICTIONS = 296558535L`,
  `@EnabledSince(targetSdkVersion = VANILLA_ICE_CREAM)` (= API 35), documented in source as: "Disables foreground
  service background starts from BOOT_COMPLETED broadcasts for all types **except**
  `FOREGROUND_SERVICE_TYPE_LOCATION`, `…_CONNECTED_DEVICE`, `…_REMOTE_MESSAGING`, `…_HEALTH`,
  `…_SYSTEM_EXEMPTED`, `…_SPECIAL_USE`." The check is
  `shouldAllowBootCompletedStart()` → `(foregroundServiceType & FGS_BOOT_COMPLETED_ALLOWLIST) != 0`, else
  `throw new ForegroundServiceStartNotAllowedException("FGS type <label> not allowed to start from BOOT_COMPLETED!")`.
  Test override: `adb shell am compat enable FGS_BOOT_COMPLETED_RESTRICTIONS <pkg>`.
  [DOCUMENTED https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/ActiveServices.java ]

**What this means, precisely, for Roadguard on `targetSdk` 36:**

| Approach | Legal? | Notes |
|---|---|---|
| `BOOT_COMPLETED` → `startForegroundService` with `camera` in the mask | **No** | `ForegroundServiceStartNotAllowedException` |
| `BOOT_COMPLETED` → start a `location`-only FGS, then `startForeground(..., +CAMERA)` later | **No** | The reason code on the record stays `REASON_BOOT_COMPLETED`, and `shouldAllowBootCompletedStart` is evaluated on **each** `startForeground` call against the type bitmask, so adding `camera` throws. [INFERRED from the AOSP call site: `if (!shouldAllowBootCompletedStart(r, foregroundServiceType)) throw …` inside the `startForeground` path, with `fgsStartReasonCode = r.getFgsAllowStart()`.] Even if it did not throw, the WIU capability would be denied. |
| `BOOT_COMPLETED` → post a notification "Roadguard is ready — tap to start recording", whose `contentIntent` opens our Activity, which then starts the FGS | **Yes** | The Activity is resumed ⇒ `PROCESS_STATE_TOP` ⇒ WIU capability granted. This is the recommended design. |
| `BOOT_COMPLETED` → post a **full-screen intent** notification to auto-open the UI | **No, in practice** | For apps targeting Android 14+, `USE_FULL_SCREEN_INTENT` switched from a normal permission to a special app access, and "only apps that have calling or alarm functionalities will have this permission enabled by default" (from 22 Jan 2025); check `NotificationManager.canUseFullScreenIntent()` and route users to `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`. A dashcam does not qualify by default. [DOCUMENTED https://developer.android.com/about/versions/14/behavior-changes-14 , https://support.google.com/googleplay/android-developer/answer/13392821 ] |
| `BOOT_COMPLETED` → launch an Activity directly | **No** | Background activity starts are blocked since Android 10 and were tightened further in Android 15. [DOCUMENTED https://developer.android.com/guide/components/activities/background-starts ] |
| Ask the user to disable battery optimisation, which *is* a List-A background-start exemption | Legal but insufficient | Battery-optimisation exemption is a **List A** exemption only; it does not defeat the `BOOT_COMPLETED` type allowlist (which is a separate, unconditional check) and does not by itself grant the while-in-use camera capability. [INFERRED — the AOSP allowlist check has no power-allowlist bypass, and `REASON_ALLOWLISTED_PACKAGE` in the WIU function refers to a platform package list, not the user power allowlist.] |

**Design decision: after reboot, Roadguard posts a high-visibility "tap to resume recording" notification (and,
if the user has enabled it, also fires a distinctive sound/vibration).** Additionally, because a car dock usually
means power is connected, register for `Intent.ACTION_POWER_CONNECTED` / `ACTION_BATTERY_CHANGED` and/or
`UsbManager` / `BluetoothDevice.ACTION_ACL_CONNECTED` (car stereo) as *cues to raise the notification's urgency*
— but never as a background camera-FGS start path. [INFERRED — none of those broadcasts are on the
while-in-use exemption list.] Document this honestly in the app: **Android does not permit a third-party app to
silently start recording video after a reboot.**

---

## 6. Doze, App Standby, Battery Saver, and battery-optimisation exemption

### 6.1 Doze

* Full Doze entry requires: device unplugged, screen off for some time, **and device stationary (via the
  significant motion detector)**. Lightweight Doze (Android 7+) needs only screen-off on battery and no SMD.
  Exit criteria: user interaction, **device movement**, screen on, imminent `AlarmClock` alarm.
  During Doze sleep: "Apps aren't allowed network access. App wakelocks ignored. Alarms are deferred." Wi-Fi scans
  stop; `SyncAdapter` syncs and `JobScheduler` jobs are deferred to maintenance windows, which become less
  frequent over time.
  [DOCUMENTED https://source.android.com/docs/core/power/platform_mgmt ,
  https://developer.android.com/traininging/monitoring-device-state/doze-standby — canonical URL:
  https://developer.android.com/training/monitoring-device-state/doze-standby ]
* `setAlarmClock()` alarms still fire; the system leaves Doze shortly before them. Same source.
* **Foreground services are not in the list of things Doze suspends.** [INFERRED — chain: the documented Doze
  restriction list is network / wake locks / alarms / jobs / syncs / Wi-Fi scans; FGS is absent from it; and the
  App Standby documentation separately states an app is *not* idle while it "has a process in the foreground
  (activity or foreground service)".]
* **In a moving car, full Doze should not engage at all**, because "device movement" is an exit criterion and
  "device is stationary (using SMD)" is an entry criterion. [INFERRED from the AOSP criteria above.] When parked
  with the engine off and the phone still, full Doze *can* engage — and then "App wakelocks ignored" is a direct
  threat to a screen-off recording session. **NOT VERIFIED — needs on-device measurement** (test D1 in §11).
* Runtime detection APIs we should log locally: `PowerManager.isDeviceIdleMode()` (API 23) +
  `ACTION_DEVICE_IDLE_MODE_CHANGED`, and `PowerManager.isDeviceLightIdleMode()` (API 33) +
  `ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED`.
  [DOCUMENTED https://developer.android.com/reference/android/os/PowerManager ]

### 6.2 App Standby buckets

Buckets: **active, working set, frequent, rare, restricted** (plus "never"). An app is placed in **active** when
it launches an activity, **runs a long-running foreground service**, or is tapped by the user from a
notification. The `restricted` bucket limits an app to one ~10-minute batched job session per day and one alarm
per day, even while charging.
[DOCUMENTED https://developer.android.com/topic/performance/appstandby ]
→ Roadguard sits in **active** while recording, so bucket restrictions are irrelevant *during* recording. They
matter only for any post-drive maintenance work, which we should therefore do with `WorkManager` while the app is
still foreground, not on a schedule.

### 6.3 Battery Saver — the one that really can break us

`PowerManager.getLocationPowerSaveMode()` (API 28) "Returns how location features should behave when battery
saver is on", with values `LOCATION_MODE_NO_CHANGE`, `LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF`,
**`LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF`**, `LOCATION_MODE_FOREGROUND_ONLY`,
`LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF`; monitor `isPowerSaveMode()` (API 21) and
`ACTION_POWER_SAVE_MODE_CHANGED`.
[DOCUMENTED https://developer.android.com/reference/android/os/PowerManager ]
→ **With Battery Saver on and an OEM policy of `ALL_DISABLED_WHEN_SCREEN_OFF`, our GPS overlay/map trail will go
dead the moment the screen turns off, even though video keeps recording.** Roadguard must query
`getLocationPowerSaveMode()` at recording start and on `ACTION_POWER_SAVE_MODE_CHANGED`, and warn the user
explicitly ("Battery Saver is on — GPS data will stop when the screen turns off"). This is a real, documented,
per-OEM-configurable behaviour, not folklore.

### 6.4 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — do not ship it

* `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` lets an app pop a dialog to add itself to the power
  allowlist; `PowerManager.isIgnoringBatteryOptimizations(String)` (API 23) reads the state; being allowlisted
  means "the system will not apply most power saving features to the app. Guardrails for extreme cases may still
  be applied."
  [DOCUMENTED https://developer.android.com/reference/android/os/PowerManager ]
* **Policy:** "Google Play policies prohibit apps from requesting direct exemption from Power Management
  features — Doze and App Standby — in Android 6.0 and above **unless the core function of the app is adversely
  affected**." The documented acceptable list is narrow (VOIP without FCM, safety apps, task automation,
  peripheral-companion apps with a persistent connection).
  [DOCUMENTED https://developer.android.com/training/monitoring-device-state/doze-standby ]

**Recommendation.** Do **not** declare `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and do **not** show the direct
dialog. Instead ship a **"Reliability check" screen** that:
1. reads `PowerManager.isIgnoringBatteryOptimizations(packageName)` and, if false, offers a button that opens
   `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (the *list* screen — allowed for any app, no
   permission required) with clear instructions: "find Roadguard → Don't optimize";
2. reads `ActivityManager.isBackgroundRestricted()` and, if true, tells the user to clear
   "Background restriction"/"Restricted" for the app;
3. reads `PowerManager.isPowerSaveMode()` + `getLocationPowerSaveMode()` and warns about the GPS consequence
   (§6.3);
4. checks `NotificationManagerCompat.areNotificationsEnabled()`;
5. on Motorola, names the exact OEM screens: `Settings > Battery > App standby optimizer` (turn off standby
   sleep/wakeup optimization for Roadguard) and `Settings > Battery > Auto launch management`
   [DOCUMENTED https://help.motorola.com/hc/1814/14/global/en-us/CG2007980805.html ];
6. persists the results into the local reliability log alongside the `ApplicationExitInfo` history (§5.3), so the
   user can see *why* a recording stopped without any telemetry leaving the device.

`ActivityManager.isBackgroundRestricted()` exists for (2) — and note that on AOSP a restricted app "cannot run
jobs, trigger alarms, or use the network, except when the app is in the foreground", with "the precise
restrictions imposed … determined by the device manufacturer."
[DOCUMENTED https://developer.android.com/develop/background-work/background-tasks/bg-work-restrictions ]
Whether a *camera FGS* survives that setting on a Motorola build is **NOT VERIFIED — needs on-device
measurement** (test M1 in §11).

---

## 7. Runtime permission flow on API 34+

### 7.1 The exact set

| When | Permission | Why |
|---|---|---|
| Onboarding, step 1 (required) | `CAMERA` | The product does not exist without it |
| Onboarding, step 2 (strongly recommended, skippable) | `ACCESS_FINE_LOCATION` **and** `ACCESS_COARSE_LOCATION` requested **in the same `requestPermissions` array** | Map + speed + geotagging. On API 31+ requesting FINE alone lets the user grant only "Approximate"; requesting both lets the dialog offer Precise/Approximate properly. [DOCUMENTED https://developer.android.com/develop/sensors-and-location/location/permissions ] |
| Onboarding, step 3 (optional) | `POST_NOTIFICATIONS` | Only to make recording state and storage warnings visible; recording works without it (§5.2) |
| **Only when the user flips the "Record audio" switch** | `RECORD_AUDIO` | Data minimisation; also a legal issue in many jurisdictions |
| Never | `ACCESS_BACKGROUND_LOCATION` | See §7.4 |

### 7.2 Requesting microphone lazily — the exact rule

`RECORD_AUDIO` must be granted **before** `startForeground()` is called with the `microphone` bit, or the system
throws `SecurityException`; the documented ordering is: check → request → only then `startForeground(...,
FOREGROUND_SERVICE_TYPE_MICROPHONE)`.
[DOCUMENTED https://developer.android.com/about/versions/14/changes/fgs-types-required ]
And if we record audio *without* the `microphone` FGS type while the app is not visible, the platform gives us
**silence**, not an error: "an app without a foreground service or foreground UI component that started to
capture … received silence, even if it was the only app capturing audio at the time."
[DOCUMENTED https://developer.android.com/media/platform/sharing-audio-input ]
Also note the privacy toggle behaviour: with "Sensors off"/mic toggle enabled the app "can still request
microphone access, but receive silence (zero-valued audio arrays) … No error is generated during active
recording."
[DOCUMENTED https://source.android.com/docs/core/interaction/sensors/sensors-off ]
→ **Roadguard must detect all-zero audio and surface "microphone is muted by the system" in the UI**, because the
platform will not tell us.

Concrete flow:
```
user toggles "Record audio" ON
  → if !granted(RECORD_AUDIO): requestPermissions([RECORD_AUDIO])
      → denied  → revert the toggle, explain, offer app-settings deep link
      → granted → restart capture session from the (visible) Activity with mask = CAMERA|LOCATION|MICROPHONE
  → if granted: restart capture session with the microphone bit
user toggles OFF → restart capture session with mask = CAMERA|LOCATION (drop the microphone bit,
                   so we stop appearing in the mic privacy indicator)
```
Declare `<uses-permission android:name="android.permission.RECORD_AUDIO" />` and
`FOREGROUND_SERVICE_MICROPHONE` in the manifest unconditionally (manifest declaration ≠ runtime grant); the app
will simply never pass the microphone bit until the user opts in.

### 7.3 Manifest permission declarations must cover the union of types

`android:foregroundServiceType="camera|location|microphone"` must be declared even though we sometimes pass only
`camera|location` at runtime, because "if the type isn't specified at runtime, it defaults to the manifest
values" and passing a runtime type **not** present in the manifest throws.
[DOCUMENTED https://developer.android.com/about/versions/14/changes/fgs-types-required ,
https://developer.android.com/develop/background-work/services/fgs/launch ]

### 7.4 Do we need `ACCESS_BACKGROUND_LOCATION`? — **No.** Precisely:

* A foreground service with `android:foregroundServiceType="location"` needs only `ACCESS_FINE_LOCATION` or
  `ACCESS_COARSE_LOCATION`; the documented behaviour is "Your app retains access when it's placed in the
  background, such as when the user presses the Home button on their device or turns their device's display off."
  [DOCUMENTED https://developer.android.com/develop/sensors-and-location/location/permissions ]
* `ACCESS_BACKGROUND_LOCATION` is only required if we start the location FGS **while already in the background**
  ("the service cannot access location unless your app has background location access")
  [DOCUMENTED https://developer.android.com/about/versions/11/privacy/foreground-services ] — which we never do
  (§1.7).
* Positive reasons to avoid it: it is a **hard restricted permission** ("cannot be held by an app until the
  installer on record allowlists the permission")
  [DOCUMENTED https://developer.android.com/reference/android/Manifest.permission ], it triggers a separate
  "Allow all the time" settings trip in the permission flow, and Play has a dedicated background-location policy
  review [DOCUMENTED https://developer.android.com/develop/sensors-and-location/location/permissions ].

**Decision: Roadguard does not declare or request `ACCESS_BACKGROUND_LOCATION`.** If a future feature genuinely
needs a background start of the location FGS (e.g. parking-mode impact detection triggered by a broadcast), it
must be redesigned around a user-visible start instead.

---

## 8. Android 15 (API 35) — every change that touches us

| Change | Effect on Roadguard | Action |
|---|---|---|
| **`BOOT_COMPLETED` FGS restrictions** (`camera`, `microphone`, `dataSync`, `mediaPlayback`, `phoneCall`, `mediaProjection` blocked; allowlist = `location`, `connectedDevice`, `remoteMessaging`, `health`, `systemExempted`, `specialUse`) | Kills silent auto-resume after reboot | §5.6 notification-tap design. Test with `adb shell am compat enable FGS_BOOT_COMPLETED_RESTRICTIONS <pkg>` then `adb shell am broadcast -a android.intent.action.BOOT_COMPLETED <pkg>` |
| **`dataSync` 6 h / 24 h timeout**, `Service.onTimeout(int,int)`, `RemoteServiceException` on overrun | None *if* we never use `dataSync` | **Never declare `dataSync`.** If any future export/backup needs it, use `mediaProcessing` (also 6 h) or `WorkManager` |
| **New `mediaProcessing` type** (6 h / 24 h) | Candidate for offline post-processing (e.g. re-muxing, thumbnailing) | Use it for batch work; implement `onTimeout(startId, fgsType)` → `stopSelf()` within seconds or the app is ANR-ed |
| **`SYSTEM_ALERT_WINDOW` background-start exemption narrowed** — now needs a *visible* `TYPE_APPLICATION_OVERLAY` window; verify via `View.getWindowVisibility()`/`onWindowVisibilityChanged(int)` | None (we don't use overlays) | Do not architect around overlays |
| **Edge-to-edge enforced** — content draws behind system bars; `Window.setStatusBarColor()`, `Window.setNavigationBarColor()` (gesture nav), `Window.setDecorFitsSystemWindows()` and the `statusBarColor`/`navigationBarColor` attrs are **disabled**; `layoutInDisplayCutoutMode` treated as `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` | **Directly hits our 50/50 video+map split.** Insets must be applied, or the map's bottom controls sit under the nav bar and the video's top edge under the status bar/cutout | Call `WindowCompat.enableEdgeToEdge(window)` (androidx.core) or `enableEdgeToEdge()` from androidx.activity; then `ViewCompat.setOnApplyWindowInsetsListener` with `WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()`, and pad the **controls**, not the video surface — the video/map 50/50 split should be measured against the full window while the *touch targets* are inset. `androidx.core:core` ≥ 1.16.0-beta01 adds `ProtectionLayout` for system-bar scrims; ≥ 1.16.0-alpha01 adds `ViewGroupCompat.installCompatInsetsDispatch()` |
| **`Configuration` no longer excludes system bars** — `Configuration.screenWidthDp`, `screenHeightDp`, `smallestScreenWidthDp` and `Display.getSize(Point)` now include the bars | Any layout maths keyed on those values shifts by the bar heights | Use `WindowMetricsCalculator.computeCurrentWindowMetrics()` / `WindowInsets`, never `Display.getSize` |
| `elegantTextHeight` defaults to `true` | Minor text-metrics shift | Test the HUD/overlay text |
| `AudioManager.requestAudioFocus()` requires being top app or running an FGS, else `AUDIOFOCUS_REQUEST_FAILED` | Only if we play sounds | We hold an FGS, so fine |
| **16 KB page size** | Native code (our encoder helpers, map renderer, any `.so`) must be 16 KB-aligned | See §9 |
| TLS 1.0/1.1 forbidden; `String.format` argument index 0 illegal; `Arrays.asList().toArray()` component type; Kotlin `List.removeFirst()/removeLast()` colliding with `SequencedCollection` (→ `NoSuchMethodError` on API 34 devices with `compileSdk=35`) | Build-time hygiene | Replace `removeFirst()` → `removeAt(0)`, `removeLast()` → `removeAt(lastIndex)` throughout |

[DOCUMENTED https://developer.android.com/about/versions/15/behavior-changes-15 ,
https://developer.android.com/develop/ui/views/layout/edge-to-edge ]

---

## 9. Android 16 (API 36) — every change that touches us

| Change | Applies to | Effect on Roadguard | Action |
|---|---|---|---|
| **Orientation / resizability / aspect-ratio restrictions on large screens** — `android:screenOrientation`, `android:resizableActivity`, `android:minAspectRatio`, `android:maxAspectRatio`, `setRequestedOrientation()`/`getRequestedOrientation()` all **ignored** | Only displays with **smallest width ≥ 600dp** | **Does not affect our phones.** Moto G04 is 720×1612 at 269 ppi ⇒ ~360dp smallest width; the Edge 60 Fusion is likewise a phone. So `screenOrientation` still works | Do **not** ship the opt-out property. Note the change is per-display, so it *could* apply if the phone is ever driven onto a ≥ 600dp external display / desktop-windowing surface [UNVERIFIED — the docs don't call out external displays]. Since our layout is fully responsive by design (portrait video-top/map-bottom, landscape video-left/map-right), we simply **don't lock orientation at all** and the change becomes a non-issue. Opt-out, if ever needed: `<property android:name="android.window.PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY" android:value="true"/>`, and note it "won't apply when targeting API level 37" |
| **Predictive back enforced** — `onBackPressed()` is no longer called and `KeyEvent.KEYCODE_BACK` is not dispatched | targetSdk 36 | If any screen overrides `onBackPressed()`, back navigation silently breaks | Use `OnBackPressedDispatcher`/`OnBackPressedCallback` (`androidx.activity` ≥ 1.6.0-alpha05) everywhere; set `android:enableOnBackInvokedCallback="true"`. Never `android:enableOnBackInvokedCallback="false"` |
| **Edge-to-edge opt-out removed** — `R.attr#windowOptOutEdgeToEdgeEnforcement` is disabled on Android 16 devices | all | Reinforces §8 | Insets are mandatory |
| **JobScheduler quota enforcement** — jobs started while the app is TOP, and jobs running concurrently with a foreground service, now obey job runtime quotas; standby bucket affects regular and expedited job runtime | all apps, regardless of targetSdk | Any `WorkManager` work we run *while recording* can be cut short; check `WorkInfo.getStopReason()` / `JobParameters.getStopReason()` / `JobScheduler#getPendingJobReasonsHistory` | Do not put any recording-critical work in `WorkManager`. Segment finalisation, storage reclamation and the ring-buffer delete pass must run **inside the recording FGS**, on its own thread. Test with `adb shell am compat enable OVERRIDE_QUOTA_ENFORCEMENT_TO_FGS_JOBS <pkg>` |
| New `STOP_REASON_TIMEOUT_ABANDONED` for jobs GC'd without `jobFinished()` | all | Diagnostics only | Log it |
| `JobInfo.Builder#setImportantWhileForeground(boolean)` fully ignored; `isImportantWhileForeground()` returns `false` | all | Remove any use | — |
| **Ordered-broadcast `android:priority` no longer honoured across processes** (only within the same app process); priority clamped to `SYSTEM_LOW_PRIORITY+1 .. SYSTEM_HIGH_PRIORITY-1` | all | Don't rely on priority to win a race on e.g. `ACTION_POWER_CONNECTED` | — |
| **16 KB page size compat mode** + `android:pageSizeCompat` manifest attribute to suppress the backcompat dialog | all | If any `.so` is 4 KB-aligned, users on 16 KB devices see a compat dialog | Build 16 KB-aligned (below) and do **not** rely on `pageSizeCompat` |
| Intent-redirect hardening (opt-out via `Intent.removeLaunchSecurityProtection()`) | targetSdk 36 | We don't forward nested intents | — |
| `MediaStore#getVersion()` now app-unique | targetSdk 36 | Only if we cache MediaStore state | Use it only for change detection |
| `View#announceForAccessibility` deprecated | all | Use `setAccessibilityPaneTitle` / `setAccessibilityLiveRegion` / `CONTENT_CHANGE_TYPE_ERROR` | Applies to our "recording started/stopped" announcements |
| Automatic themed app icons (Android 16 QPR2+) | all | Provide a monochrome layer in the adaptive icon | Design task |
| ART internal changes delivered via Play system updates | all Android 12+ | Never touch non-SDK interfaces | — |

**16 KB page size, exact requirements** [DOCUMENTED https://developer.android.com/guide/practices/page-sizes ]:
apps targeting API 35+ must support 16 KB pages on 64-bit devices for Google Play, deadline **1 February 2027**.
Java/Kotlin-only apps are already compatible. For native code: **NDK r28+** aligns to 16 KB by default; on
r27 and lower add `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384`. **AGP 8.5.1+** is required for
16 KB zip-aligned packaging with uncompressed shared libraries; on AGP ≤ 8.5 set
`android.packaging.jniLibs.useLegacyPackaging = true`; on AGP ≤ 8.0 set
`android.bundle.enableUncompressedNativeLibs=false`. Verify with `zipalign -c -P 16 -v 4 app.apk` (build-tools
35.0.0+) and `llvm-objdump -p lib.so | grep LOAD` (must show `align 2**14` or higher). Detect at runtime with
`getpagesize()` / `sysconf(_SC_PAGESIZE)`, never a hard-coded `4096`; check a device with
`adb shell getconf PAGE_SIZE` (16 KB devices report `16384`).

**Camera/media changes in Android 16 relevant to us:** the API-36 behaviour-change pages I read list no camera
FGS or camera-access policy changes. [DOCUMENTED https://developer.android.com/about/versions/16/behavior-changes-16 ,
https://developer.android.com/about/versions/16/behavior-changes-all — absence of such a section.] Android 16 does
add a *shared*-mode camera open where "the priority of the client accessing the camera determines the primary
client" and "when a higher priority client opens a camera in normal mode, the system evicts all shared camera
clients" [UNVERIFIED in detail — surfaced via search summary of source.android.com automotive multi-client docs;
not confirmed for handhelds]. Roadguard should not attempt shared-mode camera access.

---

## 10. Forward look: Android 17 (API 37)

Android 17 exists and introduces **background audio hardening**: on Android 17+ all apps with background audio
interactions must have a visible activity **or** a foreground service that is not `shortService`; and apps
targeting **API 37** must additionally run an FGS **with while-in-use capabilities**. Impacted APIs and outcomes:
`AudioTrack.write()`/`AAudioStream_write()`/OpenSL ES → silently silenced; `AudioManager.requestAudioFocus()` →
`AUDIOFOCUS_REQUEST_FAILED`; `setStreamVolume()`/`adjustStreamVolume()`/`setRingerMode()` → silently ignored.
Test with `adb shell cmd audio set-enable-hardening <enable|disable|throw>`; failures are logged under the
`AudioHardening` logcat tag (`level: full` = FGS running but lacks WIU capability; `level: partial` = no FGS).
[DOCUMENTED https://developer.android.com/about/versions/17/changes/bg-audio ]
Because Roadguard always starts its FGS from a visible Activity (hence *with* WIU capability) and only *captures*
audio rather than playing it, the expected impact is nil — but the phrase "WIU restrictions block access to
sensitive permissions (location, camera, microphone) **and audio APIs** in Android 17+" is a strong signal that
the visible-Activity start pattern in §1.7 is the durable one. Also note the API-36 large-screen orientation
opt-out explicitly stops working at API 37.

---

## 11. Google Play policy reality check (relevant even for an offline app, if it is ever listed)

* Declaring FGS types in Play Console (App content page) requires, per type: a description of the functionality,
  the user impact if the task is deferred and if it is interrupted, **a link to a video demonstrating the steps
  the user takes in the app to trigger the feature**, and a specific use case chosen from a preset list.
* The preset list's `TYPE_CAMERA` entry is "Background Camera Streaming — Continue to access the camera from the
  background. For example, video chat apps that allow for multitasking." **Continuous dashcam recording is not a
  listed preset** — but the same page states twice that the list is non-exhaustive: "This is a non-exhaustive
  list; if you do not see your use case listed, you can enter your use case manually."
* `TYPE_LOCATION` presets include "Background Location Updates: Navigation — For example, continuing driving
  navigation in maps, ride tracking for ride share" — a good fit for our map/trail.
* `TYPE_MICROPHONE` preset: "Background Audio Access — Capture audio input, for example, voice commands for
  virtual assistant without saving, voice recording."
* "User perceptible means that the user should be aware that a foreground service task is running on their
  device. Users can be considered aware if they initiate the action themselves … Your app can also make users
  aware of an ongoing foreground service by presenting a clear and accurate notification."
* `TYPE_SPECIAL_USE` is the escape hatch "in limited scenarios"; "All foreground service types are subject to
  review."

[DOCUMENTED https://support.google.com/googleplay/android-developer/answer/13392821 ]

→ **Roadguard declares `camera` + `location` (+ `microphone`) with manually-entered use cases**, and the
notification (§5.1) plus the user-initiated start (§1.7) are exactly what satisfies "user perceptible". Do **not**
reach for `specialUse`.

---

## 12. Consolidated manifest fragment

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- FGS plumbing: all protection level "normal", granted at install -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

    <!-- Runtime (dangerous) -->
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <!-- Deliberately ABSENT:
         ACCESS_BACKGROUND_LOCATION          (see §7.4)
         REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (see §6.4)
         USE_FULL_SCREEN_INTENT               (see §5.6)
         INTERNET is optional/offline-first — declared only if map download is built in -->

    <uses-feature android:name="android.hardware.camera.any" android:required="true" />

    <application
        android:enableOnBackInvokedCallback="true">   <!-- API 36 predictive back, §9 -->

        <activity
            android:name=".ui.RecordingActivity"
            android:exported="true"
            android:showWhenLocked="true"             <!-- API 27+, replaces FLAG_SHOW_WHEN_LOCKED -->
            android:launchMode="singleTask"
            android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|density|uiMode" />
            <!-- NOTE: no android:screenOrientation. Orientation follows the device, per the product
                 requirement "phone portrait -> portrait video". Also sidesteps the API 36 large-screen
                 orientation change entirely (§9). -->

        <service
            android:name=".record.RecorderService"
            android:exported="false"
            android:stopWithTask="false"              <!-- default, but be explicit: §5.4 -->
            android:foregroundServiceType="camera|location|microphone" />

        <receiver
            android:name=".boot.BootReceiver"
            android:exported="true"
            android:directBootAware="false">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>
        <!-- BootReceiver MUST NOT start the camera FGS (§5.6). It posts a
             "tap to resume recording" notification whose contentIntent opens RecordingActivity. -->

    </application>
</manifest>
```

---

## 13. Quick-reference decision table

| Question | Answer | Where |
|---|---|---|
| Can we record video with the screen off on 14/15/16? | Yes, with a `camera`-type FGS started while visible | §2 |
| Does locking the screen kill the camera? | No — the gate is UID active / process capability, not display state | §2.1–2.2 |
| Is a camera FGS time-limited? | No. Only `shortService` (3 min), `dataSync` (6 h), `mediaProcessing` (6 h) | §1.4 |
| Do we need a `PARTIAL_WAKE_LOCK`? | Yes for video-only capture; audio capture already holds one via AudioFlinger | §3 |
| Can we auto-resume recording after reboot? | **No** on targetSdk 35+. Notification-tap → Activity → FGS | §5.6 |
| Do we need `ACCESS_BACKGROUND_LOCATION`? | No | §7.4 |
| Do we need `POST_NOTIFICATIONS` for the FGS? | No, but the notification is invisible without it | §5.2 |
| Ask for battery-optimisation exemption? | No — guide the user to the settings list instead | §6.4 |
| Can we turn the screen truly off from inside the app? | No. `screenBrightness=0f` is "lowest", not off. `lockNow()` needs device admin | §4 |
| Does the API 36 orientation change affect us? | No — it is ≥ 600dp only, and we don't lock orientation | §9 |
| What kills recording that we must handle? | `onDisconnected`, `ERROR_CAMERA_IN_USE`/`_DISABLED`/`_DEVICE`/`_SERVICE`, privacy toggle, Task Manager force-stop, LMK kill, Battery Saver location cut | §2.3, §5.5, §6.3 |

---

## 14. Open questions / must-measure-on-device

Every item below is unresolved by documentation and must be settled on a **Moto G04 (Android 14)** and a
**Motorola Edge 60 Fusion**, with results recorded back into this file.

| ID | Question | Exact test that settles it |
|---|---|---|
| **W1** | Is a `PARTIAL_WAKE_LOCK` actually required for *video-only* screen-off recording, or does something in the Unisoc/MediaTek camera or codec HAL hold a kernel wake lock? | Build a debug flag that skips `acquireWakeLock()`. Start video-only recording, press power to sleep the screen, leave the device unplugged and stationary for 45 min. Compare `adb shell dumpsys power \| grep -A20 "Wake Locks"` before/after, then check the MP4 durations of segments 1..15 and `adb logcat -b all` for encoder stalls. Expected failure signature: segment durations shrink or stop after autosuspend. Repeat with audio enabled to confirm the AudioFlinger lock masks the problem. |
| **W2** | Real battery cost of the partial wake lock in each of the three screen modes (§4), in mAh/hour at 1080p30. | `adb shell dumpsys batterystats --reset`, record 60 min in each mode, `adb shell dumpsys batterystats <pkg>`; also read `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW`. |
| **D1** | Does full Doze engage while parked (stationary, screen off, unplugged) with our FGS + wake lock running, and if so does "app wakelocks ignored" stop the encoder? | Park scenario: `adb shell dumpsys deviceidle step` repeatedly to force `IDLE`, or leave the device flat and still for 60+ min. Poll `PowerManager.isDeviceIdleMode()` from the service and log transitions; verify segment continuity across the transition. Also `adb shell dumpsys deviceidle` to read the state machine. |
| **D2** | Does light Doze (screen off, on battery, moving) affect anything we depend on? | Log `isDeviceLightIdleMode()` + `ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED` during a real 30 min drive with the screen off. |
| **M1** | Does a camera FGS keep running on a Motorola build when the app is set to "Background restriction / Restricted", and when Motorola's "App standby optimizer" / "Auto launch management" are left at their defaults? | Set Restricted via `Settings > Apps > Roadguard > Battery`, start recording from the UI, sleep the screen, wait 60 min. Then repeat with `Settings > Battery > App standby optimizer` set to "Auto" for Roadguard. Check `ApplicationExitInfo` reasons on next launch. |
| **M2** | Does the alleged Motorola `com.motorola.batterycare` "Improve battery while inactive" hourly kill exist on the G04 / Edge 60 Fusion, and does it hit an FGS? | 4-hour screen-off recording run; on next launch dump `getHistoricalProcessExitReasons()` and look for hourly-cadence kills with `REASON_USER_REQUESTED`/`REASON_OTHER`/`REASON_SIGNALED`. Check `adb shell pm list packages \| grep batterycare`. |
| **P1** | After a `START_STICKY` restart with a null intent (post-LMK kill), does `startForeground(..., CAMERA)` throw, silently lose the camera capability, or succeed? | `adb shell am kill <pkg>` (or `adb shell cmd activity kill <pkg>`) while recording with the screen off; inspect logcat for `ForegroundServiceStartNotAllowedException` / `SecurityException` / the AOSP string `Foreground service started from background can not have location/camera/microphone access` / `cannot open camera … from background`. Then read the capability mask via `adb shell dumpsys activity processes \| grep -A3 <pkg>`. |
| **P2** | What is the actual `oom_score_adj` of our process while running the camera FGS with the screen off, on a 4 GB G04, and does the system ever kill it under memory pressure from other apps? | `adb shell cat /proc/$(pidof <pkg>)/oom_score_adj` in each state; then induce pressure (open several heavy apps) and check `getHistoricalProcessExitReasons()` for `REASON_LOW_MEMORY`. |
| **S1** | Does the camera capture session actually survive the Activity being destroyed if the session's outputs are only service-owned surfaces? | Start recording, `adb shell input keyevent KEYCODE_HOME`, then `adb shell am kill-all` — no; instead finish the Activity programmatically and confirm no `onDisconnected`/session reconfigure appears in logcat and segment timing is unbroken. |
| **S2** | Does the Moto G04's camera stack tolerate a many-hour continuous session, or does it need periodic session recreation? | 4-hour continuous run at the chosen profile; log every `onCaptureFailed`, frame-drop and HAL error; check `dumpsys media.camera`. (Overlaps with the camera-pipeline document; the platform-restriction angle is only whether the FGS state changes.) |
| **B1** | With Battery Saver ON, what does `getLocationPowerSaveMode()` return on each device, and does GPS really stop with the screen off? | Enable Battery Saver, log the returned constant, record with the screen off, and check whether `FusedLocationProviderClient`/`LocationManager` callbacks stop. |
| **B2** | Confirm the exact `SERVICE_START_FOREGROUND_TIMEOUT_MS` in force on Motorola builds (AOSP default is 30 000 ms, but it is `DeviceConfig`-overridable). | `adb shell device_config get activity_manager service_start_foreground_timeout_ms` (and `…_anr_delay_ms`). |
| **B3** | Does the API 36 `OVERRIDE_QUOTA_ENFORCEMENT_TO_FGS_JOBS` quota change cut short any `WorkManager` work we run during recording? | `adb shell am compat enable OVERRIDE_QUOTA_ENFORCEMENT_TO_FGS_JOBS <pkg>`, run the storage-reclaim worker during a long recording, log `WorkInfo.getStopReason()`. |
| **B4** | Whether a notification-action `PendingIntent` targeting a `Service` (no Activity trampoline) grants the while-in-use camera capability. | From a fully backgrounded app, tap a notification action that calls `startForegroundService`; check logcat for the AOSP "can not have location/camera/microphone access" line and whether the camera opens. If it fails, the Activity trampoline in §5.6 is mandatory (which is the design assumption anyway). |
| **B5** | Whether the Moto G04 ever receives Android 15/16 (affects whether the API 35/36 gates matter on the baseline device). | Check `Settings > About phone > Android version` after OTA, and Motorola's official software-upgrade page: https://en-us.support.motorola.com/app/software-upgrade/ |
