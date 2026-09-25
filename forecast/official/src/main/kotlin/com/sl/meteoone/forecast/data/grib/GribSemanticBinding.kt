package com.sl.meteoone.forecast.data.grib

import com.sl.meteoone.forecast.data.dwd.DwdIconField
import com.sl.meteoone.forecast.data.ecmwf.EcmwfSurfaceField
import java.time.Duration
import java.time.Instant

private const val GRIB_EDITION_2 = 2
private const val DISCIPLINE_METEOROLOGICAL = 0
private const val PDT_INSTANTANEOUS = 0
private const val PDT_TIME_INTERVAL = 8
private const val GDT_REGULAR_LAT_LON = 0
private const val GDT_ICOSAHEDRAL = 101
private const val DRT_SIMPLE_PACKING = 0
private const val DRT_CCSDS = 42
private const val STATISTICAL_PROCESS_AVERAGE = 0
private const val STATISTICAL_PROCESS_ACCUMULATION = 1
private const val STATISTICAL_PROCESS_MAXIMUM = 2

private const val SURFACE_GROUND_OR_WATER = 1
private const val SURFACE_ENTIRE_ATMOSPHERE = 10
private const val SURFACE_MEAN_SEA_LEVEL = 101
private const val SURFACE_HEIGHT_ABOVE_GROUND = 103

/**
 * MeteoOne-owned metadata extracted from one GRIB2 message by the native decoder.
 *
 * Native/ecCodes types stop before this boundary. Values intentionally mirror the
 * GRIB2 numeric metadata used by the immutable #26 corpus so provider semantics can
 * be validated without trusting short-name strings supplied by unrelated bytes.
 */
data class GribMessageMetadata(
    val edition: Int,
    val discipline: Int,
    val parameterCategory: Int,
    val parameterNumber: Int,
    val productDefinitionTemplate: Int,
    val gridDefinitionTemplate: Int,
    val dataRepresentationTemplate: Int,
    val referenceTime: Instant,
    val forecastTimeUnit: Int,
    val forecastTime: Long,
    val firstFixedSurfaceType: Int,
    val firstFixedSurfaceScaleFactor: Int,
    val firstFixedSurfaceScaledValue: Long,
    val intervalEnd: Instant? = null,
    val numberOfTimeRanges: Int? = null,
    val statisticalProcess: Int? = null,
    val timeRangeUnit: Int? = null,
    val timeRangeLength: Long? = null,
) {
    init {
        require(forecastTime >= 0) { "GRIB forecast time must not be negative" }
        require(timeRangeLength == null || timeRangeLength >= 0) {
            "GRIB time-range length must not be negative"
        }
    }
}

object GribSemanticBinder {
    /**
     * Returns null only for NOAA messages that are valid members of the broad NOMADS
     * response but are not one of MeteoOne's canonical point fields (for example the
     * time-averaged TCDC message returned beside instantaneous TCDC).
     */
    fun bind(
        context: OfficialGribDecodeContext,
        metadata: GribMessageMetadata,
        selectedPoint: SelectedGribGridPoint,
    ): DecodedGribField? {
        require(selectedPoint.value.isFinite()) { "Selected GRIB point value must be finite" }

        val descriptor = when (context) {
            is OfficialGribDecodeContext.Noaa -> noaaDescriptor(metadata) ?: return null
            is OfficialGribDecodeContext.Ecmwf -> ecmwfDescriptor(context.plan.field)
            is OfficialGribDecodeContext.Dwd -> dwdDescriptor(context.plan.field)
                ?: error("DWD ${context.plan.field} has no DecodedGribField mapping in M1")
        }

        validateEnvelope(context, metadata, descriptor)
        val intervalStart = validateTime(context, metadata, descriptor)
        val value = descriptor.transform(selectedPoint.value)
        require(value.isFinite()) { "Canonical GRIB point value must be finite" }

        return DecodedGribField(
            parameter = descriptor.parameter,
            value = value,
            unit = descriptor.unit,
            validTime = context.validTime,
            intervalStart = intervalStart,
        )
    }

