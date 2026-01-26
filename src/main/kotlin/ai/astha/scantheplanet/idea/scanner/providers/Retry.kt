package ai.astha.scantheplanet.idea.scanner.providers

import java.time.Duration
import kotlin.math.min

class RetryConfig(
    val maxRetries: Int = 3,
    val baseDelayMs: Long = 1000,
    val maxDelayMs: Long = 32000,
    val jitterMs: Long = 500
) {
    companion object {
        fun fromEnv(prefix: String, config: ai.astha.scantheplanet.idea.scanner.ScannerConfig): RetryConfig {
            val maxRetries = config.retryMaxRetries ?: System.getenv("RETRY_MAX_RETRIES")?.toIntOrNull() ?: 3
            val baseDelay = config.retryDelayMs ?: System.getenv("RETRY_DELAY_MS")?.toLongOrNull() ?: 1000
            return RetryConfig(maxRetries = maxRetries, baseDelayMs = baseDelay)
        }
    }

    fun backoffDelay(attempt: Int): Duration {
        val exp = baseDelayMs * (1L shl (attempt - 1))
        val capped = min(exp, maxDelayMs)
        val jitter = if (jitterMs > 0) (0..jitterMs.toInt()).random().toLong() else 0L
        return Duration.ofMillis(capped + jitter)
    }
}

inline fun <T> withRetry(config: RetryConfig, action: () -> T): T {
    return withRetry(config, null, action)
}

inline fun <T> withRetry(config: RetryConfig, noinline onRetry: ((attempt: Int, error: RuntimeException) -> Unit)?, action: () -> T): T {
    var attempt = 1
    var lastError: RuntimeException? = null
    while (attempt <= config.maxRetries + 1) {
        try {
            return action()
        } catch (e: RuntimeException) {
            lastError = e
            if (attempt > config.maxRetries) break
            onRetry?.invoke(attempt, e)
            Thread.sleep(config.backoffDelay(attempt).toMillis())
        }
        attempt += 1
    }
    throw lastError ?: RuntimeException("call failed")
}
