package com.sl.meteoone.core.database

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastInterval
import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastOrigin
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.FusedForecast
import com.sl.meteoone.core.model.FusedHourlyForecast
import com.sl.meteoone.core.model.HourlyWeatherPoint
import com.sl.meteoone.core.model.ModelAgreement
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.model.SourceForecast
import com.sl.meteoone.core.model.WeatherCondition
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal data class PersistedCoordinateKey(
    val encoded: String,
    val latitudeTenths: Int,
    val longitudeTenths: Int,
)

internal data class PersistedForecastRows(
    val snapshot: ForecastSnapshotEntity,
    val hourly: List<ForecastHourlyEntity>,
    val sources: List<ForecastSourceEntity>,
    val sourceHourly: List<ForecastSourceHourlyEntity>,
    val failedSources: List<ForecastFailedSourceEntity>,
)

internal fun ForecastCoordinate.toPersistedKey(): PersistedCoordinateKey {
    val latitudeTenths = BigDecimal.valueOf(latitude).movePointRight(1).intValueExact()
    val longitudeTenths = BigDecimal.valueOf(longitude).movePointRight(1).intValueExact()
    return PersistedCoordinateKey(
        encoded = "$latitudeTenths:$longitudeTenths",
        latitudeTenths = latitudeTenths,
        longitudeTenths = longitudeTenths,
    )
}

