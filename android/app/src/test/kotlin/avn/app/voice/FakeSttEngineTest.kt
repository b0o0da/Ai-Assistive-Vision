package avn.app.voice

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeSttEngineTest {
    private val stt = FakeSttEngine(silentVoiceLog)

    @Test
    fun aScriptedUtteranceBecomesTheTranscript() = runBlocking {
        stt.enqueue(SttScript.Say("open whatsapp", confidence = 0.9, language = "en"))
        val transcript = stt.listen(1_000)
        assertEquals("open whatsapp", transcript?.text)
        assertEquals(0.9, transcript?.confidence ?: -1.0, 0.0)
        assertEquals("en", transcript?.language)
        assertEquals(SttFailure.NONE, stt.lastFailure)
    }

    @Test
    fun partialsStreamBeforeTheFinalTranscript() = runBlocking {
        val order = mutableListOf<String>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { stt.partials.collect { order.add("partial:$it") } }
        stt.enqueue(SttScript.Say("open whatsapp", partials = listOf("open", "open what"), delayMs = 60))
        val transcript = stt.listen(2_000)
        order.add("final:${transcript?.text}")
        job.cancel()
        assertEquals(listOf("partial:open", "partial:open what", "final:open whatsapp"), order)
    }

    @Test
    fun theScriptIsConsumedOneStepPerListen() = runBlocking {
        stt.enqueue(SttScript.Say("one"), SttScript.Say("two"))
        assertEquals("one", stt.listen(1_000)?.text)
        assertEquals(1, stt.pending)
        assertEquals("two", stt.listen(1_000)?.text)
        assertEquals(0, stt.pending)
        assertEquals(2, stt.listenCount)
    }

    @Test
    fun injectedTextsQueueInsteadOfOverwritingEachOther() = runBlocking {
        stt.inject("first")
        stt.inject("second")
        assertEquals("first", stt.listen(1_000)?.text)
        assertEquals("second", stt.listen(1_000)?.text)
    }

    @Test
    fun aListenThatIsWaitingReceivesATextInjectedLater() = runBlocking {
        val pending = async { stt.listen(5_000) }
        yield()
        stt.inject("scroll down")
        assertEquals("scroll down", pending.await()?.text)
    }

    @Test
    fun withNothingScriptedTheCallEndsAsSilence() = runBlocking {
        assertNull(stt.listen(50))
        assertEquals(SttFailure.SILENCE, stt.lastFailure)
    }

    @Test
    fun silenceStepReturnsNullWithSilence() = runBlocking {
        stt.enqueue(SttScript.Silence)
        assertNull(stt.listen(1_000))
        assertEquals(SttFailure.SILENCE, stt.lastFailure)
    }

    @Test
    fun errorModeReportsEachFailure() = runBlocking {
        for (failure in listOf(SttFailure.NETWORK, SttFailure.BUSY, SttFailure.NO_PERMISSION, SttFailure.UNAVAILABLE)) {
            stt.enqueue(SttScript.Fail(failure))
            assertNull(stt.listen(1_000))
            assertEquals(failure, stt.lastFailure)
        }
    }

    @Test
    fun aFailureDoesNotDiscardTheRestOfTheScript() = runBlocking {
        stt.enqueue(SttScript.Fail(SttFailure.AUDIO), SttScript.Say("try again"))
        assertNull(stt.listen(1_000))
        assertEquals("try again", stt.listen(1_000)?.text)
        assertEquals(SttFailure.NONE, stt.lastFailure)
    }

    @Test
    fun hangEndsWithTheCallersTimeout() = runBlocking {
        stt.enqueue(SttScript.Hang, SttScript.Say("after"))
        assertNull(stt.listen(50))
        assertEquals(SttFailure.SILENCE, stt.lastFailure)
        assertEquals("after", stt.listen(1_000)?.text)
    }

    @Test
    fun aSlowUtteranceIsCutAtTheTimeout() = runBlocking {
        stt.enqueue(SttScript.Say("too late", delayMs = 2_000))
        assertNull(stt.listen(50))
        assertEquals(SttFailure.SILENCE, stt.lastFailure)
    }

    @Test
    fun clearScriptDropsPendingSteps() = runBlocking {
        stt.enqueue(SttScript.Say("stale"))
        stt.clearScript()
        assertEquals(0, stt.pending)
        assertNull(stt.listen(50))
    }

    @Test
    fun theDemoScriptRunsEndToEnd() = runBlocking {
        val commands = listOf("what's around me", "open whatsapp and read my new messages", "scroll down", "SOS")
        stt.enqueue(*commands.map { SttScript.Say(it) }.toTypedArray())
        val heard = commands.map { stt.listen(1_000)?.text }
        assertEquals(commands, heard)
        assertTrue(stt.pending == 0)
    }
}
