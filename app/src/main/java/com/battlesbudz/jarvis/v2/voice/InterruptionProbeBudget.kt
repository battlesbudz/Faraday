package com.battlesbudz.jarvis.v2.voice

/** Renewable work allowance shared by natural and verified-stop probes, never a reply lifetime cap. */
class InterruptionProbeBudget(private val windowMs: Long = 12_000, private val capacity: Int = 4) {
    private val starts = ArrayDeque<Long>()
    fun available(now: Long): Boolean {
        while (starts.isNotEmpty() && now - starts.first() >= windowMs) starts.removeFirst()
        return starts.size < capacity && (starts.isEmpty() || now - starts.last() >= 500)
    }
    fun record(now: Long) { check(available(now)); starts.addLast(now) }
}
