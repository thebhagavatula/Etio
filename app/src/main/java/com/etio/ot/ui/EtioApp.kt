package com.etio.ot.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.etio.ot.ui.caselist.CaseListScreen
import com.etio.ot.ui.delay.DelayCaptureScreen
import com.etio.ot.ui.messages.MessagesScreen
import com.etio.ot.ui.report.ReportScreen

object Routes {
    const val CASES = "cases"
    const val DELAY = "delay/{caseId}"
    const val MESSAGES = "messages/{delayId}"
    const val REPORT = "report"

    fun delay(caseId: String) = "delay/$caseId"
    fun messages(delayId: String) = "messages/$delayId"
}

@Composable
fun EtioApp(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.CASES) {

        composable(Routes.CASES) {
            CaseListScreen(
                onCaptureDelay = { caseId -> navController.navigate(Routes.delay(caseId)) },
                onOpenReport = { navController.navigate(Routes.REPORT) },
                onOpenMessages = { delayId -> navController.navigate(Routes.messages(delayId)) },
            )
        }

        composable(Routes.DELAY) { entry ->
            val caseId = entry.arguments?.getString("caseId").orEmpty()
            DelayCaptureScreen(
                caseId = caseId,
                onBack = { navController.popBackStack() },
                onNotify = { delayId ->
                    navController.navigate(Routes.messages(delayId)) {
                        popUpTo(Routes.CASES)
                    }
                },
            )
        }

        composable(Routes.MESSAGES) { entry ->
            val delayId = entry.arguments?.getString("delayId").orEmpty()
            MessagesScreen(delayId = delayId, onBack = { navController.popBackStack() })
        }

        composable(Routes.REPORT) {
            ReportScreen(onBack = { navController.popBackStack() })
        }
    }
}
