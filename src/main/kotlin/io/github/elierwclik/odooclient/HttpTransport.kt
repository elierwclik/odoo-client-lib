/* Kotlin adaptation of Odoo's odoo-client-lib. Original copyright holders:
 * Stephane Wirtel; Nicolas Vanhoren (2011); OpenERP s.a. (2011);
 * Nicolas Seinlet (2018); Odoo s.a. (2018). SPDX-License-Identifier: BSD-3-Clause
 * Full notices, conditions and disclaimer: META-INF/ODOO-LICENSE.txt.
 */
package io.github.elierwclik.odooclient

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

/** Sensitive headers/body are deliberately omitted from toString(). */
class HttpRequest(
    val url: String,
    val method: String = "POST",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
) {
    override fun toString(): String = "HttpRequest(method=$method)"
}

data class HttpResponse(val statusCode: Int, val body: String)

fun interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

/** Uses Android's TLS certificate/hostname validation. Never follows credential-bearing redirects. */
class UrlConnectionTransport(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 60_000,
    private val maxResponseBytes: Int = 16 * 1024 * 1024,
) : HttpTransport {
    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)

    init {
        require(connectTimeoutMillis > 0 && readTimeoutMillis > 0 && maxResponseBytes > 0)
    }

    override suspend fun execute(request: HttpRequest): HttpResponse = runInterruptible(Dispatchers.IO) {
        val uri = URI(request.url)
        require(uri.scheme in listOf("http", "https")) { "Only HTTP(S) URLs are supported" }
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "odoo-client-android/1.0.0")
            cookies.get(uri, emptyMap()).forEach { (name, values) ->
                if (values.isNotEmpty()) connection.setRequestProperty(name, values.joinToString("; "))
            }
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            request.body?.let { body ->
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val headers = connection.headerFields.filterKeys { it != null }
            cookies.put(uri, headers)
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use {
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val count = it.read(chunk)
                    if (count < 0) break
                    if (count > maxResponseBytes - buffer.size()) {
                        throw ProtocolException("Odoo response exceeds configured size limit")
                    }
                    buffer.write(chunk, 0, count)
                }
                buffer.toString("UTF-8")
            }.orEmpty()
            HttpResponse(status, body)
        } finally {
            connection.disconnect()
        }
    }
}
