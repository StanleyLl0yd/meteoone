package com.sl.meteoone.forecast.data.grib

import com.sl.meteoone.core.model.ForecastInterval
import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastOrigin
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.HourlyWeatherPoint
import com.sl.meteoone.core.model.SourceForecast
import com.sl.meteoone.core.model.WeatherCondition
import com.sl.meteoone.forecast.data.source.OfficialProviderIdentity
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot

class OfficialGribForecastMapper {
    fun map(
        provider: ForecastProvider,
        modelRun: Instant,
        generatedAt: Instant,
        location: ForecastLocation,
        fields: List<DecodedGribField>,
    ): SourceForecast {
        require(fields.isNotEmpty()) { "At least one decoded GRIB field is required" }
        val modelFamily = requireNotNull(OfficialProviderIdentity.modelFamily(provider)) {
            "Provider $provider is not a direct official model source"
        }
        require(fields.all { !it.validTime.isBefore(modelRun) }) {
            "Decoded GRIB valid time must not precede the model run"
        }

        val hourly = fields
            .groupBy { it.validTime }
            .toSortedMap()
            .map { (validTime, timeFields) -> mapHour(validTime, timeFields) }

        return SourceForecast(
            origin = ForecastOrigin(
                provider = provider,
                modelFamily = modelFamily,
                modelRun = modelRun,
                generatedAt = generatedAt,
            ),
            location = location,
            hourly = hourly,
        )
    }

    private fun mapHour(
        validTime: Instant,
        fields: List<DecodedGribField>,
    ): HourlyWeatherPoint {
        val byParameter = fields.groupBy { it.parameter }
        require(byParameter.values.all { it.size == 1 }) {
            "Decoded GRIB contains duplicate parameters at $validTime"
        }
        fields.forEach(::validateUnitAndValue)

        fun field(parameter: GribForecastParameter): DecodedGribField? =
            byParameter[parameter]?.single()

        val temperatureC = field(GribForecastParameter.TEMPERATURE_2M)
            ?.value
            ?.kelvinToCelsius()
        val dewPointC = field(GribForecastParameter.DEW_POINT_2M)
            ?.value
            ?.kelvinToCelsius()
        val directHumidity = field(GribForecastParameter.RELATIVE_HUMIDITY_2M)?.value
        val humidityPercent = directHumidity ?: relativeHumidityPercent(
            temperatureC = temperatureC,
            dewPointC = dewPointC,
        )
        val u = field(GribForecastParameter.WIND_U_10M)?.value
        val v = field(GribForecastParameter.WIND_V_10M)?.value
        val wind = windVector(u = u, v = v)

        val gust = field(GribForecastParameter.WIND_GUST_10M)
        val precipitation = field(GribForecastParameter.PRECIPITATION_ACCUMULATION)

        return HourlyWeatherPoint(
            time = validTime,
            temperatureC = temperatureC,
            feelsLikeC = null,
            dewPointC = dewPointC,
            humidityPercent = humidityPercent,
            pressureSeaLevelHpa = field(GribForecastParameter.PRESSURE_MEAN_SEA_LEVEL)
                ?.value
                ?.div(PASCALS_PER_HECTOPASCAL),
            windSpeedMps = wind?.speedMps,
            windGustMps = gust?.value,
            windDirectionDegrees = wind?.directionDegrees,
            precipitationMm = precipitation?.value,
            precipitationProbabilityPercent = null,
            cloudCoverPercent = field(GribForecastParameter.TOTAL_CLOUD_COVER)?.value,
            visibilityMeters = field(GribForecastParameter.VISIBILITY)?.value,
            condition = WeatherCondition.UNKNOWN,
            windGustInterval = gust?.intervalStart?.let {
                ForecastInterval(start = it, end = validTime)
            },
            precipitationInterval = precipitation?.intervalStart?.let {
                ForecastInterval(start = it, end = validTime)
            },
        )
    }

