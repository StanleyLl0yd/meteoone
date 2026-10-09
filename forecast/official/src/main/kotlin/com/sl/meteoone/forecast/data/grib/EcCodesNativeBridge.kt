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

object EcCodesNativeLibrary {
    private const val DEFAULT_LIBRARY_NAME = "meteoone_grib_jni"
    private const val DEFAULT_IDENTITY = "<java.library.path>"
    private val loadLock = Any()

    @Volatile
    private var loadedIdentity: String? = null

    internal fun ensureDefaultLoaded() {
        if (loadedIdentity != null) return
        synchronized(loadLock) {
            if (loadedIdentity != null) return
            System.loadLibrary(DEFAULT_LIBRARY_NAME)
            loadedIdentity = DEFAULT_IDENTITY
        }
    }
}

object EcCodesNativeBridge {
    external fun nativeConfigureDefinitions(definitionsPath: String)

    external fun nativeDecode(
        payload: ByteArray,
        maxMessages: Int,
        maxTotalValues: Int,
    ): Array<NativeGribMessage>
}

private object BridgeEcCodesNativeApi : EcCodesNativeApi {
    override fun configureDefinitions(definitionsPath: String) {
        EcCodesNativeLibrary.ensureDefaultLoaded()
        EcCodesNativeBridge.nativeConfigureDefinitions(definitionsPath)
    }

    override fun decode(
        payload: ByteArray,
        maxMessages: Int,
        maxTotalValues: Int,
    ): Array<NativeGribMessage> {
        EcCodesNativeLibrary.ensureDefaultLoaded()
        return EcCodesNativeBridge.nativeDecode(
            payload = payload,
            maxMessages = maxMessages,
            maxTotalValues = maxTotalValues,
        )
    }
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

// Used by the Android native session in :forecast:data.
@Suppress("unused")
object ProductionEcCodesNativeApi : EcCodesNativeApi by SerializedEcCodesNativeApi(
    delegate = BridgeEcCodesNativeApi,
)
