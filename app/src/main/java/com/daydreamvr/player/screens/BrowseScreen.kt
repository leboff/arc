package com.daydreamvr.player.screens

import android.graphics.Canvas
import com.daydreamvr.player.media.MediaKey
import com.daydreamvr.player.media.MediaNode
import com.daydreamvr.player.media.thumb.ThumbnailCache
import com.daydreamvr.player.screens.widgets.MediaGridWidget
import com.daydreamvr.player.screens.widgets.MediaInspectorWidget
import com.daydreamvr.player.screens.widgets.MediaListWidget
import com.daydreamvr.player.screens.widgets.PixRect
import com.daydreamvr.player.screens.widgets.SourceSidebarWidget
import com.daydreamvr.player.state.AppState
import com.daydreamvr.player.state.BrowseFrame
import com.daydreamvr.player.state.BrowseViewMode
import com.daydreamvr.player.state.VrScreen
import com.daydreamvr.vrcore.render.ProjectionMode
import com.daydreamvr.vrcore.ui.HitMap
import com.daydreamvr.vrcore.ui.Icon
import com.daydreamvr.vrcore.ui.PanelSurface
import com.daydreamvr.vrcore.ui.Theme
import com.daydreamvr.vrcore.ui.widgets.Timeline

/**
 * The PLAY'A three-column browse panel (UI_REDESIGN_REVIEWED_PLAN.md §11): a
 * single [PanelSurface] painted as three regions — a source/folder sidebar, the
 * media grid, and the inspector — with the detached [SystemDockScreen] hanging
 * beneath it as a second surface.
 *
 * The legacy one-column list / breadcrumb / detail-card view is retired (M8).
 */
