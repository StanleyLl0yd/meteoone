package com.sl.meteoone.forecast.data.grib

import android.content.Context
import com.sl.meteoone.forecast.data.dwd.DwdIconGridGeometryPlan
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset

private const val NATIVE_METADATA_LONG_COUNT = 35
private const val NATIVE_GEOMETRY_DOUBLE_COUNT = 4
private const val MAX_GRID_VALUES = 3_000_000
private const val MAX_NOAA_MESSAGES = 64
private const val MAX_NOAA_TOTAL_VALUES = 64
private const val MAX_SINGLE_FIELD_MESSAGES = 1
private const val MISSING_LONG = Long.MIN_VALUE

internal fun interface EcCodesNativeSession {
    fun decode(
        payload: ByteArray,
        maxMessages: Int,
        maxTotalValues: Int,
    ): Array<NativeGribMessage>
}

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

internal fun interface DwdIconGridGeometryProvider {
    fun geometryFor(plan: DwdIconGridGeometryPlan): DwdIconGridGeometry
}

internal class EcCodesGribFieldDecoder(
    private val nativeSession: EcCodesNativeSession,
    private val dwdGeometryProvider: DwdIconGridGeometryProvider,
) : GribFieldDecoder {
    override fun decode(request: GribDecodeRequest): List<DecodedGribField> {
        val limits = when (request.context) {
            is OfficialGribDecodeContext.Noaa -> NativeDecodeLimits(
                maxMessages = MAX_NOAA_MESSAGES,
                maxTotalValues = MAX_NOAA_TOTAL_VALUES,
            )
            is OfficialGribDecodeContext.Ecmwf,
            is OfficialGribDecodeContext.Dwd -> NativeDecodeLimits(
                maxMessages = MAX_SINGLE_FIELD_MESSAGES,
                maxTotalValues = MAX_GRID_VALUES,
            )
        }
        val messages = nativeSession.decode(
            payload = request.payload,
            maxMessages = limits.maxMessages,
            maxTotalValues = limits.maxTotalValues,
        )
        require(messages.isNotEmpty()) { "ecCodes returned no GRIB messages" }
        if (request.context !is OfficialGribDecodeContext.Noaa) {
            require(messages.size == 1) { "Official single-field GRIB request returned multiple messages" }
        }

        val dwdGeometry = if (request.context is OfficialGribDecodeContext.Dwd) {
            dwdGeometryProvider.geometryFor(request.context.geometryPlan).also { geometry ->
                require(geometry.modelRun == request.context.modelRun) {
                    "DWD geometry provider returned another model run"
                }
            }
        } else {
            null
        }

        return buildList {
            messages.forEach { nativeMessage ->
                val decoded = nativeMessage.decode(request.context, dwdGeometry)
                if (decoded != null) add(decoded)
            }
        }
    }

    companion object {
        fun android(
            context: Context,
            dwdGeometryProvider: DwdIconGridGeometryProvider,
        ): EcCodesGribFieldDecoder = EcCodesGribFieldDecoder(
            nativeSession = AndroidEcCodesNativeSession(context),
            dwdGeometryProvider = dwdGeometryProvider,
        )
    }
}

private data class NativeDecodeLimits(
    val maxMessages: Int,
    val maxTotalValues: Int,
)

private fun NativeGribMessage.decode(
    context: OfficialGribDecodeContext,
    dwdGeometry: DwdIconGridGeometry?,
): DecodedGribField? {
    require(metadata.size == NATIVE_METADATA_LONG_COUNT) {
        "Native GRIB metadata layout does not match the Kotlin boundary"
    }
    require(geometry.size == NATIVE_GEOMETRY_DOUBLE_COUNT) {
        "Native GRIB geometry layout does not match the Kotlin boundary"
    }
    require(values.isNotEmpty() && values.size <= MAX_GRID_VALUES) {
        "Native GRIB values exceed the Kotlin grid boundary"
    }
    require(requiredLong(28) == values.size.toLong()) {
        "Native GRIB value cardinality metadata is inconsistent"
    }

    val messageMetadata = toMessageMetadata()
    val selectedPoint = when (context) {
        is OfficialGribDecodeContext.Noaa -> GribPointSelector.selectNoaa(
            context = context,
            gridDefinitionTemplate = messageMetadata.gridDefinitionTemplate,
            latitudes = doubleArrayOf(requiredGeometry(0, "first latitude")),
            longitudesDegreesEast = doubleArrayOf(requiredGeometry(1, "first longitude")),
            values = values,
        )
        is OfficialGribDecodeContext.Ecmwf -> GribPointSelector.selectEcmwf(
            context = context,
            gridDefinitionTemplate = messageMetadata.gridDefinitionTemplate,
            geometry = EcmwfRegularLatLonGeometry(
                latitudeCount = requiredInt(30, "Nj"),
                longitudeCount = requiredInt(29, "Ni"),
                firstLatitude = requiredGeometry(0, "first latitude"),
                firstLongitudeDegreesEast = requiredGeometry(1, "first longitude"),
                latitudeIncrementDegrees = requiredGeometry(3, "j-direction increment"),
                longitudeIncrementDegrees = requiredGeometry(2, "i-direction increment"),
                iScansNegatively = requiredFlag(31, "iScansNegatively"),
                jScansPositively = requiredFlag(32, "jScansPositively"),
                jPointsAreConsecutive = requiredFlag(33, "jPointsAreConsecutive"),
                alternativeRowScanning = requiredFlag(34, "alternativeRowScanning"),
            ),
            values = values,
        )
        is OfficialGribDecodeContext.Dwd -> GribPointSelector.selectDwd(
            context = context,
            gridDefinitionTemplate = messageMetadata.gridDefinitionTemplate,
            geometry = requireNotNull(dwdGeometry) { "DWD decode requires run-bound grid geometry" },
            values = values,
        )
    }
    return GribSemanticBinder.bind(context, messageMetadata, selectedPoint)
}

