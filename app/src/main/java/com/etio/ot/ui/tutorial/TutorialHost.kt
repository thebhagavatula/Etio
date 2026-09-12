package com.etio.ot.ui.tutorial

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.etio.ot.ui.caselist.CaseListScreen
import com.etio.ot.ui.delay.CapturePhase
import com.etio.ot.ui.delay.DelayCaptureScreen
import com.etio.ot.ui.delay.DelayCaptureViewModel
import com.etio.ot.ui.theme.Etio
import com.etio.ot.ui.theme.glass
import kotlinx.coroutines.delay

/**
 * Coach marks over the running app. Not a slideshow: every step is performed for
 * real, against a sandbox theatre with one case in it, and the app underneath is the
 * same composable the demo runs.
 *
 * Skip is on every step from the first, and it makes the same promise finishing does.
 */
@Composable
fun TutorialHost(
    onFinished: () -> Unit,
    viewModel: TutorialViewModel = viewModel(factory = TutorialViewModel.Factory),
) {
    val step by viewModel.step.collectAsStateWithLifecycle()
    val sandboxCaseId by viewModel.sandboxCaseId.collectAsStateWithLifecycle()
    val registry = remember { SpotlightRegistry() }
    var capturing by remember { mutableStateOf(false) }
    var gotItVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.startSandbox() }

    // Step 3 has a way out: long-press it, or wait four seconds for the fallback.
    LaunchedEffect(step) {
        gotItVisible = false
        if (step == TutorialStep.CORRECT_TIME) {
            delay(4_000)
            gotItVisible = true
        }
    }

    CompositionLocalProvider(LocalSpotlight provides registry) {
        Box(Modifier.fillMaxSize()) {

            CaseListScreen(
                onCaptureDelay = { capturing = true },
                onRecordDelay = { capturing = true },
                onOpenMessages = { },
                onOpenReport = { },
            )

            if (capturing && sandboxCaseId != null) {
                val captureVm: DelayCaptureViewModel = viewModel(
                    factory = DelayCaptureViewModel.factory(sandboxCaseId!!),
                    key = "tutorial-capture",
                )
                val captureState by captureVm.state.collectAsStateWithLifecycle()

                // The structured card resolving from her own speech is step 4's payoff,
                // so the step only turns over once the model has actually answered.
                LaunchedEffect(captureState.phase) {
                    if (captureState.phase == CapturePhase.REVIEW) {
                        viewModel.goTo(TutorialStep.TRANSCRIPT)
                    }
                }

                DelayCaptureScreen(
                    caseId = sandboxCaseId!!,
                    onBack = { capturing = false },
                    onNotify = {
                        capturing = false
                        viewModel.goTo(TutorialStep.FINALE)
                    },
                    viewModel = captureVm,
                )
            }

            val target = when (step) {
                TutorialStep.MARK_EVENT -> SpotlightTarget.NEXT_EVENT
                TutorialStep.CORRECT_TIME -> SpotlightTarget.LAST_MARK
                TutorialStep.SPEAK_DELAY -> if (capturing) SpotlightTarget.RECORD_CONTROL else SpotlightTarget.MIC
                TutorialStep.TRANSCRIPT -> SpotlightTarget.TRANSCRIPT
                else -> null
            }

            SpotlightScrim(
                hole = target?.let { registry[it] },
                scrimColor = Etio.colors.background.copy(alpha = if (target == null) 0.94f else 0.88f),
            )

            CoachCard(
                step = step,
                gotItVisible = gotItVisible,
                onBegin = viewModel::advance,
                onSkip = { viewModel.skip(onFinished) },
                onGotIt = viewModel::advance,
                onFinish = { viewModel.finish(onFinished) },
                modifier = Modifier.align(
                    if (step == TutorialStep.MARK_EVENT || step == TutorialStep.SPEAK_DELAY) {
                        Alignment.TopCenter
                    } else {
                        Alignment.BottomCenter
                    },
                ),
            )
        }
    }
}

@Composable
private fun CoachCard(
    step: TutorialStep,
    gotItVisible: Boolean,
    onBegin: () -> Unit,
    onSkip: () -> Unit,
    onGotIt: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(Etio.space.gutter)
            .fillMaxWidth()
            .glass(RoundedCornerShape(Etio.radius.sheet))
            .padding(Etio.space.card),
    ) {
        Text(
            "STEP ${step.ordinal + 1} OF ${TutorialStep.entries.size}",
            style = MaterialTheme.typography.labelLarge,
            color = Etio.colors.accent,
        )
        Spacer(Modifier.height(Etio.space.s))
        Text(step.body, style = MaterialTheme.typography.bodyLarge)

        if (step == TutorialStep.FINALE) {
            Spacer(Modifier.height(Etio.space.m))
            Text(
                "Safety checklists are never automated: you confirm every item yourself.",
                style = MaterialTheme.typography.bodyLarge,
                // Not the safety violet, even here: "nowhere else" has to mean
                // nowhere else, or it stops meaning anything in the checklist.
                color = Etio.colors.textPrimary,
            )
        }

        Spacer(Modifier.height(Etio.space.gutter))

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onSkip) { Text("Skip") }
            Spacer(Modifier.weight(1f))
            when {
                step == TutorialStep.WELCOME -> PrimaryAction("Begin", onBegin)
                step == TutorialStep.FINALE -> PrimaryAction("Start my day", onFinish)
                step == TutorialStep.TRANSCRIPT -> PrimaryAction("Got it", onGotIt)
                gotItVisible -> PrimaryAction("Got it", onGotIt)
                else -> Text(
                    "YOUR TURN",
                    style = MaterialTheme.typography.labelLarge,
                    color = Etio.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun PrimaryAction(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(Etio.radius.pill),
        colors = ButtonDefaults.buttonColors(containerColor = Etio.colors.accent),
        modifier = Modifier.heightIn(min = 56.dp),
    ) { Text(label, style = MaterialTheme.typography.titleMedium) }
}
