package com.sl.meteoone.forecast.data.source

import com.sl.meteoone.core.model.ForecastProvider
import com.sl.meteoone.core.model.ModelFamily

object OfficialProviderIdentity {
    fun modelFamily(provider: ForecastProvider): ModelFamily? = when (provider) {
        ForecastProvider.NOAA_NOMADS -> ModelFamily.NOAA_GFS
        ForecastProvider.ECMWF_OPEN_DATA -> ModelFamily.ECMWF_IFS
        ForecastProvider.DWD_OPEN_DATA -> ModelFamily.DWD_ICON
        ForecastProvider.OPEN_METEO,
        ForecastProvider.MET_NORWAY,
        ForecastProvider.UNKNOWN,
        -> null
    }
}
