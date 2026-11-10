package com.xblackbytesx.calzone.net

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uniffi.calzone_ffi.HttpResult
import uniffi.calzone_ffi.HttpTransport

/** One OkHttp client for the app: connection pool and TLS sessions are shared. */
object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // A batch of contacts with photos can take a while on a slow line.
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // A redirect would carry the device token to wherever it points.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

/**
 * The engine's HTTP (decision 48 in calzone-server): the server's address in
 * front of the core's path, the device token once signed in. Any answer is
 * an answer; only "no answer" is an error, so the core can tell "try later"
 * from "sign in again".
 */
class OkHttpTransport(
    private val server: String,
    private val token: String?,
    private val client: OkHttpClient = Http.client,
) : HttpTransport {
    private val json = "application/json".toMediaType()

    override fun send(method: String, path: String, body: String?): HttpResult {
        val request = Request.Builder()
            .url(server.trimEnd('/') + path)
            .method(method, body?.toRequestBody(json) ?: if (method == "POST" || method == "PUT") ByteArray(0).toRequestBody(json) else null)
            .header("Accept", "application/json")
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .build()
        return try {
            client.newCall(request).execute().use { r -> HttpResult(r.code.toUShort(), r.body.string(), null) }
        } catch (e: IOException) {
            // The message names the host at most, never the token.
            HttpResult(0u, "", e.message ?: e.javaClass.simpleName)
        } catch (e: IllegalArgumentException) {
            HttpResult(0u, "", "not a server address")
        }
    }
}
