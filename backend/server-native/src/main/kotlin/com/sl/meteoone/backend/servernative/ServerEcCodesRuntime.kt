package com.sl.meteoone.backend.servernative

import com.sl.meteoone.forecast.data.grib.EcCodesNativeLibrary
import com.sl.meteoone.forecast.data.grib.EcCodesNativeSession
import com.sl.meteoone.forecast.data.grib.ProductionEcCodesNativeApi
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

private const val EXPECTED_ECCODES_COMMIT = "19e71ddcc8f45862a3909b4dae690c3cc95bbc16"
private const val EXPECTED_LIBAEC_COMMIT = "7204505af7d6635734fc12a38d6bd0a6253c9c6d"
private const val EXPECTED_ECBUILD_COMMIT = "60e7d659ec10a4316e0ec27be28254092dcd7921"
private const val MAX_DEFINITION_FILES = 100_000
private val SHA256 = Regex("^[0-9a-f]{64}$")

private val EXPECTED_BUNDLE_FILES = setOf(
    "definitions.sha256",
    "lib/libaec.so",
    "lib/libeccodes.so",
    "lib/libmeteoone_grib_jni.so",
    "lib/libsz.so",
    "licenses/eccodes-LICENSE",
    "licenses/eccodes-NOTICE",
    "licenses/libaec-LICENSE.txt",
)

class VerifiedServerEcCodesBundle internal constructor(
    val root: Path,
    val bridgeLibrary: Path,
    val definitionsDirectory: Path,
)

object ServerEcCodesBundleVerifier {
    private val json = Json

    fun verify(root: Path): VerifiedServerEcCodesBundle {
        requireLinuxX8664()
        val normalizedRoot = root.toAbsolutePath().normalize()
        require(
            Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(normalizedRoot),
        ) {
            "Server ecCodes bundle root must be a real directory"
        }

        val manifestPath = safeRegularFile(normalizedRoot, "manifest.json")
        val manifest = try {
            json.parseToJsonElement(Files.readString(manifestPath)).jsonObject
        } catch (error: Exception) {
            throw IllegalArgumentException("Server ecCodes manifest is invalid", error)
        }
        require(
            manifest.keys == setOf(
                "schema_version",
                "platform",
                "eccodes_commit",
                "libaec_commit",
                "ecbuild_commit",
                "threads",
                "definition_file_count",
                "files",
            ),
        ) {
            "Server ecCodes manifest fields drifted"
        }
        require(manifest.longField("schema_version") == 1L)
        require(manifest.stringField("platform") == "linux-x86_64")
        require(manifest.stringField("eccodes_commit") == EXPECTED_ECCODES_COMMIT)
        require(manifest.stringField("libaec_commit") == EXPECTED_LIBAEC_COMMIT)
        require(manifest.stringField("ecbuild_commit") == EXPECTED_ECBUILD_COMMIT)
        require(!manifest.booleanField("threads")) {
            "Server ecCodes runtime must preserve the serialized non-threaded build contract"
        }

        val fileCount = manifest.longField("definition_file_count")
        require(fileCount in 1..MAX_DEFINITION_FILES.toLong()) {
            "Server ecCodes definition-file count is out of bounds"
        }

        val files = requireNotNull(manifest["files"] as? JsonObject) {
            "Server ecCodes manifest files must be an object"
        }
        require(files.keys == EXPECTED_BUNDLE_FILES) {
            "Server ecCodes runtime file set drifted"
        }
        EXPECTED_BUNDLE_FILES.forEach { relative ->
            val descriptor = requireNotNull(files[relative] as? JsonObject) {
                "Server ecCodes manifest entry is invalid: $relative"
            }
            require(descriptor.keys == setOf("bytes", "sha256")) {
                "Server ecCodes manifest entry fields drifted: $relative"
            }
            val expectedBytes = descriptor.longField("bytes")
            val expectedSha = descriptor.stringField("sha256")
            require(expectedBytes > 0L && SHA256.matches(expectedSha)) {
                "Server ecCodes manifest entry is invalid: $relative"
            }
            verifyFile(
                path = safeRegularFile(normalizedRoot, relative),
                expectedBytes = expectedBytes,
                expectedSha = expectedSha,
            )
        }

        val definitions = normalizedRoot.resolve("definitions").normalize()
        require(
            definitions.startsWith(normalizedRoot) &&
                Files.isDirectory(definitions, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(definitions),
        ) {
            "Server ecCodes definitions directory is invalid"
        }
        verifyDefinitions(
            definitions = definitions,
            manifestPath = safeRegularFile(normalizedRoot, "definitions.sha256"),
            expectedCount = fileCount.toInt(),
        )

        return VerifiedServerEcCodesBundle(
            root = normalizedRoot,
            bridgeLibrary = safeRegularFile(
                normalizedRoot,
                "lib/libmeteoone_grib_jni.so",
            ),
            definitionsDirectory = definitions,
        )
    }

    private fun verifyDefinitions(
        definitions: Path,
        manifestPath: Path,
        expectedCount: Int,
    ) {
        val listed = linkedMapOf<String, String>()
        Files.readAllLines(manifestPath).forEachIndexed { index, raw ->
            require(raw.isNotBlank()) {
                "Definitions hash manifest contains a blank line at ${index + 1}"
            }
            val separator = raw.indexOf("  ")
            require(separator == 64) {
                "Definitions hash manifest line ${index + 1} is malformed"
            }
            val hash = raw.substring(0, separator)
            val relativeText = raw.substring(separator + 2)
            require(SHA256.matches(hash) && relativeText.isNotBlank()) {
                "Definitions hash manifest line ${index + 1} is invalid"
            }
            val relative = Path.of(relativeText)
            require(!relative.isAbsolute && relative.normalize() == relative) {
                "Definitions hash path is not canonical: $relativeText"
            }
            require(relative.none { it.toString() == ".." }) {
                "Definitions hash path escapes its root: $relativeText"
            }
            val normalizedText = relative.joinToString("/") { it.toString() }
            require(normalizedText !in listed) {
                "Definitions hash manifest contains a duplicate path: $normalizedText"
            }
            listed[normalizedText] = hash
        }
        require(listed.size == expectedCount) {
            "Definitions hash manifest cardinality drifted"
        }

        val actual = linkedSetOf<String>()
        Files.walk(definitions).use { stream ->
            val iterator = stream.iterator()
            while (iterator.hasNext()) {
                val path = iterator.next()
                if (path == definitions) continue
                require(!Files.isSymbolicLink(path)) {
                    "Server ecCodes definitions must not contain symlinks"
                }
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue
                require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    "Server ecCodes definitions contain a non-regular entry"
                }
                val relative = definitions.relativize(path)
                    .joinToString("/") { it.toString() }
                actual += relative
                val expectedSha = requireNotNull(listed[relative]) {
                    "Unlisted server ecCodes definition file: $relative"
                }
                require(sha256(path) == expectedSha) {
                    "Server ecCodes definition hash mismatch: $relative"
                }
            }
        }
        require(actual == listed.keys) {
            "Server ecCodes definitions file set drifted"
        }
    }

