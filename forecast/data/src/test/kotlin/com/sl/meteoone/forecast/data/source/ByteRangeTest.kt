package com.sl.meteoone.forecast.data.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ByteRangeTest {
    @Test
    fun createsInclusiveHttpRangeHeader() {
        val range = ByteRange(offset = 17_459_800, length = 609_046)

        assertEquals(18_068_845, range.inclusiveEnd)
        assertEquals("bytes=17459800-18068845", range.headerValue)
    }

    @Test
    fun acceptsLastAddressableByteWithoutOverflow() {
        val range = ByteRange(offset = Long.MAX_VALUE, length = 1)

        assertEquals(Long.MAX_VALUE, range.inclusiveEnd)
    }

    @Test
    fun rejectsNegativeZeroAndOverflowingRanges() {
        assertFailsWith<IllegalArgumentException> {
            ByteRange(offset = -1, length = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            ByteRange(offset = 0, length = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            ByteRange(offset = Long.MAX_VALUE, length = 2)
        }
    }
}
