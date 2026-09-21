package com.sl.meteoone.core.database

import com.sl.meteoone.core.model.ForecastInterval
import com.sl.meteoone.verification.domain.ObservationStation
import com.sl.meteoone.verification.domain.PrecipitationObservation
import com.sl.meteoone.verification.domain.SurfaceObservation
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val DEFAULT_OBSERVATION_RETENTION: Duration = Duration.ofDays(180)

data class VerificationObservationArchiveResult(
    val stationInserted: Boolean,
    val insertedSurface: Int,
    val enrichedSurface: Int,
    val existingSurface: Int,
    val skippedExpiredSurface: Int,
    val insertedPrecipitation: Int,
    val existingPrecipitation: Int,
    val skippedExpiredPrecipitation: Int,
    val prunedSurface: Int,
    val prunedPrecipitation: Int,
    val prunedStations: Int,
)

data class StoredVerificationObservationSeries(
    val station: ObservationStation,
    val surfaceObservations: List<SurfaceObservation>,
    val precipitationObservations: List<PrecipitationObservation>,
)

interface VerificationObservationStore {
    suspend fun archive(
        station: ObservationStation,
        surfaceObservations: List<SurfaceObservation>,
        precipitationObservations: List<PrecipitationObservation>,
    ): VerificationObservationArchiveResult

    suspend fun readSince(
        sourceId: String,
        stationId: String,
        fromInclusive: Instant,
    ): StoredVerificationObservationSeries?
}

internal data class VerificationObservationArchiveCounts(
    val stationInserted: Boolean,
    val insertedSurface: Int,
    val enrichedSurface: Int,
    val existingSurface: Int,
    val insertedPrecipitation: Int,
    val existingPrecipitation: Int,
    val prunedSurface: Int,
    val prunedPrecipitation: Int,
    val prunedStations: Int,
)

internal class RoomVerificationObservationStore(
    private val dao: VerificationObservationDao,
    private val clock: Clock = Clock.systemUTC(),
    private val retention: Duration = DEFAULT_OBSERVATION_RETENTION,
) : VerificationObservationStore {
    init {
        require(!retention.isNegative && !retention.isZero) {
            "Verification observation retention must be positive"
        }
        require(retention <= DEFAULT_OBSERVATION_RETENTION) {
            "Verification observation retention must not exceed forecast-history retention"
        }
    }

    override suspend fun archive(
        station: ObservationStation,
        surfaceObservations: List<SurfaceObservation>,
        precipitationObservations: List<PrecipitationObservation>,
    ): VerificationObservationArchiveResult {
        validateStation(station)
        val now = clock.instant()
        val cutoff = now.minus(retention)

        require(surfaceObservations.map { it.observedAt }.distinct().size == surfaceObservations.size) {
            "Verification surface observation batch contains duplicate timestamps"
        }
        require(
            precipitationObservations.map { it.interval }.distinct().size ==
                precipitationObservations.size,
        ) {
            "Verification precipitation observation batch contains duplicate intervals"
        }

        var skippedExpiredSurface = 0
        val surfaceRows = surfaceObservations.mapNotNull { observation ->
            require(observation.station == station) {
                "Verification surface observation station does not match archive station"
            }
            require(!observation.observedAt.isAfter(now)) {
                "Verification surface observation must not be in the future"
            }
            require(hasSurfaceValue(observation)) {
                "Verification surface observation must contain at least one measurement"
            }
            if (observation.observedAt.isBefore(cutoff)) {
                skippedExpiredSurface += 1
                null
            } else {
                observation.toEntity()
            }
        }

        var skippedExpiredPrecipitation = 0
        val precipitationRows = precipitationObservations.mapNotNull { observation ->
            require(observation.station == station) {
                "Verification precipitation observation station does not match archive station"
            }
            require(!observation.interval.end.isAfter(now)) {
                "Verification precipitation observation must not end in the future"
            }
            if (observation.interval.end.isBefore(cutoff)) {
                skippedExpiredPrecipitation += 1
                null
            } else {
                observation.toEntity()
            }
        }

        val counts = dao.archive(
            station = station.toEntity(),
            surface = surfaceRows,
            precipitation = precipitationRows,
            cutoffEpochSecond = cutoff.epochSecond,
            cutoffNano = cutoff.nano,
        )
        return VerificationObservationArchiveResult(
            stationInserted = counts.stationInserted,
            insertedSurface = counts.insertedSurface,
            enrichedSurface = counts.enrichedSurface,
            existingSurface = counts.existingSurface,
            skippedExpiredSurface = skippedExpiredSurface,
            insertedPrecipitation = counts.insertedPrecipitation,
            existingPrecipitation = counts.existingPrecipitation,
            skippedExpiredPrecipitation = skippedExpiredPrecipitation,
            prunedSurface = counts.prunedSurface,
            prunedPrecipitation = counts.prunedPrecipitation,
            prunedStations = counts.prunedStations,
        )
    }

    override suspend fun readSince(
        sourceId: String,
        stationId: String,
        fromInclusive: Instant,
    ): StoredVerificationObservationSeries? {
        require(sourceId.isNotBlank()) { "Verification observation source id must not be blank" }
        require(stationId.isNotBlank()) { "Verification observation station id must not be blank" }

        val stationRow = dao.readStationForSeries(sourceId, stationId) ?: return null
        val station = stationRow.toModel()
        val surface = dao.readSurfaceSince(
            sourceId,
            stationId,
            fromInclusive.epochSecond,
            fromInclusive.nano,
        ).map { row -> row.toModel(station) }
        val precipitation = dao.readPrecipitationSince(
            sourceId,
            stationId,
            fromInclusive.epochSecond,
            fromInclusive.nano,
        ).map { row -> row.toModel(station) }

        return StoredVerificationObservationSeries(
            station = station,
            surfaceObservations = surface,
            precipitationObservations = precipitation,
        )
    }
}

