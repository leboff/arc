# Prompt: Claude Sonnet — Milestone 8 (Integration & Release)

You are Claude Sonnet, the Principal Implementation Engineer on Arc VR Player.
Your task is to implement Milestone 8 of `docs/UI_REDESIGN_REVIEWED_PLAN.md` (Integration & Release).
Milestones M4 (State Machine) and M5 (3-Column UI Widgets) are ALREADY complete and merged into master.
Now wire the widgets into `BrowseScreen.kt`, create `SystemDockScreen.kt`, wire both into `AppScene.kt` and `VrActivity.kt`, and retire the legacy 1-column list view.

## Detailed Requirements from `docs/UI_REDESIGN_REVIEWED_PLAN.md` (§11, §3, §14):

### 1. Create `app/src/main/java/com/daydreamvr/player/screens/SystemDockScreen.kt`
- Implements `ScreenPanel`:
  ```kotlin
  class SystemDockScreen(panel: PanelSurface, theme: Theme) : ScreenPanel(
      panel, theme,
      panelWidthM = DockLayout.WIDTH_M,          // 1.00f
      panelHeightM = DockLayout.HEIGHT_M,        // 0.261905f
      distanceM = DockLayout.RADIUS_M,           // 2.05f
  ) {
      override val verticalOffsetM = DockLayout.VERTICAL_OFFSET_M // -0.912719f
      init {
          anchor.followThresholdDeg = BrowseLayout.FOLLOW_THRESHOLD_DEG
      }

      private val dockWidget = SystemDockWidget(theme, metrics)

      fun render(state: AppState) {
          if (state.screen != VrScreen.BROWSE) return
          val bounds = RectF(0f, 0f, panel.widthPx.toFloat(), panel.heightPx.toFloat())
          val key = listOf(state.screen, state.browse.top?.focus, state.gaze)
          renderIfChanged(key) { canvas ->
              canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
              val layout = dockWidget.measureLayout(Unit, bounds)
              dockWidget.draw(canvas, Unit, layout, state.browse.top?.focus, state.gaze)
              hitMap = HitMap(dockWidget.hitRegions(layout))
          }
      }
  }
  ```

