package ru.ilyasok.StickKs.core.feature

import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import ru.ilyasok.StickKs.core.context.EventContext
import ru.ilyasok.StickKs.core.event.queue.EventQueue
import ru.ilyasok.StickKs.dsl.Feature
import ru.ilyasok.StickKs.dsl.FeatureMeta
import ru.ilyasok.StickKs.model.FeatureStatus
import ru.ilyasok.StickKs.model.NotificationType
import ru.ilyasok.StickKs.service.FeatureErrorsService
import ru.ilyasok.StickKs.service.FeatureService
import ru.ilyasok.StickKs.service.NotificationService
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

@Component
class FeatureProcessor(
    private val featureManager: FeatureManager,
    private val featureService: FeatureService,
    private val featureErrorsService: FeatureErrorsService,
    private val notificationService: NotificationService,
    private val eventQueue: EventQueue
) {

    companion object {
        private val logger = LoggerFactory.getLogger(this::class.java)

        private const val WAITING_FOR_JOB = 15_000L

        private var processingId = 0L
    }

    private lateinit var loopJob: Job

    @PostConstruct
    fun postConstruct() = runBlocking {
        startLoop()
    }

    fun startLoop() {
        if (::loopJob.isInitialized) {
            loopJob.cancel()
        }
        loopJob = CoroutineScope(Dispatchers.Default + CoroutineName("EventQueueCoro")).launch {
            loop()
        }
    }

    private suspend fun loop() = coroutineScope {
        while (true) {
            try {
                val ec = eventQueue.dequeue()
                logger.info("Received event ${ec.hashCode()}")
                process(ec)
            } catch (_: CancellationException) {
                logger.info("EventQueue loop cancelled")
                break
            } catch (e: Throwable) {
                logger.warn("Exception in EventQueue loop: ", e)
            }
        }
    }

    suspend fun process(eventContext: EventContext) = coroutineScope {
        val features = featureManager.getFeatures()
        for (f in features) {
            launch(CoroutineName("Feature${f.id}ProcessCoro")) {
                var updatedMeta: FeatureMeta? = null
                f.process {
                    logger.info("Start process feature ${f.idName()}")
                    val meta = featureService.getMeta(f.id)
                    val feature = f.copy(meta = meta)
                    if (!feature.isEnabled() || !feature.control()) return@process
                    withTimeout(WAITING_FOR_JOB.milliseconds) {
                        try {
                            feature.takeIf { feature -> feature.checkEvent(eventContext) }
                                ?.takeIf { feature -> feature.checkCondition(eventContext) }
                                ?.let { feature ->
                                    logger.info("Start execute feature ${feature.idName()}")
                                    feature.execute(eventContext)
                                    updatedMeta = feature.meta.copy(
                                        lastSuccessExecutionAt = Instant.now(),
                                        successExecutionsAmount = feature.meta.successExecutionsAmount + 1
                                    )
                                    logger.info("Successfully process feature ${feature.idName()}")
                                }
                        } catch (e: Throwable) {
                            logger.warn("Failed to process feature ${feature.id}", e)
                            updatedMeta = feature.meta.copy(
                                status = FeatureStatus.UNSTABLE,
                                lastFailedExecutionAt = Instant.now(),
                                failedExecutionsAmount = feature.meta.failedExecutionsAmount + 1
                            )
                            featureErrorsService.updateFeatureErrors(feature.id, e.stackTraceToString())
                            notificationService.notify(feature.id, null, NotificationType.FEATURE_UNSTABLE)
                        } finally {
                            if (updatedMeta != null) {
                                logger.debug("Start updating feature {} meta after processing", feature.idName())
                                try {
                                    featureService.updateMeta(feature.id, updatedMeta!!)
                                    logger.info("After update meta")
                                } catch (e: Throwable) {
                                    logger.error(
                                        "Failed to update feature {} meta after processing",
                                        feature.idName(),
                                        e
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        logger.debug("Processing {} finished", ++processingId)
    }

    private fun <T : EventContext> Feature.checkEvent(eventContext: T): Boolean {
        val onEvent = this.feature.onEvent ?: return eventContext is ActivateFeatureEvent && eventContext.featureId == this.id
        return onEvent.event.eventContext() == eventContext::class
    }

    private suspend fun <T : EventContext> Feature.checkCondition(eventContext: T): Boolean {
        val onEvent = this.feature.onEvent ?: return this.feature.triggerBlock!!.checkCondition()
        return onEvent.event.checkCondition(eventContext)
    }

    private suspend fun <T : EventContext> Feature.execute(eventContext: T) {
        val onEvent = this.feature.onEvent ?: return this.feature.triggerBlock!!.execute()
        return onEvent.event.execute(eventContext)
    }
}