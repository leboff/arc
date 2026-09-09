# Format Support Plan — Arc VR Player

**Status:** blueprint. Nothing in this document has been built or run.
**Scope:** why Arc refuses files VLC plays, what it would take to close the gap, and the
exact path recommended for a phone-in-a-shell VR player.
**Companions:** [ARCHITECTURE.md](ARCHITECTURE.md) §9.5, §10, §14, §18 ·
[UI_REDESIGN_REVIEWED_PLAN.md](UI_REDESIGN_REVIEWED_PLAN.md) §14 (M8/R3) ·
[TESTING.md](TESTING.md) §1

---

## 0. How to read this document

Sections 1–3 are diagnosis: five distinct defects hide behind the two symptoms users report,
and only one of them is the "codec" problem everyone assumes. Sections 4–6 are the options
analysis. Sections 7–12 are the buildable plan — dependencies, file-by-file code changes,
red-first tests, milestones.

Every claim about the current code carries a `file:line` citation against the tree at
`4124200`. Every claim about a third-party library that could not be verified from inside
this repository is marked **[verify]** and carries the command that verifies it.

---

## 1. Executive Summary

### 1.1 The one-paragraph version

Arc cannot play local files **at all** — not because of a codec, but because
`ExoVideoPlayer` builds its media source factory from an HTTP-only `DataSource.Factory`
(`ExoVideoPlayer.kt:153,163`), so every `content://` URI throws before a demuxer is ever
consulted; and because the play path is still typed against `DidlItem`
(`Effect.kt:21-32`), so a local `MediaNode.Video` has no route into the player in the first
place. Separately, Arc's *format* coverage is narrower than VLC's for three compounding
reasons: Media3's extractor set has no ASF/WMV or RealMedia demuxer; Pixel hardware has no
AC-3/E-AC-3/DTS/TrueHD audio decoder and no VC-1 or MPEG-2 video decoder; and the
`media3-decoder-ffmpeg` extension that ARCHITECTURE.md §10.1 assumes is present **is not on
the classpath**, which quietly makes the `EXTENSION_RENDERER_MODE_PREFER` setting and the
entire `ForceSoftwareAudio` fallback branch dead code.

### 1.2 The recommendation, up front

**Adopt Option C (hybrid), but build it in the order A → C, and treat Option A as
independently shippable.**

| Phase | What ships | Files that fail today |
|---|---|---|
| **F0** | `DefaultDataSource.Factory` fix + local play path + container-error classification | local playback: **100 %** |
| **F1** | `media3-decoder-ffmpeg`, correctly wired, with the renderer mode inversion fixed | MKV/AC3, MKV/DTS, MKV/TrueHD, FLAC, Vorbis, WMA audio |
| **F2** | LibVLC as a second `VideoPlayer` implementation behind an engine router | WMV/ASF, VC-1, RealMedia, MPEG-2 PS, Xvid-ASP, broken/truncated containers |

F0 is a bug fix and is not optional. F1 buys ~80 % of the remaining real-world gap for
~4 MB of APK and zero risk to the render loop. F2 buys the long tail for ~30 MB and a
second frame-pacing path that must be quarantined from the VR render loop with care.

Rejecting F2 outright is defensible for v1. Rejecting F1 is not — "video plays, silently"
on an MKV/AC3 file is the single most common DLNA failure and ARCHITECTURE.md §10.1 already
committed to solving it.

### 1.3 What this costs

| | Option A only | Option B only | Option C (recommended) |
|---|---|---|---|
| APK delta (arm64-v8a) | ~3–5 MB **[verify]** | ~30–40 MB **[verify]** | ~33–45 MB **[verify]** |
| New build-system burden | NDK + one-time AAR bake | none (Maven Central) | both |
| Container coverage vs VLC | ~85 % | ~100 % | ~100 % |
| 4K60 VR frame pacing | unchanged (Media3 keeps `VideoFrameReleaseHelper`) | **regressed** — VLC has no vsync-aligned release | unchanged on the primary path |
| Thermal risk | none (audio-only SW decode) | high if `--no-hw-dec` is ever reached | contained by policy |
| Licensing exposure | LGPL-2.1 relink obligation + Dolby/DTS patents | LGPL-2.1 + Dolby/DTS patents | both |

---

## 2. Diagnostic Root-Cause Analysis

Five defects. They are independent; fixing any one alone leaves users still reporting
failures.

### 2.1 D1 — `content://` and `file://` URIs cannot be opened (HTTP-only upstream)

```kotlin
// playback/src/main/java/com/daydreamvr/playback/ExoVideoPlayer.kt:153
val httpFactory = OkHttpDataSource.Factory(OkHttpClient())
...
// :163
.setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
```

`DefaultMediaSourceFactory(DataSource.Factory)` uses the supplied factory as the *only*
upstream. `OkHttpDataSource` implements `HttpDataSource` and rejects any URI whose scheme
is not `http`/`https` — it throws before `DefaultExtractorsFactory` is ever asked to sniff
the stream.

**Failure mechanism, precisely:**

1. `ExoPlayer.prepare()` → `DefaultMediaSourceFactory.createMediaSource()` builds a
   `ProgressiveMediaSource` with the OkHttp factory.
2. `ProgressiveMediaPeriod` calls `dataSource.open(DataSpec(content://media/external/video/media/42))`.
3. `OkHttpDataSource.open` constructs an OkHttp `Request` from the URI. OkHttp's
   `HttpUrl.parse` returns `null` for a `content` scheme → `IllegalArgumentException`, or
   at best an `HttpDataSourceException` on connect.
4. `ExoPlayer` wraps it as `ERROR_CODE_IO_UNSPECIFIED` (or an unexpected-runtime error).
5. `ExoVideoPlayer.classify()` (`:247-273`) has no arm for it → `PlaybackFailure.Unknown`.
6. `FallbackPolicy.decide` (`FallbackPolicy.kt:172-176`) sees `Unknown` with zero remaining
   resources → `GiveUp`. The headset shows a raw `errorCodeName` string.

**The fix** is one line, and it is already recorded as R3/M8 in
`UI_REDESIGN_REVIEWED_PLAN.md` §14. `DefaultDataSource.Factory(context, httpFactory)`
delegates by scheme to `ContentDataSource` (`content://`), `FileDataSource`
(`file://`, bare paths), `AssetDataSource`, `RawResourceDataSource`,
`ContentDataSource` for `android.resource://`, and falls through to the supplied
`HttpDataSource.Factory` for `http`/`https`. See §8.1 for the exact replacement.

> This defect alone explains **every** local-playback report. It is not a format problem
> and no amount of FFmpeg or LibVLC would fix it.

### 2.2 D2 — there is no local play path to fix

Even with D1 repaired, a local file cannot reach `ExoVideoPlayer`:

```kotlin
// app/src/main/java/com/daydreamvr/player/state/Effect.kt:21-32
data class Play(
    val item: DidlItem,          // ← UPnP-only type
    val serverUdn: String,       // ← UPnP-only identity
    ...
)
```

```kotlin
// playback/src/main/java/com/daydreamvr/playback/VideoPlayer.kt:69-75
data class PlayRequest(
    val itemKey: String,
    val title: String,
    val rankedResources: List<Resource>,   // ← com.daydreamvr.upnp.model.Resource
    ...
)
```

`EffectRunner.play` (`EffectRunner.kt:128-161`) resolves a `MediaServer` by UDN, ranks
`effect.item.resources`, and hands `List<Resource>` to the player. `MediaCursorMapper`
produces `PlaybackRef.Local(MediaRef("content://media/external/video/media/42"))`
(`MediaCursorMapper.kt:65`) — a type with no path through `Effect.Play`.

This is also a module-layering defect: `:playback` declares `api(project(":upnp"))`
(`playback/build.gradle.kts:44`) purely so `PlayRequest` can name `Resource`. The playback
engine should not know the protocol layer exists. §8.2 proposes the seam that fixes both.

### 2.3 D3 — "container not supported" is never classified

`PlaybackException` has a dedicated code for exactly the reported symptom:

| Code | Thrown by | Meaning |
|---|---|---|
| `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED` | `UnrecognizedInputFormatException` from `BundledExtractorsAdapter` | no extractor in `DefaultExtractorsFactory` could sniff the stream |
| `ERROR_CODE_PARSING_CONTAINER_MALFORMED` | `ParserException.createForMalformedContainer` | the extractor recognised it but the bytes are broken |
| `ERROR_CODE_DECODING_FORMAT_UNSUPPORTED` | renderer capability check | container parsed, no decoder for the sample MIME |

`ExoVideoPlayer.classify()` (`:247-273`) handles the third and neither of the first two.
Both land in `PlaybackFailure.Unknown("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED: None of
the available extractors (...) could read the stream")` — which is *literally* the
"container not supported" string users are quoting back to us. The message is a leaked
internal, and `FallbackPolicy` treats it identically to a transient failure.

Distinguishing these three is the precondition for any engine-routing policy: a malformed
container is worth retrying on LibVLC, an unrecognised container is worth routing to LibVLC
*immediately*, and an unsupported sample codec is worth trying the next-ranked resource
first.

### 2.4 D4 — the FFmpeg extension ARCHITECTURE.md §10.1 assumes is not on the classpath

ARCHITECTURE.md §10.1 states:

> The `media3-decoder-ffmpeg` extension is included specifically for AC3/E-AC-3/DTS audio in
> MKV containers, which is the single most common "video plays, no sound" failure on DLNA
> libraries.

`gradle/libs.versions.toml` declares three Media3 artifacts — `media3-exoplayer`,
`media3-datasource-okhttp`, `media3-common`. There is no ffmpeg decoder artifact, in the
catalog or in `playback/build.gradle.kts:41-47`.

`DefaultRenderersFactory` discovers extension renderers **by reflection**:

```java
// DefaultRenderersFactory.buildAudioRenderers, paraphrased
Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer")
     .getConstructor(Handler.class, AudioRendererEventListener.class, AudioSink.class)
```

`ClassNotFoundException` is swallowed. So:

- `setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)` (`ExoVideoPlayer.kt:148`) is a
  no-op;
- `rebuildPlayerForcingSoftwareAudio()` (`:177-185`) tears down and rebuilds the entire
  player, loses the surface binding for a frame, re-seeks — and produces a player with the
  identical renderer set;
- `FallbackAction.ForceSoftwareAudio` (`FallbackPolicy.kt:168-170`) is therefore a placebo
  that costs the user a visible playback hitch and then fails anyway.

`FallbackPolicyTest` passes because it tests the pure decision function, which is correct.
The defect is entirely in what the caller can actually do with the decision.

### 2.5 D5 — a missing audio decoder produces *silence*, not an error

This is the subtlest one and it changes what "fix the audio codec problem" means.

`DefaultTrackSelector` classifies each track's renderer support as `FORMAT_HANDLED`,
`FORMAT_EXCEEDS_CAPABILITIES`, `FORMAT_UNSUPPORTED_DRM`, `FORMAT_UNSUPPORTED_SUBTYPE`, or
`FORMAT_UNSUPPORTED_TYPE`. Tracks at `FORMAT_UNSUPPORTED_TYPE` — which is what a DTS or
TrueHD track is on a Pixel with no FFmpeg extension — are **never selected**, by any
parameter setting. `exceedRendererCapabilitiesIfNecessary` only relaxes the
`FORMAT_EXCEEDS_CAPABILITIES` tier.

