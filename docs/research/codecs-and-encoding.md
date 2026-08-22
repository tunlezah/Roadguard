# Codecs and Encoding — H.264 vs HEVC vs AV1 for Roadguard

**Scope:** video encoder selection, bitrate, storage/hour and encoder-related thermal/reliability
behaviour for an offline-first Android dashcam on `minSdk = 34` (Android 14) through API 36
(Android 16). Baseline device: **Motorola moto g04** (Unisoc T606, 4 GB, Android 14). Also-must-run-well
device: **Motorola edge 60 fusion** (MediaTek Dimensity 7400, Android 15).

---

## Bottom line

**Ship hardware H.264 (AVC) as the default and only "always on" recording path. Offer hardware HEVC
as an explicit, opt-in "smaller files" mode that is only selectable when runtime probing proves a
*hardware* HEVC encoder exists at the requested size *and* frame rate. Reject AV1 entirely for
recording.** The reasons are structural, not preference: (a) Android's CDD only ever mandates
*one of* VP8/H.264 as an encoder, and only mandates HEVC at **Main Profile Level 3 up to 512×512**,
so a 1080p HEVC encoder is never guaranteed; (b) the AOSP *software* HEVC encoder is capped at
**512×512**, so if the hardware HEVC encoder is missing or fails there is **no software fallback at
720p or 1080p** — whereas software AVC exists up to 2048×2048; (c) AOSP's software AV1 encoder ships
`enabled="false"` and hardware AV1 *encode* appears only on Google Tensor parts, never on Unisoc or
MediaTek; (d) Android's own HEVC→AVC compatibility-transcoding safety net **only handles files up to
one minute**, and Roadguard's segments are three minutes, so an HEVC segment handed to a third-party
app is on its own. Default target: **1080p30 H.264 High profile if available (else Main, else
Baseline) at 12 Mbps VBR, 1 s I-frame interval, no B-frames** → ≈5.5 GB/hour. Fall back
720p30 @ 6 Mbps on thermal/encoder pressure. Use CameraX ≥ 1.6.0, which muxes through the Media3
MP4 muxer and rewrites the `moov` box roughly every second, so a crash or power loss costs about one
second of the current segment rather than the whole file.

## Evidence key

| Tag | Meaning |
|---|---|
| `[DOCUMENTED]` | Stated in official documentation, an official spec, a vendor product page, or AOSP/AndroidX source. URL or file path cited. |
| `[INFERRED]` | Derived by reasoning or arithmetic from `[DOCUMENTED]` facts. The chain is stated inline. |
| `[UNVERIFIED]` | Plausible but not confirmed by an authoritative source in this session. Do not code against it without checking. |
| `[MEASURED]` | *Not used anywhere in this document.* No measurement was performed in this session. Anything needing measurement is listed in the final section. |

---

## 1. What the platform actually guarantees

### 1.1 CDD mandatory / recommended encoder support

All rows below are from the Android 16 CDD, section 5.2, cross-checked against the Android 14 CDD.
`[DOCUMENTED]` — https://source.android.com/docs/compatibility/16/android-16-cdd and
https://source.android.com/docs/compatibility/14/android-14-cdd

| CDD ref | Requirement (verbatim substance) | Practical meaning for Roadguard |
|---|---|---|
| 5.2 `[C-1-1]` | If the device has a ≥2.5″ screen, a video output port, or declares `android.hardware.camera.any`: **MUST include the support of at least one of the VP8 or H.264 video encoders**, available to third-party apps. | H.264 is *not* strictly mandated — VP8 alone would satisfy the letter of the CDD. In practice every Android phone ships an H.264 encoder `[INFERRED]`, but you must still probe rather than assume. |
| 5.2 (SHOULD) | SHOULD support **both** VP8 and H.264 encoders. | — |
| 5.2 `[C-2-1]` | If the device exposes any of H.264/VP8/VP9/HEVC encoders: **MUST support dynamically configurable bitrates**. | `MediaCodec.setParameters()` with `PARAMETER_KEY_VIDEO_BITRATE` is safe to rely on for thermal throttling of bitrate mid-recording. |
| 5.2 `[C-4-1]` | If the device provides hardware-accelerated video/image encoders **and** a camera exposed through `android.camera`: **all hardware accelerated video and image encoders MUST support encoding frames from the hardware camera(s)**. | A hardware encoder advertised by `MediaCodecList` is required to work from a camera Surface. This is the strongest guarantee we have for the recording path. |
| 5.2 VBR (SHOULD) | With `MediaFormat.KEY_BITRATE_MODE = BITRATE_MODE_VBR`, encoded bitrate SHOULD NOT be >15 % over target between I-frame intervals, and SHOULD NOT be >100 % over target over a 1 s sliding window — *as long as it does not impact the minimum quality floor*. | VBR can legitimately burst to 2× target for a second. Storage headroom must absorb this. |
| 5.2 `[C-SR-2]` | With `BITRATE_MODE_CBR`, STRONGLY RECOMMENDED not to exceed target by >15 % over a 1 s window. | CBR gives much tighter storage predictability — see §5.5. |
| 5.2.2 `[C-1-1]` | H.264: **MUST support Baseline Profile Level 3**. ASO/FMO/RS optional and recommended *not* used. | Baseline L3 = max 1620 macroblocks frame size → ~720×480. **Baseline L3 alone cannot do 720p or 1080p.** |
| 5.2.2 `[C-1-2]` | H.264: MUST support the SD encoding profiles in the CDD table. | SD-low 320×240@20 fps 384 Kbps; SD-high 720×480@30 fps 2 Mbps. |
| 5.2.2 (SHOULD) | SHOULD support **Main Profile Level 4**; SHOULD support the HD profiles. | High profile is *never* mandated. Probe `CodecCapabilities.profileLevels`. |
| 5.2.2 `[C-2-1]` | **If the device reports H.264 encoding support for 720p or 1080p through the media APIs, it MUST support**: 720p = 1280×720 @ 30 fps @ **4 Mbps**; 1080p = 1920×1080 @ 30 fps @ **10 Mbps**. | This is the only hard bitrate/rate guarantee we get at HD. It is conditional on the device *advertising* HD H.264, which both target SoCs do (§3). |
| 5.2.5 `[C-1-1]` | HEVC: **MUST support Main Profile Level 3 up to 512×512 resolution**. | **The floor for HEVC is 512×512.** Nothing above that is mandatory, ever. |
| 5.2.5 `[C-SR-1]` | STRONGLY RECOMMENDED to support 720×480 SD and the HD profiles **if there is a hardware encoder**. | Table: SD 720×480@30 = 1.6 Mbps; 720p@30 = 4 Mbps; 1080p@30 = 5 Mbps; UHD@30 = 20 Mbps. Note HEVC's 1080p figure (5 Mbps) is exactly **half** H.264's (10 Mbps) — Google's own codified 2:1 expectation. |
| 5.2.6 (new in Android 14 CDD) | AV1: **conditional on the device supporting an AV1 codec at all.** If supported: MUST support Main Profile 8-bit and 10-bit; MUST publish performance data via `getSupportedFrameRatesFor()` or `getSupportedPerformancePoints()`; MUST accept HDR metadata. **If the AV1 encoder is hardware accelerated**, `[C-2-1]` MUST support up to and including 1080p. | **Nothing in CDD 5.2.6 requires an AV1 *encoder* to exist.** Table (for reference): SD 5 Mbps, 720p 8 Mbps, 1080p 16 Mbps, UHD 50 Mbps. |
| 5.1.8 (containers) | H.264 AVC → 3GPP (.3gp), MPEG-4 (.mp4), MPEG-2 TS (.ts, not seekable), Matroska (.mkv, **decode only**). H.265 HEVC → MPEG-4 (.mp4), Matroska (**decode only**). AV1 → MPEG-4 (.mp4), Matroska (**decode only**). | MP4 is the only container to consider for all three. |
| 5.1.7 `[C-1-2]` | Video encoders and decoders MUST support `COLOR_FormatYUV420Flexible`. | Not relevant for Surface-input recording, but relevant if a CPU-side path is ever needed. |
| 5.1.10 `[C-1-4]` | Codecs named `OMX.google.*` or `c2.android.*` **MUST NOT** be characterized as vendor or hardware-accelerated. | `isHardwareAccelerated()` is therefore trustworthy for *excluding* the AOSP software codecs. |
| 5.1.10 `[C-1-7]` | Codecs that use hardware acceleration MUST be characterized as hardware accelerated. | Combined with `[C-1-5]`, `isHardwareAccelerated()` / `isSoftwareOnly()` are the CDD-sanctioned way to tell them apart. |

**Media Performance Class (MPC), for context only — neither target device is expected to declare it.**
`[DOCUMENTED]` CDD 16 §2.2.7.1: MPC-V (`Build.VERSION_CODES.V`) requires `[5.1/H-1-16]` at least one
hardware encoder supporting 4K60; `[5.1/H-1-7]` hardware video encoder init latency ≤40 ms for
1080p-or-smaller under load; `[5.1/H-1-14]` a hardware AV1 **decoder** (Main 10, Level 4.1);
`[5.1/H-1-18]` "MUST support AV1 encoder which can encode up to **480p resolution at 30 fps and
1 Mbps**". Even at Performance Class V the AV1 encoder bar is 480p30 @ 1 Mbps and can be satisfied in
software. `[INFERRED]` This is decisive evidence that AV1 encode is not a realistic recording target.

### 1.2 The AOSP *software* encoders you actually get — and their hard caps

Source: AOSP `frameworks/av/media/libstagefright/data/media_codecs_sw.xml` (branch `main`)
`[DOCUMENTED]` — https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/media/libstagefright/data/media_codecs_sw.xml
(cross-checked against `media_codecs_google_c2_video.xml` in the same directory).

| Codec entry | MIME | `enabled` | `variant` | Max size | Block-count / blocks-per-second | Bitrate range | Features |
|---|---|---|---|---|---|---|---|
| `c2.android.avc.encoder` (alias `OMX.google.h264.encoder`) | `video/avc` | on | `slow-cpu,!slow-cpu` | `!slow-cpu`: **2048×2048**; `slow-cpu`: **1808×1808** | `!slow-cpu`: block-count 1–8192, bps 1–245760 (16×16 blocks); `slow-cpu`: block-count **1–1620**, bps **1–40500** | `!slow-cpu` 1–12 Mbps; `slow-cpu` 1–10 Mbps | `intra-refresh`, `qp-bounds`, `bitrate-modes VBR,CBR` |
| `c2.android.hevc.encoder` | `video/hevc` | on | **`!slow-cpu` only** | **512×512** | block-count 1–4096 (8×8 blocks), bps 1–259200 | 1–10 Mbps | `bitrate-modes VBR,CBR,CQ`, `qp-bounds` |
| `c2.android.av1.encoder` | `video/av01` | **`enabled="false"`**, `minsdk="34"` | `slow-cpu,!slow-cpu` | `!slow-cpu`: 1920×1920 (block-count ≤8100 ⇒ 1080p); `slow-cpu`: 720×720 (≤1350 ⇒ 720×480) | **no `blocks-per-second` limit declared** | `!slow-cpu` 1–20 Mbps; `slow-cpu` 1–5 Mbps | `bitrate-modes VBR,CBR,CQ`, `qp-bounds`, `quality 0–100`, `complexity 0–5` |
| `c2.android.apv.encoder` | `video/apv` | `enabled="false"`, `minsdk="36"` | `!slow-cpu` | 1920×1920 | ≤32768 | 1–240 Mbps | `bitrate-modes VBR` |

Four consequences that drive the whole design:

1. **`[INFERRED]` Software HEVC cannot encode 720p or 1080p on any stock Android device.** 512×512 is
   the declared maximum, and on a `slow-cpu` device the entry is absent entirely. Therefore *HEVC at
   HD has no software fallback*: if the hardware HEVC encoder is absent, broken, or reclaimed, HEVC
   recording at HD is impossible, not merely slow.
2. **`[INFERRED]` Software AVC on a `slow-cpu` device caps out well below 720p.** block-count ≤1620
   at 16×16 blocks ≈ 414 720 px (720×576-class); 1280×720 needs 3600 blocks. And
   40500 bps ÷ 1620 = **25 fps** at the maximum block count. So even AVC's software fallback is an
   SD-only lifeboat on the low end. 1080p recording is therefore *entirely* dependent on the
   hardware encoder on the moto g04.
3. **`[DOCUMENTED]` AOSP's software AV1 encoder is `enabled="false"`.** It exists in the config from
   `minsdk=34` but is off unless an OEM opts in. This directly contradicts the "Encoder and decoder
   are mandatory beginning with Android 14" note on
   https://developer.android.com/media/platform/supported-formats — see §4 for how to reconcile.
4. **`[INFERRED]` The AV1 software entry declares no `blocks-per-second` limit**, so
   `VideoCapabilities.getSupportedFrameRatesFor(w,h)` for it will be bounded only by the generic
   frame-rate range — i.e. it will report frame rates it cannot possibly sustain. Never trust that
   method as a performance signal (§2.4).

Also note the `variant="slow-cpu"` mechanism: `media_codecs_sw.xml` `<Settings>` contains
`<Variant name="slow-cpu" enabled="false" />` as the default `[DOCUMENTED]` (same file). **How a
device is classified as `slow-cpu` is not documented in any source located in this session
`[UNVERIFIED]`** — treat it as an unknown and probe the resulting capabilities, never the variant.

### 1.3 Container / muxer support — exact API levels

`[DOCUMENTED]` AOSP `frameworks/base/media/java/android/media/MediaMuxer.java` `addTrack()` javadoc
table (https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/refs/heads/main/media/java/android/media/MediaMuxer.java):

