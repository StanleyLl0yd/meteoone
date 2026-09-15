package com.sl.meteoone.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastInterval
import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.FusedForecast
import com.sl.meteoone.core.model.FusedHourlyForecast
import com.sl.meteoone.core.model.HourlyWeatherPoint
import com.sl.meteoone.core.model.ModelAgreement
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

        assertEquals(expected, store.read(coordinate))
        assertEquals(expected, store.observe(coordinate).first())
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

        assertEquals(replacement, store.observe(coordinate).first())
        assertEquals(1, store.read(coordinate)?.hourly?.size)
        assertEquals(other, store.read(otherCoordinate))
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
    fun productionDatabaseIsProcessSingleton() {
        val first = ForecastSnapshotDatabase.database(context)
        val second = ForecastSnapshotDatabase.database(context)

        assertSame(first, second)
    }

    @Test
    fun diskDatabaseReopensTheSameSnapshot() = runBlocking {
        val name = "forecast-snapshot-reopen-test.db"
        context.deleteDatabase(name)
        val expected = forecast(coordinate)

        val first = Room.databaseBuilder(context, MeteoOneDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        try {
            RoomForecastSnapshotStore(first.forecastSnapshotDao()).replace(coordinate, expected)
        } finally {
            first.close()
        }

        val second = Room.databaseBuilder(context, MeteoOneDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(
                expected,
                RoomForecastSnapshotStore(second.forecastSnapshotDao()).read(coordinate),
            )
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
        val location = ForecastLocation(
            latitude = target.latitude,
            longitude = target.longitude,
            elevationMeters = 12,
            timeZoneId = "Europe/Moscow",
        )
        return FusedForecast(
            location = location,
            generatedAt = Instant.parse("2026-09-15T19:30:00.123456789Z"),
            hourly = listOf(
                FusedHourlyForecast(
                    weather = HourlyWeatherPoint(
                        time = firstTime,
                        temperatureC = 10.0 + temperatureOffset,
                        feelsLikeC = 9.0 + temperatureOffset,
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
                        windGustInterval = ForecastInterval(
                            start = firstTime.minusSeconds(3600),
                            end = firstTime,
                        ),
                        precipitationInterval = ForecastInterval(
                            start = firstTime.minusSeconds(3600),
                            end = firstTime,
                        ),
                    ),
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
}
