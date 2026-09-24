package com.sl.meteoone.backend.contract

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastInterval
import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastOrigin
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ForecastTarget
import com.sl.meteoone.core.model.FusedForecast
import com.sl.meteoone.core.model.FusedHourlyForecast
import com.sl.meteoone.core.model.HourlyWeatherPoint
import com.sl.meteoone.core.model.ModelAgreement
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.model.SourceForecast
import com.sl.meteoone.core.model.WeatherCondition
import java.math.BigDecimal
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val FORECAST_API_VERSION: Int = 1
const val FORECAST_API_PATH: String = "/v1/forecast"

object ForecastWireJson {
    val json: Json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        encodeDefaults = true
        explicitNulls = true
        allowSpecialFloatingPointValues = false
    }
}

@Serializable
data class ForecastRequestDto(
    val latitudeTenths: Int,
    val longitudeTenths: Int,
    val elevationMeters: Int?,
    val timeZoneId: String,
) {
    fun toDomain(): ForecastTarget =
        ForecastTarget(
            coordinate = coordinateFromTenths(latitudeTenths, longitudeTenths),
            elevationMeters = elevationMeters,
            timeZoneId = timeZoneId,
        )

    companion object {
        fun fromDomain(target: ForecastTarget): ForecastRequestDto =
            ForecastRequestDto(
                latitudeTenths = canonicalTenths(target.coordinate.latitude),
                longitudeTenths = canonicalTenths(target.coordinate.longitude),
                elevationMeters = target.elevationMeters,
                timeZoneId = target.timeZoneId,
            )
    }
}

@Serializable
data class ForecastSourceIdentityDto(
    val provider: String,
    val modelFamily: String,
) {
    fun toDomain(): ForecastSourceIdentityValue =
        ForecastSourceIdentityValue(
            provider = parseEnum(provider, "forecast provider"),
            modelFamily = parseEnum(modelFamily, "model family"),
        )

    companion object {
        fun fromDomain(identity: ForecastSourceIdentityValue): ForecastSourceIdentityDto =
            ForecastSourceIdentityDto(
                provider = identity.provider.name,
                modelFamily = identity.modelFamily.name,
            )
    }
}

data class ForecastSourceIdentityValue(
    val provider: ForecastProvider,
    val modelFamily: ModelFamily,
)

@Serializable
data class ForecastOriginDto(
    val provider: String,
    val modelFamily: String,
    val modelRun: String?,
    val generatedAt: String,
) {
    fun toDomain(): ForecastOrigin =
        ForecastOrigin(
            provider = parseEnum(provider, "forecast provider"),
            modelFamily = parseEnum(modelFamily, "model family"),
            modelRun = modelRun?.let { parseInstant(it, "modelRun") },
            generatedAt = parseInstant(generatedAt, "generatedAt"),
        )

    companion object {
        fun fromDomain(origin: ForecastOrigin): ForecastOriginDto =
            ForecastOriginDto(
                provider = origin.provider.name,
                modelFamily = origin.modelFamily.name,
                modelRun = origin.modelRun?.toString(),
                generatedAt = origin.generatedAt.toString(),
            )
    }
}

@Serializable
data class ForecastLocationDto(
    val latitudeTenths: Int,
    val longitudeTenths: Int,
    val elevationMeters: Int?,
    val timeZoneId: String,
) {
    fun toDomain(): ForecastLocation {
        val coordinate = coordinateFromTenths(latitudeTenths, longitudeTenths)
        return ForecastLocation(
            latitude = coordinate.latitude,
            longitude = coordinate.longitude,
            elevationMeters = elevationMeters,
            timeZoneId = timeZoneId,
        )
    }

    companion object {
        fun fromDomain(location: ForecastLocation): ForecastLocationDto {
            val coordinate = ForecastCoordinate(location.latitude, location.longitude)
            return ForecastLocationDto(
                latitudeTenths = canonicalTenths(coordinate.latitude),
                longitudeTenths = canonicalTenths(coordinate.longitude),
                elevationMeters = location.elevationMeters,
                timeZoneId = location.timeZoneId,
            )
        }
    }
}

