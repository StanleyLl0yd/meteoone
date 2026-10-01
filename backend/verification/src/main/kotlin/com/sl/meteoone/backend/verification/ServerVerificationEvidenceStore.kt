package com.sl.meteoone.backend.verification

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastInterval
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.model.SourceForecast
import com.sl.meteoone.verification.domain.ForecastVerificationPointEvidence
import com.sl.meteoone.verification.domain.ForecastVerificationRunEvidence
import com.sl.meteoone.verification.domain.ObservationStation
import com.sl.meteoone.verification.domain.PrecipitationObservation
import com.sl.meteoone.verification.domain.SurfaceObservation
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val MAX_RETENTION: Duration = Duration.ofDays(180)
private val DEFAULT_RETENTION: Duration = MAX_RETENTION
private const val DEFAULT_MAX_COORDINATES = 128
private const val DEFAULT_MAX_RUNS_PER_COORDINATE = 256
private const val DEFAULT_MAX_STATIONS = 256
private const val DEFAULT_MAX_SURFACE_PER_STATION = 8192
private const val DEFAULT_MAX_PRECIPITATION_PER_STATION = 8192
private val MAX_VERIFICATION_LEAD: Duration = Duration.ofHours(72)

data class ServerStoredForecastRun(
    val evidence: ForecastVerificationRunEvidence,
    val firstCapturedAt: Instant,
    val elevationMeters: Int?,
) {
    init {
        require(!firstCapturedAt.isBefore(evidence.modelRun)) {
            "Server verification forecast capture time must not precede model run"
        }
    }
}

data class ServerStoredObservationSeries(
    val station: ObservationStation,
    val surfaceObservations: List<SurfaceObservation>,
    val precipitationObservations: List<PrecipitationObservation>,
)

data class ServerForecastArchiveResult(
    val insertedRuns: Int,
    val existingRuns: Int,
    val skippedWithoutModelRun: Int,
    val skippedExpiredRuns: Int,
    val prunedRuns: Int,
    val evictedCoordinates: Int,
)

data class ServerObservationArchiveResult(
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
    val evictedStations: Int,
)

interface ServerVerificationEvidenceStore {
    suspend fun archiveForecasts(
        coordinate: ForecastCoordinate,
        forecasts: Collection<SourceForecast>,
    ): ServerForecastArchiveResult

    suspend fun forecastsSince(
        coordinate: ForecastCoordinate,
        modelRunFromInclusive: Instant,
    ): List<ServerStoredForecastRun>

    suspend fun archiveObservations(
        station: ObservationStation,
        surfaceObservations: Collection<SurfaceObservation>,
        precipitationObservations: Collection<PrecipitationObservation>,
    ): ServerObservationArchiveResult

    suspend fun stations(sourceId: String): List<ObservationStation>

    suspend fun observationsSince(
        sourceId: String,
        stationId: String,
        fromInclusive: Instant,
    ): ServerStoredObservationSeries?
}

/**
 * Process-local bounded M5 verification evidence store.
 *
 * This is intentionally a server-owned boundary rather than a reuse of Android Room entities.
 * Losing an entry under bounded-memory pressure can only reduce verification evidence and therefore
 * causes the existing weight policy to fall back to equal fusion; it can never fabricate evidence.
 */
