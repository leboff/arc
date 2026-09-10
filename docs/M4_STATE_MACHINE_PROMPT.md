# Prompt: Claude Sonnet — Milestone 4 (State Machine & 3-Column Navigation)

You are Claude Sonnet, the Principal Implementation Engineer on Arc VR Player.
Your task is to implement Milestone 4 of `docs/UI_REDESIGN_REVIEWED_PLAN.md` (State Machine + Strangler S4–S6).

## Objectives
Implement the state machine refactor supporting the 3-column PLAY'A UI layout and local storage navigation.
Strict invariant: Do not break existing tests or playback functionality. Write red-first acceptance tests.

## Specific Requirements from docs/UI_REDESIGN_REVIEWED_PLAN.md (§10, §14):
1. **Core Types in `:app` (`com.daydreamvr.player.state`)**:
   - `BrowseFocus`:
     ```kotlin
     sealed interface BrowseFocus {
         data class Source(val index: Int) : BrowseFocus
         data class Sidebar(val index: Int) : BrowseFocus
         data class Grid(val index: Int) : BrowseFocus
         data class Inspector(val action: GazeTarget.Action) : BrowseFocus
         data class Dock(val button: GazeTarget.Dock) : BrowseFocus
         data class Toolbar(val chip: GazeTarget.Chip) : BrowseFocus
     }
     ```
   - `SortOrder`: `TITLE_ASC("Name")`, `DATE_DESC("Date")`, `DURATION_DESC("Length")`, `SIZE_DESC("Size")` with `next(): SortOrder`.
   - Update `BrowseFrame` to carry:
     - `source: MediaSource`
     - `folders: List<MediaNode.Folder>`
     - `videos: List<MediaNode.Video>`
     - `sort: SortOrder = SortOrder.TITLE_ASC`
     - `focus: BrowseFocus = BrowseFocus.Grid(0)`
     - `sidebarScrollTop: Int = 0`
     - `sortedVideos`: sorted view of videos based on `sort`
     - `gridFocusIndex: Int` (`(focus as? BrowseFocus.Grid)?.index ?: 0`)
     - `gridPage`: `gridFocusIndex / GRID_PAGE_SIZE` (where `GRID_PAGE_SIZE = 6`)
     - `pageCount`: `((sortedVideos.size + GRID_PAGE_SIZE - 1) / GRID_PAGE_SIZE).coerceAtLeast(1)`
     - `focusedVideo`: `sortedVideos.getOrNull(gridFocusIndex)`
     - Maintain backward compatibility properties for legacy code during strangler if needed.
2. **Events & UI Intents**:
   - `Event.Ui(val intent: UiIntent) : Event`
   - `Event.LocalMediaLoaded(val folders: List<MediaNode.Folder>, val byFolder: Map<String, List<MediaNode.Video>>) : Event`
   - `Event.LocalMediaFailed(val message: String) : Event`
   - `Event.LocalPermissionChanged(val grant: MediaPermission.Grant) : Event`
   - `Event.LocalMediaChanged : Event`
   - `Event.ThumbnailsArrived : Event`
   - `sealed interface UiIntent`:
     - `SelectSource(val sourceId: String)`
     - `SelectFolder(val folderId: String)`
     - `SelectGridCell(val index: Int)`
     - `SelectSort`
     - `PageNext`, `PagePrev`
     - `NavigateBreadcrumb(val depth: Int)`
     - `OverrideProjection`
     - `PlayVideo(val fromStart: Boolean)`
     - `DockAction(val button: GazeTarget.Dock)`
3. **Effects**:
   - `Effect.LoadLocalMedia`
   - `Effect.BrowseNode(val source: MediaSource, val objectId: String, val page: PageRequest)`
   - `Effect.PlayNode(val node: MediaNode.Video, val key: MediaKey, val startAtMs: Long, val projectionOverride: ProjectionMode?, val skipResumeCheck: Boolean = false)`
   - `Effect.PersistProjectionOverride(val key: MediaKey, val mode: ProjectionMode?)`
   - `Effect.PrefetchThumbnails(val keys: List<Pair<String, MediaRef>>)`
4. **`AppStateMachine.kt` Reducer Rules (§10.4, §10.5)**:
   - Handle `Event.Ui(intent)` with all `UiIntent` variants.
   - Implement `intentForFocus(frame: BrowseFrame): UiIntent?`.
   - In `reduceBrowse`:
     - `Nav(UP/DOWN)`: move within focused region (sidebar, grid row, dock).
     - `Nav(LEFT/RIGHT)`: within grid moves -1/+1 cell. At column edge: moves to adjacent region (`Sidebar <-> Grid <-> Inspector`).
     - `Nav(DOWN)` at bottom grid row moves to `Dock`. `Nav(UP)` from `Dock` returns to previous grid cell.
     - `Confirm` (A button) executes `intentForFocus(frame)`.
     - `Cancel` (B button) pops breadcrumb level or exits to source list (`softEscape`).
     - `PageUp/PageDown` (L1/R1) triggers `PagePrev`/`PageNext`.
   - Gaze: `reduceGaze` updates `frame.focus` to the gaze target when gazing into sidebar/grid/inspector/dock.
5. **Acceptance Unit Tests**:
   - `SortReducerTest`: cycling sort keeps same focused video ID (`SortReducerTest.kt`).
   - `GridPageTest`: gridPage calculation and `PageNext` behavior (`GridPageTest.kt`).
   - `ColumnNavTest`: navigation between Sidebar, Grid, Inspector, and Dock (`ColumnNavTest.kt`).
   - `EscapeHatchTest`: verify B button escape across all `BrowseFocus` states.
   - Run `./gradlew testDebugUnitTest --no-daemon` and ensure 100% green tests!
