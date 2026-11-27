package avn.app

import avn.app.core.service.ModuleRegistry
import avn.app.ui.UiDeps
import avn.app.voice.FakeSpeaker
import avn.contracts.Level
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class HiltGraphTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject lateinit var registry: ModuleRegistry

    @Inject lateinit var ui: UiDeps

    @Inject lateinit var speaker: FakeSpeaker

    @Test
    fun fakeGraphBuildsAndTheUiSpeaksOnlyThroughTheEngine() {
        hilt.inject()
        assertEquals(21, registry.modules.size)
        // The bound SpeechOutput is the same singleton the Debug screen shows.
        assertSame(speaker, registry.speechOutput)

        ui.say("hello")
        val spoken = speaker.spoken.value.single()
        assertEquals("hello", spoken.text)
        assertEquals(Level.INFO, spoken.level)
    }
}
