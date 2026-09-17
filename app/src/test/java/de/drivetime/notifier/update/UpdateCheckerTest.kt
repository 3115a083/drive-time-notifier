package de.drivetime.notifier.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
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
        val result = UpdateChecker.evaluateRelease(
            installedVersion = "1.1.0-debug",
            debugBuild = true,
            latestRaw = "v1.2.0",
            releaseUrlRaw = "https://github.com/3115a083/drive-time-notifier/releases/tag/v1.2.0",
            draft = false,
            prerelease = false
        )
        assertTrue(result is UpdateCheckResult.UpdateAvailable)
    }

    @Test
    fun prereleaseIsNotOfferedAsStableUpdate() {
        val result = UpdateChecker.evaluateRelease(
            installedVersion = "1.1.0",
            debugBuild = false,
            latestRaw = "v1.2.0-rc1",
            releaseUrlRaw = "https://github.com/3115a083/drive-time-notifier/releases/tag/v1.2.0-rc1",
            draft = false,
            prerelease = true
        )
        assertTrue(result is UpdateCheckResult.Error)
    }

    @Test
    fun invalidInstalledVersionFailsClosed() {
        val result = UpdateChecker.evaluateRelease(
            installedVersion = "broken",
            debugBuild = false,
            latestRaw = "v1.2.0",
            releaseUrlRaw = "https://github.com/3115a083/drive-time-notifier/releases/tag/v1.2.0",
            draft = false,
            prerelease = false
        )
        assertTrue(result is UpdateCheckResult.Error)
    }
}
