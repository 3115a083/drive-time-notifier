package de.drivetime.notifier.sharing

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import de.drivetime.notifier.data.SettingsStore
import de.drivetime.notifier.ui.currentLocation
import de.drivetime.notifier.ui.tr
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Foreground lookup with a response for success, denied permission and unavailable position. */
class OverlayLocationPermissionActivity : ComponentActivity() {
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) locate() else reportFailure(true)
    }
    private var locating = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) locate()
        else if (savedInstanceState == null) permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
    }
    private fun report(coordinates: String? = null, error: String? = null) {
        startService(Intent(this, PlanningOverlayService::class.java).setAction("location")
            .putExtra("request_id", intent.getStringExtra("request_id"))
            .putExtra("coordinates", coordinates).putExtra("error", error))
        finish()
    }
    private fun reportFailure(denied: Boolean) {
        lifecycleScope.launch {
            val language = SettingsStore(this@OverlayLocationPermissionActivity).flow.first().language
            report(error = if (denied) tr(language, "Location permission was denied. Enter an address instead.", "Standortberechtigung verweigert. Bitte eine Adresse eingeben.")
                else tr(language, "No recent location available. Check location services or enter an address.", "Kein aktueller Standort verfügbar. Standortdienste prüfen oder eine Adresse eingeben."))
        }
    }
    private fun locate() {
        if (locating) return
        locating = true
        lifecycleScope.launch {
            try {
                val location = withTimeout(20_000L) { currentLocation(this@OverlayLocationPermissionActivity) }
                report(coordinates = "${location.latitude},${location.longitude}")
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                reportFailure(false)
            }
        }
    }
}