    private fun validateUnitAndValue(field: DecodedGribField) {
        val expectedUnit = when (field.parameter) {
            GribForecastParameter.TEMPERATURE_2M,
            GribForecastParameter.DEW_POINT_2M,
            -> GribValueUnit.KELVIN

            GribForecastParameter.RELATIVE_HUMIDITY_2M,
            GribForecastParameter.TOTAL_CLOUD_COVER,
            -> GribValueUnit.PERCENT

            GribForecastParameter.PRESSURE_MEAN_SEA_LEVEL -> GribValueUnit.PASCAL
            GribForecastParameter.WIND_U_10M,
            GribForecastParameter.WIND_V_10M,
            GribForecastParameter.WIND_GUST_10M,
            -> GribValueUnit.METRES_PER_SECOND

            GribForecastParameter.PRECIPITATION_ACCUMULATION ->
                GribValueUnit.KILOGRAMS_PER_SQUARE_METRE

            GribForecastParameter.VISIBILITY -> GribValueUnit.METRES
        }
        require(field.unit == expectedUnit) {
            "${field.parameter} must use $expectedUnit, got ${field.unit}"
        }

        when (field.parameter) {
            GribForecastParameter.TEMPERATURE_2M -> {
                val valueC = field.value.kelvinToCelsius()
                require(valueC in MIN_TEMPERATURE_C..MAX_TEMPERATURE_C) {
                    "2 m temperature is outside the supported physical range"
                }
            }

            GribForecastParameter.DEW_POINT_2M -> {
                val valueC = field.value.kelvinToCelsius()
                require(valueC in MIN_DEW_POINT_C..MAX_DEW_POINT_C) {
                    "2 m dew point is outside the supported physical range"
                }
            }

            GribForecastParameter.RELATIVE_HUMIDITY_2M,
            GribForecastParameter.TOTAL_CLOUD_COVER,
            -> require(field.value in 0.0..100.0) {
                "Percent-valued GRIB field must be within 0..100"
            }

            GribForecastParameter.PRESSURE_MEAN_SEA_LEVEL ->
                require(field.value / PASCALS_PER_HECTOPASCAL in MIN_MSLP_HPA..MAX_MSLP_HPA) {
                    "Mean sea-level pressure is outside the supported physical range"
                }

            GribForecastParameter.WIND_U_10M,
            GribForecastParameter.WIND_V_10M,
            -> require(field.value in -MAX_WIND_COMPONENT_MPS..MAX_WIND_COMPONENT_MPS) {
                "10 m wind component is outside the supported physical range"
            }

            GribForecastParameter.WIND_GUST_10M ->
                require(field.value in 0.0..MAX_WIND_GUST_MPS) {
                    "10 m wind gust is outside the supported physical range"
                }

            GribForecastParameter.PRECIPITATION_ACCUMULATION ->
                require(field.value in 0.0..MAX_PRECIPITATION_MM) {
                    "Precipitation accumulation is outside the supported physical range"
                }

            GribForecastParameter.VISIBILITY ->
                require(field.value in 0.0..MAX_VISIBILITY_METRES) {
                    "Visibility is outside the supported physical range"
                }
        }
    }

    private fun Double.kelvinToCelsius(): Double = this - KELVIN_OFFSET

    private fun relativeHumidityPercent(
        temperatureC: Double?,
        dewPointC: Double?,
    ): Double? {
        if (temperatureC == null || dewPointC == null) return null

        val exponent =
            (MAGNUS_A * dewPointC / (MAGNUS_B_C + dewPointC)) -
                (MAGNUS_A * temperatureC / (MAGNUS_B_C + temperatureC))
        return (100.0 * exp(exponent)).coerceIn(0.0, 100.0)
    }

    private fun windVector(u: Double?, v: Double?): WindVector? {
        if (u == null || v == null) return null

        val speed = hypot(u, v)
        if (speed <= CALM_WIND_EPSILON_MPS) {
            return WindVector(speedMps = 0.0, directionDegrees = null)
        }

        val direction = normalizeDegrees(Math.toDegrees(atan2(-u, -v)))
        return WindVector(speedMps = speed, directionDegrees = direction)
    }

    private fun normalizeDegrees(value: Double): Double {
        val normalized = value % FULL_CIRCLE_DEGREES
        return if (normalized < 0.0) normalized + FULL_CIRCLE_DEGREES else normalized
    }

    private data class WindVector(
        val speedMps: Double,
        val directionDegrees: Double?,
    )

    private companion object {
        const val KELVIN_OFFSET = 273.15
        const val PASCALS_PER_HECTOPASCAL = 100.0
        const val FULL_CIRCLE_DEGREES = 360.0
        const val CALM_WIND_EPSILON_MPS = 1e-9

        // Magnus approximation over the operational near-surface temperature range.
        const val MAGNUS_A = 17.625
        const val MAGNUS_B_C = 243.04

        const val MIN_TEMPERATURE_C = -120.0
        const val MAX_TEMPERATURE_C = 80.0
        const val MIN_DEW_POINT_C = -150.0
        const val MAX_DEW_POINT_C = 80.0
        const val MIN_MSLP_HPA = 500.0
        const val MAX_MSLP_HPA = 1200.0
        const val MAX_WIND_COMPONENT_MPS = 200.0
        const val MAX_WIND_GUST_MPS = 200.0
        const val MAX_PRECIPITATION_MM = 5000.0
        const val MAX_VISIBILITY_METRES = 1_000_000.0
    }
}
