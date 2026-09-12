package com.etio.ot.di

import android.content.Context
import com.etio.ot.core.Clock

/**
 * ⛔ FROZEN FILE — do not edit on a feature branch.
 *
 * This holds only what all three slices share: the application context and the
 * clock. Everything else lives in a per-slice module so two branches never touch
 * the same DI file:
 *
 *   CoreModule    → owned by the SPINE branch   (config, database, cases)
 *   AiModule      → owned by the AI branch      (LLM, ASR, delays, drafting)
 *   SafetyModule  → owned by the SAFETY branch  (checklist, report)
 *
 * Dependencies point one way: Ai and Safety may read Core. Core reads neither.
 * If you think you need to change this file, that is a conversation with the
 * whole team, not a commit.
 */
object ServiceLocator {

    private lateinit var context: Context

    val appContext: Context get() = context

    val clock: Clock = Clock.System

    fun init(context: Context) {
        this.context = context.applicationContext
    }
}
