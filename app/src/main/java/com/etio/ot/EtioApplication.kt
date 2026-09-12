package com.etio.ot

import android.app.Application
import android.util.Log
import com.etio.ot.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EtioApplication : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)

        // Config must be on disk before anything reads a prompt.
        ServiceLocator.config.installIfNeeded()

        scope.launch {
            ServiceLocator.caseRepository.seedIfEmpty()

            // PRD §10: load the model once, at app start, and hold it. Never on the
            // inference path of the first delay capture — that would put a multi-second
            // load in the middle of the demo.
            ServiceLocator.llmEngine.warmUp()
                .onSuccess { Log.i(TAG, "LLM warm") }
                .onFailure { Log.e(TAG, "LLM warm-up failed — app still fully usable", it) }
        }
    }

    override fun onTerminate() {
        ServiceLocator.llmEngine.close()
        super.onTerminate()
    }

    private companion object { const val TAG = "EtioApplication" }
}
