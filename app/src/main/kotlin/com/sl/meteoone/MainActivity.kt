package com.sl.meteoone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.sl.meteoone.core.location.AndroidCurrentLocationClient
import com.sl.meteoone.core.preferences.ForecastTargetStore
import com.sl.meteoone.forecast.repository.ForecastRepository
import com.sl.meteoone.ui.theme.MeteoOneTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appContext = applicationContext
        val repository = ForecastRepository.android(appContext)
        val targetStore = ForecastTargetStore.android(appContext)
        val locationClient = AndroidCurrentLocationClient(appContext)

        setContent {
            MeteoOneTheme {
                AlphaForecastScreen(
                    repository = repository,
                    targetStore = targetStore,
                    locationClient = locationClient,
                )
            }
        }
    }
}
