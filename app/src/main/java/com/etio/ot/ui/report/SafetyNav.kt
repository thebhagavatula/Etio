package com.etio.ot.ui.report

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.etio.ot.ui.Routes

/**
 * OWNER: safety branch. The end-of-day report. The WHO checklist is not a route —
 * it is a dialog hosted over the case list by ChecklistHost.
 *
 * The signature is dictated by EtioApp.kt, which is frozen: `safetyGraph(navController)`.
 * Do not change it here; a new destination gets added to Routes on main first.
 */
fun NavGraphBuilder.safetyGraph(nav: NavHostController) {
    composable(Routes.REPORT) {
        ReportScreen(onBack = { nav.leaveReport() })
    }
}

/**
 * The report is the one screen that can legitimately be the whole back stack: the
 * projector deep-links into it, and a process death during a long theatre day can
 * restore straight onto it. A bare `popBackStack()` in that situation pops the only
 * entry and leaves an empty NavHost — a blank screen with no way back, in front of
 * the room. Fall through to the case list instead.
 */
private fun NavHostController.leaveReport() {
    if (previousBackStackEntry != null) {
        popBackStack()
    } else {
        navigate(Routes.CASES) {
            popUpTo(Routes.REPORT) { inclusive = true }
            launchSingleTop = true
        }
    }
}
