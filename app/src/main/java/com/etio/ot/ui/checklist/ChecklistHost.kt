package com.etio.ot.ui.checklist

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * OWNER: safety branch.
 *
 * The spine branch's screen contains exactly one line referring to the checklist:
 *
 *     ChecklistHost(viewModel.checklistGate, snackbar)
 *
 * Everything the dialog does, when it appears, and what it blocks is decided here.
 */
@Composable
fun ChecklistHost(
    controller: ChecklistGateController,
    snackbar: SnackbarHostState,
) {
    val prompt by controller.prompt.collectAsStateWithLifecycle()
    val message by controller.message.collectAsStateWithLifecycle()

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            controller.consumeMessage()
        }
    }

    prompt?.let { p ->
        ChecklistDialog(
            phase = p.phase,
            items = p.items,
            run = p.run,
            onToggle = controller::toggle,
            onComplete = controller::complete,
            onSkip = controller::skip,
            onDismiss = controller::dismiss,
        )
    }
}
