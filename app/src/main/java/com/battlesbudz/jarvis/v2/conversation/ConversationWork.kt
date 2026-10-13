package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.work.ProcessConversationAdmission

/** Process-wide conversation admission shared by foreground service and model setup. */
internal object ConversationWork {
    val activeJobs get() = ProcessConversationAdmission.activeJobs
}
