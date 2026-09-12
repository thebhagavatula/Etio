package com.etio.ot.ui.delay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.etio.ot.ai.LlmEngine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DelayCaptureScreen(
    caseId: String,
    onBack: () -> Unit,
    onNotify: (String) -> Unit,
    viewModel: DelayCaptureViewModel = viewModel(factory = DelayCaptureViewModel.factory(caseId)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle(LlmEngine.EngineState.NotLoaded)
    var typed by remember { mutableStateOf("") }
    var showTyped by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val mic = rememberMicPermission()
    val micUsable = mic.granted && state.micAvailable

    // Nothing to hold the mic for — put the keyboard fallback up straight away.
    LaunchedEffect(micUsable) { if (!micUsable) showTyped = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Case ${state.caseNumber}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            state.procedureName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {

            // Engine banner — visible during the build, honest on stage.
            when (val e = engineState) {
                is LlmEngine.EngineState.Ready ->
                    Banner("Model warm · ${e.backend} · ${e.loadMs} ms load", MaterialTheme.colorScheme.primary)
                is LlmEngine.EngineState.Loading ->
                    Banner(e.message, MaterialTheme.colorScheme.secondary)
                is LlmEngine.EngineState.Failed ->
                    Banner(
                        "Model unavailable — capture still works, classification will be Other",
                        MaterialTheme.colorScheme.error,
                        actionLabel = "Try loading again",
                        onAction = viewModel::retryModel,
                    )
                LlmEngine.EngineState.NotLoaded -> Unit
            }

            Spacer(Modifier.padding(top = 12.dp))

            when (state.phase) {
                CapturePhase.IDLE, CapturePhase.LISTENING -> {
                    if (!mic.granted) {
                        NoticeCard(
                            message = "Microphone access is off, so Etio can't hear the room. " +
                                "You can still type what happened.",
                            actionLabel = if (mic.asked) "Open settings" else "Allow microphone",
                            onAction = mic.request,
                        )
                    } else if (!state.micAvailable) {
                        NoticeCard(
                            message = "No offline speech recogniser on this phone. Install one in " +
                                "Settings, or type what happened.",
                            actionLabel = "Check again",
                            onAction = viewModel::recheckMic,
                        )
                    } else {
                        Text(
                            text = when (state.phase) {
                                CapturePhase.LISTENING -> "Listening…"
                                else -> "Tap and say what's holding it up"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.padding(top = 24.dp))
                        TalkButton(
                            listening = state.phase == CapturePhase.LISTENING,
                            level = state.level,
                            onToggle = {
                                // Fires on both edges — the toggle is the only entry point.
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.toggleListening()
                            },
                        )
                        if (state.phase == CapturePhase.LISTENING) {
                            Spacer(Modifier.padding(top = 16.dp))
                            RecordingReadout(elapsedSec = state.elapsedSec, level = state.level)
                        }
                    }
                    Spacer(Modifier.padding(top = 20.dp))
                    if (state.transcript.isNotBlank()) {
                        TranscriptBlock(state.transcript)
                    }
                    state.error?.let {
                        Spacer(Modifier.padding(top = 12.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.padding(top = 12.dp))
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
                    // Transcript first, classification streams in behind it — perceived
                    // latency is what matters (PRD §10).
                    TranscriptBlock(state.transcript)
                    Spacer(Modifier.padding(top = 28.dp))
                    CircularProgressIndicator()
                    Spacer(Modifier.padding(top = 12.dp))
                    Text("Reading that…", style = MaterialTheme.typography.bodyMedium)
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

            Spacer(Modifier.padding(bottom = 32.dp))
        }
    }
}

@Composable
private fun Banner(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(actionLabel, style = MaterialTheme.typography.labelSmall, color = color)
                }
            }
        }
    }
}

/**
 * A blocked path, said plainly, with the one thing that unblocks it. Same tinted
 * surface as [Banner] — this is not a separate error design, just a louder instance.
 */
@Composable
private fun NoticeCard(message: String, actionLabel: String, onAction: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.padding(top = 6.dp))
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
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

@Composable
private fun TranscriptBlock(transcript: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "Heard",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(top = 4.dp))
            Text("“$transcript”", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * Tap to start, tap to stop. Toggle trades away the certainty of a held finger, so
 * the open mic has to be unmistakable: the button turns red and swaps to a stop
 * glyph, and [RecordingReadout] runs a counter and a live level underneath.
 */
@Composable
private fun TalkButton(
    listening: Boolean,
    level: Float,
    onToggle: () -> Unit,
) {
    // RMS dB from SpeechRecognizer runs roughly -2..10; map to a gentle pulse.
    val pulse = if (listening) 1f + (level.coerceIn(0f, 10f) / 40f) else 1f

    Surface(
        color = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        shape = CircleShape,
        modifier = Modifier
            .size(148.dp)
            .scale(pulse)
            .clickable(onClick = onToggle),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Icon(
                    if (listening) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = if (listening) "Tap to stop" else "Tap to speak",
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(0.dp))
            }
        }
    }
}

/**
 * The answer to "is it still listening?". Seconds elapsed against the hard cap, and
 * a bar that moves with the room — a still bar means the mic is hearing nothing.
 */
@Composable
private fun RecordingReadout(elapsedSec: Int, level: Float) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "● Recording ${elapsedSec.asClock()} / ${DelayCaptureViewModel.MAX_RECORDING_SEC.asClock()}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.padding(top = 8.dp))
        LinearProgressIndicator(
            // SpeechRecognizer reports RMS roughly -2..10 dB.
            progress = { ((level + 2f) / 12f).coerceIn(0f, 1f) },
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth(0.6f),
        )
        Spacer(Modifier.padding(top = 4.dp))
        Text(
            "mic level",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun Int.asClock(): String = "%d:%02d".format(this / 60, this % 60)
