# Codex Fix Task (t_e7882e75): Durable Projection Mode Persistence Across Sessions and In-Player Selection

You are implementing durable projection mode persistence for Arc VR Player (`/root/daydream-vr-player`).

---

## 1. Problem & Context

Currently:
1. When setting a projection override in the Browse Inspector (`ProjectionChooserOrigin.BROWSE_OVERRIDE`), it is saved in memory in `AppState.projectionOverrides[key.storageKey()]` and emits `Effect.PersistProjectionOverride(MediaKey, ProjectionMode?)`.
   However, in `EffectRunner.kt`:
   ```kotlin
   is Effect.PersistProjectionOverride -> Unit // override store wiring lands with M8 integration
   ```
   It is a no-op! Astra's architectural review noted:
   *"- PersistProjectionOverride is a no-op, so overrides disappear on recreation."*
   On app restart or activity recreation, `AppState.projectionOverrides` resets to `emptyMap()`.
2. When changing projection mode inside the player HUD (`ProjectionChooserOrigin.PLAYER`) or via `InputAction.CycleProjection`, it only updates `state.playback.projection` and emits `Effect.SetProjection`. It does NOT persist the override to `projectionOverrides` or disk, so backing out to library or replaying the file loses the mode selection.

---

## 2. Requirements & Implementation

### A. Persist Projection Overrides in `SettingsStore.kt`
File: `app/src/main/java/com/daydreamvr/player/data/SettingsStore.kt`
1. Add preference key:
   ```kotlin
   private val projectionOverridesKey = stringPreferencesKey("projection.overrides")
   ```
2. Add methods to read and write overrides:
   ```kotlin
   suspend fun loadProjectionOverrides(): Map<String, ProjectionMode> =
       (store.data.first()[projectionOverridesKey]
           ?.let { raw ->
               runCatching {
                   json.decodeFromString<Map<String, String>>(raw)
                       .mapNotNull { (key, name) ->
                           runCatching { ProjectionMode.valueOf(name) }.getOrNull()?.let { key to it }
                       }.toMap()
               }.getOrNull()
           }
           ?: emptyMap())

   suspend fun saveProjectionOverrides(overrides: Map<String, ProjectionMode>) {
       val blob = overrides.mapValues { it.value.name }
       store.edit { it[projectionOverridesKey] = json.encodeToString(blob) }
   }

   suspend fun saveProjectionOverride(key: String, mode: ProjectionMode?) {
       val current = loadProjectionOverrides().toMutableMap()
       if (mode == null) {
           current.remove(key)
       } else {
           current[key] = mode
       }
       saveProjectionOverrides(current)
   }
   ```

### B. Wire `EffectRunner.kt`
File: `app/src/main/java/com/daydreamvr/player/state/EffectRunner.kt`
1. Add constructor parameter:
   ```kotlin
   private val saveProjectionOverride: suspend (String, ProjectionMode?) -> Unit = settingsStore::saveProjectionOverride,
   ```
2. In `fun run(effect: Effect)`:
   ```kotlin
   is Effect.PersistProjectionOverride -> {
       val key = effect.key.storageKey()
       scope.launch(Dispatchers.IO) {
           runCatching { saveProjectionOverride(key, effect.mode) }
       }
   }
   ```

### C. Add `Event.ProjectionOverridesLoaded` in `Event.kt`
File: `app/src/main/java/com/daydreamvr/player/state/Event.kt`
1. Add event:
   ```kotlin
   data class ProjectionOverridesLoaded(val overrides: Map<String, ProjectionMode>) : Event
   ```

### D. Rehydrate in `VrActivity.kt`
File: `app/src/main/java/com/daydreamvr/player/VrActivity.kt`
1. In `loadPersistedState(container: com.daydreamvr.player.di.AppContainer)`:
   ```kotlin
   private fun loadPersistedState(container: com.daydreamvr.player.di.AppContainer) {
       lifecycleScope.launch {
           val settings = runCatching { container.settingsStore.current() }.getOrDefault(Settings())
           container.resumeStore.restore(runCatching { container.settingsStore.loadResume() }.getOrDefault(emptyList()))
           val overrides = runCatching { container.settingsStore.loadProjectionOverrides() }.getOrDefault(emptyMap())
           stateMachine.dispatch(Event.SettingsLoaded(settings))
           stateMachine.dispatch(Event.ProjectionOverridesLoaded(overrides))
           applySettings(settings)
       }
   }
   ```

