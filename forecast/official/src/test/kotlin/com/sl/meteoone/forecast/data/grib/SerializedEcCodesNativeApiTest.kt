package com.sl.meteoone.forecast.data.grib

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SerializedEcCodesNativeApiTest {
    @Test
    fun serializesConcurrentDecodeCalls() {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val callCount = AtomicInteger()
        val delegate = object : EcCodesNativeApi {
            override fun configureDefinitions(definitionsPath: String) = Unit

            override fun decode(
                payload: ByteArray,
                maxMessages: Int,
                maxTotalValues: Int,
            ): Array<NativeGribMessage> {
                when (callCount.incrementAndGet()) {
                    1 -> {
                        firstEntered.countDown()
                        check(releaseFirst.await(WAIT_SECONDS, TimeUnit.SECONDS))
                    }
                    2 -> secondEntered.countDown()
                    else -> error("unexpected decode call")
                }
                return emptyArray()
            }
        }
        val api = SerializedEcCodesNativeApi(delegate)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val first = executor.submit {
                api.decode(byteArrayOf(1), maxMessages = 1, maxTotalValues = 1)
            }
            assertTrue(firstEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))

            val second = executor.submit {
                secondStarted.countDown()
                api.decode(byteArrayOf(2), maxMessages = 1, maxTotalValues = 1)
            }
            assertTrue(secondStarted.await(WAIT_SECONDS, TimeUnit.SECONDS))
            assertFalse(secondEntered.await(BLOCKED_PROBE_MILLIS, TimeUnit.MILLISECONDS))

            releaseFirst.countDown()
            first.get(WAIT_SECONDS, TimeUnit.SECONDS)
            second.get(WAIT_SECONDS, TimeUnit.SECONDS)

            assertTrue(secondEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))
            assertEquals(2, callCount.get())
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun serializesConfigurationAgainstDecode() {
        val configureEntered = CountDownLatch(1)
        val releaseConfigure = CountDownLatch(1)
        val decodeStarted = CountDownLatch(1)
        val decodeEntered = CountDownLatch(1)
        val delegate = object : EcCodesNativeApi {
            override fun configureDefinitions(definitionsPath: String) {
                configureEntered.countDown()
                check(releaseConfigure.await(WAIT_SECONDS, TimeUnit.SECONDS))
            }

            override fun decode(
                payload: ByteArray,
                maxMessages: Int,
                maxTotalValues: Int,
            ): Array<NativeGribMessage> {
                decodeEntered.countDown()
                return emptyArray()
            }
        }
        val api = SerializedEcCodesNativeApi(delegate)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val configure = executor.submit {
                api.configureDefinitions("/definitions")
            }
            assertTrue(configureEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))

            val decode = executor.submit {
                decodeStarted.countDown()
                api.decode(byteArrayOf(1), maxMessages = 1, maxTotalValues = 1)
            }
            assertTrue(decodeStarted.await(WAIT_SECONDS, TimeUnit.SECONDS))
            assertFalse(decodeEntered.await(BLOCKED_PROBE_MILLIS, TimeUnit.MILLISECONDS))

            releaseConfigure.countDown()
            configure.get(WAIT_SECONDS, TimeUnit.SECONDS)
            decode.get(WAIT_SECONDS, TimeUnit.SECONDS)

            assertTrue(decodeEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))
        } finally {
            releaseConfigure.countDown()
            executor.shutdownNow()
        }
    }

    private companion object {
        const val WAIT_SECONDS = 5L
        const val BLOCKED_PROBE_MILLIS = 200L
    }
}
