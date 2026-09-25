package com.sl.meteoone.forecast.data.grib

import android.content.Context

internal class AndroidEcCodesNativeSession(
    context: Context,
    private val nativeApi: EcCodesNativeApi = ProductionEcCodesNativeApi,
) : EcCodesNativeSession {
    private val installer = EcCodesDefinitionsInstaller(context.applicationContext)
    private val configureLock = Any()

    @Volatile
    private var configured = false

    override fun decode(
        payload: ByteArray,
        maxMessages: Int,
        maxTotalValues: Int,
    ): Array<NativeGribMessage> {
        ensureConfigured()
        return nativeApi.decode(payload, maxMessages, maxTotalValues)
    }

    private fun ensureConfigured() {
        if (configured) return
        synchronized(configureLock) {
            if (configured) return
            val definitions = installer.install()
            nativeApi.configureDefinitions(definitions.absolutePath)
            configured = true
        }
    }
}
