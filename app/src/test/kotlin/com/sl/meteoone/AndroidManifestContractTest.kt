package com.sl.meteoone

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidManifestContractTest {
    @Test
    fun approximateLocationPermissionMatchesRuntimeContract() {
        val manifest = sequenceOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).firstOrNull(File::isFile)
            ?: error("Unable to locate app AndroidManifest.xml")

        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)

        val permissions = document
            .getElementsByTagName("uses-permission")
            .let { nodes ->
                buildSet {
                    for (index in 0 until nodes.length) {
                        val element = nodes.item(index)
                        add(
                            element.attributes
                                .getNamedItemNS(ANDROID_NAMESPACE, "name")
                                ?.nodeValue
                                .orEmpty(),
                        )
                    }
                }
            }

        assertTrue(
            "Approximate-location runtime flow requires ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION" in permissions,
        )
        assertFalse(
            "MeteoOne must not request precise location for the approximate-location flow",
            "android.permission.ACCESS_FINE_LOCATION" in permissions,
        )
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