class BrowseScreen(panel: PanelSurface, theme: Theme) : ScreenPanel(
    panel, theme,
    panelWidthM = BrowseLayout.WIDTH_M,
    panelHeightM = BrowseLayout.HEIGHT_M,
    distanceM = BrowseLayout.RADIUS_M,
) {
    override val verticalOffsetM = BrowseLayout.VERTICAL_OFFSET_M

    init {
        anchor.followThresholdDeg = BrowseLayout.FOLLOW_THRESHOLD_DEG
    }

    private val sidebar = SourceSidebarWidget(theme, metrics)
    private val grid = MediaGridWidget(theme, metrics)
    private val list = MediaListWidget(theme, metrics)
    private val inspector = MediaInspectorWidget(theme, metrics)

    private val height = BrowseLayout.HEIGHT_PX.toFloat()
    private val sidebarBounds = PixRect(0f, 0f, BrowseLayout.SIDEBAR_PX.toFloat(), height)
    private val gridBounds = PixRect(
        (BrowseLayout.SIDEBAR_PX + BrowseLayout.GUTTER_PX).toFloat(),
        0f,
        (BrowseLayout.SIDEBAR_PX + BrowseLayout.GUTTER_PX + BrowseLayout.CENTRE_PX).toFloat(),
        height,
    )
    private val inspectorBounds = PixRect(
        (BrowseLayout.WIDTH_PX - BrowseLayout.SIDEBAR_PX).toFloat(),
        0f,
        BrowseLayout.WIDTH_PX.toFloat(),
        height,
    )

    fun render(state: AppState, thumbs: ThumbnailCache) {
        if (state.screen != VrScreen.BROWSE) return
        val f = state.browse.top ?: return

        // Kick off any missing thumbnail loads for this folder. Idempotent and
        // de-duplicated by the cache; arrivals coalesce into a thumbGeneration bump.
        f.videos.forEach { v ->
            val key = v.thumbnailKey
            val ref = v.thumbnailRef
            if (key != null && ref != null) thumbs.request(key, ref)
        }

        val key = listOf(
            state.sources.selectedId, state.sources.localPermission,
            state.servers.size, state.localMedia.loaded,
            state.browse.stack.map { it.objectId },
            f.folders.map { it.id }, f.sortedVideos.map { it.id },
            f.focus, f.sort, f.viewMode, f.sidebarScrollTop, f.loading, f.error,
            state.gaze, state.thumbGeneration, state.projectionOverrides,
        )
        renderIfChanged(key) { canvas ->
            canvas.panelBackground(theme, metrics)

            val sidebarModel = sidebarModel(state, f)
            val gridModel = gridModel(state, f)
            val inspectorModel = inspectorModel(state, f)

            val sl = sidebar.measureLayout(sidebarModel, sidebarBounds)
            val il = inspector.measureLayout(inspectorModel, inspectorBounds)

            sidebar.draw(canvas, sidebarModel, sl, f.focus, state.gaze)
            inspector.draw(canvas, inspectorModel, il, f.focus, state.gaze, thumbs)

            val centreHitRegions = if (f.viewMode == BrowseViewMode.LIST) {
                val listModel = MediaListWidget.Model(gridModel.items, gridModel.page, gridModel.pageCount)
                val ll = list.measureLayout(listModel, gridBounds)
                list.draw(canvas, listModel, ll, f.focus, state.gaze)
                list.hitRegions(ll)
            } else {
                val gl = grid.measureLayout(gridModel, gridBounds)
                grid.draw(canvas, gridModel, gl, f.focus, state.gaze, thumbs)
                grid.hitRegions(gl)
            }

            drawGutters(canvas)

            hitMap = HitMap(sidebar.hitRegions(sl) + centreHitRegions + inspector.hitRegions(il))
        }
    }

    /** Measured sidebar capacity — feeds `ListWindow.sidebar` via `AppScene` (F5, §5.1). */
    fun visibleSidebarRows(): Int =
        sidebar.measureLayout(SourceSidebarWidget.Model(emptyList(), emptyList()), sidebarBounds).visibleRows()

    private fun drawGutters(canvas: Canvas) {
        val paint = theme.strokePaint(theme.dividerColor, 1f)
        canvas.drawLine(406f, 75f, 406f, 784f, paint)
        canvas.drawLine(1130f, 75f, 1130f, 784f, paint)
    }

    // ---- model builders -----------------------------------------------------

    private fun sidebarModel(state: AppState, f: BrowseFrame): SourceSidebarWidget.Model {
        val deviceCount = state.localMedia.folders.sumOf { it.childCount ?: 0 }.takeIf { state.localMedia.loaded }
        val sources = listOf(
            SourceSidebarWidget.Model.Source("Device", Icon.PHONE, deviceCount),
            SourceSidebarWidget.Model.Source("Network", Icon.NETWORK, state.servers.size.takeIf { it > 0 }),
            SourceSidebarWidget.Model.Source("Favourites", Icon.STAR, null),
        )
        val folders = f.folders.map { SourceSidebarWidget.Model.Folder(it.title, it.childCount, it.icon) }
        return SourceSidebarWidget.Model(sources, folders, f.sidebarScrollTop)
    }

    private fun gridModel(state: AppState, f: BrowseFrame): MediaGridWidget.Model {
        val items = f.sortedVideos.map { v ->
            MediaGridWidget.Model.Card(
                title = v.title,
                meta = listOfNotNull(v.resolutionLabel, v.sizeBytes?.let(::formatBytes))
                    .joinToString(" · ").ifBlank { null },
                durationLabel = v.durationMs?.let { Timeline.formatMs(it) },
                projection = effectiveProjection(state, f, v),
                qualityLabel = qualityLabel(v),
                thumbnailKey = v.thumbnailKey,
            )
        }
        return MediaGridWidget.Model(items, f.gridPage, f.pageCount)
    }

    private fun inspectorModel(state: AppState, f: BrowseFrame): MediaInspectorWidget.Model {
        val v = f.focusedVideo
        return MediaInspectorWidget.Model(
            title = v?.title ?: "",
            posterKey = v?.thumbnailKey,
            metadata = v?.let(::metaRows).orEmpty(),
            resumeLabel = null,
            projectionLabel = v?.let { projectionLabel(effectiveProjection(state, f, it)) } ?: "Auto",
            hasSelection = v != null,
        )
    }

    private fun effectiveProjection(state: AppState, f: BrowseFrame, v: MediaNode.Video): ProjectionMode =
        state.projectionOverrides[MediaKey(f.mediaSource.id, v.id).storageKey()] ?: v.detectedProjection

    private fun metaRows(v: MediaNode.Video): List<MediaInspectorWidget.Model.Row> = buildList {
        v.resolutionLabel?.let { add(MediaInspectorWidget.Model.Row("Resolution", it)) }
        v.durationMs?.let { add(MediaInspectorWidget.Model.Row("Duration", Timeline.formatMs(it))) }
        v.mimeType?.let { add(MediaInspectorWidget.Model.Row("Type", it.substringAfter('/').uppercase())) }
        v.sizeBytes?.let { add(MediaInspectorWidget.Model.Row("Size", formatBytes(it))) }
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) "%.1f GB".format(mb / 1024.0) else "%.0f MB".format(mb)
    }

    private fun qualityLabel(v: MediaNode.Video): String? = when {
        v.width >= 3840 || v.height >= 2160 -> "4K"
        v.width >= 2560 || v.height >= 1440 -> "2K"
        else -> null
    }

    private fun projectionLabel(mode: ProjectionMode): String = mode.label
}
