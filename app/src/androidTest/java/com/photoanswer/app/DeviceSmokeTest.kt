package com.photoanswer.app

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    @Before fun disableExternalUpdateChecks() {
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("updates", 0)
            .edit().putBoolean("automatic", false).commit()
    }
    @Test fun launcherSurvivesRecreationWithoutCameraPermission() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()
            scenario.onActivity { activity -> assertFalse(activity.isFinishing) }
        }
    }

    @Test fun backgroundServiceAndUpdateFilesArePrivate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = context.packageManager
        assertFalse(pm.getServiceInfo(ComponentName(context, SolveService::class.java), 0).exported)
        val provider = pm.resolveContentProvider("${context.packageName}.updates", PackageManager.GET_META_DATA)!!
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
    }
}
