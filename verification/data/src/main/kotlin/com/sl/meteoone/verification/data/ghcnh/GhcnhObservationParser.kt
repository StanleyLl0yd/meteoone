package com.sl.meteoone.verification.data.ghcnh

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal object GhcnhObservationParser {
    private const val MISSING_ELEVATION = -999.9

    fun parse(
        content: String,
        expectedStationId: String,
        expectedYear: Int,
    ): List<GhcnhRawObservation> {
        require(expectedStationId.matches(Regex("^[A-Z0-9]{11}$"))) {
            "Expected GHCNh station id is invalid"
        }
        require(expectedYear in 1790..9999) {
            "Expected GHCNh observation year is out of bounds"
        }

        val lines = content.lineSequence()
            .map { it.removeSuffix("\r") }
            .filter { it.isNotBlank() }
            .iterator()
        require(lines.hasNext()) { "GHCNh PSV must contain a header" }

        val schema = HeaderSchema(lines.next().removePrefix("\uFEFF"))
        val observations = ArrayList<GhcnhRawObservation>()
        var dataLineNumber = 1
        while (lines.hasNext()) {
            dataLineNumber += 1
            val row = splitPsv(lines.next())
            require(row.size == schema.size) {
                "GHCNh PSV line $dataLineNumber has ${row.size} columns; expected ${schema.size}"
            }
            observations += parseRow(
                schema = schema,
                row = row,
                lineNumber = dataLineNumber,
                expectedStationId = expectedStationId,
                expectedYear = expectedYear,
            )
        }
        return observations
    }

    private fun parseRow(
        schema: HeaderSchema,
        row: List<String>,
        lineNumber: Int,
        expectedStationId: String,
        expectedYear: Int,
    ): GhcnhRawObservation {
        val stationId = schema.required(row, lineNumber, "station_id", "station")
        require(stationId == expectedStationId) {
            "GHCNh PSV line $lineNumber station id does not match the requested station"
        }

        val observedAt = parseUtcInstant(
            schema.required(row, lineNumber, "date", "datetime"),
            lineNumber,
        )
        val utc = observedAt.atOffset(ZoneOffset.UTC)
        require(utc.year == expectedYear) {
            "GHCNh PSV line $lineNumber timestamp is outside the requested year"
        }
        schema.validateIntIfPresent(row, lineNumber, "year", utc.year)
        schema.validateIntIfPresent(row, lineNumber, "month", utc.monthValue)
        schema.validateIntIfPresent(row, lineNumber, "day", utc.dayOfMonth)
        schema.validateIntIfPresent(row, lineNumber, "hour", utc.hour)
        schema.validateIntIfPresent(row, lineNumber, "minute", utc.minute)

        val latitude = schema.requiredFiniteDouble(row, lineNumber, "latitude")
        val longitude = schema.requiredFiniteDouble(row, lineNumber, "longitude")
        require(latitude in -90.0..90.0) {
            "GHCNh PSV line $lineNumber latitude is out of range"
        }
        require(longitude in -180.0..180.0) {
            "GHCNh PSV line $lineNumber longitude is out of range"
        }
        val elevationRaw = schema.required(row, lineNumber, "elevation").toDoubleOrNull()
            ?: throw IllegalArgumentException(
                "GHCNh PSV line $lineNumber elevation is not numeric",
            )
        require(elevationRaw.isFinite()) {
            "GHCNh PSV line $lineNumber elevation must be finite"
        }
        val elevation = elevationRaw.takeUnless { it == MISSING_ELEVATION }

        val values = buildMap {
            GhcnhVariable.entries.forEach { variable ->
                schema.variableEvidence(row, lineNumber, variable)?.let { evidence ->
                    put(variable, evidence)
                }
            }
        }

        return GhcnhRawObservation(
            stationId = stationId,
            observedAt = observedAt,
            latitude = latitude,
            longitude = longitude,
            elevationMeters = elevation,
            values = values,
        )
    }
}

private class HeaderSchema(headerLine: String) {
    private val headers = splitPsv(headerLine)
    private val indexes: Map<String, Int>

    val size: Int
        get() = headers.size