private fun NativeGribMessage.toMessageMetadata(): GribMessageMetadata = GribMessageMetadata(
    edition = requiredInt(0, "edition"),
    discipline = requiredInt(1, "discipline"),
    parameterCategory = requiredInt(2, "parameterCategory"),
    parameterNumber = requiredInt(3, "parameterNumber"),
    productDefinitionTemplate = requiredInt(4, "productDefinitionTemplateNumber"),
    gridDefinitionTemplate = requiredInt(5, "gridDefinitionTemplateNumber"),
    dataRepresentationTemplate = requiredInt(6, "dataRepresentationTemplateNumber"),
    referenceTime = requiredInstant(7),
    forecastTimeUnit = requiredInt(13, "indicatorOfUnitOfTimeRange"),
    forecastTime = requiredLong(14),
    firstFixedSurfaceType = requiredInt(15, "typeOfFirstFixedSurface"),
    firstFixedSurfaceScaleFactor = requiredInt(16, "scaleFactorOfFirstFixedSurface"),
    firstFixedSurfaceScaledValue = requiredLong(17),
    intervalEnd = optionalInstant(18),
    numberOfTimeRanges = optionalInt(24, "numberOfTimeRanges"),
    statisticalProcess = optionalInt(25, "typeOfStatisticalProcessing"),
    timeRangeUnit = optionalInt(26, "indicatorOfUnitForTimeRange"),
    timeRangeLength = optionalLong(27),
)

private fun NativeGribMessage.requiredInstant(startIndex: Int) = instantFromSix(
    year = requiredInt(startIndex, "year"),
    month = requiredInt(startIndex + 1, "month"),
    day = requiredInt(startIndex + 2, "day"),
    hour = requiredInt(startIndex + 3, "hour"),
    minute = requiredInt(startIndex + 4, "minute"),
    second = requiredInt(startIndex + 5, "second"),
)

private fun NativeGribMessage.optionalInstant(startIndex: Int) =
    metadata.sliceArray(startIndex until startIndex + 6).let { parts ->
        if (parts.all { it == MISSING_LONG }) {
            null
        } else {
            require(parts.none { it == MISSING_LONG }) {
                "Native GRIB interval end is only partially populated"
            }
            instantFromSix(
                year = parts[0].toExactInt("interval year"),
                month = parts[1].toExactInt("interval month"),
                day = parts[2].toExactInt("interval day"),
                hour = parts[3].toExactInt("interval hour"),
                minute = parts[4].toExactInt("interval minute"),
                second = parts[5].toExactInt("interval second"),
            )
        }
    }

private fun instantFromSix(
    year: Int,
    month: Int,
    day: Int,
    hour: Int,
    minute: Int,
    second: Int,
) = try {
    LocalDateTime.of(year, month, day, hour, minute, second).toInstant(ZoneOffset.UTC)
} catch (error: DateTimeException) {
    throw IllegalArgumentException("Native GRIB timestamp is invalid", error)
}

private fun NativeGribMessage.requiredLong(index: Int): Long = metadata[index].also { value ->
    require(value != MISSING_LONG) { "Native GRIB required metadata is missing at index $index" }
}

private fun NativeGribMessage.optionalLong(index: Int): Long? = metadata[index].let { value ->
    if (value == MISSING_LONG) null else value
}

private fun NativeGribMessage.requiredInt(index: Int, label: String): Int =
    requiredLong(index).toExactInt(label)

private fun NativeGribMessage.optionalInt(index: Int, label: String): Int? =
    optionalLong(index)?.toExactInt(label)

private fun Long.toExactInt(label: String): Int {
    require(this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        "Native GRIB $label is outside the Kotlin Int range"
    }
    return toInt()
}

private fun NativeGribMessage.requiredFlag(index: Int, label: String): Boolean =
    when (val value = requiredInt(index, label)) {
        0 -> false
        1 -> true
        else -> throw IllegalArgumentException("Native GRIB $label must be 0 or 1, got $value")
    }

private fun NativeGribMessage.requiredGeometry(index: Int, label: String): Double = geometry[index].also { value ->
    require(value.isFinite()) { "Native GRIB $label is missing or non-finite" }
}
