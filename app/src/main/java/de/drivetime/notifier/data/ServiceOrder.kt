package de.drivetime.notifier.data

fun AppSettings.routingOrder(): List<RoutingProvider> =
    (listOf(routingProvider) + fallbackProviderIds.mapNotNull { id -> RoutingProvider.entries.firstOrNull { it.id == id } }).distinct()

fun AppSettings.withRoutingOrder(order: List<RoutingProvider>): AppSettings {
    val unique = order.distinct()
    require(unique.isNotEmpty())
    return copy(routingProvider = unique.first(), fallbackProviderIds = unique.drop(1).map { it.id })
}

fun <T> moveService(items: List<T>, from: Int, to: Int): List<T> {
    if (from !in items.indices || to !in items.indices || from == to) return items
    return items.toMutableList().apply { add(to, removeAt(from)) }
}

fun AppSettings.photonOrder(): List<String> = (listOf(photonBaseUrl) + photonEndpoints).distinct().take(8)
