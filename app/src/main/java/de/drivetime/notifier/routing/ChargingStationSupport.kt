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
    val connectors: Set<ChargingConnectorPreference>,
    val resultLimit: Int,
    val maxDistanceMeters: Int,
    val speedPreference: ChargingSpeedPreference,
    val preferredOperator: String,
    val showOtherOperators: Boolean,
    val useBNetzA: Boolean
) {
    companion object {
        fun from(settings: AppSettings): ChargingSearchOptions? {
            if (!settings.showChargingStations) return null
            val selected = settings.chargingConnectors
                .filterNot { it == ChargingConnectorPreference.ANY }
                .toSet()
                .ifEmpty {
                    settings.chargingConnector
                        .takeUnless { it == ChargingConnectorPreference.ANY }
                        ?.let(::setOf)
                        ?: emptySet()
                }
            return ChargingSearchOptions(
                connectors = selected,
                resultLimit = settings.chargingResultLimit.coerceIn(1, 50),
                maxDistanceMeters = settings.chargingMaxDistanceMeters.coerceIn(100, 10_000),
                speedPreference = settings.chargingSpeedPreference,
                preferredOperator = settings.chargingPreferredOperator.trim(),
                showOtherOperators = settings.chargingShowOtherOperators,
                useBNetzA = settings.chargingUseBNetzA
            )
        }
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
                    name = clean(existing.name) ?: clean(registryStation.name),
                    operator = clean(registryStation.operator) ?: clean(existing.operator),
                    network = clean(existing.network) ?: clean(registryStation.network),
                    connectorTypes = existing.connectorTypes + registryStation.connectorTypes,
                    maxPowerKw = listOfNotNull(existing.maxPowerKw, registryStation.maxPowerKw).maxOrNull(),
                    access = clean(existing.access) ?: clean(registryStation.access),
                    fee = clean(existing.fee) ?: clean(registryStation.fee),
                    openingHours = clean(registryStation.openingHours) ?: clean(existing.openingHours),
                    maxStay = clean(registryStation.maxStay) ?: clean(existing.maxStay),
                    capacity = registryStation.capacity ?: existing.capacity,
                    parkingType = clean(registryStation.parkingType) ?: clean(existing.parkingType),
                    address = clean(registryStation.address) ?: clean(existing.address),
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
                options.connectors.isEmpty() || station.connectorTypes.any { it in options.connectors }
            }
            .filter { station ->
                preferred.isBlank() || options.showOtherOperators || operatorMatches(station, preferred)
            }
            .sortedWith(
                compareBy<RoutePoi> { station -> options.speedPreference.penalty(station.maxPowerKw) }
                    .thenBy { station -> if (preferred.isBlank() || operatorMatches(station, preferred)) 0 else 1 }
                    .thenBy { station -> stayPenalty(station.maxStay) }
                    .thenBy { station -> parkingPenalty(station) }
                    .thenBy { station -> station.distanceFromDestinationMeters ?: Int.MAX_VALUE }
            )
            .take(options.resultLimit)
            .toList()
    }

    private fun operatorMatches(station: RoutePoi, preferred: String): Boolean {
        val needle = preferred.lowercase().trim()
        return sequenceOf(station.operator, station.network, station.name)
            .mapNotNull(::clean)
            .any { it.lowercase().contains(needle) }
    }

    private fun parkingPenalty(station: RoutePoi): Int {
        val parking = station.parkingType?.lowercase().orEmpty()
        val longEnough = parseMaxStayMinutes(station.maxStay)?.let { it >= 60 } == true
        return if (longEnough && ("multi-storey" in parking || "underground" in parking || "parking" in parking)) 0 else 1
    }

    internal fun stayPenalty(raw: String?): Int = when (val minutes = parseMaxStayMinutes(raw)) {
        null -> 2
        in 0..59 -> 3
        in 60..119 -> 1
        else -> 0
    }

    internal fun parseMaxStayMinutes(raw: String?): Int? {
        val text = clean(raw)?.lowercase() ?: return null
        Regex("(\\d+)\\s*[:h]\\s*(\\d{1,2})").find(text)?.let { match ->
            val h = match.groupValues[1].toIntOrNull() ?: return@let
            val m = match.groupValues[2].toIntOrNull() ?: 0
            return h * 60 + m
        }
        val number = Regex("\\d+(?:[.,]\\d+)?").find(text)?.value?.replace(',', '.')?.toDoubleOrNull() ?: return null
        return when {
            "day" in text || "tag" in text -> (number * 1440).toInt()
            "hour" in text || "std" in text || " h" in " $text" -> (number * 60).toInt()
            "min" in text -> number.toInt()
            text.matches(Regex("\\d+")) -> number.toInt()
            else -> null
        }
    }

    private fun clean(value: String?): String? = value?.trim()?.takeIf {
        it.isNotEmpty() && !it.equals("null", true) && !it.equals("none", true)
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
