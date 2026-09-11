package com.daydreamvr.player.media.local

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.daydreamvr.player.media.MediaNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Flat result of a local scan: ordered folders plus their videos keyed by folder id. */
data class LocalLibrary(
    val folders: List<MediaNode.Folder>,
    val byFolder: Map<String, List<MediaNode.Video>>,
) {
    companion object { val EMPTY = LocalLibrary(emptyList(), emptyMap()) }
}

/**
 * Reads on-device video via `MediaStore` (UI_REDESIGN_REVIEWED_PLAN.md §8.3).
 *
 * The Android-facing half: cursor iteration, permissions, coroutines. All the
 * grouping / labelling / projection logic lives in the pure [MediaCursorMapper].
 */
class LocalMediaRepository(
    context: Context,
    private val scope: CoroutineScope,
    var onChanged: () -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    private val collection: Uri =
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private var observer: ContentObserver? = null
    private var pendingSignal: CancellationSignal? = null

    /** Scans the whole library. Runs on [Dispatchers.IO]; safe to cancel mid-scan. */
    suspend fun load(): Result<LocalLibrary> = withContext(Dispatchers.IO) {
        runCatching {
            pendingSignal?.cancel()
            val signal = CancellationSignal().also { pendingSignal = it }
            val rows = query(signal)
            val grouped = MediaCursorMapper.map(rows)
            LocalLibrary(
                folders = grouped.keys.toList(),
                byFolder = grouped.entries.associate { it.key.id to it.value },
            )
        }
    }

    private fun query(signal: CancellationSignal): List<VideoRow> {
        val rows = ArrayList<VideoRow>()
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val args = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, SELECTION)
                putString(
                    ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                    "${MediaStore.Video.Media.DATE_MODIFIED} DESC",
                )
            }
            resolver.query(collection, PROJECTION, args, signal)
        } else {
            resolver.query(
                collection, PROJECTION, SELECTION, null,
                "${MediaStore.Video.Media.DATE_MODIFIED} DESC",
            )
        } ?: return emptyList()

        cursor.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val titleIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
            val durIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val wIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val hIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val mimeIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val bucketIdIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
            val bucketNameIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val relPathIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            val modIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val addIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val pendingIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.IS_PENDING)

            while (c.moveToNext()) {
                rows.add(
                    VideoRow(
                        id = c.getLong(idIdx),
                        displayName = c.getString(nameIdx) ?: "",
                        title = c.getString(titleIdx),
                        durationMs = c.getLong(durIdx),
                        sizeBytes = c.getLong(sizeIdx),
                        width = c.getInt(wIdx),
                        height = c.getInt(hIdx),
                        mimeType = c.getString(mimeIdx),
                        bucketId = c.getInt(bucketIdIdx),
                        bucketName = c.getString(bucketNameIdx),
                        relativePath = c.getString(relPathIdx),
                        dateModifiedSec = c.getLong(modIdx),
                        dateAddedSec = c.getLong(addIdx),
                        isPending = c.getInt(pendingIdx) != 0,
                    ),
                )
            }
        }
        return rows
    }

    /** Registers a debounced observer that calls [onChanged] on library edits. */
    fun startWatching() {
        if (observer != null) return
        val handler = Handler(Looper.getMainLooper())
        val obs = object : ContentObserver(handler) {
            private var scheduled = false
            override fun onChange(selfChange: Boolean) {
                if (scheduled) return
                scheduled = true
                handler.postDelayed({
                    scheduled = false
                    scope.launch { onChanged() }
                }, DEBOUNCE_MS)
            }
        }
        runCatching {
            resolver.registerContentObserver(collection, true, obs)
            observer = obs
        }.onFailure {
            android.util.Log.w("LocalMediaRepository", "Failed to register content observer", it)
        }
    }

    fun stopWatching() {
        observer?.let { obs ->
            runCatching { resolver.unregisterContentObserver(obs) }
        }
        observer = null
        pendingSignal?.cancel()
    }

    private companion object {
        const val DEBOUNCE_MS = 500L

        val PROJECTION = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.IS_PENDING,
        )

        const val SELECTION = "${MediaStore.Video.Media.IS_PENDING} = 0"
    }
}