class InMemoryServerVerificationEvidenceStore(
    private val clock: Clock = Clock.systemUTC(),
    private val retention: Duration = DEFAULT_RETENTION,
    private val maxCoordinates: Int = DEFAULT_MAX_COORDINATES,
    private val maxRunsPerCoordinate: Int = DEFAULT_MAX_RUNS_PER_COORDINATE,
    private val maxStations: Int = DEFAULT_MAX_STATIONS,
    private val maxSurfacePerStation: Int = DEFAULT_MAX_SURFACE_PER_STATION,
    private val maxPrecipitationPerStation: Int = DEFAULT_MAX_PRECIPITATION_PER_STATION,
) : ServerVerificationEvidenceStore {
    init {
        require(!retention.isZero && !retention.isNegative && retention <= MAX_RETENTION) {
            "Server verification retention must be positive and at most $MAX_RETENTION"
        }
        require(maxCoordinates > 0) { "Server verification coordinate limit must be positive" }
        require(maxRunsPerCoordinate > 0) { "Server verification run limit must be positive" }
        require(maxStations > 0) { "Server verification station limit must be positive" }
        require(maxSurfacePerStation > 0) {
            "Server verification surface-observation limit must be positive"
        }
        require(maxPrecipitationPerStation > 0) {
            "Server verification precipitation-observation limit must be positive"
        }
    }

    private data class ForecastRunKey(
        val provider: ForecastProvider,
        val modelFamily: ModelFamily,
        val modelRun: Instant,
    )

    private data class ForecastBucket(
        val runs: MutableMap<ForecastRunKey, ServerStoredForecastRun> = linkedMapOf(),
    )

    private data class StationKey(
        val sourceId: String,
        val stationId: String,
    )

    private data class ObservationBucket(
        val station: ObservationStation,
        val surface: MutableMap<Instant, SurfaceObservation> = linkedMapOf(),
        val precipitation: MutableMap<ForecastInterval, PrecipitationObservation> = linkedMapOf(),
    )

    private val mutex = Mutex()
    private val forecasts = LinkedHashMap<ForecastCoordinate, ForecastBucket>(16, 0.75f, true)
    private val observations = LinkedHashMap<StationKey, ObservationBucket>(16, 0.75f, true)

    override suspend fun archiveForecasts(
        coordinate: ForecastCoordinate,
        forecasts: Collection<SourceForecast>,
    ): ServerForecastArchiveResult = mutex.withLock {
        val now = clock.instant()
        val cutoff = now.minus(retention)
        var prunedRuns = pruneForecasts(cutoff)
        var evictedCoordinates = 0
        var insertedRuns = 0
        var existingRuns = 0
        var skippedWithoutModelRun = 0
        var skippedExpiredRuns = 0

        val seen = mutableSetOf<ForecastRunKey>()
        val accepted = forecasts.mapNotNull { forecast ->
            validateForecastCoordinate(coordinate, forecast)
            require(!forecast.origin.generatedAt.isAfter(now)) {
                "Server verification forecast capture time must not be in the future"
            }
            val modelRun = forecast.origin.modelRun
            if (modelRun == null) {
                skippedWithoutModelRun += 1
                return@mapNotNull null
            }
            require(!forecast.origin.generatedAt.isBefore(modelRun)) {
                "Server verification forecast capture time must not precede model run"
            }
            val key = ForecastRunKey(
                provider = forecast.origin.provider,
                modelFamily = forecast.origin.modelFamily,
                modelRun = modelRun,
            )
            require(seen.add(key)) {
                "Server verification forecast batch contains duplicate run identity"
            }
            if (modelRun.isBefore(cutoff)) {
                skippedExpiredRuns += 1
                return@mapNotNull null
            }
            key to forecast.toStoredEvidence(coordinate, modelRun)
        }

        if (accepted.isNotEmpty()) {
            if (coordinate !in forecastsMapKeys()) {
                while (this.forecasts.size >= maxCoordinates) {
                    val eldest = this.forecasts.entries.first()
                    prunedRuns += eldest.value.runs.size
                    this.forecasts.remove(eldest.key)
                    evictedCoordinates += 1
                }
            }
            val bucket = this.forecasts.getOrPut(coordinate, ::ForecastBucket)
            accepted.forEach { (key, stored) ->
                if (key in bucket.runs) {
                    existingRuns += 1
                } else {
                    bucket.runs[key] = stored
                    insertedRuns += 1
                }
            }
            while (bucket.runs.size > maxRunsPerCoordinate) {
                val oldest = bucket.runs.entries.minWith(
                    compareBy<Map.Entry<ForecastRunKey, ServerStoredForecastRun>>(
                        { it.key.modelRun },
                        { it.key.provider.ordinal },
                        { it.key.modelFamily.ordinal },
                    ),
                )
                bucket.runs.remove(oldest.key)
                prunedRuns += 1
            }
        }

        ServerForecastArchiveResult(
            insertedRuns = insertedRuns,
            existingRuns = existingRuns,
            skippedWithoutModelRun = skippedWithoutModelRun,
            skippedExpiredRuns = skippedExpiredRuns,
            prunedRuns = prunedRuns,
            evictedCoordinates = evictedCoordinates,
        )
    }

    override suspend fun forecastsSince(
        coordinate: ForecastCoordinate,
        modelRunFromInclusive: Instant,
    ): List<ServerStoredForecastRun> = mutex.withLock {
        pruneForecasts(clock.instant().minus(retention))
        forecasts[coordinate]
            ?.runs
            ?.values
            .orEmpty()
            .filter { stored ->
                !stored.evidence.modelRun.isBefore(modelRunFromInclusive)
            }
            .sortedWith(
                compareBy<ServerStoredForecastRun>(
                    { it.evidence.modelRun },
                    { it.evidence.provider.ordinal },
                    { it.evidence.modelFamily.ordinal },
                ),
            )
    }

    override suspend fun archiveObservations(
        station: ObservationStation,
        surfaceObservations: Collection<SurfaceObservation>,
        precipitationObservations: Collection<PrecipitationObservation>,
    ): ServerObservationArchiveResult = mutex.withLock {
        val now = clock.instant()
        val cutoff = now.minus(retention)
        val pruned = pruneObservations(cutoff)
        var evictedStations = pruned.evictedStations
        var prunedSurface = pruned.prunedSurface
        var prunedPrecipitation = pruned.prunedPrecipitation

        require(surfaceObservations.map(SurfaceObservation::observedAt).distinct().size ==
            surfaceObservations.size) {
            "Server verification surface observation batch contains duplicate timestamps"
        }
        require(precipitationObservations.map(PrecipitationObservation::interval).distinct().size ==
            precipitationObservations.size) {
            "Server verification precipitation batch contains duplicate intervals"
        }

        val key = StationKey(station.sourceId, station.stationId)
        val existingBucket = observations[key]
        if (existingBucket != null) {
            require(existingBucket.station == station) {
                "Server verification station metadata cannot change for an existing identity"
            }
        } else {
            while (observations.size >= maxStations) {
                val eldest = observations.entries.first()
                prunedSurface += eldest.value.surface.size
                prunedPrecipitation += eldest.value.precipitation.size
                observations.remove(eldest.key)
                evictedStations += 1
            }
        }
        val stationInserted = existingBucket == null
        val bucket = observations.getOrPut(key) { ObservationBucket(station) }

        var insertedSurface = 0
        var enrichedSurface = 0
        var existingSurface = 0
        var skippedExpiredSurface = 0
        surfaceObservations.forEach { incoming ->
            require(incoming.station == station) {
                "Server verification surface observation station mismatch"
            }
            require(!incoming.observedAt.isAfter(now)) {
                "Server verification surface observation must not be in the future"
            }
            require(incoming.hasSurfaceValue()) {
                "Server verification surface observation must contain a measurement"
            }
            if (incoming.observedAt.isBefore(cutoff)) {
                skippedExpiredSurface += 1
                return@forEach
            }
            val current = bucket.surface[incoming.observedAt]
            when {
                current == null -> {
                    bucket.surface[incoming.observedAt] = incoming
                    insertedSurface += 1
                }

                else -> {
                    val merged = current.enrichWith(incoming)
                    if (merged == current) {
                        existingSurface += 1
                    } else {
                        bucket.surface[incoming.observedAt] = merged
                        enrichedSurface += 1
                    }
                }
            }
        }

        var insertedPrecipitation = 0
        var existingPrecipitation = 0
        var skippedExpiredPrecipitation = 0
        precipitationObservations.forEach { incoming ->
            require(incoming.station == station) {
                "Server verification precipitation observation station mismatch"
            }
            require(!incoming.interval.end.isAfter(now)) {
                "Server verification precipitation observation must not end in the future"
            }
            if (incoming.interval.end.isBefore(cutoff)) {
                skippedExpiredPrecipitation += 1
                return@forEach
            }
            if (bucket.precipitation.putIfAbsent(incoming.interval, incoming) == null) {
                insertedPrecipitation += 1
            } else {
                existingPrecipitation += 1
            }
        }

        while (bucket.surface.size > maxSurfacePerStation) {
            val oldest = bucket.surface.keys.min()
            bucket.surface.remove(oldest)
            prunedSurface += 1
        }
        while (bucket.precipitation.size > maxPrecipitationPerStation) {
            val oldest = bucket.precipitation.keys.minBy(ForecastInterval::end)
            bucket.precipitation.remove(oldest)
            prunedPrecipitation += 1
        }

        ServerObservationArchiveResult(
            stationInserted = stationInserted,
            insertedSurface = insertedSurface,
            enrichedSurface = enrichedSurface,
            existingSurface = existingSurface,
            skippedExpiredSurface = skippedExpiredSurface,
            insertedPrecipitation = insertedPrecipitation,
            existingPrecipitation = existingPrecipitation,
            skippedExpiredPrecipitation = skippedExpiredPrecipitation,
            prunedSurface = prunedSurface,
            prunedPrecipitation = prunedPrecipitation,
            evictedStations = evictedStations,
        )
    }

    override suspend fun stations(sourceId: String): List<ObservationStation> = mutex.withLock {
        require(sourceId.isNotBlank()) { "Server verification source id must not be blank" }
        pruneObservations(clock.instant().minus(retention))
        observations.values
            .map(ObservationBucket::station)
            .filter { station -> station.sourceId == sourceId }
            .sortedBy(ObservationStation::stationId)
    }

    override suspend fun observationsSince(
        sourceId: String,
        stationId: String,
        fromInclusive: Instant,
    ): ServerStoredObservationSeries? = mutex.withLock {
        require(sourceId.isNotBlank()) { "Server verification source id must not be blank" }
        require(stationId.isNotBlank()) { "Server verification station id must not be blank" }
        pruneObservations(clock.instant().minus(retention))
        val bucket = observations[StationKey(sourceId, stationId)] ?: return@withLock null
        ServerStoredObservationSeries(
            station = bucket.station,
            surfaceObservations = bucket.surface.values
                .filter { observation -> !observation.observedAt.isBefore(fromInclusive) }
                .sortedBy(SurfaceObservation::observedAt),
            precipitationObservations = bucket.precipitation.values
                .filter { observation -> !observation.interval.end.isBefore(fromInclusive) }
                .sortedWith(
                    compareBy<PrecipitationObservation> { it.interval.end }
                        .thenBy { it.interval.start },
                ),
        )
    }

    internal suspend fun trackedCoordinateCount(): Int = mutex.withLock { forecasts.size }

    internal suspend fun trackedStationCount(): Int = mutex.withLock { observations.size }

    private fun forecastsMapKeys(): Set<ForecastCoordinate> = forecasts.keys

    private fun pruneForecasts(cutoff: Instant): Int {
        var pruned = 0
        val coordinateIterator = forecasts.entries.iterator()
        while (coordinateIterator.hasNext()) {
            val bucket = coordinateIterator.next().value
            val runIterator = bucket.runs.entries.iterator()
            while (runIterator.hasNext()) {
                if (runIterator.next().key.modelRun.isBefore(cutoff)) {
                    runIterator.remove()
                    pruned += 1
                }
            }
            if (bucket.runs.isEmpty()) coordinateIterator.remove()
        }
        return pruned
    }

    private data class ObservationPruneResult(
        val prunedSurface: Int,
        val prunedPrecipitation: Int,
        val evictedStations: Int,
    )

    private fun pruneObservations(cutoff: Instant): ObservationPruneResult {
        var prunedSurface = 0
        var prunedPrecipitation = 0
        var evictedStations = 0
        val stationIterator = observations.entries.iterator()
        while (stationIterator.hasNext()) {
            val bucket = stationIterator.next().value
            val surfaceIterator = bucket.surface.entries.iterator()
            while (surfaceIterator.hasNext()) {
                if (surfaceIterator.next().key.isBefore(cutoff)) {
                    surfaceIterator.remove()
                    prunedSurface += 1
                }
            }
            val precipitationIterator = bucket.precipitation.entries.iterator()
            while (precipitationIterator.hasNext()) {
                if (precipitationIterator.next().key.end.isBefore(cutoff)) {
                    precipitationIterator.remove()
                    prunedPrecipitation += 1
                }
            }
            if (bucket.surface.isEmpty() && bucket.precipitation.isEmpty()) {
                stationIterator.remove()
                evictedStations += 1
            }
        }
        return ObservationPruneResult(
            prunedSurface = prunedSurface,
            prunedPrecipitation = prunedPrecipitation,
            evictedStations = evictedStations,
        )
    }
}

