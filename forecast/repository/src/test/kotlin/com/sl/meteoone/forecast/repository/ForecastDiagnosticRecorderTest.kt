package com.sl.meteoone.forecast.repository

import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import com.sl.meteoone.forecast.domain.ForecastDiagnosticFallbackReason
import com.sl.meteoone.forecast.domain.ForecastWeightParameter
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForecastDiagnosticRecorderTest {
    private val now = Instant.parse("2026-10-14T08:00:00Z")

    @Test
    fun noBackgroundCollectionAndResetOnRestart() {
        val recorder = RefreshDiagnosticRecorder()
        recorder.sourceResult(ForecastProvider.NOAA_NOMADS, ModelFamily.NOAA_GFS, true)
        recorder.nativeDecodeSucceeded(ForecastProvider.NOAA_NOMADS)
        recorder.start()
        val first = recorder.finish(ForecastRefreshResult.Unavailable, now)
        assertTrue(first.sources.isEmpty())
        assertTrue(first.sanitizedText("0.4.0", "arm64-v8a").contains("not_evaluated"))

        recorder.start()
        recorder.sourceResult(ForecastProvider.NOAA_NOMADS, ModelFamily.NOAA_GFS, true)
        recorder.nativeDecodeSucceeded(ForecastProvider.NOAA_NOMADS)
        recorder.discard()
        recorder.start()
        val afterDiscard = recorder.finish(ForecastRefreshResult.Failed, now)
        assertTrue(afterDiscard.sources.isEmpty())

        val afterRestart = RefreshDiagnosticRecorder()
        afterRestart.start()
        assertTrue(afterRestart.finish(ForecastRefreshResult.Unavailable, now).sources.isEmpty())
    }

    @Test
    fun aggregateReportCapturesPartialFailureAndWeightFallback() {
        val recorder = RefreshDiagnosticRecorder()
        recorder.start()
        recorder.sourceResult(ForecastProvider.NOAA_NOMADS, ModelFamily.NOAA_GFS, true)
        recorder.nativeDecodeSucceeded(ForecastProvider.NOAA_NOMADS)
        recorder.sourceResult(ForecastProvider.DWD_OPEN_DATA, ModelFamily.DWD_ICON, false)
        recorder.sourceResult(ForecastProvider.OPEN_METEO, ModelFamily.DWD_ICON, true)
        recorder.m4RunAcquisition(true)
        recorder.m4StoredRuns(15)
        recorder.m4Evidence(200, mapOf(ForecastWeightParameter.TEMPERATURE to 76))
        recorder.weightDecision(
            ForecastWeightParameter.TEMPERATURE, measured = false,
            fallback = ForecastDiagnosticFallbackReason.SPARSE_EVIDENCE,
        )
        recorder.weightDecision(ForecastWeightParameter.TEMPERATURE, measured = true, fallback = null)
        val report = recorder.finish(ForecastRefreshResult.UpdatedWithDegradation, now)
        assertEquals(3, report.sources.size)
        assertTrue(report.sources[0].officialGribSelected)
        assertFalse(report.sources[1].officialGribSelected)
        assertFalse(report.sources[2].officialGribSelected)
        assertEquals(15, report.storedRuns)
        assertEquals(200, report.ghcnhObservations)
        assertEquals(76, report.samples.getValue(ForecastWeightParameter.TEMPERATURE))
        assertEquals(2, report.weights.size)
        assertEquals(ForecastDiagnosticFallbackReason.SPARSE_EVIDENCE, report.weights.first().fallback)
    }

    @Test
    fun sanitizedTextRejectsUntrustedMetadataAndPrivateState() {
        val recorder = RefreshDiagnosticRecorder()
        recorder.start()
        recorder.sourceResult(ForecastProvider.NOAA_NOMADS, ModelFamily.NOAA_GFS, true)
        recorder.m4Evidence(10, mapOf(ForecastWeightParameter.WIND to 5))
        val report = recorder.finish(ForecastRefreshResult.Updated, now)
        val text = report.sanitizedText(
            version = "0.4.0\nhttps://example.test/?lat=59.900001",
            abi = "arm64-v8a\nstation=RSM00026063",
        )
        assertTrue(text.contains("Version: unknown"))
        assertTrue(text.contains("ABI: unknown"))
        assertFalse(text.contains("59.900001"))
        assertFalse(text.contains("RSM00026063"))
        assertFalse(text.contains("https://"))
    }

    @Test
    fun countersBoundedAndNotInferredFromSourceLabels() {
        val recorder = RefreshDiagnosticRecorder()
        recorder.start()
        recorder.sourceResult(ForecastProvider.ECMWF_OPEN_DATA, ModelFamily.ECMWF_IFS, true)
        recorder.m4StoredRuns(Int.MAX_VALUE)
        recorder.m4Evidence(Int.MAX_VALUE, mapOf(ForecastWeightParameter.PRESSURE to Int.MAX_VALUE))
        val report = recorder.finish(ForecastRefreshResult.Updated, now)
        assertFalse(report.sources.single().officialGribSelected)
        assertEquals(0, report.sources.single().nativeDecodeSuccesses)
        assertEquals(10_000, report.storedRuns)
        assertEquals(10_000, report.ghcnhObservations)
        assertEquals(10_000, report.samples.getValue(ForecastWeightParameter.PRESSURE))
    }
}
