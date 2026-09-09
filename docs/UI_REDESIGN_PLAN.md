# Arc VR Player — PLAY'A-Inspired UI Redesign & Local File System Architecture

**Status:** Authoritative Implementation Specification, v1.0  
**Target Execution Agent:** Claude Sonnet 5 via `claude -c -p ...`  
**Companion Documents:** [ARCHITECTURE.md](ARCHITECTURE.md), [UI_GAZE_PLAN.md](UI_GAZE_PLAN.md), [UI_REDESIGN_PLAYA_BACKLOG.md](UI_REDESIGN_PLAYA_BACKLOG.md)  
**Baseline Commit:** `master` @ `a4f9947` (v0.2.5 verified playing UPnP video on Pixel 11 Pro)

---

## 1. Executive Summary & Objectives

This specification defines the complete overhaul of the in-headset Arc VR media browsing and selection experience, modeled after the proven, ergonomic UX patterns of **PLAY'A VR**, **Skybox VR**, and **Meta Quest Gallery**.

### Core Deliverables
1. **Local Storage & File System Support:** First-class browsing and playback of on-device local videos alongside UPnP/DLNA servers via Android `MediaStore`.
2. **Three-Column Stereoscopic Curved Browser:**
   - **Left Panel (Sources & Hierarchy, ~20° FOV):** Source switcher (Local Storage vs UPnP Servers vs Favorites), top-level directory buckets, and folder navigation.
   - **Center Panel (Media Explorer & Grid, ~32° FOV):** Dynamic multi-column video card grid (2×3 or 3×3) with asynchronous thumbnail loading, duration badges, format tags (`VR 180`, `3D SBS`, `4K`, `MP4`), breadcrumbs, sorting, and pagination.
   - **Right Panel (Media Inspector, ~18° FOV):** High-resolution poster preview, video/audio stream technical specs, auto-detected projection format override, and direct *Play* / *Resume* actions.
