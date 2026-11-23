package com.battlesbudz.jarvis.v2.privilege

import android.os.Binder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HelperHandoffTest {
    private val binder = Binder()
    private fun accepted() = HelperHandoff.validate(
        callerUid = 2000, callerPid = 40, reportedUid = 2000, reportedPid = 40,
        apk = "/data/app/faraday.apk", installedApk = "/data/app/faraday.apk", binder = binder,
        nonceMatches = true,
    )

    @Test fun acceptsShellHelperAndRejectsEachEnumeratedFailure() {
        assertNull(accepted())
        assertEquals(HelperHandoff.Reject.NOT_SHELL, HelperHandoff.validate(10000, 40, 10000, 40,
            "/data/app/faraday.apk", "/data/app/faraday.apk", binder, true))
        assertEquals(HelperHandoff.Reject.ID_MISMATCH, HelperHandoff.validate(2000, 40, 2000, 41,
            "/data/app/faraday.apk", "/data/app/faraday.apk", binder, true))
        assertEquals(HelperHandoff.Reject.STALE_APK, HelperHandoff.validate(2000, 40, 2000, 40,
            "/data/app/old.apk", "/data/app/faraday.apk", binder, true))
        assertEquals(HelperHandoff.Reject.NO_BINDER, HelperHandoff.validate(2000, 40, 2000, 40,
            "/data/app/faraday.apk", "/data/app/faraday.apk", null, true))
        assertEquals(HelperHandoff.Reject.BAD_NONCE, HelperHandoff.validate(2000, 40, 2000, 40,
            "/data/app/faraday.apk", "/data/app/faraday.apk", binder, false))
        assertEquals(HelperHandoff.Reject.BINDER_ELSEWHERE, HelperHandoff.validate(2000, 40, 2000, 40,
            "/data/app/faraday.apk", "/data/app/faraday.apk", binder, true, 2000, 99))
    }

    @Test fun helperCommandKeepsNonceOffCommandLine() {
        val nonce = "secret-one-time-value"
        val command = HelperHandoff.command("/data/app/faraday.apk", "com.battlesbudz.jarvis.v2", 10123, "aa")
        assertEquals(false, command.contains(nonce))
        assertEquals("$nonce\n", String(HelperHandoff.nonceStdin(nonce)))
        assertEquals(true, command.contains("setsid app_process /system/bin"))
    }

    @Test fun shellPolicyKeepsPermissionDisplayAndClipboardBoundsClosed() {
        assertEquals(setOf("display", "accessibility"), ShellPrivilegePolicy.allowedPermissionNames)
        assertEquals(true, ShellPrivilegePolicy.acceptsPermission("display"))
        assertEquals(false, ShellPrivilegePolicy.acceptsPermission("android.permission.WRITE_SECURE_SETTINGS"))
        assertEquals(true, ShellPrivilegePolicy.ownsDisplay(8, setOf(8)))
        assertEquals(false, ShellPrivilegePolicy.ownsDisplay(9, setOf(8)))
        assertEquals(false, ShellPrivilegePolicy.mayUseClipboard(false))
        assertEquals(true, ShellPrivilegePolicy.mayUseClipboard(true))
    }
}
