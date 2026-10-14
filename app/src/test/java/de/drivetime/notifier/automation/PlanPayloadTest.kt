package de.drivetime.notifier.automation

import org.junit.Assert.*
import org.junit.Test

class PlanPayloadTest {
    private val plan = SavedPlan("Private home", "Private destination", 1_800_000_000_000, 1_800_003_600_000,
        1_800_004_500_000, 42, 7, 1_799_999_000_000, "updated", "identity", "Private appointment title")

    @Test fun onlySelectedPersonalFieldsLeaveTheApp() {
        val payload = PlanPayload.values(plan, setOf("start_time"), "delivery")
        assertEquals(setOf("schema_version", "delivery_id", "start_time", "start_time_ms"), payload.keys)
        assertEquals(plan.departureMillis, payload["start_time_ms"])
        assertEquals("2027-01-15T08:00:00Z", payload["start_time"])
        assertFalse(payload.values.any { it.toString().contains("Private") })
    }

    @Test fun stablePlanIdAllowsAlarmUpdatesAndAllOptionalFieldsAreTyped() {
        val selected = PlanPayloadField.entries.map { it.id }.toSet()
        val payload = PlanPayload.values(plan, selected, "delivery")
        assertEquals("calendar:7:42", payload["plan_id"])
        assertEquals(payload["plan_id"], PlanPayload.values(plan.copy(revisionMillis = plan.revisionMillis + 1), selected, "next")["plan_id"])
        assertEquals(15L, payload["buffer_minutes"])
        assertEquals(3600L, payload["planned_duration_seconds"])
        assertEquals("updated", payload["operation"])
    }

    @Test fun missingCalendarDataIsOmittedFromIcsExports() {
        val payload = PlanPayload.values(plan.copy(eventId = null, calendarId = null, appointmentMillis = null),
            setOf("calendar_event_id", "calendar_id", "appointment_time", "buffer_minutes"), "delivery")
        assertEquals(setOf("schema_version", "delivery_id"), payload.keys)
    }

    @Test fun destinationsAndFieldConfigurationAreValidated() {
        assertFalse(PlanHookConfig().intentEnabled)
        assertFalse(PlanHookConfig().webhookEnabled)
        assertTrue(PlanHookConfig(intentEnabled = true).valid())
        assertTrue(PlanHookConfig(webhookEnabled = true, webhookUrl = "https://ha.example/api/webhook/secret").valid())
        for (url in listOf("http://ha.local/api/webhook/id", "https://user:pass@ha.example/hook", "https://ha.example/hook#fragment", "javascript:alert(1)"))
            assertFalse(PlanHookConfig.validWebhookUrl(url))
        assertFalse(PlanHookConfig(bearerToken = "value\r\nInjected: header").valid())
        assertFalse(PlanHookConfig(fields = setOf("unknown_sensitive_field")).valid())
        assertFalse(PlanHookConfig(intentEnabled = true, action = "android.intent.action.VIEW").valid())
        assertEquals(PlanHookConfig(fields = setOf("start_time", "plan_id")).fingerprint(),
            PlanHookConfig(fields = setOf("plan_id", "start_time")).fingerprint())
    }
}
