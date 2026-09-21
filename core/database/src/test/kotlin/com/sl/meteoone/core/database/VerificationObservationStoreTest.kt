package com.sl.meteoone.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sl.meteoone.core.model.ForecastInterval
import com.sl.meteoone.verification.domain.ObservationStation
import com.sl.meteoone.verification.domain.PrecipitationObservation
import com.sl.meteoone.verification.domain.SurfaceObservation
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VerificationObservationStoreTest {
    private lateinit var context: Context
    private lateinit var database: MeteoOneDatabase

    private val now = Instant.parse("2026-09-21T12:00:00Z")
    private val station = ObservationStation(
        sourceId = "noaa-ncei-ghcnh",
        stationId = "RSM00026063",
        latitude = 59.9667,
        longitude = 30.3,
        elevationMeters = 4.0,
    )

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
    fun canonicalSurfaceAndExplicitPrecipitationRoundTrip() : Unit = runBlocking {
        val observedAt = Instant.parse("2026-09-20T12:00:00Z")
        val surface = surface(
            observedAt = observedAt,
            temperatureC = 11.2,
            pressureHpa = 1014.3,
            windSpeedMps = 3.4,
            windDirectionDegrees = 270.0,
        )
        val precipitation = precipitation(
            start = observedAt.minus(Duration.ofHours(3)),
            end = observedAt,
            amountMm = 2.4,
        )

        val result = store().archive(
            station = station,
            surfaceObservations = listOf(surface),
            precipitationObservations = listOf(precipitation),
        )

        assertEquals(true, result.stationInserted)
        assertEquals(1, result.insertedSurface)
        assertEquals(0, result.enrichedSurface)
        assertEquals(1, result.insertedPrecipitation)

        val restored = assertNotNull(
            store().readSince(station.sourceId, station.stationId, Instant.EPOCH),
        )
        assertEquals(station, restored.station)
        assertEquals(listOf(surface), restored.surfaceObservations)
        assertEquals(listOf(precipitation), restored.precipitationObservations)
    }

    @Test
    fun repeatedEvidenceIsIdempotentAndMissingFieldsMayOnlyBeEnriched() : Unit = runBlocking {
        val observedAt = Instant.parse("2026-09-20T12:00:00Z")
        val first = surface(observedAt, temperatureC = 11.2)
        val store = store()

        store.archive(station, listOf(first), emptyList())
        val repeated = store.archive(station, listOf(first), emptyList())
        assertEquals(1, repeated.existingSurface)
        assertEquals(0, repeated.enrichedSurface)

        val enriched = surface(
            observedAt = observedAt,
            temperatureC = 11.2,
            pressureHpa = 1014.3,
        )
        val enrichment = store.archive(station, listOf(enriched), emptyList())

        assertEquals(0, enrichment.insertedSurface)
        assertEquals(1, enrichment.enrichedSurface)
        val restored = assertNotNull(
            store.readSince(station.sourceId, station.stationId, Instant.EPOCH),
        ).surfaceObservations.single()
        assertEquals(11.2, restored.temperatureC)
        assertEquals(1014.3, restored.pressureSeaLevelHpa)
    }

    @Test
    fun sameFieldOrPrecipitationIntervalCannotBeSilentlyRewritten() : Unit = runBlocking {
        val observedAt = Instant.parse("2026-09-20T12:00:00Z")
        val store = store()
        store.archive(
            station,
            listOf(surface(observedAt, temperatureC = 11.2)),
            listOf(
                precipitation(
                    observedAt.minus(Duration.ofHours(3)),
                    observedAt,
                    2.4,
                ),
            ),
        )

        assertFailsWith<IllegalStateException> {
            store.archive(
                station,
                listOf(surface(observedAt, temperatureC = 99.0)),
                emptyList(),
            )
        }
        assertFailsWith<IllegalStateException> {
            store.archive(
                station,
                emptyList(),
                listOf(
                    precipitation(
                        observedAt.minus(Duration.ofHours(3)),
                        observedAt,
                        99.0,
                    ),
                ),
            )
        }

        val restored = assertNotNull(
            store.readSince(station.sourceId, station.stationId, Instant.EPOCH),
        )
        assertEquals(11.2, restored.surfaceObservations.single().temperatureC)
        assertEquals(2.4, restored.precipitationObservations.single().amountMm)
    }

    @Test
    fun stationIdentityCannotBeReboundToDifferentPublicMetadata() : Unit = runBlocking {
        val observedAt = Instant.parse("2026-09-20T12:00:00Z")
        val store = store()
        store.archive(
            station,
            listOf(surface(observedAt, temperatureC = 11.2)),
            emptyList(),
        )

        val conflictingStation = station.copy(latitude = 60.0)
        assertFailsWith<IllegalStateException> {
            store.archive(
                conflictingStation,
                listOf(
                    SurfaceObservation(
                        station = conflictingStation,
                        observedAt = observedAt.plusSeconds(3600),
                        temperatureC = 12.0,
                        pressureSeaLevelHpa = null,
                        windSpeedMps = null,
                        windDirectionDegrees = null,
                    ),
                ),
                emptyList(),
            )
        }
        assertEquals(
            station,
            assertNotNull(
                store.readSince(station.sourceId, station.stationId, Instant.EPOCH),
            ).station,
        )
    }

    @Test
    fun retentionSkipsExpiredInputAndPrunesEvidenceThatCanNoLongerVerifyRuns() : Unit = runBlocking {
        val retention = Duration.ofDays(10)
        val firstNow = Instant.parse("2026-09-15T12:00:00Z")
        val firstStore = store(
            retention = retention,
            clock = Clock.fixed(firstNow, ZoneOffset.UTC),
        )
        val initiallyFresh = firstNow.minus(Duration.ofDays(9))
        firstStore.archive(
            station,
            listOf(surface(initiallyFresh, temperatureC = 7.0)),
            listOf(
                precipitation(
                    initiallyFresh.minus(Duration.ofHours(3)),
                    initiallyFresh,
                    1.0,
                ),
            ),
        )

        val advancedNow = Instant.parse("2026-09-30T12:00:00Z")
        val advancedStore = store(
            retention = retention,
            clock = Clock.fixed(advancedNow, ZoneOffset.UTC),
        )
        val fresh = advancedNow.minus(Duration.ofDays(1))
        val expired = advancedNow.minus(Duration.ofDays(20))
        val result = advancedStore.archive(
            station,
            listOf(
                surface(expired, temperatureC = 1.0),
                surface(fresh, temperatureC = 9.0),
            ),
            listOf(
                precipitation(
                    expired.minus(Duration.ofHours(3)),
                    expired,
                    5.0,
                ),
                precipitation(
                    fresh.minus(Duration.ofHours(3)),
                    fresh,
                    0.5,
                ),
            ),
        )

        assertEquals(1, result.skippedExpiredSurface)
        assertEquals(1, result.skippedExpiredPrecipitation)
        assertEquals(1, result.prunedSurface)
        assertEquals(1, result.prunedPrecipitation)

        val restored = assertNotNull(
            advancedStore.readSince(station.sourceId, station.stationId, Instant.EPOCH),
        )
        assertEquals(listOf(fresh), restored.surfaceObservations.map { it.observedAt })
        assertEquals(listOf(fresh), restored.precipitationObservations.map { it.interval.end })
    }

    @Test
    fun futureEmptyAndDuplicateBatchEvidenceFailClosed() : Unit = runBlocking {
        val store = store()

        assertFailsWith<IllegalArgumentException> {
            store.archive(
                station,
                listOf(
                    surface(now.plusSeconds(1), temperatureC = 10.0),
                ),
                emptyList(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            store.archive(
                station,
                listOf(
                    surface(
                        observedAt = now.minusSeconds(1),
                        temperatureC = null,
                        pressureHpa = null,
                        windSpeedMps = null,
                        windDirectionDegrees = null,
                    ),
                ),
                emptyList(),
            )
        }

        val duplicateTime = now.minusSeconds(3600)
        assertFailsWith<IllegalArgumentException> {
            store.archive(
                station,
                listOf(
                    surface(duplicateTime, temperatureC = 10.0),
                    surface(duplicateTime, pressureHpa = 1010.0),
                ),
                emptyList(),
            )
        }

        val interval = ForecastInterval(
            start = duplicateTime.minusSeconds(3600),
            end = duplicateTime,
        )
        assertFailsWith<IllegalArgumentException> {
            store.archive(
                station,
                emptyList(),
                listOf(
                    PrecipitationObservation(station, interval, 1.0),
                    PrecipitationObservation(station, interval, 1.0),
                ),
            )
        }
    }

    @Test
    fun observationTablesHaveNoUserCoordinateKeyOrPrivacyGridColumns() {
        val sqlite = database.openHelper.readableDatabase
        listOf(
            "verification_observation_stations",
            "verification_surface_observations",
            "verification_precipitation_observations",
        ).forEach { table ->
            val columns = sqlite.query("PRAGMA table_info(`$table`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
            }
            assertTrue("coordinate_key" !in columns)
            assertTrue("latitude_tenths" !in columns)
            assertTrue("longitude_tenths" !in columns)
        }

        val stationColumns = sqlite.query(
            "PRAGMA table_info(`verification_observation_stations`)",
        ).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }
        assertTrue("latitude" in stationColumns)
        assertTrue("longitude" in stationColumns)
    }

    @Test
    fun expiredOnlyArchiveDoesNotLeaveOrphanStation() : Unit = runBlocking {
        val expired = now.minus(Duration.ofDays(181))
        val result = store().archive(
            station,
            listOf(surface(expired, temperatureC = 1.0)),
            emptyList(),
        )

        assertEquals(1, result.skippedExpiredSurface)
        assertEquals(1, result.prunedStations)
        assertNull(store().readSince(station.sourceId, station.stationId, Instant.EPOCH))
    }

    private fun store(
        retention: Duration = Duration.ofDays(180),
        clock: Clock = Clock.fixed(now, ZoneOffset.UTC),
    ): VerificationObservationStore =
        RoomVerificationObservationStore(
            dao = database.verificationObservationDao(),
            retention = retention,
            clock = clock,
        )

    private fun surface(
        observedAt: Instant,
        temperatureC: Double? = null,
        pressureHpa: Double? = null,
        windSpeedMps: Double? = null,
        windDirectionDegrees: Double? = null,
    ): SurfaceObservation =
        SurfaceObservation(
            station = station,
            observedAt = observedAt,
            temperatureC = temperatureC,
            pressureSeaLevelHpa = pressureHpa,
            windSpeedMps = windSpeedMps,
            windDirectionDegrees = windDirectionDegrees,
        )

    private fun precipitation(
        start: Instant,
        end: Instant,
        amountMm: Double,
    ): PrecipitationObservation =
        PrecipitationObservation(
            station = station,
            interval = ForecastInterval(start = start, end = end),
            amountMm = amountMm,
        )
}
