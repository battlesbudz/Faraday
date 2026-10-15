package com.battlesbudz.jarvis.v2.actions

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M7 is fully gated: invisible, inaccessible, non-functional. The exported
 * service has no manifest declaration, so no other app can bind to it, and
 * there is no Settings UI or toggle that can turn it on. The M7
 * implementation classes remain in the tree but nothing can reach them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalAgentGatedTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun externalAgentServiceHasNoManifestDeclaration() {
        val component = ComponentName(context, ExternalAgentService::class.java)
        try {
            context.packageManager.getServiceInfo(component, 0)
            fail("ExternalAgentService must have no manifest declaration while M7 is gated")
        } catch (_: PackageManager.NameNotFoundException) {
            // Expected: no service, nothing to bind to.
        }
    }
}
