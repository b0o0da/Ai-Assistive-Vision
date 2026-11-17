package avn.engine

/** Deterministic clock for tests: time only moves when [advanceBy] is called. */
class FakeClock(startMillis: Long = 0L) : Clock {
    private var millis = startMillis

    override fun nowMillis(): Long = millis

    fun advanceBy(deltaMillis: Long) {
        millis += deltaMillis
    }
}