3. **Bottom Floating System Dock (~15° below eye line):** Detached curved control capsule for quick recentering, optics/settings access, aspect/curve calibration, and exit to lobby.
4. **Spatial Grounding & Comfort (OLED Void + Grid Floor):** True OLED black void (#000000) with a perspective wireframe ground plane at $y = -1.2\text{m}$ and subtle horizon glow to anchor vestibular balance and eliminate VR disorientation.

---

## 2. Mathematical & Ergonomic Layout Geometry

All panels sit on a shared cylindrical surface matching the user's focal distance ($R = D = 2.30\text{m}$), ensuring constant angular scale, zero vergence-accommodation strain, and zero distortion across head rotation.

```
                  [Head / Eye Center (0, 0, 0)]
                               |
                   R = 2.30m  / \   Azimuth Span: ~70°
                             /   \
                            /     \
       +-------------------/---+---\-------------------+
       |     LEFT PANEL    |   CENTER PANEL   |  RIGHT |
       | Sources & Folders |    Media Grid    | Inspect|
       |     (~20° FOV)    |    (~32° FOV)    | (~18°) |
       +-----------------------+--------------+--------+
                               |
                      [BOTTOM FLOATING DOCK]
                     (y = -0.65m, D = 2.05m)
```

### 2.1 Angular Subtense & Physical Dimensions
* **Total Horizontal Field of View:** $\theta_{\text{total}} = 70.0^\circ = 1.2217\text{ rad}$.
* **Total Arc Width:** $W_{\text{arc}} = R \times \theta_{\text{total}} = 2.30 \times 1.2217 = 2.81\text{ meters}$.
* **Panel Height:** $H = 1.45\text{ meters}$ ($\approx 36.1^\circ$ vertical FOV).
* **Texture Canvas Resolution:** $1920 \times 1080\text{ px}$ (gives an ultra-crisp $27.4\text{ px/deg}$, matching Pixel 11 Pro display density).

### 2.2 Column Distribution
* **Left Column (Sources & Tree):**
  * Width: $0.28 \times W_{\text{arc}} \approx 538\text{ px}$ ($19.6^\circ$).
  * Azimuth Center: $-25.2^\circ$ relative to forward gaze.
* **Center Column (Media Grid & Breadcrumbs):**
  * Width: $0.46 \times W_{\text{arc}} \approx 883\text{ px}$ ($32.2^\circ$).
  * Azimuth Center: $+1.5^\circ$ (slight right offset to center the primary grid in dominant field).
* **Right Column (Media Inspector & Action Deck):**
  * Width: $0.26 \times W_{\text{arc}} \approx 499\text{ px}$ ($18.2^\circ$).
  * Azimuth Center: $+25.9^\circ$.
* **Inter-Column Gutters:** $20\text{ px}$ translucent separator boundaries with glowing vertical hairline borders.

---

## 3. Data Architecture: Unified Media Abstraction

To support Local Storage alongside UPnP servers seamlessly without duplicating UI logic, introduce a unified domain hierarchy in `:app`:

```kotlin
sealed interface MediaSource {
    data class Upnp(val server: MediaServer) : MediaSource
    data object Local : MediaSource
}

sealed interface MediaNode {
    val id: String
    val title: String
    val parentId: String?

    data class Folder(
        override val id: String,
        override val title: String,
        override val parentId: String? = null,
        val childCount: Int? = null,
        val icon: Icon = Icon.FOLDER,
    ) : MediaNode

    data class Video(
        override val id: String,
        override val title: String,
        override val parentId: String? = null,
        val uri: Uri,
        val durationMs: Long? = null,
        val sizeBytes: Long? = null,
        val resolution: Resolution? = null,
        val mimeType: String? = null,
        val dateModified: Long? = null,
        val projectionMode: ProjectionMode = ProjectionMode.FLAT,
        val thumbnailUri: Uri? = null,
        val thumbnailBitmap: Bitmap? = null,
    ) : MediaNode
}
```

### 3.1 Android `MediaStore` Local Video Engine
* **Permissions Required:**
  * Android 13+ (API 33+): `<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />`
  * Android <= 12: `<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />`
* **Local Repository Query:**
  Query `MediaStore.Video.Media.EXTERNAL_CONTENT_URI` with projection:
  `_ID`, `DISPLAY_NAME`, `TITLE`, `DURATION`, `SIZE`, `WIDTH`, `HEIGHT`, `MIME_TYPE`, `BUCKET_DISPLAY_NAME`, `DATE_MODIFIED`.
* **Virtual Folder Hierarchy:**
  * Top-level local containers derived from unique `BUCKET_DISPLAY_NAME` values (`Camera`, `Movies`, `Download`, `Oculus`, `VR`, etc.).
  * Selecting a bucket displays its videos in the Center Grid.
* **Thumbnail Extraction Pipeline:**
  * Native hardware-accelerated thumbnail generation:
    `context.contentResolver.loadThumbnail(contentUri, Size(320, 240), null)` on API 29+.
  * Store in an in-memory LRU cache (`LruCache<String, Bitmap>(maxSize = 64)`).

---

## 4. UI Component Hierarchy & Visual Design

### 4.1 Left Panel — Source Selector & Folder Sidebar
* **Top Header:** Tabbed Source Switcher:
  * `[ Device Storage ]` | `[ Network (UPnP) ]` | `[ Favorites ]`
* **Folder List:**
  * Displays top-level directories / UPnP containers.
  * Shows item count pill on trailing edge.
  * Focused folder displays glowing cyan elevation border (`0xFF00E5FF`).

### 4.2 Center Panel — Media Explorer & Grid
* **Breadcrumb Header:**
  * Navigable pill badges (`Storage` > `Movies` > `SciFi`).
  * Gaze-click or Gamepad bumper jumps back to parent levels.
* **Sort & Filter Bar:**
  * Dropdown/cycle pills: `Sort: Date ▾`, `Filter: All ▾`.
* **Media Card Grid (2×3 or 3×3):**
  * Aspect Ratio: 16:9 widescreen video card.
  * Preview Thumbnail: Scaled bitmap or fallback gradient placeholder with video icon.
  * Format Tag Badges: Small glassmorphic pills in top corners:
    * Top-Left: Projection format (`VR180`, `VR360`, `3D SBS`, `3D TB`).
    * Top-Right: Quality / Codec (`4K`, `HEVC`, `MP4`).
  * Bottom Overlay: Duration pill (`01:24:50`) on dark semi-transparent scrim.
  * Title: Typographically measured font-metrics two-line label below card.
* **Pagination Footer:**
  * `< Prev (L1)` | `Page 1 of 4` | `Next > (R1)`

### 4.3 Right Panel — Media Inspector & Action Deck
* **Large Preview Poster:** 16:9 high-res keyframe preview.
* **Metadata Card:**
  * Title, file size (MB/GB), date modified.
  * Video stream: Resolution (`3840×2160`), FPS (`60 fps`), Codec (`HEVC`).
  * Audio stream: Codec (`AAC`, `AC3`), Channels (`Stereo`, `5.1`).
* **Projection Mode Override:**
  * Cycle toggle: `[ Auto: VR 180 ]` -> `[ 3D SBS ]` -> `[ 2D Cinema ]` -> `[ 360 Sphere ]`.
* **Action Buttons:**
  * **PLAY VIDEO** (Accented primary button with neon cyan glow).
  * **RESUME from MM:SS** (if previously watched).

### 4.4 Bottom Floating System Dock
* Detached capsule at $y = -0.65\text{m}$, distance $2.05\text{m}$.
* Quick icons:
  * 🔄 **Recenter View** (immediate yaw snap)
  * ⚙️ **Settings & Optics** (IPD, distortion, viewer profile)
  * 🖥️ **Screen Curvature & Distance**
  * 🚪 **Exit to 2D Lobby**

---

## 5. Implementation Milestones

### Milestone 1: Local Storage Engine & Permissions
* Add `READ_MEDIA_VIDEO` and `READ_EXTERNAL_STORAGE` to `AndroidManifest.xml`.
* Implement `LocalMediaRepository` with `MediaStore` queries, bucket aggregation, and thumbnail caching.
* Unit tests in `:app`: `LocalMediaRepositoryTest` verifying bucket grouping and URI resolution.

### Milestone 2: Domain Model & State Machine Unification
* Refactor `BrowseState` and `AppStateMachine` to operate over `MediaSource` and `MediaNode`.
* Add events: `SourceSelected`, `SortOrderChanged`, `PageChanged`, `ProjectionModeOverridden`.
* Unit tests: Reducer tests for local browsing, sorting, pagination, and effect emission.

### Milestone 3: 3-Column UI Layout & Widgets
* Implement `SourceSidebarWidget`, `MediaGridWidget`, `MediaInspectorWidget`, and `SystemDockWidget`.
* Integrate into `BrowseScreen` (1920×1080 canvas) with font-metrics layouts and hit-region mapping.
* Wire gaze reticle hit detection for grid cards, pagination buttons, and inspector actions.

### Milestone 4: Spatial Environment & Ground Grid
* Implement wireframe perspective ground plane at $y = -1.2\text{m}$ in `AppScene` with horizon fade.
* Build and verify signed release APK, verify 100% test passage.
