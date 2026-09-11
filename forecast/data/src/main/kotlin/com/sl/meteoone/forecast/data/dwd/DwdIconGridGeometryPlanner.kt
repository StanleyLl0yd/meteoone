package com.sl.meteoone.forecast.data.dwd

import com.sl.meteoone.forecast.data.source.OfficialSourceRequest
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset

private const val GRID_BASE_URL = "https://opendata.dwd.de/weather/nwp/icon/grib"
private const val MAX_GRID_FIELD_BYTES = 4L * 1024L * 1024L

private enum class DwdIconGridGeometryField(
    val directory: String,
    val fileToken: String,
) {
    LATITUDE("clat", "CLAT"),
    LONGITUDE("clon", "CLON"),
}

data class DwdIconGridGeometryPlan(
    val latitudeRequest: OfficialSourceRequest,
    val longitudeRequest: OfficialSourceRequest,
    val modelRun: Instant,
) {
    init {
        requireOperationalIconModelRun(modelRun)
        require(latitudeRequest == buildGridRequest(modelRun, DwdIconGridGeometryField.LATITUDE)) {
            "DWD ICON latitude geometry request must match the planned model run"
        }
        require(longitudeRequest == buildGridRequest(modelRun, DwdIconGridGeometryField.LONGITUDE)) {
            "DWD ICON longitude geometry request must match the planned model run"
        }
    }
}

object DwdIconGridGeometryPlanner {
    fun plan(modelRun: Instant): DwdIconGridGeometryPlan {
        requireOperationalIconModelRun(modelRun)

        return DwdIconGridGeometryPlan(
            latitudeRequest = buildGridRequest(modelRun, DwdIconGridGeometryField.LATITUDE),
            longitudeRequest = buildGridRequest(modelRun, DwdIconGridGeometryField.LONGITUDE),
            modelRun = modelRun,
        )
    }
}

private fun requireOperationalIconModelRun(modelRun: Instant) {
    val runUtc = modelRun.atOffset(ZoneOffset.UTC)
    require(runUtc.minute == 0 && runUtc.second == 0 && runUtc.nano == 0) {
        "ICON model run must be aligned to an exact UTC hour"
    }
    require(runUtc.hour in setOf(0, 6, 12, 18)) {
        "ICON model run must use a 00, 06, 12, or 18 UTC cycle"
    }
}

private fun buildGridRequest(
    modelRun: Instant,
    field: DwdIconGridGeometryField,
): OfficialSourceRequest {
    val runUtc = modelRun.atOffset(ZoneOffset.UTC)
    val date = buildString {
        append(runUtc.year.toString().padStart(4, '0'))
        append(runUtc.monthValue.toString().padStart(2, '0'))
        append(runUtc.dayOfMonth.toString().padStart(2, '0'))
    }
    val cycle = runUtc.hour.toString().padStart(2, '0')
    val runToken = "$date$cycle"

    return OfficialSourceRequest(
        uri = URI.create(
            "$GRID_BASE_URL/$cycle/${field.directory}/" +
                "icon_global_icosahedral_time-invariant_${runToken}_${field.fileToken}.grib2.bz2",
        ),
        maxResponseBytes = MAX_GRID_FIELD_BYTES,
    )
}