| Codec | MP4 (`MUXER_OUTPUT_MPEG_4`) | WEBM | Supported from SDK |
|---|---|---|---|
| `MIMETYPE_VIDEO_AVC` (H.264) | ✓ | — | **17** |
| `MIMETYPE_VIDEO_HEVC` (H.265) | ✓ | — | **24** |
| `MIMETYPE_VIDEO_AV1` | ✓ | — | **31** |
| `MIMETYPE_VIDEO_VP9` | — | ✓ | 24 |
| `MIMETYPE_AUDIO_AAC` | ✓ | — | 17 |

At `minSdk 34` all three video codecs are muxable into MP4, so the container is **not** a
differentiator. `MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4` is available since API 18
(`api-versions.xml`, android-36). Also `[DOCUMENTED]` from the same javadoc: for MP4 use
`setOrientationHint()` rather than `MediaFormat.KEY_ROTATION`; codec-specific data **must** be
supplied via the `MediaFormat` passed to `addTrack()`, never through `writeSampleData()`.

### 1.4 Profile/level ceilings — what level you actually need

`[DOCUMENTED]` from AOSP `MediaCodecInfo.java`, `VideoCapabilities.applyLevelLimits()`
(https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/refs/heads/main/media/java/android/media/MediaCodecInfo.java).
`MBPS` = max macroblocks/s, `FS` = max frame size in macroblocks, `BR` = max bitrate in kbps
(multiplied by 1000 for Baseline/Main/Extended, **×1250 for High / ConstrainedHigh**, ×3000 for High10).

| AVC level | MBPS | FS (MB) | BR (kbps ×factor) | Fits |
|---|---|---|---|---|
| `AVCLevel3` (CDD floor) | 40 500 | 1 620 | 10 000 | 720×576@25, **not 720p** |
| `AVCLevel31` | 108 000 | 3 600 | 14 000 | 1280×720@30 exactly (3600 MB) |
| `AVCLevel4` | 245 760 | 8 192 | 20 000 (High: 25 000) | **1920×1088@30** (8160 MB, 245 760/8160 = 30.1 fps) |
| `AVCLevel41` | 245 760 | 8 192 | 50 000 (High: 62 500) | 1080p30 at high bitrate |
| `AVCLevel42` | 522 240 | 8 704 | 50 000 | **1080p60** |

| HEVC level | FR | FS (luma samples) | BR (kbps) | Fits |
|---|---|---|---|---|
| `HEVCMainTierLevel3` (CDD floor) | 30 | 552 960 | 6 000 | ~960×576. **Not 720p** (921 600 samples) |
| `HEVCMainTierLevel31` | 33.75 | 983 040 | 10 000 | 720p30 |
| `HEVCMainTierLevel4` | 30 | 2 228 224 | 12 000 | 1080p30 |
| `HEVCMainTierLevel41` | 60 | 2 228 224 | 20 000 | 1080p60 |

**`[INFERRED]` Implication:** the CDD's mandatory floors (AVC Baseline L3, HEVC Main L3 ≤512×512)
are both *below* 720p. Every HD capability on any device is either a conditional CDD requirement or
purely vendor discretion. **Probe, never assume.**

### 1.5 The minimum quality floor (API 31+) — it will override your bitrate

