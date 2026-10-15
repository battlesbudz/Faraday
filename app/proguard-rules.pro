# These SDKs use native string lookups for Kotlin classes, fields and callbacks.
# Preserve their Java/Kotlin ABI while R8 shrinks unused application/AndroidX code.
-keep class com.k2fsa.sherpa.onnx.** { *; }
# Only JNI entry points and native-created DTOs need stable names. Keeping the
# entire SDK would retain unused download/TTS/UI helpers and their dependencies.
-keep class ai.moonshine.voice.JNI { *; }
-keep class ai.moonshine.voice.Transcript { *; }
-keep class ai.moonshine.voice.TranscriptLine { *; }
-keep class ai.moonshine.voice.TranscriberOption { *; }
-keep class ai.moonshine.voice.WordTiming { *; }
-keep class ai.moonshine.voice.SpeakerSpan { *; }
-keep class ai.moonshine.voice.TtsSynthesisResult { *; }
-keep class ai.moonshine.voice.SpeechClip { *; }
-keep class ai.moonshine.voice.TtsChunk { *; }
-keep class com.google.ai.edge.litertlm.** { *; }
# Release instrumentation calls this exact production asset-reader boundary.
-keep class com.battlesbudz.jarvis.v2.ai.audio.GemmaStreamingArtifactStore {
    public static final com.battlesbudz.jarvis.v2.ai.audio.GemmaStreamingArtifactStore INSTANCE;
    public java.util.Map verifyPackagedAssets(kotlin.jvm.functions.Function1);
}
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

-keep class com.battlesbudz.jarvis.v2.voice.MicroWakeWord { *; }
-keep class com.battlesbudz.jarvis.v2.voice.MicroWakeWord$Companion { *; }

# Concrete callback signature used by Sherpa Piper JNI. Kotlin lambdas are not ABI-stable.
-keep class com.battlesbudz.jarvis.v2.voice.SherpaPcmCallback { *; }

# The separately shrunk release instrumentation APK shares the target's class
# loader. Preserve this shared ABI: otherwise R8 can remove/reshape a Kotlin
# method used only by AndroidJUnitRunner (e.g. Intrinsics.checkNotNullParameter),
# producing NoSuchMethodError before any test starts. The rest of the app and
# AndroidX remain optimized; these rules also apply to the APK we actually ship.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class androidx.lifecycle.Lifecycle** { *; }
# AndroidX Test core/runner also calls these shared dependencies directly.
# Audited against the release test DEX's external method and field owners.
-keep class androidx.tracing.** { *; }
-keep class androidx.concurrent.futures.** { *; }
-keep class androidx.annotation.** { *; }
-keep class com.google.common.util.concurrent.ListenableFuture { *; }

