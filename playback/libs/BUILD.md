# Media3 FFmpeg Decoder Build Record

This document records the exact inputs, toolchain, and command used to produce
`playback/libs/media3-decoder-ffmpeg-1.4.1.aar`.

## Inputs and Versions
- **Media3 Tag**: `1.4.1` (commit `c35a9d62baec57118ea898e271ac66819399649b`)
  - Source: `https://github.com/androidx/media.git`
- **FFmpeg Branch**: `release/6.0` (commit `ba69be84a1ceabfb39127831ad8da0fd7cb471f3`)
  - Source: `https://github.com/FFmpeg/FFmpeg.git`
- **Android NDK**: `26.1.10909125` (r26b)
- **Host Platform**: `linux-x86_64`
- **Android Native ABI Level**: `21` (matches Media3 minimum native API)
- **Architectures Built**: `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`
- **Java**: Temurin OpenJDK 17.0.14+7
- **Gradle**: 8.4 (via wrapper in androidx/media)

## Decoders Enabled (LGPL only)
```bash
ENABLED_DECODERS=(
  ac3 eac3            # Dolby Digital / Digital Plus
  dca                 # DTS core + HD extensions
  mlp truehd          # Dolby TrueHD
  alac                # Apple Lossless
  vorbis opus flac    # Complete audio format coverage
  mp3 aac             # Fallback decoders
  amrnb amrwb
  pcm_s16le pcm_s24le pcm_s32le pcm_f32le pcm_mulaw pcm_alaw
)
```

No `--enable-gpl` or `--enable-nonfree` flags were used. The build is strictly LGPL v2.1+.

## Output Artifact
- **File**: `playback/libs/media3-decoder-ffmpeg-1.4.1.aar`
- **SHA-256**: `cfeb55a1d87c13a1fccfd2f166f9fd631e48f8032c2fb4823538b176e990aa11`
- **Classes**: `androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer`, `FfmpegLibrary`, `FfmpegAudioDecoder`
- **Native JNI Libraries**: `libffmpegJNI.so` present for all 4 ABIs.
