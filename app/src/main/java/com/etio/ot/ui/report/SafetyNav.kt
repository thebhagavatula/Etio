package com.etio.ot.ui.report

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.etio.ot.ui.Routes

/**
 * OWNER: safety branch. The end-of-day report. The WHO checklist is not a route —
 * it is a dialog hosted over the case list by ChecklistHost.
 */
fun NavGraphBuilder.safetyGraph(nav: NavHostController) {
    composable(Routes.REPORT) {
        ReportScreen(onBack = { nav.popBackStack() })
    }
}
