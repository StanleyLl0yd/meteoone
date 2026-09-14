package com.sl.meteoone.forecast.data.grib

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EcCodesDefinitionsArchiveTest {
    @Test
    fun `verified archive extracts only inside destination`() {
        val archive = zipOf("grib2/test.def" to "definition".encodeToByteArray())
        val destination = createTempDirectory("eccodes-definitions-test").toFile()
        try {
            EcCodesDefinitionsArchive.extractAndVerify(
                input = ByteArrayInputStream(archive),
                destination = destination,
                expectedSha256 = archive.sha256(),
            )

            val extracted = destination.resolve("grib2/test.def")
            assertTrue(extracted.isFile)
            assertEquals("definition", extracted.readText())
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun `archive digest drift fails before extraction`() {
        val archive = zipOf("grib2/test.def" to "definition".encodeToByteArray())
        val destination = createTempDirectory("eccodes-definitions-test").toFile()
        try {
            assertFailsWith<IllegalArgumentException> {
                EcCodesDefinitionsArchive.extractAndVerify(
                    input = ByteArrayInputStream(archive),
                    destination = destination,
                    expectedSha256 = "0".repeat(64),
                )
            }
            assertFalse(destination.resolve("grib2/test.def").exists())
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun `path traversal entry fails closed`() {
        val archive = zipOf("../escape.def" to "bad".encodeToByteArray())
        val parent = createTempDirectory("eccodes-definitions-parent").toFile()
        val destination = parent.resolve("definitions").also { check(it.mkdir()) }
        try {
            assertFailsWith<IllegalArgumentException> {
                EcCodesDefinitionsArchive.extractAndVerify(
                    input = ByteArrayInputStream(archive),
                    destination = destination,
                    expectedSha256 = archive.sha256(),
                )
            }
            assertFalse(parent.resolve("escape.def").exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, payload) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(payload)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
}
