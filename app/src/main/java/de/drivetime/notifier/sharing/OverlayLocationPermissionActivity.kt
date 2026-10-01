package de.drivetime.notifier.sharing

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import de.drivetime.notifier.ui.currentLocation
import kotlinx.coroutines.*

/** Foreground location lookup, without background location access. */
class OverlayLocationPermissionActivity : ComponentActivity() {
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) locate() else finish()
    }
    private var locating = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) locate()
        else if (savedInstanceState == null) permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
    }
    private fun locate() {
        if (locating) return
        locating = true
        lifecycleScope.launch {
            try {
                val location = withTimeout(20_000L) { currentLocation(this@OverlayLocationPermissionActivity) }
                startService(Intent(this@OverlayLocationPermissionActivity, PlanningOverlayService::class.java)
                    .setAction("location").putExtra("coordinates", "${location.latitude},${location.longitude}"))
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                Toast.makeText(this@OverlayLocationPermissionActivity, "Location unavailable / Standort nicht verfügbar", Toast.LENGTH_LONG).show()
            } finally { finish() }
        }
    }
}
