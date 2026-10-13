package com.battlesbudz.jarvis.v2.voice

/**
 * The foreground type actually accepted for this video service token. Refreshes
 * must use that same token/type via Service.startForeground: a raw notify can
 * acquire the FGS flag before a stop but reach NMS after its system cancellation.
 * Repeating a foreground start goes through AMS admission and its ordered
 * ServiceRecord post/cancel queue instead. This owner never promotes a fallback
 * service to camera eligibility merely because permission later changes.
 */
internal class VideoForegroundNotification(
    private val cameraType: Int,
    private val fallbackType: Int,
    private val startForeground: (status: String, type: Int) -> Unit,
) {
    @Volatile
    private var acceptedType: Int? = null

    fun start(status: String, cameraGranted: Boolean) {
        check(acceptedType == null) { "Video foreground notification already started" }
        val requestedType = if (cameraGranted) cameraType else fallbackType
        val actualType = try {
            startForeground(status, requestedType)
            requestedType
        } catch (rejected: SecurityException) {
            if (requestedType == fallbackType) throw rejected
            startForeground(status, fallbackType)
            fallbackType
        }
        acceptedType = actualType
    }

    fun update(status: String) {
        val type = checkNotNull(acceptedType) { "Video foreground notification is not started" }
        startForeground(status, type)
    }

    fun canCapture(cameraGranted: Boolean): Boolean = cameraGranted && acceptedType == cameraType
}
