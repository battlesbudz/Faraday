package com.battlesbudz.jarvis.v2.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.battlesbudz.jarvis.v2.actions.ToolTaskJournal
import com.battlesbudz.jarvis.v2.chat.ConversationHistory
import com.battlesbudz.jarvis.v2.presentation.AgentActivitySnapshot
import com.battlesbudz.jarvis.v2.voice.VoicePlaybackFrame
import com.battlesbudz.jarvis.v2.voice.VoiceSessionState
import com.battlesbudz.jarvis.v2.voice.VoiceSessionUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/** Shared app chrome keeps the persistent character outside every navigation destination. */
@Composable
internal fun WispAppFrame(presence: @Composable () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))) {
        presence()
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

/** The one app-level character. It observes existing owners and never owns a call or task. */
@Composable
internal fun WispPresence(
    history: ConversationHistory,
    chatBusy: StateFlow<Boolean>,
    callState: StateFlow<VoiceSessionState>,
    voicePlayback: StateFlow<VoicePlaybackFrame>,
    phoneTasks: StateFlow<ToolTaskJournal?>?,
    phoneTaskError: StateFlow<String?>?,
    agentActivity: StateFlow<AgentActivitySnapshot?>?,
    setupActivity: WispPresentation? = null,
    onMotionFrame: ((Boolean) -> Unit)? = null
) {
    val thread by history.current.collectAsStateWithLifecycle()
    val busy by chatBusy.collectAsStateWithLifecycle()
    val call by callState.collectAsStateWithLifecycle()
    val armed by VoiceSessionUi.armed.collectAsStateWithLifecycle()
    val phase by VoiceSessionUi.phase.collectAsStateWithLifecycle()
    val paused by VoiceSessionUi.paused.collectAsStateWithLifecycle()
    val journal by (phoneTasks?.collectAsStateWithLifecycle() ?: remember { mutableStateOf<ToolTaskJournal?>(null) })
    val error by (phoneTaskError?.collectAsStateWithLifecycle() ?: remember { mutableStateOf<String?>(null) })
    val observed by (agentActivity?.collectAsStateWithLifecycle() ?: remember { mutableStateOf<AgentActivitySnapshot?>(null) })
    val receipts = remember { WispReceiptTracker() }
    val receptions = remember { WispReceptionTracker() }
    var receivedKey by remember(thread.id) { mutableStateOf<Long?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, agentActivity, history, thread.id) {
        fun baseline() {
            receptions.resume(agentActivity?.value, history.current.value.id)
            receivedKey = null
        }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) baseline() else receptions.suspend()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> baseline()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    receptions.suspend()
                    receivedKey = null
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); receptions.suspend() }
    }
    LaunchedEffect(observed, thread.id) {
        receptions.update(observed, thread.id)?.let { receivedKey = it }
    }
    LaunchedEffect(receivedKey) {
        if (receivedKey != null) {
            delay(700)
            receivedKey = null
        }
    }
    var receipt by remember(thread.id) { mutableStateOf<WispPresentation?>(null) }
    LaunchedEffect(journal, thread.id) {
        receipts.update(journal, thread.id)?.let { receipt = it }
    }
    LaunchedEffect(receipt?.taskKey) {
        if (receipt != null) {
            delay(if (receipt?.activity == WispActivity.SUCCESS) 2_600 else 5_000)
            receipt = null
        }
    }
    val activity = observed?.takeIf { it.conversationId == thread.id }?.let {
        WispPresentation(
            when (it.kind) {
                com.battlesbudz.jarvis.v2.presentation.AgentActivityKind.ERROR -> WispActivity.ERROR
                com.battlesbudz.jarvis.v2.presentation.AgentActivityKind.CHECKING_REFERENCES -> WispActivity.CHECKING
                com.battlesbudz.jarvis.v2.presentation.AgentActivityKind.WORKING -> WispActivity.THINKING
            },
            it.label, taskKey = "activity:${it.operationId}", gesture =
                if (it.kind == com.battlesbudz.jarvis.v2.presentation.AgentActivityKind.CHECKING_REFERENCES)
                    WispGesture.RESEARCH else WispGesture.NONE)
    } ?: setupActivity
    val presentation = WispPresenter.present(thread.id, journal, error, busy, armed, phase, call, paused, activity, receipt)
    // Reserve a little more space for the whole drawing during a real call, including its card.
    // Only the call boundary changes size; poses/audio never move the transcript underneath it.
    val compact = LocalConfiguration.current.screenHeightDp < 500
    val viewport = WispPresenter.viewport(armed, call, compact)
    val durationScale by rememberWispDurationScale()
    // Bypass animated state entirely at scale zero, including a change during an active tween.
    val width by if (durationScale > 0f) {
        animateDpAsState(viewport.widthDp.dp, tween(320), label = "Wisp viewport width")
    } else rememberUpdatedState(viewport.widthDp.dp)
    val height by if (durationScale > 0f) {
        animateDpAsState(viewport.heightDp.dp, tween(320), label = "Wisp viewport height")
    } else rememberUpdatedState(viewport.heightDp.dp)
    val detailsAllowed by rememberWispDetailsAllowed()
    val status = WispPresenter.statusText(presentation, detailsAllowed)
    Column(Modifier.fillMaxWidth().testTag("jarvis_wisp"), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(width, height).testTag("jarvis_wisp_viewport")) {
            WispAudioCharacter(presentation, voicePlayback, armed, phase, paused, call, receivedKey, Modifier.fillMaxSize(), onMotionFrame)
        }
        if (status != null) {
            // No marquee/typewriter/extra inference. Event updates replace one bounded public
            // sentence; real approval/outcome transitions are never delayed to animate text.
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 460.dp)) {
                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium, maxLines = 2,
                    textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        .testTag("jarvis_wisp_status").semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }
}

/** Isolate high-rate envelope collection from the app chrome and transcript composition. */
@Composable
private fun WispAudioCharacter(presentation: WispPresentation, playbackFlow: StateFlow<VoicePlaybackFrame>,
    armed: Boolean, phase: com.battlesbudz.jarvis.v2.voice.VoicePhase, paused: Boolean,
    callState: VoiceSessionState, receivedKey: Long?, modifier: Modifier,
    onMotionFrame: ((Boolean) -> Unit)?) {
    val microphone by VoiceSessionUi.level.collectAsStateWithLifecycle()
    val playback by playbackFlow.collectAsStateWithLifecycle()
    val audio = WispPresenter.audioActivity(phase, armed, paused, callState)
    val level = WispPresenter.audioLevel(audio ?: WispActivity.READY, phase, armed, paused, microphone, playback.level)
    WispCharacter(presentation, level, modifier, audioActivity = audio, receivedKey = receivedKey,
        onMotionFrame = onMotionFrame)
}
