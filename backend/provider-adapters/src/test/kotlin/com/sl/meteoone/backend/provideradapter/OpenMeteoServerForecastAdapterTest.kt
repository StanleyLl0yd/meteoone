package com.sl.meteoone.backend.provideradapter

import com.sl.meteoone.backend.provider.ProviderGateway
import com.sl.meteoone.backend.provider.ProviderGatewayFailureReason
import com.sl.meteoone.backend.provider.ProviderGatewayRequest
import com.sl.meteoone.backend.provider.ProviderGatewayResponse
import com.sl.meteoone.backend.provider.ProviderGatewayResult
import com.sl.meteoone.backend.provider.ProviderResponseValidator
import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenMeteoServerForecastAdapterTest {
    private val coordinate = ForecastCoordinate(59.9, 30.3)
    private val location = ForecastLocation(
        latitude = coordinate.latitude,
        longitude = coordinate.longitude,
        elevationMeters = 12,
        timeZoneId = "Europe/Moscow",
    )
    private val generatedAt = Instant.parse("2026-09-24T12:30:00Z")

    @Test
    fun fetchesEveryFallbackModelThroughBoundedGatewayWithDistinctProvenance() = runBlocking {
        val expected = listOf(
            ModelFamily.NOAA_GFS to "ncep_gfs_global",
            ModelFamily.ECMWF_IFS to "ecmwf_ifs",
            ModelFamily.DWD_ICON to "icon_global",
        )

        expected.forEach { (family, apiId) ->
            val gateway = FixtureGateway(payload())
            val adapter = OpenMeteoServerForecastAdapter(gateway)

            val result = adapter.fetch(
                modelFamily = family,
                coordinate = coordinate,
                location = location,
                generatedAt = generatedAt,
            )

            val success = assertIs<ServerForecastAdapterResult.Success>(result)
            assertEquals(ForecastProvider.OPEN_METEO, success.forecast.origin.provider)
            assertEquals(family, success.forecast.origin.modelFamily)
            assertEquals(72, success.forecast.hourly.size)

            val request = gateway.requests.single()
            assertEquals(ForecastProvider.OPEN_METEO, request.provider)
            assertEquals(family, request.modelFamily)
            assertEquals("api.open-meteo.com", request.uri.host)
            assertEquals("/v1/forecast", request.uri.path)
            assertTrue(request.uri.rawQuery.contains("models=$apiId"))
            assertEquals(setOf(200), request.expectedStatusCodes)
            assertEquals(null, request.credential)
        }
    }

    @Test
    fun malformedProviderPayloadBecomesInvalidResponse() = runBlocking {
        val adapter = OpenMeteoServerForecastAdapter(
            FixtureGateway("""{"not":"forecast"}"""),
        )

        val result = adapter.fetch(
            modelFamily = ModelFamily.ECMWF_IFS,
            coordinate = coordinate,
            location = location,
            generatedAt = generatedAt,
        )

        val failure = assertIs<ServerForecastAdapterResult.Failure>(result)
        assertEquals(ForecastProvider.OPEN_METEO, failure.provider)
        assertEquals(ModelFamily.ECMWF_IFS, failure.modelFamily)
        assertEquals(ServerForecastAdapterFailureReason.INVALID_RESPONSE, failure.reason)
    }

    @Test
    fun gatewayCircuitFailurePropagatesWithoutFabricatingForecast() = runBlocking {
        val adapter = OpenMeteoServerForecastAdapter(
            FailingGateway(ProviderGatewayFailureReason.CIRCUIT_OPEN),
        )

        val result = adapter.fetch(
            modelFamily = ModelFamily.NOAA_GFS,
            coordinate = coordinate,
            location = location,
            generatedAt = generatedAt,
        )

        val failure = assertIs<ServerForecastAdapterResult.Failure>(result)
        assertEquals(ServerForecastAdapterFailureReason.CIRCUIT_OPEN, failure.reason)
        assertEquals(ForecastProvider.OPEN_METEO, failure.provider)
        assertEquals(ModelFamily.NOAA_GFS, failure.modelFamily)
    }

    @Test
    fun rejectsRawLocationMismatchAndUnknownModelBeforeGateway() = runBlocking {
        val gateway = FixtureGateway(payload())
        val adapter = OpenMeteoServerForecastAdapter(gateway)

        assertFailsWith<IllegalArgumentException> {
            adapter.fetch(
                modelFamily = ModelFamily.ECMWF_IFS,
                coordinate = coordinate,
                location = location.copy(latitude = 59.94),
                generatedAt = generatedAt,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            adapter.fetch(
                modelFamily = ModelFamily.UNKNOWN,
                coordinate = coordinate,
                location = location,
                generatedAt = generatedAt,
            )
        }
        assertTrue(gateway.requests.isEmpty())
    }

    private fun payload(): String {
        val first = Instant.parse("2026-09-24T13:00:00Z").epochSecond
        val times = List(72) { index -> first + index * 3600L }.joinToString(",")
        val temperatures = List(72) { "10.0" }.joinToString(",")
        return """
            {
              "latitude": 59.9,
              "longitude": 30.3,
              "utc_offset_seconds": 0,
              "hourly_units": {
                "time": "unixtime",
                "temperature_2m": "°C"
              },
              "hourly": {
                "time": [$times],
                "temperature_2m": [$temperatures]
              }
            }
        """.trimIndent()
    }

    private class FixtureGateway(
        private val payload: String,
    ) : ProviderGateway {
        val requests = mutableListOf<ProviderGatewayRequest>()

        override suspend fun execute(
            request: ProviderGatewayRequest,
            responseValidator: ProviderResponseValidator,
        ): ProviderGatewayResult {
            requests += request
            val response = ProviderGatewayResponse(
                provider = request.provider,
                modelFamily = request.modelFamily,
                statusCode = 200,
                headers = mapOf("Content-Type" to listOf("application/json")),
                body = payload.encodeToByteArray(),
            )
            return if (responseValidator.isValid(response)) {
                ProviderGatewayResult.Success(response)
            } else {
                ProviderGatewayResult.Failure(
                    provider = request.provider,
                    modelFamily = request.modelFamily,
                    reason = ProviderGatewayFailureReason.INVALID_RESPONSE,
                )
            }
        }
    }

    private class FailingGateway(
        private val reason: ProviderGatewayFailureReason,
    ) : ProviderGateway {
        override suspend fun execute(
            request: ProviderGatewayRequest,
            responseValidator: ProviderResponseValidator,
        ): ProviderGatewayResult =
            ProviderGatewayResult.Failure(
                provider = request.provider,
                modelFamily = request.modelFamily,
                reason = reason,
            )
    }
}
