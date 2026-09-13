package com.etio.ot.ui.delay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.etio.ot.ai.LlmEngine
import com.etio.ot.ui.tutorial.SpotlightTarget
import com.etio.ot.ui.tutorial.spotlight
import com.etio.ot.ui.common.BlockedState
import com.etio.ot.ui.theme.Etio
import com.etio.ot.ui.theme.EtioMonoStyle
import com.etio.ot.ui.theme.glass
import com.etio.ot.ui.theme.rememberEtioHaptics

/**
 * A sheet, not a page. It sits over the day rather than replacing it, because what
 * happens here is an interruption to the list, not a departure from it.
 *
 * Glass is allowed on this surface — it is an overlay, and it carries none of the
 * day's timers. The elapsed counter inside it is the exception that proves the rule:
 * it sits on its own opaque block, because a number read from three metres cannot be
 * asked to compete with whatever shows through.
 */
@Composable
fun DelayCaptureScreen(
    caseId: String,
    onBack: () -> Unit,
    onNotify: (String) -> Unit,
    /** True only when arriving from the breach banner, whose tap was the start. */
    autoStart: Boolean = false,
    viewModel: DelayCaptureViewModel = viewModel(factory = DelayCaptureViewModel.factory(caseId)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle(LlmEngine.EngineState.NotLoaded)
    var typed by remember { mutableStateOf("") }
    var showTyped by remember { mutableStateOf(false) }
    val haptics = rememberEtioHaptics()
    val mic = rememberMicPermission()
    val micUsable = mic.granted && state.micAvailable

    // Nothing to hold the mic for — put the keyboard fallback up straight away.
    LaunchedEffect(micUsable) { if (!micUsable) showTyped = true }

    // The breach banner's mic tap continues here rather than asking for a second one.
    var autoStarted by remember { mutableStateOf(false) }
    LaunchedEffect(autoStart, micUsable) {
        if (autoStart && micUsable && !autoStarted) {
            autoStarted = true
            haptics.medium()
            viewModel.startListening()
        }
    }

    Box(Modifier.fillMaxSize()) {

        // The scrim is a sibling of the sheet, not its parent. Wrapping the sheet in a
        // clickable merges the whole subtree into one semantics node: a screen reader
        // then announces the sheet as a single element, the buttons inside stop being
        // separately focusable, and a tap aimed at one of them dismisses the sheet
        // instead. Behaviour is unchanged — tapping outside still closes it while idle.
        Box(
            Modifier
                .fillMaxSize()
                .background(Etio.colors.background.copy(alpha = 0.88f))
                .then(
                    if (state.phase == CapturePhase.IDLE) {
                        Modifier.clickable(onClick = onBack)
                    } else {
                        Modifier
                    },
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .glass(RoundedCornerShape(topStart = Etio.radius.sheet, topEnd = Etio.radius.sheet))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Etio.space.gutter)
                .padding(bottom = Etio.space.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(Etio.space.m))
            Box(
                Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Etio.colors.textSecondary.copy(alpha = 0.4f)),
            )
            Spacer(Modifier.height(Etio.space.l))

            Text(
                "CASE ${state.caseNumber} · ${state.procedureName}".uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = Etio.colors.textSecondary,
                textAlign = TextAlign.Center,
            )

            if (engineState is LlmEngine.EngineState.Failed) {
                Spacer(Modifier.height(Etio.space.m))
                Notice(
                    headline = "Model not loaded",
                    message = "Capture still works — what you say is kept verbatim, and the cause " +
                        "comes back as Other for you to set.",
                    actionLabel = "Try loading again",
                    onAction = viewModel::retryModel,
                )
            }

            Spacer(Modifier.height(Etio.space.gutter))

            when (state.phase) {
                CapturePhase.IDLE, CapturePhase.LISTENING -> {
                    if (!mic.granted) {
                        Notice(
                            headline = "Microphone is off",
                            message = "Etio can't hear the room, so type what happened instead.",
                            actionLabel = if (mic.asked) "Open settings" else "Allow microphone",
                            onAction = mic.request,
                        )
                    } else if (!state.micAvailable) {
                        Notice(
                            headline = "No offline recogniser",
                            message = "This phone has no offline speech pack. Add English (India) in " +
                                "Settings, or type what happened.",
                            actionLabel = "Check again",
                            onAction = viewModel::recheckMic,
                        )
                    } else if (state.phase == CapturePhase.LISTENING) {
                        RecordingPanel(
                            elapsedSec = state.elapsedSec,
                            level = state.level,
                            onStop = {
                                haptics.medium()
                                viewModel.stopListening()
                            },
                        )
                    } else {
                        Text(
                            "Say what's holding it up",
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(Etio.space.gutter))
                        Button(
                            onClick = {
                                haptics.medium()
                                viewModel.toggleListening()
                            },
                            shape = RoundedCornerShape(Etio.radius.pill),
                            colors = ButtonDefaults.buttonColors(containerColor = Etio.colors.accent),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .spotlight(SpotlightTarget.RECORD_CONTROL),
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = null)
                            Spacer(Modifier.width(Etio.space.s))
                            Text("Start recording", style = MaterialTheme.typography.titleMedium)
                        }
                    }

                    Spacer(Modifier.height(Etio.space.l))
                    if (state.transcript.isNotBlank()) {
                        TranscriptBlock(state.transcript)
                    }
                    state.error?.let {
                        Spacer(Modifier.height(Etio.space.m))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Etio.colors.delay,
                            textAlign = TextAlign.Center,
                        )
                    }

                    Spacer(Modifier.height(Etio.space.s))
                    TextButton(onClick = { showTyped = !showTyped }) {
                        Text(if (showTyped) "Hide typing" else "Type it instead")
                    }
                    if (showTyped) {
                        OutlinedTextField(
                            value = typed,
                            onValueChange = { typed = it },
                            label = { Text("What happened?") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(
                            onClick = { viewModel.classifyTyped(typed) },
                            enabled = typed.isNotBlank(),
                        ) { Text("Classify") }
                    }
                }

                CapturePhase.CLASSIFYING -> {
                    TranscriptBlock(state.transcript)
                    Spacer(Modifier.height(Etio.space.xl))
                    CircularProgressIndicator(color = Etio.colors.accent)
                    Spacer(Modifier.height(Etio.space.m))
                    Text("Reading that…", style = MaterialTheme.typography.bodyLarge)
                }

                CapturePhase.REVIEW -> {
                    state.record?.let { record ->
                        DelayReviewCard(
                            record = record,
                            onCodeChange = viewModel::editCode,
                            onDeptChange = viewModel::editDept,
                            onAvoidableChange = viewModel::editAvoidable,
                            onEstimateChange = viewModel::editEstimatedMin,
                            onNoteChange = viewModel::editNote,
                            onTranscriptCorrected = viewModel::reclassify,
                            onDiscard = viewModel::discard,
                            onNotify = { viewModel.confirm(onNotify) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            Spacer(Modifier.height(Etio.space.l))
            TextButton(onClick = onBack) { Text("Back to the list") }
        }
    }
}

/**
 * Unmistakable from three metres: the seconds are the largest thing on the sheet, the
 * bar moves with the room, and Stop is the only target.
 *
 * Opaque block, deliberately — a counter read across a theatre does not sit on glass.
 */
@Composable
private fun RecordingPanel(elapsedSec: Int, level: Float, onStop: () -> Unit) {
    Surface(
        color = Etio.colors.surface,
        shape = RoundedCornerShape(Etio.radius.card),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(Etio.space.card),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("RECORDING", style = MaterialTheme.typography.labelLarge, color = Etio.colors.delay)
            Spacer(Modifier.height(Etio.space.s))
            Text(
                "${elapsedSec}s",
                style = MaterialTheme.typography.displaySmall,
                color = Etio.colors.textPrimary,
            )
            Text(
                "OF ${DelayCaptureViewModel.MAX_RECORDING_SEC}s",
                style = MaterialTheme.typography.labelLarge,
                color = Etio.colors.textSecondary,
            )

            Spacer(Modifier.height(Etio.space.l))
            AmplitudeBar(level)
            Spacer(Modifier.height(Etio.space.gutter))

            Button(
                onClick = onStop,
                shape = RoundedCornerShape(Etio.radius.pill),
                colors = ButtonDefaults.buttonColors(containerColor = Etio.colors.delay),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .spotlight(SpotlightTarget.RECORD_CONTROL),
            ) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(Modifier.width(Etio.space.s))
                Text("Stop", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/**
 * A bar, not a waveform. It answers one question — is the microphone hearing you —
 * and it is drawn straight from the smoothed level, with no animation of its own.
 */
@Composable
private fun AmplitudeBar(level: Float) {
    val fraction = ((level + 2f) / 12f).coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Etio.colors.textSecondary.copy(alpha = 0.25f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .clip(RoundedCornerShape(5.dp))
                .background(Etio.colors.running),
        )
    }
}

@Composable
private fun TranscriptBlock(transcript: String) {
    Surface(
        color = Etio.colors.surface,
        shape = RoundedCornerShape(Etio.radius.card),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Etio.space.l)) {
            Text("HEARD", style = MaterialTheme.typography.labelLarge, color = Etio.colors.textSecondary)
            Spacer(Modifier.height(Etio.space.xs))
            Text(transcript, style = EtioMonoStyle)
        }
    }
}

/** A blocked path, said plainly, with the one thing that unblocks it. */
@Composable
private fun Notice(message: String, actionLabel: String, onAction: () -> Unit, headline: String = "") {
    // Amber, not red. None of the three things that land here stop the day being
    // logged — a missing model, a refused microphone and an absent recogniser each
    // remove one convenience and leave typing intact. Red would say the app is
    // broken, which would be the screen's only dishonest sentence.
    BlockedState(
        headline = headline.ifBlank { "Still usable" },
        line = message,
        actionLabel = actionLabel,
        onAction = onAction,
    )
}

/** What the screen needs to know about RECORD_AUDIO, and how to ask for it. */
private data class MicPermission(
    val granted: Boolean,
    /** True once we have asked in this session — a second refusal needs Settings. */
    val asked: Boolean,
    val request: () -> Unit,
)

/**
 * Re-reads the grant on every resume, so permission revoked in Settings mid-demo
 * shows up as a handled state rather than a recogniser error nobody can act on.
 */
@Composable
private fun rememberMicPermission(): MicPermission {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasMicPermission()) }
    var asked by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result ->
        granted = result
        asked = true
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = context.hasMicPermission()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return MicPermission(granted = granted, asked = asked) {
        if (asked) context.openAppSettings() else launcher.launch(Manifest.permission.RECORD_AUDIO)
    }
}

private fun Context.hasMicPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

/** Android stops showing the system prompt after a second refusal; this is the way back. */
private fun Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
