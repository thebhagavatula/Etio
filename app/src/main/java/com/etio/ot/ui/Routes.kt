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
    const val DELAY = "delay/{caseId}?autostart={autostart}"
    const val MESSAGES = "messages/{delayId}"
    const val REPORT = "report"

    const val ARG_CASE_ID = "caseId"
    const val ARG_DELAY_ID = "delayId"

    /**
     * [autoStart] is set only by the threshold-breach banner, whose tap IS the
     * explicit start of recording. Ordinary navigation leaves it false.
     */
    const val ARG_AUTOSTART = "autostart"

    fun delay(caseId: String, autoStart: Boolean = false): String =
        "delay/$caseId?autostart=$autoStart"

    fun messages(delayId: String): String = "messages/$delayId"
}
