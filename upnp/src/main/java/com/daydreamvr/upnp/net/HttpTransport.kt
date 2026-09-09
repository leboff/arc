package com.daydreamvr.upnp.net

import com.daydreamvr.upnp.model.UpnpError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/** Result of an HTTP exchange. Non-2xx is a valid response, not an error — a UPnP
 * fault arrives as HTTP 500 with a body we must parse (ARCHITECTURE.md §9.4). */
data class HttpResponse(
    val status: Int,
    val body: ByteArray,
    val headers: Map<String, String>,
) {
    fun bodyString(): String = body.toString(Charsets.UTF_8)

    override fun equals(other: Any?): Boolean =
        this === other || (other is HttpResponse && status == other.status && body.contentEquals(other.body))

    override fun hashCode(): Int = 31 * status + body.contentHashCode()
}

/** Thin, injectable seam over OkHttp so the ContentDirectory client is testable
 * against MockWebServer with no real network. */
interface HttpTransport {
    suspend fun get(
        url: URI,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Long = MAX_DESCRIPTION_BYTES,
    ): HttpResponse

    suspend fun post(
        url: URI,
        body: ByteArray,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Long = MAX_RESULT_BYTES,
    ): HttpResponse

    companion object {
        /** Device descriptions are small (ARCHITECTURE.md §16.1). */
        const val MAX_DESCRIPTION_BYTES = 2L * 1024 * 1024

        /** Browse results can be large but not unbounded. */
        const val MAX_RESULT_BYTES = 8L * 1024 * 1024

        const val USER_AGENT = "Android/16 UPnP/1.0 DaydreamVrPlayer/1.0"

        fun okHttp(client: OkHttpClient): HttpTransport = OkHttpTransport(client)

        /** Factory with no OkHttp types in its signature, for callers that don't
         * (and shouldn't need to) put OkHttp on their compile classpath. */
        fun okHttpDefault(): HttpTransport = OkHttpTransport(defaultClient())

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

class OkHttpTransport(private val client: OkHttpClient) : HttpTransport {

    override suspend fun get(url: URI, headers: Map<String, String>, maxBytes: Long): HttpResponse {
        val request = Request.Builder()
            .url(url.toURL())
            .header("User-Agent", HttpTransport.USER_AGENT)
            .headers(headers.toHeaders())
            .get()
            .build()
        return execute(request, maxBytes)
    }

    override suspend fun post(
        url: URI,
        body: ByteArray,
        contentType: String,
        headers: Map<String, String>,
        maxBytes: Long,
    ): HttpResponse {
        val request = Request.Builder()
            .url(url.toURL())
            .header("User-Agent", HttpTransport.USER_AGENT)
            .headers(headers.toHeaders())
            .post(body.toRequestBody(contentType.toMediaTypeOrNull()))
            .build()
        return execute(request, maxBytes)
    }

    private suspend fun execute(request: Request, maxBytes: Long): HttpResponse =
        withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    val source = response.body?.source()
                    val bytes = if (source == null) {
                        ByteArray(0)
                    } else {
                        // Read at most maxBytes + 1 so we can detect an over-cap body.
                        source.request(maxBytes + 1)
                        val buffered = source.buffer
                        if (buffered.size > maxBytes) throw UpnpError.ResponseTooLarge(maxBytes)
                        buffered.readByteArray()
                    }
                    HttpResponse(
                        status = response.code,
                        body = bytes,
                        headers = response.headers.toMap(),
                    )
                }
            } catch (e: UpnpError) {
                throw e
            } catch (e: IOException) {
                throw UpnpError.Transport("HTTP ${request.method} ${request.url} failed: ${e.message}", e)
            }
        }
}
