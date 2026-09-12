package com.etio.ot.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.etio.ot.ui.caselist.spineGraph
import com.etio.ot.ui.delay.aiGraph
import com.etio.ot.ui.report.safetyGraph

/**
 * ⛔ FROZEN FILE — do not edit on a feature branch.
 *
 * Each slice contributes its own destinations from a file it alone owns, so three
 * people can add screens in parallel and this file never changes:
 *
 *   spineGraph   → ui/caselist/SpineNav.kt    (spine branch)
 *   aiGraph      → ui/delay/AiNav.kt          (AI branch)
 *   safetyGraph  → ui/report/SafetyNav.kt     (safety branch)
 */
@Composable
fun EtioApp(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.CASES) {
        spineGraph(navController)
        aiGraph(navController)
        safetyGraph(navController)
    }
}
