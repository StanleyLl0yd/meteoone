package com.sl.meteoone.forecast.data.execution

import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.SourceForecast
import com.sl.meteoone.forecast.data.openmeteo.OpenMeteoModel
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class M1ForecastEnginePublicBoundaryTest {
    @Test
    fun publicFacadeCarriesOnlyPrivacyNormalizedCoordinateIntoSources() {
        val coordinate = ForecastCoordinate(latitude = 59.9, longitude = 30.3)
        val generatedAt = Instant.parse("2026-09-14T12:30:00Z")
        val executor = RecordingFailingExecutor()

        val result = M1ForecastEngine(executor).forecast(
            coordinate = coordinate,
            elevationMeters = 20,
            timeZoneId = "Europe/Moscow",
            generatedAt = generatedAt,
        )

        assertIs<M1ForecastEngineResult.Unavailable>(result)
        assertEquals(6, executor.calls.size)
        assertTrue(executor.calls.all { it.coordinate == coordinate })
        assertTrue(
            executor.calls.all {
                it.location == ForecastLocation(
                    latitude = coordinate.latitude,
                    longitude = coordinate.longitude,
                    elevationMeters = 20,
                    timeZoneId = "Europe/Moscow",
                )
            },
        )
    }

    private class RecordingFailingExecutor : ForecastSourceExecutor {
        val calls = mutableListOf<Call>()

        override fun openMeteo(
            model: OpenMeteoModel,
            coordinate: ForecastCoordinate,
            location: ForecastLocation,
            generatedAt: Instant,
        ): SourceForecast = fail(coordinate, location)

        override fun noaa(
            modelRun: Instant,
            forecastHour: Int,
            coordinate: ForecastCoordinate,
            location: ForecastLocation,
            generatedAt: Instant,
        ): SourceForecast = fail(coordinate, location)

        override fun ecmwf(
            modelRun: Instant,
            forecastHour: Int,
            coordinate: ForecastCoordinate,
            location: ForecastLocation,
            generatedAt: Instant,
        ): SourceForecast = fail(coordinate, location)

        override fun dwd(
            modelRun: Instant,
            forecastHour: Int,
            coordinate: ForecastCoordinate,
            location: ForecastLocation,
            generatedAt: Instant,
        ): SourceForecast = fail(coordinate, location)

        private fun fail(
            coordinate: ForecastCoordinate,
            location: ForecastLocation,
        ): Nothing {
            calls += Call(coordinate, location)
            throw IllegalStateException("planned boundary-test failure")
        }
    }

    private data class Call(
        val coordinate: ForecastCoordinate,
        val location: ForecastLocation,
    )
}
