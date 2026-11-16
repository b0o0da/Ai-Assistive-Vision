package avn.engine

/**
 * The engine's only source of time. Everything in `:engine` that needs "now" takes a [Clock]
 * instead of calling `System.currentTimeMillis()` directly, so engine logic (including
 * [InterruptRules]) can be driven by a deterministic fake clock in tests instead of real wall
 * time (architecture.md section 5, "Testing: deterministic injected clock").
 */
interface Clock {
    fun nowMillis(): Long
}

/** Real wall-clock time, for production wiring. */
object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
