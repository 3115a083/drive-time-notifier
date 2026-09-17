package de.drivetime.notifier.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    private val checker = UpdateChecker()

    @Test
    fun semanticVersionComparisonWorks() {
        assertTrue(UpdateChecker.compareVersions("1.2.0", "1.1.9") > 0)
        assertEquals(0, UpdateChecker.compareVersions("1.1", "1.1.0"))
    }

    @Test
    fun debugSuffixIsIgnoredForComparison() {
        assertEquals("1.1.0", UpdateChecker.normalize("1.1.0-debug"))
    }

    @Test
    fun stableReleaseCanBeReportedAsNewer() {
        val result = checker.parseReleaseJson(
            "1.1.0-debug",
            true,
            """{\"tag_name\":\"v1.2.0\",\"html_url\":\"https://github.com/3115a083/drive-time-notifier/releases/tag/v1.2.0\",\"draft\":false,\"prerelease\":false}"""
        )
        assertTrue(result is UpdateCheckResult.UpdateAvailable)
    }

    @Test
    fun invalidPayloadFailsClosed() {
        assertTrue(checker.parseReleaseJson("1.1.0", false, "not-json") is UpdateCheckResult.Error)
    }
}
