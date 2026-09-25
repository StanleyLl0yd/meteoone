package com.sl.meteoone.forecast.data.dwd

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class BoundedDwdBzip2DecompressorTest {
    @Test
    fun `decompresses a valid DWD-sized payload`() {
        val expected = "GRIB-meteoone-test-payload-7777".encodeToByteArray()

        val actual = BoundedDwdBzip2Decompressor().decompress(compress(expected))

        assertContentEquals(expected, actual)
    }

    @Test
    fun `rejects output beyond the configured bound`() {
        val compressed = compress(ByteArray(1_025) { 7 })

        assertFailsWith<IllegalArgumentException> {
            BoundedDwdBzip2Decompressor(maxOutputBytes = 1_024).decompress(compressed)
        }
    }

    @Test
    fun `rejects a truncated stream`() {
        val compressed = compress(ByteArray(4_096) { (it and 0xff).toByte() })
        val truncated = compressed.copyOf(compressed.size - 5)

        assertFailsWith<IllegalArgumentException> {
            BoundedDwdBzip2Decompressor().decompress(truncated)
        }
    }

    @Test
    fun `rejects trailing garbage after a valid stream`() {
        val compressed = compress("GRIB7777".encodeToByteArray()) + byteArrayOf(1, 2, 3)

        assertFailsWith<IllegalArgumentException> {
            BoundedDwdBzip2Decompressor().decompress(compressed)
        }
    }

    @Test
    fun `rejects an empty compressed body`() {
        assertFailsWith<IllegalArgumentException> {
            BoundedDwdBzip2Decompressor().decompress(byteArrayOf())
        }
    }

    @Test
    fun `rejects a compressed body above the DWD transport bound`() {
        assertFailsWith<IllegalArgumentException> {
            BoundedDwdBzip2Decompressor().decompress(ByteArray(8 * 1024 * 1024 + 1))
        }
    }

    @Test
    fun `rejects an empty decompressed body`() {
        assertFailsWith<IllegalArgumentException> {
            BoundedDwdBzip2Decompressor().decompress(compress(byteArrayOf()))
        }
    }

    private fun compress(payload: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        BZip2CompressorOutputStream(output).use { compressor ->
            compressor.write(payload)
        }
        return output.toByteArray()
    }
}
