package com.battlesbudz.jarvis.v2.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.battlesbudz.jarvis.v2.assistant.JarvisInteractionService

/**
 * Live-status decision for the assistant banner: it shows only while
 * Faraday is not the selected assistant and the user has not dismissed it.
 * The live selection status is re-read on every start and resume, so
 * becoming the default assistant hides the banner even if it was never
 * dismissed, and a stale dismissed flag can never keep it showing once
 * Faraday is selected.
 */
internal fun assistantBannerShouldShow(isSelected: Boolean, dismissed: Boolean): Boolean =
    !isSelected && !dismissed

/**
 * The dismissed flag to keep after a status re-check. Selection clears a
 * persisted dismissal: if the phone later reverts the default assistant
 * (for example back to Gemini, as Battles has seen), the banner must be
 * able to nag again instead of staying suppressed forever by a flag that
 * was set in a previous not-selected episode.
 */
internal fun assistantBannerDismissedAfterRecheck(isSelected: Boolean, dismissed: Boolean): Boolean =
    if (isSelected) false else dismissed

/**
 * Banner shown when Jarvis is not the system-selected assistant.
 * Explains that background app launches need the assistant binding, with a
 * button to open system assistant settings. Dismissal is persisted so it
 * only nags once per not-selected episode.
 *
 * Root cause of the build-1544 bug this fixes: the status was read only
 * once, in a LaunchedEffect(Unit) at first composition. Tapping "Open
 * assistant settings", setting Faraday as the default, and returning left
 * the stale not-selected result on screen, so the popup still demanded a
 * manual Dismiss even though its condition was already satisfied. The
 * banner now re-checks the live status on start and on every ON_RESUME,
 * so returning from the settings screen hides it automatically.
 *
 * The banner is a popup overlay: it must be composed AFTER the content it
 * covers so it draws on top, and its card background is fully opaque so no
 * button or text underneath shows through.
 *
 * @param onVisibilityChanged reports whether the popup is currently visible,
 * so the host can hide obscured content from accessibility services (a popup
 * must behave like a popup).
 */
@Composable
fun AssistantStatusBanner(
    onVisibilityChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences("jarvis_assistant_status", Context.MODE_PRIVATE)
    }
    AssistantStatusBanner(
        onVisibilityChanged = onVisibilityChanged,
        isSelected = { JarvisInteractionService.isSelected(context) },
        loadDismissed = { prefs.getBoolean("banner_dismissed", false) },
        saveDismissed = { dismissed ->
            prefs.edit().putBoolean("banner_dismissed", dismissed).apply()
        },
        onOpenSettings = {
            context.startActivity(
                Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        },
    )
}

/**
 * The assistant-status gating without the Android service / preferences
 * plumbing, so tests can drive the selection status and the lifecycle
 * directly. Production passes the real [JarvisInteractionService] check
 * and the persisted dismissed flag.
 */
@Composable
internal fun AssistantStatusBanner(
    onVisibilityChanged: (Boolean) -> Unit,
    isSelected: () -> Boolean,
    loadDismissed: () -> Boolean,
    saveDismissed: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    // Explicit State holders (not delegated locals) so the lifecycle
    // observer registered once below always reads the current values.
    val dismissedState = remember { mutableStateOf(loadDismissed()) }
    val selectedState = remember { mutableStateOf(isSelected()) }

    fun recheck() {
        val nowSelected = isSelected()
        selectedState.value = nowSelected
        val cleared = assistantBannerDismissedAfterRecheck(nowSelected, dismissedState.value)
        if (cleared != dismissedState.value) {
            dismissedState.value = cleared
            saveDismissed(cleared)
        }
    }

    // Start: read the live status when the banner first composes.
    LaunchedEffect(Unit) { recheck() }

    // Resume: the user may have just set (or unset) Faraday as the default
    // assistant in the system settings this banner sent them to. Re-check
    // on every return to the foreground, not only at first composition.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) recheck()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val show = assistantBannerShouldShow(selectedState.value, dismissedState.value)

    LaunchedEffect(show) {
        onVisibilityChanged(show)
    }

    if (!show) return

    AssistantStatusBannerCard(
        onOpenSettings = onOpenSettings,
        onDismiss = {
            saveDismissed(true)
            dismissedState.value = true
        }
    )
}

/**
 * The presentational banner card, without the SharedPreferences /
 * assistant-service gating, so readability tests can mount it directly.
 */
@Composable
fun AssistantStatusBannerCard(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Faraday is not your system assistant",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Opening apps while Faraday is in the background needs the " +
                    "system assistant binding. Set Faraday as your default " +
                    "assistant to make background launches work reliably.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))
            Row {
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Open assistant settings")
                }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Dismiss")
                }
            }
        }
    }
}

/**
 * The production popup arrangement for the assistant-status banner. JarvisApp
 * renders its content through this layer, and the readability tests mount this
 * same composable, so a regression in the ordering, tap handling, or semantics
 * wiring fails the tests instead of shipping silently.
 *
 * The content is composed first and the banner overlay second, so the popup
 * always draws on top. The overlay carries a no-op clickable: a popup must
 * consume taps, otherwise they fall through to the covered controls
 * underneath. While the popup shows, the obscured content is hidden from
 * accessibility services (the same modal idiom used for showingMemory).
 */
@Composable
fun AssistantBannerPopupLayer(
    bannerVisible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
    banner: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .then(if (bannerVisible) Modifier.clearAndSetSemantics { } else Modifier)
                .testTag("banner_popup_content")
        ) {
            content()
        }
        // Popup banner, drawn after the content so it renders on top.
        // The card background is fully opaque, so no button or text
        // underneath shows through: it looks and behaves like a popup.
        Box(
            Modifier.fillMaxWidth()
                .align(Alignment.TopCenter)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { /* consume: taps must never reach covered controls */ }
                )
                .testTag("banner_popup_overlay")
        ) {
            banner()
        }
    }
}
