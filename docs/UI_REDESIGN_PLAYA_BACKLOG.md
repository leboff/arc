# Backlog: Arc VR Player UI Redesign (PLAY'A VR Architecture)

## Status: Queued in Backlog
**Target Trigger:** Pick up once the UPnP library migration (jUPnP) lands and browsing/playback are verified end-to-end.
**Todoist Task:** `6hRrXXHJ7QFCp2VW` under *Someday/Maybe*.

---

## 1. Vision & Visual Aesthetic
Transition from the current minimalist, flat 2D canvas panels to a rich, immersive VR media browser inspired by **PLAY'A VR**:

- **Environment & Theme:**
  - Dark OLED space-black void with a subtle magenta/neon pink glow along the horizon line.
  - Subtle perspective wireframe grid on the floor plane to anchor spatial grounding and prevent simulator disorientation.
  - Glassmorphic panels with subtle glowing borders on hover/focus.
- **Curvature & Comfort:**
  - 3-panel curved layout subtending an ergonomic ~70° field of view at 2.5–3.0m virtual depth, matching natural head rotation without neck strain.

---

## 2. Three-Panel Stereoscopic Architecture

### A. Left Panel — Source & Folder Navigation Sidebar
- **Header:** Quick-switch / Source profile (UPnP Servers, Local Storage, Bookmarks/Favorites).
- **Navigation Tree:**
  - Top-level server roots (e.g. Gerbera: `PC Directory`, `Video`, `Photos`).
  - Quick-access virtual folders: `Recent`, `Favorites`, `Playlists`, `Pinned`.
  - Icon-backed category items with item counts.

### B. Center Panel — Media Grid & Explorer
- **Top Toolbar:**
  - Path breadcrumbs with clickable / navigable pill badges (`Gerbera` > `Video` > `VR 180`).
  - Sorting dropdown: `Name ⌄`, `Date ⌄`, `Duration ⌄`, `Size ⌄`.
  - Search trigger & refresh action.
- **Media Grid:**
  - Dynamic 3×3 or 4×3 thumbnail card layout.
  - Cards display:
    - High-res video thumbnail (cached asynchronously via OkHttp / Glide / Coil from DLNA `albumArtURI` or local keyframe extraction).
    - Format badge (e.g. `MP4`, `MKV`, `SBS`, `360`, `180`).
    - Video duration pill (`HH:MM:SS`) overlaid on thumbnail.
    - Title truncated cleanly with font metrics.
- **Pagination & Navigation:**
  - Floating footer with `< Prev`, page indicators (`1`, `2`, `3`), and `Next >`.
  - Smooth page navigation via gamepad bumpers (`L1` / `R1`) or gaze click.

### C. Right Panel — Media Inspector & Detail Card
- **Large Preview Header:** Generous poster / preview image of the currently focused or hovered media item.
- **Metadata Breakdown:**
  - Video stream: Codec (e.g. `HEVC / H.265`, `AVC / H.264`), Resolution (`3840×2160`, `1920×1080`), Framerate (`60 fps`).
  - Projection mode: `2D Flat`, `3D SBS`, `Over-Under`, `VR180`, `VR360`.
  - Audio stream: Channels (`5.1 Surround`, `Stereo`), Codec (`AC3`, `AAC`, `DTS`).
  - File metadata: Size in GB/MB, Date modified / added.
- **Primary Actions:**
  - **Play** (or **Resume at MM:SS**)
  - **Play in Mode...** (override projection mode directly before launch)
  - **Add to Playlist / Queue**

---

## 3. Bottom Floating System Dock
A detached, curved floating pill docked below the main browser:
- **Quick Controls:**
  - Power / Standby / Exit VR
  - Settings (IPD, Lens distortion, Head tracking calibration)
  - Screen Framing & Aspect Ratio
  - Projection Mode Quick Toggle
  - Re-center View (`Y` button shortcut)
  - Night Mode / Void Brightness dimmer

---

## 4. Technical Migration Path
- **Renderer Evolution:** Replace raw `Canvas.drawRect / drawText` pixel math with a modular component hierarchy or an offscreen Android View/Compose-to-Texture pipeline rendered onto curved 3D OpenGL quads.
- **Thumbnail Pipeline:** Integrate an asynchronous image loader with memory/disk caching for UPnP album art and local video thumbnails.
