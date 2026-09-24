package com.sl.meteoone.forecast.data.ecmwf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class EcmwfIndexParserPrivacyTest {
    @Test
    fun malformedAndNonObjectJsonDoNotExposeIndexContentInExceptions() {
        val secret = "meteoone-sensitive-index-sentinel"
        val inputs = listOf(
            """{"secret":"$secret","broken":[}""",
            "\"$secret\"",
        )

        inputs.forEach { content ->
            val error = assertFailsWith<IllegalArgumentException> {
                EcmwfIndexParser.parse(content)
            }

            assertEquals("ECMWF index line 1 is not a valid JSON object", error.message)
            assertFalse(error.message.orEmpty().contains(secret))
            assertNull(error.cause)
        }
    }
}