    private fun safeRegularFile(root: Path, relative: String): Path {
        val relativePath = Path.of(relative)
        require(!relativePath.isAbsolute && relativePath.normalize() == relativePath) {
            "Server ecCodes bundle path is not canonical: $relative"
        }
        val path = root.resolve(relativePath).normalize()
        require(path.startsWith(root)) {
            "Server ecCodes bundle path escapes its root: $relative"
        }
        var current = root
        relativePath.forEach { component ->
            current = current.resolve(component)
            require(!Files.isSymbolicLink(current)) {
                "Server ecCodes bundle must not contain symlinks: $relative"
            }
        }
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "Server ecCodes bundle file is missing: $relative"
        }
        return path
    }

    private fun verifyFile(
        path: Path,
        expectedBytes: Long,
        expectedSha: String,
    ) {
        require(Files.size(path) == expectedBytes) {
            "Server ecCodes bundle file size drifted: ${path.fileName}"
        }
        require(sha256(path) == expectedSha) {
            "Server ecCodes bundle file hash drifted: ${path.fileName}"
        }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            updateDigest(digest, input)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun updateDigest(
        digest: MessageDigest,
        input: InputStream,
    ) {
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            if (read > 0) digest.update(buffer, 0, read)
        }
    }

    private fun requireLinuxX8664() {
        require(System.getProperty("os.name").lowercase(Locale.ROOT).contains("linux")) {
            "Server ecCodes native bundle is Linux-only"
        }
        require(System.getProperty("os.arch").lowercase(Locale.ROOT) in setOf("amd64", "x86_64")) {
            "Server ecCodes native bundle requires x86_64"
        }
    }

    private fun JsonObject.stringField(name: String): String =
        requireNotNull(this[name]) { "Missing manifest field $name" }
            .jsonPrimitive.content

    private fun JsonObject.longField(name: String): Long =
        requireNotNull(this[name]) { "Missing manifest field $name" }
            .jsonPrimitive.long

    private fun JsonObject.booleanField(name: String): Boolean =
        requireNotNull(this[name]) { "Missing manifest field $name" }
            .jsonPrimitive.boolean
}

object ServerEcCodesRuntime {
    fun open(bundleRoot: Path): EcCodesNativeSession {
        val bundle = ServerEcCodesBundleVerifier.verify(bundleRoot)
        EcCodesNativeLibrary.loadAbsolute(bundle.bridgeLibrary.toString())
        ProductionEcCodesNativeApi.configureDefinitions(
            bundle.definitionsDirectory.toString(),
        )
        return EcCodesNativeSession { payload, maxMessages, maxTotalValues ->
            ProductionEcCodesNativeApi.decode(
                payload = payload,
                maxMessages = maxMessages,
                maxTotalValues = maxTotalValues,
            )
        }
    }
}
