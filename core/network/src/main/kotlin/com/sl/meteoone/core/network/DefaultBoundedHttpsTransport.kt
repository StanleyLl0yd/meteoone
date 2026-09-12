package com.sl.meteoone.core.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request

class DefaultBoundedHttpsTransport : BoundedHttpsTransport {
    private val callFactory: Call.Factory

    constructor() : this(createClient())

    internal constructor(callFactory: Call.Factory) {
        this.callFactory = callFactory
    }

    override fun newCall(request: BoundedHttpsRequest): BoundedHttpsCall {
        val builder = Request.Builder()
            .url(request.uri.toASCIIString())
            .get()
            .header("Accept-Encoding", "identity")

        request.headers.forEach { (name, value) ->
            builder.header(name, value)
        }

        return OkHttpBoundedCall(
            delegate = callFactory.newCall(builder.build()),
            maxResponseBytes = request.maxResponseBytes,
        )
    }

    internal companion object {
        fun createClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .build()
    }
}

private class OkHttpBoundedCall(
    private val delegate: Call,
    private val maxResponseBytes: Long,
) : BoundedHttpsCall {
    override fun execute(): BoundedHttpsResult {
        if (delegate.isCanceled()) {
            return BoundedHttpsResult.Failure(BoundedHttpsFailureReason.CANCELLED)
        }

        return try {
            delegate.execute().use { response ->
                val contentEncodings = response.headers.values("Content-Encoding")
                if (
                    contentEncodings.size > 1 ||
                    contentEncodings.any { !it.equals("identity", ignoreCase = true) }
                ) {
                    return BoundedHttpsResult.Failure(BoundedHttpsFailureReason.INVALID_RESPONSE)
                }

                val contentLengths = response.headers.values("Content-Length")
                if (contentLengths.size > 1) {
                    return BoundedHttpsResult.Failure(BoundedHttpsFailureReason.INVALID_RESPONSE)
                }
                val declaredLength = contentLengths.singleOrNull()?.let { raw ->
                    raw.toLongOrNull()?.takeIf { it >= 0 }
                        ?: return BoundedHttpsResult.Failure(BoundedHttpsFailureReason.INVALID_RESPONSE)
                }
                if (declaredLength != null && declaredLength > maxResponseBytes) {
                    return BoundedHttpsResult.Failure(BoundedHttpsFailureReason.RESPONSE_TOO_LARGE)
                }

                val body = response.body.byteStream().use { input ->
                    val output = ByteArrayOutputStream(minOf(maxResponseBytes, 8192L).toInt())
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        if (total > maxResponseBytes - count) {
                            return BoundedHttpsResult.Failure(
                                BoundedHttpsFailureReason.RESPONSE_TOO_LARGE,
                            )
                        }
                        output.write(buffer, 0, count)
                        total += count
                    }
                    output.toByteArray()
                }

                if (declaredLength != null && declaredLength != body.size.toLong()) {
                    return BoundedHttpsResult.Failure(BoundedHttpsFailureReason.INVALID_RESPONSE)
                }

                val headers = response.headers.names().associateWith { name ->
                    response.headers.values(name).toList()
                }
                BoundedHttpsResult.Success(
                    BoundedHttpsResponse(
                        statusCode = response.code,
                        headers = headers,
                        body = body,
                    ),
                )
            }
        } catch (_: IOException) {
            val reason = if (delegate.isCanceled()) {
                BoundedHttpsFailureReason.CANCELLED
            } else {
                BoundedHttpsFailureReason.IO
            }
            BoundedHttpsResult.Failure(reason)
        }
    }

    override fun cancel() {
        delegate.cancel()
    }
}
