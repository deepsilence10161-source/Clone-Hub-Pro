package com.clonehub.advanced.core.container

/**
 * Result of an operation that crosses into the bundled virtual-container runtime.
 *
 * The runtime is a closed binary, so every failure must carry the step that failed and a
 * reason a user can act on. Nothing in the app may report success without one of these.
 */
sealed class EngineOutcome {
    object Success : EngineOutcome()

    /** The runtime could not be reached at all (provider missing, binder dead, extraction failed). */
    data class RuntimeUnavailable(val detail: String) : EngineOutcome()

    /** The runtime was reached but refused or failed the operation. */
    data class Rejected(val step: String, val code: Int, val detail: String? = null) : EngineOutcome()

    /** The runtime answered, but a follow-up verification did not confirm the effect. */
    data class NotVerified(val step: String, val detail: String) : EngineOutcome()

    val isSuccess: Boolean get() = this is Success

    /** Short, user-facing explanation. Never claims the operation worked. */
    fun describe(): String = when (this) {
        is Success -> "OK"
        is RuntimeUnavailable -> "Virtual engine unavailable: $detail"
        is Rejected -> "Engine rejected $step (code $code)${detail?.let { ": $it" } ?: ""}"
        is NotVerified -> "Could not verify $step: $detail"
    }
}
