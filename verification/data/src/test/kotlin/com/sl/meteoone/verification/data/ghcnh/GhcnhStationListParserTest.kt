package com.sl.meteoone.verification.data.ghcnh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GhcnhStationListParserTest {
    @Test
    fun parsesDocumentedFixedWidthMetadataAndMissingElevation() {
        val content = listOf(
            stationLine(
                id = "USW00094846",
                latitude = "41.9600",
                longitude = "-87.9317",
                elevation = "204.8",
                state = "IL",
                name = "CHICAGO OHARE INTL AP",
                gsn = "GSN",
                hcn = "HCN",
                wmo = "72530",
                icao = "KORD",
            ),
            stationLine(
                id = "RSM00026063",
                latitude = "59.9667",
                longitude = "30.3000",
                elevation = "-999.9",
                name = "ST PETERSBURG",
                wmo = "26063",
                icao = "ULLI",
            ),
        ).joinToString("\n")

        val stations = GhcnhStationListParser.parse(content)

        assertEquals(2, stations.size)
        with(stations[0]) {
            assertEquals("USW00094846", stationId)
            assertEquals(41.96, latitude)
            assertEquals(-87.9317, longitude)
            assertEquals(204.8, elevationMeters)
            assertEquals("IL", state)
            assertEquals("CHICAGO OHARE INTL AP", name)
            assertEquals(true, gsn)
            assertEquals(GhcnhClimateNetwork.HCN, hcnCrn)
            assertEquals("72530", wmoId)
            assertEquals("KORD", icao)
        }
        with(stations[1]) {
            assertNull(elevationMeters)
            assertNull(state)
            assertEquals(false, gsn)
            assertNull(hcnCrn)
            assertEquals("26063", wmoId)
            assertEquals("ULLI", icao)
        }
    }

    @Test
    fun trailingOptionalMetadataMayBeAbsent() {
        val line = stationLine(
            id = "RSM00026063",
            latitude = "59.9667",
            longitude = "30.3000",
            elevation = "4.0",
            name = "ST PETERSBURG",
        ).trimEnd()

        val station = GhcnhStationListParser.parse(line).single()

        assertNull(station.wmoId)
        assertNull(station.icao)
    }

    @Test
    fun rejectsTruncatedDuplicateAndUnknownFlags() {
        assertFailsWith<IllegalArgumentException> {
            GhcnhStationListParser.parse("USW00094846")
        }

        val line = stationLine(
            id = "USW00094846",
            latitude = "41.9600",
            longitude = "-87.9317",
            elevation = "204.8",
            name = "CHICAGO OHARE INTL AP",
        )
        assertFailsWith<IllegalArgumentException> {
            GhcnhStationListParser.parse("$line\n$line")
        }

        assertFailsWith<IllegalArgumentException> {
            GhcnhStationListParser.parse(
                stationLine(
                    id = "USW00094846",
                    latitude = "41.9600",
                    longitude = "-87.9317",
                    elevation = "204.8",
                    name = "CHICAGO OHARE INTL AP",
                    gsn = "BAD",
                ),
            )
        }
    }

    @Test
    fun rejectsInvalidCoordinatesAndIdentifiers() {
        assertFailsWith<IllegalArgumentException> {
            GhcnhStationListParser.parse(
                stationLine(
                    id = "BAD ID     ",
                    latitude = "41.9600",
                    longitude = "-87.9317",
                    elevation = "204.8",
                    name = "BAD",
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GhcnhStationListParser.parse(
                stationLine(
                    id = "USW00094846",
                    latitude = "91.0000",
                    longitude = "-87.9317",
                    elevation = "204.8",
                    name = "BAD",
                ),
            )
        }
    }

    private fun stationLine(
        id: String,
        latitude: String,
        longitude: String,
        elevation: String,
        state: String = "",
        name: String,
        gsn: String = "",
        hcn: String = "",
        wmo: String = "",
        icao: String = "",
    ): String {
        val chars = CharArray(90) { ' ' }
        put(chars, 0, 11, id)
        put(chars, 12, 20, latitude)
        put(chars, 21, 30, longitude)
        put(chars, 31, 37, elevation)
        put(chars, 38, 40, state)
        put(chars, 41, 71, name)
        put(chars, 72, 75, gsn)
        put(chars, 76, 79, hcn)
        put(chars, 80, 85, wmo)
        put(chars, 86, 90, icao)
        return chars.concatToString()
    }

    private fun put(
        chars: CharArray,
        start: Int,
        end: Int,
        value: String,
    ) {
        require(value.length <= end - start)
        value.forEachIndexed { index, char -> chars[start + index] = char }
    }
}
