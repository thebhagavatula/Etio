package com.etio.ot.ui.delay

import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
                    Banner("Model unavailable — capture still works, classification will be Other", MaterialTheme.colorScheme.error)
                LlmEngine.EngineState.NotLoaded -> Unit
            }

            Spacer(Modifier.padding(top = 12.dp))

            when (state.phase) {
                CapturePhase.IDLE, CapturePhase.LISTENING -> {
                    Text(
                        text = when (state.phase) {
                            CapturePhase.LISTENING -> "Listening…"
                            else -> "Hold and say what's holding it up"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.padding(top = 24.dp))
                    PushToTalkButton(
                        listening = state.phase == CapturePhase.LISTENING,
                        level = state.level,
                        onPress = viewModel::startListening,
                        onRelease = viewModel::stopListening,
                    )
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
private fun Banner(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
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
 * Hold to speak. Press-and-hold, not tap-toggle: there is no ambiguity about
 * whether the mic is open, which matters when you are walking and talking.
 */
@Composable
private fun PushToTalkButton(
    listening: Boolean,
    level: Float,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    // RMS dB from SpeechRecognizer runs roughly -2..10; map to a gentle pulse.
    val pulse = if (listening) 1f + (level.coerceIn(0f, 10f) / 40f) else 1f

    Surface(
        color = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        shape = CircleShape,
        modifier = Modifier
            .size(148.dp)
            .scale(pulse)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPress()
                        tryAwaitRelease()
                        onRelease()
                    },
                )
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = "Hold to speak",
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(0.dp))
            }
        }
    }
}
