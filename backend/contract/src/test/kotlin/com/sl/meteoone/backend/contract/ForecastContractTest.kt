package com.sl.meteoone.backend.contract

import com.sl.meteoone.core.model.ForecastInterval
import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastOrigin
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ForecastTarget
import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.FusedForecast
import com.sl.meteoone.core.model.FusedHourlyForecast
import com.sl.meteoone.core.model.HourlyWeatherPoint
import com.sl.meteoone.core.model.ModelAgreement
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.model.SourceForecast
import com.sl.meteoone.core.model.WeatherCondition
import java.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForecastContractTest {
    @Test
    fun requestUsesIntegerTenthsAndRoundTripsForecastTarget() {
        val target = ForecastTarget(
            coordinate = ForecastCoordinate(59.9, 30.3),
            elevationMeters = 15,
            timeZoneId = "Europe/Moscow",
        )

        val dto = ForecastRequestDto.fromDomain(target)
        val json = ForecastWireJson.json.encodeToString(dto)

        assertTrue(json.contains("\"latitudeTenths\":599"))
        assertTrue(json.contains("\"longitudeTenths\":303"))
        assertFalse(json.contains("59.9"))
        assertEquals(
            target,
            ForecastWireJson.json.decodeFromString<ForecastRequestDto>(json).toDomain(),
        )
    }

    @Test
    fun requestRejectsNonCanonicalWireCoordinate() {
        assertFailsWith<IllegalArgumentException> {
            ForecastRequestDto(
                latitudeTenths = 901,
                longitudeTenths = 0,
                elevationMeters = null,
                timeZoneId = "UTC",
            ).toDomain()
        }
        assertFailsWith<IllegalArgumentException> {
            ForecastRequestDto(
                latitudeTenths = 0,
                longitudeTenths = 1800,
                elevationMeters = null,
                timeZoneId = "UTC",
            ).toDomain()
        }
    }

    @Test
    fun strictJsonRejectsUnknownRequestFields() {
        assertFailsWith<SerializationException> {
            ForecastWireJson.json.decodeFromString<ForecastRequestDto>(
                """{"latitudeTenths":599,"longitudeTenths":303,"elevationMeters":null,"timeZoneId":"UTC","rawLatitude":59.934}""",
            )
        }
    }

    @Test
    fun responseRoundTripsCurrentForecastAndComparisonEvidence() {
        val response = responseFixture()
        val dto = ForecastResponseDto.fromDomain(
            forecast = response.forecast,
            sourceForecasts = response.sourceForecasts,
            failedSources = response.failedSources,
        )

        val json = ForecastWireJson.json.encodeToString(dto)
        val decoded = ForecastWireJson.json
            .decodeFromString<ForecastResponseDto>(json)
            .toDomain()

        assertEquals(response, decoded)
        assertTrue(json.contains("\"provider\":\"NOAA_NOMADS\""))
        assertTrue(json.contains("\"modelFamily\":\"NOAA_GFS\""))
        assertTrue(json.contains("\"modelRun\":\"2026-09-24T00:00:00Z\""))
        assertTrue(json.contains("\"agreement\":\"HIGH\""))
    }

    @Test
    fun missingWeatherValueRemainsNullAcrossJson() {
        val response = responseFixture()
        val dto = ForecastResponseDto.fromDomain(
            forecast = response.forecast,
            sourceForecasts = response.sourceForecasts,
            failedSources = response.failedSources,
        )

        val decoded = ForecastWireJson.json.decodeFromString<ForecastResponseDto>(
            ForecastWireJson.json.encodeToString(dto),
        ).toDomain()

        assertNull(decoded.forecast.hourly.single().weather.precipitationProbabilityPercent)
    }

    @Test
    fun unknownProviderFailsClosedAtDomainBoundary() {
        val response = ForecastResponseDto.fromDomain(
            forecast = responseFixture().forecast,
            sourceForecasts = responseFixture().sourceForecasts,
            failedSources = emptyList(),
        ).copy(
            failedSources = listOf(
                ForecastSourceIdentityDto(
                    provider = "FUTURE_PROVIDER",
                    modelFamily = "NOAA_GFS",
                ),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            response.toDomain()
        }
    }

    @Test
    fun responseRejectsDuplicateOrOverlappingSourceIdentity() {
        val fixture = responseFixture()
        val identity = ForecastSourceIdentityValue(
            provider = ForecastProvider.NOAA_NOMADS,
            modelFamily = ModelFamily.NOAA_GFS,
        )

        assertFailsWith<IllegalArgumentException> {
            DecodedForecastResponse(
                forecast = fixture.forecast,
                sourceForecasts = fixture.sourceForecasts,
                failedSources = listOf(identity),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DecodedForecastResponse(
                forecast = fixture.forecast,
                sourceForecasts = fixture.sourceForecasts + fixture.sourceForecasts,
                failedSources = emptyList(),
            )
        }
    }

    private fun responseFixture(): DecodedForecastResponse {
        val generatedAt = Instant.parse("2026-09-24T07:30:15.123456789Z")
        val validTime = Instant.parse("2026-09-24T08:00:00Z")
        val interval = ForecastInterval(
            start = Instant.parse("2026-09-24T07:00:00Z"),
            end = validTime,
        )
        val location = ForecastLocation(
            latitude = 59.9,
            longitude = 30.3,
            elevationMeters = 15,
            timeZoneId = "Europe/Moscow",
        )
        val weather = HourlyWeatherPoint(
            time = validTime,
            temperatureC = 12.5,
            feelsLikeC = 11.0,
            dewPointC = 8.0,
            humidityPercent = 72.0,
            pressureSeaLevelHpa = 1012.4,
            windSpeedMps = 4.2,
            windGustMps = 6.8,
            windDirectionDegrees = 245.0,
            precipitationMm = 0.4,
            precipitationProbabilityPercent = null,
            cloudCoverPercent = 65.0,
            visibilityMeters = 12_000.0,
            condition = WeatherCondition.RAIN,
            windGustInterval = interval,
            precipitationInterval = interval,
        )
        val source = SourceForecast(
            origin = ForecastOrigin(
                provider = ForecastProvider.NOAA_NOMADS,
                modelFamily = ModelFamily.NOAA_GFS,
                modelRun = Instant.parse("2026-09-24T00:00:00Z"),
                generatedAt = generatedAt,
            ),
            location = location,
            hourly = listOf(weather),
        )
        return DecodedForecastResponse(
            forecast = FusedForecast(
                location = location,
                generatedAt = generatedAt,
                hourly = listOf(
                    FusedHourlyForecast(
                        weather = weather,
                        providerCount = 2,
                        independentEvidenceCount = 2,
                        agreement = ModelAgreement.HIGH,
                    ),
                ),
            ),
            sourceForecasts = listOf(source),
            failedSources = listOf(
                ForecastSourceIdentityValue(
                    provider = ForecastProvider.OPEN_METEO,
                    modelFamily = ModelFamily.ECMWF_IFS,
                ),
            ),
        )
    }
}