Consequence: an MKV with H.264 video and a DTS audio track plays **video, perfectly, with no
audio and no `onPlayerError` at all**. `ERROR_CODE_AUDIO_TRACK_INIT_FAILED` never fires, so
`ForceSoftwareAudio` is never even *requested*, so D4's placebo never gets a chance to
disappoint anyone. The user sees a working picture and no sound, and Arc reports success.

The detection for this is not an error handler. It is a positive check in
`onTracksChanged`:

```
media has ≥1 audio track  ∧  no audio track is selected  ⇒  MissingAudioDecoder(mime)
```

`Tracks.Group.isTrackSupported(i)` and `Tracks.isTypeSelected(C.TRACK_TYPE_AUDIO)` give
this directly. `ExoVideoPlayer.onTracksChanged` (`:385-393`) currently reads the groups for
labels only and never asks the question.

### 2.6 D6 — the extension renderer mode is inverted

```kotlin
// ExoVideoPlayer.kt:144-150
.setExtensionRendererMode(
    if (forcedSoftwareAudio) EXTENSION_RENDERER_MODE_ON
    else EXTENSION_RENDERER_MODE_PREFER,
)
```

Media3 semantics:

| Mode | Ordering |
|---|---|
| `EXTENSION_RENDERER_MODE_OFF` | extension renderers not used |
| `EXTENSION_RENDERER_MODE_ON` | MediaCodec first, **extension as fallback** |
| `EXTENSION_RENDERER_MODE_PREFER` | **extension first**, MediaCodec as fallback |

The intent is plainly "hardware normally, software when hardware fails" — that is
`..._ON`. The code asks for `..._PREFER` in the normal case, which once F1 lands would route
*every* AAC and MP3 stream through the FFmpeg software decoder, burning CPU and battery for
no benefit, and would then "fall back" to `..._ON` when forcing software audio — the
strictly *less* software-biased mode. The two branches are swapped.

Because of D4 the inversion is currently invisible. It becomes a live thermal regression the
moment F1 lands, which is why it must be fixed in the same commit.

### 2.7 Container coverage vs codec coverage — the distinction that drives the whole plan

These are two different subsystems in Media3 and they fail differently.

**Containers** are handled in pure Java by `DefaultExtractorsFactory`. Nothing about the
device matters; coverage is fixed by the library version.

| Container | Media3 extractor | Notes |
|---|---|---|
| MP4 / M4V / MOV | `Mp4Extractor`, `FragmentedMp4Extractor` | full |
| MKV / WebM | `MatroskaExtractor` | container full; **sample codec coverage is the gate** |
| MPEG-TS | `TsExtractor` | full; AC-3/E-AC-3/DTS elementary streams are *parsed* but not decodable without F1 |
| MPEG-PS | `PsExtractor` | present |
| AVI | `AviExtractor` | present since ExoPlayer 2.16; index-based seeking only, limited codec table |
| FLV | `FlvExtractor` | present |
| Ogg / Opus / Vorbis | `OggExtractor` | present |
| MP3 / AAC(ADTS) / AC3(raw) / WAV / FLAC / AMR | dedicated extractors | present |
| **ASF / WMV** | **none** | `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED` |
| **RealMedia (.rm/.rmvb)** | **none** | same |
| **OGM** | **none** | same |
| **MPEG-4 with unusual/DivX-era atoms** | partial | `MalformedContainer` in practice |

**Codecs** are handled by the device's `MediaCodec` implementation. Coverage is per-SoC and
per-Android-release. For a Pixel-class Tensor device **[verify with §11.4's
`DecoderProbeTest` on the actual handset]**:

| Video codec | Pixel hardware | Notes |
|---|---|---|
| H.264 / AVC | ✅ | up to High profile, 4K |
| H.265 / HEVC | ✅ | Main/Main10, 4K |
| VP8, VP9 | ✅ | |
| AV1 | ✅ | hardware from Tensor G3 onward |
| MPEG-4 SP | ✅ (software `c2.android.mpeg4.decoder`) | |
| **Xvid / DivX (MPEG-4 ASP)** | ⚠️ | AOSP decoder is SP-only; ASP with GMC/qpel fails or corrupts |
| **MPEG-2** | ❌ | no AOSP decoder on phones |
| **VC-1 / WMV3** | ❌ | never present on phone SoCs |
| **RealVideo** | ❌ | |

| Audio codec | Pixel hardware | Why |
|---|---|---|
| AAC-LC / HE-AAC, MP3, Vorbis, Opus, FLAC, PCM | ✅ | AOSP baseline |
| **AC-3 (Dolby Digital)** | ❌ | AOSP ships no AC-3 decoder; Dolby licensing is per-device and phone OEMs do not pay it |
| **E-AC-3 (DD+) / Atmos** | ❌ | same |
| **DTS / DTS-HD / DTS:X** | ❌ | DTS licensing, same reasoning |
| **TrueHD / MLP** | ❌ | same |
| **ALAC** | ❌ | not in AOSP's codec set |
| **WMA / WMA Pro** | ❌ | |

The AC-3/DTS gap is a **licensing** gap, not a technical one. Android TV boxes and some
tablets *do* expose `OMX.dolby.ac3.decoder` because their OEM paid Dolby. Phones do not.
This is also why passthrough is not a workaround: `AudioCapabilities` reports AC-3
passthrough only over HDMI/ARC, and there is no HDMI on a phone in a Cardboard shell.

> **The strategic conclusion.** The audio gap is closed entirely by software decoding
> (Option A). The container gap for ASF/RealMedia and the video gap for VC-1/MPEG-2/ASP are
> **not** — `media3-decoder-ffmpeg` ships an *audio-only* renderer. That asymmetry is why
> the recommendation is a hybrid and not simply "add the FFmpeg extension".

### 2.8 Why VLC plays everything

VLC is not "better at codecs". It is a fundamentally different architecture:

1. **Its own demuxer set** (`modules/demux/`) — ~40 demuxers including `asf`, `real`, `mkv`
   (a full libebml/libmatroska implementation, not a subset), `avi`, `ps`, `ts`, `ogg`,
   `nuv`, `mp4`, plus `avformat` as a catch-all wrapping FFmpeg's ~300 demuxers. Nothing is
   delegated to the platform.
2. **Software decode for everything, always available** (`avcodec` module over
   libavcodec) — VC-1, MPEG-2, RealVideo, Xvid-ASP, WMA, DTS-HD, TrueHD.
3. **Hardware decode as an optimisation, not a requirement** (`mediacodec_ndk`), with
   automatic fallback to software when MediaCodec refuses.
4. **Aggressive error recovery** — VLC will play a truncated MP4 with no `moov` atom, an MKV
   with a broken SeekHead, a TS with a corrupt PAT. Media3's extractors are comparatively
   strict and throw `MalformedContainer`.

Point 4 is underrated and probably accounts for a meaningful share of "VLC plays it fine"
reports on files that are *nominally* in supported containers.

The corollary is the trade Arc has to make consciously: VLC's universality comes from never
depending on the hardware path, and Arc's 4K60 VR frame budget comes from *always*
depending on it.

---

## 3. Symptom → defect mapping

Use this table when triaging an incoming report; it is also the acceptance checklist for §11.

| User-visible symptom | Root cause | Fixed by |
|---|---|---|
| "Container not supported" opening any phone file | **D1** + **D2** | F0 |
| Raw `ERROR_CODE_...` string shown in the headset | **D3** | F0 |
| `.wmv` / `.rm` from a DLNA server fails | **D3** (message) + no demuxer | F2 |
| MKV plays, no sound | **D5** (undetected) + no AC-3 decoder | F1 |
| MKV audio "cuts out and player restarts" | **D4** — `ForceSoftwareAudio` rebuild loop | F1 |
| Battery drains fast after the FFmpeg update | **D6** | F1 (same commit) |
| Old DivX plays as a green smear | MPEG-4 ASP on an SP-only decoder | F2 |
| A file VLC plays but Arc calls "damaged" | strict extractors (§2.8 pt 4) | F2 |

---

## 4. Option A — Media3 + `media3-decoder-ffmpeg`

### 4.1 What it is

An official AndroidX Media module (`libraries/decoder_ffmpeg` in
`github.com/androidx/media`) providing `FfmpegAudioRenderer`, a `Renderer` backed by a JNI
shim over libavcodec. It plugs into `DefaultRenderersFactory` by reflection (§2.4) and
requires no application code beyond putting it on the classpath and choosing the renderer
mode.

**It is audio-only.** The repository also contains an `FfmpegVideoRenderer`, but it is
explicitly experimental, is not constructed by `DefaultRenderersFactory`, and is not a
supported path **[verify: inspect `libraries/decoder_ffmpeg/src/main/java/androidx/media3/decoder/ffmpeg/` at your pinned tag]**. Plan on audio only.

### 4.2 What it buys

Every decoder you compile in, for audio, in any container Media3 can already demux. With
the decoder list in §7.2:

AC-3, E-AC-3, DTS (core + HD extensions), TrueHD/MLP, Vorbis, Opus, FLAC, ALAC, WMA/WMA Pro,
MP3, AAC, AMR-NB/WB, and PCM variants.

That closes the entire audio column of §2.7 — the "video plays, no sound" class in full.

### 4.3 What it does **not** buy

- No new containers. ASF/WMV and RealMedia still hit `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED`.
- No new video codecs. VC-1, MPEG-2, RealVideo, MPEG-4 ASP remain unplayable.
- No leniency on malformed containers.

### 4.4 The property that makes it the right *primary* engine

Video decoding is untouched. `MediaCodecVideoRenderer` still decodes to the `Surface` that
`VideoTexture` (`vrcore/gl/VideoTexture.kt:36-55`) wraps around its
`GL_TEXTURE_EXTERNAL_OES` texture. The decoded frame never crosses into application address
space: the codec writes into a gralloc buffer, the buffer is queued to the `SurfaceTexture`,
and the GL sampler reads it in place. Zero copies, zero CPU touch of pixel data, at 4K.

Equally important and easier to overlook: Media3's `VideoFrameReleaseHelper` stays in
play. It hooks `Choreographer`, learns the display's actual vsync period, snaps each frame's
release time to the nearest vsync, and adjusts for the display's reported presentation
latency. On a 90 Hz or 120 Hz panel driving a stereo VR view, that is the difference between
smooth motion and per-frame judder that reads as nausea through the lenses. **No alternative
engine reproduces it** (see §5.4).

Software *audio* decoding is thermally irrelevant: an AC-3 5.1 stream at 640 kbps is
single-digit-percent of one little core.

### 4.5 The cost: there is no Maven artifact

This is the whole difficulty of Option A. The Media3 decoder extensions
(`ffmpeg`, `av1`, `vpx`, `opus`, `flac`, `iamf`) are **not published to Maven Central or
Google's Maven** — you build the native library yourself with the NDK.
**[verify: search `androidx.media3:media3-decoder-ffmpeg` on Maven Central before assuming;
`media3-decoder-midi` *is* published, which misleads people into expecting the others are.]**