    private fun validateEnvelope(
        context: OfficialGribDecodeContext,
        metadata: GribMessageMetadata,
        descriptor: SemanticDescriptor,
    ) {
        require(metadata.edition == GRIB_EDITION_2) { "Official forecast payload must be GRIB edition 2" }
        require(metadata.discipline == descriptor.discipline) {
            "GRIB discipline contradicts the validated provider field"
        }
        require(metadata.parameterCategory == descriptor.parameterCategory) {
            "GRIB parameter category contradicts the validated provider field"
        }
        require(metadata.parameterNumber == descriptor.parameterNumber) {
            "GRIB parameter number contradicts the validated provider field"
        }
        require(metadata.productDefinitionTemplate == descriptor.productDefinitionTemplate) {
            "GRIB product definition template contradicts the validated provider field"
        }
        require(metadata.gridDefinitionTemplate == descriptor.gridDefinitionTemplate) {
            "GRIB grid definition template contradicts the validated provider field"
        }
        require(metadata.dataRepresentationTemplate == descriptor.dataRepresentationTemplate) {
            "GRIB data representation template contradicts the measured provider envelope"
        }
        require(descriptor.surface.matches(metadata)) {
            "GRIB fixed-surface metadata contradicts the validated provider field"
        }
        require(metadata.referenceTime == context.modelRun) {
            "GRIB reference time contradicts the validated provider model run"
        }
    }

    private fun validateTime(
        context: OfficialGribDecodeContext,
        metadata: GribMessageMetadata,
        descriptor: SemanticDescriptor,
    ): Instant? {
        val forecastOffset = gribDuration(metadata.forecastTimeUnit, metadata.forecastTime)
        val startOrValid = metadata.referenceTime.plus(forecastOffset)

        if (descriptor.productDefinitionTemplate == PDT_INSTANTANEOUS) {
            require(startOrValid == context.validTime) {
                "Instantaneous GRIB valid time contradicts the validated provider plan"
            }
            require(metadata.intervalEnd == null) {
                "Instantaneous GRIB message must not carry interval-end metadata"
            }
            return null
        }

        require(descriptor.productDefinitionTemplate == PDT_TIME_INTERVAL)
        val intervalEnd = requireNotNull(metadata.intervalEnd) {
            "Interval GRIB message is missing its explicit interval end"
        }
        require(intervalEnd == context.validTime) {
            "GRIB interval end contradicts the validated provider valid time"
        }
        require(startOrValid.isBefore(intervalEnd)) {
            "GRIB statistical interval must have positive duration"
        }
        require(metadata.numberOfTimeRanges == 1) {
            "Only one GRIB statistical time range is supported by the measured M1 envelope"
        }
        require(metadata.statisticalProcess == descriptor.statisticalProcess) {
            "GRIB statistical process contradicts the validated provider field"
        }

        val rangeUnit = requireNotNull(metadata.timeRangeUnit) {
            "Interval GRIB message is missing its time-range unit"
        }
        val rangeLength = requireNotNull(metadata.timeRangeLength) {
            "Interval GRIB message is missing its time-range length"
        }
        require(gribDuration(rangeUnit, rangeLength) == Duration.between(startOrValid, intervalEnd)) {
            "GRIB interval length contradicts its encoded start and end times"
        }
        return startOrValid
    }

    private fun noaaDescriptor(metadata: GribMessageMetadata): SemanticDescriptor? =
        NOAA_DESCRIPTORS.singleOrNull { descriptor ->
            descriptor.discipline == metadata.discipline &&
                descriptor.parameterCategory == metadata.parameterCategory &&
                descriptor.parameterNumber == metadata.parameterNumber &&
                descriptor.productDefinitionTemplate == metadata.productDefinitionTemplate &&
                descriptor.surface.matches(metadata)
        }

