package com.sl.meteoone.core.model

import java.time.Instant

enum class ForecastProvider {
    OPEN_METEO,
    MET_NORWAY,
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
)

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
)

data class SourceForecast(
    val origin: ForecastOrigin,
    val location: ForecastLocation,
    val hourly: List<HourlyWeatherPoint>,
)

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