### E. Handle Loaded Overrides & Player Parity in `AppStateMachine.kt`
File: `app/src/main/java/com/daydreamvr/player/state/AppStateMachine.kt`
1. In `reduce(state: AppState, event: Event)`:
   ```kotlin
   is Event.ProjectionOverridesLoaded ->
       state.copy(projectionOverrides = event.overrides + state.projectionOverrides) to noFx()
   ```
2. In `openProjectionChooserFromPlayer(state: AppState)`:
   Populate `targetKey`:
   ```kotlin
   private fun openProjectionChooserFromPlayer(state: AppState): Pair<AppState, List<Effect>> {
       val current = state.playback.projection
       val currentKey = state.playback.queue.entries.getOrNull(state.playback.queue.index)?.key?.storageKey()
           ?: state.playback.itemKey
       return state.copy(
           overlay = Overlay.ProjectionChooser(
               current = current,
               returnTo = ProjectionChooserOrigin.PLAYER,
               targetKey = currentKey,
               focusIndex = Overlay.ProjectionChooser.initialFocusIndex(current),
           ),
       ) to noFx()
   }
   ```
3. In `confirmProjectionChooser`:
   When `overlay.returnTo == ProjectionChooserOrigin.PLAYER`:
   ```kotlin
   ProjectionChooserOrigin.PLAYER -> {
       val currentVideo = state.playback.queue.entries.getOrNull(state.playback.queue.index)?.node
       val applied = mode ?: currentVideo?.detectedProjection ?: cleared.playback.projection
       val storageKey = overlay.targetKey
       if (storageKey != null) {
           val overrides = if (mode == null) {
               cleared.projectionOverrides - storageKey
           } else {
               cleared.projectionOverrides + (storageKey to mode)
           }
           val (sourceId, nodeId) = storageKey.split("|", limit = 2)
               .let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
           cleared.copy(
               playback = cleared.playback.copy(projection = applied),
               projectionOverrides = overrides,
           ) to listOf(
               Effect.SetProjection(applied),
               Effect.PersistProjectionOverride(MediaKey(sourceId, nodeId), mode),
           )
       } else {
           cleared.copy(playback = cleared.playback.copy(projection = applied)) to
               listOf(Effect.SetProjection(applied))
       }
   }
   ```
4. In `applyPlayerAction`:
   For `InputAction.CycleProjection`:
   ```kotlin
   InputAction.CycleProjection -> {
       val next = nextProjection(state.playback.projection)
       val currentKey = state.playback.queue.entries.getOrNull(state.playback.queue.index)?.key
           ?: state.playback.itemKey?.let { itemKey ->
               val parts = itemKey.split("|", limit = 2)
               MediaKey(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" })
           }
       if (currentKey != null) {
           val storageKey = currentKey.storageKey()
           val overrides = state.projectionOverrides + (storageKey to next)
           state.copy(
               playback = state.playback.copy(projection = next),
               projectionOverrides = overrides,
           ) to listOf(
               Effect.SetProjection(next),
               Effect.PersistProjectionOverride(currentKey, next),
           )
       } else {
           state.copy(playback = state.playback.copy(projection = next)) to listOf(Effect.SetProjection(next))
       }
   }
   ```

---

## 3. Unit Tests & Verification

1. In `app/src/test/java/com/daydreamvr/player/state/EffectRunnerLocalMediaTest.kt`:
   - Add test `persistProjectionOverrideInvokesSaveProjectionOverride`:
     Call `runner.run(Effect.PersistProjectionOverride(MediaKey("local", "10"), ProjectionMode.SBS_FULL))` and assert that `saveProjectionOverride` is invoked with `"local|10"` and `ProjectionMode.SBS_FULL`.
2. In `app/src/test/java/com/daydreamvr/player/state/ProjectionOverrideTest.kt`:
   - Add tests:
     - `projectionOverridesLoadedEventPopulatesState`: dispatching `Event.ProjectionOverridesLoaded` updates `state.projectionOverrides`.
     - `playerHudOpensChooserAndPersistsOverrideForCurrentPlayingItem`: opening chooser from player and confirming mode persists override and updates `state.projectionOverrides`.
     - `cycleProjectionInPlayerPersistsOverride`: sending `InputAction.CycleProjection` updates playback projection and persists override for current playing item.
3. Verify all existing tests pass:
   `./gradlew testDebugUnitTest --no-daemon`
   `./gradlew assembleRelease --no-daemon`
4. Commit changes:
   `fix(playback): durable projection mode persistence across sessions and in-player selection`
