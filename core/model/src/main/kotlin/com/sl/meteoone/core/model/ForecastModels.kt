package com.sl.meteoone.core.model

import java.time.Duration
import java.time.Instant

enum class ForecastProvider {
    OPEN_METEO,
    MET_NORWAY,
    NOAA_NOMADS,
    ECMWF_OPEN_DATA,
    DWD_OPEN_DATA,
    UNKNOWN,
}

enum class ModelFamily {
    ECMWF_IFS,
    DWD_ICON,
    NOAA_GFS,
    UNKNOWN,
}

data class ForecastOrigin(
    val provider: ForecastProvider,
    val modelFamily: ModelFamily,
    val modelRun: Instant?,
    val generatedAt: Instant,
) {
    init {
        require(modelRun == null || !generatedAt.isBefore(modelRun)) {
            "Forecast generation time must not precede the model run"
        }
    }
}

data class ForecastLocation(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Int?,
    val timeZoneId: String,
) {
    init {
        require(latitude in -90.0..90.0)
        require(longitude in -180.0..180.0)
        require(timeZoneId.isNotBlank())
    }
}

data class ForecastInterval(
    val start: Instant,
    val end: Instant,
) {
    init {
        require(start.isBefore(end)) { "Forecast interval start must be before its end" }
    }

    val duration: Duration
        get() = Duration.between(start, end)
}

enum class WeatherCondition {
    UNKNOWN,
    CLEAR,
    PARTLY_CLOUDY,
    CLOUDY,
    FOG,
    RAIN,
    HEAVY_RAIN,
    SNOW,
    SLEET,
    THUNDERSTORM,
}

data class HourlyWeatherPoint(
    val time: Instant,
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
    val condition: WeatherCondition = WeatherCondition.UNKNOWN,
    val windGustInterval: ForecastInterval? = null,
    val precipitationInterval: ForecastInterval? = null,
) {
    init {
        require(
            temperatureC.isNullOrFinite() &&
                feelsLikeC.isNullOrFinite() &&
                dewPointC.isNullOrFinite() &&
                humidityPercent.isNullOrFinite() &&
                pressureSeaLevelHpa.isNullOrFinite() &&
                windSpeedMps.isNullOrFinite() &&
                windGustMps.isNullOrFinite() &&
                windDirectionDegrees.isNullOrFinite() &&
                precipitationMm.isNullOrFinite() &&
                precipitationProbabilityPercent.isNullOrFinite() &&
                cloudCoverPercent.isNullOrFinite() &&
                visibilityMeters.isNullOrFinite(),
        ) {
            "Hourly weather numeric values must be finite when present"
        }
        require(windGustInterval == null || windGustMps != null) {
            "Wind-gust interval requires a wind-gust value"
        }
        require(precipitationInterval == null || precipitationMm != null) {
            "Precipitation interval requires a precipitation value"
        }
        require(windGustInterval == null || windGustInterval.end == time) {
            "Wind-gust interval must end at the weather-point time"
        }
        require(precipitationInterval == null || precipitationInterval.end == time) {
            "Precipitation interval must end at the weather-point time"
        }
    }
}

private fun Double?.isNullOrFinite(): Boolean = this?.isFinite() ?: true

data class SourceForecast(
    val origin: ForecastOrigin,
    val location: ForecastLocation,
    val hourly: List<HourlyWeatherPoint>,
) {
    init {
        require(hourly.isNotEmpty()) { "Source forecast must contain at least one hourly point" }
        require(hourly.zipWithNext().all { (previous, next) -> previous.time.isBefore(next.time) }) {
            "Source forecast hourly timestamps must be strictly increasing and unique"
        }
    }
}

enum class ModelAgreement {
    HIGH,
    MEDIUM,
    LOW,
    INSUFFICIENT,
}

data class FusedHourlyForecast(
    val weather: HourlyWeatherPoint,
    val providerCount: Int,
    val independentEvidenceCount: Int,
    val agreement: ModelAgreement,
)

data class FusedForecast(
    val location: ForecastLocation,
    val generatedAt: Instant,
    val hourly: List<FusedHourlyForecast>,
)
