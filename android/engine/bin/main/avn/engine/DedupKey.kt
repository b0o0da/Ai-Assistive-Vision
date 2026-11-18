package avn.engine

import avn.contracts.Event

/** Architecture.md section 5: the cooldown and duplicate key is type + label + side + band. */
object DedupKey {
    private const val NONE = "-"

    fun of(event: Event): String = listOf(
        event.type.name,
        event.label.trim(),
        event.side?.name ?: NONE,
        event.distanceBand?.name ?: NONE
    ).joinToString("|").lowercase()
}
