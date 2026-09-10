package com.sl.meteoone.forecast.data.ecmwf

import com.sl.meteoone.forecast.data.source.ByteRange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val MAX_INDEX_BYTES = 2 * 1024 * 1024
private const val MAX_INDEX_LINE_BYTES = 64 * 1024

data class EcmwfIndexEntry(
    val domain: String,
    val date: String,
    val time: String,
    val dataClass: String,
    val type: String,
    val stream: String,
    val step: Int,
    val levelType: String,
    val parameter: String,
    val range: ByteRange,
)

object EcmwfIndexParser {
    private val json = Json

    fun parse(content: String): List<EcmwfIndexEntry> {
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_INDEX_BYTES) {
            "ECMWF index exceeds the configured size limit"
        }

        val entries = mutableListOf<EcmwfIndexEntry>()
        content.lineSequence().forEachIndexed { zeroBasedLine, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEachIndexed

            val lineNumber = zeroBasedLine + 1
            require(line.toByteArray(Charsets.UTF_8).size <= MAX_INDEX_LINE_BYTES) {
                "ECMWF index line $lineNumber exceeds the configured size limit"
            }

            val objectValue = try {
                json.parseToJsonElement(line).jsonObject
            } catch (error: Exception) {
                throw IllegalArgumentException(
                    "ECMWF index line $lineNumber is not a valid JSON object",
                    error,
                )
            }

            entries += parseEntry(objectValue, lineNumber)
        }

        require(entries.isNotEmpty()) { "ECMWF index contains no entries" }
        return entries
    }

    private fun parseEntry(
        value: JsonObject,
        lineNumber: Int,
    ): EcmwfIndexEntry {
        fun string(key: String): String =
            value[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException(
                    "ECMWF index line $lineNumber is missing string field $key",
                )

        val step = string("step").toIntOrNull()
            ?: throw IllegalArgumentException(
                "ECMWF index line $lineNumber has an invalid step",
            )
        require(step >= 0) { "ECMWF index line $lineNumber has a negative step" }

        val offset = value["_offset"]?.jsonPrimitive?.longOrNull
            ?: throw IllegalArgumentException(
                "ECMWF index line $lineNumber has an invalid _offset",
            )
        val length = value["_length"]?.jsonPrimitive?.longOrNull
            ?: throw IllegalArgumentException(
                "ECMWF index line $lineNumber has an invalid _length",
            )

        val range = try {
            ByteRange(offset = offset, length = length)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException(
                "ECMWF index line $lineNumber has an invalid byte range",
                error,
            )
        }

        return EcmwfIndexEntry(
            domain = string("domain"),
            date = string("date"),
            time = string("time"),
            dataClass = string("class"),
            type = string("type"),
            stream = string("stream"),
            step = step,
            levelType = string("levtype"),
            parameter = string("param"),
            range = range,
        )
    }
}