    private fun ecmwfDescriptor(field: EcmwfSurfaceField): SemanticDescriptor = when (field) {
        EcmwfSurfaceField.TEMPERATURE_2M -> ecmwfInstant(
            parameter = GribForecastParameter.TEMPERATURE_2M,
            unit = GribValueUnit.KELVIN,
            category = 0,
            number = 0,
            surface = SurfaceExpectation.height(2),
        )
        EcmwfSurfaceField.DEW_POINT_2M -> ecmwfInstant(
            parameter = GribForecastParameter.DEW_POINT_2M,
            unit = GribValueUnit.KELVIN,
            category = 0,
            number = 6,
            surface = SurfaceExpectation.height(2),
        )
        EcmwfSurfaceField.PRESSURE_MEAN_SEA_LEVEL -> ecmwfInstant(
            parameter = GribForecastParameter.PRESSURE_MEAN_SEA_LEVEL,
            unit = GribValueUnit.PASCAL,
            category = 3,
            number = 0,
            surface = SurfaceExpectation.type(SURFACE_MEAN_SEA_LEVEL),
        )
        EcmwfSurfaceField.WIND_U_10M -> ecmwfInstant(
            parameter = GribForecastParameter.WIND_U_10M,
            unit = GribValueUnit.METRES_PER_SECOND,
            category = 2,
            number = 2,
            surface = SurfaceExpectation.height(10),
        )
        EcmwfSurfaceField.WIND_V_10M -> ecmwfInstant(
            parameter = GribForecastParameter.WIND_V_10M,
            unit = GribValueUnit.METRES_PER_SECOND,
            category = 2,
            number = 3,
            surface = SurfaceExpectation.height(10),
        )
        EcmwfSurfaceField.WIND_GUST_10M_MAXIMUM -> ecmwfInterval(
            parameter = GribForecastParameter.WIND_GUST_10M,
            unit = GribValueUnit.METRES_PER_SECOND,
            category = 2,
            number = 22,
            surface = SurfaceExpectation.height(10),
            statisticalProcess = STATISTICAL_PROCESS_MAXIMUM,
        )
        EcmwfSurfaceField.TOTAL_PRECIPITATION -> ecmwfInterval(
            parameter = GribForecastParameter.PRECIPITATION_ACCUMULATION,
            unit = GribValueUnit.KILOGRAMS_PER_SQUARE_METRE,
            category = 1,
            number = 193,
            surface = SurfaceExpectation.type(SURFACE_GROUND_OR_WATER),
            statisticalProcess = STATISTICAL_PROCESS_ACCUMULATION,
            transform = { metres -> metres * 1000.0 },
        )
        EcmwfSurfaceField.TOTAL_CLOUD_COVER -> ecmwfInstant(
            parameter = GribForecastParameter.TOTAL_CLOUD_COVER,
            unit = GribValueUnit.PERCENT,
            category = 6,
            number = 192,
            surface = SurfaceExpectation.type(SURFACE_GROUND_OR_WATER),
            transform = { fraction -> fraction * 100.0 },
        )
    }

    private fun dwdDescriptor(field: DwdIconField): SemanticDescriptor? = when (field) {
        DwdIconField.TEMPERATURE_2M -> dwdInstant(
            GribForecastParameter.TEMPERATURE_2M,
            GribValueUnit.KELVIN,
            0,
            0,
            SurfaceExpectation.height(2),
        )
        DwdIconField.DEW_POINT_2M -> dwdInstant(
            GribForecastParameter.DEW_POINT_2M,
            GribValueUnit.KELVIN,
            0,
            6,
            SurfaceExpectation.height(2),
        )
        DwdIconField.RELATIVE_HUMIDITY_2M -> dwdInstant(
            GribForecastParameter.RELATIVE_HUMIDITY_2M,
            GribValueUnit.PERCENT,
            1,
            1,
            SurfaceExpectation.height(2),
        )
        DwdIconField.PRESSURE_MEAN_SEA_LEVEL -> dwdInstant(
            GribForecastParameter.PRESSURE_MEAN_SEA_LEVEL,
            GribValueUnit.PASCAL,
            3,
            1,
            SurfaceExpectation.type(SURFACE_MEAN_SEA_LEVEL),
        )
        DwdIconField.WIND_U_10M -> dwdInstant(
            GribForecastParameter.WIND_U_10M,
            GribValueUnit.METRES_PER_SECOND,
            2,
            2,
            SurfaceExpectation.height(10),
        )
        DwdIconField.WIND_V_10M -> dwdInstant(
            GribForecastParameter.WIND_V_10M,
            GribValueUnit.METRES_PER_SECOND,
            2,
            3,
            SurfaceExpectation.height(10),
        )
        DwdIconField.WIND_MAX_10M -> dwdInterval(
            GribForecastParameter.WIND_GUST_10M,
            GribValueUnit.METRES_PER_SECOND,
            2,
            22,
            SurfaceExpectation.height(10),
            STATISTICAL_PROCESS_MAXIMUM,
        )
        DwdIconField.TOTAL_PRECIPITATION -> dwdInterval(
            GribForecastParameter.PRECIPITATION_ACCUMULATION,
            GribValueUnit.KILOGRAMS_PER_SQUARE_METRE,
            1,
            52,
            SurfaceExpectation.type(SURFACE_GROUND_OR_WATER),
            STATISTICAL_PROCESS_ACCUMULATION,
        )
        DwdIconField.TOTAL_CLOUD_COVER -> dwdInstant(
            GribForecastParameter.TOTAL_CLOUD_COVER,
            GribValueUnit.PERCENT,
            6,
            1,
            SurfaceExpectation.type(SURFACE_GROUND_OR_WATER),
        )
        DwdIconField.WEATHER_CODE -> null
    }

