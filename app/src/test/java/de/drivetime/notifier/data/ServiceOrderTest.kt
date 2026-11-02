package de.drivetime.notifier.data

import org.junit.Assert.*
import org.junit.Test

class ServiceOrderTest {
    @Test fun freshSettingsUseOnlyTheTwoKeylessProviders() {
        assertEquals(listOf(RoutingProvider.VALHALLA,RoutingProvider.OSRM),AppSettings().routingOrder())
        assertTrue(AppSettings().routingOrder().none {it.keyRequired})
    }
    @Test fun movingAServiceChangesPrimaryAndFallbacksWithoutLosingConfiguration() {
        val original=AppSettings().copy(homeAddress="Home")
        val changed=original.withRoutingOrder(listOf(RoutingProvider.TOMTOM,RoutingProvider.OSRM,RoutingProvider.VALHALLA))
        assertEquals(RoutingProvider.TOMTOM,changed.routingProvider)
        assertEquals(listOf("osrm","valhalla"),changed.fallbackProviderIds)
        assertEquals("Home",changed.homeAddress)
        assertEquals(listOf("b","c","a"),moveService(listOf("a","b","c"),0,2))
    }
    @Test fun duplicateProvidersAndInvalidMovesDoNotCreateExtraQueries() {
        assertEquals(listOf(RoutingProvider.OSRM),AppSettings().withRoutingOrder(listOf(RoutingProvider.OSRM,RoutingProvider.OSRM)).routingOrder())
        assertEquals(listOf("a"),moveService(listOf("a"),0,-1))
    }
}
