package android.os
object Trace {
    @JvmStatic fun beginSection(name: String) {}
    @JvmStatic fun endSection() {}
    @JvmStatic fun isEnabled(): Boolean = false
}