# Explicit application boundary exercised by the release integration tests.
-keep class com.battlesbudz.jarvis.v2.actions.MobileAction** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ScreenScrollDirection { *; }
-keep class com.battlesbudz.jarvis.v2.actions.AndroidMobileActionExecutor { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ActionRequest { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ActionValidation** { *; }
# Release test DEX references the typed outcome enum as well as the existing constructor.
-keep class com.battlesbudz.jarvis.v2.actions.ExecutionResult** { *; }
# Release instrumentation reaches the multi-action contract through the shared test DEX.
-keep class com.battlesbudz.jarvis.v2.actions.ActionTurnPlan** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.BatteryCondition** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ActionTurnRunner** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ToolTask** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.FileToolTaskStore** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.InMemoryToolTaskStore** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.JournaledActionPipeline { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ToolAuthority { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ToolActionGrant { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ActionApproval** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ApprovalDecision { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ActionDispatchGate { *; }
-keep class com.battlesbudz.jarvis.v2.actions.AuthorizedDispatch { *; }
# M1e device validation: release journeys drive the permission, lock and
# source-access gates directly from the instrumentation DEX.
-keep class com.battlesbudz.jarvis.v2.actions.ToolSourceAccess { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ToolSourcePolicy { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ToolSourceAccessRecord { *; }
-keep class com.battlesbudz.jarvis.v2.actions.SourceAccessState { *; }
# M2 workflows: release journeys drive the workflow ledger, scheduling
# policy and engine directly from the instrumentation DEX.
-keep class com.battlesbudz.jarvis.v2.actions.WorkflowAlarmScheduler$Scheduled { *; }
-keep class com.battlesbudz.jarvis.v2.actions.DisableResult { *; }
-keep class com.battlesbudz.jarvis.v2.actions.WorkflowDefinition { *; }
-keep class com.battlesbudz.jarvis.v2.actions.WorkflowStep** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.WorkflowTrigger** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.WorkflowCondition** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.WorkflowWait** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.WorkflowEventKind { *; }
-keep class com.battlesbudz.jarvis.v2.actions.WorkflowValueType { *; }
-keep class com.battlesbudz.jarvis.v2.actions.WorkflowBinding { *; }
-keep class com.battlesbudz.jarvis.v2.actions.EffortBudget { *; }
-keep class com.battlesbudz.jarvis.v2.actions.WorkflowOrigin { *; }
-keep class com.battlesbudz.jarvis.v2.actions.WorkflowLedger { *; }
-keep class com.battlesbudz.jarvis.v2.actions.WorkflowOccurrence { *; }
-keep class com.battlesbudz.jarvis.v2.actions.WorkflowOccurrenceState { *; }
-keep class com.battlesbudz.jarvis.v2.actions.WorkflowReceipt { *; }
-keep class com.battlesbudz.jarvis.v2.actions.WorkflowReceiptKind { *; }
-keep class com.battlesbudz.jarvis.v2.actions.MissedRunDecision** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.WorkflowScheduling { *; }
-keep class com.battlesbudz.jarvis.v2.actions.WorkflowScheduling** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.WorkflowEngine { *; }
-keep class com.battlesbudz.jarvis.v2.actions.WorkflowRunOutcome** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.WorkflowSettingsProjection { *; }
-keep class com.battlesbudz.jarvis.v2.actions.WorkflowSettingsProjection** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.WorkflowAlarmScheduler { *; }
-keep class com.battlesbudz.jarvis.v2.actions.DeviceLockGate { *; }
-keep class com.battlesbudz.jarvis.v2.actions.OwnerRecognitionMode { *; }
-keep class com.battlesbudz.jarvis.v2.actions.LockVerdict { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ToolCapabilityProbe { *; }
-keep class com.battlesbudz.jarvis.v2.actions.AndroidToolGatesKt { *; }
-keep class com.battlesbudz.jarvis.v2.ui.PhoneTaskPanelKt { *; }
-keep class com.battlesbudz.jarvis.v2.actions.NativeActionDecoder { *; }
-keep class com.battlesbudz.jarvis.v2.ai.ToolCall { *; }
# Release instrumentation reads durable multi-action receipts after cancellation/recreation.
-keep class com.battlesbudz.jarvis.v2.chat.ConversationHistory { *; }
-keep class com.battlesbudz.jarvis.v2.chat.ConversationMessage { *; }
-keep class com.battlesbudz.jarvis.v2.chat.ActionReceipt { *; }
# Return values traversed by the release test DEX while checking persisted receipts.
-keep class com.battlesbudz.jarvis.v2.chat.ConversationThread { *; }
-keep class com.battlesbudz.jarvis.v2.ChatEntry { *; }

# Release instrumentation exercises the local finalized-input to approved-packet
# boundary and the production prompt builder without model weights.
-keep class com.battlesbudz.jarvis.v2.memory.ConversationMemory { *; }
-keep class com.battlesbudz.jarvis.v2.memory.FinalMemoryInput { *; }
-keep class com.battlesbudz.jarvis.v2.memory.ConversationMemoryResult { *; }
-keep class com.battlesbudz.jarvis.v2.memory.ConversationMemoryOutcome { *; }
-keep class com.battlesbudz.jarvis.v2.memory.ConversationMemorySource { *; }
-keep class com.battlesbudz.jarvis.v2.memory.MemoryOs { *; }
-keep class com.battlesbudz.jarvis.v2.memory.MemoryProposal { *; }
-keep class com.battlesbudz.jarvis.v2.memory.MemorySource { *; }
-keep class com.battlesbudz.jarvis.v2.memory.MemoryRecord { *; }
-keep class com.battlesbudz.jarvis.v2.memory.MemoryResult { *; }
-keep class com.battlesbudz.jarvis.v2.memory.MemoryPacketResult { *; }
-keep class com.battlesbudz.jarvis.v2.memory.MemoryContextPacket { *; }
-keep class com.battlesbudz.jarvis.v2.memory.MemoryReviewStatus { *; }
-keep class com.battlesbudz.jarvis.v2.memory.MemoryOutcome { *; }
-keep class com.battlesbudz.jarvis.v2.ai.ConversationPromptBuilder { *; }
-keep class com.battlesbudz.jarvis.v2.chat.ShortTermConversationContext { *; }

# A controlled Android-test fixture renders the actual conversation surface.
-keep class com.battlesbudz.jarvis.v2.ui.ConversationScreenKt { *; }
-keep class com.battlesbudz.jarvis.v2.ui.MemoryScreenKt { *; }
-keep class com.battlesbudz.jarvis.v2.ai.LocalModelSpec { *; }
# Release instrumentation seeds the shipping parent through ModelCatalog; retain its Kotlin object INSTANCE ABI.
-keep class com.battlesbudz.jarvis.v2.ai.ModelCatalog { *; }
-keep class com.battlesbudz.jarvis.v2.voice.VoiceSessionUi { *; }
-keep class com.battlesbudz.jarvis.v2.voice.VoiceSessionState { *; }
# test49 reads phase.label across the independently shrunk release-test DEX.
# Build 1025 removed the otherwise inlined getter; preserve this exact enum ABI.
-keep class com.battlesbudz.jarvis.v2.voice.VoicePhase { *; }

# The controlled Compose fixture in release instrumentation directly calls these
# shared top-level composables after replacing the activity content.
-keep class androidx.activity.compose.ComponentActivityKt { *; }
-keep class androidx.compose.ui.Modifier { *; }
-keep class androidx.compose.ui.Modifier$Companion { *; }
-keep class androidx.compose.runtime.Composer { *; }
-keep class androidx.compose.runtime.ComposerKt { *; }
-keep class androidx.compose.runtime.ScopeUpdateScope { *; }
# test45 acknowledges the committed replacement from the separate test DEX.
# Preserve the exact descriptor; R8 otherwise removes its unused changed argument.
-keep class androidx.compose.runtime.EffectsKt {
    public static void SideEffect(kotlin.jvm.functions.Function0, androidx.compose.runtime.Composer, int);
}
-keep class androidx.compose.runtime.internal.ComposableLambdaKt { *; }
-keep class androidx.compose.material3.MaterialThemeKt { *; }
-keep class androidx.compose.material3.SurfaceKt { *; }
-keep class androidx.compose.material3.TextKt { *; }
-keep class androidx.compose.foundation.layout.SizeKt { *; }
# Build 916's layout test calls Dp.constructor-impl through its separate DEX.
# Preserve this inline value-class ABI used by the 320 dp width fixture.
-keep class androidx.compose.ui.unit.Dp { *; }
-keep class androidx.compose.foundation.layout.BoxKt { *; }
-keep class androidx.compose.ui.semantics.SemanticsModifierKt { *; }
-keep class androidx.compose.ui.semantics.SemanticsProperties_androidKt { *; }

# Natural route release journeys invoke the authoritative routing contract.
-keep class com.battlesbudz.jarvis.v2.ai.TurnOrchestrator { *; }
-keep class com.battlesbudz.jarvis.v2.ai.TurnPlan { *; }
-keep class com.battlesbudz.jarvis.v2.ai.TurnKind { *; }
-keep class com.battlesbudz.jarvis.v2.ai.ReferenceGroundingClient { *; }

# Continuous accepted-action release journeys cross this production boundary from
# the separately shrunk instrumentation DEX. Keep only the durable queue/session
# contracts and saved-call DTO/controller API they invoke.
-keep class com.battlesbudz.jarvis.v2.actions.AcceptedActionQueue { *; }
-keep class com.battlesbudz.jarvis.v2.actions.AcceptedActionTask { *; }
-keep class com.battlesbudz.jarvis.v2.actions.AcceptedActionEvent { *; }
-keep class com.battlesbudz.jarvis.v2.actions.AcceptedActionState { *; }
-keep class com.battlesbudz.jarvis.v2.voice.ContinuousActionSession { *; }
-keep class com.battlesbudz.jarvis.v2.voice.SessionCapture { *; }
-keep class com.battlesbudz.jarvis.v2.voice.CapturedKind** { *; }
-keep class com.battlesbudz.jarvis.v2.voice.CaptureOutcome** { *; }
-keep class com.battlesbudz.jarvis.v2.voice.PendingReport { *; }
-keep class com.battlesbudz.jarvis.v2.voice.ReportDelivery { *; }
-keep class com.battlesbudz.jarvis.v2.voice.VoiceActionControl** { *; }
-keep class com.battlesbudz.jarvis.v2.voice.VoiceSessionController { *; }
-keep class com.battlesbudz.jarvis.v2.voice.VoiceCallStore { *; }
-keep class com.battlesbudz.jarvis.v2.voice.SharedPreferencesVoiceCallStore { *; }
-keep class com.battlesbudz.jarvis.v2.voice.VoiceCallRecord { *; }
-keep class com.battlesbudz.jarvis.v2.voice.TranscriptEntry { *; }
-keep class com.battlesbudz.jarvis.v2.voice.VoiceActionOutcome { *; }
-keep class com.battlesbudz.jarvis.v2.voice.VoiceTaskStatus { *; }
-keep class com.battlesbudz.jarvis.v2.voice.VoiceTaskState { *; }
# The separately shrunk AndroidJUnitRunner DEX reads the atomic store snapshot
# and Compose companion through the target APK's class loader. These are narrow
# shared ABI owners observed in the release test DEX, not broad Compose keeps.
-keep class com.battlesbudz.jarvis.v2.memory.MemoryStore$Read { *; }
-keep class com.battlesbudz.jarvis.v2.memory.MemoryStore { *; }
-keep class com.battlesbudz.jarvis.v2.memory.MemoryStore$Update { *; }
-keep class com.battlesbudz.jarvis.v2.memory.MemoryPersistence { *; }
-keep class com.battlesbudz.jarvis.v2.memory.SQLiteMemoryStore { *; }
-keep class com.battlesbudz.jarvis.v2.memory.MemorySnapshot { *; }
# The previous-APK upgrade journey reads persisted tombstones through this
# shared DTO. Retain its ABI before R8 inlines getters used by the test DEX.
-keep class com.battlesbudz.jarvis.v2.memory.MemoryTombstone { *; }
-keep class androidx.compose.runtime.Composer$Companion { *; }
# The release-test inline Box composition also links these companion getters
# from the independently shrunk instrumentation DEX.
-keep class androidx.compose.ui.Alignment$Companion { *; }
-keep class androidx.compose.ui.node.ComposeUiNode$Companion { *; }
# Keep the exact interface field through which the test DEX reaches Alignment's
# companion; the companion getter keep above does not retain this static field.
-keepclassmembers interface androidx.compose.ui.Alignment {
    public static androidx.compose.ui.Alignment$Companion Companion;
}
# Inline Compose code in the separately shrunk release-test DEX invokes these
# runtime helper owners through the target APK's class loader.
-keep class androidx.compose.runtime.ComposablesKt { *; }
-keep class androidx.compose.runtime.SnapshotStateKt** { *; }
-keep interface androidx.compose.runtime.State { *; }
-keep class androidx.compose.runtime.Updater { *; }

# Preserve the destination branch navigation policy shared with release instrumentation.
-keep class com.battlesbudz.jarvis.v2.voice.VoiceNavigationPolicy** { *; }

# Controlled release dictation fixture implements this production boundary.
-keep interface com.battlesbudz.jarvis.v2.voice.ChatDictation { *; }
# The raw-audio release journey validates the stored WAV through this shared API.
-keep class com.battlesbudz.jarvis.v2.chat.AttachmentPolicy { *; }

# Release storage recovery journey exercises the installed-file/preferences boundary.
-keep class com.battlesbudz.jarvis.v2.ai.ModelStore { *; }
# The overlay journey observes the real armed StateFlow across its UI changes.
-keep class androidx.compose.runtime.SnapshotStateKt { *; }
# The separate Download/Choose journey renders the shipping model browser.
-keep class com.battlesbudz.jarvis.v2.ui.ModelBrowserKt { *; }
-keep class com.battlesbudz.jarvis.v2.ai.PhoneProfile { *; }
# Dark-palette screenshot fixtures call this factory through the target class loader.
-keep class androidx.compose.material3.ColorSchemeKt { *; }
-keep class androidx.compose.material3.ColorScheme { *; }

# Build 775: the release-test DEX invokes mutableStateOf$default through the
# SnapshotStateKt facade, which production R8 otherwise removes/inlines. Keep
# that facade and its inherited factory ABI, not the entire Compose runtime.
-keep,includedescriptorclasses class androidx.compose.runtime.SnapshotStateKt** {
    public static *** mutableStateOf*(...);
}

# The controlled shipping-parent fixture crosses these precise release ABI boundaries.
# Build 842 removed ModelStore default wrappers and reshaped JarvisApp/VoicePlaybackFrame.
-keep class com.battlesbudz.jarvis.v2.ai.ModelStore {
    public static int $stable;
    public <init>(...);
    public java.io.File fileFor(...);
    public boolean isUsable(...);
    public static boolean isUsable$default(...);
    public boolean smokeTestPassed(...);
    public static boolean smokeTestPassed$default(...);
}
-keep class com.battlesbudz.jarvis.v2.ui.JarvisAppKt {
    public static void JarvisApp(...);
}
-keep class com.battlesbudz.jarvis.v2.voice.TtsModelStore {
    public static int $stable;
    public <init>(...);
}
-keep class com.battlesbudz.jarvis.v2.voice.VoicePlaybackFrame { *; }

# The archive checkpoint is exercised through these exact release-test boundaries.
-keep class com.battlesbudz.jarvis.v2.memory.MemorySourceArchive { *; }
-keep class com.battlesbudz.jarvis.v2.memory.SourceArchiveOutcome { *; }
-keep class com.battlesbudz.jarvis.v2.memory.SourceArchiveCapture { *; }
-keep class com.battlesbudz.jarvis.v2.memory.SourceArchiveSearch { *; }
-keep class com.battlesbudz.jarvis.v2.memory.SourceEpisode { *; }
# WorkManager restores the persisted worker by class name after process death.
-keep class com.battlesbudz.jarvis.v2.memory.MemoryArchiveMaintenanceWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# The reference journey invokes these narrow shipping API owners from its separate DEX.
-keep class com.battlesbudz.jarvis.v2.ai.ReferencePdfText { *; }
-keep class com.battlesbudz.jarvis.v2.memory.MemoryCaptureAcknowledgment { *; }

# PDFBox's optional JPX image decoder is deliberately absent. ReferencePdfText
# extracts embedded text only; it does not render images or perform OCR.
# PDFBox supports this configuration and ignores JPX images without the decoder.
-dontwarn com.gemalto.jp2.JP2Decoder

# Shared assistant readiness API used by the separately shrunk release journey.
-keep class com.battlesbudz.jarvis.v2.assistant.JarvisInteractionService** { *; }

# M1c screen-control boundary exercised by the release integration tests
# (test55-test58). The separately shrunk instrumentation DEX constructs
# ScreenNode fixtures, calls the top-level extractor, and drives the session
# through the service bridge; without these, R8 strips or renames them and the
# tests fail with NoClassDefFoundError/NoSuchMethodError.
-keep class com.battlesbudz.jarvis.v2.actions.ScreenControlService { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ScreenControlService$Companion { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ScreenControlServiceKt { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ScreenControlSession { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ScreenNode { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ScreenObservation { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ScreenBridge { *; }
-keep class com.battlesbudz.jarvis.v2.actions.AdmitResult** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.DispatchGate** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.TargetVerification** { *; }
# Round-4 findings 1+2: the release instrumentation DEX calls these top-level
# functions directly through the target class loader (verifiedLaunchReceipt in
# test07, contentFingerprintOf in FakeScreenBridge). Without a keep, R8
# renames the file-facade classes and the tests crash with
# NoClassDefFoundError — the same failure mode the M1c keeps above guard.
-keep class com.battlesbudz.jarvis.v2.actions.BackgroundLaunchKt { *; }
# The same test07 call crosses this enum in the retained function descriptor.
-keep class com.battlesbudz.jarvis.v2.actions.BackgroundLaunchRoute { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ScreenControlKt { *; }

# M1d task/conversation scheduling boundary exercised by the release
# integration tests (test59-test62). The instrumentation DEX drives the
# scheduler, the approval admission, the progress projector and the
# notification poster directly; without these, R8 renames them and the tests
# fail with IncompatibleClassChangeError/NoSuchMethodError.
-keep class com.battlesbudz.jarvis.v2.actions.TaskScheduler { *; }
-keep class com.battlesbudz.jarvis.v2.actions.TaskResource** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ScheduleDecision** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.TaskStopRouter { *; }
-keep class com.battlesbudz.jarvis.v2.actions.TaskStopScope** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ScreenApprovalAdmission { *; }
-keep class com.battlesbudz.jarvis.v2.actions.TaskStatusProjection { *; }
-keep class com.battlesbudz.jarvis.v2.actions.TaskProjectionState { *; }
-keep class com.battlesbudz.jarvis.v2.actions.TaskProgressProjector { *; }
-keep class com.battlesbudz.jarvis.v2.actions.TaskProgressNotification { *; }
# M3 ecosystem integrations: the release journeys drive the provider
# registry, dispatcher, MCP flow and settings projection reflectively
# through the shared test class loader; keep them unshrunk like the other
# journey-driven action classes.
-keep class com.battlesbudz.jarvis.v2.actions.Provider** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.AppFunction** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.Mcp** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.Alias** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.Discovery** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.TypeConversion** { *; }
# These two start with InMemory/UrlConnection so the Mcp** wildcard misses
# them; the journeys instantiate both from the instrumentation DEX.
-keep class com.battlesbudz.jarvis.v2.actions.InMemoryMcpCredentialStore { *; }
-keep class com.battlesbudz.jarvis.v2.actions.UrlConnectionMcpHttpClient { *; }
# Reminder bug-fix slice: the release journeys drive the reminder coordinator
# and the schedule/notification boundary from the instrumentation DEX.
-keep class com.battlesbudz.jarvis.v2.actions.ReminderWorkflowKt { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ReminderSpec { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ReminderScheduling { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ReminderCoordinator { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ReminderNotification { *; }

# Controlled release benchmark journey crosses the independently shrunk test
# DEX into the shipping DTOs, persisted store, and dashboard composable.
-keep class com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmark** { *; }
-keep class com.battlesbudz.jarvis.v2.diagnostics.AndroidPipelineBenchmarkStore { *; }
-keep class com.battlesbudz.jarvis.v2.ui.PipelineBenchmarkScreenKt { *; }
# Release export journey verifies real ContentResolver/FileProvider transports.
-keep class com.battlesbudz.jarvis.v2.ui.BenchmarkExportFiles { *; }
-keep class com.battlesbudz.jarvis.v2.ui.BenchmarkExportFormat { *; }
# Controlled picker outcomes cross the separately shrunk instrumentation DEX.
-keep class androidx.activity.compose.LocalActivityResultRegistryOwner { *; }
-keep interface androidx.activity.result.ActivityResultRegistryOwner { *; }
-keep class androidx.activity.result.ActivityResultRegistry { *; }
-keep class androidx.activity.result.contract.ActivityResultContract { *; }
-keep class androidx.core.content.FileProvider { *; }
-keep class androidx.compose.ui.platform.AndroidCompositionLocals_androidKt { *; }
-keep class androidx.compose.runtime.CompositionLocalKt { *; }
-keep class androidx.compose.runtime.CompositionLocal { *; }
-keep class androidx.compose.runtime.ProvidableCompositionLocal { *; }
-keep class androidx.compose.runtime.ProvidedValue { *; }


# Build 878: the separate release-test DEX reads the input-mode companion and
# renders the shipping settings composable. Preserve these precise shared ABI
# owners; all other voice/UI code remains eligible for release optimization.
-keep class com.battlesbudz.jarvis.v2.voice.VoiceInputMode** { *; }
# The real previous-APK upgrade verifies the profile migration through this ABI.
-keep class com.battlesbudz.jarvis.v2.voice.SpeechCaptureProfile** { *; }
-keep class com.battlesbudz.jarvis.v2.ui.VoiceInputSettingsKt { *; }

# Build 880: test47's controlled settings fixture calls these lazy-layout
# entry points from the independently shrunk test DEX. R8 otherwise reshapes
# LazyColumn's static ABI and the LazyListScope item default wrapper.
-keep class androidx.compose.foundation.lazy.LazyDslKt { *; }
-keep interface androidx.compose.foundation.lazy.LazyListScope { *; }
# Release instrumentation exercises the production farewell/service lifecycle.
# Preserve only the runtime entry/member ABI it calls across the target class loader.
-keep,allowoptimization,includedescriptorclasses class com.battlesbudz.jarvis.v2.JarvisRuntime {
    public static ** Companion;
    public void arm();
    public java.lang.String sendChat(...);
    public static java.lang.String sendChat$default(...);
    public void endVoiceCall(...);
    public void returnToWakeListening*(java.lang.String);
    public *** getVoiceSessionController*();
    public *** getVoiceCallStore*();
    public *** getVoiceTurnJob*();
    public void setVoiceTurnJob*(...);
    public boolean getVoiceSessionArmed*();
    public void setVoiceSessionArmed*(boolean);
    public int getAudioRecoveryAttempts*();
    public void setAudioRecoveryAttempts*(int);
    public *** getReturnToWakeCuePending*();
    public *** getSessionReport*();
    public void setSessionReport*(...);
}
-keep,allowoptimization class com.battlesbudz.jarvis.v2.JarvisRuntime$Companion {
    public com.battlesbudz.jarvis.v2.JarvisRuntime get(android.content.Context);
}
# The same lifecycle journey observes the service's completed-stop signal.
-keepclassmembers class com.battlesbudz.jarvis.v2.voice.VoiceCallService {
    public static ** Companion;
}
-keep,allowoptimization class com.battlesbudz.jarvis.v2.voice.VoiceCallService$Companion {
    public *** getStopRequested();
}

# Wisp's release journey calls the shipping observer-driven header and DTO from test DEX.
-keep,includedescriptorclasses class com.battlesbudz.jarvis.v2.ui.WispPresenceKt { *; }
-keep class com.battlesbudz.jarvis.v2.presentation.AgentActivitySnapshot { *; }
-keep class com.battlesbudz.jarvis.v2.presentation.AgentActivityKind { *; }
# test80 (video-call farewell): the release journey reads the service's live
# instance and invokes the spoken-farewell refresh through the Companion
# after startForegroundService. R8 removed the Companion class outright
# (release mapping: VideoCallService$Companion -> R8$$REMOVED$$CLASS), so the
# instrumentation DEX's Companion/getInstance/refreshVideoStatusAfterFarewell
# linkage fails on device. Keep only that narrow test-facing companion
# surface; the rest of the service stays eligible for release shrinking.
-keepclassmembers class com.battlesbudz.jarvis.v2.voice.VideoCallService {
    public static ** Companion;
}
-keep,allowoptimization class com.battlesbudz.jarvis.v2.voice.VideoCallService$Companion {
    public com.battlesbudz.jarvis.v2.voice.VideoCallService getInstance();
    public void refreshVideoStatusAfterFarewell(java.lang.String);
}
# test80 (video-call farewell): the journey swaps a fake vision pipeline into
# the call registry; R8 otherwise removes these helpers as unreachable from
# the production factories.
-keep class com.battlesbudz.jarvis.v2.voice.CallVisionController** { *; }
-keep class com.battlesbudz.jarvis.v2.voice.CallVisionRegistry** { *; }
-keep class com.battlesbudz.jarvis.v2.voice.VisionFrameHub** { *; }
-keep class com.battlesbudz.jarvis.v2.voice.VisionObserver** { *; }
# test76/test77 (M4 browser runtime): the production factories do not install
# the browser path, so R8 removes the whole subtree; the release journey
# drives it directly across the shared class loader.
-keep class com.battlesbudz.jarvis.v2.actions.BrowserPageSnapshot** { *; }
# test77 (M4 browser runtime): currentPage() returns BrowserPage, not the
# snapshot DTO; without this keep R8 renames it (observed as actions/w)
# and strips getPageToken(), crashing the release journey.
-keep class com.battlesbudz.jarvis.v2.actions.BrowserPage** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.BrowserProvenance** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.BrowserLink** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.BrowserForm** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.BrowserField** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.FieldKind** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.BrowserBridge** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.FakeBrowserBridge** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.BrowserSession** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.AndroidBrowserExecutor** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.MobileToolCatalog** { *; }
# test78 (M5 script step): the allowlisted script host and its execution
# outcome are only referenced by the release journey.
-keep class com.battlesbudz.jarvis.v2.actions.ScriptHost** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ScriptExecution** { *; }
# test78 (M5 script step): the journey calls runWithInterpreter with default
# args through the ScriptRuntimeKt facade; R8 otherwise drops the $default
# synthetic (observed as NoSuchMethodError on the obfuscated facade).
-keep,includedescriptorclasses class com.battlesbudz.jarvis.v2.actions.ScriptRuntimeKt {
    public static *** runWithInterpreter(...);
    public static *** runWithInterpreter$default(...);
}
# test79 (M5 workflow export): top-level export/parse entry points and the
# export model, referenced only by the release journey.
-keep class com.battlesbudz.jarvis.v2.actions.WorkflowImportExportKt** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.WorkflowManifestKt** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.WorkflowExport** { *; }
# test79 (M5 workflow export): the parsed manifest DTO and its nested model
# types are only touched by the release journey; without this keep R8 renames
# the WorkflowManifest class (observed as actions/g5) and strips getWorkflow(),
# crashing the release journey with NoSuchMethodError.
-keep class com.battlesbudz.jarvis.v2.actions.WorkflowManifest** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ExportPreview** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.RedactionRecord** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.SetupBinding** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ManifestProvenance** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ToolContract** { *; }
-keep class com.battlesbudz.jarvis.v2.actions.ScriptRuntimeRequirements** { *; }

# Smart Turn JNI is compiled into the existing Sherpa library; retain exactly this bridge.
-keep class com.battlesbudz.jarvis.v2.voice.smartturn.SmartTurnNative { *; }

# Native on-device vision (phoneinference module): keep the ONNX Runtime Java API.
-keep class ai.onnxruntime.** { *; }
