package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.work.ProcessConversationAdmission
import com.battlesbudz.jarvis.v2.work.TurnGuard

/** Process-wide conversation admission shared by foreground service and model setup. */
internal object ConversationWork {
    val activeJobs get() = ProcessConversationAdmission.activeJobs

    /** Process-wide turn sequence guard claimed by the conversation coordinator (M7). */
    val turnGuard: TurnGuard get() = ProcessConversationAdmission.turnGuard
}