    init {
        require(headers.isNotEmpty()) { "GHCNh PSV header must not be empty" }
        val mapped = LinkedHashMap<String, Int>()
        headers.forEachIndexed { index, raw ->
            val normalized = normalizeHeader(raw)
            require(normalized.isNotBlank()) {
                "GHCNh PSV header contains a blank column name"
            }
            require(mapped.put(normalized, index) == null) {
                "GHCNh PSV header contains duplicate column $raw"
            }
        }
        indexes = mapped

        require(indexOf("station_id", "station") != null) {
            "GHCNh PSV is missing Station_ID/STATION"
        }
        require(indexOf("date", "datetime") != null) {
            "GHCNh PSV is missing the documented ISO date-time column"
        }
        listOf("latitude", "longitude", "elevation").forEach { required ->
            require(indexOf(required) != null) {
                "GHCNh PSV is missing required column $required"
            }
        }
    }

    fun required(
        row: List<String>,
        lineNumber: Int,
        vararg names: String,
    ): String {
        val index = indexOf(*names)
            ?: throw IllegalArgumentException(
                "GHCNh PSV is missing required column ${names.joinToString("/")}",
            )
        val value = row[index].trim()
        require(value.isNotEmpty()) {
            "GHCNh PSV line $lineNumber has blank ${names.first()}"
        }
        return value
    }

    fun requiredFiniteDouble(
        row: List<String>,
        lineNumber: Int,
        vararg names: String,
    ): Double {
        val raw = required(row, lineNumber, *names)
        val value = raw.toDoubleOrNull()
            ?: throw IllegalArgumentException(
                "GHCNh PSV line $lineNumber has non-numeric ${names.first()}",
            )
        require(value.isFinite()) {
            "GHCNh PSV line $lineNumber has non-finite ${names.first()}"
        }
        return value
    }

    fun validateIntIfPresent(
        row: List<String>,
        lineNumber: Int,
        name: String,
        expected: Int,
    ) {
        val index = indexOf(name) ?: return
        val actual = row[index].trim().toIntOrNull()
            ?: throw IllegalArgumentException(
                "GHCNh PSV line $lineNumber has invalid $name",
            )
        require(actual == expected) {
            "GHCNh PSV line $lineNumber $name disagrees with its ISO timestamp"
        }
    }

    fun variableEvidence(
        row: List<String>,
        lineNumber: Int,
        variable: GhcnhVariable,
    ): GhcnhValueEvidence? {
        val valueIndex = indexOf(variable.header) ?: return null
        val attributeNames = listOf(
            "${variable.header}_measurement_code",
            "${variable.header}_quality_code",
            "${variable.header}_report_type",
            "${variable.header}_source_code",
            "${variable.header}_source_station_id",
        )
        val attributeIndexes = attributeNames.map { name ->
            indexOf(name)
                ?: throw IllegalArgumentException(
                    "GHCNh PSV variable ${variable.header} is missing attribute column $name",
                )
        }

        val rawValue = row[valueIndex].trim()
        val value = if (rawValue.isEmpty()) {
            null
        } else {
            rawValue.toDoubleOrNull()
                ?.takeIf { it.isFinite() }
                ?: throw IllegalArgumentException(
                    "GHCNh PSV line $lineNumber has invalid ${variable.header}",
                )
        }

        fun attribute(position: Int): String? =
            row[attributeIndexes[position]].trim().ifEmpty { null }

        return GhcnhValueEvidence(
            value = value,
            attributes = GhcnhValueAttributes(
                measurementCode = attribute(0),
                qualityCode = attribute(1),
                reportType = attribute(2),
                sourceCode = attribute(3),
                sourceStationId = attribute(4),
            ),
        )
    }

    private fun indexOf(vararg names: String): Int? =
        names.firstNotNullOfOrNull { name -> indexes[normalizeHeader(name)] }
}

private fun normalizeHeader(value: String): String =
    value.trim().removePrefix("\uFEFF").lowercase()

private fun parseUtcInstant(
    value: String,
    lineNumber: Int,
): Instant {
    try {
        return Instant.parse(value)
    } catch (_: DateTimeException) {
        // Continue: GHCNh documents UTC and some producers omit an explicit Z/offset.
    }
    try {
        return OffsetDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME).toInstant()
    } catch (_: DateTimeException) {
        // Continue with an offset-less ISO local date-time interpreted as documented UTC.
    }
    try {
        return LocalDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME)
            .toInstant(ZoneOffset.UTC)
    } catch (error: DateTimeException) {
        throw IllegalArgumentException(
            "GHCNh PSV line $lineNumber has invalid ISO UTC date-time",
            error,
        )
    }
}

private fun splitPsv(line: String): List<String> {
    val fields = ArrayList<String>()
    var start = 0
    line.forEachIndexed { index, character ->
        if (character == '|') {
            fields += line.substring(start, index)
            start = index + 1
        }
    }
    fields += line.substring(start)
    return fields
}
