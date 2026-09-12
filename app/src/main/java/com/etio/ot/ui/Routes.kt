package com.etio.ot.ui

/**
 * ⛔ FROZEN FILE — the navigation contract between the three slices.
 *
 * Every route and argument name is declared here up front, so the spine branch can
 * navigate to a screen the AI branch has not written yet, and vice versa. Nobody
 * hardcodes a route string anywhere else.
 *
 * Adding a route is a team decision made on main before branching, not a commit on
 * a feature branch — a route added in two branches at once is exactly the merge you
 * do not want at 02:00.
 */
object Routes {

    const val CASES = "cases"
    const val DELAY = "delay/{caseId}"
    const val MESSAGES = "messages/{delayId}"
    const val REPORT = "report"

    const val ARG_CASE_ID = "caseId"
    const val ARG_DELAY_ID = "delayId"

    fun delay(caseId: String): String = "delay/$caseId"

    fun messages(delayId: String): String = "messages/$delayId"
}
