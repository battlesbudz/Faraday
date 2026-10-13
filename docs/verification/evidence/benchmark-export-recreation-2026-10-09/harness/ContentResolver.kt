package android.content
import android.net.Uri
import java.io.OutputStream
/** Host transport seam only: native Android provider I/O remains an instrumentation gate. */
class ContentResolver(val open: (Uri, String) -> OutputStream) {
    fun openOutputStream(uri: Uri, mode: String): OutputStream = open(uri, mode)
}
