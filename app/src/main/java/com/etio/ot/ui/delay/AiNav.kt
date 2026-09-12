package com.etio.ot.ui.delay

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.etio.ot.ui.Routes
import com.etio.ot.ui.messages.MessagesScreen

/**
 * OWNER: AI branch. Delay capture and the four-audience drafting screen.
 */
fun NavGraphBuilder.aiGraph(nav: NavHostController) {

    composable(Routes.DELAY) { entry ->
        val caseId = entry.arguments?.getString(Routes.ARG_CASE_ID).orEmpty()
        DelayCaptureScreen(
            caseId = caseId,
            onBack = { nav.popBackStack() },
            onNotify = { delayId ->
                nav.navigate(Routes.messages(delayId)) { popUpTo(Routes.CASES) }
            },
        )
    }

    composable(Routes.MESSAGES) { entry ->
        val delayId = entry.arguments?.getString(Routes.ARG_DELAY_ID).orEmpty()
        MessagesScreen(delayId = delayId, onBack = { nav.popBackStack() })
    }
}
