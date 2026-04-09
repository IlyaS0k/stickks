package ru.ilyasok.StickKs.core.feature

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import ru.ilyasok.StickKs.dsl.Feature
import ru.ilyasok.StickKs.model.AvailabilityStatus
import ru.ilyasok.StickKs.model.NotificationType
import ru.ilyasok.StickKs.service.FeatureService
import ru.ilyasok.StickKs.service.NotificationService
import java.util.UUID


@Component
class FeatureManager(
    internal val featureService: FeatureService,
    internal val featureUpdatesQueue: FeatureUpdatesQueue,
    internal val notificationService: NotificationService,
    internal val activatedFeaturesManager: ActivatedFeaturesManager
) {
    internal val status = MutableStateFlow(AvailabilityStatus.DISABLED)
    internal var fetchUpdatesJob: Job? = null
    internal val fetchJobMutex = Mutex()
    internal val features: MutableList<Feature> = mutableListOf()
    internal val featuresMutex = Mutex()

    @Autowired
    @Lazy
    private lateinit var strategyProvider: FetchUpdatesStrategyProvider

    companion object {
        private val log = LoggerFactory.getLogger(this::class.java)
    }

    fun disable() {
        if (status.value == AvailabilityStatus.DISABLED) return
        status.value = AvailabilityStatus.DISABLED
        runBlocking {
            fetchJobMutex.withLock {
                withContext(Dispatchers.Default) {
                    fetchUpdatesJob?.cancel()
                }
            }
            featuresMutex.withLock { features.clear() }
        }
    }

    fun enable(refresh: Boolean = false) {
        if (status.value == AvailabilityStatus.ENABLED && !refresh) return
        CoroutineScope(Dispatchers.Default).launch {
            featuresMutex.withLock {
                features.clear()
                featureService.getAllCompiled().collect {
                   try {
                       applyNewUnprotected(it)
                       notificationService.notify(it.id, null, NotificationType.FEATURE_LOADED)
                   } catch (e: Throwable) {
                       log.error("Failed to load feature", e)
                   }
                }
            }
            fetchJobMutex.withLock {
                if (fetchUpdatesJob == null || fetchUpdatesJob?.isActive == false) {
                    fetchUpdatesJob = launch(CoroutineName("FetchUpdatesCoro")) {
                        strategyProvider.provide().loop()
                    }
                }
            }
        }
        status.value = AvailabilityStatus.ENABLED
    }

    suspend fun apply(feature: Feature) = featuresMutex.withLock {
        val index = features.indexOfFirst { it.id == feature.id }
        if (index != -1) {
            tryToApplyIfActivated(feature)
            features[index] = feature
        } else {
            tryToApplyIfActivated(feature)
            features.add(feature)
        }
    }

    suspend fun applyNew(feature: Feature) = featuresMutex.withLock {
        applyNewUnprotected(feature)
    }

    suspend fun applyNewUnprotected(feature: Feature) {
        tryToApplyIfActivated(feature)
        features.add(feature)
    }

    suspend fun tryToApplyIfActivated(feature: Feature) {
        val applyResult = if (feature.isActivated())
            activatedFeaturesManager.apply(feature)
        else
            ActivatedFeaturesManager.ApplyActivatedFeatureResult(true)
        if (!applyResult.success) {
            throw RuntimeException("Failed to apply feature on start: $feature", applyResult.error)
        }
    }

    suspend fun delete(featureId: UUID) = featuresMutex.withLock {
        activatedFeaturesManager.cancel(featureId)
        features.removeAll { it.id == featureId }
    }

    suspend fun getFeatures(): List<Feature> {
        waitWhileDisabled()
        featuresMutex.withLock {
            return features
        }
    }

    private suspend fun waitWhileDisabled() {
        status.first { it != AvailabilityStatus.DISABLED }
    }
}