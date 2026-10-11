package com.clarity.life.data

/**
 * The explicit state machines of contracts/states.json (spec section 18, point 6).
 *
 * Every state change goes through [move], which throws on a move the contract does not allow, and on every
 * start [repairOnStart] turns a stuck state into a safe one (a reply left streaming becomes interrupted, a
 * job left running becomes pending), so no spinner can stay forever.
 *
 * StateMachineTest reads contracts/states.json and fails if this table and that file ever disagree.
 */
data class StateMachine(
    val name: String,
    val table: String,
    val column: String,
    val states: List<String>,
    val start: List<String>,
    val transitions: Map<String, List<String>>,
    val repairOnStart: Map<String, String>,
) {
    fun allows(from: String, to: String): Boolean = to in (transitions[from] ?: emptyList())
}

object StateMachines {

    val MESSAGE = StateMachine(
        name = "message",
        table = "message",
        column = "status",
        states = listOf("streaming", "complete", "interrupted", "failed"),
        start = listOf("streaming", "complete"),
        transitions = mapOf(
            "streaming" to listOf("complete", "interrupted", "failed"),
            "interrupted" to listOf("streaming"),
            "failed" to listOf("streaming"),
            "complete" to emptyList(),
        ),
        repairOnStart = mapOf("streaming" to "interrupted"),
    )

    val JOB = StateMachine(
        name = "job",
        table = "job",
        column = "status",
        states = listOf("pending", "running", "succeeded", "failed", "skipped"),
        start = listOf("pending"),
        transitions = mapOf(
            "pending" to listOf("running", "skipped"),
            "running" to listOf("succeeded", "failed", "pending"),
            "failed" to listOf("pending"),
            "succeeded" to emptyList(),
            "skipped" to emptyList(),
        ),
        repairOnStart = mapOf("running" to "pending"),
    )

    val CARD = StateMachine(
        name = "card",
        table = "card",
        column = "status",
        states = listOf("offered", "saved", "dismissed"),
        start = listOf("offered"),
        transitions = mapOf(
            "offered" to listOf("saved", "dismissed"),
            "saved" to emptyList(),
            "dismissed" to emptyList(),
        ),
        repairOnStart = emptyMap(),
    )

    val PROMISE = StateMachine(
        name = "promise",
        table = "promise",
        column = "status",
        states = listOf("open", "kept", "not_done", "moved", "let_go"),
        start = listOf("open"),
        transitions = mapOf(
            "open" to listOf("kept", "not_done", "moved", "let_go"),
            "kept" to listOf("open"),
            "not_done" to listOf("open"),
            "moved" to emptyList(),
            "let_go" to listOf("open"),
        ),
        repairOnStart = emptyMap(),
    )

    val NOTE = StateMachine(
        name = "note",
        table = "note",
        column = "status",
        states = listOf("active", "retired", "deleted_by_user"),
        start = listOf("active"),
        transitions = mapOf(
            "active" to listOf("retired", "deleted_by_user"),
            "retired" to listOf("active", "deleted_by_user"),
            // His deletions are final (rule 5): there is no way back from deleted_by_user.
            "deleted_by_user" to emptyList(),
        ),
        repairOnStart = emptyMap(),
    )

    val ALL = listOf(MESSAGE, JOB, CARD, PROMISE, NOTE)

    fun byName(name: String): StateMachine =
        ALL.firstOrNull { it.name == name } ?: throw IllegalArgumentException("no state machine called $name")

    class IllegalTransition(message: String) : IllegalStateException(message)

    /** Returns [to] when the move is allowed, and throws otherwise. Every state change calls this. */
    fun move(machine: StateMachine, from: String, to: String): String {
        if (from !in machine.states) throw IllegalTransition("${machine.name}: \"$from\" is not a state")
        if (to !in machine.states) throw IllegalTransition("${machine.name}: \"$to\" is not a state")
        if (!machine.allows(from, to)) {
            throw IllegalTransition("${machine.name}: $from cannot become $to")
        }
        return to
    }

    fun requireStart(machine: StateMachine, state: String) {
        if (state !in machine.start) {
            throw IllegalTransition("${machine.name}: a new row cannot start as $state")
        }
    }
}