Three ways to consume it, in descending order of recommendation:

| Approach | Mechanism | Verdict |
|---|---|---|
| **A1 — bake an AAR once, check it in** | build `:lib-decoder-ffmpeg:assembleRelease` in a clone of `androidx/media`, commit the `.aar` under `playback/libs/` with a `BUILD.md` recording the exact commit, NDK version and decoder list | **recommended.** No NDK on developer machines or CI. Reproducible from the recorded inputs. Fits Arc's existing "no exotic build steps" posture. |
| A2 — source dependency / composite build | clone `androidx/media` next to the repo, `includeBuild` it, build the native lib in `preBuild` | correct but makes every clean build depend on a working NDK + a 20-minute FFmpeg compile |
| A3 — third-party prebuilt AAR from JitPack | several community mirrors exist | **rejected.** Unaudited native code in the process that reads the user's media, with no provenance for the LGPL relink obligation |

A1's honest downside is a binary in git. Mitigate with `.gitattributes` marking it binary,
a checked-in `playback/libs/BUILD.md`, and a CI job that rebuilds it on the recorded inputs
and diffs the symbol table — not the bytes, which will not be reproducible.

### 4.6 Licensing

**FFmpeg's own licence.** LGPL v2.1-or-later when configured `--disable-gpl` (the default).
Every decoder Arc needs — `ac3`, `eac3`, `dca`, `mlp`, `truehd`, `alac`, `wmav2`, `wmapro`,
`vorbis`, `opus`, `flac` — is LGPL. **Do not** pass `--enable-gpl` or `--enable-nonfree`;
neither is needed and both would make the APK undistributable under Arc's terms.

**The LGPL §6 obligation.** Distributing an LGPL library inside a proprietary app requires
that the user be able to relink the app against a modified version. Arc satisfies this the
standard way:

1. `libffmpegJNI.so` is a **shared** object, dynamically loaded by `System.loadLibrary`.
   Never link it statically into another `.so`.
2. Ship the exact FFmpeg source tarball or a public git tag + patch set, plus the exact
   `build_ffmpeg.sh` invocation (§7.2), at a stable URL.
3. Add an in-app attribution screen (or a `NOTICE` in the APK) naming FFmpeg, the version,
   the licence, and that URL. The lobby (`SetupActivity`) is the natural home — an in-VR
   licence screen is unreadable.

**Patents are a separate question from copyright.** AC-3/E-AC-3 are Dolby technologies and
DTS is DTS/Xperi technology. FFmpeg's LGPL licence grants no patent rights. Distributing a
commercial product that decodes AC-3 or DTS may require a licence from the respective
holder regardless of the software's copyright licence. This is the same exposure VLC and
every FFmpeg-based player carries, and in practice small distributors of open/free apps are
not pursued — but it is a business decision, not an engineering one, and it must be made
by a human before F1 ships commercially.

Note the asymmetry: this exposure exists *because* we are shipping the decoder. It does not
exist in F0, and in F2 it exists identically.

### 4.7 Option A scorecard

| Dimension | Assessment |
|---|---|
| Closes the reported "no sound" class | **fully** |
| Closes the reported "container not supported" class | no (that is D1/D3, i.e. F0) |
| Closes ASF/RM/VC-1 | no |
| Risk to the VR render loop | **none** — video path byte-identical |
| Thermal risk | negligible, *once D6 is fixed* |
| Integration complexity in Arc's code | ~15 lines (`build.gradle.kts` + renderer mode) |
| Build system complexity | one-time NDK bake, then none |
| APK delta | ~3–5 MB arm64 **[verify]** |

---

## 5. Option B — LibVLC for Android

### 5.1 What it is

`org.videolan.android:libvlc-all` on Maven Central **[verify latest: 3.6.x at time of
writing; 4.0.0-eap exists and is not production-ready]**. An AAR containing
`libvlcjni.so` plus VLC's plugin set, and a Java API (`LibVLC`, `MediaPlayer`, `Media`,
`IVLCVout`) that is a thin JNI binding over libvlc's C API.

Coordinates:

```kotlin
implementation("org.videolan.android:libvlc-all:3.6.5")   // [verify]
```

It resolves from `mavenCentral()`, which is already declared in
`settings.gradle.kts:18`. No extra repository, no NDK, no build step. That is Option B's
single largest advantage over Option A and it is a real one.

### 5.2 How it works on Android

```
Media(libVLC, uri | ParcelFileDescriptor)
  → access module   (file / fd / http / smb ...)
  → demux module    (mkv, asf, avi, ps, ts, avformat, ...)
  → decoder module  (mediacodec_ndk → avcodec fallback)
  → vout module     (android_display) → android.view.Surface
  → aout module     (AudioTrack)
```

`LibVLC` is constructed once with an options list; `MediaPlayer` is per-item. Everything of
interest arrives on `MediaPlayer.setEventListener` as `MediaPlayer.Event` (`Playing`,
`Paused`, `EndReached`, `EncounteredError`, `TimeChanged`, `LengthChanged`, `Buffering`,
`ESAdded`/`ESDeleted`).

### 5.3 Rendering into Arc's GL pipeline

This is the question that decides whether Option B is viable at all, and the answer is
better than folklore suggests.

**`vmem` callbacks are the wrong path and are not even reachable.** `libvlc_video_set_callbacks`
(lock/unlock/display into an application buffer) forces software decode, forces a full-frame
memcpy of decoded YUV per frame, and then needs a `glTexImage2D` upload plus a YUV→RGB
shader. At 4K that is ~12 MB of copy per frame, twice, at 60 fps. It is also **not exposed
in the Android Java API** — it is a C-only entry point. Dismiss it.

**The Surface path is the right one and it fits Arc's existing bridge exactly.** `IVLCVout`
offers:

```java
void setVideoSurface(Surface videoSurface, SurfaceHolder surfaceHolder);
void setVideoSurface(SurfaceTexture videoSurfaceTexture);
void setWindowSize(int width, int height);
void attachViews(OnNewVideoLayoutListener listener);
void detachViews();
```

`VideoTexture` already produces both a `SurfaceTexture` and a `Surface` over a
`GL_TEXTURE_EXTERNAL_OES` texture (`VideoTexture.kt:51-54`). So:

```kotlin
val vout = mediaPlayer.vlcVout
vout.setVideoSurface(surface, null)      // the same Surface ExoPlayer was given
vout.setWindowSize(width, height)        // MANDATORY — no video without it
vout.attachViews(onNewVideoLayout)
```

With `--codec=mediacodec_ndk` VLC's MediaCodec decoder renders **directly** into that
Surface — the same zero-copy `gralloc → SurfaceTexture → sampler` path Media3 uses. With
software decode, VLC's `android_display` vout does the YUV→RGB conversion and blit into the
Surface natively; still no Java-side copy, but now with a full CPU decode behind it.

Crucially, `VideoPlayer.attach(surface: Surface)` (`VideoPlayer.kt:18`) **does not have to
change**. The existing interface is already engine-agnostic at the surface boundary. That is
a genuinely fortunate piece of prior design.

Two traps:

- **`setWindowSize` is not optional.** Omit it and you get audio with a black screen, with
  no error. This is the single most common LibVLC-on-a-raw-Surface bug.
- **`detachViews()` must be called before releasing the `SurfaceTexture`.** Arc's GL thread
  owns `VideoTexture.release()` (`VideoTexture.kt:78-87`); LibVLC's vout runs on its own
  native thread. Ordering these across a surface-lost event is the highest-risk part of
  F2. §8.6 specifies the handshake.

### 5.4 Performance, battery, thermal, and frame pacing

**Frame pacing is where Option B genuinely loses, and it is not a small loss for VR.**

| Concern | Media3 | LibVLC |
|---|---|---|
| Vsync-aligned frame release | `VideoFrameReleaseHelper`: `Choreographer` callback, learned vsync period, per-device presentation-latency offset, frame-time smoothing over a sliding window | none. VLC's clock releases frames on its own timeline; the frame lands whenever it lands relative to the compositor |
| Display refresh-rate matching | `setVideoChangeFrameRateStrategy` requests a matching `Display.Mode` | not integrated with `Display.Mode` |
| Dropped/late frame accounting | `DecoderCounters` → `FrameStats` (Arc already consumes this: `vrcore/render/FrameStats.kt`) | no equivalent surface |

In flat-screen playback, VLC's approach is imperceptible. In a stereo VR view where the head
is moving and the panel is fixed in world space, a frame that misses vsync shows up as a
1-frame judder in an otherwise-stable scene, and the vestibular system is extremely good at
noticing exactly that. This is the concrete reason LibVLC must be the *fallback* and never
the default, even though it plays more files.

**Thermal.** Arc already has a `ThermalGovernor` (`app/perf/ThermalGovernor.kt`, tested) and
a `RenderWatchdog`. Software video decode is the workload those exist to prevent:

| Workload | Rough steady-state cost |
|---|---|
| 4K60 HEVC, hardware | one fixed-function block, ~0.5–1.5 W |
| 1080p H.264, software (libavcodec, 4 threads) | 3–4 big cores near saturation |
| 4K HEVC, software | not achievable in real time on a phone; frames drop, cores pin, the device throttles within minutes |

And in a Cardboard shell there is no airflow and the phone is against the user's face.
Therefore, in the recommended design, **LibVLC is configured to use MediaCodec for video and
is only permitted to fall back to software video decode under an explicit resolution cap**
(§9.3). LibVLC's value to Arc is its *demuxer* and its *audio* decoders, not its software
video decoder.

**Battery.** ~1 extra big-core's worth of draw during software audio decode is
indistinguishable from Option A. Software *video* decode roughly triples system power for
playback.

**APK size.** `libvlc-all` carries all four ABIs. `abiFilters("arm64-v8a")` (§7.3) cuts it
to roughly a quarter. Arc targets a Pixel 11 Pro; there is no 32-bit case. **[verify by
measuring the APK — do not trust the figure in §1.3.]**

### 5.5 The architecture required to swap in a LibVLC `VideoPlayer`

`VideoPlayer` (`playback/VideoPlayer.kt:13-41`) is 11 methods and a `StateFlow`. A
`VlcVideoPlayer` implementing it is genuinely feasible, and the interface needs no changes.
The work is in the impedance mismatches:

| `VideoPlayer` member | LibVLC equivalent | Mismatch to absorb |
|---|---|---|
| `attach(Surface)` | `vout.setVideoSurface(s, null)` + `setWindowSize` + `attachViews` | must know the video size before attach; §5.3 |
| `detach()` | `vout.detachViews()` | thread-ordering with the GL thread |
| `play(PlayRequest)` | `Media(libVLC, uri)`; `setMedia`; `play` | `content://` needs an fd, not a URI — §8.6 |
| `seekTo(ms, exact)` | `mediaPlayer.time = ms` | no `CLOSEST_SYNC`/`EXACT` distinction; `ScrubController`'s preview-then-commit model (ARCHITECTURE.md §10.4) degrades to "commit only" |
| `setSpeed(f)` | `mediaPlayer.rate = f` | pitch correction differs |
| `selectAudioTrack(id)` | `mediaPlayer.setAudioTrack(vlcTrackId)` | **ids are not compatible.** Arc's ids are `"groupIndex/trackIndex"` (`ExoVideoPlayer.kt:292`); VLC's are opaque ints. The router must namespace them |
| `snapshot` | poll `time`/`length` + `Event` callbacks | VLC emits `TimeChanged` at ~250 ms; Arc polls at 500 ms (`ExoVideoPlayer.kt:405`). Compatible |
| `activeCues` | VLC renders subtitles into the *subtitle* surface | **no text callback.** Arc's `SubtitleRenderer` draws cues itself. Either give VLC a second `SurfaceTexture` for its subtitle plane, or accept no subtitles on the fallback engine (recommended for v1) |