private fun validateStation(station: ObservationStation) {
    require(station.sourceId.isNotBlank())
    require(station.stationId.isNotBlank())
}

private fun hasSurfaceValue(observation: SurfaceObservation): Boolean =
    observation.temperatureC != null ||
        observation.pressureSeaLevelHpa != null ||
        observation.windSpeedMps != null ||
        observation.windDirectionDegrees != null

private fun ObservationStation.toEntity(): VerificationObservationStationEntity =
    VerificationObservationStationEntity(
        sourceId = sourceId,
        stationId = stationId,
        latitude = latitude,
        longitude = longitude,
        elevationMeters = elevationMeters,
    )

private fun VerificationObservationStationEntity.toModel(): ObservationStation =
    ObservationStation(
        sourceId = sourceId,
        stationId = stationId,
        latitude = latitude,
        longitude = longitude,
        elevationMeters = elevationMeters,
    )

private fun SurfaceObservation.toEntity(): VerificationSurfaceObservationEntity =
    VerificationSurfaceObservationEntity(
        sourceId = station.sourceId,
        stationId = station.stationId,
        observedAtEpochSecond = observedAt.epochSecond,
        observedAtNano = observedAt.nano,
        temperatureC = temperatureC,
        pressureSeaLevelHpa = pressureSeaLevelHpa,
        windSpeedMps = windSpeedMps,
        windDirectionDegrees = windDirectionDegrees,
    )

private fun VerificationSurfaceObservationEntity.toModel(
    station: ObservationStation,
): SurfaceObservation =
    SurfaceObservation(
        station = station,
        observedAt = storedObservationInstant(observedAtEpochSecond, observedAtNano),
        temperatureC = temperatureC,
        pressureSeaLevelHpa = pressureSeaLevelHpa,
        windSpeedMps = windSpeedMps,
        windDirectionDegrees = windDirectionDegrees,
    )

private fun PrecipitationObservation.toEntity(): VerificationPrecipitationObservationEntity =
    VerificationPrecipitationObservationEntity(
        sourceId = station.sourceId,
        stationId = station.stationId,
        intervalStartEpochSecond = interval.start.epochSecond,
        intervalStartNano = interval.start.nano,
        intervalEndEpochSecond = interval.end.epochSecond,
        intervalEndNano = interval.end.nano,
        amountMm = amountMm,
    )

private fun VerificationPrecipitationObservationEntity.toModel(
    station: ObservationStation,
): PrecipitationObservation =
    PrecipitationObservation(
        station = station,
        interval = ForecastInterval(
            start = storedObservationInstant(intervalStartEpochSecond, intervalStartNano),
            end = storedObservationInstant(intervalEndEpochSecond, intervalEndNano),
        ),
        amountMm = amountMm,
    )

private fun storedObservationInstant(epochSecond: Long, nano: Int): Instant {
    check(nano in 0..999_999_999) {
        "Stored verification observation timestamp nanoseconds are out of range"
    }
    return Instant.ofEpochSecond(epochSecond, nano.toLong())
}
