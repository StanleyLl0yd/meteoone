package com.sl.meteoone.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sl.meteoone.core.model.ForecastCoordinate
import com.sl.meteoone.core.model.ForecastInterval
import com.sl.meteoone.core.model.ForecastLocation
import com.sl.meteoone.core.model.ForecastOrigin
import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.HourlyWeatherPoint
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.core.model.SourceForecast
import com.sl.meteoone.core.model.WeatherCondition
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ForecastVerificationHistoryStoreTest {
    private lateinit var context: Context
    private lateinit var database: MeteoOneDatabase

    private val coordinate = ForecastCoordinate(59.9, 30.3)
    private val now = Instant.parse("2026-09-18T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @BeforeTest
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, MeteoOneDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @AfterTest
    fun tearDown() {
        database.close()
    }

    @Test
    fun exactRunRoundTripsOnlyVerificationFieldsAndLead() : Unit = runBlocking {
        val run = Instant.parse("2026-09-15T00:00:00Z")
        val source = source(
            modelRun = run,
            points = listOf(
                weather(run.plusSeconds(6 * 3600), 7.0),
                weather(run.plusSeconds(24 * 3600), 9.0),
            ),
        )
        val store = store()

        val result = store.archive(coordinate, listOf(source))

        assertEquals(1, result.insertedRuns)
        assertEquals(2, result.insertedPoints)
        assertEquals(0, result.existingPoints)
        val restored = store.readSince(coordinate, run).single()
        assertEquals(coordinate, restored.coordinate)
        assertEquals(ForecastProvider.ECMWF_OPEN_DATA, restored.provider)
        assertEquals(ModelFamily.ECMWF_IFS, restored.modelFamily)
        assertEquals(run, restored.modelRun)
        assertEquals(Duration.ofHours(6), restored.hourly[0].leadTime)
        assertEquals(Duration.ofHours(24), restored.hourly[1].leadTime)
        assertEquals(7.0, restored.hourly[0].temperatureC)
        assertEquals(1012.0, restored.hourly[0].pressureSeaLevelHpa)
        assertEquals(4.0, restored.hourly[0].windSpeedMps)
        assertEquals(270.0, restored.hourly[0].windDirectionDegrees)
        assertEquals(1.5, restored.hourly[0].precipitationMm)
        assertEquals(
            source.hourly[0].precipitationInterval,
            restored.hourly[0].precipitationInterval,
        )
    }

    @Test
    fun repeatedArchiveIsIdempotent() : Unit = runBlocking {
        val run = Instant.parse("2026-09-15T00:00:00Z")
        val source = source(run, listOf(weather(run.plusSeconds(6 * 3600), 7.0)))
        val store = store()

        store.archive(coordinate, listOf(source))
        val second = store.archive(coordinate, listOf(source))

        assertEquals(0, second.insertedRuns)
        assertEquals(0, second.insertedPoints)
        assertEquals(1, second.existingPoints)
        assertEquals(1, store.readSince(coordinate, run).single().hourly.size)
    }

    @Test
    fun sameRunIdentityDoesNotConflictOnNonForecastTargetMetadata() : Unit = runBlocking {
        val run = Instant.parse("2026-09-15T00:00:00Z")
        val point = weather(run.plusSeconds(6 * 3600), 7.0)
        val store = store()
        store.archive(coordinate, listOf(source(run, listOf(point))))

        val repeated = source(run, listOf(point)).copy(
            location = ForecastLocation(
                latitude = coordinate.latitude,
                longitude = coordinate.longitude,
                elevationMeters = 99,
                timeZoneId = "UTC",
            ),
        )
        val result = store.archive(coordinate, listOf(repeated))

        assertEquals(1, result.existingPoints)
        val restored = store.readSince(coordinate, run).single()
        assertEquals(12, restored.elevationMeters)
        assertEquals("Europe/Moscow", restored.timeZoneId)
    }

    @Test
    fun sameRunCanGainNewValidTimesIncrementally() : Unit = runBlocking {
        val run = Instant.parse("2026-09-15T00:00:00Z")
        val store = store()

        store.archive(
            coordinate,
            listOf(source(run, listOf(weather(run.plusSeconds(6 * 3600), 7.0)))),
        )
        val second = store.archive(
            coordinate,
            listOf(source(run, listOf(weather(run.plusSeconds(12 * 3600), 8.0)))),
        )

        assertEquals(0, second.insertedRuns)
        assertEquals(1, second.insertedPoints)
        assertEquals(
            listOf(Duration.ofHours(6), Duration.ofHours(12)),
            store.readSince(coordinate, run).single().hourly.map { it.leadTime },
        )
    }

    @Test
    fun sameRunAndValidTimeCannotBeSilentlyRewritten() : Unit = runBlocking {
        val run = Instant.parse("2026-09-15T00:00:00Z")
        val valid = run.plusSeconds(6 * 3600)
        val store = store()

        store.archive(coordinate, listOf(source(run, listOf(weather(valid, 7.0)))))

        assertFailsWith<IllegalStateException> {
            store.archive(
                coordinate,
                listOf(source(run, listOf(weather(valid, 99.0)))),
            )
        }
        assertEquals(
            7.0,
            store.readSince(coordinate, run).single().hourly.single().temperatureC,
        )
    }

    @Test
    fun sourceWithoutModelRunIsSkippedWithoutInventingLead() : Unit = runBlocking {
        val source = source(
            modelRun = null,
            points = listOf(weather(Instant.parse("2026-09-15T06:00:00Z"), 7.0)),
        )
        val store = store()

        val result = store.archive(coordinate, listOf(source))

        assertEquals(1, result.skippedWithoutModelRun)
        assertEquals(0, result.insertedRuns)
        assertEquals(emptyList(), store.readSince(coordinate, Instant.EPOCH))
    }

    @Test
    fun retentionSkipsExpiredInputAndPrunesPreviouslyStoredRuns() : Unit = runBlocking {
        val store = store(retention = Duration.ofDays(10))
        val initiallyFreshRun = Instant.parse("2026-09-09T00:00:00Z")
        store.archive(
            coordinate,
            listOf(
                source(
                    initiallyFreshRun,
                    listOf(weather(initiallyFreshRun.plusSeconds(3600), 7.0)),
                ),
            ),
        )
        assertEquals(1, store.readSince(coordinate, Instant.EPOCH).size)

        val advancedClock = Clock.fixed(
            Instant.parse("2026-09-25T12:00:00Z"),
            ZoneOffset.UTC,
        )
        val advancedStore = store(
            retention = Duration.ofDays(10),
            clock = advancedClock,
        )
        val newRun = Instant.parse("2026-09-24T00:00:00Z")
        val result = advancedStore.archive(
            coordinate,
            listOf(
                source(
                    Instant.parse("2026-09-01T00:00:00Z"),
                    listOf(weather(Instant.parse("2026-09-01T01:00:00Z"), 1.0)),
                ),
                source(newRun, listOf(weather(newRun.plusSeconds(3600), 8.0))),
            ),
        )

        assertEquals(1, result.skippedExpiredRuns)
        assertEquals(1, result.prunedRuns)
        assertEquals(
            listOf(newRun),
            advancedStore.readSince(coordinate, Instant.EPOCH).map { it.modelRun },
        )
    }

    @Test
    fun duplicateRunIdentityInsideOneBatchIsRejected() : Unit = runBlocking {
        val run = Instant.parse("2026-09-15T00:00:00Z")
        val store = store()

        assertFailsWith<IllegalArgumentException> {
            store.archive(
                coordinate,
                listOf(
                    source(run, listOf(weather(run.plusSeconds(3600), 1.0))),
                    source(run, listOf(weather(run.plusSeconds(7200), 2.0))),
                ),
            )
        }
    }

    @Test
    fun rawPrecisionLocationCannotEnterVerificationHistory() : Unit = runBlocking {
        val run = Instant.parse("2026-09-15T00:00:00Z")
        val raw = source(run, listOf(weather(run.plusSeconds(3600), 7.0))).copy(
            location = ForecastLocation(
                latitude = 59.94,
                longitude = 30.31,
                elevationMeters = 12,
                timeZoneId = "Europe/Moscow",
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            store().archive(coordinate, listOf(raw))
        }
        assertEquals(emptyList(), store().readSince(coordinate, Instant.EPOCH))
    }

    @Test
    fun leadOutsideM4HorizonIsRejected() : Unit = runBlocking {
        val run = Instant.parse("2026-09-15T00:00:00Z")

        assertFailsWith<IllegalArgumentException> {
            store().archive(
                coordinate,
                listOf(
                    source(
                        run,
                        listOf(weather(run.plusSeconds(72 * 3600 + 1), 7.0)),
                    ),
                ),
            )
        }
    }

    private fun store(
        retention: Duration = Duration.ofDays(180),
        clock: Clock = this.clock,
    ): ForecastVerificationHistoryStore =
        RoomForecastVerificationHistoryStore(
            dao = database.forecastVerificationHistoryDao(),
            clock = clock,
            retention = retention,
        )

    private fun source(
        modelRun: Instant?,
        points: List<HourlyWeatherPoint>,
    ): SourceForecast = SourceForecast(
        origin = ForecastOrigin(
            provider = ForecastProvider.ECMWF_OPEN_DATA,
            modelFamily = ModelFamily.ECMWF_IFS,
            modelRun = modelRun,
            generatedAt = modelRun?.plusSeconds(30 * 60) ?: now,
        ),
        location = ForecastLocation(
            latitude = coordinate.latitude,
            longitude = coordinate.longitude,
            elevationMeters = 12,
            timeZoneId = "Europe/Moscow",
        ),
        hourly = points,
    )

    private fun weather(
        time: Instant,
        temperatureC: Double,
    ): HourlyWeatherPoint = HourlyWeatherPoint(
        time = time,
        temperatureC = temperatureC,
        feelsLikeC = null,
        dewPointC = null,
        humidityPercent = null,
        pressureSeaLevelHpa = 1012.0,
        windSpeedMps = 4.0,
        windGustMps = null,
        windDirectionDegrees = 270.0,
        precipitationMm = 1.5,
        precipitationProbabilityPercent = null,
        cloudCoverPercent = null,
        visibilityMeters = null,
        condition = WeatherCondition.UNKNOWN,
        precipitationInterval = ForecastInterval(
            start = time.minusSeconds(3600),
            end = time,
        ),
    )
}
