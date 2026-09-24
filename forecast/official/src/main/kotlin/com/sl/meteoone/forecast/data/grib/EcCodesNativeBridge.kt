package com.sl.meteoone.forecast.data.grib

class NativeGribMessage(
    val metadata: LongArray,
    val geometry: DoubleArray,
    val values: DoubleArray,
)

interface EcCodesNativeApi {
    fun configureDefinitions(definitionsPath: String)

    fun decode(
        payload: ByteArray,
        maxMessages: Int,
        maxTotalValues: Int,
    ): Array<NativeGribMessage>
}

object EcCodesNativeBridge {
    init {
        System.loadLibrary("meteoone_grib_jni")
    }

    external fun nativeConfigureDefinitions(definitionsPath: String)

    external fun nativeDecode(
        payload: ByteArray,
        maxMessages: Int,
        maxTotalValues: Int,
    ): Array<NativeGribMessage>
}

private object BridgeEcCodesNativeApi : EcCodesNativeApi {
    override fun configureDefinitions(definitionsPath: String) {
        EcCodesNativeBridge.nativeConfigureDefinitions(definitionsPath)
    }

    override fun decode(
        payload: ByteArray,
        maxMessages: Int,
        maxTotalValues: Int,
    ): Array<NativeGribMessage> = EcCodesNativeBridge.nativeDecode(
        payload = payload,
        maxMessages = maxMessages,
        maxTotalValues = maxTotalValues,
    )
}

/**
 * Serializes access to one ecCodes API delegate.
 *
 * The vendored M1 ecCodes bundle is built without `ENABLE_ECCODES_THREADS`, so configuration and
 * decode calls must not overlap even when separate engine/session instances are used concurrently.
 */
internal class SerializedEcCodesNativeApi(
    private val delegate: EcCodesNativeApi,
    private val lock: Any = Any(),
) : EcCodesNativeApi {
    override fun configureDefinitions(definitionsPath: String) {
        synchronized(lock) {
            delegate.configureDefinitions(definitionsPath)
        }
    }

    override fun decode(
        payload: ByteArray,
        maxMessages: Int,
        maxTotalValues: Int,
    ): Array<NativeGribMessage> = synchronized(lock) {
        delegate.decode(
            payload = payload,
            maxMessages = maxMessages,
            maxTotalValues = maxTotalValues,
        )
    }
}

object ProductionEcCodesNativeApi : EcCodesNativeApi by SerializedEcCodesNativeApi(
    delegate = BridgeEcCodesNativeApi,
)
