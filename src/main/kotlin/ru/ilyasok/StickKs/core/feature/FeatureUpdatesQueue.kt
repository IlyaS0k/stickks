package ru.ilyasok.StickKs.core.feature

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import ru.ilyasok.StickKs.model.AvailabilityStatus
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

@Component
class FeatureUpdatesQueue {
    companion object {
        private val logger = LoggerFactory.getLogger(this::class.java)
    }

    private val updates: Channel<FeatureUpdateInfo> = Channel(Channel.UNLIMITED)

    private val status = MutableStateFlow(AvailabilityStatus.DISABLED)

    suspend fun add(id: UUID, reqId: UUID?, updateType: FeatureUpdateType) {
        if (status.value == AvailabilityStatus.ENABLED) {
            val fui = FeatureUpdateInfo(id, reqId, updateType)
            updates.send(fui)
            logger.info("Added new update: $fui")
        } else {
            logger.info("Added new update: id = $id, reqId = $reqId, updateType = $updateType, but queue is disabled")
        }
    }

    fun get(): Flow<FeatureUpdateInfo> {
        return updates.receiveAsFlow()
    }

    suspend fun disableAfterTimeout(timeout: Duration) = coroutineScope {
        async(CoroutineName("DisableUpdatesQueueAfterTimeoutCoro")) {
            try {
                logger.info("Disabling updates after timeout $timeout")
                withTimeout(timeout) {
                    status.first { it != AvailabilityStatus.DISABLED }
                }
            } catch (_: TimeoutCancellationException) {
                status.value = AvailabilityStatus.DISABLED
                logger.debug("Disabled updates queue job timeout exceeded: updates queue is disabled")
            } catch (_: CancellationException) {
                logger.debug("Cancelled disable updates queue job: updates queue is enabled")
            }
        }
    }

    suspend fun enable() {
        status.value = AvailabilityStatus.ENABLED
    }

}

data class FeatureUpdateInfo(
    val id: UUID,
    val reqId: UUID?,
    val updateType: FeatureUpdateType
)

enum class FeatureUpdateType {
    CREATED,
    DELETED,
    CODE_UPDATED
}