internal fun FusedForecast.toPersistedRows(
    coordinate: ForecastCoordinate,
    sourceForecasts: List<SourceForecast>,
    failedSources: List<StoredForecastSourceIdentity>,
): PersistedForecastRows {
    require(location.latitude == coordinate.latitude && location.longitude == coordinate.longitude) {
        "Persisted forecast location must match the privacy-reduced coordinate"
    }

    val successfulIdentities = sourceForecasts.map { source ->
        StoredForecastSourceIdentity(
            provider = source.origin.provider,
            modelFamily = source.origin.modelFamily,
        )
    }
    require(successfulIdentities.size == successfulIdentities.toSet().size) {
        "Persisted successful forecast source identities must be unique"
    }
    require(failedSources.size == failedSources.toSet().size) {
        "Persisted failed forecast source identities must be unique"
    }
    require(successfulIdentities.toSet().intersect(failedSources.toSet()).isEmpty()) {
        "Persisted forecast source identity cannot be both successful and failed"
    }
    sourceForecasts.forEach { source ->
        require(source.location == location) {
            "Persisted source forecast location must match the fused forecast location"
        }
    }

    val key = coordinate.toPersistedKey()
    val snapshot = ForecastSnapshotEntity(
        coordinateKey = key.encoded,
        latitudeTenths = key.latitudeTenths,
        longitudeTenths = key.longitudeTenths,
        generatedAtEpochSecond = generatedAt.epochSecond,
        generatedAtNano = generatedAt.nano,
        elevationMeters = location.elevationMeters,
        timeZoneId = location.timeZoneId,
    )
    val hourly = hourly.mapIndexed { position, fused ->
        val weather = fused.weather
        ForecastHourlyEntity(
            coordinateKey = key.encoded,
            position = position,
            timeEpochSecond = weather.time.epochSecond,
            timeNano = weather.time.nano,
            temperatureC = weather.temperatureC,
            feelsLikeC = weather.feelsLikeC,
            dewPointC = weather.dewPointC,
            humidityPercent = weather.humidityPercent,
            pressureSeaLevelHpa = weather.pressureSeaLevelHpa,
            windSpeedMps = weather.windSpeedMps,
            windGustMps = weather.windGustMps,
            windDirectionDegrees = weather.windDirectionDegrees,
            precipitationMm = weather.precipitationMm,
            precipitationProbabilityPercent = weather.precipitationProbabilityPercent,
            cloudCoverPercent = weather.cloudCoverPercent,
            visibilityMeters = weather.visibilityMeters,
            condition = weather.condition.name,
            windGustIntervalStartEpochSecond = weather.windGustInterval?.start?.epochSecond,
            windGustIntervalStartNano = weather.windGustInterval?.start?.nano,
            precipitationIntervalStartEpochSecond = weather.precipitationInterval?.start?.epochSecond,
            precipitationIntervalStartNano = weather.precipitationInterval?.start?.nano,
            providerCount = fused.providerCount,
            independentEvidenceCount = fused.independentEvidenceCount,
            agreement = fused.agreement.name,
        )
    }

    val sourceRows = sourceForecasts.map { source ->
        ForecastSourceEntity(
            coordinateKey = key.encoded,
            provider = source.origin.provider.name,
            modelFamily = source.origin.modelFamily.name,
            modelRunEpochSecond = source.origin.modelRun?.epochSecond,
            modelRunNano = source.origin.modelRun?.nano,
            generatedAtEpochSecond = source.origin.generatedAt.epochSecond,
            generatedAtNano = source.origin.generatedAt.nano,
        )
    }
    val sourceHourlyRows = sourceForecasts.flatMap { source ->
        source.hourly.mapIndexed { position, weather ->
            ForecastSourceHourlyEntity(
                coordinateKey = key.encoded,
                provider = source.origin.provider.name,
                modelFamily = source.origin.modelFamily.name,
                position = position,
                timeEpochSecond = weather.time.epochSecond,
                timeNano = weather.time.nano,
                temperatureC = weather.temperatureC,
                feelsLikeC = weather.feelsLikeC,
                dewPointC = weather.dewPointC,
                humidityPercent = weather.humidityPercent,
                pressureSeaLevelHpa = weather.pressureSeaLevelHpa,
                windSpeedMps = weather.windSpeedMps,
                windGustMps = weather.windGustMps,
                windDirectionDegrees = weather.windDirectionDegrees,
                precipitationMm = weather.precipitationMm,
                precipitationProbabilityPercent = weather.precipitationProbabilityPercent,
                cloudCoverPercent = weather.cloudCoverPercent,
                visibilityMeters = weather.visibilityMeters,
                condition = weather.condition.name,
                windGustIntervalStartEpochSecond = weather.windGustInterval?.start?.epochSecond,
                windGustIntervalStartNano = weather.windGustInterval?.start?.nano,
                precipitationIntervalStartEpochSecond = weather.precipitationInterval?.start?.epochSecond,
                precipitationIntervalStartNano = weather.precipitationInterval?.start?.nano,
            )
        }
    }
    val failedRows = failedSources.map { identity ->
        ForecastFailedSourceEntity(
            coordinateKey = key.encoded,
            provider = identity.provider.name,
            modelFamily = identity.modelFamily.name,
        )
    }
    return PersistedForecastRows(
        snapshot = snapshot,
        hourly = hourly,
        sources = sourceRows,
        sourceHourly = sourceHourlyRows,
        failedSources = failedRows,
    )
}

internal fun Flow<ForecastSnapshotWithEvidence?>.mapSnapshotRows(): Flow<StoredForecastSnapshot?> =
    map { rows -> rows?.toModel() }

