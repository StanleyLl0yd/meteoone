package com.sl.meteoone.forecast.data.source

data class ByteRange(
    val offset: Long,
    val length: Long,
) {
    init {
        require(offset >= 0) { "Byte-range offset must not be negative" }
        require(length > 0) { "Byte-range length must be positive" }
        require(offset <= Long.MAX_VALUE - (length - 1)) {
            "Byte range exceeds Long address space"
        }
    }

    val inclusiveEnd: Long
        get() = offset + length - 1

    val headerValue: String
        get() = "bytes=$offset-$inclusiveEnd"
}