private fun validateForecastCoordinate(
    coordinate: ForecastCoordinate,
    forecast: SourceForecast,
) {
    require(
        forecast.location.latitude == coordinate.latitude &&
            forecast.location.longitude == coordinate.longitude
    ) {
        "Server verification forecast location must match privacy-reduced coordinate"
    }
    require(forecast.origin.provider != ForecastProvider.UNKNOWN) {
        "Server verification forecast provider must be known"
    }
    require(forecast.origin.modelFamily != ModelFamily.UNKNOWN) {
        "Server verification model family must be known"
    }
}

private fun SourceForecast.toStoredEvidence(
    coordinate: ForecastCoordinate,
    modelRun: Instant,
): ServerStoredForecastRun {
    val points = hourly.map { point ->
        val lead = Duration.between(modelRun, point.time)
        require(!lead.isNegative && lead <= MAX_VERIFICATION_LEAD) {
            "Server verification forecast lead must be within 0..72 hours"
        }
        ForecastVerificationPointEvidence(
            validTime = point.time,
            temperatureC = point.temperatureC,
            pressureSeaLevelHpa = point.pressureSeaLevelHpa,
            windSpeedMps = point.windSpeedMps,
            windDirectionDegrees = point.windDirectionDegrees,
            precipitationMm = point.precipitationMm,
            precipitationInterval = point.precipitationInterval,
        )
    }
    return ServerStoredForecastRun(
        evidence = ForecastVerificationRunEvidence(
            coordinate = coordinate,
            provider = origin.provider,
            modelFamily = origin.modelFamily,
            modelRun = modelRun,
            timeZoneId = location.timeZoneId,
            hourly = points,
        ),
        firstCapturedAt = origin.generatedAt,
        elevationMeters = location.elevationMeters,
    )
}

private fun SurfaceObservation.hasSurfaceValue(): Boolean =
    temperatureC != null ||
        pressureSeaLevelHpa != null ||
        windSpeedMps != null ||
        windDirectionDegrees != null

private fun SurfaceObservation.enrichWith(incoming: SurfaceObservation): SurfaceObservation {
    require(station == incoming.station && observedAt == incoming.observedAt) {
        "Server verification surface enrichment identity mismatch"
    }
    return SurfaceObservation(
        station = station,
        observedAt = observedAt,
        temperatureC = temperatureC ?: incoming.temperatureC,
        pressureSeaLevelHpa = pressureSeaLevelHpa ?: incoming.pressureSeaLevelHpa,
        windSpeedMps = windSpeedMps ?: incoming.windSpeedMps,
        windDirectionDegrees = windDirectionDegrees ?: incoming.windDirectionDegrees,
    )
}
