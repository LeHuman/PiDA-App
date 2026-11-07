package com.pida.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.pida.app.data.PiApiClient
import com.pida.app.data.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Icon repository for widgets: each action's real PNG is fetched once from
 * GET /api/icon/<action path> (base URL from preferences) and cached on
 * disk (filesDir/icons/<sanitized-path>.png) plus a small in-memory
 * LruCache.
 *
 * [peek] touches memory only — main-thread safe anywhere a widget is
 * rendered. [load] performs the possible disk/network IO on a background
 * scope and calls its receiver with the bitmap (or null on 404/failure —
 * callers keep the generic fallback icon then).
 */
object PiWidgetIcons {

    private const val TAG = "PiDA-W"
    private const val TARGET_PX = 96

    // Holds every bound action icon across re-renders: a render that finds
    // its bitmap resident skips the fallback flash entirely (no flicker).
    private val mem = LruCache<String, Bitmap>(64)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = HashSet<String>()

    fun peek(actionPath: String?): Bitmap? =
        actionPath?.let { mem.get(sanitize(it)) }

    fun load(context: android.content.Context, actionPath: String, onReady: (Bitmap?) -> Unit) {
        val file = sanitize(actionPath)
        val started = synchronized(inFlight) { inFlight.add(file) }
        if (!started) {
            // Already being fetched: the in-flight load will push its own
            // render; don't duplicate the network trip.
            onReady(mem.get(file))
            return
        }
        io.launch {
            var bitmap: Bitmap? = null
            try {
                bitmap = readDisk(context, file)
                if (bitmap == null) {
                    val api = PiApiClient.getInstance(PreferencesManager.getInstance(context))
                    api.fetchIcon(actionPath).onSuccess { bytes ->
                        try {
                            val dir = File(context.filesDir, "icons").apply { mkdirs() }
                            File(dir, "$file.png").writeBytes(bytes)
                        } catch (e: Exception) {
                            android.util.Log.w(TAG, "icon cache write failed: ${e.message}")
                        }
                        bitmap = decode(bytes)
                    }.onFailure { t ->
                        android.util.Log.i(
                            TAG, "icon fetch fallback: ${t.message}")
                    }
                }
                if (bitmap != null) mem.put(file, bitmap)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "icon load failed: ${e.message}")
            } finally {
                synchronized(inFlight) { inFlight.remove(file) }
            }
            try {
                onReady(bitmap)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "icon callback failed: ${e.message}")
            }
        }
    }

    /**
     * Load the first candidate that yields a bitmap (candidates in
     * priority order); null only when every candidate fails. De-dupes
     * against in-flight single loads, walks the ladder on failure.
     */
    fun loadFirst(
        context: android.content.Context,
        candidates: Array<out String?>,
        onReady: (Bitmap?) -> Unit
    ) {
        val immediate: Bitmap? = candidates.filterNotNull()
            .asSequence()
            .map { mem.get(sanitize(it)) }
            .firstOrNull { it != null }
        if (immediate != null) {
            onReady(immediate)
            return
        }
        val remaining = candidates.filterNotNull()
        fun step(rest: List<String>) {
            if (rest.isEmpty()) {
                onReady(null)
                return
            }
            load(context, rest.first()) { bitmap ->
                if (bitmap != null) onReady(bitmap) else step(rest.drop(1))
            }
        }
        step(remaining)
    }

    private fun readDisk(context: android.content.Context, file: String): Bitmap? = try {
        val png = File(File(context.filesDir, "icons"), "$file.png")
        if (png.isFile) decode(png.readBytes()) else null
    } catch (e: Exception) {
        null
    }

    /** Decode PNG bytes and downscale to about [TARGET_PX] on the long side. */
    private fun decode(bytes: ByteArray): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) > TARGET_PX * sample * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: return null
        val long = maxOf(decoded.width, decoded.height)
        if (long > TARGET_PX) {
            val scale = TARGET_PX.toFloat() / long
            Bitmap.createScaledBitmap(
                decoded, (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1), true)
                .also { decoded.recycle() }
        } else decoded
    } catch (e: Exception) {
        android.util.Log.w(TAG, "icon decode failed: ${e.message}")
        null
    }

    private fun sanitize(actionPath: String): String =
        actionPath.trim().trim('/').replace(Regex("[^A-Za-z0-9]"), "_")
}
