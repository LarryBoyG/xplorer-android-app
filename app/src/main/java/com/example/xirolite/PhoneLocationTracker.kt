package com.example.xirolite

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.android.awaitFrame

fun hasPhoneLocationPermission(context: Context): Boolean {
    val fineGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val coarseGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    return fineGranted || coarseGranted
}

@Composable
fun rememberPhoneFlightCoordinate(permissionGranted: Boolean): State<FlightCoordinate?> {
    val context = LocalContext.current.applicationContext
    return produceState<FlightCoordinate?>(initialValue = null, permissionGranted) {
        if (!permissionGranted) {
            value = null
            return@produceState
        }

        awaitFrame()

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return@produceState
        val listener = LocationListener { location ->
            value = location.toFlightCoordinate()
        }

        fun requestProvider(provider: String) {
            runCatching {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(
                        provider,
                        1_000L,
                        1f,
                        listener,
                        Looper.getMainLooper()
                    )
                    locationManager.getLastKnownLocation(provider)?.let { lastKnown ->
                        value = lastKnown.toFlightCoordinate()
                    }
                }
            }
        }

        requestProvider(LocationManager.GPS_PROVIDER)
        requestProvider(LocationManager.NETWORK_PROVIDER)

        awaitDispose {
            runCatching { locationManager.removeUpdates(listener) }
        }
    }
}

private fun Location.toFlightCoordinate(): FlightCoordinate =
    FlightCoordinate(latitude = latitude, longitude = longitude)
