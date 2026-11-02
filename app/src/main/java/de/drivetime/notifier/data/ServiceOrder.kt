package de.drivetime.notifier.data

fun AppSettings.routingOrder(): List<RoutingProvider> {
    val configured = listOf(routingProvider) + fallbackProviderIds.mapNotNull { id -> RoutingProvider.entries.firstOrNull { it.id == id } }
    // Upgrade old primary/fallback configurations without losing them. Once the list has
    // been edited, explicit removals are preserved instead of adding defaults again.
    return (configured + if(routingServicesConfigured) emptyList() else RoutingProvider.entries.filterNot { it.keyRequired }).distinct()
}

fun AppSettings.withRoutingOrder(order: List<RoutingProvider>): AppSettings {
    val unique = order.distinct()
    require(unique.isNotEmpty())
    return copy(routingServicesConfigured = true, routingProvider = unique.first(), fallbackProviderIds = unique.drop(1).map { it.id })
}

fun <T> moveService(items: List<T>, from: Int, to: Int): List<T> {
    if (from !in items.indices || to !in items.indices || from == to) return items
    return items.toMutableList().apply { add(to, removeAt(from)) }
}

fun AppSettings.photonOrder(): List<String> = (listOf(photonBaseUrl) + photonEndpoints).distinct().take(8)
