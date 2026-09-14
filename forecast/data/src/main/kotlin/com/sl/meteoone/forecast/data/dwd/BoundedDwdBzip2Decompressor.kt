package com.sl.meteoone.forecast.data.dwd

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

private const val MAX_COMPRESSED_DWD_BYTES = 8 * 1024 * 1024
private const val MAX_DECOMPRESSED_GRIB_BYTES = 64 * 1024 * 1024
private const val COPY_BUFFER_BYTES = 8 * 1024

internal class BoundedDwdBzip2Decompressor(
    private val maxOutputBytes: Int = MAX_DECOMPRESSED_GRIB_BYTES,
) {
    init {
        require(maxOutputBytes in 1..MAX_DECOMPRESSED_GRIB_BYTES) {
            "DWD decompressed payload limit must be between 1 and $MAX_DECOMPRESSED_GRIB_BYTES bytes"
        }
    }

    fun decompress(payload: ByteArray): ByteArray {
        require(payload.isNotEmpty()) { "DWD BZip2 payload must not be empty" }
        require(payload.size <= MAX_COMPRESSED_DWD_BYTES) {
            "DWD BZip2 payload exceeds the compressed transport limit"
        }

        return try {
            ByteArrayInputStream(payload).use { compressed ->
                BZip2CompressorInputStream(compressed, true).use { bzip2 ->
                    val output = ByteArrayOutputStream(minOf(maxOutputBytes, COPY_BUFFER_BYTES))
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0
                    while (true) {
                        val read = bzip2.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        if (read > maxOutputBytes - total) {
                            throw IllegalArgumentException(
                                "DWD BZip2 payload exceeds the decompressed GRIB limit",
                            )
                        }
                        output.write(buffer, 0, read)
                        total += read
                    }
                    require(total > 0) { "DWD BZip2 payload decompressed to an empty body" }
                    output.toByteArray()
                }
            }
        } catch (error: IOException) {
            throw IllegalArgumentException("Invalid DWD BZip2 payload", error)
        }
    }
}
