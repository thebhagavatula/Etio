package com.etio.ot.core

/**
 * Deliberately not kotlin.Result: we want a Loading state and an error that carries
 * a user-facing message, because the UI must never surface a raw exception.
 */
sealed interface Outcome<out T> {
    data object Idle : Outcome<Nothing>
    data class Loading(val stage: String = "") : Outcome<Nothing>
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val userMessage: String, val cause: Throwable? = null) : Outcome<Nothing>

    val valueOrNull: T? get() = (this as? Success)?.value
    val isBusy: Boolean get() = this is Loading
}