@Serializable
data class ForecastIntervalDto(
    val start: String,
    val end: String,
) {
    fun toDomain(): ForecastInterval =
        ForecastInterval(
            start = parseInstant(start, "interval start"),
            end = parseInstant(end, "interval end"),
        )

    companion object {
        fun fromDomain(interval: ForecastInterval): ForecastIntervalDto =
            ForecastIntervalDto(
                start = interval.start.toString(),
                end = interval.end.toString(),
            )
    }
}

@Serializable
data class HourlyWeatherPointDto(
    val time: String,
    val temperatureC: Double?,
    val feelsLikeC: Double?,
    val dewPointC: Double?,
    val humidityPercent: Double?,
    val pressureSeaLevelHpa: Double?,
    val windSpeedMps: Double?,
    val windGustMps: Double?,
    val windDirectionDegrees: Double?,
    val precipitationMm: Double?,
    val precipitationProbabilityPercent: Double?,
    val cloudCoverPercent: Double?,
    val visibilityMeters: Double?,
    val condition: String,
    val windGustInterval: ForecastIntervalDto?,
    val precipitationInterval: ForecastIntervalDto?,
) {
    fun toDomain(): HourlyWeatherPoint =
        HourlyWeatherPoint(
            time = parseInstant(time, "weather time"),
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
            condition = parseEnum(condition, "weather condition"),
            windGustInterval = windGustInterval?.toDomain(),
            precipitationInterval = precipitationInterval?.toDomain(),
        )

    companion object {
        fun fromDomain(point: HourlyWeatherPoint): HourlyWeatherPointDto =
            HourlyWeatherPointDto(
                time = point.time.toString(),
                temperatureC = point.temperatureC,
                feelsLikeC = point.feelsLikeC,
                dewPointC = point.dewPointC,
                humidityPercent = point.humidityPercent,
                pressureSeaLevelHpa = point.pressureSeaLevelHpa,
                windSpeedMps = point.windSpeedMps,
                windGustMps = point.windGustMps,
                windDirectionDegrees = point.windDirectionDegrees,
                precipitationMm = point.precipitationMm,
                precipitationProbabilityPercent = point.precipitationProbabilityPercent,
                cloudCoverPercent = point.cloudCoverPercent,
                visibilityMeters = point.visibilityMeters,
                condition = point.condition.name,
                windGustInterval = point.windGustInterval?.let(ForecastIntervalDto::fromDomain),
                precipitationInterval =
                    point.precipitationInterval?.let(ForecastIntervalDto::fromDomain),
            )
    }
}

@Serializable
data class SourceForecastDto(
    val origin: ForecastOriginDto,
    val location: ForecastLocationDto,
    val hourly: List<HourlyWeatherPointDto>,
) {
    fun toDomain(): SourceForecast =
        SourceForecast(
            origin = origin.toDomain(),
            location = location.toDomain(),
            hourly = hourly.map(HourlyWeatherPointDto::toDomain),
        )

    companion object {
        fun fromDomain(source: SourceForecast): SourceForecastDto =
            SourceForecastDto(
                origin = ForecastOriginDto.fromDomain(source.origin),
                location = ForecastLocationDto.fromDomain(source.location),
                hourly = source.hourly.map(HourlyWeatherPointDto::fromDomain),
            )
    }
}

@Serializable
data class FusedHourlyForecastDto(
    val weather: HourlyWeatherPointDto,
    val providerCount: Int,
    val independentEvidenceCount: Int,
    val agreement: String,
) {
    fun toDomain(): FusedHourlyForecast =
        FusedHourlyForecast(
            weather = weather.toDomain(),
            providerCount = providerCount,
            independentEvidenceCount = independentEvidenceCount,
            agreement = parseEnum(agreement, "model agreement"),
        )

    companion object {
        fun fromDomain(forecast: FusedHourlyForecast): FusedHourlyForecastDto =
            FusedHourlyForecastDto(
                weather = HourlyWeatherPointDto.fromDomain(forecast.weather),
                providerCount = forecast.providerCount,
                independentEvidenceCount = forecast.independentEvidenceCount,
                agreement = forecast.agreement.name,
            )
    }
}

