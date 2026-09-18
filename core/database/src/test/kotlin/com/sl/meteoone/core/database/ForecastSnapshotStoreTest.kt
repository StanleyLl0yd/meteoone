package com.sl.meteoone.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ForecastSnapshotStoreTest {
    private lateinit var context: Context
    private lateinit var database: MeteoOneDatabase
    private lateinit var store: ForecastSnapshotStore

    private val coordinate = ForecastCoordinate(latitude = 59.9, longitude = 30.3)
    private val otherCoordinate = ForecastCoordinate(latitude = 60.0, longitude = 30.4)

    @BeforeTest
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, MeteoOneDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomForecastSnapshotStore(database.forecastSnapshotDao())
    }

    @AfterTest
    fun tearDown() {
        database.close()
    }

    @Test
    fun exactCanonicalForecastRoundTripsThroughRoom() = runBlocking {
        val expected = forecast(coordinate)

        store.replace(coordinate, expected)

        assertEquals(expected, store.read(coordinate)?.forecast)
        assertEquals(expected, store.observe(coordinate).first()?.forecast)
    }

    @Test
    fun sourceForecastsAndFailedIdentitiesRoundTripExactly() = runBlocking {
        val fused = forecast(coordinate)
        val ecmwf = sourceForecast(
            target = coordinate,
            provider = ForecastProvider.ECMWF_OPEN_DATA,
            modelFamily = ModelFamily.ECMWF_IFS,
            temperatureOffset = 1.5,
        )
        val openMeteo = sourceForecast(
            target = coordinate,
            provider = ForecastProvider.OPEN_METEO,
            modelFamily = ModelFamily.NOAA_GFS,
            temperatureOffset = -2.0,
            modelRun = null,
        )
        val failed = listOf(
            StoredForecastSourceIdentity(ForecastProvider.DWD_OPEN_DATA, ModelFamily.DWD_ICON),
        )

        store.replace(
            coordinate = coordinate,
            forecast = fused,
            sourceForecasts = listOf(ecmwf, openMeteo),
            failedSources = failed,
        )

        val restored = requireNotNull(store.read(coordinate))
        assertEquals(fused, restored.forecast)
        assertEquals(listOf(ecmwf, openMeteo), restored.sourceForecasts)
        assertEquals(failed, restored.failedSources)
        assertEquals(restored, store.observe(coordinate).first())
    }

    @Test
    fun replacementRemovesStaleComparisonEvidenceAtomically() = runBlocking {
        val firstSource = sourceForecast(
            coordinate,
            ForecastProvider.OPEN_METEO,
            ModelFamily.ECMWF_IFS,
            temperatureOffset = 1.0,
        )
        val secondSource = sourceForecast(
            coordinate,
            ForecastProvider.NOAA_NOMADS,
            ModelFamily.NOAA_GFS,
            temperatureOffset = 2.0,
        )
        store.replace(
            coordinate,
            forecast(coordinate),
            sourceForecasts = listOf(firstSource),
            failedSources = listOf(
                StoredForecastSourceIdentity(ForecastProvider.DWD_OPEN_DATA, ModelFamily.DWD_ICON),
            ),
        )

        val replacement = forecast(coordinate, temperatureOffset = 20.0)
        store.replace(
            coordinate,
            replacement,
            sourceForecasts = listOf(secondSource),
            failedSources = emptyList(),
        )

        val restored = requireNotNull(store.read(coordinate))
        assertEquals(replacement, restored.forecast)
        assertEquals(listOf(secondSource), restored.sourceForecasts)
        assertEquals(emptyList(), restored.failedSources)
    }

    @Test
    fun replacementIsCompleteAndOtherCoordinatesRemainIsolated() = runBlocking {
        val original = forecast(coordinate)
        val other = forecast(otherCoordinate, temperatureOffset = 5.0)
        store.replace(coordinate, original)
        store.replace(otherCoordinate, other)

        val replacement = forecast(coordinate, temperatureOffset = 20.0).copy(
            hourly = listOf(forecast(coordinate, temperatureOffset = 20.0).hourly.last()),
        )
        store.replace(coordinate, replacement)

        assertEquals(replacement, store.observe(coordinate).first()?.forecast)
        assertEquals(1, store.read(coordinate)?.forecast?.hourly?.size)
        assertEquals(other, store.read(otherCoordinate)?.forecast)
    }

    @Test
    fun persistenceKeyUsesCanonicalTenthsRatherThanRawCoordinatePrecision() = runBlocking {
        store.replace(coordinate, forecast(coordinate))

        val rows = requireNotNull(
            database.forecastSnapshotDao().readSnapshot(coordinate.toPersistedKey().encoded),
        )
        assertEquals("599:303", rows.snapshot.coordinateKey)
        assertEquals(599, rows.snapshot.latitudeTenths)
        assertEquals(303, rows.snapshot.longitudeTenths)
    }

    @Test
    fun rawPrecisionForecastLocationCannotBePersistedUnderCanonicalKey() = runBlocking {
        val rawLocationForecast = forecast(coordinate).copy(
            location = ForecastLocation(
                latitude = 59.94,
                longitude = 30.31,
                elevationMeters = 12,
                timeZoneId = "Europe/Moscow",
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            store.replace(coordinate, rawLocationForecast)
        }
        assertEquals(null, store.read(coordinate))
    }

    @Test
    fun sourceForecastLocationMustMatchFusedPrivacyReducedLocation() = runBlocking {
        val source = sourceForecast(
            coordinate,
            ForecastProvider.OPEN_METEO,
            ModelFamily.ECMWF_IFS,
            temperatureOffset = 0.0,
        ).copy(
            location = ForecastLocation(59.8, 30.3, 12, "Europe/Moscow"),
        )

        assertFailsWith<IllegalArgumentException> {
            store.replace(coordinate, forecast(coordinate), sourceForecasts = listOf(source))
        }
        assertEquals(null, store.read(coordinate))
    }

    @Test
    fun productionDatabaseIsProcessSingleton() {
        val first = ForecastSnapshotDatabase.database(context)
        val second = ForecastSnapshotDatabase.database(context)

        assertSame(first, second)
    }

    @Test
    fun diskDatabaseReopensTheSameSnapshotAndEvidence() = runBlocking {
        val name = "forecast-snapshot-reopen-test.db"
        context.deleteDatabase(name)
        val expected = forecast(coordinate)
        val source = sourceForecast(
            coordinate,
            ForecastProvider.OPEN_METEO,
            ModelFamily.DWD_ICON,
            temperatureOffset = 3.0,
        )

        val first = Room.databaseBuilder(context, MeteoOneDatabase::class.java, name)
            .addMigrations(MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            RoomForecastSnapshotStore(first.forecastSnapshotDao()).replace(
                coordinate,
                expected,
                sourceForecasts = listOf(source),
            )
        } finally {
            first.close()
        }

        val second = Room.databaseBuilder(context, MeteoOneDatabase::class.java, name)
            .addMigrations(MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val restored = requireNotNull(
                RoomForecastSnapshotStore(second.forecastSnapshotDao()).read(coordinate),
            )
            assertEquals(expected, restored.forecast)
            assertEquals(listOf(source), restored.sourceForecasts)
        } finally {
            second.close()
            context.deleteDatabase(name)
        }
    }

    private fun forecast(
        target: ForecastCoordinate,
        temperatureOffset: Double = 0.0,
    ): FusedForecast {
        val firstTime = Instant.parse("2026-09-15T20:00:00Z")
        val secondTime = firstTime.plusSeconds(3600)
        val location = location(target)
        return FusedForecast(
            location = location,
            generatedAt = Instant.parse("2026-09-15T19:30:00.123456789Z"),
            hourly = listOf(
                FusedHourlyForecast(
                    weather = detailedWeather(firstTime, 10.0 + temperatureOffset),
                    providerCount = 3,
                    independentEvidenceCount = 3,
                    agreement = ModelAgreement.HIGH,
                ),
                FusedHourlyForecast(
                    weather = HourlyWeatherPoint(
                        time = secondTime,
                        temperatureC = 11.0 + temperatureOffset,
                        feelsLikeC = null,
                        dewPointC = null,
                        humidityPercent = null,
                        pressureSeaLevelHpa = null,
                        windSpeedMps = 0.0,
                        windGustMps = null,
                        windDirectionDegrees = null,
                        precipitationMm = 0.0,
                        precipitationProbabilityPercent = null,
                        cloudCoverPercent = 10.0,
                        visibilityMeters = null,
                        condition = WeatherCondition.CLEAR,
                        precipitationInterval = ForecastInterval(
                            start = secondTime.minusSeconds(3600),
                            end = secondTime,
                        ),
                    ),
                    providerCount = 1,
                    independentEvidenceCount = 2,
                    agreement = ModelAgreement.INSUFFICIENT,
                ),
            ),
        )
    }

    private fun sourceForecast(
        target: ForecastCoordinate,
        provider: ForecastProvider,
        modelFamily: ModelFamily,
        temperatureOffset: Double,
        modelRun: Instant? = Instant.parse("2026-09-15T12:00:00Z"),
    ): SourceForecast {
        val firstTime = Instant.parse("2026-09-15T20:00:00Z")
        return SourceForecast(
            origin = ForecastOrigin(
                provider = provider,
                modelFamily = modelFamily,
                modelRun = modelRun,
                generatedAt = Instant.parse("2026-09-15T19:30:00.123456789Z"),
            ),
            location = location(target),
            hourly = listOf(
                detailedWeather(firstTime, 9.0 + temperatureOffset),
                detailedWeather(firstTime.plusSeconds(3600), 10.0 + temperatureOffset),
            ),
        )
    }

    private fun location(target: ForecastCoordinate) = ForecastLocation(
        latitude = target.latitude,
        longitude = target.longitude,
        elevationMeters = 12,
        timeZoneId = "Europe/Moscow",
    )

    private fun detailedWeather(time: Instant, temperatureC: Double) = HourlyWeatherPoint(
        time = time,
        temperatureC = temperatureC,
        feelsLikeC = temperatureC - 1.0,
        dewPointC = 5.0,
        humidityPercent = 70.0,
        pressureSeaLevelHpa = 1012.3,
        windSpeedMps = 4.5,
        windGustMps = 8.0,
        windDirectionDegrees = 270.0,
        precipitationMm = 1.2,
        precipitationProbabilityPercent = 60.0,
        cloudCoverPercent = 80.0,
        visibilityMeters = 12_000.0,
        condition = WeatherCondition.RAIN,
        windGustInterval = ForecastInterval(time.minusSeconds(3600), time),
        precipitationInterval = ForecastInterval(time.minusSeconds(3600), time),
    )
}
