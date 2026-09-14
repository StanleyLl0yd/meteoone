package com.sl.meteoone.forecast.data.grib

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

private const val DEFINITIONS_ASSET = "eccodes-definitions.zip"
private const val DEFINITIONS_SHA256 = "600af8a8c746dd541397c54ac13554155f2f9e7b7eea6d06f02afe54f3be2007"
private const val MAX_DEFINITIONS_ARCHIVE_BYTES = 8 * 1024 * 1024
private const val MAX_DEFINITION_FILES = 20_000
private const val MAX_DEFINITION_FILE_BYTES = 16 * 1024 * 1024
private const val MAX_DEFINITION_TOTAL_BYTES = 128 * 1024 * 1024
private const val MAX_DEFINITION_PATH_LENGTH = 512

internal class EcCodesDefinitionsInstaller(
    private val context: Context,
) {
    fun install(): File = synchronized(INSTALL_LOCK) {
        val parent = File(context.codeCacheDir, "meteoone-eccodes")
        val destination = File(parent, DEFINITIONS_SHA256)
        val marker = File(destination, ".complete")
        if (marker.isFile && marker.readText() == DEFINITIONS_SHA256) {
            return@synchronized destination
        }

        parent.mkdirs()
        require(parent.isDirectory) { "Unable to create ecCodes runtime directory" }
        destination.deleteRecursively()

        val temporary = File(parent, ".${DEFINITIONS_SHA256}-${System.nanoTime()}")
        require(temporary.mkdirs()) { "Unable to create temporary ecCodes definitions directory" }
        try {
            context.assets.open(DEFINITIONS_ASSET).use { input ->
                EcCodesDefinitionsArchive.extractAndVerify(
                    input = input,
                    destination = temporary,
                    expectedSha256 = DEFINITIONS_SHA256,
                )
            }
            File(temporary, ".complete").writeText(DEFINITIONS_SHA256)
            if (!temporary.renameTo(destination)) {
                check(
                    File(destination, ".complete").isFile &&
                        File(destination, ".complete").readText() == DEFINITIONS_SHA256,
                ) { "Unable to publish ecCodes definitions atomically" }
            }
            destination
        } finally {
            temporary.deleteRecursively()
        }
    }

    private companion object {
        val INSTALL_LOCK = Any()
    }
}

internal object EcCodesDefinitionsArchive {
    fun extractAndVerify(
        input: InputStream,
        destination: File,
        expectedSha256: String,
    ) {
        require(expectedSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Expected ecCodes definitions digest must be lowercase SHA-256"
        }
        require(destination.isDirectory) { "Definitions destination must already exist" }

        val archive = input.readBounded(MAX_DEFINITIONS_ARCHIVE_BYTES)
        require(archive.sha256() == expectedSha256) {
            "Packaged ecCodes definitions digest does not match the verified native bundle"
        }

        val destinationPath = destination.canonicalFile.toPath()
        var fileCount = 0
        var totalBytes = 0L
        val names = HashSet<String>()

        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                require(name.isNotEmpty() && name.length <= MAX_DEFINITION_PATH_LENGTH) {
                    "ecCodes definitions ZIP contains an invalid entry path"
                }
                require('\\' !in name && !name.startsWith('/')) {
                    "ecCodes definitions ZIP contains a non-portable entry path"
                }
                require(names.add(name)) {
                    "ecCodes definitions ZIP contains a duplicate entry"
                }

                val output = File(destination, name).canonicalFile
                require(output.toPath().startsWith(destinationPath)) {
                    "ecCodes definitions ZIP attempts path traversal"
                }

                if (entry.isDirectory) {
                    require(output.mkdirs() || output.isDirectory) {
                        "Unable to create ecCodes definitions directory"
                    }
                    zip.closeEntry()
                    continue
                }

                fileCount += 1
                require(fileCount <= MAX_DEFINITION_FILES) {
                    "ecCodes definitions ZIP contains too many files"
                }
                output.parentFile?.let { parent ->
                    require(parent.mkdirs() || parent.isDirectory) {
                        "Unable to create ecCodes definitions parent directory"
                    }
                }

                var entryBytes = 0L
                output.outputStream().use { target ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        require(entryBytes + read <= MAX_DEFINITION_FILE_BYTES) {
                            "ecCodes definition file exceeds its extraction limit"
                        }
                        require(totalBytes + read <= MAX_DEFINITION_TOTAL_BYTES) {
                            "ecCodes definitions exceed the total extraction limit"
                        }
                        target.write(buffer, 0, read)
                        entryBytes += read
                        totalBytes += read
                    }
                }
                zip.closeEntry()
            }
        }

        require(fileCount > 0) { "ecCodes definitions ZIP contains no files" }
    }

    private fun InputStream.readBounded(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            if (read == 0) continue
            require(read <= maxBytes - total) {
                "ecCodes definitions ZIP exceeds the packaged archive limit"
            }
            output.write(buffer, 0, read)
            total += read
        }
        require(total > 0) { "ecCodes definitions ZIP must not be empty" }
        return output.toByteArray()
    }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).toHex()

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}
