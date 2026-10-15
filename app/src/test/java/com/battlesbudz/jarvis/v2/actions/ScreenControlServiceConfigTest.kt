package com.battlesbudz.jarvis.v2.actions

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/**
 * The production screenshot path (AccessibilityService.takeScreenshot) only
 * delivers pixels when the service config declares
 * android:canTakeScreenshot. Without it the platform reports onError, the
 * capture returns null, and every launch verification stays unconfirmed —
 * while journeys that inject UiDevice capture keep passing. This test parses
 * the shipped config so the capability cannot silently regress.
 */
class ScreenControlServiceConfigTest {
    private fun configAttributes(): org.w3c.dom.NamedNodeMap {
        val file = listOf(
            File("src/main/res/xml/screen_control_service.xml"),
            File("app/src/main/res/xml/screen_control_service.xml")
        ).firstOrNull { it.isFile }
            ?: error("screen_control_service.xml not found under src/main or app/src/main")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        return document.documentElement.attributes
    }

    @Test fun screenControlServiceDeclaresScreenshotCapability() {
        val value = configAttributes().getNamedItem("android:canTakeScreenshot")?.nodeValue
        assertEquals("takeScreenshot needs the service to declare the capability", "true", value)
    }

    @Test fun screenControlServiceStillDeclaresWindowContent() {
        val value = configAttributes().getNamedItem("android:canRetrieveWindowContent")?.nodeValue
        assertEquals("the capability edit must not drop the sibling capability", "true", value)
    }
}
