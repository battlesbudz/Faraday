package com.battlesbudz.jarvis.v2.voice

/**
 * Thread-safe fan-out for captured frames plus a latest-frame cache.
 *
 * Fail-closed by design: if no observer is registered, frames are dropped
 * on the floor — never buffered, so a consumer that never arrives cannot
 * grow memory without bound. The cache holds exactly one frame (the most
 * recent dispatched one) for poll-style consumers.
 */
class VisionFrameHub {
    private val observers = LinkedHashSet<VisionObserver>()

    @Volatile
    private var latest: VisionFrame? = null
    private var droppedForNoObserver = 0L
    private var generation = 0L

    /** A prepared publication, invalidated by clear even after its targets were captured. */
    internal class PendingDelivery(
        val generation: Long,
        val frame: VisionFrame,
        val targets: List<VisionObserver>,
    )

    @Synchronized
    fun register(observer: VisionObserver) {
        observers += observer
    }

    @Synchronized
    fun unregister(observer: VisionObserver) {
        observers -= observer
    }

    @Synchronized
    fun observerCount(): Int = observers.size

    /**
     * Deliver a frame to all registered observers. Drops the frame (and does
     * not cache it) when nobody is registered. One misbehaving observer can
     * never break delivery to the rest.
     */
    fun dispatch(jpegBytes: ByteArray, timestampMs: Long) {
        deliver(prepareDispatch(jpegBytes, timestampMs))
    }

    /**
     * Cache and snapshot atomically, without invoking consumer code. The binder
     * can call this under its generation lock, then deliver after releasing it.
     */
    @Synchronized
    internal fun prepareDispatch(jpegBytes: ByteArray, timestampMs: Long): PendingDelivery? {
        if (observers.isEmpty()) {
            droppedForNoObserver++
            return null
        }
        val frame = VisionFrame(jpegBytes, timestampMs)
        latest = frame
        return PendingDelivery(generation, frame, observers.toList())
    }

    /**
     * Admit each callback against the current epoch, but never hold a hub or
     * binder lock while invoking it. A callback admitted before clear may
     * finish (or enter after a scheduling pause) during teardown. Clear revokes
     * later admissions and the cache, without waiting for consumer code to drain.
     */
    internal fun deliver(delivery: PendingDelivery?) {
        if (delivery == null) return
        for (observer in delivery.targets) {
            synchronized(this) {
                if (delivery.generation != generation) return
            }
            try {
                observer.onFrame(delivery.frame.jpegBytes, delivery.frame.timestampMs)
            } catch (_: Exception) {
                // A throwing observer must not kill delivery to the others.
            }
        }
    }

    /** The most recently dispatched frame, or null if none (or cleared). */
    fun latest(): VisionFrame? = latest

    @Synchronized
    fun clear() {
        generation++
        latest = null
    }

    @Synchronized
    fun droppedCount(): Long = droppedForNoObserver
}
