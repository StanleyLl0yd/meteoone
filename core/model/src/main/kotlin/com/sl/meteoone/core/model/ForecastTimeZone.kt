package com.sl.meteoone.core.model

import java.time.DateTimeException
import java.time.ZoneId

internal fun requireValidTimeZoneId(timeZoneId: String) {
    require(timeZoneId.isNotBlank()) {
        "Forecast time zone must not be blank"
    }
    try {
        ZoneId.of(timeZoneId)
    } catch (error: DateTimeException) {
        throw IllegalArgumentException("Forecast time zone must be a valid ZoneId: $timeZoneId", error)
    }
}