internal fun ForecastSnapshotWithEvidence.toModel(): StoredForecastSnapshot {
    val key = persistedCoordinateKey(snapshot)
    val coordinate = ForecastCoordinate(
        latitude = tenthsToDegrees(key.latitudeTenths),
        longitude = tenthsToDegrees(key.longitudeTenths),
    )
    val location = ForecastLocation(
        latitude = coordinate.latitude,
        longitude = coordinate.longitude,
        elevationMeters = snapshot.elevationMeters,
        timeZoneId = snapshot.timeZoneId,
    )
    val ordered = hourly.sortedBy { it.position }
    check(ordered.isNotEmpty()) { "Cached forecast snapshot contains no hourly rows" }
    check(ordered.map { it.position } == ordered.indices.toList()) {
        "Cached forecast snapshot positions are not contiguous"
    }
    check(ordered.all { it.coordinateKey == key.encoded }) {
        "Cached forecast rows do not match the snapshot coordinate key"
    }

    val fused = try {
        FusedForecast(
            location = location,
            generatedAt = instant(snapshot.generatedAtEpochSecond, snapshot.generatedAtNano),
            hourly = ordered.map { it.toFusedModel() },
        )
    } catch (error: IllegalArgumentException) {
        throw IllegalStateException("Cached forecast violates canonical model invariants", error)
    }

    val sourceIdentities = sources.map { it.provider to it.modelFamily }
    check(sourceIdentities.size == sourceIdentities.toSet().size) {
        "Cached successful forecast source identities are duplicated"
    }
    val sourceHourlyByIdentity = sourceHourly.groupBy { it.provider to it.modelFamily }
    check(sourceHourlyByIdentity.keys == sourceIdentities.toSet()) {
        "Cached source hourly rows do not match source metadata exactly"
    }
    check(sources.all { it.coordinateKey == key.encoded }) {
        "Cached source metadata does not match the snapshot coordinate key"
    }
    check(sourceHourly.all { it.coordinateKey == key.encoded }) {
        "Cached source hourly rows do not match the snapshot coordinate key"
    }

    val sourceForecasts = sources.map { source ->
        val identity = source.provider to source.modelFamily
        val rows = requireNotNull(sourceHourlyByIdentity[identity])
            .sortedBy { it.position }
        check(rows.map { it.position } == rows.indices.toList()) {
            "Cached source hourly positions are not contiguous"
        }
        check(rows.all { it.provider == source.provider && it.modelFamily == source.modelFamily }) {
            "Cached source hourly provenance does not match source metadata"
        }
        source.toModel(location, rows)
    }

    val failed = failedSources.map { row ->
        check(row.coordinateKey == key.encoded) {
            "Cached failed source identity does not match the snapshot coordinate key"
        }
        StoredForecastSourceIdentity(
            provider = enumValue(row.provider, "forecast provider"),
            modelFamily = enumValue(row.modelFamily, "model family"),
        )
    }
    check(failed.size == failed.toSet().size) {
        "Cached failed forecast source identities are duplicated"
    }
    val successful = sourceForecasts.map { source ->
        StoredForecastSourceIdentity(source.origin.provider, source.origin.modelFamily)
    }
    check(successful.toSet().intersect(failed.toSet()).isEmpty()) {
        "Cached source identity cannot be both successful and failed"
    }

    return StoredForecastSnapshot(
        forecast = fused,
        sourceForecasts = sourceForecasts,
        failedSources = failed,
    )
}

private fun ForecastHourlyEntity.toFusedModel(): FusedHourlyForecast {
    val time = instant(timeEpochSecond, timeNano)
    return try {
        FusedHourlyForecast(
            weather = toWeatherPoint(time),
            providerCount = providerCount,
            independentEvidenceCount = independentEvidenceCount,
            agreement = enumValue<ModelAgreement>(agreement, "model agreement"),
        )
    } catch (error: IllegalArgumentException) {
        throw IllegalStateException("Cached hourly forecast violates canonical model invariants", error)
    }
}

private fun ForecastHourlyEntity.toWeatherPoint(time: Instant): HourlyWeatherPoint =
    HourlyWeatherPoint(
        time = time,
        temperatureC = temperatureC,
        feelsLikeC = feelsLikeC,
        dewPointC = dewPointC,
        humidityPercent = humidityPercent,
        pressureSeaLevelHpa = pressureSeaLevelHpa,
        windSpeedMps = windSpeedMps,
        windGustMps = windGustMps,
        windDirectionDegrees = windDirectionDegrees,
        precipitationMm = precipitationMm,
        precipitationProbabilityPercent = precipitationProbabilityPercent,
        cloudCoverPercent = cloudCoverPercent,
        visibilityMeters = visibilityMeters,
        condition = enumValue(condition, "weather condition"),
        windGustInterval = interval(
            startEpochSecond = windGustIntervalStartEpochSecond,
            startNano = windGustIntervalStartNano,
            end = time,
            label = "wind-gust",
        ),
        precipitationInterval = interval(
            startEpochSecond = precipitationIntervalStartEpochSecond,
            startNano = precipitationIntervalStartNano,
            end = time,
            label = "precipitation",
        ),
    )

