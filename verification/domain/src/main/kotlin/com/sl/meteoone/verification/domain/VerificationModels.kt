package com.sl.meteoone.verification.domain

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastInterval
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import java.time.Duration
import java.time.Instant
import java.time.Month
import java.time.ZoneId

enum class VerificationParameter {
    TEMPERATURE,
    PRESSURE,
    WIND,
    PRECIPITATION,
}

enum class LeadTimeBucket {
    H0_6,
    H6_24,
    H24_48,
    H48_72,
    ;

    companion object {
        private val SIX_HOURS = Duration.ofHours(6)
        private val TWENTY_FOUR_HOURS = Duration.ofHours(24)
        private val FORTY_EIGHT_HOURS = Duration.ofHours(48)
        private val SEVENTY_TWO_HOURS = Duration.ofHours(72)

        fun from(duration: Duration): LeadTimeBucket? {
            if (duration.isNegative) return null
            return when {
                duration <= SIX_HOURS -> H0_6
                duration <= TWENTY_FOUR_HOURS -> H6_24
                duration <= FORTY_EIGHT_HOURS -> H24_48
                duration <= SEVENTY_TWO_HOURS -> H48_72
                else -> null
            }
        }
    }
}

enum class MeteorologicalSeason {
    WINTER,
    SPRING,
    SUMMER,
    AUTUMN,
    ;

    companion object {
        fun from(month: Month): MeteorologicalSeason = when (month) {
            Month.DECEMBER,
            Month.JANUARY,
            Month.FEBRUARY,
            -> WINTER

            Month.MARCH,
            Month.APRIL,
            Month.MAY,
            -> SPRING

            Month.JUNE,
            Month.JULY,
            Month.AUGUST,
            -> SUMMER

            Month.SEPTEMBER,
            Month.OCTOBER,
            Month.NOVEMBER,
            -> AUTUMN
        }
    }
}

data class VerificationContext(
    val coordinate: ForecastCoordinate,
    val provider: ForecastProvider,
    val modelFamily: ModelFamily,
    val modelRun: Instant,
    val validTime: Instant,
    val timeZoneId: String,
) {
    init {
        require(provider != ForecastProvider.UNKNOWN) {
            "Verification provider must be known"
        }
        require(modelFamily != ModelFamily.UNKNOWN) {
            "Verification model family must be known"
        }
        require(!validTime.isBefore(modelRun)) {
            "Verification valid time must not precede the model run"
        }
        requireNotNull(LeadTimeBucket.from(Duration.between(modelRun, validTime))) {
            "Verification lead time must be within 0..72 hours"
        }
        ZoneId.of(timeZoneId)
    }

    val leadTime: Duration
        get() = Duration.between(modelRun, validTime)

    val leadBucket: LeadTimeBucket
        get() = requireNotNull(LeadTimeBucket.from(leadTime))

    val season: MeteorologicalSeason
        get() = MeteorologicalSeason.from(validTime.atZone(ZoneId.of(timeZoneId)).month)
}

data class ObservationStation(
    val sourceId: String,
    val stationId: String,
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double?,
) {
    init {
        require(sourceId.isNotBlank()) { "Observation source id must not be blank" }
        require(stationId.isNotBlank()) { "Observation station id must not be blank" }
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "Observation station latitude is invalid"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "Observation station longitude is invalid"
        }
        require(elevationMeters == null || elevationMeters.isFinite()) {
            "Observation station elevation must be finite when present"
        }
    }
}

data class SurfaceObservation(
    val station: ObservationStation,
    val observedAt: Instant,
    val temperatureC: Double?,
    val pressureSeaLevelHpa: Double?,
    val windSpeedMps: Double?,
    val windDirectionDegrees: Double?,
) {
    init {
        requireFiniteOrNull(temperatureC, "temperature")
        requireFiniteOrNull(pressureSeaLevelHpa, "sea-level pressure")
        requireFiniteOrNull(windSpeedMps, "wind speed")
        requireFiniteOrNull(windDirectionDegrees, "wind direction")
        require(windSpeedMps == null || windSpeedMps >= 0.0) {
            "Observation wind speed must not be negative"
        }
        require(
            windDirectionDegrees == null ||
                (windDirectionDegrees >= 0.0 && windDirectionDegrees < 360.0),
        ) {
            "Observation wind direction must be within [0, 360)"
        }
    }
}

data class PrecipitationObservation(
    val station: ObservationStation,
    val interval: ForecastInterval,
    val amountMm: Double,
) {
    init {
        require(amountMm.isFinite() && amountMm >= 0.0) {
            "Observed precipitation must be finite and non-negative"
        }
    }
}

sealed interface VerificationSample {
    val context: VerificationContext
    val station: ObservationStation
    val parameter: VerificationParameter
}

data class ScalarVerificationSample(
    override val context: VerificationContext,
    override val station: ObservationStation,
    override val parameter: VerificationParameter,
    val predicted: Double,
    val observed: Double,
    val error: ScalarError,
) : VerificationSample {
    init {
        require(
            parameter == VerificationParameter.TEMPERATURE ||
                parameter == VerificationParameter.PRESSURE,
        ) {
            "Scalar verification samples support only temperature or pressure"
        }
        require(predicted.isFinite() && observed.isFinite()) {
            "Scalar verification values must be finite"
        }
    }
}

data class WindVerificationSample(
    override val context: VerificationContext,
    override val station: ObservationStation,
    val predictedSpeedMps: Double,
    val predictedDirectionDegrees: Double?,
    val observedSpeedMps: Double,
    val observedDirectionDegrees: Double?,
    val error: WindVectorError,
) : VerificationSample {
    override val parameter: VerificationParameter = VerificationParameter.WIND
}

data class PrecipitationVerificationSample(
    override val context: VerificationContext,
    override val station: ObservationStation,
    val interval: ForecastInterval,
    val predictedMm: Double,
    val observedMm: Double,
    val error: ScalarError,
) : VerificationSample {
    override val parameter: VerificationParameter = VerificationParameter.PRECIPITATION

    init {
        require(predictedMm.isFinite() && predictedMm >= 0.0) {
            "Predicted precipitation must be finite and non-negative"
        }
        require(observedMm.isFinite() && observedMm >= 0.0) {
            "Observed precipitation must be finite and non-negative"
        }
    }
}

data class ScalarError(
    val signed: Double,
    val absolute: Double,
    val squared: Double,
) {
    init {
        require(signed.isFinite() && absolute.isFinite() && squared.isFinite()) {
            "Scalar error values must be finite"
        }
        require(absolute >= 0.0 && squared >= 0.0) {
            "Absolute and squared error must not be negative"
        }
    }
}

data class WindVectorError(
    val uErrorMps: Double,
    val vErrorMps: Double,
    val magnitudeMps: Double,
) {
    init {
        require(uErrorMps.isFinite() && vErrorMps.isFinite() && magnitudeMps.isFinite()) {
            "Wind vector error values must be finite"
        }
        require(magnitudeMps >= 0.0) {
            "Wind vector error magnitude must not be negative"
        }
    }
}

private fun requireFiniteOrNull(value: Double?, label: String) {
    require(value == null || value.isFinite()) {
        "Observation $label must be finite when present"
    }
}