@Serializable
data class FusedForecastDto(
    val location: ForecastLocationDto,
    val generatedAt: String,
    val hourly: List<FusedHourlyForecastDto>,
) {
    fun toDomain(): FusedForecast =
        FusedForecast(
            location = location.toDomain(),
            generatedAt = parseInstant(generatedAt, "fused generatedAt"),
            hourly = hourly.map(FusedHourlyForecastDto::toDomain),
        )

    companion object {
        fun fromDomain(forecast: FusedForecast): FusedForecastDto =
            FusedForecastDto(
                location = ForecastLocationDto.fromDomain(forecast.location),
                generatedAt = forecast.generatedAt.toString(),
                hourly = forecast.hourly.map(FusedHourlyForecastDto::fromDomain),
            )
    }
}

@Serializable
data class ForecastResponseDto(
    val apiVersion: Int = FORECAST_API_VERSION,
    val forecast: FusedForecastDto,
    val sourceForecasts: List<SourceForecastDto>,
    val failedSources: List<ForecastSourceIdentityDto>,
) {
    fun toDomain(): DecodedForecastResponse {
        require(apiVersion == FORECAST_API_VERSION) {
            "Unsupported forecast API version: $apiVersion"
        }
        return DecodedForecastResponse(
            forecast = forecast.toDomain(),
            sourceForecasts = sourceForecasts.map(SourceForecastDto::toDomain),
            failedSources = failedSources.map(ForecastSourceIdentityDto::toDomain),
        )
    }

    companion object {
        fun fromDomain(
            forecast: FusedForecast,
            sourceForecasts: List<SourceForecast>,
            failedSources: List<ForecastSourceIdentityValue>,
        ): ForecastResponseDto =
            ForecastResponseDto(
                forecast = FusedForecastDto.fromDomain(forecast),
                sourceForecasts = sourceForecasts.map(SourceForecastDto::fromDomain),
                failedSources = failedSources.map(ForecastSourceIdentityDto::fromDomain),
            ).also { response ->
                response.toDomain()
            }
    }
}

data class DecodedForecastResponse(
    val forecast: FusedForecast,
    val sourceForecasts: List<SourceForecast>,
    val failedSources: List<ForecastSourceIdentityValue>,
) {
    init {
        require(sourceForecasts.all { source -> source.location == forecast.location }) {
            "Source forecast locations must match the fused forecast location"
        }

        val successful = sourceForecasts.map { source ->
            ForecastSourceIdentityValue(
                provider = source.origin.provider,
                modelFamily = source.origin.modelFamily,
            )
        }
        require(successful.size == successful.toSet().size) {
            "Successful source identities must be unique"
        }
        require(failedSources.size == failedSources.toSet().size) {
            "Failed source identities must be unique"
        }
        require(successful.toSet().intersect(failedSources.toSet()).isEmpty()) {
            "A source identity cannot be both successful and failed"
        }
    }
}

private fun coordinateFromTenths(
    latitudeTenths: Int,
    longitudeTenths: Int,
): ForecastCoordinate {
    require(latitudeTenths in -900..900) {
        "Forecast latitude tenths must be within [-900, 900]"
    }
    require(longitudeTenths in -1800..1799) {
        "Forecast longitude tenths must be within [-1800, 1799]"
    }
    return ForecastCoordinate(
        latitude = latitudeTenths / 10.0,
        longitude = longitudeTenths / 10.0,
    )
}

private fun canonicalTenths(value: Double): Int =
    BigDecimal.valueOf(value).movePointRight(1).intValueExact()

private fun parseInstant(
    value: String,
    label: String,
): Instant = try {
    Instant.parse(value)
} catch (error: DateTimeParseException) {
    throw IllegalArgumentException("$label must be an ISO-8601 UTC instant", error)
}

private inline fun <reified T : Enum<T>> parseEnum(
    value: String,
    label: String,
): T =
    enumValues<T>().firstOrNull { candidate -> candidate.name == value }
        ?: throw IllegalArgumentException("Unknown $label: $value")
