package com.photoanswer.app

import android.content.pm.PackageInfo
import android.content.pm.Signature
import com.nic.photoanswer.BuildConfig
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = android.app.Application::class)
@Suppress("DEPRECATION")
class UpdateIdentityTest {
    private val signer = java.security.MessageDigest.getInstance("SHA-256").digest(Signature("01234567").toByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
    private val release = AppRelease("1.0.2", ReleaseVersion.parse("1.0.2").code, 26,
        "https://github.com/macanine/camera/releases/download/v1.0.2/camera-v1.0.2.apk",
        "a".repeat(64), 1, "", signer)
    private fun info(signerBytes: String = "01234567", versionCode: Int = release.versionCode,
        versionName: String = release.versionName) = PackageInfo().apply {
        packageName = BuildConfig.APPLICATION_ID
        this.versionName = versionName
        this.versionCode = versionCode
        signatures = arrayOf(Signature(signerBytes))
    }
    @Test fun `newer same identity APK is accepted while different signer package and downgrade are rejected`() {
        val installed = info(versionCode = ReleaseVersion.parse("1.0.1").code, versionName = "1.0.1")
        AppUpdates.validateIdentity(info(), installed, release, BuildConfig.APPLICATION_ID)
        val invalid = listOf(info("89abcdef"), info().apply { packageName = "other.package" },
            info().apply { signatures = emptyArray() }, info().apply { versionCode -= 1 })
        invalid.forEach { candidate -> assertThrows(Exception::class.java) {
            AppUpdates.validateIdentity(candidate, installed, release, BuildConfig.APPLICATION_ID)
        } }
        val old = release.copy(versionCode = installed.versionCode, versionName = installed.versionName!!)
        assertThrows(Exception::class.java) { AppUpdates.validateIdentity(info().apply {
            versionCode = old.versionCode; versionName = old.versionName
        }, installed, old, BuildConfig.APPLICATION_ID) }
    }

    @Test fun `certificate metadata must match both the APK and installed app`() {
        val installed = info(versionCode = ReleaseVersion.parse("1.0.1").code, versionName = "1.0.1")
        assertThrows(Exception::class.java) {
            AppUpdates.validateIdentity(info(), installed, release.copy(
                signingCertificateSha256 = "f".repeat(64),
            ), BuildConfig.APPLICATION_ID)
        }
    }
}
