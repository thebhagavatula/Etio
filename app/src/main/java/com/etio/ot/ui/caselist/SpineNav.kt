package com.etio.ot.ui.caselist

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.etio.ot.ui.Routes
import com.etio.ot.ui.debug.EvalScreen
import com.etio.ot.ui.settings.SettingsScreen

/**
 * OWNER: spine branch. Destinations owned by the case list and event marking.
 */
fun NavGraphBuilder.spineGraph(nav: NavHostController) {
    composable(Routes.CASES) {
        CaseListScreen(
            onCaptureDelay = { caseId -> nav.navigate(Routes.delay(caseId)) },
            onRecordDelay = { caseId -> nav.navigate(Routes.delay(caseId, autoStart = true)) },
            onOpenMessages = { delayId -> nav.navigate(Routes.messages(delayId)) },
            onOpenReport = { nav.navigate(Routes.REPORT) },
            onOpenSettings = { nav.navigate(Routes.SETTINGS) },
        )
    }

    composable(Routes.SETTINGS) {
        SettingsScreen(
            onBack = { nav.popBackStack() },
            onOpenEval = { nav.navigate(Routes.EVAL) },
        )
    }

    composable(Routes.EVAL) {
        EvalScreen(onBack = { nav.popBackStack() })
    }
}
