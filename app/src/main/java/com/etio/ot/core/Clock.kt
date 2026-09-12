package com.etio.ot.core

import java.util.UUID

/** Injectable clock so timer logic is unit-testable without waiting for real time. */
fun interface Clock {
    fun nowMs(): Long

    companion object {
        val System = Clock { java.lang.System.currentTimeMillis() }
    }
}

fun newId(): String = UUID.randomUUID().toString()

/** Minutes, rounded to nearest, never negative. */
fun Long.msToMinutes(): Int = ((this.coerceAtLeast(0L) + 30_000L) / 60_000L).toInt()

fun Long.formatMmSs(): String {
    val total = this.coerceAtLeast(0L) / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

fun Int.formatSignedMinutes(): String = if (this >= 0) "+${this}m" else "${this}m"