    private fun ecmwfInstant(
        parameter: GribForecastParameter,
        unit: GribValueUnit,
        category: Int,
        number: Int,
        surface: SurfaceExpectation,
        transform: (Double) -> Double = { it },
    ) = SemanticDescriptor(
        parameter = parameter,
        unit = unit,
        discipline = DISCIPLINE_METEOROLOGICAL,
        parameterCategory = category,
        parameterNumber = number,
        productDefinitionTemplate = PDT_INSTANTANEOUS,
        gridDefinitionTemplate = GDT_REGULAR_LAT_LON,
        dataRepresentationTemplate = DRT_CCSDS,
        surface = surface,
        transform = transform,
    )

    private fun ecmwfInterval(
        parameter: GribForecastParameter,
        unit: GribValueUnit,
        category: Int,
        number: Int,
        surface: SurfaceExpectation,
        statisticalProcess: Int,
        transform: (Double) -> Double = { it },
    ) = SemanticDescriptor(
        parameter = parameter,
        unit = unit,
        discipline = DISCIPLINE_METEOROLOGICAL,
        parameterCategory = category,
        parameterNumber = number,
        productDefinitionTemplate = PDT_TIME_INTERVAL,
        gridDefinitionTemplate = GDT_REGULAR_LAT_LON,
        dataRepresentationTemplate = DRT_CCSDS,
        surface = surface,
        statisticalProcess = statisticalProcess,
        transform = transform,
    )

    private fun dwdInstant(
        parameter: GribForecastParameter,
        unit: GribValueUnit,
        category: Int,
        number: Int,
        surface: SurfaceExpectation,
    ) = SemanticDescriptor(
        parameter = parameter,
        unit = unit,
        discipline = DISCIPLINE_METEOROLOGICAL,
        parameterCategory = category,
        parameterNumber = number,
        productDefinitionTemplate = PDT_INSTANTANEOUS,
        gridDefinitionTemplate = GDT_ICOSAHEDRAL,
        dataRepresentationTemplate = DRT_CCSDS,
        surface = surface,
    )

    private fun dwdInterval(
        parameter: GribForecastParameter,
        unit: GribValueUnit,
        category: Int,
        number: Int,
        surface: SurfaceExpectation,
        statisticalProcess: Int,
    ) = SemanticDescriptor(
        parameter = parameter,
        unit = unit,
        discipline = DISCIPLINE_METEOROLOGICAL,
        parameterCategory = category,
        parameterNumber = number,
        productDefinitionTemplate = PDT_TIME_INTERVAL,
        gridDefinitionTemplate = GDT_ICOSAHEDRAL,
        dataRepresentationTemplate = DRT_CCSDS,
        surface = surface,
        statisticalProcess = statisticalProcess,
    )

    private fun gribDuration(unit: Int, value: Long): Duration = when (unit) {
        0 -> Duration.ofMinutes(value)
        1 -> Duration.ofHours(value)
        2 -> Duration.ofDays(value)
        10 -> Duration.ofHours(Math.multiplyExact(value, 3L))
        11 -> Duration.ofHours(Math.multiplyExact(value, 6L))
        12 -> Duration.ofHours(Math.multiplyExact(value, 12L))
        13 -> Duration.ofSeconds(value)
        else -> throw IllegalArgumentException("Unsupported GRIB time unit $unit")
    }

    private data class SemanticDescriptor(
        val parameter: GribForecastParameter,
        val unit: GribValueUnit,
        val discipline: Int,
        val parameterCategory: Int,
        val parameterNumber: Int,
        val productDefinitionTemplate: Int,
        val gridDefinitionTemplate: Int,
        val dataRepresentationTemplate: Int,
        val surface: SurfaceExpectation,
        val statisticalProcess: Int? = null,
        val transform: (Double) -> Double = { it },
    )

    private data class SurfaceExpectation(
        val type: Int,
        val scaleFactor: Int? = null,
        val scaledValue: Long? = null,
    ) {
        fun matches(metadata: GribMessageMetadata): Boolean =
            metadata.firstFixedSurfaceType == type &&
                (scaleFactor == null || metadata.firstFixedSurfaceScaleFactor == scaleFactor) &&
                (scaledValue == null || metadata.firstFixedSurfaceScaledValue == scaledValue)

        companion object {
            fun type(type: Int) = SurfaceExpectation(type = type)
            fun height(metres: Long) = SurfaceExpectation(
                type = SURFACE_HEIGHT_ABOVE_GROUND,
                scaleFactor = 0,
                scaledValue = metres,
            )
        }
    }

