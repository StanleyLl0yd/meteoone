package com.sl.meteoone.core.database

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastInterval
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.model.SourceForecast
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val DEFAULT_VERIFICATION_HISTORY_RETENTION: Duration = Duration.ofDays(180)
private val MAX_VERIFICATION_LEAD: Duration = Duration.ofHours(72)

data class StoredVerificationForecastPoint(
    val validTime: Instant,
    val leadTime: Duration,
    val temperatureC: Double?,
    val pressureSeaLevelHpa: Double?,
    val windSpeedMps: Double?,
    val windDirectionDegrees: Double?,
    val precipitationMm: Double?,
    val precipitationInterval: ForecastInterval?,
)

data class StoredVerificationForecastRun(
    val coordinate: ForecastCoordinate,
    val provider: ForecastProvider,
    val modelFamily: ModelFamily,
    val modelRun: Instant,
    val firstCapturedAt: Instant,
    val elevationMeters: Int?,
    val timeZoneId: String,
    val hourly: List<StoredVerificationForecastPoint>,
)

data class ForecastVerificationArchiveResult(
    val insertedRuns: Int,
    val insertedPoints: Int,
    val existingPoints: Int,
    val skippedWithoutModelRun: Int,
    val skippedExpiredRuns: Int,
    val prunedRuns: Int,
)

interface ForecastVerificationHistoryStore {
    suspend fun archive(
        coordinate: ForecastCoordinate,
        forecasts: List<SourceForecast>,
    ): ForecastVerificationArchiveResult

    suspend fun readSince(
        coordinate: ForecastCoordinate,
        modelRunFromInclusive: Instant,
    ): List<StoredVerificationForecastRun>
}

internal data class VerificationForecastRows(
    val run: VerificationForecastRunEntity,
    val hourly: List<VerificationForecastHourlyEntity>,
)

internal data class VerificationArchiveCounts(
    val insertedRuns: Int,
    val insertedPoints: Int,
    val existingPoints: Int,
    val prunedRuns: Int,
)

internal class RoomForecastVerificationHistoryStore(
    private val dao: ForecastVerificationHistoryDao,
    private val clock: Clock = Clock.systemUTC(),
    private val retention: Duration = DEFAULT_VERIFICATION_HISTORY_RETENTION,
) : ForecastVerificationHistoryStore {
    init {
        require(!retention.isNegative && !retention.isZero) {
            "Verification history retention must be positive"
        }
    }

    override suspend fun archive(
        coordinate: ForecastCoordinate,
        forecasts: List<SourceForecast>,
    ): ForecastVerificationArchiveResult {
        val cutoff = clock.instant().minus(retention)
        var skippedWithoutModelRun = 0
        var skippedExpiredRuns = 0
        val identities = mutableSetOf<VerificationRunIdentity>()
        val rows = buildList {
            forecasts.forEach { forecast ->
                validateVerificationSource(coordinate, forecast)
                val modelRun = forecast.origin.modelRun
                if (modelRun == null) {
                    skippedWithoutModelRun += 1
                    return@forEach
                }
                if (modelRun.isBefore(cutoff)) {
                    skippedExpiredRuns += 1
                    return@forEach
                }

                val identity = VerificationRunIdentity(
                    provider = forecast.origin.provider,
                    modelFamily = forecast.origin.modelFamily,
                    modelRun = modelRun,
                )
                require(identities.add(identity)) {
                    "Verification forecast batch contains duplicate run identity"
                }
                add(forecast.toVerificationRows(coordinate, modelRun))
            }
        }

        val counts = dao.archive(
            rows = rows,
            cutoffEpochSecond = cutoff.epochSecond,
            cutoffNano = cutoff.nano,
        )
        return ForecastVerificationArchiveResult(
            insertedRuns = counts.insertedRuns,
            insertedPoints = counts.insertedPoints,
            existingPoints = counts.existingPoints,
            skippedWithoutModelRun = skippedWithoutModelRun,
            skippedExpiredRuns = skippedExpiredRuns,
            prunedRuns = counts.prunedRuns,
        )
    }

    override suspend fun readSince(
        coordinate: ForecastCoordinate,
        modelRunFromInclusive: Instant,
    ): List<StoredVerificationForecastRun> {
        val key = coordinate.toPersistedKey()
        val runs = dao.readRunsSince(
            coordinateKey = key.encoded,
            fromEpochSecond = modelRunFromInclusive.epochSecond,
            fromNano = modelRunFromInclusive.nano,
        )
        val hourly = dao.readHourlySince(
            coordinateKey = key.encoded,
            fromEpochSecond = modelRunFromInclusive.epochSecond,
            fromNano = modelRunFromInclusive.nano,
        )
        val hourlyByRun = hourly.groupBy(VerificationForecastHourlyEntity::runIdentity)
        return runs.map { run ->
            val points = hourlyByRun[run.runIdentity()]
                .orEmpty()
                .map(VerificationForecastHourlyEntity::toStoredPoint)
            check(points.isNotEmpty()) {
                "Verification forecast run contains no hourly evidence"
            }
            run.toStoredRun(points)
        }
    }
}

private data class VerificationRunIdentity(
    val provider: ForecastProvider,
    val modelFamily: ModelFamily,
    val modelRun: Instant,
)

private data class PersistedVerificationRunIdentity(
    val coordinateKey: String,
    val provider: String,
    val modelFamily: String,
    val modelRunEpochSecond: Long,
    val modelRunNano: Int,
)

