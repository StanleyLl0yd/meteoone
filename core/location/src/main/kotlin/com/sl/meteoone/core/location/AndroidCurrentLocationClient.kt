package com.sl.meteoone.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

class AndroidCurrentLocationClient(
    context: Context,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        require(timeoutMillis in 1..MAX_TIMEOUT_MILLIS) {
            "Location timeout must be between 1 and $MAX_TIMEOUT_MILLIS milliseconds"
        }
    }

    fun requestCurrentLocation(
        callback: (CurrentLocationResult) -> Unit,
    ): LocationRequestHandle {
        val completed = AtomicBoolean(false)
        var cancellationSignal: CancellationSignal? = null
        var legacyListener: LocationListener? = null
        var timeoutCallback: Runnable? = null

        fun finish(result: CurrentLocationResult) {
            if (!completed.compareAndSet(false, true)) return

            timeoutCallback?.let(mainHandler::removeCallbacks)
            cancellationSignal?.cancel()
            legacyListener?.let { listener ->
                try {
                    locationManager.removeUpdates(listener)
                } catch (_: RuntimeException) {
                    // Cleanup is best-effort after the result has already been decided.
                }
            }
            callback(result)
        }

        val handle = LocationRequestHandle {
            mainHandler.post {
                finish(CurrentLocationResult.Unavailable(CurrentLocationResult.Reason.CANCELLED))
            }
        }

        val hasCoarsePermission =
            appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        if (!hasCoarsePermission) {
            mainHandler.post {
                finish(
                    CurrentLocationResult.Unavailable(
                        CurrentLocationResult.Reason.PERMISSION_REQUIRED,
                    ),
                )
            }
            return handle
        }

        if (!isNetworkProviderEnabled()) {
            mainHandler.post {
                finish(
                    CurrentLocationResult.Unavailable(
                        CurrentLocationResult.Reason.PROVIDER_UNAVAILABLE,
                    ),
                )
            }
            return handle
        }

        timeoutCallback = Runnable {
            finish(CurrentLocationResult.Unavailable(CurrentLocationResult.Reason.TIMEOUT))
        }.also { mainHandler.postDelayed(it, timeoutMillis) }

        mainHandler.post {
            if (completed.get()) return@post
            try {
                requestPlatformLocation(
                    onCancellationSignal = { cancellationSignal = it },
                    onLegacyListener = { legacyListener = it },
                    onLocation = { location ->
                        val result = location?.let(::normalizeLocation)
                            ?: CurrentLocationResult.Unavailable(
                                CurrentLocationResult.Reason.PLATFORM_FAILURE,
                            )
                        finish(result)
                    },
                )
            } catch (_: SecurityException) {
                finish(
                    CurrentLocationResult.Unavailable(
                        CurrentLocationResult.Reason.PERMISSION_REQUIRED,
                    ),
                )
            } catch (_: RuntimeException) {
                finish(
                    CurrentLocationResult.Unavailable(
                        CurrentLocationResult.Reason.PLATFORM_FAILURE,
                    ),
                )
            }
        }

        return handle
    }

    private fun isNetworkProviderEnabled(): Boolean =
        try {
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: RuntimeException) {
            false
        }

    @SuppressLint("MissingPermission")
    private fun requestPlatformLocation(
        onCancellationSignal: (CancellationSignal) -> Unit,
        onLegacyListener: (LocationListener) -> Unit,
        onLocation: (Location?) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cancellationSignal = CancellationSignal()
            onCancellationSignal(cancellationSignal)
            val executor = Executor { runnable -> mainHandler.post(runnable) }
            locationManager.getCurrentLocation(
                LocationManager.NETWORK_PROVIDER,
                cancellationSignal,
                executor,
            ) { location ->
                onLocation(location)
            }
            return
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                onLocation(location)
            }

            @Deprecated("Deprecated in Android")
            override fun onStatusChanged(
                provider: String?,
                status: Int,
                extras: Bundle?,
            ) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit
        }
        onLegacyListener(listener)
        @Suppress("DEPRECATION")
        locationManager.requestSingleUpdate(
            LocationManager.NETWORK_PROVIDER,
            listener,
            Looper.getMainLooper(),
        )
    }

    private fun normalizeLocation(location: Location): CurrentLocationResult =
        try {
            CurrentLocationResult.Available(
                ForecastCoordinateNormalizer.normalize(
                    latitude = location.latitude,
                    longitude = location.longitude,
                ),
            )
        } catch (_: IllegalArgumentException) {
            CurrentLocationResult.Unavailable(CurrentLocationResult.Reason.PLATFORM_FAILURE)
        }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 15_000L
        const val MAX_TIMEOUT_MILLIS = 60_000L
    }
}