The track-id and subtitle mismatches are the two that leak into the UI layer, and they are
the reason §8.3 introduces an engine-tagged id rather than trying to unify the two schemes.

### 5.6 Option B scorecard

| Dimension | Assessment |
|---|---|
| Closes the reported "no sound" class | fully |
| Closes ASF / RM / VC-1 / MPEG-2 / ASP | **fully — the only option that does** |
| Tolerates malformed containers | **yes — the only option that does** |
| Build complexity | **lowest of the three** (one Maven coordinate) |
| Risk to the VR render loop | **high if primary** (§5.4), low if quarantined as fallback |
| Feature parity with the current player | subtitles and exact-seek scrub regress |
| APK delta | ~30–40 MB arm64 **[verify]** |
| Licensing | LGPL-2.1 relink obligation (same shape as A) + the same Dolby/DTS patent question |

**As a replacement for Media3: reject.** It regresses frame pacing, subtitles, scrubbing, and
`FrameStats`, in exchange for a long tail of files.
**As a second engine: accept.**

---

## 6. Option C — Hybrid

### 6.1 Shape

```
                         ┌──────────────────────────────┐
   EffectRunner ────────►│  PlaybackEngineRouter        │  implements VideoPlayer
                         │  (playback/)                 │
                         └──────┬───────────────┬───────┘
                                │               │
              primary ──────────┘               └────────── fallback
        ┌────────────────────────────┐   ┌──────────────────────────────┐
        │ ExoVideoPlayer             │   │ VlcVideoPlayer               │
        │ Media3 + DefaultDataSource │   │ libvlc-all                   │
        │ + media3-decoder-ffmpeg    │   │ mediacodec_ndk video         │
        │ HW video, vsync-paced      │   │ SW demux + SW audio          │
        └────────────┬───────────────┘   └───────────────┬──────────────┘
                     └──────────► same Surface ◄─────────┘
                            vrcore/gl/VideoTexture
```

Both engines write to the identical `Surface`. `AppScene`, `VrRenderer`, `ScreenPanel` and
every shader are untouched — they cannot tell which engine produced the frame.

### 6.2 Routing policy

Three inputs, evaluated in order:

**1 — Sticky per-item hint (fast path).** A `PlaybackEngineStore`, keyed by
`MediaKey.storageKey()` (the same key `ResumeStore` uses, `MediaModel.kt:29`), records which
engine last succeeded. On replay, go straight there. This is what makes "the file that
failed yesterday" open instantly today.

**2 — Static pre-route (avoid a guaranteed failure).** If the URI extension or the DLNA
`protocolInfo` MIME is in a known-Media3-hostile set, start on LibVLC:

```
.wmv .asf .rm .rmvb .ogm .divx .vob(some) .mpg/.mpeg(MPEG-2 video) .ts(VC-1)
video/x-ms-wmv  video/x-ms-asf  application/vnd.rn-realmedia  audio/x-ms-wma
```

Extension sniffing is a heuristic and must never be the *only* mechanism — hence rule 3.
Note this list is also directly consumable by `ResourceRanker` via `DecoderCaps.mimeTypes`
(`upnp/cds/ResourceRanker.kt:16-19`), which already ranks known MIME types above unknown
ones. Widening `DecoderCapsProvider.CONTAINERS_BY_VIDEO_MIME`
(`DecoderCapsProvider.kt:57-65`) to reflect the *union* of both engines' capabilities is
part of F2.

**3 — Reactive failover (the correctness backstop).** On a failure classified as
engine-fatal, re-open on the other engine at the last known position:

