package com.etio.ot.ui.caselist

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.etio.ot.ui.Routes

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
        )
    }
}
