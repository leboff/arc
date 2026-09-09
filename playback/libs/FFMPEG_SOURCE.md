# FFmpeg Licensing & Source Distribution Notice

This software includes binary libraries from the FFmpeg project, licensed under the GNU Lesser General Public License (LGPL) version 2.1 or later.

## FFmpeg Source Access
In compliance with LGPL v2.1 §6:
- The FFmpeg source code corresponding to the compiled binaries is available from the official FFmpeg git repository:
  `https://git.ffmpeg.org/ffmpeg.git` (branch `release/6.0`, commit `ba69be84a1ceabfb39127831ad8da0fd7cb471f3`)
- Mirror: `https://github.com/FFmpeg/FFmpeg.git`
- AndroidX Media3 FFmpeg JNI wrapper source:
  `https://github.com/androidx/media.git` (tag `1.4.1`, commit `c35a9d62baec57118ea898e271ac66819399649b`)

## Relinking Instructions
`libffmpegJNI.so` is packaged as an uncompressed shared object dynamically loaded by Android via `System.loadLibrary`. Users wishing to modify or relink FFmpeg can:
1. Check out the specified commits.
2. Modify the C source code or decoder configurations.
3. Follow the build instructions in `playback/libs/BUILD.md`.
4. Replace `media3-decoder-ffmpeg-1.4.1.aar` or replace `libffmpegJNI.so` directly in the uncompressed APK structure and re-sign using standard Android signing tools (`apksigner`).
