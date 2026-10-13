package com.battlesbudz.jarvis.v2.actions

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import org.json.JSONArray
import org.json.JSONObject

/**
 * M7: exported service endpoint for external agents. Other on-device apps
 * bind to this service to call the five exposed Jarvis functions.
 *
 * Security: the caller's identity comes from Binder.getCallingUid(), never
 * from a client-supplied string — a caller cannot spoof another package.
 * Authorization is grant-based: [ExternalAgentAccess.call] denies unknown,
 * disabled, or revoked callers with honest receipts. The service itself
 * enforces nothing beyond identity; all policy lives in the boundary.
 */
class ExternalAgentService : Service() {

    inner class AgentBinder : Binder() {
        /**
         * Call one exposed function. Returns a JSON object:
         * `{"ok":true,"result":"...","attemptId":"..."}` or
         * `{"ok":false,"code":"CALLER_UNKNOWN","message":"..."}`.
         */
        fun callFunction(functionName: String, argsJson: String): String {
            val callerPackage = callingPackage() ?: return denied(
                ExternalDenialCode.CALLER_UNKNOWN, "Could not identify the calling package.")
            val access = ExternalAgentProvider.get()
                ?: return denied(ExternalDenialCode.CALLER_UNKNOWN, "Jarvis is not ready yet.")
            val callerId = try {
                ExternalAgentFunctions.sanitizeCallerId(callerPackage)
            } catch (_: IllegalArgumentException) {
                return denied(ExternalDenialCode.CALLER_UNKNOWN,
                    "This caller is not authorized to use Jarvis functions.")
            }
            val args = try {
                val json = JSONObject(argsJson)
                json.keys().asSequence().associateWith { json.getString(it) }
            } catch (_: Exception) {
                return denied(ExternalDenialCode.INVALID_ARGUMENTS, "Arguments must be a JSON object of strings.")
            }
            return try {
                when (val result = access.call(callerId, ActionRequest(functionName, args))) {
                    is ExternalAgentCallResult.Executed -> JSONObject()
                        .put("ok", true)
                        .put("result", result.result.toString())
                        .put("attemptId", result.attemptId)
                        .toString()
                    is ExternalAgentCallResult.Denied -> denied(result.code, result.message)
                }
            } catch (e: Exception) {
                denied(ExternalDenialCode.INVALID_ARGUMENTS,
                    "Call failed: ${e.message?.take(200)}")
            }
        }

        /** JSON array of the exposed function names, for client discovery. */
        fun listExposedFunctions(): String =
            JSONArray(ExternalAgentFunctions.EXPOSED.sorted()).toString()

        private fun callingPackage(): String? {
            val uid = Binder.getCallingUid()
            return packageManager.getPackagesForUid(uid)?.firstOrNull()
        }

        private fun denied(code: ExternalDenialCode, message: String): String =
            JSONObject()
                .put("ok", false)
                .put("code", code.name)
                .put("message", message)
                .toString()
    }

    override fun onBind(intent: Intent): IBinder = AgentBinder()
}

/**
 * Process-wide holder for the [ExternalAgentAccess] boundary. Initialized
 * once by JarvisRuntime with the shared task ledger; the exported service
 * reads it from here.
 */
object ExternalAgentProvider {
    @Volatile private var access: ExternalAgentAccess? = null

    fun init(ledger: ToolTaskLedger, executor: ExternalActionExecutor) {
        if (access == null) {
            synchronized(this) {
                if (access == null) access = ExternalAgentAccess(ledger, executor)
            }
        }
    }

    fun get(): ExternalAgentAccess? = access
}
