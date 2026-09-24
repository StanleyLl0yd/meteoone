package com.sl.meteoone.backend.servernative

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ServerEcCodesRuntimeTest {
    @Test
    fun verifiedBundleRejectsMutatedRuntimeFile() {
        val root = Files.createTempDirectory("meteoone-server-native-test")
        try {
            writeFixture(root)
            ServerEcCodesBundleVerifier.verify(root)

            Files.writeString(root.resolve("lib/libaec.so"), "mutated")

            assertFailsWith<IllegalArgumentException> {
                ServerEcCodesBundleVerifier.verify(root)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun verifiedNativeBundleLoadsSharedJniBridgeWhenConfigured() {
        val configured = System.getProperty("meteoone.server.eccodes.bundle") ?: return
        val session = ServerEcCodesRuntime.open(Path.of(configured))
        assertNotNull(session)

        assertFailsWith<IllegalArgumentException> {
            session.decode(
                payload = byteArrayOf(0x47, 0x52, 0x49, 0x42),
                maxMessages = 1,
                maxTotalValues = 1,
            )
        }
    }

    private fun writeFixture(root: Path) {
        val files = linkedMapOf<String, ByteArray>()
        for (relative in listOf(
            "lib/libaec.so",
            "lib/libeccodes.so",
            "lib/libmeteoone_grib_jni.so",
            "lib/libsz.so",
            "licenses/eccodes-LICENSE",
            "licenses/eccodes-NOTICE",
            "licenses/libaec-LICENSE.txt",
        )) {
            files[relative] = "fixture-$relative".encodeToByteArray()
        }
        files.forEach { (relative, bytes) ->
            val path = root.resolve(relative)
            Files.createDirectories(path.parent)
            Files.write(path, bytes)
        }

        val definition = root.resolve("definitions/grib2/boot.def")
        Files.createDirectories(definition.parent)
        Files.writeString(definition, "fixture-definition")
        val definitionHash = sha256(definition)
        val definitionsManifest = "$definitionHash  grib2/boot.def\n"
        root.resolve("definitions.sha256").writeText(definitionsManifest)

        val manifested = linkedMapOf<String, ByteArray>()
        manifested.putAll(files)
        manifested["definitions.sha256"] = definitionsManifest.encodeToByteArray()
        val fileJson = manifested.entries.joinToString(",\n") { (relative, bytes) ->
            """    "$relative": {"bytes": ${bytes.size}, "sha256": "${sha256(bytes)}"}"""
        }
        root.resolve("manifest.json").writeText(
            """
            {
              "schema_version": 1,
              "platform": "linux-x86_64",
              "eccodes_commit": "19e71ddcc8f45862a3909b4dae690c3cc95bbc16",
              "libaec_commit": "7204505af7d6635734fc12a38d6bd0a6253c9c6d",
              "ecbuild_commit": "60e7d659ec10a4316e0ec27be28254092dcd7921",
              "threads": false,
              "definition_file_count": 1,
              "files": {
            $fileJson
              }
            }
            """.trimIndent(),
        )
    }

    private fun sha256(path: Path): String = sha256(Files.readAllBytes(path))

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
}
