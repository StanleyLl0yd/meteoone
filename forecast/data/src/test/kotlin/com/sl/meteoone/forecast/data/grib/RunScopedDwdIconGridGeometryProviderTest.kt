package com.sl.meteoone.forecast.data.grib

import com.sl.meteoone.core.network.BoundedHttpsCall
import com.sl.meteoone.core.network.BoundedHttpsFailureReason
import com.sl.meteoone.core.network.BoundedHttpsRequest
import com.sl.meteoone.core.network.BoundedHttpsResponse
import com.sl.meteoone.core.network.BoundedHttpsResult
import com.sl.meteoone.core.network.BoundedHttpsTransport
import com.sl.meteoone.forecast.data.dwd.BoundedDwdBzip2Decompressor
import com.sl.meteoone.forecast.data.dwd.DwdIconGridGeometryPlanner
import com.sl.meteoone.forecast.data.transport.ForecastHttpAdapter
import java.io.ByteArrayOutputStream
import java.time.Instant
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class RunScopedDwdIconGridGeometryProviderTest {
    private val runA = Instant.parse("2026-09-10T18:00:00Z")
    private val runB = Instant.parse("2026-09-11T00:00:00Z")

    @Test
    fun `same run reuses one fully validated in-memory geometry`() {
        val transport = GeometryTransport()
        var nativeCalls = 0
        val session = EcCodesNativeSession { payload, maxMessages, maxTotalValues ->
            nativeCalls += 1
            assertEquals(1, maxMessages)
            assertEquals(3_000_000, maxTotalValues)
            when (payload.decodeToString()) {
                "latitude" -> arrayOf(coordinateMessage(1, doubleArrayOf(10.0, -20.0)))
                "longitude" -> arrayOf(coordinateMessage(2, doubleArrayOf(-10.0, 200.0)))
                else -> error("Unexpected geometry fixture")
            }
        }
        val provider = provider(transport, session)
        val plan = DwdIconGridGeometryPlanner.plan(runA)

        val first = provider.geometryFor(plan)
        val second = provider.geometryFor(plan)

        assertSame(first, second)
        assertEquals(runA, first.modelRun)
        assertEquals(2, first.pointCount)
        assertEquals(10.0, first.coordinateAt(0).latitude)
        assertEquals(350.0, first.coordinateAt(0).longitudeDegreesEast)
        assertEquals(-20.0, first.coordinateAt(1).latitude)
        assertEquals(200.0, first.coordinateAt(1).longitudeDegreesEast)
        assertEquals(2, transport.requests.size)
        assertEquals(2, nativeCalls)
    }

    @Test
    fun `different run reloads both coordinate fields and never reuses another run`() {
        val transport = GeometryTransport()
        val provider = provider(transport, geometrySession())
        val planA = DwdIconGridGeometryPlanner.plan(runA)
        val planB = DwdIconGridGeometryPlanner.plan(runB)

        assertEquals(runA, provider.geometryFor(planA).modelRun)
        assertEquals(runA, provider.geometryFor(planA).modelRun)
        assertEquals(runB, provider.geometryFor(planB).modelRun)
        assertEquals(runB, provider.geometryFor(planB).modelRun)

        assertEquals(4, transport.requests.size)
        assertEquals(
            listOf(
                planA.latitudeRequest.uri,
                planA.longitudeRequest.uri,
                planB.latitudeRequest.uri,
                planB.longitudeRequest.uri,
            ),
            transport.requests.map { it.uri },
        )
    }

    @Test
    fun `failed replacement does not publish partial geometry or corrupt prior run`() {
        val transport = GeometryTransport().apply {
            failLongitudeForRunToken = "2026091100"
        }
        val provider = provider(transport, geometrySession())
        val planA = DwdIconGridGeometryPlanner.plan(runA)
        val planB = DwdIconGridGeometryPlanner.plan(runB)
        val geometryA = provider.geometryFor(planA)

        assertFailsWith<IllegalStateException> {
            provider.geometryFor(planB)
        }
        assertSame(geometryA, provider.geometryFor(planA))
        assertEquals(5, transport.requests.size)
        assertEquals(2, transport.requests.count { it.uri == planB.longitudeRequest.uri })
    }

    @Test
    fun `coordinate signature and gdt drift fail closed`() {
        val invalidMessages = listOf(
            coordinateMessage(2, doubleArrayOf(10.0)),
            coordinateMessage(1, doubleArrayOf(10.0), gridTemplate = 0),
            coordinateMessage(1, doubleArrayOf(10.0), parameterCategory = 190),
        )

        for (invalidLatitude in invalidMessages) {
            val provider = provider(
                GeometryTransport(),
                EcCodesNativeSession { payload, _, _ ->
                    when (payload.decodeToString()) {
                        "latitude" -> arrayOf(invalidLatitude)
                        "longitude" -> arrayOf(coordinateMessage(2, doubleArrayOf(20.0)))
                        else -> error("Unexpected geometry fixture")
                    }
                },
            )
            assertFailsWith<IllegalArgumentException> {
                provider.geometryFor(DwdIconGridGeometryPlanner.plan(runA))
            }
        }
    }

    @Test
    fun `clat clon cardinality mismatch and non-finite values fail closed`() {
        val mismatch = provider(
            GeometryTransport(),
            EcCodesNativeSession { payload, _, _ ->
                when (payload.decodeToString()) {
                    "latitude" -> arrayOf(coordinateMessage(1, doubleArrayOf(10.0, 11.0)))
                    "longitude" -> arrayOf(coordinateMessage(2, doubleArrayOf(20.0)))
                    else -> error("Unexpected geometry fixture")
                }
            },
        )
        assertFailsWith<IllegalArgumentException> {
            mismatch.geometryFor(DwdIconGridGeometryPlanner.plan(runA))
        }

        val nonFinite = provider(
            GeometryTransport(),
            EcCodesNativeSession { payload, _, _ ->
                when (payload.decodeToString()) {
                    "latitude" -> arrayOf(coordinateMessage(1, doubleArrayOf(Double.NaN)))
                    "longitude" -> arrayOf(coordinateMessage(2, doubleArrayOf(20.0)))
                    else -> error("Unexpected geometry fixture")
                }
            },
        )
        assertFailsWith<IllegalArgumentException> {
            nonFinite.geometryFor(DwdIconGridGeometryPlanner.plan(runA))
        }
    }

    private fun provider(
        transport: GeometryTransport,
        nativeSession: EcCodesNativeSession,
    ) = RunScopedDwdIconGridGeometryProvider(
        httpAdapter = ForecastHttpAdapter(transport),
        decompressor = BoundedDwdBzip2Decompressor(),
        nativeSession = nativeSession,
    )

    private fun geometrySession() = EcCodesNativeSession { payload, _, _ ->
        when (payload.decodeToString()) {
            "latitude" -> arrayOf(coordinateMessage(1, doubleArrayOf(10.0, -20.0)))
            "longitude" -> arrayOf(coordinateMessage(2, doubleArrayOf(-10.0, 200.0)))
            else -> error("Unexpected geometry fixture")
        }
    }

    private fun coordinateMessage(
        parameterNumber: Int,
        values: DoubleArray,
        gridTemplate: Int = 101,
        parameterCategory: Int = 191,
    ): NativeGribMessage {
        val metadata = LongArray(35) { Long.MIN_VALUE }
        metadata[0] = 2
        metadata[1] = 0
        metadata[2] = parameterCategory.toLong()
        metadata[3] = parameterNumber.toLong()
        metadata[4] = 0
        metadata[5] = gridTemplate.toLong()
        metadata[6] = 42
        metadata[28] = values.size.toLong()
        return NativeGribMessage(
            metadata = metadata,
            geometry = DoubleArray(4) { Double.NaN },
            values = values,
        )
    }

    private class GeometryTransport : BoundedHttpsTransport {
        val requests = mutableListOf<BoundedHttpsRequest>()
        var failLongitudeForRunToken: String? = null

        override fun newCall(request: BoundedHttpsRequest): BoundedHttpsCall {
            requests += request
            val path = request.uri.path
            val shouldFail =
                path.contains("/clon/") &&
                    failLongitudeForRunToken?.let(path::contains) == true
            val result = if (shouldFail) {
                BoundedHttpsResult.Failure(BoundedHttpsFailureReason.IO)
            } else {
                val marker = if (path.contains("/clat/")) "latitude" else "longitude"
                BoundedHttpsResult.Success(
                    BoundedHttpsResponse(
                        statusCode = 200,
                        headers = emptyMap(),
                        body = bzip2(marker),
                    ),
                )
            }
            return object : BoundedHttpsCall {
                override fun execute(): BoundedHttpsResult = result
                override fun cancel() = Unit
            }
        }
    }

    private companion object {
        fun bzip2(value: String): ByteArray {
            val output = ByteArrayOutputStream()
            BZip2CompressorOutputStream(output).use { compressed ->
                compressed.write(value.encodeToByteArray())
            }
            return output.toByteArray()
        }
    }
}