### 2. Rewrite `app/src/main/java/com/daydreamvr/player/screens/BrowseScreen.kt`
- Replace the legacy single-column list / breadcrumb / detail-card code completely with the 3-column composition:
  ```kotlin
  class BrowseScreen(panel: PanelSurface, theme: Theme) : ScreenPanel(
      panel, theme,
      panelWidthM = BrowseLayout.WIDTH_M,        // 2.80f
      panelHeightM = BrowseLayout.HEIGHT_M,      // 1.458333f
      distanceM = BrowseLayout.RADIUS_M,         // 2.50f
  ) {
      override val verticalOffsetM = BrowseLayout.VERTICAL_OFFSET_M // -0.087302f
      init { anchor.followThresholdDeg = BrowseLayout.FOLLOW_THRESHOLD_DEG }

      private val sidebar = SourceSidebarWidget(theme, metrics)
      private val grid = MediaGridWidget(theme, metrics)
      private val inspector = MediaInspectorWidget(theme, metrics)

      fun render(state: AppState, thumbs: ThumbnailCache) {
          if (state.screen != VrScreen.BROWSE) return
          val f = state.browse.top ?: return
          val key = listOf(
              state.sources.selectedId, state.sources.localPermission,
              state.browse.stack.map { it.objectId },
              f.folders.map { it.id }, f.sortedVideos.map { it.id },
              f.focus, f.sort, f.sidebarScrollTop, f.loading, f.error,
              state.gaze, state.thumbGeneration,
          )
          renderIfChanged(key) { canvas ->
              canvas.panelBackground(theme, metrics)
              val boundsSidebar = RectF(0f, 0f, BrowseLayout.SIDEBAR_PX.toFloat(), BrowseLayout.HEIGHT_PX.toFloat()) // [0, 394]
              val boundsGrid = RectF(
                  (BrowseLayout.SIDEBAR_PX + BrowseLayout.GUTTER_PX).toFloat(),
                  0f,
                  (BrowseLayout.SIDEBAR_PX + BrowseLayout.GUTTER_PX + BrowseLayout.CENTRE_PX).toFloat(),
                  BrowseLayout.HEIGHT_PX.toFloat()
              ) // [418, 1118]
              val boundsInspector = RectF(
                  (BrowseLayout.WIDTH_PX - BrowseLayout.SIDEBAR_PX).toFloat(),
                  0f,
                  BrowseLayout.WIDTH_PX.toFloat(),
                  BrowseLayout.HEIGHT_PX.toFloat()
              ) // [1142, 1536]

              val sl = sidebar.measureLayout(state to f, boundsSidebar)
              val gl = grid.measureLayout(f, boundsGrid)
              val il = inspector.measureLayout(f to (state.playback.resumePositionMs != null), boundsInspector)

              sidebar.draw(canvas, state to f, sl, f.focus, state.gaze)
              grid.draw(canvas, f, gl, f.focus, state.gaze, thumbs)
              inspector.draw(canvas, f to (state.playback.resumePositionMs != null), il, f.focus, state.gaze)

              drawGutters(canvas)
              hitMap = HitMap(sidebar.hitRegions(sl) + grid.hitRegions(gl) + inspector.hitRegions(il))
          }
      }

      private fun drawGutters(canvas: Canvas) {
          // Draw subtle vertical hairline dividers at gutter centers x = 406 and x = 1130
          val paint = theme.hairlinePaint()
          canvas.drawLine(406f, 75f, 406f, 784f, paint)
          canvas.drawLine(1130f, 75f, 1130f, 784f, paint)
      }

      fun visibleSidebarRows(): Int = sidebar.visibleRows()
  }
  ```

### 3. Update `app/src/main/java/com/daydreamvr/player/render/AppScene.kt`
- Update constructor to take `thumbnailCacheProvider: () -> ThumbnailCache`:
  ```kotlin
  class AppScene(
      ...,
      private val thumbnailCacheProvider: () -> ThumbnailCache,
  )
  ```
- In `onGlCreate()`:
  - `browse = BrowseScreen(PanelSurface(BrowseLayout.WIDTH_PX, BrowseLayout.HEIGHT_PX), theme).also { it.onGlCreate() }`
  - `dock = SystemDockScreen(PanelSurface(DockLayout.WIDTH_PX, DockLayout.HEIGHT_PX), theme).also { it.onGlCreate() }`
- In `update(dtSeconds, pose)`:
  - `browse?.render(state, thumbnailCacheProvider())`
  - `dock?.render(state)`
  - `dock?.anchor?.snapTo(browse.anchor.yawRad)` (already there!)
  - `dock?.updateTexture()`
- In `drawGl(eye, viewM, projM)`:
  ```kotlin
  VrScreen.BROWSE -> {
      browse?.drawGl(eye, viewM, projM)
      dock?.drawGl(eye, viewM, projM)
  }
  ```
- In `onGlDestroy()`:
  - Include `dock` in destruction: `listOfNotNull(serverList, browse, dock, settings, calibration, gamepadCal, hud, overlay).forEach { it.onGlDestroy() }`
  - `dock = null`
- In `reportListWindowOnce()`:
  - `browse = b.visibleSidebarRows()` (or `visibleRows()`)

### 4. Update `app/src/main/java/com/daydreamvr/player/VrActivity.kt`
- Pass `thumbnailCacheProvider = { container.thumbnailCache }` into `AppScene(...)`.

### 5. Verification
- Fix any broken unit tests in `:app`, `:vrcore`, `:playback`, `:upnp`.
- Run `./gradlew testDebugUnitTest --no-daemon`.
- Run `./gradlew assembleRelease --no-daemon`.
