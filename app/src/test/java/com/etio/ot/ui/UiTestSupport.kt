package com.etio.ot.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.core.app.ApplicationProvider
import com.etio.ot.di.ServiceLocator
import com.etio.ot.ui.theme.EtioTheme

/**
 * Shared setup for the Compose tests.
 *
 * Robolectric is told to use a plain [Application] rather than EtioApplication:
 * the real one loads a 550MB model at start-up, and none of these tests want that.
 * What they do want is [ServiceLocator] pointed at a context, because a few
 * composables read the taxonomy out of assets/config on the way to their first frame.
 */
object UiTestSupport {

    fun initServiceLocator() {
        ServiceLocator.init(ApplicationProvider.getApplicationContext<Application>())
    }
}

/** Every screen renders inside the real theme — tokens included, as shipped. */
fun ComposeContentTestRule.setEtioContent(content: @Composable () -> Unit) {
    setContent { EtioTheme(darkTheme = true) { content() } }
}
