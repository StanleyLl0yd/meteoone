package com.sl.meteoone.forecast.data.grib

internal class NativeGribMessage(
    val metadata: LongArray,
    val geometry: DoubleArray,
    val values: DoubleArray,
)

internal interface EcCodesNativeApi {
    fun configureDefinitions(definitionsPath: String)

    fun decode(
        payload: ByteArray,
        maxMessages: Int,
        maxTotalValues: Int,
    ): Array<NativeGribMessage>
}

internal object EcCodesNativeBridge {
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

internal object ProductionEcCodesNativeApi : EcCodesNativeApi {
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
