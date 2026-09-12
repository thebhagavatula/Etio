package com.etio.ot

import android.app.Application
import android.util.Log
import com.etio.ot.di.AiModule
import com.etio.ot.di.CoreModule
import com.etio.ot.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ⛔ FROZEN FILE — do not edit on a feature branch.
 *
 * Startup order matters and is the same for everyone: context, then config on disk,
 * then seed, then the model warm-up in the background. If your slice needs
 * something initialised at launch, do it lazily in your own DI module instead.
 */
class EtioApplication : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)

        // Config must be on disk before anything reads a prompt.
        CoreModule.config.installIfNeeded()

        scope.launch {
            CoreModule.caseRepository.seedIfEmpty()

            // PRD §10: load the model once, at app start, and hold it. Never on the
            // inference path of the first delay capture — that would put a multi-second
            // load in the middle of the demo.
            AiModule.llmEngine.warmUp()
                .onSuccess { Log.i(TAG, "LLM warm") }
                .onFailure { Log.e(TAG, "LLM warm-up failed — app still fully usable", it) }
        }
    }

    override fun onTerminate() {
        AiModule.llmEngine.close()
        super.onTerminate()
    }

    private companion object { const val TAG = "EtioApplication" }
}
