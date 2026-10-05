package de.drivetime.notifier.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import de.drivetime.notifier.data.AppLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Composable
fun CurrentLocationChip(language: AppLanguage, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val select by rememberUpdatedState(onSelect)
    var busy by remember { mutableStateOf(false) }
    fun locate() {
        if (busy) return
        scope.launch {
            busy = true
            try {
                val location = withTimeout(20_000L) { currentLocation(context) }
                select("${location.latitude},${location.longitude}")
            } catch (e: Exception) {
                if (e is CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                Toast.makeText(context, tr(language, "Location unavailable. Check location services and try again.", "Standort nicht verfügbar. Standortdienste prüfen und erneut versuchen."), Toast.LENGTH_LONG).show()
            } finally { busy = false }
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) locate()
        else Toast.makeText(context, tr(language, "Location permission is required.", "Die Standortberechtigung ist erforderlich."), Toast.LENGTH_LONG).show()
    }
    AssistChip(
        enabled = !busy,
        onClick = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) locate()
            else permissions.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
        },
        label = { Text(if (busy) tr(language, "Locating…", "Standort wird ermittelt…") else tr(language, "Current location", "Aktueller Standort")) },
        leadingIcon = { Icon(Icons.Outlined.MyLocation, null) }
    )
}

@SuppressLint("MissingPermission")
internal suspend fun currentLocation(context: Context): Location = suspendCancellableCoroutine { continuation ->
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            manager.removeUpdates(this)
            if (continuation.isActive) continuation.resume(location)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Legacy Android callback")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }
    continuation.invokeOnCancellation { manager.removeUpdates(listener) }
    try {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val provider = if (fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
        check(manager.isProviderEnabled(provider)) { "Location services disabled" }
        manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
    } catch (e: Exception) {
        manager.removeUpdates(listener)
        if (continuation.isActive) continuation.resumeWithException(e)
    }
}