private fun ForecastSourceEntity.toModel(
    location: ForecastLocation,
    rows: List<ForecastSourceHourlyEntity>,
): SourceForecast {
    check((modelRunEpochSecond == null) == (modelRunNano == null)) {
        "Cached source model-run timestamp is incomplete"
    }
    val modelRun = if (modelRunEpochSecond == null || modelRunNano == null) {
        null
    } else {
        instant(modelRunEpochSecond, modelRunNano)
    }
    return try {
        SourceForecast(
            origin = ForecastOrigin(
                provider = enumValue(provider, "forecast provider"),
                modelFamily = enumValue(modelFamily, "model family"),
                modelRun = modelRun,
                generatedAt = instant(generatedAtEpochSecond, generatedAtNano),
            ),
            location = location,
            hourly = rows.map { it.toWeatherPoint() },
        )
    } catch (error: IllegalArgumentException) {
        throw IllegalStateException("Cached source forecast violates canonical model invariants", error)
    }
}

private fun ForecastSourceHourlyEntity.toWeatherPoint(): HourlyWeatherPoint {
    val time = instant(timeEpochSecond, timeNano)
    return HourlyWeatherPoint(
        time = time,
        temperatureC = temperatureC,
        feelsLikeC = feelsLikeC,
        dewPointC = dewPointC,
        humidityPercent = humidityPercent,
        pressureSeaLevelHpa = pressureSeaLevelHpa,
        windSpeedMps = windSpeedMps,
        windGustMps = windGustMps,
        windDirectionDegrees = windDirectionDegrees,
        precipitationMm = precipitationMm,
        precipitationProbabilityPercent = precipitationProbabilityPercent,
        cloudCoverPercent = cloudCoverPercent,
        visibilityMeters = visibilityMeters,
        condition = enumValue(condition, "weather condition"),
        windGustInterval = interval(
            startEpochSecond = windGustIntervalStartEpochSecond,
            startNano = windGustIntervalStartNano,
            end = time,
            label = "wind-gust",
        ),
        precipitationInterval = interval(
            startEpochSecond = precipitationIntervalStartEpochSecond,
            startNano = precipitationIntervalStartNano,
            end = time,
            label = "precipitation",
        ),
    )
}

private fun persistedCoordinateKey(snapshot: ForecastSnapshotEntity): PersistedCoordinateKey {
    val encoded = "${snapshot.latitudeTenths}:${snapshot.longitudeTenths}"
    check(snapshot.coordinateKey == encoded) {
        "Cached forecast coordinate key does not match its integer coordinates"
    }
    return PersistedCoordinateKey(
        encoded = encoded,
        latitudeTenths = snapshot.latitudeTenths,
        longitudeTenths = snapshot.longitudeTenths,
    )
}

private fun tenthsToDegrees(value: Int): Double =
    BigDecimal.valueOf(value.toLong()).movePointLeft(1).toDouble()

private fun instant(epochSecond: Long, nano: Int): Instant {
    check(nano in 0..999_999_999) { "Cached timestamp nanoseconds are out of range" }
    return Instant.ofEpochSecond(epochSecond, nano.toLong())
}

private fun interval(
    startEpochSecond: Long?,
    startNano: Int?,
    end: Instant,
    label: String,
): ForecastInterval? {
    check((startEpochSecond == null) == (startNano == null)) {
        "Cached $label interval start is incomplete"
    }
    if (startEpochSecond == null || startNano == null) return null
    return ForecastInterval(
        start = instant(startEpochSecond, startNano),
        end = end,
    )
}

private inline fun <reified T : Enum<T>> enumValue(
    name: String,
    label: String,
): T = enumValues<T>().firstOrNull { it.name == name }
    ?: throw IllegalStateException("Cached $label is unknown: $name")
