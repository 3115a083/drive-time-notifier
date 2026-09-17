package de.drivetime.notifier.routing

import de.drivetime.notifier.data.AppSettings
import de.drivetime.notifier.data.ChargingConnectorPreference
import de.drivetime.notifier.data.ChargingSpeedPreference
import org.osmdroid.util.GeoPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class ChargingSearchOptions(
    val connector: ChargingConnectorPreference,
    val maxDistanceMeters: Int,
    val speedPreference: ChargingSpeedPreference,
    val preferredOperator: String,
    val showOtherOperators: Boolean,
    val useBNetzA: Boolean
) {
    companion object {
        fun from(settings: AppSettings): ChargingSearchOptions? =
            if (!settings.showChargingStations) null else ChargingSearchOptions(
                connector = settings.chargingConnector,
                maxDistanceMeters = settings.chargingMaxDistanceMeters.coerceIn(100, 10_000),
                speedPreference = settings.chargingSpeedPreference,
                preferredOperator = settings.chargingPreferredOperator.trim(),
                showOtherOperators = settings.chargingShowOtherOperators,
                useBNetzA = settings.chargingUseBNetzA
            )
    }
}

internal object ChargingStationSelector {
    fun mergeAndRank(
        osm: List<RoutePoi>,
        registry: List<RoutePoi>,
        options: ChargingSearchOptions
    ): List<RoutePoi> {
        val merged = osm.toMutableList()
        registry.forEach { registryStation ->
            val index = merged.indexOfFirst {
                haversineMeters(it.point, registryStation.point) <= 120.0
            }
            if (index >= 0) {
                val existing = merged[index]
                merged[index] = existing.copy(
                    name = existing.name ?: registryStation.name,
                    operator = registryStation.operator ?: existing.operator,
                    network = existing.network ?: registryStation.network,
                    connectorTypes = existing.connectorTypes + registryStation.connectorTypes,
                    maxPowerKw = listOfNotNull(existing.maxPowerKw, registryStation.maxPowerKw).maxOrNull(),
                    openingHours = registryStation.openingHours ?: existing.openingHours,
                    address = registryStation.address ?: existing.address,
                    sources = existing.sources + registryStation.sources,
                    distanceFromDestinationMeters = minOf(
                        existing.distanceFromDestinationMeters ?: Int.MAX_VALUE,
                        registryStation.distanceFromDestinationMeters ?: Int.MAX_VALUE
                    ).takeIf { it != Int.MAX_VALUE }
                )
            } else {
                merged += registryStation
            }
        }

        val preferred = options.preferredOperator.trim()
        return merged.asSequence()
            .filter { it.kind == RoutePoi.Kind.CHARGING_STATION }
            .filter { station ->
                options.connector == ChargingConnectorPreference.ANY || options.connector in station.connectorTypes
            }
            .filter { station ->
                preferred.isBlank() || options.showOtherOperators || operatorMatches(station, preferred)
            }
            .sortedWith(
                compareBy<RoutePoi> { station ->
                    if (preferred.isBlank() || operatorMatches(station, preferred)) 0 else 1
                }.thenBy { station ->
                    options.speedPreference.penalty(station.maxPowerKw)
                }.thenBy { station ->
                    station.distanceFromDestinationMeters ?: Int.MAX_VALUE
                }
            )
            .take(5)
            .toList()
    }

    private fun operatorMatches(station: RoutePoi, preferred: String): Boolean {
        val needle = preferred.lowercase()
        return sequenceOf(station.operator, station.network, station.name)
            .filterNotNull()
            .any { it.lowercase().contains(needle) }
    }

    fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val r = 6_371_000.0
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }
}