private fun validateVerificationSource(
    coordinate: ForecastCoordinate,
    forecast: SourceForecast,
) {
    require(
        forecast.location.latitude == coordinate.latitude &&
            forecast.location.longitude == coordinate.longitude
    ) {
        "Verification forecast location must match the privacy-reduced coordinate"
    }
    require(forecast.origin.provider != ForecastProvider.UNKNOWN) {
        "Verification forecast provider must be known"
    }
    require(forecast.origin.modelFamily != ModelFamily.UNKNOWN) {
        "Verification forecast model family must be known"
    }
}

private fun SourceForecast.toVerificationRows(
    coordinate: ForecastCoordinate,
    modelRun: Instant,
): VerificationForecastRows {
    val key = coordinate.toPersistedKey()
    val run = VerificationForecastRunEntity(
        coordinateKey = key.encoded,
        latitudeTenths = key.latitudeTenths,
        longitudeTenths = key.longitudeTenths,
        provider = origin.provider.name,
        modelFamily = origin.modelFamily.name,
        modelRunEpochSecond = modelRun.epochSecond,
        modelRunNano = modelRun.nano,
        firstCapturedAtEpochSecond = origin.generatedAt.epochSecond,
        firstCapturedAtNano = origin.generatedAt.nano,
        elevationMeters = location.elevationMeters,
        timeZoneId = location.timeZoneId,
    )
    val hourlyRows = hourly.map { point ->
        val lead = Duration.between(modelRun, point.time)
        require(!lead.isNegative && lead <= MAX_VERIFICATION_LEAD) {
            "Verification forecast point lead must be within 0..72 hours"
        }
        require(point.windSpeedMps == null || point.windSpeedMps >= 0.0) {
            "Verification forecast wind speed must not be negative"
        }
        require(point.precipitationMm == null || point.precipitationMm >= 0.0) {
            "Verification forecast precipitation must not be negative"
        }

        VerificationForecastHourlyEntity(
            coordinateKey = key.encoded,
            provider = origin.provider.name,
            modelFamily = origin.modelFamily.name,
            modelRunEpochSecond = modelRun.epochSecond,
            modelRunNano = modelRun.nano,
            validTimeEpochSecond = point.time.epochSecond,
            validTimeNano = point.time.nano,
            leadSeconds = lead.seconds,
            leadNano = lead.nano,
            temperatureC = point.temperatureC,
            pressureSeaLevelHpa = point.pressureSeaLevelHpa,
            windSpeedMps = point.windSpeedMps,
            windDirectionDegrees = point.windDirectionDegrees,
            precipitationMm = point.precipitationMm,
            precipitationIntervalStartEpochSecond = point.precipitationInterval?.start?.epochSecond,
            precipitationIntervalStartNano = point.precipitationInterval?.start?.nano,
        )
    }
    return VerificationForecastRows(run = run, hourly = hourlyRows)
}

private fun VerificationForecastRunEntity.runIdentity() = PersistedVerificationRunIdentity(
    coordinateKey = coordinateKey,
    provider = provider,
    modelFamily = modelFamily,
    modelRunEpochSecond = modelRunEpochSecond,
    modelRunNano = modelRunNano,
)

private fun VerificationForecastHourlyEntity.runIdentity() = PersistedVerificationRunIdentity(
    coordinateKey = coordinateKey,
    provider = provider,
    modelFamily = modelFamily,
    modelRunEpochSecond = modelRunEpochSecond,
    modelRunNano = modelRunNano,
)

private fun VerificationForecastRunEntity.toStoredRun(
    points: List<StoredVerificationForecastPoint>,
): StoredVerificationForecastRun {
    val coordinate = ForecastCoordinate(
        latitude = latitudeTenths.toDegrees(),
        longitude = longitudeTenths.toDegrees(),
    )
    check(coordinate.toPersistedKey().encoded == coordinateKey) {
        "Verification forecast coordinate key is inconsistent"
    }
    return StoredVerificationForecastRun(
        coordinate = coordinate,
        provider = enumValue(provider, "verification forecast provider"),
        modelFamily = enumValue(modelFamily, "verification model family"),
        modelRun = instant(modelRunEpochSecond, modelRunNano),
        firstCapturedAt = instant(firstCapturedAtEpochSecond, firstCapturedAtNano),
        elevationMeters = elevationMeters,
        timeZoneId = timeZoneId,
        hourly = points,
    )
}

private fun VerificationForecastHourlyEntity.toStoredPoint(): StoredVerificationForecastPoint {
    val validTime = instant(validTimeEpochSecond, validTimeNano)
    val lead = Duration.ofSeconds(leadSeconds, leadNano.toLong())
    check(!lead.isNegative && lead <= MAX_VERIFICATION_LEAD) {
        "Stored verification forecast lead is outside 0..72 hours"
    }
    val interval = when {
        precipitationIntervalStartEpochSecond == null &&
            precipitationIntervalStartNano == null -> null

        precipitationIntervalStartEpochSecond != null &&
            precipitationIntervalStartNano != null -> ForecastInterval(
                start = instant(
                    precipitationIntervalStartEpochSecond,
                    precipitationIntervalStartNano,
                ),
                end = validTime,
            )

        else -> error("Stored verification precipitation interval is incomplete")
    }
    return StoredVerificationForecastPoint(
        validTime = validTime,
        leadTime = lead,
        temperatureC = temperatureC,
        pressureSeaLevelHpa = pressureSeaLevelHpa,
        windSpeedMps = windSpeedMps,
        windDirectionDegrees = windDirectionDegrees,
        precipitationMm = precipitationMm,
        precipitationInterval = interval,
    )
}

private fun Int.toDegrees(): Double =
    java.math.BigDecimal.valueOf(toLong()).movePointLeft(1).toDouble()
