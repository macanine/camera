package com.photoanswer.app

import com.nic.photoanswer.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    @Test fun `new release metadata invalidates an older downloaded APK while repeated checks retain it`() {
        val old = AppRelease.parse(manifest().toString(), "macanine/camera")
        val file = java.io.File("/not-read-by-this-state-test.apk")
        val current = UpdateState(release = old, ready = file, progress = 100)
        assertEquals(file, current.found(old.copy()).ready)
        assertNull(current.found(old.copy(sha256 = "b".repeat(64))).ready)
        assertEquals(0, current.found(old.copy(versionCode = old.versionCode + 1)).progress)
    }
    private fun manifest(version: String = "1.2.3") = JSONObject().put("schemaVersion", 1).put("applicationId", BuildConfig.APPLICATION_ID)
        .put("versionName", version).put("versionCode", ReleaseVersion.parse(version).code).put("minSdk", 26)
        .put("signingCertificateSha256", "c".repeat(64))
        .put("apk", JSONObject().put("url", "https://github.com/macanine/camera/releases/download/v$version/camera-v$version.apk")
            .put("sha256", "a".repeat(64)).put("size", 1024))

    @Test fun `release codes advance across release candidates patches and minor versions`() {
        val versions = listOf("1.0.1-rc.1", "1.0.1-rc.98", "1.0.1", "1.0.2-rc.1", "1.0.2", "1.1.0", "2.0.0")
            .map { ReleaseVersion.parse(it).code }
        assertTrue(versions.zipWithNext().all { (a, b) -> a < b })
        for (invalid in listOf("v1.0.0", "1.0", "01.0.0", "1.100.0", "1.0.0-rc.99", "2100.0.0")) {
            assertThrows(Exception::class.java) { ReleaseVersion.parse(invalid) }
        }
    }

    @Test fun `automatic checks respect the interval and clock rollback`() {
        val sixHours = 6 * 60 * 60 * 1000L
        assertTrue(shouldCheckForUpdates(now = sixHours, last = 0, automatic = true, manual = false))
        assertFalse(shouldCheckForUpdates(now = sixHours - 1, last = 100, automatic = true, manual = false))
        assertTrue(shouldCheckForUpdates(now = sixHours + 100, last = 100, automatic = true, manual = false))
        assertTrue(shouldCheckForUpdates(now = 100, last = 200, automatic = true, manual = false))
        assertFalse(shouldCheckForUpdates(now = sixHours, last = 0, automatic = false, manual = false))
        assertTrue(shouldCheckForUpdates(now = 1, last = 1, automatic = false, manual = true))
    }

    @Test fun `update requests only accept HTTPS GitHub release hosts`() {
        assertTrue(AppUpdates.isGitHubUpdateUrl("https://github.com/macanine/camera/releases/latest/download/update.json".toHttpUrl()))
        assertTrue(AppUpdates.isGitHubUpdateUrl("https://release-assets.githubusercontent.com/file".toHttpUrl()))
        assertTrue(AppUpdates.isGitHubUpdateUrl("https://objects.githubusercontent.com/file".toHttpUrl()))
        assertFalse(AppUpdates.isGitHubUpdateUrl("https://evil.example/file".toHttpUrl()))
        assertFalse(AppUpdates.isGitHubUpdateUrl("https://githubusercontent.com.evil.example/file".toHttpUrl()))
        assertFalse(AppUpdates.isGitHubUpdateUrl("http://github.com/file".toHttpUrl()))
    }

    @Test fun `update metadata is bound to repository package version and digest`() {
        assertEquals("1.2.3", AppRelease.parse(manifest().toString(), "macanine/camera").versionName)
        val invalid = listOf(
            manifest().put("versionCode", 1),
            manifest().put("applicationId", "other.app"),
            manifest().put("schemaVersion", 2),
            manifest().apply { getJSONObject("apk").put("url", "https://evil.example/update.apk") },
            manifest().apply { getJSONObject("apk").put("sha256", "bad") },
            manifest().apply { getJSONObject("apk").put("size", AppRelease.MAX_APK_BYTES + 1) },
            manifest().put("signingCertificateSha256", "bad"),
            manifest().apply { remove("signingCertificateSha256") }
        )
        invalid.forEach { assertThrows(Exception::class.java) {
            AppRelease.parse(it.toString(), "macanine/camera")
        } }
    }

    @Test fun `incompatible releases clear stale install state and explain the requirement`() {
        val release = AppRelease.parse(manifest().toString(), "macanine/camera")
        val stale = UpdateState(release = release, ready = java.io.File("/old.apk"), progress = 100, prompt = true)
        val checked = stale.checked(release.copy(minSdk = 37), 1, 36, true)
        assertNull(checked.release)
        assertNull(checked.ready)
        assertFalse(checked.prompt)
        assertEquals(0, checked.progress)
        assertEquals("新版本需要更高的 Android 系统版本", checked.message)
        assertEquals("当前已是最新版本", stale.checked(release, release.versionCode, 36, true).message)
    }
}
