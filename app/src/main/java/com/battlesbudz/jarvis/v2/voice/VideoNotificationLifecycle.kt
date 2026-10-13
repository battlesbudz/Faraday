package com.battlesbudz.jarvis.v2.voice

/**
 * One video service instance's right to update its notification. Android may
 * remove its foreground association before delivering onDestroy; an update in
 * that interval can leave an ordinary notification behind. Remove both the
 * foreground association and that exact notification ID, in that order.
 *
 * Revoke publication before camera cleanup (which can synchronously report a
 * detach failure). Serialize the actual post with revocation, including posts
 * from camera callbacks outside the service coroutine scope. Cleanup runs
 * outside this lock: never hold a notification lock while waiting for a camera
 * callback. A closed instance never regains publication rights.
 */
internal class VideoNotificationLifecycle(
    private val post: (String) -> Unit,
    private val removeForeground: () -> Unit,
    private val cancelNotification: () -> Unit,
    private val onPostRejected: (RuntimeException) -> Unit = { throw it },
) {
    private val lock = Any()
    private var closed = false
    private var cleanupStarted = false

    val isOpen: Boolean get() = synchronized(lock) { !closed }

    fun publish(status: String): Boolean {
        val rejected = synchronized(lock) {
            if (closed) return false
            try {
                post(status)
                return true
            } catch (failure: RuntimeException) {
                // A refused foreground update cannot leave capture advertising
                // success. Revoke first, but let video teardown run outside this
                // lock so it can join callbacks that also attempt publication.
                closed = true
                failure
            }
        }
        onPostRejected(rejected)
        return false
    }

    fun close(cleanup: () -> Unit) {
        synchronized(lock) {
            if (cleanupStarted) return
            closed = true
            cleanupStarted = true
        }
        try {
            cleanup()
        } finally {
            try {
                removeForeground()
            } finally {
                cancelNotification()
            }
        }
    }
}
