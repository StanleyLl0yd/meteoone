package com.sl.meteoone.verification.data.ghcnh

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GhcnhObservationParserTest {
    @Test
    fun parsesCurrentNamedPsvColumnsAndPreservesFiveAttributes() {
        val content = fixture(
            stationId = "RSM00026063",
            date = "2026-09-20T12:00:00Z",
            temperature = "11.2",
            temperatureQuality = "5",
            precipitation3h = "2.4",
            precipitation3hMeasurement = "",
            precipitation3hQuality = "1",
        )

        val row = GhcnhObservationParser.parse(
            content = content,
            expectedStationId = "RSM00026063",
            expectedYear = 2026,
        ).single()

        assertEquals(Instant.parse("2026-09-20T12:00:00Z"), row.observedAt)
        assertEquals(59.9667, row.latitude)
        assertEquals(30.3, row.longitude)
        assertEquals(4.0, row.elevationMeters)

        val temperature = row.values.getValue(GhcnhVariable.TEMPERATURE)
        assertEquals(11.2, temperature.value)
        with(temperature.attributes) {
            assertNull(measurementCode)
            assertEquals("5", qualityCode)
            assertEquals("METAR", reportType)
            assertEquals("220", sourceCode)
            assertEquals("26063-99999", sourceStationId)
        }

        val precipitation = row.values.getValue(GhcnhVariable.PRECIPITATION_3_HOUR)
        assertEquals(2.4, precipitation.value)
        assertEquals("1", precipitation.attributes.qualityCode)
    }

    @Test
    fun rejectsIdentityTimestampAndSchemaDrift() {
        val valid = fixture(
            stationId = "RSM00026063",
            date = "2026-09-20T12:00:00Z",
            temperature = "11.2",
            temperatureQuality = "5",
        )

        assertFailsWith<IllegalArgumentException> {
            GhcnhObservationParser.parse(valid, "USW00094846", 2026)
        }
        assertFailsWith<IllegalArgumentException> {
            GhcnhObservationParser.parse(valid, "RSM00026063", 2025)
        }

        val inconsistentMinute = valid.replace(
            "|2026|09|20|12|00|",
            "|2026|09|20|12|01|",
        )
        assertFailsWith<IllegalArgumentException> {
            GhcnhObservationParser.parse(inconsistentMinute, "RSM00026063", 2026)
        }

        val missingAttributeHeader = valid
            .lineSequence()
            .mapIndexed { index, line ->
                if (index == 0) {
                    line.replace("|temperature_Source_Station_ID", "")
                } else {
                    line
                }
            }
            .joinToString("\n")
        assertFailsWith<IllegalArgumentException> {
            GhcnhObservationParser.parse(
                missingAttributeHeader,
                "RSM00026063",
                2026,
            )
        }
    }

    @Test
    fun acceptsDocumentedUtcDateWithoutExplicitOffset() {
        val row = GhcnhObservationParser.parse(
            fixture(
                stationId = "RSM00026063",
                date = "2026-09-20T12:00:00",
                temperature = "11.2",
                temperatureQuality = "5",
            ),
            "RSM00026063",
            2026,
        ).single()

        assertEquals(Instant.parse("2026-09-20T12:00:00Z"), row.observedAt)
    }

    @Test
    fun normalizerUsesQualityCompletenessAndExplicitPrecipitationIntervals() {
        val observedAt = Instant.parse("2026-09-20T12:00:00Z")
        val parserRows = GhcnhObservationParser.parse(
            listOf(
                fixture(
                    stationId = "RSM00026063",
                    date = "2026-09-20T12:00:00Z",
                    temperature = "10.0",
                    temperatureQuality = "5",
                    pressure = "1012.0",
                    pressureQuality = "5",
                    precipitation = "9.9",
                    precipitationQuality = "5",
                    precipitation3h = "2.4",
                    precipitation3hQuality = "1",
                ).lineSequence().last(),
                fixture(
                    stationId = "RSM00026063",
                    date = "2026-09-20T12:00:00Z",
                    temperature = "20.0",
                    temperatureQuality = "5",
                    pressure = "",
                    pressureQuality = "",
                    precipitation = "",
                    precipitationQuality = "",
                    precipitation3h = "",
                    precipitation3hQuality = "",
                ).lineSequence().last(),
            ).joinToString(
                separator = "\n",
                prefix = fixtureHeader() + "\n",
            ),
            "RSM00026063",
            2026,
        )

        val series = GhcnhObservationNormalizer.normalize(
            candidate = candidate("RSM00026063"),
            observations = parserRows,
            startInclusive = observedAt.minusSeconds(60),
            endExclusive = observedAt.plusSeconds(60),
        )

        val surface = series.surfaceObservations.single()
        assertEquals(10.0, surface.temperatureC)
        assertEquals(1012.0, surface.pressureSeaLevelHpa)

        val precipitation = series.precipitationObservations.single()
        assertEquals(2.4, precipitation.amountMm)
        assertEquals(observedAt, precipitation.interval.end)
        assertEquals(observedAt.minusSeconds(3 * 60 * 60L), precipitation.interval.start)

        assertTrue(
            series.evidence.single { row ->
                row.values[GhcnhVariable.PRECIPITATION]?.value == 9.9
            }.values.containsKey(GhcnhVariable.PRECIPITATION),
        )
    }

    @Test
    fun equalCompletenessConflictsBecomeMissingAndBadQcNeverWins() {
        val observedAt = Instant.parse("2026-09-20T12:00:00Z")
        val rows = GhcnhObservationParser.parse(
            listOf(
                fixture(
                    stationId = "RSM00026063",
                    date = "2026-09-20T12:00:00Z",
                    temperature = "10.0",
                    temperatureQuality = "5",
                    pressure = "1012.0",
                    pressureQuality = "5",
                ).lineSequence().last(),
                fixture(
                    stationId = "RSM00026063",
                    date = "2026-09-20T12:00:00Z",
                    temperature = "20.0",
                    temperatureQuality = "5",
                    pressure = "1012.0",
                    pressureQuality = "5",
                ).lineSequence().last(),
                fixture(
                    stationId = "RSM00026063",
                    date = "2026-09-20T12:00:00Z",
                    temperature = "99.0",
                    temperatureQuality = "2",
                    pressure = "1012.0",
                    pressureQuality = "2",
                ).lineSequence().last(),
            ).joinToString(
                separator = "\n",
                prefix = fixtureHeader() + "\n",
            ),
            "RSM00026063",
            2026,
        )

        val surface = GhcnhObservationNormalizer.normalize(
            candidate("RSM00026063"),
            rows,
            observedAt.minusSeconds(1),
            observedAt.plusSeconds(1),
        ).surfaceObservations.single()

        assertNull(surface.temperatureC)
        assertEquals(1012.0, surface.pressureSeaLevelHpa)
    }

    @Test
    fun tracePrecipitationIsPreservedButNotFabricatedAsZero() {
        val observedAt = Instant.parse("2026-09-20T12:00:00Z")
        val row = GhcnhObservationParser.parse(
            fixture(
                stationId = "RSM00026063",
                date = "2026-09-20T12:00:00Z",
                temperature = "",
                temperatureQuality = "",
                precipitation3h = "0.0",
                precipitation3hMeasurement = "T",
                precipitation3hQuality = "5",
            ),
            "RSM00026063",
            2026,
        ).single()

        val series = GhcnhObservationNormalizer.normalize(
            candidate("RSM00026063"),
            listOf(row),
            observedAt.minusSeconds(1),
            observedAt.plusSeconds(1),
        )

        assertTrue(series.precipitationObservations.isEmpty())
        assertEquals(
            "T",
            series.evidence.single()
                .values.getValue(GhcnhVariable.PRECIPITATION_3_HOUR)
                .attributes.measurementCode,
        )
    }

    private fun candidate(id: String): GhcnhStationCandidate =
        GhcnhStationCandidate(
            station = GhcnhStationMetadata(
                stationId = id,
                latitude = 59.9667,
                longitude = 30.3,
                elevationMeters = 4.0,
                state = null,
                name = "ST PETERSBURG",
                gsn = false,
                hcnCrn = null,
                wmoId = "26063",
                icao = "ULLI",
            ),
            distanceKm = 2.0,
            elevationDeltaMeters = 1.0,
        )

    private fun fixture(
        stationId: String,
        date: String,
        temperature: String,
        temperatureQuality: String,
        pressure: String = "",
        pressureQuality: String = "",
        precipitation: String = "",
        precipitationQuality: String = "",
        precipitation3h: String = "",
        precipitation3hMeasurement: String = "",
        precipitation3hQuality: String = "",
    ): String {
        val timestamp = if (date.endsWith("Z")) {
            Instant.parse(date)
        } else {
            Instant.parse(date + "Z")
        }.atOffset(java.time.ZoneOffset.UTC)

        val row = buildList {
            add(stationId)
            add("ST PETERSBURG")
            add(date)
            add(timestamp.year.toString())
            add(timestamp.monthValue.toString().padStart(2, '0'))
            add(timestamp.dayOfMonth.toString().padStart(2, '0'))
            add(timestamp.hour.toString().padStart(2, '0'))
            add(timestamp.minute.toString().padStart(2, '0'))
            add("59.9667")
            add("30.3000")
            add("4.0")
            addAll(valueFields(temperature, "", temperatureQuality))
            addAll(valueFields(pressure, "", pressureQuality))
            addAll(valueFields(precipitation, "", precipitationQuality))
            addAll(
                valueFields(
                    precipitation3h,
                    precipitation3hMeasurement,
                    precipitation3hQuality,
                ),
            )
        }.joinToString("|")
        return fixtureHeader() + "\n" + row
    }

    private fun fixtureHeader(): String = buildList {
        addAll(
            listOf(
                "Station_ID",
                "Station_name",
                "Date",
                "Year",
                "Month",
                "Day",
                "Hour",
                "Minute",
                "Latitude",
                "Longitude",
                "Elevation",
            ),
        )
        addAll(variableHeaders("temperature"))
        addAll(variableHeaders("sea_level_pressure"))
        addAll(variableHeaders("precipitation"))
        addAll(variableHeaders("precipitation_3_hour"))
    }.joinToString("|")

    private fun variableHeaders(name: String): List<String> = listOf(
        name,
        "${name}_Measurement_Code",
        "${name}_Quality_Code",
        "${name}_Report_Type",
        "${name}_Source_Code",
        "${name}_Source_Station_ID",
    )

    private fun valueFields(
        value: String,
        measurement: String,
        quality: String,
    ): List<String> = listOf(
        value,
        measurement,
        quality,
        "METAR",
        "220",
        "26063-99999",
    )
}
