# Prompt: Claude Sonnet — Milestone 5 (3-Column UI Widgets & System Dock)

You are Claude Sonnet, the Principal Implementation Engineer on Arc VR Player.
Your task is to implement Milestone 5 of `docs/UI_REDESIGN_REVIEWED_PLAN.md` (Widgets: `SourceSidebarWidget`, `MediaGridWidget`, `MediaInspectorWidget`, `SystemDockWidget` + Acceptance Tests).

## Invariants & Architecture
1. **Purity of layout:** Every widget takes `(theme: Theme, metrics: PanelMetrics, measure: TextMeasure = TextMeasure.PAINT)` and implements:
   ```kotlin
   fun measureLayout(model: <Model>, bounds: RectF): <Layout>
   fun draw(canvas: Canvas, model: <Model>, layout: <Layout>, focus: BrowseFocus?, hover: GazeTarget?, thumbs: ThumbnailCache? = null)
   fun hitRegions(layout: <Layout>): List<HitRegion<GazeTarget>>
   ```
   `measureLayout` must be pure (no Canvas, no Paint). All text sizing and bounds must resolve through `measure` and `metrics`.
2. **Package placement:**
   - Widgets: `app/src/main/java/com/daydreamvr/player/screens/widgets/`
   - Tests: `app/src/test/java/com/daydreamvr/player/screens/widgets/`
3. **No regressions:**
   Existing tests (`:app`, `:vrcore`, `:playback`, `:upnp`) must stay 100% green.

---

## Detailed Widget Specifications (from docs/UI_REDESIGN_REVIEWED_PLAN.md §5)

### 1. `SourceSidebarWidget` (Left column, bounds: x ∈ [0, 394], y ∈ [0, 800])
- Header: y ∈ [0, 75.04) "SOURCES"
- 3 Source switcher rows (`GazeTarget.SourceTab(0..2)`):
  - 0: Device (`Icon.FOLDER` or device icon)
  - 1: Network (`Icon.SERVER`)
  - 2: Favourites (`Icon.STAR`)
  - Each row height: `65.35 px` (`Type.chip`, 2.730° tall)
- Divider at `y = 271.09`
- Folder list starting at `y = 284.45`:
  - Each folder row: height `75.57 px` (`Type.rowTitle`, 3.157° tall)
  - Publishes `GazeTarget.SidebarRow(index)`
  - `visibleRows()` returns the exact integer number of folder rows that fit in bounds `[284.45, 800)`.

### 2. `MediaGridWidget` (Center column, bounds: x ∈ [418, 1118], y ∈ [0, 800])
- Grid band: y ∈ `[140.39, 719.10)` (height 578.71 px)
- 3 columns × 2 rows = 6 cards per page:
  - `padH = px(Space.L) = 15.56 px`
  - `gapH = px(Space.M) = 10.77 px`
  - `cardW = (700 - 2 * padH - 2 * gapH) / 3 = 215.78 px` (within 0.5 px)
  - `posterH = cardW * 9 / 16 = 121.38 px` (16:9 within 0.5%)
  - `titleH = 2 lines * px(1.55°) * 1.18 = 87.56 px`
  - `metaH = 1 line * px(1.55°) * 1.18 = 43.78 px`
  - `cardH = posterH + px(Space.S) + titleH + metaH = 259.89 px`
- Each card box contains:
  - Poster (bitmap from `thumbs?.peek(node.thumbnailKey)` centre-cropped, or `cardFill` gradient placeholder)
  - Badges (VR180/3D SBS, quality 4K/1080p, duration pill bottom-right)
  - Watched progress bar if partially watched
  - Title (2 lines ellipsised)
  - Meta line (e.g. resolution & size)
- **Hit region:** The FULL card box `(left, top, right, bottom)` of size `cardW × cardH` (`9.015° × 10.858°`), NOT just the poster!
- Published target: `GazeTarget.GridCell(absoluteIndex)` where `absoluteIndex = page * 6 + slot`.

### 3. `MediaInspectorWidget` (Right column, bounds: x ∈ [1142, 1536], y ∈ [0, 800])
- Width `394 - 2 * 15.56 = 362.88 px`
- Content band `[75.04, 784.45)` = 709.4 px
- Top-down information:
  - Poster frame: `0.80 * 362.88 = 290.30 px` wide, 16:9 -> `163.29 px` tall
  - Title: up to 2 lines ellipsised
  - Technical metadata rows (Resolution, Duration, Codec, Size, Frame rate, etc.)
- Bottom-anchored actions (always drawn upward from `y = 784.45`, never pushed off screen by metadata):
  - `[ PLAY ]`: `75.57 px` tall (`GazeTarget.InspectorAction(Action.PLAY)`)
  - `[ RESUME hh:mm:ss ]`: `75.57 px` tall (`GazeTarget.InspectorAction(Action.RESUME)`) — drawn ONLY when resume position exists. When absent, PLAY moves down.
  - `[ Projection: ... ]`: `65.35 px` tall (`GazeTarget.InspectorAction(Action.PROJECTION)`)
- If metadata rows would collide with the action block, metadata rows are clipped/truncated.

### 4. `SystemDockWidget` (Detached curved surface, bounds: 672 × 176 px)
- From `DockLayout`: 6 cells tiling `[0, 672)` exactly.
- Each cell width = `672 / 6 = 112 px = 4.658°`, height = `176 px = 7.320°`.
- Slots (0..5):
  - 0: `RECENTER` (`GazeTarget.DockButton(Dock.RECENTER)`)
  - 1: `SETTINGS` (`GazeTarget.DockButton(Dock.SETTINGS)`)
  - 2: `CALIBRATE` (`GazeTarget.DockButton(Dock.CALIBRATE)`)
  - 3: `VIEW_MODE` (`GazeTarget.DockButton(Dock.VIEW_MODE)`)
  - 4: `RESCAN` (`GazeTarget.DockButton(Dock.RESCAN)`)
  - 5: `EXIT` (`GazeTarget.DockButton(Dock.EXIT)`)
- Each cell publishes its `HitRegion<GazeTarget>`.

---

## Acceptance Tests Required (M5)
In `app/src/test/java/com/daydreamvr/player/screens/widgets/`:
1. **`MediaGridLayoutTest.kt`**:
   - 6 cells measured.
   - `cardW` is within 0.5 px of `215.78`.
   - Poster aspect ratio is 16:9 within 0.5%.
   - The 6 card boxes are pairwise non-overlapping and all strictly inside `[140.39, 719.10)`.
   - Hit regions span the **full card** (`box.bottom - box.top == cardH ± 0.5 px`).
   - Absolute index: on page 1, slot 0 publishes `GridCell(6)`.
2. **`HitTargetSizeTest.kt`**:
   - Across all four widgets with a populated model, every single `HitRegion` satisfies `min(deg(w), deg(h)) >= 2.0°`.
3. **`SourceSidebarLayoutTest.kt`**:
   - 3 source rows, divider, folder rows.
   - `visibleRows()` matches the number of boxes actually emitted.
4. **`MediaInspectorLayoutTest.kt`**:
   - With 40 metadata rows, PLAY and PROJECTION are **still present** and within panel bounds (metadata clipped).
   - With resume entry: RESUME button present; without: RESUME absent and PLAY takes bottom action slot.
5. **`SystemDockLayoutTest.kt`**:
   - 6 cells tile `[0, 672)` exactly.
   - Each cell visual angle is `4.658° × 7.320°` ± 0.01°.
6. Run `./gradlew testDebugUnitTest --no-daemon` and confirm 100% green!
