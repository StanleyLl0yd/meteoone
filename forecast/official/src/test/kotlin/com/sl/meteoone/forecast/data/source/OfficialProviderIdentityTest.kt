package com.sl.meteoone.forecast.data.source

import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OfficialProviderIdentityTest {
    @Test
    fun directOfficialProvidersMapToExactlyOneModelFamily() {
        assertEquals(ModelFamily.NOAA_GFS, OfficialProviderIdentity.modelFamily(ForecastProvider.NOAA_NOMADS))
        assertEquals(ModelFamily.ECMWF_IFS, OfficialProviderIdentity.modelFamily(ForecastProvider.ECMWF_OPEN_DATA))
        assertEquals(ModelFamily.DWD_ICON, OfficialProviderIdentity.modelFamily(ForecastProvider.DWD_OPEN_DATA))
    }

    @Test
    fun aggregatorsDoNotImplyOneModelFamily() {
        assertNull(OfficialProviderIdentity.modelFamily(ForecastProvider.OPEN_METEO))
        assertNull(OfficialProviderIdentity.modelFamily(ForecastProvider.MET_NORWAY))
        assertNull(OfficialProviderIdentity.modelFamily(ForecastProvider.UNKNOWN))
    }
}