| `PlaybackFailure` | Engine-fatal? |
|---|---|
| `UnsupportedContainer` (new, §8.4) | **yes — immediate** |
| `MalformedContainer` (new) | **yes** (VLC's leniency is the point) |
| `UnsupportedVideoCodec` | yes, *after* exhausting ranked resources |
| `MissingAudioDecoder` (new, §2.5) | yes, *if* the FFmpeg extension also lacks it |
| `DecoderInitFailed` | yes, after resources |
| `NetworkTimeout` / `ConnectionFailed` / `BadHttpStatus` | **no** — transport, not format. Existing ladder applies |
| `AudioTrackInitFailed` | no — try FFmpeg renderer first |

The rule that keeps this from becoming a loop: **failover is attempted at most once per
`PlayRequest`, in one direction only** (Exo → VLC, never back). `FallbackPolicy` gets an
`enginesTried` parameter, and it remains a pure function — which is what keeps the whole
policy JVM-testable, as `FallbackPolicyTest` already is.

### 6.3 Why not probe first?

Opening every file on both engines to see which works costs 200–800 ms of latency on the
99 % of files that work fine, and doubles the decoder-init thermal load. The sticky hint
(rule 1) already gives probing's benefit on the second play, without its cost on the first.

`MediaMetadataRetriever` as a pre-flight probe is also tempting and also wrong: it uses the
*same* platform extractors and codecs as MediaCodec, so it tells you nothing Media3 would
not have told you 50 ms later, and it costs a full extra open.

### 6.4 Option C scorecard

| Dimension | Assessment |
|---|---|
| Coverage | ~VLC parity |
| Frame pacing on the common path | **unchanged** — Media3 handles ≥95 % of playbacks |
| Frame pacing on the rare path | VLC-grade, on files that otherwise would not play at all |
| Failure mode when routing guesses wrong | one extra open, ~1 s, invisible-ish |
| New concepts in the codebase | one interface implementation, one router, one policy input, one store |
| APK delta | ~33–45 MB **[verify]** |
| Ongoing maintenance | two engines' quirks; mitigated by the router being the only place they meet |

---

## 7. Recommendation and rationale

### 7.1 The recommendation

**Build F0 → F1 → F2, gating each on the acceptance criteria in §11. Ship F0 and F1
together as the format release; ship F2 behind a settings toggle
(`Settings.enableCompatibilityEngine`, default on) so it can be disabled in the field if it
destabilises.**

### 7.2 Why, for a *mobile VR headset player* specifically

Four constraints make this different from choosing a player engine for a phone app:

1. **The frame budget is not negotiable.** At 90 Hz stereo, the whole frame — two eye
   passes, distortion mesh, panels, gaze raycast — has ~11 ms. Media3's vsync-aligned
   release (§4.4) is not a nicety; it is the reason motion looks stable when the head moves.
   Any design that makes VLC the default trades a visible, physical comfort regression for a
   long-tail compatibility win. Wrong trade.
2. **Thermal headroom is the scarcest resource.** The phone is sealed in plastic against a
   face, already driving a 4K panel at 90 Hz with a distortion pass. `ThermalGovernor`
   exists because this is already tight. Software *video* decode is off the table as a
   default; software *audio* decode is free. That maps exactly onto Option A being the
   primary and Option B being capped (§9.3).
3. **Errors must be recoverable inside the headset.** ARCHITECTURE.md §10.5: "A silent black
   screen with a logcat entry is a product failure." The hybrid's automatic failover is the
   in-headset recovery — the user never sees a codec name. This argues for the router being
   automatic and *not* a user-facing "try VLC?" prompt, which would be unusable through
   lenses with a gamepad.
4. **The library is a home NAS, not a curated catalogue.** DLNA libraries are full of
   decade-old rips: MKV/AC3, AVI/Xvid, WMV, TS/VC-1. This is precisely the population where
   Media3's coverage is weakest, which is why "just use Media3, it covers modern formats" is
   the wrong read of the requirement.

### 7.3 Why not Option A alone

It leaves ASF/WMV, RealMedia, VC-1, MPEG-2 and malformed containers failing, and every one
of those is a "VLC plays it fine" report. It is, however, a legitimate v1 scope cut — see
§13.

### 7.4 Why not Option B alone

§5.4 and §5.6. It regresses frame pacing, subtitles, scrub precision and `FrameStats`, in a
product whose differentiator is comfortable VR playback.

### 7.5 Sequencing rationale

F0 first because it is a bug fix that unblocks a shipped feature (local media, M1–M6 are
already merged and unreachable). F1 second because it is small, low-risk, and closes the
largest single symptom class. F2 last because it is the only phase that adds a second
runtime, and it should land against a codebase where the error taxonomy (§8.4) and the
router seam (§8.3) are already proven by F0/F1.

---

## 8. Detailed design

### 8.1 F0 — `ExoVideoPlayer` data source (D1)

```kotlin
// playback/src/main/java/com/daydreamvr/playback/ExoVideoPlayer.kt

import androidx.media3.datasource.DefaultDataSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

private fun ensurePlayer() {
    if (player != null) return

    val renderers = DefaultRenderersFactory(context)
        // D6: hardware first, extension (ffmpeg) as fallback. Never PREFER —
        // that would route AAC/MP3 through the software decoder (§2.6).
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)

    val httpFactory = OkHttpDataSource.Factory(okHttpClient)   // injected, see §8.7

    // D1: scheme-dispatching upstream. content:// → ContentDataSource,
    // file:// and bare paths → FileDataSource, http(s) → the OkHttp factory.
    val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)

    val extractors = DefaultExtractorsFactory()
        // CBR seeking makes AVI/MP3 scrubbing usable when there is no index.
        .setConstantBitrateSeekingEnabled(true)
        .setConstantBitrateSeekingAlwaysEnabled(true)
        // DLNA servers hand out TS with AC-3/E-AC-3/DTS elementary streams.
        .setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
        )

    val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractors)

    ...
    .setMediaSourceFactory(mediaSourceFactory)
}
```

Notes:

- `DefaultDataSource.Factory(context, upstream)` — the two-argument form. The one-argument
  form builds its own `DefaultHttpDataSource` and would silently drop OkHttp, losing the
  shared connection pool ARCHITECTURE.md §2 explicitly wants.
- `media3-datasource` and `media3-extractor` arrive transitively with `media3-exoplayer`;
  no new coordinate is required for F0. Add explicit catalog entries anyway (§9.1) so the
  imports are declared rather than inherited.
- `FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS` makes `TsExtractor` emit DTS tracks it otherwise
  skips. Without F1 those tracks are then unselectable (D5) — which is *correct* behaviour,
  and now correctly *reported*.

### 8.2 F0 — decoupling `PlayRequest` from `:upnp` (D2)

Introduce a source type owned by `:playback`:

```kotlin
// playback/src/main/java/com/daydreamvr/playback/PlaybackSource.kt   (new)
package com.daydreamvr.playback

/**
 * One candidate set of bytes for an item, engine- and protocol-agnostic.
 * Replaces `com.daydreamvr.upnp.model.Resource` in [PlayRequest] so `:playback`
 * no longer depends on the protocol layer.
 *
 * PURE Kotlin — no `android.*`. [uri] is a string for the same reason
 * `MediaRef` is (UI_REDESIGN_REVIEWED_PLAN.md §7, R6).
 */
data class PlaybackSource(
    val uri: String,
    val mimeType: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val bitrate: Int? = null,
    val sizeBytes: Long? = null,
) {
    val isContent: Boolean get() = uri.startsWith("content://")
    val isFile: Boolean get() = uri.startsWith("file://") || uri.startsWith("/")
    val isHttp: Boolean get() = uri.startsWith("http://") || uri.startsWith("https://")
}
```

```kotlin
// playback/.../VideoPlayer.kt  — changed
data class PlayRequest(
    val itemKey: String,
    val title: String,
    val sources: List<PlaybackSource>,      // was: rankedResources: List<Resource>
    val startAtMs: Long = 0L,
    val projection: ProjectionMode? = null,
    /** Router hint from [PlaybackEngineStore]; null = let the router decide. F2. */
    val preferredEngine: PlaybackEngine? = null,
)
```

Mapping lives in `:app`, next to the other adapters:

```kotlin
// app/src/main/java/com/daydreamvr/player/media/PlaybackSourceAdapter.kt   (new, pure)
object PlaybackSourceAdapter {

    fun fromUpnp(ranked: List<Resource>): List<PlaybackSource> = ranked.map {
        PlaybackSource(
            uri = it.uri.toString(),
            mimeType = it.mimeType,
            width = it.resolution?.width ?: 0,
            height = it.resolution?.height ?: 0,
            bitrate = it.bitrate,
            sizeBytes = it.sizeBytes,
        )
    }

    fun fromLocal(video: MediaNode.Video, ref: MediaRef): List<PlaybackSource> = listOf(
        PlaybackSource(
            uri = ref.value,
            mimeType = video.mimeType,
            width = video.width,
            height = video.height,
            sizeBytes = video.sizeBytes,
        ),
    )
}
```

Then `playback/build.gradle.kts:44` drops `api(project(":upnp"))`. Confirm nothing else in
`:playback` imports `com.daydreamvr.upnp` — at `4124200` only `VideoPlayer.kt:4` and
`DecoderCapsProvider.kt:6` do, and the latter should move its `DecoderCaps` dependency the
same way (it currently reaches *into* `:upnp` to construct a type `:upnp` owns, which is
backwards).

`Effect.Play` becomes node-typed, matching the M4 strangler that
`UI_REDESIGN_REVIEWED_PLAN.md` §14 already schedules as `Effect.PlayNode`:

```kotlin
data class PlayNode(
    val node: MediaNode.Video,
    val sourceId: String,          // MediaSource.id — "local" or "upnp:uuid:…"
    val startAtMs: Long,
    val projectionOverride: ProjectionMode?,
    val skipResumeCheck: Boolean = false,
) : Effect
```

`EffectRunner.play` then branches once on `node.playback`:

```kotlin
val sources = when (val ref = effect.node.playback) {
    is PlaybackRef.Upnp  -> PlaybackSourceAdapter.fromUpnp(ResourceRanker.rank(ref.resources, decoderCaps()))
    is PlaybackRef.Local -> PlaybackSourceAdapter.fromLocal(effect.node, ref.ref)
}
```

> **Sequencing note.** This overlaps M4's strangler. If M4 has not landed when F0 starts,
> do the minimal version — keep `Effect.Play(item: DidlItem, …)` and add a sibling
> `Effect.PlayLocal(video: MediaNode.Video)` — and let M4 collapse them. Do **not** block
> the D1 fix on the domain refactor; D1 is a one-line change to a shipped defect.

### 8.3 F2 — the engine seam

```kotlin
// playback/src/main/java/com/daydreamvr/playback/PlaybackEngine.kt   (new, pure)
enum class PlaybackEngine { MEDIA3, VLC }

/** Persisted per item, keyed by `MediaKey.storageKey()`. Survives process death. */
interface PlaybackEngineStore {
    fun preferred(itemKey: String): PlaybackEngine?
    fun remember(itemKey: String, engine: PlaybackEngine)
}
```

```kotlin
// playback/src/main/java/com/daydreamvr/playback/PlaybackEngineRouter.kt   (new)
/**
 * The only [VideoPlayer] the app constructs from F2 onward. Owns both engines,
 * forwards every transport call to the active one, and republishes its snapshot.
 *
 * Both engines share one [Surface]; only one is prepared at a time. The inactive
 * engine is fully released (not merely paused) so it holds no MediaCodec instance —
 * the platform limits concurrent secure/HW decoder instances and a leaked one is a
 * black screen on the next play.
 */
@UnstableApi
class PlaybackEngineRouter(
    private val media3: VideoPlayer,
    private val vlcFactory: () -> VideoPlayer,   // lazy: do not load libvlcjni.so unless needed
    private val engineStore: PlaybackEngineStore,
    private val preRoute: (PlaybackSource) -> PlaybackEngine = EnginePreRoute::decide,
    private val compatibilityEngineEnabled: () -> Boolean = { true },
) : VideoPlayer { ... }
```

```kotlin
// playback/src/main/java/com/daydreamvr/playback/EnginePreRoute.kt   (new, PURE — the testable core)
object EnginePreRoute {

    /** Containers with no Media3 extractor at all (§2.7). */
    val MEDIA3_HOSTILE_EXTENSIONS = setOf("wmv", "asf", "rm", "rmvb", "ogm", "divx")

    val MEDIA3_HOSTILE_MIMES = setOf(
        "video/x-ms-wmv", "video/x-ms-asf", "video/x-msvideo-ms",
        "application/vnd.rn-realmedia", "application/vnd.rn-realmedia-vbr",
        "audio/x-ms-wma",
    )

    fun decide(source: PlaybackSource): PlaybackEngine {
        val mime = source.mimeType?.lowercase()
        if (mime != null && mime in MEDIA3_HOSTILE_MIMES) return PlaybackEngine.VLC
        val ext = source.uri.substringAfterLast('.', "").substringBefore('?').lowercase()
        if (ext in MEDIA3_HOSTILE_EXTENSIONS) return PlaybackEngine.VLC
        return PlaybackEngine.MEDIA3
    }
}
```

Keeping `EnginePreRoute` pure and separate from the router is what lets the whole routing
table be unit-tested with no Android, no ExoPlayer and no `libvlcjni.so` — the same
discipline `FallbackPolicy` already follows.

**Track ids across engines.** `applyOverride` parses `"groupIndex/trackIndex"`
(`ExoVideoPlayer.kt:292`) and would `NumberFormatException` on a VLC id. Namespace them at
the source: `ExoVideoPlayer` emits `"m3:$g/$t"`, `VlcVideoPlayer` emits `"vlc:$id"`, and
each implementation rejects ids that are not its own. The UI treats `TrackInfo.id` as
opaque, which it already does (`PlayerHud`/`OverlayRenderer` pass it straight through).

### 8.4 F0/F2 — the error taxonomy

```kotlin
// playback/.../PlaybackFailure.kt  — additions

/** No demuxer recognised the byte stream. The strongest signal to switch engines. */
data class UnsupportedContainer(val hint: String?) : PlaybackFailure() {
    override val userMessage =
        "This file's format isn't supported" + (hint?.let { " ($it)" } ?: "") + "."
}

/** A demuxer recognised the container but the bytes are damaged or truncated. */
data object MalformedContainer : PlaybackFailure() {
    override val userMessage = "This file appears to be damaged."
}

/** The file has audio, but no decoder on this device can play it (§2.5). Not an exception. */
data class MissingAudioDecoder(val codec: String?) : PlaybackFailure() {
    override val userMessage =
        "This file's audio" + (codec?.let { " ($it)" } ?: "") + " can't be decoded on this phone."
}
```

```kotlin
// ExoVideoPlayer.classify() — additions
PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
    PlaybackFailure.UnsupportedContainer(lastContainerHint)

PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
->
    PlaybackFailure.MalformedContainer
```

```kotlin
// ExoVideoPlayer.PlayerListener.onTracksChanged — the D5 detector
override fun onTracksChanged(tracks: Tracks) {
    ...
    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    if (audioGroups.isNotEmpty() && !tracks.isTypeSelected(C.TRACK_TYPE_AUDIO)) {
        val codec = audioGroups.first().getTrackFormat(0).let { it.codecs ?: it.sampleMimeType }
        onSilentAudio(PlaybackFailure.MissingAudioDecoder(codec))
    }
}
```

`onSilentAudio` publishes the failure on the snapshot **without stopping playback** — the
video is fine — and, under F2, asks the router to consider a re-open on VLC at the current
position. Under F1-only it surfaces an in-headset toast: "No audio — this file uses DTS,
which this phone can't decode." That is a strictly better product outcome than silence even
if nothing can be done about it.

`FallbackPolicy.decide` gains the new arms and one parameter:

```kotlin
fun decide(
    failure: PlaybackFailure,
    attempt: Int,
    remainingResources: Int,
    enginesTried: Set<PlaybackEngine> = setOf(PlaybackEngine.MEDIA3),   // F2
): FallbackAction
```

```kotlin
is PlaybackFailure.UnsupportedContainer,
PlaybackFailure.MalformedContainer,
-> when {
    PlaybackEngine.VLC !in enginesTried -> FallbackAction.SwitchEngine(PlaybackEngine.VLC)
    remainingResources > 0              -> FallbackAction.NextResource
    else                                -> FallbackAction.GiveUp(failure.userMessage)
}
```

`FallbackAction` gains `data class SwitchEngine(val to: PlaybackEngine) : FallbackAction`.
The default value on `enginesTried` keeps every existing `FallbackPolicyTest` case
compiling and green, which is the point — the F2 policy change must not disturb the F0/F1
ladder.

### 8.5 F1 — renderer wiring

Beyond the mode fix in §8.1, nothing. The extension is discovered reflectively. Add a
diagnostic so the "is it actually loaded?" question is answerable from the debug overlay
rather than by reading logcat, since D4 was invisible for exactly that reason:

```kotlin
// playback/src/main/java/com/daydreamvr/playback/FfmpegAvailability.kt   (new)
object FfmpegAvailability {
    /** True when libffmpegJNI.so is present and loadable. Reflective: no compile-time dep. */
    val isAvailable: Boolean by lazy {
        runCatching {
            val lib = Class.forName("androidx.media3.decoder.ffmpeg.FfmpegLibrary")
            lib.getMethod("isAvailable").invoke(null) as Boolean
        }.getOrDefault(false)
    }

    val version: String? by lazy {
        runCatching {
            Class.forName("androidx.media3.decoder.ffmpeg.FfmpegLibrary")
                .getMethod("getVersion").invoke(null) as String?
        }.getOrNull()
    }
}
```

Surface `isAvailable`/`version` in `DebugOverlay` (`app/debug/DebugOverlay.kt`) next to the
existing diagnostics.

### 8.6 F2 — `VlcVideoPlayer` sketch

```kotlin
// playback/src/main/java/com/daydreamvr/playback/vlc/VlcVideoPlayer.kt   (new)
class VlcVideoPlayer(
    private val context: Context,
    private val resumeStore: ResumeStore,
    private val onFatalError: (String) -> Unit = {},
) : VideoPlayer {

    private val libVlc: LibVLC by lazy {
        LibVLC(
            context,
            arrayListOf(
                // Video stays on MediaCodec. Software video decode is a thermal
                // non-starter in a sealed viewer (§5.4); the cap is enforced in §9.3.
                "--codec=mediacodec_ndk,none",
                "--avcodec-skiploopfilter=0",
                "--avcodec-skip-frame=0",
                "--avcodec-skip-idct=0",
                // Demux + audio are where VLC earns its place.
                "--audio-time-stretch",
                "--network-caching=3000",   // LAN; mirrors DefaultLoadControl's 2.5 s floor
                "--file-caching=1500",
                "--no-sub-autodetect-file", // subtitles are not supported on this engine (§5.5)
                "-vv".takeIf { BuildConfig.DEBUG } ?: "-q",
            ),
        )
    }

    private var mediaPlayer: MediaPlayer? = null
    private var attachedSurface: Surface? = null
    private var attached = false

    override fun attach(surface: Surface) {
        attachedSurface = surface
        val mp = mediaPlayer ?: return
        val vout = mp.vlcVout
        if (attached) vout.detachViews()
        vout.setVideoSurface(surface, null)
        // MANDATORY. Without it: audio, black screen, no error (§5.3).
        vout.setWindowSize(surfaceWidth, surfaceHeight)
        vout.attachViews { _, w, h, _, _, _ -> onVideoLayout(w, h) }
        attached = true
    }

    override fun detach() {
        // Must complete before the GL thread calls VideoTexture.release().
        mediaPlayer?.vlcVout?.takeIf { attached }?.detachViews()
        attached = false
        attachedSurface = null
    }

    private fun mediaFor(source: PlaybackSource): Media = when {
        // content:// is not a URI LibVLC's access modules understand. Hand it an fd.
        source.isContent -> context.contentResolver
            .openAssetFileDescriptor(Uri.parse(source.uri), "r")
            .use { afd -> Media(libVlc, requireNotNull(afd).parcelFileDescriptor.fileDescriptor) }

        else -> Media(libVlc, Uri.parse(source.uri))
    }
    ...
}
```

**The surface-lifecycle handshake is the highest-risk part of F2.** `VideoTexture.release()`
runs on the GL thread; `detachViews()` blocks until VLC's native vout thread has stopped
touching the surface. Ordering:

```
GL thread wants to release
  → post to main: player.detach()          // synchronous detachViews()
  → main posts back to GL: VideoTexture.release()
```

Releasing the `SurfaceTexture` while VLC's vout still holds it is a native crash inside
`libvlcjni.so`, not a Java exception, and it will not appear in any test that does not run
on a device. `SurfaceLifecycleTest` (§11.3) exists specifically to catch it.

### 8.7 Shared `OkHttpClient` (incidental, do it in F0)

`ExoVideoPlayer.kt:153` constructs `OkHttpClient()` inline, while `AppContainer:52` already
holds `HttpTransport.okHttpDefault()` for UPnP. ARCHITECTURE.md §2 calls for "one connection
pool, one timeout policy, one set of interceptors". Thread the shared client into
`ExoVideoPlayer`'s constructor. One line each in `AppContainer`, `VrActivity:122` and
`ExoVideoPlayer`.

---

## 9. Build configuration

### 9.1 Version catalog

```toml
# gradle/libs.versions.toml

[versions]
# Bump per ARCHITECTURE.md §2's pinning rule: newest stable at implementation time.
# The media3-decoder-ffmpeg AAR MUST be built from the identical tag (§9.2).
media3 = "1.4.1"                # [verify: newest stable]
libvlc = "3.6.5"                # [verify on Maven Central]

[libraries]
media3-exoplayer         = { group = "androidx.media3", name = "media3-exoplayer",         version.ref = "media3" }
media3-common            = { group = "androidx.media3", name = "media3-common",            version.ref = "media3" }
media3-datasource        = { group = "androidx.media3", name = "media3-datasource",        version.ref = "media3" }  # F0: DefaultDataSource
media3-datasource-okhttp = { group = "androidx.media3", name = "media3-datasource-okhttp", version.ref = "media3" }
media3-extractor         = { group = "androidx.media3", name = "media3-extractor",         version.ref = "media3" }  # F0: DefaultExtractorsFactory, TS flags

libvlc-all               = { group = "org.videolan.android", name = "libvlc-all", version.ref = "libvlc" }           # F2
```

`media3-datasource` and `media3-extractor` are already on the compile classpath transitively;
declaring them makes the dependency explicit rather than incidental, which matters because
F0 imports from both directly.

### 9.2 `media3-decoder-ffmpeg` — the one-time bake (F1)

Approach A1 from §4.5. Run once, on a Linux or macOS host with the NDK installed; commit
the output.

```bash
# ---- inputs, recorded verbatim in playback/libs/BUILD.md ----------------------
MEDIA3_TAG=1.4.1                     # MUST equal libs.versions.toml [versions] media3
FFMPEG_TAG=n6.0                      # [verify: pick the branch the media3 tag builds against]
NDK_PATH=$ANDROID_HOME/ndk/26.1.10909125
HOST_PLATFORM=linux-x86_64
ANDROID_ABI=21                       # minimum API level for the native build, NOT an ABI name

# ---- fetch -------------------------------------------------------------------
git clone --depth 1 --branch "$MEDIA3_TAG" https://github.com/androidx/media.git
cd media
FFMPEG_MODULE_PATH="$(pwd)/libraries/decoder_ffmpeg/src/main"
cd "$FFMPEG_MODULE_PATH/jni"
git clone https://git.ffmpeg.org/ffmpeg.git ffmpeg
cd ffmpeg && git checkout "$FFMPEG_TAG" && cd ..

# ---- the decoder set ---------------------------------------------------------
# Every one of these is LGPL. Do NOT add --enable-gpl or --enable-nonfree (§4.6).
ENABLED_DECODERS=(
  ac3 eac3            # Dolby Digital / Digital Plus  ← the headline fix
  dca                 # DTS core + HD extensions
  mlp truehd          # Dolby TrueHD
  alac                # Apple Lossless
  wmav1 wmav2 wmapro  # WMA family (pairs with the VLC demuxer in F2)
  vorbis opus flac    # complete the coverage where hardware is inconsistent
  mp3 aac             # fallback when a device's MediaCodec AAC is broken
  amrnb amrwb
  pcm_s16le pcm_s24le pcm_s32le pcm_f32le pcm_mulaw pcm_alaw
)

./build_ffmpeg.sh "$FFMPEG_MODULE_PATH" "$NDK_PATH" "$HOST_PLATFORM" "$ANDROID_ABI" "${ENABLED_DECODERS[@]}"

# ---- assemble the AAR --------------------------------------------------------
cd "$(git rev-parse --show-toplevel)"
./gradlew :lib-decoder-ffmpeg:assembleRelease
# → libraries/decoder_ffmpeg/buildout/outputs/aar/lib-decoder-ffmpeg-release.aar
```

**[verify]** the module path and Gradle project name against the pinned tag before running
— the media3 repository has renamed module directories between releases.

Then, in Arc:

```
playback/libs/media3-decoder-ffmpeg-1.4.1.aar
playback/libs/BUILD.md          # the block above, with the exact commit SHAs used
playback/libs/FFMPEG_SOURCE.md  # the LGPL §6 relink notice + source URL (§4.6)
```

```kotlin
// playback/build.gradle.kts
dependencies {
    implementation(files("libs/media3-decoder-ffmpeg-$MEDIA3_VERSION.aar"))
    ...
}
```

```gitattributes
# .gitattributes
playback/libs/*.aar binary
```

**Trim the ABI.** The bake produces `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`. Arc targets
a Pixel 11 Pro:

```kotlin
// app/build.gradle.kts — defaultConfig
ndk { abiFilters += listOf("arm64-v8a") }     // add "x86_64" if emulator testing is needed
```

```kotlin
// app/build.gradle.kts — android { }
packaging {
    jniLibs {
        useLegacyPackaging = false   // uncompressed .so, mapped from the APK; no extraction
    }
}
```

### 9.3 LibVLC (F2)

```kotlin
// playback/build.gradle.kts
dependencies {
    implementation(libs.libvlc.all)
}
```

`mavenCentral()` is already declared (`settings.gradle.kts:18`). No JitPack, no VideoLAN
repository, no `flatDir`.

**The thermal cap, enforced in code, not by convention.** LibVLC is constructed with
`--codec=mediacodec_ndk,none` — MediaCodec only, no software video decoder. When MediaCodec
refuses (VC-1, MPEG-2, RealVideo — exactly the cases F2 exists for), that config yields no
video. So the fallback is *conditional*, and the condition is resolution:

```kotlin
// playback/src/main/java/com/daydreamvr/playback/vlc/VlcCodecPolicy.kt   (new, PURE)
object VlcCodecPolicy {

    /** Above this, software video decode cannot hold frame rate on a phone and cooks it (§5.4). */
    const val MAX_SOFTWARE_DECODE_HEIGHT = 1080

    /** Never permit software decode above LIGHT thermal load, whatever the resolution. */
    const val MAX_THERMAL_STATUS_FOR_SOFTWARE = 1   // PowerManager.THERMAL_STATUS_LIGHT

    fun codecOption(height: Int, thermalStatus: Int): String = when {
        height in 1..MAX_SOFTWARE_DECODE_HEIGHT && thermalStatus <= MAX_THERMAL_STATUS_FOR_SOFTWARE ->
            "--codec=mediacodec_ndk,avcodec"     // HW first, SW permitted
        else ->
            "--codec=mediacodec_ndk,none"        // HW only; fail rather than melt
    }
}
```

`thermalStatus` is already plumbed — `AppScene`'s `thermalStatusProvider`
(`VrActivity.kt:143-147`) reads `PowerManager.currentThermalStatus`, and `ThermalGovernor`
consumes it. Reuse that source; do not add a second one.

The honest consequence: a 4K VC-1 file still will not play. That is the correct outcome —
it *cannot* play on this hardware at an acceptable frame rate, and failing with "this file
is too demanding for this phone" beats a 4-fps slideshow that thermal-throttles the whole
render loop and ruins the session.

### 9.4 R8 / ProGuard

`app/proguard-rules.pro` already carries `-keep class androidx.media3.** { *; }`, which
covers the FFmpeg extension's reflective entry point. Make the intent explicit anyway,
since the existing broad keep is a candidate for future narrowing and the reflective
constructor would be the silent casualty:

```proguard
# ---------------------------------------------------------------------------
# Media3 FFmpeg decoder extension (docs/FORMAT_SUPPORT_PLAN.md §4.5, §8.5)
#
# DefaultRenderersFactory finds FfmpegAudioRenderer via Class.forName + the
# (Handler, AudioRendererEventListener, AudioSink) constructor and SWALLOWS
# ClassNotFoundException. R8 removing it produces no error, no log, and silent
# AC-3 playback -- exactly the D4 failure this document exists to prevent.
# ---------------------------------------------------------------------------
-keep class androidx.media3.decoder.ffmpeg.FfmpegLibrary { *; }
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer {
    public <init>(android.os.Handler,
                  androidx.media3.exoplayer.audio.AudioRendererEventListener,
                  androidx.media3.exoplayer.audio.AudioSink);
}
-keepclasseswithmembernames class androidx.media3.decoder.ffmpeg.** {
    native <methods>;
}

# ---------------------------------------------------------------------------
# LibVLC (F2)
#
# libvlcjni.so resolves Java classes, fields and methods BY NAME through JNI.
# Any rename or removal is a native crash with no Java stack trace.
# ---------------------------------------------------------------------------
-keep class org.videolan.libvlc.** { *; }
-keep interface org.videolan.libvlc.** { *; }
-keep class org.videolan.libvlc.interfaces.** { *; }
-keepclassmembers class org.videolan.libvlc.** {
    native <methods>;
    <fields>;
    <init>(...);
}
-dontwarn org.videolan.**
```

The `<fields>` keep on the LibVLC package is deliberately broad: libvlcjni caches
`jfieldID`s for `MediaPlayer.Event` payload fields and `Media.Track` subclasses. Narrowing
it is a future optimisation that must be validated on-device, not in CI.

### 9.5 Manifest

No new permissions. `READ_MEDIA_VIDEO` and `READ_EXTERNAL_STORAGE (maxSdkVersion=32)` are
already declared (`AndroidManifest.xml:12-18`) and are what `ContentDataSource` needs for
`content://media/...`.

One addition worth considering for F2: `android:largeHeap="true"` is **not** needed —
LibVLC's buffers are native, outside the Java heap.

### 9.6 Build-verification commands

```bash
export ANDROID_HOME=/root/android-sdk

./gradlew check                        # ktlint + all JVM unit tests + :upnp purity rule
./gradlew :app:assembleRelease         # R8 full mode with the new keep rules
./gradlew :app:connectedCheck          # instrumented; needs the handset

# F1 only: confirm the native lib actually shipped
unzip -l app/build/outputs/apk/release/app-release.apk | grep -E 'libffmpegJNI|libvlcjni'

# F1 only: confirm R8 did not strip the reflective renderer
unzip -p app/build/outputs/apk/release/app-release.apk classes.dex \
  | strings | grep -c 'FfmpegAudioRenderer'     # must be ≥ 1
```

That last command is worth putting in CI. It is the only cheap check that catches D4's
failure mode, which produces no error at any layer.

---

## 10. File-by-file change inventory

### 10.1 F0 — local playback works

| File | Change |
|---|---|
| `playback/src/main/java/com/daydreamvr/playback/ExoVideoPlayer.kt` | `DefaultDataSource.Factory(context, httpFactory)`; `DefaultExtractorsFactory` with CBR seeking + TS flags; renderer mode `..._ON` (D6); inject `OkHttpClient`; new `classify()` arms; D5 detector in `onTracksChanged`; emit `"m3:"`-prefixed track ids |
| `playback/src/main/java/com/daydreamvr/playback/PlaybackSource.kt` | **new**, pure |
| `playback/src/main/java/com/daydreamvr/playback/VideoPlayer.kt` | `PlayRequest.sources: List<PlaybackSource>` replaces `rankedResources` |
| `playback/src/main/java/com/daydreamvr/playback/PlaybackFailure.kt` | `UnsupportedContainer`, `MalformedContainer`, `MissingAudioDecoder` |
| `playback/src/main/java/com/daydreamvr/playback/FallbackPolicy.kt` | arms for the three new failures |
| `playback/build.gradle.kts` | drop `api(project(":upnp"))`; add `media3-datasource`, `media3-extractor` |
| `app/src/main/java/com/daydreamvr/player/media/PlaybackSourceAdapter.kt` | **new**, pure |
| `app/src/main/java/com/daydreamvr/player/state/Effect.kt` | `Play` → `PlayNode(node, sourceId, …)` (or the interim `PlayLocal`, §8.2) |
| `app/src/main/java/com/daydreamvr/player/state/EffectRunner.kt` | branch on `PlaybackRef`; build sources via the adapter |
| `app/src/main/java/com/daydreamvr/player/state/AppStateMachine.kt` | emit the new effect |
| `app/src/main/java/com/daydreamvr/player/di/AppContainer.kt` | expose the shared `OkHttpClient` |
| `app/src/main/java/com/daydreamvr/player/VrActivity.kt` | pass it to `ExoVideoPlayer` |
| `gradle/libs.versions.toml` | two new Media3 coordinates |

### 10.2 F1 — audio codecs

| File | Change |
|---|---|
| `playback/libs/media3-decoder-ffmpeg-<v>.aar` | **new**, checked in |
| `playback/libs/BUILD.md`, `playback/libs/FFMPEG_SOURCE.md` | **new** — reproducibility + LGPL §6 |
| `playback/build.gradle.kts` | `implementation(files("libs/…aar"))` |
| `playback/src/main/java/com/daydreamvr/playback/FfmpegAvailability.kt` | **new** |
| `app/build.gradle.kts` | `abiFilters`, `jniLibs.useLegacyPackaging = false` |
| `app/proguard-rules.pro` | explicit ffmpeg keeps (§9.4) |
| `app/src/main/java/com/daydreamvr/player/debug/DebugOverlay.kt` | show ffmpeg availability + version |
| `.gitattributes` | **new** — `*.aar binary` |
| `app/src/main/res/…` (lobby attribution) | FFmpeg licence notice |

### 10.3 F2 — the compatibility engine

| File | Change |
|---|---|
| `playback/src/main/java/com/daydreamvr/playback/PlaybackEngine.kt` | **new**, pure |
| `playback/src/main/java/com/daydreamvr/playback/EnginePreRoute.kt` | **new**, pure |
| `playback/src/main/java/com/daydreamvr/playback/PlaybackEngineRouter.kt` | **new** |
| `playback/src/main/java/com/daydreamvr/playback/vlc/VlcVideoPlayer.kt` | **new** |
| `playback/src/main/java/com/daydreamvr/playback/vlc/VlcCodecPolicy.kt` | **new**, pure |
| `playback/src/main/java/com/daydreamvr/playback/FallbackPolicy.kt` | `enginesTried` param; `SwitchEngine` action |
| `playback/build.gradle.kts` | `libs.libvlc.all` |
| `app/src/main/java/com/daydreamvr/player/data/SettingsStore.kt` | persist per-item engine preference (`PlaybackEngineStore` impl) |
| `app/src/main/java/com/daydreamvr/player/state/AppState.kt` | `Settings.enableCompatibilityEngine: Boolean = true` |
| `app/src/main/java/com/daydreamvr/player/screens/SettingsScreen.kt` | the toggle |
| `app/src/main/java/com/daydreamvr/player/VrActivity.kt` | construct `PlaybackEngineRouter` instead of `ExoVideoPlayer` |
| `app/src/main/java/com/daydreamvr/player/di/AppContainer.kt` | `PlaybackEngineStore` |
| `app/proguard-rules.pro` | LibVLC keeps (§9.4) |
| `playback/src/main/java/com/daydreamvr/playback/DecoderCapsProvider.kt` | widen `CONTAINERS_BY_VIDEO_MIME` to the union of both engines |

---

## 11. Test strategy and acceptance criteria

House rule (`UI_REDESIGN_REVIEWED_PLAN.md` §14): **red-first**. Each test below states the
defect it must fail against before the fix. A test that passes on first write against the
current tree is testing the wrong thing and must be rewritten.

### 11.1 F0 — JVM unit tests (`:playback`, `:app`)

`PlaybackSourceTest` (new, pure)
1. `PlaybackSource("content://media/external/video/media/42").isContent` is true; `isHttp` false.
2. `PlaybackSource("/storage/emulated/0/a.mkv").isFile` is true (bare path, no scheme).
3. `PlaybackSource("http://h/a.mp4?x=1").isHttp` is true.

`PlaybackSourceAdapterTest` (new, pure)
4. `fromUpnp` preserves order, uri, mime, resolution and bitrate for a 3-resource list.
   *(Fails to compile before `PlaybackSource` exists.)*
5. `fromLocal` yields exactly one source whose uri is the `MediaRef` value verbatim.
6. `:playback` no longer references `com.daydreamvr.upnp` — a reflective/source scan
   mirroring `MediaModelPurityTest`'s technique. *(Fails against `VideoPlayer.kt:4` today.)*

`FallbackPolicyTest` (extended)
7. `UnsupportedContainer` with `enginesTried = {MEDIA3}` → `SwitchEngine(VLC)`.
   *(Fails: the failure type does not exist.)*
8. `UnsupportedContainer` with `enginesTried = {MEDIA3, VLC}` and 1 resource left → `NextResource`.
9. `UnsupportedContainer`, both engines tried, no resources → `GiveUp`.
10. `MalformedContainer` routes identically to `UnsupportedContainer`.
11. `MissingAudioDecoder` never yields `GiveUp` while resources remain — video is still playing.
12. Every pre-existing case still returns its pre-existing action with the default
    `enginesTried`. *(Guards the §8.4 signature change.)*

`EnginePreRouteTest` (new, pure — F2, written in F0 if the taxonomy lands early)
13. `"…/movie.wmv"` → `VLC`; `"…/movie.mkv"` → `MEDIA3`.
14. mime `video/x-ms-asf` with a `.mp4` extension → `VLC` (**mime beats extension**).
15. `"http://h/a.WMV?token=1"` → `VLC` (case-insensitive, query stripped).
16. An extensionless `content://media/external/video/media/42` → `MEDIA3` (never guess from
    an opaque URI). *(Fails against a naive `substringAfterLast('.')` that would see
    `…/media/42` and match nothing predictable.)*

`VlcCodecPolicyTest` (new, pure — F2)
17. `(height=1080, thermal=0)` → contains `avcodec`.
18. `(height=2160, thermal=0)` → `mediacodec_ndk,none`. *(4K software decode is never permitted.)*
19. `(height=720, thermal=3)` → `mediacodec_ndk,none`. *(Thermal veto overrides resolution.)*

### 11.2 F0/F1 — Robolectric / instrumented

`ExoPlayerFactoryTest` — this is M8 acceptance #3 from `UI_REDESIGN_REVIEWED_PLAN.md` §14,
promoted here because it is the direct D1 regression test.

20. The media-source factory resolves `content://media/external/video/media/1` to a
    `MediaSource` without throwing. *(Fails against `DefaultMediaSourceFactory(httpFactory)`
    at `ExoVideoPlayer.kt:163`.)*
21. It resolves `file:///android_asset/…` without throwing.
22. It still resolves `http://…` through the injected `OkHttpDataSource.Factory` — assert
    the OkHttp client is the shared instance, not a fresh one. *(Fails against
    `ExoVideoPlayer.kt:153`.)*

`FfmpegAvailabilityTest` (instrumented, F1)
23. `FfmpegAvailability.isAvailable` is **true** on-device.
    *(Fails before the AAR is added — and, critically, fails again if R8 strips it, which is
    the only automated guard against D4 recurring.)*
24. `version` is non-null and matches the tag in `playback/libs/BUILD.md`.

`PlaybackSmokeTest` (extended — `TESTING.md` §1.2 already lists this class)
25. An MKV/H.264/**AC-3** asset reaches `STATE_READY` **and** `Tracks.isTypeSelected(TRACK_TYPE_AUDIO)`
    is true. *(Fails today: the AC-3 group is `FORMAT_UNSUPPORTED_TYPE`, nothing is selected,
    and no error is raised — D5 exactly.)*
26. An MKV/H.264/**DTS** asset: same assertion. *(F1.)*
27. An `.mp4` with a **damaged `moov`** yields `MalformedContainer`, not `Unknown`.
28. A file with a `.bin` extension containing valid MP4 bytes plays — sniffing, not extension.
29. Without the FFmpeg extension, the AC-3 asset publishes
    `PlaybackFailure.MissingAudioDecoder("ac-3")` on the snapshot while `isPlaying` stays
    true. *(The D5 detector. Run this on an F0-only build to prove the detector works
    independently of F1.)*

### 11.3 F2 — instrumented

`EngineRouterTest`
30. A `.wmv` asset plays end-to-end; `snapshot.engine == VLC`.
31. An `.mp4` asset plays; `snapshot.engine == MEDIA3`; the VLC `LibVLC` instance was never
    constructed. *(Asserts the lazy factory — loading `libvlcjni.so` on every launch is a
    ~100 ms cold-start regression for nothing.)*
32. After a successful VLC playback, `PlaybackEngineStore.preferred(key) == VLC`, and the
    next `play()` for that key goes straight to VLC with no Media3 attempt.
33. With `enableCompatibilityEngine = false`, the `.wmv` asset fails with
    `UnsupportedContainer` and **no** engine switch.
34. Failover happens at most once: a file that fails on both engines produces exactly two
    opens and one `GiveUp`. *(Fails against a router that re-evaluates the policy on the
    second engine's error.)*

`SurfaceLifecycleTest` — the §8.6 crash guard
35. 50 cycles of `attach → play 500 ms → detach → VideoTexture.release() → recreate` on the
    VLC engine, with no native crash and no leaked `MediaCodec` instance.
36. Engine switch mid-playback: Media3 playing → `SwitchEngine(VLC)` → VLC playing on the
    **same** `Surface`, with `glGetError() == GL_NO_ERROR` afterwards.

`FramePacingTest` — the §5.4 regression guard
37. 600 steady-state frames of 1080p60 on the **Media3** engine: `FrameStats.p95` within
    15 % of the display's `Display.Mode` interval. *(This is `TESTING.md`'s existing
    `RefreshRateTest` bar; F2 must not move it.)*
38. The same measurement on the **VLC** engine, recorded as a **baseline, not a gate** — we
    expect it to be worse. Record the number in the release notes so the trade is
    documented rather than discovered.

### 11.4 Device capability probe (diagnostic, not a gate)

`DecoderProbeTest` (instrumented) — writes the actual `MediaCodecList` audio/video MIME set
of the handset to the test report. §2.7's tables are stated from general Android knowledge;
this replaces them with measurement on the specific Pixel 11 Pro under test. Run it once and
paste the output into §14.

### 11.5 Manual matrix additions

Extend `TESTING.md` §2.1's Content axis:

| Added content | Expectation after |
|---|---|
| **MKV H.264 + AC-3 5.1** | F1 — audio present |
| **MKV H.265 + DTS-HD MA** | F1 — audio present (core or HD) |
| **MKV + TrueHD** | F1 — audio present |
| **WMV / VC-1 1080p** | F2 — plays via VLC |
| **AVI / Xvid (ASP, qpel)** | F2 — plays via VLC |
| **.rmvb** | F2 — plays via VLC |
| **Truncated MP4 (no `moov`)** | F2 — plays via VLC; F0 — clean "damaged file" message |
| **4K HEVC on the phone's own storage** | F0 — plays at all; F1/F2 — no regression |
| **4K VC-1** | F2 — fails cleanly with "too demanding for this phone", **and the render loop stays at rate** (§9.3) |

### 11.6 Definition of done

**F0.** `./gradlew check` green; `assembleRelease` green; tests 1–12, 20–22, 27–29 pass; on
the handset, a `content://` file from `MediaStore` plays, seeks and resumes; every failure
mode shows a human sentence in the headset, never an `ERROR_CODE_` string.

**F1.** Test 23 passes on-device **and** on an R8 release build; tests 25, 26 pass; the
`strings | grep FfmpegAudioRenderer` CI check is green; the MKV/AC-3 row of the manual
matrix has audio; `ThermalGovernor` reports no status change vs the F0 baseline over a
20-minute 1080p playback (proves D6 is fixed — with the inversion still present this
regresses measurably).

**F2.** Tests 30–36 pass; test 37 still meets the F0 number; the manual matrix' four new
rows pass; toggling `enableCompatibilityEngine` off cleanly reverts to F1 behaviour with no
crash and no `libvlcjni.so` load.

---

## 12. Milestones

Each is one commit, red-first, ending on `./gradlew check` fully green.

| # | Milestone | Contents | Acceptance |
|---|---|---|---|
| **FS0** | Error taxonomy | `PlaybackFailure` additions, `FallbackPolicy` arms, `enginesTried` with a default. No behaviour change to the happy path. | 7–12 |
| **FS1** | Playback source seam | `PlaybackSource`, `PlaybackSourceAdapter`, `PlayRequest.sources`, drop `:playback → :upnp` | 1–6 |
| **FS2** | **The D1 fix** | `DefaultDataSource.Factory`, `DefaultExtractorsFactory`, shared `OkHttpClient`, renderer mode `..._ON` | 20–22 |
| **FS3** | Local play path | `Effect.PlayNode`, `EffectRunner` branch, `AppStateMachine` wiring | on-device local playback |
| **FS4** | Silent-audio detection | D5 detector, `MissingAudioDecoder` surfacing in the HUD | 29 |
| **FS5** | FFmpeg bake | AAR + `BUILD.md` + `FFMPEG_SOURCE.md` + `.gitattributes` + `abiFilters` + keep rules + `FfmpegAvailability` + debug overlay | 23, 24, and the `strings` check |
| **FS6** | FFmpeg verification | MKV/AC-3, MKV/DTS, MKV/TrueHD assets in `androidTest`; 20-minute thermal soak | 25, 26 |
| **FS7** | Engine seam (pure) | `PlaybackEngine`, `EnginePreRoute`, `VlcCodecPolicy`, `PlaybackEngineStore` interface. **No LibVLC dependency yet** — pure Kotlin, JVM-tested. | 13–19 |
| **FS8** | `VlcVideoPlayer` | the implementation, `libvlc-all`, keep rules, surface handshake | 35 |
| **FS9** | Router | `PlaybackEngineRouter`, `SwitchEngine` execution, sticky store, settings toggle | 30–34, 36 |
| **FS10** | Release | frame-pacing baselines, manual matrix, release notes recording the VLC-path pacing number | 37, 38, §11.5 |

FS0–FS4 are F0. FS5–FS6 are F1. FS7–FS10 are F2.

FS7 landing before FS8 is deliberate and load-bearing: every routing decision becomes a
green JVM test *before* a 30 MB native dependency and a second frame-pacing path enter the
build. If F2 is later cancelled, FS7 is ~200 lines of dead-but-harmless pure Kotlin, not a
half-integrated runtime.

---

## 13. Risks, and what to do about them

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| The FFmpeg bake is not reproducible six months on | high | medium | `BUILD.md` records NDK version, media3 tag, FFmpeg SHA and the decoder array verbatim. CI job rebuilds and diffs the exported symbol table, not the bytes. |
| R8 silently strips `FfmpegAudioRenderer` after a future keep-rule cleanup | medium | **high** — reintroduces D4 with no signal | Test 23 runs on a release build; the `strings` grep is a CI gate (§9.6) |
| Media3 upgrade desynchronises from the baked AAR | medium | high — JNI ABI mismatch, native crash | Pin both from the same `[versions] media3`. A Gradle assertion comparing the AAR filename to the version is cheap and worth adding. |
| LibVLC native crash on surface teardown | medium | high | §8.6 handshake + `SurfaceLifecycleTest` 50-cycle soak. Ship F2 behind the settings toggle so field disablement is one setting, not a release. |
| VLC's pacing is worse than expected even on the fallback path | medium | low | It only affects files that otherwise would not play at all. Measured and documented, not gated (test 38). |
| APK grows past a distribution threshold | low | medium | `abiFilters("arm64-v8a")`; measure before committing to F2 |
| Dolby / DTS patent licensing | low probability, high consequence | business | Decide before F1 ships commercially (§4.6). Engineering can proceed; distribution is the gate. |
| Two engines double the bug surface | certain | medium | The router is the only place they meet, and its decision logic is pure and fully unit-tested (FS7) |
| F0's `PlayRequest` change collides with M4's strangler | high | low | §8.2's sequencing note — take the interim `Effect.PlayLocal` if M4 has not landed |

**The scope cut, stated explicitly.** If F2 must be dropped: ship F0 + F1, and change
`PlaybackFailure.UnsupportedContainer.userMessage` to name the format and say Arc cannot
play it. Users with WMV libraries will be unhappy, and they will be unhappy with an accurate
message instead of a leaked `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED` string. That is a
defensible v1.

---

## 14. Appendix — device capability record

Populate from `DecoderProbeTest` (§11.4) on the actual handset and keep it here. Until then,
§2.7's tables are general Android knowledge, not measurement, and are marked **[verify]**.

```
Device:            <ro.product.model>
Android:           <ro.build.version.release> / API <SDK_INT>
Media3:            <libs.versions.toml media3>
FFmpeg extension:  <FfmpegAvailability.version or "absent">
LibVLC:            <libs.versions.toml libvlc or "absent">

Video decoders (MediaCodecList.REGULAR_CODECS, decoders only):
  <mime>  <codec name>  <max WxH>  <hw|sw>
  …

Audio decoders:
  <mime>  <codec name>  <hw|sw>
  …
```

## 15. Appendix — glossary of the failure codes referenced

| Code | Layer | Meaning in this document |
|---|---|---|
| `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED` | extractor | no demuxer recognised the bytes → `UnsupportedContainer` → route to VLC |
| `ERROR_CODE_PARSING_CONTAINER_MALFORMED` | extractor | recognised but damaged → `MalformedContainer` → route to VLC |
| `ERROR_CODE_DECODING_FORMAT_UNSUPPORTED` | renderer | container fine, no decoder for the sample MIME → `UnsupportedVideoCodec` |
| `ERROR_CODE_DECODER_INIT_FAILED` | MediaCodec | decoder exists but would not start (usually resolution/profile) |
| `ERROR_CODE_AUDIO_TRACK_INIT_FAILED` | AudioTrack | output path failed, not the decoder |
| *(no code)* | track selector | audio present, none selected → `MissingAudioDecoder`. **Silence, not an error** — D5 |
| `FORMAT_UNSUPPORTED_TYPE` | track selector | the flag behind D5; never selected under any parameters |
| `FORMAT_EXCEEDS_CAPABILITIES` | track selector | selectable under `exceedRendererCapabilitiesIfNecessary`; distinct from the above |
