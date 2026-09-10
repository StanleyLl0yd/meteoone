package com.sl.meteoone.forecast.data.dwd

import com.sl.meteoone.forecast.data.source.OfficialSourceRequest
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset

private const val GRID_BASE_URL = "https://opendata.dwd.de/weather/nwp/icon/grib"
private const val MAX_GRID_FIELD_BYTES = 4L * 1024L * 1024L

data class DwdIconGridGeometryPlan(
    val latitudeRequest: OfficialSourceRequest,
    val longitudeRequest: OfficialSourceRequest,
    val modelRun: Instant,
)

object DwdIconGridGeometryPlanner {
    fun plan(modelRun: Instant): DwdIconGridGeometryPlan {
        val runUtc = modelRun.atOffset(ZoneOffset.UTC)
        require(runUtc.minute == 0 && runUtc.second == 0 && runUtc.nano == 0) {
            "ICON model run must be aligned to an exact UTC hour"
        }
        require(runUtc.hour in setOf(0, 6, 12, 18)) {
            "ICON model run must use a 00, 06, 12, or 18 UTC cycle"
        }

        val date = buildString {
            append(runUtc.year.toString().padStart(4, '0'))
            append(runUtc.monthValue.toString().padStart(2, '0'))
            append(runUtc.dayOfMonth.toString().padStart(2, '0'))
        }
        val cycle = runUtc.hour.toString().padStart(2, '0')
        val runToken = "$date$cycle"

        return DwdIconGridGeometryPlan(
            latitudeRequest = gridRequest(cycle, runToken, "clat", "CLAT"),
            longitudeRequest = gridRequest(cycle, runToken, "clon", "CLON"),
            modelRun = modelRun,
        )
    }

    private fun gridRequest(
        cycle: String,
        runToken: String,
        directory: String,
        fieldToken: String,
    ): OfficialSourceRequest = OfficialSourceRequest(
        uri = URI.create(
            "$GRID_BASE_URL/$cycle/$directory/" +
                "icon_global_icosahedral_time-invariant_${runToken}_${fieldToken}.grib2.bz2",
        ),
        maxResponseBytes = MAX_GRID_FIELD_BYTES,
    )
}
