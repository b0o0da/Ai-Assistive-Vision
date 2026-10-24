package avn.engine

/**
 * The engine's only source of "now" (architecture.md WS-16 deep dive, "Testing": deterministic
 * injected clock). Everything time-based in the engine -- cooldowns, the speech queue's age
 * ordering and expiry (AVN-56) -- takes a [Clock] instead of calling `System.currentTimeMillis()`
 * directly, so scenario tests can drive time by hand instead of racing a wall clock.
 */
interface Clock {
    fun nowMs(): Long
}

/** Real clock for production wiring. */
class SystemClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}
