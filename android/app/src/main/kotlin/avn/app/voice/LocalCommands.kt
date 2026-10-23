package avn.app.voice

/**
 * Voice commands that have no [avn.contracts.PlanStep] yet. They are handled on the phone and never leave it.
 * Promote one to a plan step only through a Technical Decision, because the step list is part of the contract.
 */
enum class LocalCommand {
    STOP,
    CANCEL,
    REPEAT,
    LOUDER,
    QUIETER,
    FASTER,
    SLOWER,
    STOP_NAVIGATION,
    READ_TEXT,
    ENROLL_PERSON
}

interface LocalCommandHandler {
    fun handle(command: LocalCommand, arg: String?)
}

class FakeLocalCommandHandler : LocalCommandHandler {
    private val received = mutableListOf<Pair<LocalCommand, String?>>()

    val commands: List<Pair<LocalCommand, String?>> get() = received.toList()

    override fun handle(command: LocalCommand, arg: String?) {
        received.add(command to arg)
    }
}
