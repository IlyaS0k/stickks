package ru.ilyasok.StickKs.service

import kotlinx.coroutines.delay
import org.springframework.dao.OptimisticLockingFailureException

suspend fun <T> optimisticTry(maxAttempts: Long = Long.MAX_VALUE, block: suspend () -> T): T {
    var attempts = 0L
    var currentDelay = 100L
    while (attempts < maxAttempts) {
        try {
            return block()
        } catch (_: OptimisticLockingFailureException) {
            delay(currentDelay)
            currentDelay += 100L
            attempts++
        }
    }
    throw RuntimeException("Max optimistic retry attempts exceeded")
}