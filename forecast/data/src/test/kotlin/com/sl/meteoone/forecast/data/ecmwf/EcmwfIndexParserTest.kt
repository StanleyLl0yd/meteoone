package com.sl.meteoone.forecast.data.ecmwf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EcmwfIndexParserTest {
    @Test
    fun parsesOfficialShapeJsonLinesAndIgnoresUnrelatedKeys() {
        val content = """
            {"domain":"g","date":"20260910","time":"0600","expver":"0001","class":"od","type":"fc","stream":"oper","step":"6","levtype":"sfc","param":"2t","_offset":17459800,"_length":609046,"extra":"ignored"}
            {"domain":"g","date":"20260910","time":"0600","class":"od","type":"fc","stream":"oper","step":"6","levtype":"sfc","param":"msl","_offset":18068846,"_length":500000}
        """.trimIndent()

        val entries = EcmwfIndexParser.parse(content)

        assertEquals(2, entries.size)
        assertEquals("2t", entries[0].parameter)
        assertEquals(17_459_800L, entries[0].range.offset)
        assertEquals(609_046L, entries[0].range.length)
        assertEquals("msl", entries[1].parameter)
    }

    @Test
    fun ignoresBlankLinesBetweenRecords() {
        val content = """

            {"domain":"g","date":"20260910","time":"0000","class":"od","type":"fc","stream":"oper","step":"0","levtype":"sfc","param":"2t","_offset":0,"_length":1}

        """.trimIndent()

        assertEquals(1, EcmwfIndexParser.parse(content).size)
    }

    @Test
    fun rejectsMalformedOrIncompleteRecords() {
        assertFailsWith<IllegalArgumentException> {
            EcmwfIndexParser.parse("not-json")
        }
        assertFailsWith<IllegalArgumentException> {
            EcmwfIndexParser.parse(
                """{"domain":"g","date":"20260910","time":"0000","class":"od","type":"fc","stream":"oper","step":"0","levtype":"sfc","param":"2t","_length":1}""",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            EcmwfIndexParser.parse(
                """{"domain":"g","date":"20260910","time":"0000","class":"od","type":"fc","stream":"oper","step":"x","levtype":"sfc","param":"2t","_offset":0,"_length":1}""",
            )
        }
    }

    @Test
    fun rejectsWrongJsonTypesInsteadOfCoercingThem() {
        assertFailsWith<IllegalArgumentException> {
            EcmwfIndexParser.parse(
                """{"domain":"g","date":"20260910","time":"0000","class":"od","type":"fc","stream":"oper","step":0,"levtype":"sfc","param":"2t","_offset":0,"_length":1}""",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            EcmwfIndexParser.parse(
                """{"domain":"g","date":"20260910","time":"0000","class":"od","type":"fc","stream":"oper","step":"0","levtype":"sfc","param":"2t","_offset":"0","_length":1}""",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            EcmwfIndexParser.parse(
                """{"domain":"g","date":"20260910","time":"0000","class":"od","type":"fc","stream":"oper","step":"0","levtype":"sfc","param":2,"_offset":0,"_length":1}""",
            )
        }
    }

    @Test
    fun rejectsInvalidRangesAndEmptyIndexes() {
        assertFailsWith<IllegalArgumentException> {
            EcmwfIndexParser.parse(
                """{"domain":"g","date":"20260910","time":"0000","class":"od","type":"fc","stream":"oper","step":"0","levtype":"sfc","param":"2t","_offset":0,"_length":0}""",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            EcmwfIndexParser.parse("\n\n")
        }
    }
}