`[DOCUMENTED]` `MediaCodec` class javadoc, section "Minimum Quality Floor for Video Encoding"
(https://developer.android.com/reference/android/media/MediaCodec#qualityFloor):

> "Beginning with `Build.VERSION_CODES.S`, Android's Video MediaCodecs enforce a minimum quality
> floor. … This quality floor is applied when the codec is in **Variable Bitrate (VBR)** mode; it is
> **not applied when the codec is in Constant Bitrate (CBR)** mode. The quality floor enforcement is
> also restricted to a particular size range; this size range is currently for video resolutions
> **larger than 320x240 up through 1920x1080**. … The metric used to choose these targets is the
> VMAF … with a target score of **70** for selected test sequences. The typical effect is that some
> videos will generate a **higher bitrate than originally configured**."

Consequences for a dashcam:
- **`[INFERRED]` Under VBR, our configured bitrate is a floor-ish hint, not a cap.** On a
  high-motion, high-detail scene (which is exactly what driving is), the framework may spend more
  than we asked. All GB/hour figures in §5.6 are therefore **lower bounds** in VBR mode.
- **`[INFERRED]` 1080p is inside the enforced range; 1440p and above are outside it.** If we ever
  add a 1440p mode, its quality is entirely on us.
- **`[INFERRED]` CBR is the mode to use if predictable storage matters more than quality-per-bit.**
  CBR loses the quality floor but gains the CDD `[C-SR-2]` ≤15 % / 1 s overshoot bound.
  Recommendation: default VBR (better plates in hard scenes); expose CBR for users who need exact
  "hours of retention" arithmetic.

---

## 2. Runtime probing — the exact APIs, with API levels

API levels below come from the installed SDK's authoritative
`/home/user/android-sdk/platforms/android-36/data/api-versions.xml` `[DOCUMENTED]`.

### 2.1 MIME type constants

| Codec | Constant | Literal | `since` |
|---|---|---|---|
| H.264 | `MediaFormat.MIMETYPE_VIDEO_AVC` | `"video/avc"` | 21 |
| HEVC | `MediaFormat.MIMETYPE_VIDEO_HEVC` | `"video/hevc"` | 21 |
| AV1 | `MediaFormat.MIMETYPE_VIDEO_AV1` | `"video/av01"` | 29 |
| APV | `MediaFormat.MIMETYPE_VIDEO_APV` | `"video/apv"` | **36** |
| VP8 | `MediaFormat.MIMETYPE_VIDEO_VP8` | `"video/x-vnd.on2.vp8"` | 21 |
| VP9 | `MediaFormat.MIMETYPE_VIDEO_VP9` | `"video/x-vnd.on2.vp9"` | 21 |
| AAC | `MediaFormat.MIMETYPE_AUDIO_AAC` | `"audio/mp4a-latm"` | 21 |

### 2.2 Enumerating encoders

```java
// API 21+. REGULAR_CODECS excludes secure-only / tunneled-only components.
MediaCodecInfo[] all = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
```

`[DOCUMENTED]` `MediaCodecList.REGULAR_CODECS` javadoc: "enumerate only codecs that are suitable for
regular (buffer-to-buffer) decoding or encoding"; `ALL_CODECS` additionally returns components that
"only work with special input or output surfaces, such as secure-only or tunneled-only codecs".
**Use `REGULAR_CODECS` for recording.** Both constants `since 21`; `getCodecInfos()` `since 21`.

`[DOCUMENTED]` `MediaCodecList` javadoc on ordering, from `VideoCapabilities.getAchievableFrameRatesFor`:
> "Codecs are listed in `MediaCodecList` in the **preferred order as defined by the device
> manufacturer**. As such, applications should use the **first suitable codec** in the list to
> achieve the best balance between power use and performance."

So: iterate in list order, take the first that passes all gates. Do not sort by any heuristic of
your own.

`MediaCodecList.findEncoderForFormat(MediaFormat)` (`since 21`) is a convenient shortcut but returns
only a name and gives no visibility into *why* something was chosen — use it only as a cross-check.

### 2.3 `MediaCodecInfo` classification flags

| Method | `since` | Semantics (verbatim from AOSP javadoc) |
|---|---|---|
| `isEncoder()` | 16 | — |
| `getName()` | 16 | — |
| `getCanonicalName()` | **29** | "returns the name of the underlying codec name, which must not be another alias" |
| `isAlias()` | **29** | "Query if the codec is an alias for another underlying codec." |
| `isVendor()` | **29** | "provided by the Android platform (false) or the device manufacturer (true)" |
| `isSoftwareOnly()` | **29** | "Software-only codecs are more secure as they run in a tighter security sandbox. On the other hand, **software-only codecs do not provide any performance guarantees**." |
| `isHardwareAccelerated()` | **29** | "This attribute is provided by the device manufacturer. **Note that it cannot be tested for correctness.**" |
| `getSupportedTypes()` | 16 | — |
| `getSecurityModel()` | 36 | `SECURITY_MODEL_SANDBOXED` / `SECURITY_MODEL_MEMORY_SAFE` |

**`[INFERRED]` Correct gate for "is this a real hardware encoder":**
`info.isEncoder() && info.isHardwareAccelerated() && !info.isSoftwareOnly() && !info.isAlias()`.
Deduplicate on `getCanonicalName()`. Also skip names ending in `.secure` — those are DRM
components (`Feature name="special-codec" required="true"` in vendor configs, §3.3) and are
irrelevant to camera recording.

### 2.4 `VideoCapabilities` — which methods are performance signals and which are lies

All methods below `since 21` unless noted.

| Method | Is it a performance signal? |
|---|---|
| `getSupportedWidths()` / `getSupportedHeights()` | No — hard size range only. |
| `getSupportedWidthsFor(int height)` / `getSupportedHeightsFor(int width)` | No. Throws `IllegalArgumentException` for an unsupported argument — **wrap in try/catch**. |
| `getWidthAlignment()` / `getHeightAlignment()` | No, but **critical** — see §2.7. |
| `getBitrateRange()` | Hard clamp on `KEY_BIT_RATE`. Use it. |
| `getSupportedFrameRates()` | **NO.** Javadoc: "This is **not a performance indicator**. Rather, it expresses the limits specified in the coding standard…" |
| `getSupportedFrameRatesFor(int w, int h)` | **NO.** Same javadoc caveat. Implementation is literally `blocksPerSecondRange / blockCount` intersected with the declared frame-rate range — i.e. it reflects whatever numbers the vendor typed into `media_codecs*.xml`. See §3.3 for a real device where this yields **120 fps for 1080p HEVC**. |
| `areSizeAndRateSupported(int w, int h, double fps)` | Structural feasibility only — same underlying `supports()` check. Necessary, not sufficient. |
| `isSizeSupported(int w, int h)` | Structural only. |
| `getAchievableFrameRatesFor(int w, int h)` — **`since 23`, `@Nullable`** | **YES**, when non-null. Javadoc: "corresponds closer to sustained performance *in tested configurations*… one can expect to achieve sustained performance higher than the lower limit more than 50 % of the time". Returns **`null`** and logs "Codec did not publish any measurement data" when the vendor shipped no `measured-frame-rate-*` limits. |
| `getSupportedPerformancePoints()` — **`since 29`, `@Nullable`** | **YES**, when non-null/non-empty. Javadoc: "This is a **performance guarantee provided by the device manufacturer** for hardware codecs based on hardware capabilities of the device." May return `null` (vendor not updated) or an empty list (vendor guarantees nothing). |
| `PerformancePoint.covers(MediaFormat)` / `covers(PerformancePoint)` — `since 29` | The correct way to test a target against the list. Constants include `HD_30`, `HD_60`, `FHD_30`, `FHD_60`, `UHD_30`. |

**Rule: `getSupportedPerformancePoints()` → else `getAchievableFrameRatesFor()` → else treat the
encoder as having *unknown* performance and derate the target.** Never promote
`getSupportedFrameRatesFor()` into that chain.

Additional `CodecCapabilities` probes:

| Item | `since` | Use |
|---|---|---|
| `getVideoCapabilities()` | 21 | Entry point. |
| `getEncoderCapabilities()` | 21 | → `isBitrateModeSupported(BITRATE_MODE_VBR / _CBR / _CQ / _CBR_FD)`; `getQualityRange()` (`since 28`); `getComplexityRange()`. `BITRATE_MODE_CBR_FD` is `since 31`. |
| `getMaxSupportedInstances()` | **23** | Number of concurrent sessions. Relevant if we ever add a second stream. |
| `isFormatSupported(MediaFormat)` | 21 | Final gate — pass the exact format you will configure. |
| `profileLevels` (`CodecProfileLevel[]`) | 16 | The only way to know whether `AVCProfileHigh` / `AVCProfileMain` / `HEVCProfileMain` and the needed level are offered. |
| `isFeatureSupported(FEATURE_QpBounds)` | feature const `since 31` | Gates `KEY_VIDEO_QP_MIN` / `KEY_VIDEO_QP_MAX` (both `since 31`). |
| `isFeatureSupported(FEATURE_IntraRefresh)` | feature const `since 24` | Gates `KEY_INTRA_REFRESH_PERIOD` (`since 24`). CDD 5.1.7 `[C-3-1]`: if advertised, MUST support refresh periods 10–60 frames within 20 % accuracy `[DOCUMENTED]`. |
| `isFeatureSupported(FEATURE_EncodingStatistics)` | `since 33` | Gives per-frame QP/statistics; useful for an on-device quality telemetry panel that never leaves the device. |

Relevant profile/level constants and their API levels: `AVCProfileBaseline`/`AVCProfileMain`/
`AVCProfileHigh` (all `since` ≤16), `AVCProfileConstrainedBaseline`/`AVCProfileConstrainedHigh`
(`since 27`), `AVCLevel3`…`AVCLevel51` (≤16), `AVCLevel52` (`since 21`);
`HEVCProfileMain`, `HEVCMainTierLevel3`…`HEVCMainTierLevel62` (all `since 21`);
`AV1ProfileMain8`, `AV1ProfileMain10`, `AV1Level2`…`AV1Level73` (all `since 29`).

### 2.5 `EncoderProfiles` / `CamcorderProfile` — the OEM's own opinion

`[DOCUMENTED]` `api-versions.xml`:

| API | `since` | Notes |
|---|---|---|
| `CamcorderProfile.getAll(String cameraId, int quality)` → `EncoderProfiles` | **31** | The modern entry point. `cameraId` is the Camera2 id string. |
| `CamcorderProfile.get(int)` / `get(int,int)` | ≤9 | **Deprecated in API 31.** Only exposes one codec per quality. |
| `CamcorderProfile.hasProfile(int)` / `hasProfile(int,int)` | 11 | Cheap existence test. |
| `EncoderProfiles.getVideoProfiles()` → `List<EncoderProfiles.VideoProfile>` | 31 | **Multiple codecs per quality level** — this is how you discover that the OEM ships both AVC and HEVC at 720p but only AVC at 1080p. |
| `EncoderProfiles.getRecommendedFileFormat()`, `getDefaultDurationSeconds()`, `getAudioProfiles()` | 31 | — |
| `VideoProfile.getMediaType()` (returns the MIME string), `getCodec()`, `getBitrate()`, `getFrameRate()`, `getWidth()`, `getHeight()`, `getProfile()` | 31 | The OEM's tuned bitrate for that codec at that size. **Best available per-device bitrate prior** — better than any generic table. |
| `VideoProfile.getBitDepth()`, `getChromaSubsampling()`, `getHdrFormat()`; `HDR_NONE`, `YUV_420` | **33** | Filter to `HDR_NONE` + `YUV_420` + `getBitDepth()==8` for SDR. |

Quality constants of interest: `QUALITY_LOW`, `QUALITY_HIGH`, `QUALITY_480P` / `QUALITY_720P` /
`QUALITY_1080P` (`since 11`), `QUALITY_2160P` (`since 21`), `QUALITY_QHD`/`QUALITY_2K`/`QUALITY_VGA`
(`since 30`).

**`[INFERRED]` Use `EncoderProfiles` as the bitrate prior and `MediaCodecList`+`VideoCapabilities`
as the feasibility gate.** They disagree on real devices (§3.3): `media_profiles` is authored by the
camera team and `media_codecs` by the codec team, and neither validates the other.

### 2.6 How CameraX surfaces all of this

Latest **stable** CameraX is **1.6.1** `[DOCUMENTED]`
(https://developer.android.com/jetpack/androidx/releases/camera — versions 1.6.1, then 1.7.0-alpha03).

| CameraX API | Availability | What it does |
|---|---|---|
| `Recorder.getVideoCapabilities(CameraInfo)` | 1.6.x | Qualities available with the default capabilities source. |
| `Recorder.getVideoCapabilities(CameraInfo, int source)` | 1.6.x | `source` = `Recorder.VIDEO_CAPABILITIES_SOURCE_CAMCORDER_PROFILE` (**0**, default) or `Recorder.VIDEO_CAPABILITIES_SOURCE_CODEC_CAPABILITIES` (**1**). |
| `Recorder.Builder.setVideoCapabilitiesSource(int)` | 1.6.x | Switches the source. `SOURCE_CODEC_CAPABILITIES` lets you use qualities the `CamcorderProfile` table omits. |
| `Recorder.Builder.setQualitySelector(QualitySelector)` | 1.6.x | `Quality.SD / HD / FHD / QHD / UHD / HIGHEST / LOWEST`; `QualitySelector.fromOrderedList(list, FallbackStrategy)`. |
| `Recorder.Builder.setTargetVideoEncodingBitRate(int)` | 1.6.x | Sets `VideoSpec.bitrate`; **bypasses** CameraX's own bitrate derivation. |
| `Recorder.Builder.setAspectRatio(int)`, `setRequiredFreeStorageBytes(long)` | 1.6.x | Storage-safety hook lives here. |
| `QualitySelector.getResolution(CameraInfo, Quality)` | 1.6.x | Resolves a `Quality` to an actual `Size`. |
| `VideoCapture.getSelectedQuality()`, `getResolutionInfo()` | 1.6.x | Post-bind verification. |
| **`Recorder.Builder.setVideoMimeType(String)`**, `setAudioMimeType(String)`, `Recorder.getSupportedVideoMimeTypes()` (`@ExperimentalMimeTypeApi`), `Recorder.getVideoCapabilities(CameraInfo, String mime)` | **1.7.0-alpha02 and later only** | The *only* public CameraX API that lets an app choose HEVC vs H.264. Release note: "Introduced APIs for granular control over encoding formats… Preferred formats can be explicitly configured via `Recorder.Builder.setVideoMimeType()`" (I4620e, b/491319384) `[DOCUMENTED]`. |

**`[DOCUMENTED]` What CameraX does when you *don't* pick a MIME type** — AndroidX `androidx-main`
`camera/camera-video/src/main/java/androidx/camera/video/internal/config/VideoConfigUtil.kt`:

- `outputFormatToVideoMime()`: MP4/3GPP → `VIDEO_ENCODER_MIME_MPEG4_DEFAULT = MediaFormat.MIMETYPE_VIDEO_AVC`; WebM → VP8.
- `getDynamicRangeDefaultMime()`: `ENCODING_SDR` → **`MIMETYPE_VIDEO_AVC`** ("For SDR, default to h264 (AVC)"); HLG/HDR10/HDR10+ → HEVC; Dolby Vision → Dolby Vision.
- `resolveCompatibleVideoProfile(videoMime, dynamicRange, videoProfiles)` picks the **first**
  `EncoderProfiles.VideoProfile` matching the requested MIME and the dynamic range's HDR format and
  bit depth.

**`[INFERRED]` Therefore: on stable CameraX 1.6.x, SDR recording is H.264. You get H.264 by
default, for free, with no API surface required — which is exactly the recommendation in this
document.** Choosing HEVC on 1.6.x would require either CameraX 1.7.0-alpha or a hand-rolled
MediaCodec path.

**`[DOCUMENTED]` CameraX's bitrate derivation** (`VideoEncoderConfigVideoProfileResolver.kt`,
`VideoEncoderConfigDefaultResolver.kt`, `VideoConfigUtil.scaleBitrate`):

```
resolvedBitrate = baseBitrate
                × (actualBitDepth / baseBitDepth)
                × (actualFrameRate / baseFrameRate)
                × (actualWidth  / baseWidth)
                × (actualHeight / baseHeight)          // linear in each dimension
```

- With an `EncoderProfiles.VideoProfile` available, `baseBitrate` = `videoProfile.getBitrate()` and
  the base size/rate come from that profile.
- With **no** profile, `VideoEncoderConfigDefaultResolver` uses
  `VIDEO_BITRATE_BASE = 14_000_000`, `VIDEO_SIZE_BASE = Size(1280, 720)`,
  `VIDEO_FRAME_RATE_BASE = 30`, `VIDEO_BIT_DEPTH_BASE = 8`, with the source comment
  *"Base config based on generic 720p H264 quality will be scaled by actual source settings."*
- **`[INFERRED]`** That fallback yields 14 Mbps at 720p30 and
  `14 × (1920/1280) × (1080/720) = **31.5 Mbps** at 1080p30` — roughly **3× the CDD's 10 Mbps 1080p
  guarantee** and ~14 GB/hour. **Always call `setTargetVideoEncodingBitRate()` explicitly** rather
  than letting the fallback fire.
- CameraX then reports the encoder's clamp via
  `((VideoEncoderInfo) mVideoEncoder.getEncoderInfo()).getSupportedBitrateRange()`
  (`Recorder.java`), which wraps `VideoCapabilities.getBitrateRange()`.

**`[DOCUMENTED]` The `MediaFormat` CameraX configures** (`VideoEncoderConfig.java`
`toMediaFormat()`): `KEY_COLOR_FORMAT`, `KEY_BIT_RATE`, `KEY_FRAME_RATE` (= encode rate),
`KEY_I_FRAME_INTERVAL` = **`VIDEO_INTRA_FRAME_INTERVAL_DEFAULT = 1`** (one second),
`KEY_PROFILE` when known, and — when capture rate ≠ encode rate — `KEY_CAPTURE_RATE`,
`KEY_OPERATING_RATE`, and `KEY_PRIORITY = 0` ("Smaller value, higher priority" = realtime).
It does **not** set `KEY_BITRATE_MODE`, so the encoder default (typically VBR) applies and the
API 31+ quality floor is in play. It does **not** set `KEY_MAX_B_FRAMES`, so the platform default of
**0 B-frames** applies (`MediaFormat.KEY_MAX_B_FRAMES` javadoc: "The default value is 0, which means
that no B frames are allowed.") — which is exactly what we want (§7).

### 2.7 Encoder size alignment can silently crop the recorded frame

`[DOCUMENTED]` AndroidX `camera/camera-video/.../VideoCapture.java`,
`adjustCropRectToValidSize(Rect cropRect, Size resolution, VideoEncoderInfo)`:

> "This method resizes the crop rectangle to a valid size. The valid size must fulfill: the multiple
> of `VideoEncoderInfo.getWidthAlignment()`/`getHeightAlignment()` alignment; in the scope of Surface
> resolution and `VideoEncoderInfo.getSupportedWidths()`/`getSupportedHeights()`. … it seeks to
> shrink or enlarge the size with the smallest amount of change … The new cropping rectangle position
> … is then calculated by extending or indenting from the center of the original cropping rectangle."

It also honours `videoEncoderInfo.canSwapWidthHeight()` (backed by the vendor
`<Feature name="can-swap-width-height" />`) via `SwappedVideoEncoderInfo`.

**`[INFERRED]` Direct consequence for Roadguard's "recorded video must not be cropped" constraint:**
if the selected encoder reports `heightAlignment = 16`, then 1920×1080 is not a legal encode size
(1080 / 16 = 67.5) and CameraX will pick the nearest legal size — 1920×1072 (shrink, an 8-row crop)
or 1920×1088 (enlarge). Both target SoCs' *Codec2* encoders report `alignment = 2x2` (§3.3), so 1080p
is exact there; but an older OMX path on the same silicon declares `16x16` (§3.3). **The codec chooser
must read `getWidthAlignment()`/`getHeightAlignment()` and prefer an encoder whose alignment divides
the target height exactly**, and must surface any residual crop to the storage/metadata layer rather
than hiding it.

### 2.8 Reference probe (pseudocode — this is the shape to implement)

```kotlin
data class EncoderPick(
    val codecName: String,        // MediaCodecInfo.getName()
    val mime: String,             // video/avc | video/hevc
    val size: Size,               // exact, alignment-legal
    val fps: Int,
    val bitrateBps: Int,
    val profile: Int?, val level: Int?,
    val bitrateMode: Int,         // BITRATE_MODE_VBR | _CBR
    val perfEvidence: PerfEvidence // PERFORMANCE_POINTS | ACHIEVABLE_RATES | UNKNOWN
)

fun probe(target: Size, fps: Int, mime: String): EncoderPick? {
    val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()
    for (info in list) {                                    // manufacturer preference order
        if (!info.isEncoder) continue
        if (!info.supportedTypes.any { it.equals(mime, true) }) continue
        if (!info.isHardwareAccelerated || info.isSoftwareOnly) continue   // API 29+
        if (info.isAlias) continue
        if (info.name.endsWith(".secure")) continue
        val caps = info.getCapabilitiesForType(mime)
        val vc   = caps.videoCapabilities ?: continue

        // 1. alignment-legal size at or below target, never above
        val size = alignDownWithin(target, vc) ?: continue   // uses getWidthAlignment/HeightAlignment
        if (!vc.isSizeSupported(size.width, size.height)) continue
        if (!vc.areSizeAndRateSupported(size.width, size.height, fps.toDouble())) continue

        // 2. real performance evidence, in strict priority order
        val pps = vc.supportedPerformancePoints                      // API 29, nullable
        val evidence = when {
            pps != null && pps.isNotEmpty() ->
                if (pps.any { it.covers(formatFor(mime, size, fps)) }) PERFORMANCE_POINTS
                else return@probe null.also { /* try next codec */ }
            vc.getAchievableFrameRatesFor(size.width, size.height)   // API 23, nullable
                ?.let { it.lower >= fps } == true -> ACHIEVABLE_RATES
            else -> UNKNOWN                                          // e.g. Unisoc: publishes nothing
        }

        // 3. bitrate: EncoderProfiles prior, clamped to the codec's own range
        val prior = encoderProfilesBitrateFor(cameraId, mime, size, fps)   // API 31
        val bitrate = (prior ?: policyBitrate(mime, size, fps))
            .coerceIn(vc.bitrateRange.lower, vc.bitrateRange.upper)

        // 4. best available profile/level
        val (prof, lvl) = bestProfileLevel(caps.profileLevels, mime, size, fps, bitrate)

        // 5. bitrate mode
        val ec = caps.encoderCapabilities
        val mode = when {
            userWantsPredictableStorage && ec?.isBitrateModeSupported(BITRATE_MODE_CBR) == true ->
                BITRATE_MODE_CBR
            ec?.isBitrateModeSupported(BITRATE_MODE_VBR) == true -> BITRATE_MODE_VBR
            else -> BITRATE_MODE_CBR
        }

        // 6. final authoritative gate
        val fmt = buildFormat(mime, size, fps, bitrate, prof, lvl, mode)
        if (!caps.isFormatSupported(fmt)) continue

        return EncoderPick(info.name, mime, size, fps, bitrate, prof, lvl, mode, evidence)
    }
    return null
}
```

Rules baked into that sketch:
- Iterate in list order; never re-sort.
- `alignDownWithin` never *enlarges* past the requested size (enlarging is what produces the
  1920×1088 letterbox and the crop side-effects of §2.7).
- `PerformancePoint.covers(MediaFormat)` is the gate when performance points exist; `null`/empty is
  *not* a pass, it drops through to achievable rates and then to `UNKNOWN`.
- An `UNKNOWN` performance verdict is allowed for H.264 (it is the only path on Unisoc, §3.3) but
  **must never be allowed for HEVC** — see §8.

---

## 3. Hardware reality on the two target SoCs

### 3.1 Motorola moto g04 — Unisoc T606

`[DOCUMENTED]` Motorola official specification page
(https://en-us.support.motorola.com/app/answers/detail/a_id/178144/):
- SoC: "Processeur UNISOC T606 avec UC octa-core **2×A75 1,6 GHz + 6×A55 1,6 GHz**, processeur
  graphique **ARM Mali-G57 MP1 650 MHz**"
- RAM 4 GB (up to 8 GB via software RAM boost); storage 64 GB **UFS 2.2**, microSD to 1 TB
- OS: **Android 14**
- Display: 6.56″, **HD+ 1612 × 720, 269 ppi**, IPS LCD, 90 Hz, 20:9
- Rear camera: 16 MP f/2.2, 1.0 µm, PDAF. Front: 5 MP f/2.2; front video **"FHD (30 fps) | HD (30 fps)"**

**The rear-camera video resolution/frame-rate list is not published on the Motorola specification
page `[UNVERIFIED]`.** Third-party databases report rear 1080p@30 for the moto g04
`[UNVERIFIED]` (gsmarena.com/motorola_moto_g04-13215.php class sources). **Unisoc does not publish a
public T606 datasheet** — `unisoc.com` product listings do not include the T606 (verified: the
`/en/product` listing enumerates only newer parts, no `T606` string) `[DOCUMENTED, negative result]`.
So **there is no vendor-documented encode capability for the T606.**

Two facts that matter more than the display: **720p display** (so a 1080p *preview* is pointless —
only the *recorded* stream needs 1080p), and **4 GB RAM / UFS 2.2** (so sustained write throughput
and memory pressure are the real risks, not codec choice).

### 3.2 Motorola edge 60 fusion — MediaTek Dimensity 7400

`[DOCUMENTED]` Motorola official specification page
(https://en-us.support.motorola.com/app/answers/detail/a_id/184937): "**MediaTek Dimensity 7400**
processor with 4×A78 2.5 GHz + 4×A55 2.0 GHz octa-core CPU, **Arm Mali-G615 MC2** GPU"; 8 GB LPDDR4X;
256 GB UFS 2.2; **Android 15**; 6.67″ pOLED 2712 × 1220 @ 120 Hz.
Note: some outlets list the Dimensity **7300** for this model in other markets `[UNVERIFIED]`; the
US Motorola support page says 7400. Both are the same MT6878-class part per third-party sources
`[UNVERIFIED]`, differing mainly in peak A78 clock.

`[DOCUMENTED]` MediaTek official Dimensity 7400 product page
(https://www.mediatek.com/products/smartphones/mediatek-dimensity-7400):
- **Video Encoding: "H.264", "HEVC"** — no AV1, no VP9 encode
- Video Playback/Decode: "H.264", "HEVC", "VP-9"
- **Max Video Capture Resolution: "4K30 (3840 × 2160)"**
- Max camera sensor 200 MP; CPU "4× Arm Cortex-A78 up to 2.6 GHz" + "4× Arm Cortex-A55";
  GPU "Arm Mali-G615 MC2"; memory LPDDR5 / LPDDR4x up to 6400 Mbps

**`[DOCUMENTED]` Conclusion for the mid-range target: hardware H.264 and hardware HEVC, no hardware
AV1 encode, 4K30 encode ceiling.** 1080p30 and 1080p60 are comfortably inside that envelope
`[INFERRED]`.

### 3.3 Vendor codec configuration — actual shipped `media_codecs` / `media_profiles`

The `moto g04`'s own configuration files were not obtainable in this session. The tables below are
from **shipped firmware dumps and Unisoc BSP mirrors for the same SoC platform family**
(Unisoc UMS9230 / "sharkl5Pro", which covers the T606/T610/T612/T616/T618 class per third-party
identification `[UNVERIFIED]`). Treat every row as **`[INFERRED]` for the moto g04 specifically** —
it is the shape of the answer, not the answer. §"Open questions" says exactly how to settle it.

**Unisoc UMS9230-class, Codec2 era** — realme RMX3761 (Unisoc T612) firmware dump,
`proprietary/etc/media_codecs_c2.xml`
(https://raw.githubusercontent.com/SudirEbi/vendor_realme_RMX3761/master/proprietary/etc/media_codecs_c2.xml):

| Encoder | MIME | Alias | Size | Align | Block size | Block count | Blocks/s | Bitrate |
|---|---|---|---|---|---|---|---|---|
| `c2.unisoc.avc.encoder` | `video/avc` | `OMX.sprd.h264.encoder` | 176×144 – **1920×3840** | **2×2** | 16×16 | — | **1 – 245 760** | 1 – **40 Mbps** |
| `c2.unisoc.hevc.encoder` | `video/hevc` | `OMX.sprd.h265.encoder` | 176×144 – **1920×3840** | **2×2** | **32×32** | 1 – 8160 | 1 – 244 800 | 1 – **25 Mbps** |

- **`[INFERRED]` AVC at 1920×1080** = ⌈1920/16⌉ × ⌈1080/16⌉ = 120 × 68 = **8160 blocks**;
  245 760 / 8160 = **30.1 fps**. So *1080p30 is the declared ceiling; 1080p60 is not available.*
- **`[INFERRED]` AVC at 1280×720** = 80 × 45 = 3600 blocks; 245 760 / 3600 = **68.3 fps** →
  720p60 is declared feasible.
- **`[INFERRED]` The HEVC entry is internally inconsistent and its frame-rate claim is nonsense.**
  With `block-size 32x32`, 1920×1080 is 60 × 34 = 2040 blocks, so 244 800 / 2040 = **120 fps** for
  1080p HEVC — physically implausible on a 12 nm budget part, and the `block-count 1-8160` limit is
  a 16×16-derived number pasted next to a 32×32 block size. This is a concrete, real-world example of
  why `getSupportedFrameRatesFor()` must never gate a recording decision (§2.4).
- **`[DOCUMENTED]` No AV1 encoder entry exists** in this file.

**Older Unisoc OMX-era BSP for the same platform** (`sharkl5Pro/common/media_codecs.xml`,
https://raw.githubusercontent.com/coldraintea/SPRD-stuff/master/sharkl5Pro/common/media_codecs.xml)
— included because it shows the alignment trap of §2.7:

| Encoder | Size | Align | Blocks/s | Bitrate |
|---|---|---|---|---|
| `OMX.sprd.h264.encoder` | 176×144 – **1920×1088** | **16×16** | 1 – 245 760 | 1 – 40 Mbps |
| `OMX.sprd.h265.encoder` | 176×144 – **1920×1088** | **16×16** | 1 – 244 800 | 1 – 25 Mbps |

**`[INFERRED]` With `alignment 16x16`, `VideoCapabilities.isSizeSupported(1920, 1080)` returns
`false`** (1080 % 16 ≠ 0) and CameraX would crop to 1920×1072 or expand to 1920×1088.

**Unisoc UMS9230-class `media_profiles_V1_0.xml`** — the table behind
`EncoderProfiles`/`CamcorderProfile`, same dump
(`proprietary/etc/media_profiles_V1_0.xml`), camera 0:

| `CamcorderProfile` quality | Video profiles present |
|---|---|
| `QUALITY_LOW` | h264 176×144@30, 256 kbps |
| `QUALITY_480P` | h264 720×480@30 **4 Mbps**; **hevc** 720×480@30 4 Mbps |
| `QUALITY_720P` | h264 1280×720@30 **8 Mbps**; **hevc** 1280×720@30 8 Mbps |
| `QUALITY_1080P` | **h264 only**, 1920×1080@30 **16 Mbps** |
| `QUALITY_HIGH` | h264 1920×1080@30 16 Mbps |

`<VideoEncoderCap>` rows from the same file:

| name | minBitRate | maxBitRate | max frame size | frame rate |
|---|---|---|---|---|
| `h264` | 16 000 | **32 000 000** | 1920 × 1080 | **15 – 60** |
| `hevc` | 16 000 | **25 000 000** | 1920 × 1080 | **15 – 30** |

**`[INFERRED]` Three things follow.** (1) On this platform the OEM itself does **not** offer HEVC at
1080p through `EncoderProfiles` — only H.264 — so CameraX's `resolveCompatibleVideoProfile()` will
select AVC for FHD regardless of what you ask for on 1.6.x. (2) The OEM's own 1080p30 bitrate opinion
is **16 Mbps**, i.e. 1.6× the CDD's 10 Mbps guarantee. (3) `media_profiles` and `media_codecs`
disagree (1920×1080 vs 1920×3840 max) — confirming §2.5's rule to use them for different jobs.

**`[DOCUMENTED]` Encoder performance data — the critical asymmetry.** Same dump,
`proprietary/etc/media_codecs_performance_c2.xml` `<Encoders>` block contains
`measured-frame-rate-*` limits **only** for `c2.android.*` (software) codecs:

| Software encoder | Measured frame rates published |
|---|---|
| `c2.android.avc.encoder` | 320×240: 264–274 · 720×480: 98–109 · 1280×720: 62–73 · 1920×1080: **34–45** |
| `c2.android.hevc.encoder` | **320×240 only: 24–34** |
| `c2.android.vp8.encoder` | 320×180: 161–172 · 640×360: 84–95 · 1280×720: 31–41 · 1920×1080: 16–27 |

and `proprietary/etc/media_codecs_performance.xml` is an **empty `<MediaCodecs>` element**.
**No `measured-frame-rate-*` and no `performance-point-*` data is published for
`c2.unisoc.avc.encoder` or `c2.unisoc.hevc.encoder`.**

**`[INFERRED]` On Unisoc-class hardware, `getSupportedPerformancePoints()` and
`getAchievableFrameRatesFor()` will return `null` for the hardware encoders.** Our codec chooser must
therefore have a defined behaviour for `perfEvidence == UNKNOWN`, and that behaviour must differ
between H.264 (accept, with derating) and HEVC (reject) — see §8. Note also the software HEVC
measurement: **320×240 at 24–34 fps**, corroborating §1.2's conclusion that software HEVC is not a
fallback at any useful size.

**MediaTek Codec2 configuration, for contrast** — Motorola `cancunf` device tree
(`configs/media/media_codecs_c2.xml`,
https://raw.githubusercontent.com/sarthakroy2002/device_motorola_cancunf/master/configs/media/media_codecs_c2.xml):

| Encoder | Size | Align | Blocks/s | Bitrate | Published performance points |
|---|---|---|---|---|---|
| `c2.mtk.avc.encoder` (alias `OMX.MTK.VIDEO.ENCODER.AVC`) | 160×160 – 2560×1440 | **2×2** | 100 – 489 600 | 1 – 60 Mbps | **2560×1440 @ 30, 1920×1080 @ 60, 1280×720 @ 120**; `can-swap-width-height` |
| `c2.mtk.hevc.encoder` (alias `OMX.MTK.VIDEO.ENCODER.HEVC`) | 160×128 – 2560×1440 | **2×2** | 80 – 432 000 | 1 – 60 Mbps | **2560×1440 @ 30, 1920×1080 @ 60, 1280×720 @ 120**; `bitrate-modes VBR,CBR,CQ` |

A newer MediaTek tree (Nothing `Galaga`,
https://raw.githubusercontent.com/nothing-galaga/android_device_nothing_Galaga/master/configs/media/media_codecs_c2.xml)
declares `c2.mtk.avc.encoder` and `c2.mtk.hevc.encoder` at up to 3840×2176 with
`performance-point-3840x2160 = 30`. **No `c2.mtk.av1.encoder` exists in any MediaTek device tree
found** (a GitHub code search for `"c2.mtk.av1.encoder"` returns **0 results**) `[INFERRED]`.

**`[INFERRED]` Net: on MediaTek the codec chooser will get real `PerformancePoint` data and can
confidently offer HEVC; on Unisoc it will get nothing and must stay on H.264.** That asymmetry — not
a per-SoC hardcode — is what the chooser should key off.

---

## 4. AV1: reject for recording

| Question | Finding |
|---|---|
| Does any CDD version mandate an AV1 **encoder**? | **No.** CDD 16 §5.2.6 and CDD 14 §5.2.6 are both conditional: "If device implementations support AV1 codec then, they: …". The hardware clause is "**If** AV1 encoder is hardware accelerated, then it: `[C-2-1]` MUST support up to and including HD1080p". `[DOCUMENTED]` |
| Does developer.android.com say it's mandatory? | Yes — the video-formats table on https://developer.android.com/media/platform/supported-formats lists AV1 Encoder "Android 14+" with the note "Encoder and decoder are mandatory beginning with Android 14." `[DOCUMENTED]` |
| Does AOSP ship an enabled AV1 encoder? | **No.** `media_codecs_sw.xml` declares `c2.android.av1.encoder` with **`enabled="false"`** and `minsdk="34"`. `[DOCUMENTED]` |
| How to reconcile? | **`[INFERRED]`** The developer-site note is best read as "the *framework* gained an AV1 encoder component in 14", not "every shipping device exposes one". The CDD — the normative document — does not require it, and AOSP's own default is off. **Resolution: never assume; probe `MediaCodecList` for `video/av01` encoders and log the result on both target devices.** |
| Hardware AV1 encode on phones? | **`[INFERRED]`, strong.** A GitHub code search across the device-tree/firmware-dump corpus finds `c2.google.av1.encoder` only in **Google Tensor** trees (`google/zuma` = Tensor G3/Pixel 8, `google/zumapro` = Tensor G4, plus `laguna`/`frankel`/`komodo`/`comet`) and `c2.intel.av1.encoder` in Intel's Celadon x86 mixins. **Zero** hits for a Qualcomm (`c2.qti.*`), MediaTek (`c2.mtk.*`), Unisoc (`c2.unisoc.*`), or Exynos AV1 encoder. Corroborated by MediaTek's own Dimensity 7400 page listing encode as "H.264, HEVC" only `[DOCUMENTED]`. |
| Cost of software AV1 encode? | **`[INFERRED]`** Not viable for realtime capture on either target. The AOSP software AV1 entry declares **no `blocks-per-second` limit at all**, so it publishes no performance envelope; on a `slow-cpu` device it is capped at 720×480 / 5 Mbps. Independent encoder benchmarking puts libaom-AV1 well below realtime for 1080p30 even on desktop CPUs and SVT-AV1 at fast presets at only ~10–30× realtime on **modern multi-core desktop** parts `[UNVERIFIED]` (streaminglearningcenter.com preset analyses) — a 2×A75+6×A55 12 nm phone is orders of magnitude away. Even Media Performance Class V only asks for **480p30 @ 1 Mbps** AV1 encode `[DOCUMENTED]`. |
| Verdict | **Reject AV1 for recording.** Do not expose it in the UI. Do not attempt it as a fallback. Optionally *decode* AV1 in the in-app player (AV1 decode is broadly available and Android 14+ requires it) — but never encode. |

**APV, briefly.** `MediaFormat.MIMETYPE_VIDEO_APV` (`"video/apv"`) is new in **API 36**
`[DOCUMENTED]`, and the supported-formats table calls encoder+decoder "mandatory beginning with
Android 16" `[DOCUMENTED]`. AOSP's `c2.android.apv.encoder` is also `enabled="false"`
(`minsdk="36"`, `variant="!slow-cpu"`, bitrate up to **240 Mbps**) `[DOCUMENTED]`. APV is an
intra-only *production/mezzanine* codec — its bitrates are 10–20× a dashcam budget. **Irrelevant to
Roadguard; do not implement.**

---

## 5. Bitrate for evidence-quality footage

### 5.1 First: resolution, not bitrate, sets the legibility ceiling

`[DOCUMENTED]` IEC 62676-4:2014 DORI pixel-density criteria, as published by Axis
(https://whitepapers.axis.com/en-us/pixel-density-based-on-iec-62676-4-2014): **Detect 25 px/m,
Observe 63 px/m, Recognise 125 px/m, Identify 250 px/m.** (The 2025 revision replaces DORI with a
seven-level visual-performance framework; the legacy thresholds remain the standard design anchors
`[UNVERIFIED]` per Axis's newsroom commentary.)

`[DOCUMENTED]` Axis "License plate capture" white paper
(https://whitepapers.axis.com/en-us/license-plate-capture): "A European standard license plate should
cover **at least 75 pixels** for the letters to be imaged with full contrast"; "Most LPR software
require **100–150 pixels over the width of the plate**"; the underlying rule is "at least two pixels
across the smallest structure to be resolved". It also notes that for on-camera LPR "the resolution
should typically not be higher that 2 MP" and gives a shutter-time table (e.g. 5° camera angle at
50 km/h → 11.6 ms max; 30° at 130 km/h → **0.8 ms**). The white paper does **not** address
bitrate/codec effects on plate readability.

**`[INFERRED]` Legibility geometry.** With an EU plate 520 mm wide, a horizontal image width of
`W` metres at distance `d`, and horizontal field of view `HFOV`:
`W = 2·d·tan(HFOV/2)`, and `plate_px = image_width_px × 0.52 / W`.

| Recorded width | HFOV | 100 px across plate at | 120 px at | 150 px at | Identify (250 px/m) out to | Recognise (125 px/m) out to |
|---|---|---|---|---|---|---|
| 1280 (720p) | 55° | 6.4 m | 5.3 m | 4.3 m | 4.9 m | 9.8 m |
| 1280 (720p) | 65° | 5.2 m | 4.4 m | 3.5 m | 4.0 m | 8.0 m |
| 1280 (720p) | 75° | 4.3 m | 3.6 m | 2.9 m | 3.3 m | 6.7 m |
| **1920 (1080p)** | 55° | **9.6 m** | 8.0 m | 6.4 m | 7.4 m | 14.8 m |
| **1920 (1080p)** | 65° | **7.8 m** | 6.5 m | 5.2 m | 6.0 m | 12.1 m |
| **1920 (1080p)** | 75° | **6.5 m** | 5.4 m | 4.3 m | 5.0 m | 10.0 m |
| 2560 (1440p) | 65° | 10.4 m | 8.7 m | 7.0 m | 8.1 m | 16.2 m |

HFOV is an **assumption** here — the real value must be computed on-device from
`CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE` and `LENS_INFO_AVAILABLE_FOCAL_LENGTHS`
`[INFERRED]`; the table brackets the plausible range for a phone main camera.

**`[INFERRED]` Conclusion: 1080p buys roughly 1.5× the plate-legible distance of 720p — the single
biggest quality lever available, and far larger than any bitrate change.** 1080p30 is therefore the
default; 720p is a *degradation* mode, not a co-equal option. Note also the shutter-time constraint:
at highway speed a plate needs a sub-millisecond exposure, which is an exposure/AE-lock problem, not
a codec problem — but it means bitrate spent on a motion-blurred plate is wasted.

### 5.2 Documented bitrate anchors

| Source | Codec | 720p30 | 1080p30 | 1080p60 | 1440p30 | 2160p30 | Tag |
|---|---|---|---|---|---|---|---|
| **Android CDD 5.2.2** conditional H.264 HD requirement | H.264 | **4 Mbps** | **10 Mbps** | — | — | — | `[DOCUMENTED]` |
| **Android CDD 5.2.5** HEVC HD profiles (SR, if HW encoder) | HEVC | **4 Mbps** | **5 Mbps** | — | — | 20 Mbps | `[DOCUMENTED]` |
| **Android CDD 5.2.6** AV1 profiles (if HW encoder) | AV1 | 8 Mbps | 16 Mbps | — | — | 50 Mbps | `[DOCUMENTED]` |
| **YouTube** recommended upload (SDR) | H.264, closed GOP, GOP = ½ frame rate | **5 Mbps** | **8 Mbps** | **12 Mbps** | 16 Mbps | 35–45 Mbps | `[DOCUMENTED]` https://support.google.com/youtube/answer/1722171 |
| **Unisoc UMS9230-class `media_profiles`** (OEM's own tuning) | H.264 | **8 Mbps** | **16 Mbps** | — | — | — | `[DOCUMENTED]` (file), `[INFERRED]` for moto g04 |
| Same file | HEVC | **8 Mbps** | *(not offered)* | — | — | — | same |
| **CameraX no-profile fallback** (`VIDEO_BITRATE_BASE`) | H.264 | **14 Mbps** | **31.5 Mbps** `[INFERRED]` | 63 Mbps `[INFERRED]` | — | — | `[DOCUMENTED]` source constant |
| **BlackVue DR900X** (4K flagship dashcam) | H.264 | — | — | — | — | **25 Mbps** | `[UNVERIFIED]` (blackboxmycar.com comparison) |
| **Thinkware U1000** | H.264 | — | — | — | — | **25 Mbps** | `[UNVERIFIED]` (same) |
| **VIOFO A129 Pro** 4K, Low/Med/High/Max | H.264 | — | — | — | — | **15.3 / 30.3 / 42.4 / 60.3 Mbps** | `[UNVERIFIED]` (same) |
| VIOFO A129 Pro 4K, same four steps | HEVC | — | — | — | — | **13.7 / 22.4 / 34.5 / 52.4 Mbps** | `[UNVERIFIED]` (same) |
| **Nextbase iQ 4K** | HEVC | — | — | — | — | ~40 Mbps (≈150 MB / 30 s) | `[UNVERIFIED]` (review-derived) |

**`[INFERRED]` Scaling the real-dashcam 4K anchors down to 1080p by pixel count (÷4):**
BlackVue/Thinkware 25 Mbps @ 4K ⇒ **≈6.3 Mbps @ 1080p**; VIOFO "High" 42.4 Mbps @ 4K ⇒
**≈10.6 Mbps @ 1080p**; VIOFO "Max" 60.3 Mbps ⇒ **≈15 Mbps @ 1080p**. So the commercial dashcam
industry's 1080p-equivalent band is roughly **6–15 Mbps H.264**, which brackets both the CDD's
10 Mbps and the Unisoc OEM's 16 Mbps. That convergence is the strongest justification available for
the recommendation below.

**`[INFERRED]` Note on the VIOFO H.264-vs-HEVC steps:** at the same quality *step*, HEVC is only
**10–13 % lower bitrate** than H.264 (e.g. High: 34.5 vs 42.4 Mbps), not 50 %. That is the realistic
gain from a realtime *hardware* HEVC encoder on an embedded part — see §6.1.

### 5.3 The HEVC "50 %" claim, honestly

`[DOCUMENTED]` The canonical result is Ohm, Sullivan, Schwarz, Tan & Wiegand, "Comparison of the
Coding Efficiency of Video Coding Standards—Including High Efficiency Video Coding (HEVC)", *IEEE
Transactions on Circuits and Systems for Video Technology*, **22(12):1669–1684, Dec 2012**,
DOI `10.1109/TCSVT.2012.2221191` — subjective tests found HEVC reaching equivalent quality to
H.264/AVC at about **50 % less bitrate** on average for WVGA/HD test material.

**`[INFERRED]` Do not budget for 50 %.** That figure comes from offline, multi-pass, unconstrained
reference encoders (HM vs JM) on short test sequences. A phone's realtime hardware HEVC encoder runs
single-pass, low-latency, with a 1 s I-frame interval and no B-frames, on a fixed silicon block. The
evidence available here points to a much smaller real gain: Google's own CDD tables imply 2:1
(10 → 5 Mbps at 1080p30) `[DOCUMENTED]`; the Unisoc OEM's `media_profiles` gives HEVC and H.264
**the same 8 Mbps at 720p** `[DOCUMENTED]`; and a real dashcam's own H.264/HEVC step tables show
only 10–13 % `[UNVERIFIED]`. **Plan HEVC at ~70–75 % of the H.264 bitrate for equal quality
(a 25–30 % saving), and require an on-device A/B before advertising anything better.**

### 5.4 Recommended bitrate ladder

Chosen to sit above the CDD guarantees, at or below the OEM's own opinion where known, inside the
commercial-dashcam band, and with headroom for VBR overshoot and the API 31+ quality floor.

| Mode | Size | fps | H.264 target | HEVC target (`[INFERRED]` 0.72×) | Notes |
|---|---|---|---|---|---|
| **Default (Evidence)** | 1920×1080 | 30 | **12 Mbps** | 8.5 Mbps | Above CDD's 10 Mbps floor, below Unisoc's 16 Mbps, ≈VIOFO "High" scaled. |
| High (Evidence+) | 1920×1080 | 30 | 16 Mbps | 11.5 Mbps | Matches the Unisoc OEM profile exactly. Only offer when storage ≥128 GB. |
| Smooth | 1920×1080 | 60 | 18 Mbps | 13 Mbps | **Only when a `PerformancePoint` covers FHD_60.** Not available on Unisoc-class (§3.3). |
| **Thermal / low-storage fallback** | 1280×720 | 30 | **6 Mbps** | 4.5 Mbps | Above CDD's 4 Mbps; ≈BlackVue scaled. |
| Emergency floor | 1280×720 | 24 | 4 Mbps | 3 Mbps | Last resort before stopping. Keep recording over quality. |
| Deep floor | 720×480 | 30 | 2.5 Mbps | 2 Mbps | Only if HD encode is impossible (software-only path, §1.2). |

Companion `MediaFormat` settings (all justified in §7 and §1.5):
`KEY_I_FRAME_INTERVAL = 1`, `KEY_MAX_B_FRAMES = 0`, `KEY_BITRATE_MODE = BITRATE_MODE_VBR`
(or `_CBR` in "predictable storage" mode), `KEY_PRIORITY = 0`, `KEY_OPERATING_RATE = capture fps`,
`KEY_PROFILE` = best available, plus `KEY_PREPEND_HEADER_TO_SYNC_FRAMES = 1` **only if** the encoder
accepts it (§7.3).

### 5.5 VBR vs CBR — pick per user intent, not globally

| | VBR (`BITRATE_MODE_VBR`) | CBR (`BITRATE_MODE_CBR`) |
|---|---|---|
| Quality floor (API 31+) | **Applies** at >320×240 up to 1920×1080; may raise bitrate above target `[DOCUMENTED]` | **Does not apply** `[DOCUMENTED]` |
| CDD overshoot bound | SHOULD ≤15 % over between I-frames; ≤100 % over in a 1 s window `[DOCUMENTED]` | `[C-SR-2]` STRONGLY RECOMMENDED ≤15 % over target in a 1 s window `[DOCUMENTED]` |
| Storage predictability | Poor — "hours remaining" is an estimate | Good — "hours remaining" is arithmetic |
| Plate legibility in hard scenes | Better (floor spends more bits on detail) | Worse (fixed budget, motion wins) |
| Recommendation | **Default.** Recording quality is the point. | Offer as "Predictable storage" for users doing retention arithmetic. Gate on `EncoderCapabilities.isBitrateModeSupported(BITRATE_MODE_CBR)`. |

`BITRATE_MODE_CBR_FD` (CBR with frame drops) is `since 31` — **do not use it**: dropping frames is
exactly the failure mode a dashcam must not have `[INFERRED]`.

### 5.6 Storage per hour — arithmetic shown

Formula: `bytes/hour = bitrate_bps × 3600 / 8`. SI units (1 GB = 10⁹ B) because that is how
storage is sold; GiB shown for cross-checking against `StatFs`.

| Video bitrate | MB/min | **GB/hour (SI)** | GiB/hour | MB per **3-minute segment** |
|---|---|---|---|---|
| 2 Mbps | 15.0 | **0.90** | 0.84 | 45 |
| 3 Mbps | 22.5 | **1.35** | 1.26 | 68 |
| 4 Mbps | 30.0 | **1.80** | 1.68 | 90 |
| 5 Mbps | 37.5 | **2.25** | 2.10 | 112 |
| **6 Mbps** | 45.0 | **2.70** | 2.51 | 135 |
| 8 Mbps | 60.0 | **3.60** | 3.35 | 180 |
| 10 Mbps | 75.0 | **4.50** | 4.19 | 225 |
| **12 Mbps** | 90.0 | **5.40** | 5.03 | 270 |
| 14 Mbps | 105.0 | **6.30** | 5.87 | 315 |
| **16 Mbps** | 120.0 | **7.20** | 6.71 | 360 |
| 18 Mbps | 135.0 | 8.10 | 7.55 | 405 |
| 20 Mbps | 150.0 | 9.00 | 8.38 | 450 |
| 30 Mbps | 225.0 | 13.50 | 12.57 | 675 |

Worked example for 12 Mbps: `12 000 000 × 3600 / 8 = 5.4 × 10⁹ B = 5.40 GB`; per 3-min segment
`12 000 000 × 180 / 8 = 2.70 × 10⁸ B = 270 MB`. `[INFERRED]` — arithmetic, not measurement.

**With AAC audio at 128 kbps and ~1 % MP4 container overhead** `[INFERRED]`:

| Video | Total GB/hour | 3-min segment |
|---|---|---|
| 4 Mbps | 1.88 | 94 MB |
| 6 Mbps | 2.79 | 139 MB |
| 8 Mbps | 3.69 | 185 MB |
| **12 Mbps** | **5.51** | **276 MB** |
| 16 Mbps | 7.33 | 367 MB |

**Loop-retention capacity** `[INFERRED]`, assuming ~7 % lost to filesystem + reserve:

| Card | Usable | @6 Mbps | @12 Mbps | @16 Mbps |
|---|---|---|---|---|
| 32 GB | ~29.8 GB | 11.0 h | 5.5 h | 4.1 h |
| **64 GB** (moto g04 internal) | ~59.5 GB | 22.0 h | 11.0 h | 8.3 h |
| 128 GB | ~119 GB | 44.1 h | 22.1 h | 16.5 h |
| **256 GB** (edge 60 fusion internal) | ~238 GB | 88.2 h | 44.1 h | 33.1 h |
| 512 GB | ~476 GB | 176.4 h | 88.2 h | 66.1 h |

**`[INFERRED]` These are VBR *lower bounds*** (§1.5). Budget +15 % for VBR overshoot and quality-floor
intervention when sizing the loop-eviction watermark, and never let free space fall below what
`Recorder.Builder.setRequiredFreeStorageBytes()` demands.

**`[INFERRED]` Storage does not justify HEVC on the moto g04.** At 12 Mbps H.264 the internal 64 GB
gives ~11 hours of loop; dropping to 8.5 Mbps HEVC gives ~15.5 hours. Buying ~4 hours of retention
at the cost of losing the software fallback (§1.2), the sharing compatibility (§6.2), and the
`PerformancePoint` evidence (§3.3) is a bad trade. A larger microSD (supported to 1 TB
`[DOCUMENTED]`) buys far more retention for far less risk.

---

## 6. Compatibility: does HEVC's smaller file justify the risk?

### 6.1 What you actually gain
`[INFERRED]` 25–30 % file size at equal quality (§5.3), i.e. ~1.4 GB/hour saved at the 12 Mbps
default. Real but modest.

### 6.2 What you risk

| Risk | Evidence |
|---|---|
| **The HD HEVC encoder may not exist.** | CDD 5.2.5 mandates only Main Profile Level 3 **≤512×512** `[DOCUMENTED]`. |
| **There is no software fallback at HD.** | `c2.android.hevc.encoder` max **512×512**, `variant="!slow-cpu"`, measured 320×240 @ 24–34 fps on a real Unisoc device `[DOCUMENTED]`. |
| **The OEM may not even offer HEVC at 1080p through `EncoderProfiles`.** | Unisoc UMS9230-class `media_profiles_V1_0.xml`: `QUALITY_1080P` lists **h264 only** `[DOCUMENTED]`. |
| **No performance evidence on low-end silicon.** | Unisoc publishes no `measured-frame-rate-*` / `performance-point-*` for its hardware encoders `[DOCUMENTED]`; its declared HEVC frame-rate maths yields an implausible 120 fps at 1080p `[INFERRED]`. |
| **Android's own HEVC→AVC safety net does not cover our segments.** | Compatible media transcoding (Android 12+) "only supports video files **up to one minute** in length" `[DOCUMENTED]` https://source.android.com/docs/core/media/media-transcoding. **Roadguard segments are 3 minutes** ⇒ **never transcoded** `[INFERRED]`. And where it does apply it costs ~20 s per minute of video on a Pixel 3 `[DOCUMENTED]` https://developer.android.com/media/platform/transcoding. |
| **Desktop playback friction.** | Windows 10/11 has no in-box HEVC decode; Microsoft's HEVC Video Extension is a Store item (and was delisted in 2023) `[UNVERIFIED]`. macOS High Sierra+ decodes HEVC natively `[UNVERIFIED]`. VLC/ffmpeg handle it everywhere `[UNVERIFIED]`. For an *evidence* file that may be handed to an insurer or a police officer on an unknown machine, "install a codec first" is a real failure. |
| **Third-party Android app friction.** | WhatsApp and similar do not reliably accept HEVC-in-MP4 `[UNVERIFIED]` (vendor community threads). H.264 + AAC in MP4 is the universally accepted combination `[UNVERIFIED]`. |
| **Container is *not* a differentiator.** | `MediaMuxer` writes HEVC into MP4 from API 24 and AV1 from API 31 `[DOCUMENTED]`; both are fine at `minSdk 34`. HEVC into `.mkv` is **decode-only** per CDD 5.1.8 `[DOCUMENTED]`, so MP4 it is either way. |

### 6.3 Verdict

**`[INFERRED]` H.264 by default; HEVC as an opt-in with an explicit warning.** Concretely:
- Default the app to `MIMETYPE_VIDEO_AVC`.
- Offer HEVC only when §8's HEVC gate passes (hardware + performance evidence + `EncoderProfiles`
  agreement).
- When HEVC is enabled, show one plain-language line: *"Smaller files. Some computers and apps
  cannot play H.265 video without extra software."*
- **Always export/share via H.264.** If a user shares an HEVC segment, transcode it in-app to
  H.264 + AAC first (Media3 `Transformer`), because the platform's automatic transcoder will not
  touch a 3-minute file.
- Declare our own capabilities honestly so the platform doesn't transcode *for* us where it would
  matter: `AndroidManifest.xml` `<property android:name="android.media.PROPERTY_MEDIA_CAPABILITIES"
  android:resource="@xml/media_capabilities" />` with
  `<media-capabilities><format android:name="HEVC" supported="true"/>
  <format android:name="HDR10" supported="false"/>
  <format android:name="HDR10Plus" supported="false"/></media-capabilities>` `[DOCUMENTED]`
  (element/attribute names per developer.android.com/media/platform/transcoding). The programmatic
  equivalent is `ApplicationMediaCapabilities.Builder().addSupportedVideoMimeType(...)`
  (`ApplicationMediaCapabilities` `since 31` `[DOCUMENTED]`).

---

## 7. GOP, B-frames, and truncation resilience

### 7.1 The real truncation risk is the container, not the GOP

A plain `MediaMuxer` MP4 is unplayable until `stop()` writes the `moov` box. A kill, a crash, or an
abrupt power loss mid-segment therefore historically destroyed **the whole segment**, regardless of
I-frame interval. Two documented mitigations now exist:

- **`[DOCUMENTED]` CameraX 1.6.0 migrated to the AndroidX Media3 muxer by default.** Release notes
  (https://developer.android.com/jetpack/androidx/releases/camera): 1.6.0 "Media3 Muxer Integration —
  CameraX now integrates the Media3 Muxer by default within the VideoCapture API… **Crash
  Resilience: Improved protection against video file corruption, ensuring that data is preserved
  even if the application crashes during recording**"; and in 1.6.0-beta02, "Migrated to AndroidX
  Media3 muxer. This fixed 1: **video corruption during unexpected interruptions or app
  termination** and 2: crashes when saving videos to proxy file descriptors. (I23b63, b/433649708,
  b/264812009, b/475750115)". AndroidX `Recorder.java`'s `DEFAULT_MUXER_FACTORY` returns
  `Media3MuxerImpl` for `MUXER_FORMAT_MPEG_4`/`MUXER_FORMAT_3GPP` and `MediaMuxerImpl` only for
  WebM `[DOCUMENTED]`.
- **`[DOCUMENTED]` The mechanism**: Media3's `Mp4Writer`
  (`libraries/muxer/src/main/java/androidx/media3/muxer/Mp4Writer.java`) declares
  `MOOV_BOX_UPDATE_INTERVAL_US = 1_000_000L` — "Used for updating the moov box **periodically**" —
  and `DEFAULT_MOOV_BOX_SIZE_BYTES = 400_000` of space reserved after `ftyp` when
  `Mp4Muxer.Builder.setAttemptStreamableOutputEnabled(true)` (the default) can fit it.
  `Mp4Muxer`'s own javadoc: "if an error occurs and the muxer is not closed, then the output MP4
  file **may still have some partial data**."
- **`[INFERRED]`** Net effect: with CameraX ≥ 1.6.0 an interrupted segment loses on the order of
  **one second**, and the partial file remains playable. This is a first-order reliability win and
  is a hard reason to require **CameraX ≥ 1.6.0** (currently 1.6.1 stable).

`[DOCUMENTED]` **If a stronger guarantee is needed**, `androidx.media3.muxer.FragmentedMp4Muxer`
(`@UnstableApi`, Media3 **1.11.0** stable as of 2026-08-05) writes true fragmented MP4 with
`DEFAULT_FRAGMENT_DURATION_MS = 2_000` and `Builder.setFragmentDurationMs(long)`; it muxes AV1,
H.264, H.265, VP9, APV, MPEG-4, H.263 and Dolby Vision video plus AAC/AMR/Opus/Vorbis/raw audio.
**Caveat `[DOCUMENTED]`: `Recorder.Builder.setMuxerFactory()` is NOT public CameraX API** (absent
from `camera/camera-video/api/current.txt`), so plugging `FragmentedMp4Muxer` into CameraX is not
possible today — it would require a hand-rolled MediaCodec + Media3-muxer pipeline. **Recommendation:
rely on CameraX ≥ 1.6.0's periodic-`moov` behaviour; keep fMP4 as a documented escalation if
on-device kill testing shows unacceptable loss.**

### 7.2 I-frame interval

- `[DOCUMENTED]` `MediaFormat.KEY_I_FRAME_INTERVAL` is "the frequency of key frames expressed in
  **seconds** between key frames… A negative value means no key frames are requested after the first
  frame. A zero value means a stream containing all key frames". Also: "Most video encoders will
  convert this value [to] the number of non-key-frames between key-frames, using the frame rate
  information; therefore, **if the actual frame rate differs (e.g. input frames are dropped or the
  frame rate changes), the time interval between key frames will not be the configured value.**"
- `[DOCUMENTED]` CameraX's default is **1 second** (`VIDEO_INTRA_FRAME_INTERVAL_DEFAULT = 1`).
- `[DOCUMENTED]` YouTube's own guidance for H.264 is "Closed GOP. GOP of **half the frame rate**"
  (0.5 s at 30 fps).
- **`[INFERRED]` Use 1 second and do not lengthen it.** Rationale: a truncated or partially recovered
  file can only be decoded from a keyframe, and every seek in the review UI lands on a keyframe. At
  1 s / 30 fps a 3-minute segment has 180 keyframes and 180 independently decodable recovery points.
  The bitrate cost of frequent IDRs is real but modest at these rates, and the encoder's rate
  control absorbs it. Do **not** use the doc's "0" (all-keyframe) option — that is roughly
  Motion-JPEG-class bitrate.
- **`[INFERRED]` Because of the frame-rate caveat above**, always set `KEY_FRAME_RATE` to the actual
  encode rate and `KEY_OPERATING_RATE` to the capture rate, so a dropped-frame episode does not
  silently stretch the keyframe interval to many seconds.

### 7.3 B-frames

- `[DOCUMENTED]` `KEY_MAX_B_FRAMES` (`since 29`): "maximum number of B frames between I or P
  frames… The default value is **0**, which means that no B frames are allowed. Note that non-zero
  value does not guarantee B frames."
- `[DOCUMENTED]` `MediaMuxer` "supports muxing B-frames in MP4 since Android Nougat MR1."
- **`[INFERRED]` Keep B-frames at 0 (the default).** B-frames buy a few percent of coding efficiency
  and cost: reorder delay (bad for a live preview and for `pause()`/`resume()`), a harder-to-recover
  truncated tail (trailing B-frames reference a future frame that was never written), and additional
  encoder complexity on a low-end part. CameraX never sets the key, so 0 is what you already get.

### 7.4 Header repetition and intra refresh

- `[DOCUMENTED]` `KEY_PREPEND_HEADER_TO_SYNC_FRAMES` (`since 29`): "whether encoders prepend headers
  to sync frames (e.g. SPS and PPS to IDR frames for H.264)… **A video encoder may not support this
  feature; the component will fail to configure in that case.** … 1 indicating to prepend headers to
  every sync frames, or 0 otherwise. The default value is 0."
- **`[INFERRED]` Try it, but behind a configure-retry.** In-band SPS/PPS at every IDR makes a
  recovered byte range decodable without the `moov`-level codec-specific data — genuinely useful for
  forensic recovery of a damaged file. Because a non-supporting encoder *fails to configure*, the
  implementation must be: configure with the key → on `IllegalArgumentException`/`CodecException`,
  drop the key and configure again. Never let this optional key be the reason recording fails.
- `[DOCUMENTED]` `KEY_INTRA_REFRESH_PERIOD` (`since 24`, gated on
  `FEATURE_IntraRefresh`, `since 24`): "the whole frame is completely refreshed after the specified
  period… leads to more constant bitrate than inserting a key frame… recommended for video streaming
  applications as it provides low-delay and good error-resilience". CDD 5.1.7 `[C-3-1]`: if
  advertised, MUST support 10–60 frame periods within 20 % accuracy.
- **`[INFERRED]` Do not use intra refresh instead of IDRs.** It smooths bitrate but destroys clean
  seek points and clean recovery points, which is the opposite of what a dashcam needs. The AOSP
  software AVC encoder advertises `intra-refresh` `[DOCUMENTED]`; ignore it.

---

## 8. The "Auto" codec chooser — concrete runtime rules

Evaluate in order. Every predicate maps to an API named in §2.

```
INPUT:  cameraId, targetSize (1920x1080), targetFps (30), userMode
OUTPUT: EncoderPick, plus a UI-visible reason string

R0. userMode == "H.264 (compatible)"  -> force mime = video/avc, skip to R2
    userMode == "H.265 (smaller)"     -> attempt HEVC via R1..R4; on any failure fall back to
                                         video/avc and TELL the user why
    userMode == "Auto"                -> R1

R1. HEVC ELIGIBILITY GATE (all five must hold; any failure => H.264)
    a) A MediaCodecList(REGULAR_CODECS) entry exists with isEncoder && supports "video/hevc"
       && isHardwareAccelerated && !isSoftwareOnly && !isAlias && !name.endsWith(".secure").
    b) Its VideoCapabilities.isSizeSupported(targetSize) is true AND
       (targetSize.height % getHeightAlignment() == 0) AND
       (targetSize.width  % getWidthAlignment()  == 0).          // no crop, no expand (§2.7)
    c) REAL performance evidence for targetSize@targetFps:
         getSupportedPerformancePoints() is non-null, non-empty, and some point .covers(format)
         OR getAchievableFrameRatesFor(w,h) is non-null and its lower bound >= targetFps.
       *** A null/empty performance list is a HARD FAIL for HEVC. ***      // Unisoc, §3.3
    d) CamcorderProfile.getAll(cameraId, QUALITY_1080P).getVideoProfiles() contains a profile whose
       getMediaType() == "video/hevc" with getHdrFormat()==HDR_NONE, getBitDepth()==8,
       getChromaSubsampling()==YUV_420.                          // the OEM itself ships HEVC here
    e) caps.profileLevels contains HEVCProfileMain at a level >= the one required by
       targetSize@targetFps (1080p30 => HEVCMainTierLevel4).
    -> mime = video/hevc

R2. H.264 SELECTION (the guaranteed path)
    a) Same hardware gate as R1(a) for "video/avc".
       If NO hardware AVC encoder exists at all -> R5 (degrade resolution) before considering
       a software encoder.
    b) Same alignment gate as R1(b).
    c) Performance evidence: prefer PerformancePoints, then achievable rates. UNKNOWN is
       ACCEPTED for H.264 (it is the only path on Unisoc-class silicon) but is recorded in
       EncoderPick.perfEvidence and triggers the conservative bitrate in R3 and stricter
       thermal derating.
    d) Profile: pick the best available from caps.profileLevels, in order
         AVCProfileHigh > AVCProfileMain > AVCProfileConstrainedBaseline > AVCProfileBaseline.
       Pick the lowest level that satisfies MBPS/FS/BR for the target (§1.4): 1080p30 => AVCLevel4
       (or AVCLevel41 if bitrate > 20 Mbps on a non-High profile).

R3. BITRATE
    prior = EncoderProfiles.VideoProfile.getBitrate() for the chosen (mime, size, fps), if present
    policy = §5.4 ladder value for (mime, size, fps)
    target = prior != null ? min(prior, policy * 1.4) : policy
    if perfEvidence == UNKNOWN: target = min(target, policy)          // no optimism
    bitrate = clamp(target, VideoCapabilities.getBitrateRange())

R4. FINAL GATE
    Build the exact MediaFormat and require caps.isFormatSupported(format).
    If false, drop optional keys in this order and retry:
      KEY_PREPEND_HEADER_TO_SYNC_FRAMES -> KEY_LEVEL -> KEY_PROFILE -> KEY_BITRATE_MODE.
    If still false, next codec in list order. If no codec remains for this mime, and mime was
    HEVC, restart at R2 with video/avc. If AVC also fails, R5.

R5. RESOLUTION DEGRADATION (never give up on recording)
    1920x1080@30 -> 1280x720@30 -> 1280x720@24 -> 720x480@30 -> stop with a loud, persistent error.
    Re-run R2..R4 at each step. Only at 720x480 may a software encoder
    (isSoftwareOnly == true) be accepted, and then only for video/avc — never for video/hevc,
    never for video/av01.

R6. AV1 IS NEVER SELECTED. Probe for it and log the result for telemetry-free local diagnostics,
    but it is not a candidate in any branch.
```

**`[INFERRED]` Why R1(c) is a hard fail for HEVC but not for H.264.** H.264 is the CDD-guaranteed
path with a working (if small) software fallback and universal playback; accepting an unproven
hardware H.264 encoder risks frame drops we can detect and degrade from. HEVC has no HD fallback at
all (§1.2), so an unproven hardware HEVC encoder that turns out to be slow leaves nowhere to go
mid-drive. Asymmetric risk justifies asymmetric gates.

**`[INFERRED]` On CameraX 1.6.x, translate the chooser's result as follows.** Since
`setVideoMimeType()` does not exist before 1.7.0-alpha02, HEVC selection on stable CameraX is not
expressible — so either (a) ship H.264-only on 1.6.x and expose the HEVC toggle only when the app is
built against 1.7.x, or (b) run the HEVC path through a hand-rolled MediaCodec pipeline. Option (a)
is strongly preferred for a reliability-first product. In both cases still run the full chooser: use
it to pick `QualitySelector`, `setTargetVideoEncodingBitRate()`, and
`setVideoCapabilitiesSource(VIDEO_CAPABILITIES_SOURCE_CODEC_CAPABILITIES)` when the
`CamcorderProfile` table is missing a size we can prove the codec supports.

---

## 9. Reliability: encoder failure modes and recovery

### 9.1 The exceptions and what they mean

`[DOCUMENTED]` `MediaCodec.CodecException` (`since 21`) javadoc:

| Member | `since` | Semantics |
|---|---|---|
| `isTransient()` | 21 | "the codec exception is a transient issue, perhaps due to resource constraints, and that the method (or encoding/decoding) **may be retried at a later time**." |
| `isRecoverable()` | 21 | "the codec **cannot proceed further, but can be recovered by stopping, configuring, and starting again**." |
| `getErrorCode()` | **23** | — |
| `getDiagnosticInfo()` | 21 | Vendor string; log it locally. |
| `ERROR_INSUFFICIENT_RESOURCE` | 23 | "required resource was not able to be allocated." |
| `ERROR_RECLAIMED` | 23 | "the resource manager reclaimed the media resource used by the codec. **With this exception, the codec must be released, as it has moved to terminal state.**" |

**`[INFERRED]` Recovery ladder, in this exact order:**

1. `isTransient()` → keep the codec, back off ~50–250 ms, retry the operation. Do **not** tear down.
2. `isRecoverable()` → `stop()` → `configure()` → `start()` on the *same* `MediaCodec`, keep the
   same `MediaMuxer`/segment if the muxer is still healthy; otherwise roll to a new segment.
3. `getErrorCode() == ERROR_INSUFFICIENT_RESOURCE` → another app or use case is holding encoder
   sessions. Release non-essential use cases (unbind `ImageAnalysis`, drop preview resolution),
   then retry once; if it fails again, drop one rung on the R5 resolution ladder.
4. `getErrorCode() == ERROR_RECLAIMED` → `release()` the codec, **finalize and close the current
   segment immediately** (the Media3 muxer's last periodic `moov` keeps it playable, §7.1), then
   start a brand-new segment with a fresh codec. Set `KEY_PRIORITY = 0` (realtime) so the resource
   manager treats us as a capture client — CameraX already does this when capture rate ≠ encode
   rate, and we should set it unconditionally in a hand-rolled path `[INFERRED]`.
5. Neither transient nor recoverable → `release()`, drop one rung on the R5 ladder, and start a new
   segment. Count consecutive failures; after N (suggest 3) at the same rung, degrade a rung
   unconditionally.
6. Never let any of this end in "recording stopped, no user-visible reason". A dashcam that silently
   stops is worse than one that records at 480p.

### 9.2 CameraX-level errors

`[DOCUMENTED]` `androidx.camera.video.VideoRecordEvent.Finalize` error codes
(`camera/camera-video/api/current.txt`): `ERROR_NONE = 0`, `ERROR_UNKNOWN = 1`,
`ERROR_FILE_SIZE_LIMIT_REACHED = 2`, `ERROR_INSUFFICIENT_STORAGE = 3`, `ERROR_SOURCE_INACTIVE = 4`,
`ERROR_INVALID_OUTPUT_OPTIONS = 5`, **`ERROR_ENCODING_FAILED = 6`**, `ERROR_RECORDER_ERROR = 7`,
`ERROR_NO_VALID_DATA = 8`, `ERROR_DURATION_LIMIT_REACHED = 9`,
`ERROR_RECORDING_GARBAGE_COLLECTED = 10`.

`[DOCUMENTED]` `Recorder.java` finalizes with `mMuxer == null ? ERROR_NO_VALID_DATA :
ERROR_ENCODING_FAILED` when the encoding futures fail — i.e. `ERROR_ENCODING_FAILED` means "the muxer
existed, so there is probably a partial file worth keeping"; `ERROR_NO_VALID_DATA` means "nothing was
written". `[INFERRED]` Treat them differently: keep and index the partial file on
`ERROR_ENCODING_FAILED`; delete the zero-byte artefact on `ERROR_NO_VALID_DATA`.

**`[INFERRED]` Handling `ERROR_ENCODING_FAILED`:** CameraX gives no `CodecException` detail through
the public event, so the app cannot distinguish transient from terminal. The correct response is
therefore fixed and conservative:
1. Keep the finalized (possibly partial) file — it is evidence.
2. Increment a per-configuration failure counter persisted locally.
3. Immediately start a **new** recording at the *same* configuration once. Most failures are
   one-off resource contention.
4. On a second consecutive failure, rebuild the `Recorder`/`VideoCapture` with the next rung down
   the R5 ladder and record the downgrade in a user-visible status line.
5. On a third, disable HEVC for this device permanently (store the decision locally, never
   uploaded) and pin H.264.

Other CameraX handles worth wiring: `ERROR_INSUFFICIENT_STORAGE` → trigger loop eviction and retry;
`ERROR_SOURCE_INACTIVE` → camera was taken (a call, another app) → hold and resume;
`ERROR_RECORDING_GARBAGE_COLLECTED` → we dropped the `Recording` reference — a code bug, must be
impossible by construction (hold a strong reference in a foreground service).

### 9.3 Why the hardware H.264 path beats the hardware HEVC path

`[INFERRED]`, from the documented facts above:

| Dimension | HW H.264 | HW HEVC |
|---|---|---|
| CDD existence guarantee | one of VP8/H.264 mandated; H.264 universal in practice | only ≤512×512 mandated |
| HD software fallback | yes, up to 2048×2048 (SD-only on `slow-cpu`) | **none** — 512×512 cap |
| `EncoderProfiles` presence at 1080p on the baseline SoC family | yes, 16 Mbps | **absent** |
| Published performance data on the baseline SoC family | none (same as HEVC) | none, plus an implausible derived 120 fps |
| Encoder complexity / silicon maturity | oldest, most-exercised block | newer, less-exercised on budget parts |
| Playback/sharing on arbitrary devices | universal | needs a codec on Windows; rejected by some apps |
| Platform HEVC→AVC rescue for our 3-min segments | n/a | **does not apply** (1-minute limit) |
| Bitrate cost | +25–35 % file size | baseline |

The only column HEVC wins is file size, and §5.6 shows that column is worth ~4 hours of retention on
a 64 GB device — recoverable with a microSD card instead of an architectural risk.

---

## 10. Codec choice and thermal behaviour

Thermal management is covered in depth in its own research document; only the codec-specific
interactions belong here.

**What is documented:**
- `[DOCUMENTED]` `PowerManager.addThermalStatusListener(Executor, OnThermalStatusChangedListener)`
  and `getCurrentThermalStatus()` are `since 29`; `THERMAL_STATUS_NONE / LIGHT / MODERATE / SEVERE /
  CRITICAL / EMERGENCY / SHUTDOWN` are `since 29`; `getThermalHeadroom(int forecastSeconds)` is
  `since 30`; `getThermalHeadroomThresholds()` is `since 35`;
  `addThermalHeadroomListener(...)` / `removeThermalHeadroomListener(...)` are **`since 36`**
  (`api-versions.xml`, android-36).
- `[DOCUMENTED]` CDD 5.2 `[C-2-1]`: any device exposing H.264/VP8/VP9/HEVC encoders **MUST support
  dynamically configurable bitrates**. The runtime lever is
  `MediaCodec.setParameters(Bundle)` with `MediaCodec.PARAMETER_KEY_VIDEO_BITRATE` (`since 19`).
  Also available: `PARAMETER_KEY_REQUEST_SYNC_FRAME` (`since 19`) and `PARAMETER_KEY_SUSPEND`
  (`since 19`). CameraX uses both `PARAMETER_KEY_SUSPEND` and `PARAMETER_KEY_REQUEST_SYNC_FRAME`
  internally (`EncoderImpl.java`) `[DOCUMENTED]`.
- `[DOCUMENTED]` `KEY_MAX_FPS_TO_ENCODER` (`since 29`): "Instruct the video encoder in
  'surface-input' mode to **drop excessive frames from the source**, so that the input frame rate to
  the encoder does not exceed the specified fps." This is a thermal lever, but frame-dropping is a
  bad trade for a dashcam — prefer bitrate reduction, then a resolution rung.

**What is not established:**
- **`[UNVERIFIED]` Whether hardware HEVC encode draws more, less, or the same power as hardware H.264
  at the same resolution and frame rate on the T606 or Dimensity 7400.** There are two opposing
  effects (HEVC's more complex per-block tooling vs. its lower output bitrate reducing memory-bus
  and flash-write energy) and no vendor data. **Do not claim an HEVC thermal benefit in the UI.**
- **`[UNVERIFIED]` Whether the T606's video encoder shares a thermal/power domain with the ISP and
  GPU in a way that makes the map rendering (the other 50 % of our UI) the dominant heat source.**

**`[INFERRED]` Codec-side thermal policy** (the ladder, tied to `THERMAL_STATUS_*`):

| Thermal status | Action | Mechanism |
|---|---|---|
| `NONE` / `LIGHT` | Full configuration | — |
| `MODERATE` | Reduce bitrate to 75 % of target | `setParameters(PARAMETER_KEY_VIDEO_BITRATE)` — no encoder restart, no segment break |
| `SEVERE` | Reduce bitrate to 50 %, then drop to 720p30 at the next segment boundary | bitrate first (free), then rebuild at the segment roll |
| `CRITICAL` | 720p30 @ 4 Mbps; drop the preview to the lowest legal size; consider pausing map rendering | segment roll |
| `EMERGENCY` / `SHUTDOWN` | Finalize the current segment cleanly and stop, with a persistent user-visible notice | never leave a segment un-finalized |

**`[INFERRED]` Prefer bitrate reduction over resolution reduction, and resolution reduction over
frame-rate reduction, and never choose frame dropping.** Bitrate changes are free (no restart, no
segment break, CDD-guaranteed to work). Resolution changes require a new encoder and hence a segment
boundary — do them *at* a natural 3-minute boundary wherever possible so no footage is lost.
Frame-rate reduction directly degrades the ability to catch the one frame where the plate is sharp.

---

## Open questions / must-measure-on-device

Everything below is `[UNVERIFIED]` or `[INFERRED]` and must be settled by running a probe app on a
real **moto g04** and a real **edge 60 fusion**. The probe should dump, for every encoder returned by
`MediaCodecList(REGULAR_CODECS)`: `getName()`, `getCanonicalName()`, `isAlias()`, `isVendor()`,
`isHardwareAccelerated()`, `isSoftwareOnly()`, `getSupportedTypes()`, and for each video type the
full `VideoCapabilities` (`getSupportedWidths/Heights`, both alignments, `getBitrateRange()`,
`getSupportedFrameRatesFor(1280,720)` and `(1920,1080)`, `getAchievableFrameRatesFor(...)`,
`getSupportedPerformancePoints()`, `getMaxSupportedInstances()`, `profileLevels`,
`EncoderCapabilities.isBitrateModeSupported(...)`, `getQualityRange()`), plus
`CamcorderProfile.getAll(cameraId, q).getVideoProfiles()` for every `q` with `hasProfile()`.

1. **What are the moto g04's actual encoder component names and caps?** Expected `c2.unisoc.avc.encoder`
   and `c2.unisoc.hevc.encoder`; must confirm names, max sizes, `blocks-per-second`, bitrate ranges,
   and **width/height alignment** (2×2 as in the Codec2 dump, or 16×16 as in the OMX BSP — this
   decides whether 1080p is encoded uncropped, §2.7).
2. **Does the moto g04 publish any encoder performance data?** Call
   `getSupportedPerformancePoints()` and `getAchievableFrameRatesFor(1920,1080)` on the hardware AVC
   and HEVC encoders. If both return `null`, §8's R1(c) permanently disqualifies HEVC on this device
   and R2(c) marks H.264 as `UNKNOWN`. This is the single highest-value measurement.
3. **Is the moto g04 configured as `slow-cpu`?** Not directly queryable. Infer it from the reported
   caps of `c2.android.avc.encoder`: `slow-cpu` ⇒ max 1808×1808 with block-count ≤1620 and
   blocks-per-second ≤40 500; `!slow-cpu` ⇒ 2048×2048 with ≤8192 and ≤245 760. This determines
   whether any software fallback above SD exists at all.
4. **Can the moto g04 actually sustain 1080p30 H.264 for a 60-minute drive?** Record 60 minutes at
   12 Mbps with the map UI live; log per-segment frame count vs. expected (30 × 180 = 5400),
   `RecordingStats.getNumBytesRecorded()`, skin/battery temperature, `getCurrentThermalStatus()`
   transitions, and any `ERROR_ENCODING_FAILED`. Accept only if dropped frames < 0.1 % and no
   segment is lost.
5. **Does the moto g04 offer HEVC at 1080p through `EncoderProfiles`, and can it sustain it?** If
   `QUALITY_1080P` lists only h264 (as the same-family dump does), HEVC@1080p is off the table
   regardless of `MediaCodecList`. If it does list HEVC, run the same 60-minute test at 8.5 Mbps and
   compare frame drops and temperature against H.264.
6. **What is the real H.264-vs-HEVC bitrate saving at equal plate legibility on these encoders?**
   Method: fixed 90-second driving clip replayed through both encoders at matched configurations,
   sweep bitrate, and score plate legibility by counting correctly-read characters at fixed
   distances from a known-plate test rig. Expected 25–30 % (§5.3), not 50 %. Until measured, the
   UI must not promise a specific saving.
7. **Does either device expose an AV1 encoder at all?** Probe for `video/av01` encoders and record
   `isHardwareAccelerated()`. Expected: none on either device (§4). If one appears in software,
   confirm it is unusable by measuring `getAchievableFrameRatesFor(1280,720)` — expected `null` or
   far below 30.
8. **Is `KEY_PREPEND_HEADER_TO_SYNC_FRAMES = 1` accepted by the chosen encoders?** The doc says a
   non-supporting component "will fail to configure". Test configure-with-then-without on both
   devices and record the result so the retry path is exercised in production, not discovered there.
9. **How much footage is actually lost to a hard kill / power cut with CameraX 1.6.1?** Method:
   `adb shell am kill` and a battery-pull equivalent at t = 90 s into a 3-minute segment, ×20 trials;
   measure the recovered duration and whether the file opens in ExoPlayer, ffprobe, and Windows
   Films & TV. Expected ≈1 s loss with a playable file (§7.1). If loss exceeds ~3 s or files are
   unplayable, escalate to a hand-rolled MediaCodec + `FragmentedMp4Muxer` pipeline.
10. **What is the real HFOV of each device's rear camera at the recording aspect ratio?** Compute from
    `SENSOR_INFO_PHYSICAL_SIZE`, `SENSOR_INFO_ACTIVE_ARRAY_SIZE`, `SCALER_CROP_REGION` and
    `LENS_INFO_AVAILABLE_FOCAL_LENGTHS`. This picks the correct row of §5.1's table and turns the
    plate-legibility numbers from assumptions into device-specific facts.
11. **Does the VBR quality floor materially raise our bitrate on real driving footage?** Record the
    same drive at 12 Mbps VBR and 12 Mbps CBR and compare `getNumBytesRecorded()`. Quantifies how
    much headroom the loop-eviction watermark needs (§5.6).
12. **Does the edge 60 fusion ship the Dimensity 7300 or 7400 in the units we support, and does it
    change the encoder configuration?** Read `Build.SOC_MODEL` (API 31+) and diff the probe dump
    between units. MediaTek's published encode capability is identical for both parts, so no
    behavioural difference is expected — confirm rather than assume.
13. **Are 1080p60 `PerformancePoint`s present on the edge 60 fusion?** The MediaTek device trees
    examined declare `performance-point-1920x1080 = 60`. If confirmed on the real device, the
    "Smooth" 1080p60 rung of §5.4 becomes available there while remaining unavailable on the
    moto g04 — a legitimate per-device capability difference to expose in the UI.
14. **Sustained write throughput of internal UFS 2.2 and of representative microSD cards** at
    12 Mbps (1.5 MB/s) and 18 Mbps (2.25 MB/s), while the loop-eviction deleter is running. Storage
    stalls present as encoder back-pressure and look like codec failures; rule them out before
    blaming the encoder.

---

### Source index (URLs actually retrieved for this document)

- Android 16 CDD — https://source.android.com/docs/compatibility/16/android-16-cdd (§§2.2.7.1, 5.1.7–5.1.10, 5.2, 5.2.2, 5.2.5, 5.2.6)
- Android 14 CDD — https://source.android.com/docs/compatibility/14/android-14-cdd (§5.2.5, 5.2.6)
- Supported media formats — https://developer.android.com/media/platform/supported-formats
- Compatible media transcoding (app side) — https://developer.android.com/media/platform/transcoding
- Compatible media transcoding (AOSP) — https://source.android.com/docs/core/media/media-transcoding
- `MediaCodec` minimum quality floor — https://developer.android.com/reference/android/media/MediaCodec#qualityFloor
- AOSP `media_codecs_sw.xml` — https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/media/libstagefright/data/media_codecs_sw.xml
- AOSP `media_codecs_google_c2_video.xml` — https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/media/libstagefright/data/media_codecs_google_c2_video.xml
- AOSP `MediaCodecInfo.java` (level tables, capability javadoc) — https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/refs/heads/main/media/java/android/media/MediaCodecInfo.java
- AOSP `MediaFormat.java` (key javadoc) — https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/refs/heads/main/media/java/android/media/MediaFormat.java
- AOSP `MediaMuxer.java` (container/codec table) — https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/refs/heads/main/media/java/android/media/MediaMuxer.java
- AOSP `MediaCodecList.java` (`REGULAR_CODECS` javadoc) — https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/refs/heads/main/media/java/android/media/MediaCodecList.java
- CameraX releases / release notes — https://developer.android.com/jetpack/androidx/releases/camera
- CameraX `camera-video` public API — https://raw.githubusercontent.com/androidx/androidx/refs/heads/androidx-main/camera/camera-video/api/current.txt
- CameraX `VideoConfigUtil.kt`, `VideoEncoderConfigDefaultResolver.kt`, `VideoEncoderConfigVideoProfileResolver.kt`, `VideoEncoderConfig.java`, `VideoEncoderInfoImpl.kt`, `VideoCapture.java`, `Recorder.java`, `EncoderImpl.java` — under https://github.com/androidx/androidx/tree/androidx-main/camera/camera-video/src/main/java/androidx/camera/video
- Media3 releases — https://developer.android.com/jetpack/androidx/releases/media3 (1.11.0, 2026-08-05)
- Media3 `FragmentedMp4Muxer.java`, `Mp4Muxer.java`, `Mp4Writer.java` — under https://github.com/androidx/media/tree/main/libraries/muxer/src/main/java/androidx/media3/muxer
- Motorola moto g04 official specs — https://en-us.support.motorola.com/app/answers/detail/a_id/178144/
- Motorola edge 60 fusion official specs — https://en-us.support.motorola.com/app/answers/detail/a_id/184937
- MediaTek Dimensity 7400 official specs — https://www.mediatek.com/products/smartphones/mediatek-dimensity-7400
- Unisoc product listing (T606 absent) — https://www.unisoc.com/en/product
- Unisoc UMS9230-class shipped configs — https://raw.githubusercontent.com/SudirEbi/vendor_realme_RMX3761/master/proprietary/etc/{media_codecs_c2.xml,media_codecs_performance_c2.xml,media_codecs_performance.xml,media_profiles_V1_0.xml}
- Unisoc sharkl5Pro BSP `media_codecs.xml` — https://raw.githubusercontent.com/coldraintea/SPRD-stuff/master/sharkl5Pro/common/media_codecs.xml
- MediaTek device configs — https://raw.githubusercontent.com/sarthakroy2002/device_motorola_cancunf/master/configs/media/media_codecs_c2.xml and https://raw.githubusercontent.com/nothing-galaga/android_device_nothing_Galaga/master/configs/media/media_codecs_c2.xml
- YouTube recommended upload encoding settings — https://support.google.com/youtube/answer/1722171
- Axis, "Pixel density based on IEC 62676-4:2014" — https://whitepapers.axis.com/en-us/pixel-density-based-on-iec-62676-4-2014
- Axis, "License plate capture" — https://whitepapers.axis.com/en-us/license-plate-capture
- Dash cam bitrate comparison (secondary, `[UNVERIFIED]`) — https://www.blackboxmycar.com/pages/dash-cam-bitrate-explained-how-does-it-affect-my-footage
- Local SDK API-level source of truth — `/home/user/android-sdk/platforms/android-36/data/api-versions.xml`
