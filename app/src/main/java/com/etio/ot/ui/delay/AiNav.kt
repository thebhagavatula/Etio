package com.etio.ot.ui.delay

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.etio.ot.ui.Routes
import com.etio.ot.ui.messages.MessagesScreen

/**
 * OWNER: AI branch. Delay capture and the four-audience drafting screen.
 */
fun NavGraphBuilder.aiGraph(nav: NavHostController) {

    composable(
        route = Routes.DELAY,
        arguments = listOf(
            navArgument(Routes.ARG_AUTOSTART) {
                type = NavType.BoolType
                defaultValue = false
            },
        ),
    ) { entry ->
        val caseId = entry.arguments?.getString(Routes.ARG_CASE_ID).orEmpty()
        DelayCaptureScreen(
            caseId = caseId,
            autoStart = entry.arguments?.getBoolean(Routes.ARG_AUTOSTART) == true,
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
