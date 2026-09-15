package com.sl.meteoone.core.database

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastInterval
import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.FusedForecast
import com.sl.meteoone.core.model.FusedHourlyForecast
import com.sl.meteoone.core.model.HourlyWeatherPoint
import com.sl.meteoone.core.model.ModelAgreement
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

internal fun FusedForecast.toPersistedRows(coordinate: ForecastCoordinate): PersistedForecastRows {
    require(location.latitude == coordinate.latitude && location.longitude == coordinate.longitude) {
        "Persisted forecast location must match the privacy-reduced coordinate"
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
    return PersistedForecastRows(snapshot = snapshot, hourly = hourly)
}

internal fun Flow<ForecastSnapshotWithHourly?>.mapSnapshotRows(): Flow<FusedForecast?> =
    map { rows -> rows?.toModel() }

internal fun ForecastSnapshotWithHourly.toModel(): FusedForecast {
    val key = persistedCoordinateKey(snapshot)
    val coordinate = ForecastCoordinate(
        latitude = tenthsToDegrees(key.latitudeTenths),
        longitude = tenthsToDegrees(key.longitudeTenths),
    )
    val ordered = hourly.sortedBy { it.position }
    check(ordered.isNotEmpty()) { "Cached forecast snapshot contains no hourly rows" }
    check(ordered.map { it.position } == ordered.indices.toList()) {
        "Cached forecast snapshot positions are not contiguous"
    }
    check(ordered.all { it.coordinateKey == key.encoded }) {
        "Cached forecast rows do not match the snapshot coordinate key"
    }

    return try {
        FusedForecast(
            location = ForecastLocation(
                latitude = coordinate.latitude,
                longitude = coordinate.longitude,
                elevationMeters = snapshot.elevationMeters,
                timeZoneId = snapshot.timeZoneId,
            ),
            generatedAt = instant(snapshot.generatedAtEpochSecond, snapshot.generatedAtNano),
            hourly = ordered.map { it.toModel() },
        )
    } catch (error: IllegalArgumentException) {
        throw IllegalStateException("Cached forecast violates canonical model invariants", error)
    }
}

private fun ForecastHourlyEntity.toModel(): FusedHourlyForecast {
    val time = instant(timeEpochSecond, timeNano)
    return try {
        FusedHourlyForecast(
            weather = HourlyWeatherPoint(
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
                condition = enumValue<WeatherCondition>(condition, "weather condition"),
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
            ),
            providerCount = providerCount,
            independentEvidenceCount = independentEvidenceCount,
            agreement = enumValue<ModelAgreement>(agreement, "model agreement"),
        )
    } catch (error: IllegalArgumentException) {
        throw IllegalStateException("Cached hourly forecast violates canonical model invariants", error)
    }
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