    private val NOAA_DESCRIPTORS = listOf(
        SemanticDescriptor(
            GribForecastParameter.TEMPERATURE_2M,
            GribValueUnit.KELVIN,
            DISCIPLINE_METEOROLOGICAL,
            0,
            0,
            PDT_INSTANTANEOUS,
            GDT_REGULAR_LAT_LON,
            DRT_SIMPLE_PACKING,
            SurfaceExpectation.height(2),
        ),
        SemanticDescriptor(
            GribForecastParameter.DEW_POINT_2M,
            GribValueUnit.KELVIN,
            DISCIPLINE_METEOROLOGICAL,
            0,
            6,
            PDT_INSTANTANEOUS,
            GDT_REGULAR_LAT_LON,
            DRT_SIMPLE_PACKING,
            SurfaceExpectation.height(2),
        ),
        SemanticDescriptor(
            GribForecastParameter.RELATIVE_HUMIDITY_2M,
            GribValueUnit.PERCENT,
            DISCIPLINE_METEOROLOGICAL,
            1,
            1,
            PDT_INSTANTANEOUS,
            GDT_REGULAR_LAT_LON,
            DRT_SIMPLE_PACKING,
            SurfaceExpectation.height(2),
        ),
        SemanticDescriptor(
            GribForecastParameter.PRESSURE_MEAN_SEA_LEVEL,
            GribValueUnit.PASCAL,
            DISCIPLINE_METEOROLOGICAL,
            3,
            1,
            PDT_INSTANTANEOUS,
            GDT_REGULAR_LAT_LON,
            DRT_SIMPLE_PACKING,
            SurfaceExpectation.type(SURFACE_MEAN_SEA_LEVEL),
        ),
        SemanticDescriptor(
            GribForecastParameter.WIND_U_10M,
            GribValueUnit.METRES_PER_SECOND,
            DISCIPLINE_METEOROLOGICAL,
            2,
            2,
            PDT_INSTANTANEOUS,
            GDT_REGULAR_LAT_LON,
            DRT_SIMPLE_PACKING,
            SurfaceExpectation.height(10),
        ),
        SemanticDescriptor(
            GribForecastParameter.WIND_V_10M,
            GribValueUnit.METRES_PER_SECOND,
            DISCIPLINE_METEOROLOGICAL,
            2,
            3,
            PDT_INSTANTANEOUS,
            GDT_REGULAR_LAT_LON,
            DRT_SIMPLE_PACKING,
            SurfaceExpectation.height(10),
        ),
        SemanticDescriptor(
            GribForecastParameter.WIND_GUST_10M,
            GribValueUnit.METRES_PER_SECOND,
            DISCIPLINE_METEOROLOGICAL,
            2,
            22,
            PDT_INSTANTANEOUS,
            GDT_REGULAR_LAT_LON,
            DRT_SIMPLE_PACKING,
            SurfaceExpectation.type(SURFACE_GROUND_OR_WATER),
        ),
        SemanticDescriptor(
            GribForecastParameter.PRECIPITATION_ACCUMULATION,
            GribValueUnit.KILOGRAMS_PER_SQUARE_METRE,
            DISCIPLINE_METEOROLOGICAL,
            1,
            8,
            PDT_TIME_INTERVAL,
            GDT_REGULAR_LAT_LON,
            DRT_SIMPLE_PACKING,
            SurfaceExpectation.type(SURFACE_GROUND_OR_WATER),
            statisticalProcess = STATISTICAL_PROCESS_ACCUMULATION,
        ),
        SemanticDescriptor(
            GribForecastParameter.TOTAL_CLOUD_COVER,
            GribValueUnit.PERCENT,
            DISCIPLINE_METEOROLOGICAL,
            6,
            1,
            PDT_INSTANTANEOUS,
            GDT_REGULAR_LAT_LON,
            DRT_SIMPLE_PACKING,
            SurfaceExpectation.type(SURFACE_ENTIRE_ATMOSPHERE),
        ),
        SemanticDescriptor(
            GribForecastParameter.VISIBILITY,
            GribValueUnit.METRES,
            DISCIPLINE_METEOROLOGICAL,
            19,
            0,
            PDT_INSTANTANEOUS,
            GDT_REGULAR_LAT_LON,
            DRT_SIMPLE_PACKING,
            SurfaceExpectation.type(SURFACE_GROUND_OR_WATER),
        ),
    )
}
