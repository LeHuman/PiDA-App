package com.pida.app.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * HTTP-level failure preserving the status code, so callers can distinguish
 * endpoint problems (404) from server faults (5xx) and transport failures.
 */
class HttpError(val code: Int, message: String) : IOException(message)

/**
 * Generic Pi API client. All responses are JSON maps with at least "ok";
 * parameterized action responses merge their declared outputs at top level
 * ({"ok": true, <outputs>}), so everything is read from Map<String, Any?>.
 */
class PiApiClient private constructor(private val prefs: PreferencesManager) {

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val mapType = object : TypeToken<Map<String, Any?>>() {}.type

    // Reads can queue on the Pi (30 s lane wait) and legacy button dispatches
    // may run long macros; generous read timeout, still bounded.
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val baseUrl: String
        get() = prefs.serverBaseUrl


    suspend fun getHealth(): Result<HealthResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$baseUrl/api/health")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw HttpError(response.code, "Unexpected HTTP ${response.code}: ${response.message}")
                }
                val body = response.body?.string() ?: throw IOException("Empty response")
                gson.fromJson(body, HealthResponse::class.java)
            }
        }
    }

    suspend fun getSchema(): Result<Map<String, Any?>> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$baseUrl/api/schema")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: throw IOException("Empty response")
                if (!response.isSuccessful) {
                    throw HttpError(response.code, "HTTP ${response.code}: $body")
                }
                gson.fromJson(body, mapType) ?: emptyMap()
            }
        }
    }

    /** Parameterized dispatch: partial-delta body {param: value}. */
    suspend fun dispatch(path: String, body: Map<String, Any?>): Result<Map<String, Any?>> =
        withContext(Dispatchers.IO) {
            runCatching { post(clean(path), gson.toJson(body)) }
        }

    /** Legacy no-arg action: POST {} (button dispatch, no state verification). */
    suspend fun dispatchLegacy(path: String): Result<Map<String, Any?>> =
        withContext(Dispatchers.IO) {
            runCatching { post(clean(path), "{}") }
        }

    /** GET-as-state readback for a parameterized action (empty delta). */
    suspend fun getState(path: String): Result<Map<String, Any?>> =
        withContext(Dispatchers.IO) {
            runCatching { get("$baseUrl/api/${clean(path)}") }
        }

    /** Real control icon for an action: GET /api/icon/<action path> (PNG). */
    suspend fun fetchIcon(actionPath: String): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$baseUrl/api/icon/${clean(actionPath)}")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw HttpError(response.code, "HTTP ${response.code}")
                    }
                    response.body?.bytes() ?: throw IOException("Empty response")
                }
            }
        }

    private fun clean(path: String): String = path.trim().trimStart('/')

    private fun parse(response: okhttp3.Response): Map<String, Any?> {
        val body = response.body?.string()
        val parsed: Map<String, Any?> = if (!body.isNullOrBlank()) {
            gson.fromJson(body, mapType) ?: emptyMap()
        } else {
            emptyMap()
        }
        if (!response.isSuccessful || parsed["ok"] != true) {
            throw HttpError(response.code, (parsed["error"] as? String) ?: "HTTP ${response.code}")
        }
        return parsed
    }

    private fun get(url: String): Map<String, Any?> {
        val request = Request.Builder().url(url).get().build()
        return client.newCall(request).execute().use { response -> parse(response) }
    }

    private fun post(path: String, json: String): Map<String, Any?> {
        val body = json.toRequestBody(jsonMediaType)
        val request = Request.Builder().url("$baseUrl/api/$path").post(body).build()
        return client.newCall(request).execute().use { response -> parse(response) }
    }

    companion object {
        /** Short, user-facing failure text; server error strings pass through. */
        fun friendlyError(t: Throwable): String = when (t) {
            is HttpError -> when {
                t.code == 404 -> "Not found — check the endpoint path"
                t.code in 500..599 -> "Pi server error (${t.code})"
                else -> t.message?.ifBlank { null } ?: "HTTP ${t.code}"
            }
            is java.net.UnknownHostException -> "Pi not found — check the server address"
            is java.net.SocketTimeoutException -> "Pi timed out"
            is java.net.ConnectException -> "Can't reach the Pi (is it online?)"
            is IOException -> "Network error"
            else -> t.message ?: "Unknown error"
        }

        @Volatile
        private var instance: PiApiClient? = null

        fun getInstance(prefs: PreferencesManager): PiApiClient {
            return instance ?: synchronized(this) {
                instance ?: PiApiClient(prefs).also { instance = it }
            }
        }
    }
}
