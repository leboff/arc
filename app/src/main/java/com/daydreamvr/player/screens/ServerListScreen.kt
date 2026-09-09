package com.daydreamvr.player.screens

import com.daydreamvr.player.state.AppState
import com.daydreamvr.player.state.DiscoveryState
import com.daydreamvr.vrcore.ui.AngularMetrics
import com.daydreamvr.vrcore.ui.PanelSurface
import com.daydreamvr.vrcore.ui.Theme
import com.daydreamvr.vrcore.ui.widgets.ListView

/** Discovered servers + "Add manually" + "Retry discovery" (ARCHITECTURE.md §11.4). */
class ServerListScreen(panel: PanelSurface, theme: Theme) :
    ScreenPanel(panel, theme, panelWidthM = 2.20f, panelHeightM = 1.45f) {

    private val list = ListView(theme, panel.widthPx)

    fun render(state: AppState) {
        if (state.screen != com.daydreamvr.player.state.VrScreen.SERVER_LIST) return
        val key = listOf(
            state.servers.map { it.udn },
            state.serverFocusIndex,
            state.discovery,
        )
        renderIfChanged(key) { canvas ->
            canvas.panelBackground(theme, panel.widthPx, panel.heightPx)
            val titleSize = AngularMetrics.textSizePx(2.4f, panel.widthPx, theme.panelWidthDegrees)
            canvas.drawText("Media servers", theme.paddingPx, theme.paddingPx + titleSize, theme.textPaint(titleSize, theme.textColor, bold = true))

            if (state.discovery == DiscoveryState.RUNNING) {
                val s = AngularMetrics.textSizePx(1.4f, panel.widthPx, theme.panelWidthDegrees)
                canvas.drawText("Searching…", panel.widthPx - theme.paddingPx - 160f, theme.paddingPx + titleSize, theme.textPaint(s, theme.accentColor))
            }

            val rows = buildList {
                state.servers.forEach { srv ->
                    add(
                        ListView.Row(
                            title = srv.friendlyName,
                            subtitle = listOfNotNull(srv.manufacturer, srv.descriptionUrl.host).joinToString(" · "),
                            isContainer = true,
                        ),
                    )
                }
                add(ListView.Row(title = "Add server manually", subtitle = "Enter host:port", isContainer = false))
                add(ListView.Row(title = "Retry discovery", isContainer = false))
            }
            list.draw(
                canvas, rows, state.serverFocusIndex, scrollTop = 0,
                left = theme.paddingPx, top = theme.paddingPx * 2 + titleSize,
                width = panel.widthPx - theme.paddingPx * 2,
                height = panel.heightPx - theme.paddingPx * 3 - titleSize,
            )
        }
    }
}
