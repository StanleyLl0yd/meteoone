package com.sl.meteoone.forecast.data.ecmwf

import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.forecast.data.source.ByteRange
import com.sl.meteoone.forecast.data.source.OfficialSourceRequest
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset

private const val MAX_SELECTED_FIELD_BYTES = 16L * 1024L * 1024L

enum class EcmwfSurfaceField(
    val parameter: String,
    val parameterAliases: Set<String> = emptySet(),
) {
    TEMPERATURE_2M("2t"),
    DEW_POINT_2M("2d"),
    PRESSURE_MEAN_SEA_LEVEL("msl"),
    WIND_U_10M("10u"),
    WIND_V_10M("10v"),
    WIND_GUST_10M_LAST_3H("10fg", setOf("10fg3")),
    TOTAL_PRECIPITATION("tp"),
    TOTAL_CLOUD_COVER("tcc"),
    ;

    val acceptedParameters: Set<String> = setOf(parameter) + parameterAliases
}

data class EcmwfFieldRangePlan(
    val request: OfficialSourceRequest,
    val range: ByteRange,
    val field: EcmwfSurfaceField,
    val provider: ForecastProvider,
    val modelFamily: ModelFamily,
    val modelRun: Instant,
    val validTime: Instant,
    val forecastHour: Int,
)

object EcmwfIfsFieldSelector {
    fun select(
        indexContent: String,
        plan: EcmwfIfsRequestPlan,
        fields: Set<EcmwfSurfaceField>,
    ): List<EcmwfFieldRangePlan> {
        require(fields.isNotEmpty()) { "At least one ECMWF field must be requested" }

        val entries = EcmwfIndexParser.parse(indexContent)
        validateIndexProvenance(entries, plan)

        return fields
            .sortedBy { it.ordinal }
            .map { field ->
                val matches = entries.filter { entry ->
                    entry.levelType == "sfc" && entry.parameter in field.acceptedParameters
                }
                require(matches.size == 1) {
                    "Expected exactly one ECMWF surface entry for ${field.acceptedParameters}, found ${matches.size}"
                }

                val range = matches.single().range
                require(range.length <= MAX_SELECTED_FIELD_BYTES) {
                    "ECMWF field ${field.parameter} exceeds the configured response limit"
                }

                EcmwfFieldRangePlan(
                    request = OfficialSourceRequest(
                        uri = plan.gribUri,
                        maxResponseBytes = range.length,
                    ),
                    range = range,
                    field = field,
                    provider = ForecastProvider.ECMWF_OPEN_DATA,
                    modelFamily = ModelFamily.ECMWF_IFS,
                    modelRun = plan.modelRun,
                    validTime = plan.validTime,
                    forecastHour = plan.forecastHour,
                )
            }
    }

    private fun validateIndexProvenance(
        entries: List<EcmwfIndexEntry>,
        plan: EcmwfIfsRequestPlan,
    ) {
        require(plan.provider == ForecastProvider.ECMWF_OPEN_DATA)
        require(plan.modelFamily == ModelFamily.ECMWF_IFS)

        val runUtc = plan.modelRun.atOffset(ZoneOffset.UTC)
        val expectedDate = buildString {
            append(runUtc.year.toString().padStart(4, '0'))
            append(runUtc.monthValue.toString().padStart(2, '0'))
            append(runUtc.dayOfMonth.toString().padStart(2, '0'))
        }
        val expectedTime = runUtc.hour.toString().padStart(2, '0') + "00"

        entries.forEachIndexed { index, entry ->
            val entryNumber = index + 1
            require(entry.domain == "g") {
                "ECMWF index entry $entryNumber has unexpected domain ${entry.domain}"
            }
            require(entry.dataClass == "od") {
                "ECMWF index entry $entryNumber has unexpected class ${entry.dataClass}"
            }
            require(entry.type == "fc") {
                "ECMWF index entry $entryNumber has unexpected type ${entry.type}"
            }
            require(entry.stream == "oper") {
                "ECMWF index entry $entryNumber has unexpected stream ${entry.stream}"
            }
            require(entry.date == expectedDate) {
                "ECMWF index entry $entryNumber does not match the planned run date"
            }
            require(entry.time == expectedTime) {
                "ECMWF index entry $entryNumber does not match the planned run cycle"
            }
            require(entry.step == plan.forecastHour) {
                "ECMWF index entry $entryNumber does not match the planned forecast step"
            }
        }
    }
}
