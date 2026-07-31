package com.kunk.singbox.utils.perf

internal data class ResourceGuardRegistration(
    val ownerId: Any,
    val generation: Long
)

internal enum class ResourceRecoveryPhase {
    RESETTING,
    AWAITING_SUCCESSOR,
    OBSERVING_SUCCESSOR,
    RECLAIM_CLAIMED
}

internal data class ResourceRecoveryRegisterResult(
    val unchanged: Boolean = false,
    val rejected: Boolean = false,
    val cancelledAttemptId: Long? = null,
    val successorAttemptId: Long? = null
)

internal data class ResourceRecoveryDetachResult(
    val detached: Boolean,
    val cancelledAttemptId: Long? = null,
    val preservedAttemptId: Long? = null
)

internal data class ResourceRecoveryCancelResult(
    val registrationCancelled: Boolean,
    val cancelledAttemptId: Long?
)

/**
 * 纯状态门。所有方法由 BackgroundResourceGuard 的同一把锁保护。
 */
internal class ResourceRecoveryGate {
    private data class Attempt(
        val id: Long,
        val ownerId: Any,
        val sourceGeneration: Long,
        val phase: ResourceRecoveryPhase
    )

    private var registration: ResourceGuardRegistration? = null
    private var attempt: Attempt? = null
    private var nextAttemptId = 0L

    @Suppress("ReturnCount")
    fun register(candidate: ResourceGuardRegistration): ResourceRecoveryRegisterResult {
        val currentRegistration = registration
        if (currentRegistration.matches(candidate)) {
            return ResourceRecoveryRegisterResult(unchanged = true)
        }
        if (currentRegistration != null &&
            currentRegistration.ownerId === candidate.ownerId &&
            candidate.generation < currentRegistration.generation
        ) {
            return ResourceRecoveryRegisterResult(rejected = true)
        }

        val currentAttempt = attempt
        if (currentAttempt?.isAwaitingSuccessorFor(candidate) == true && registration == null) {
            if (candidate.generation <= currentAttempt.sourceGeneration) {
                return ResourceRecoveryRegisterResult(rejected = true)
            }
            registration = candidate
            attempt = currentAttempt.copy(phase = ResourceRecoveryPhase.OBSERVING_SUCCESSOR)
            return ResourceRecoveryRegisterResult(successorAttemptId = currentAttempt.id)
        }

        registration = candidate
        attempt = null
        return ResourceRecoveryRegisterResult(cancelledAttemptId = currentAttempt?.id)
    }

    fun beginRecovery(source: ResourceGuardRegistration): Long? {
        if (!registration.matches(source) || attempt != null) return null
        val attemptId = ++nextAttemptId
        attempt = Attempt(
            id = attemptId,
            ownerId = source.ownerId,
            sourceGeneration = source.generation,
            phase = ResourceRecoveryPhase.RESETTING
        )
        return attemptId
    }

    fun awaitSuccessor(source: ResourceGuardRegistration, attemptId: Long): Boolean {
        val current = attempt ?: return false
        if (!registration.matches(source) || !current.matches(source, attemptId, ResourceRecoveryPhase.RESETTING)) {
            return false
        }
        attempt = current.copy(phase = ResourceRecoveryPhase.AWAITING_SUCCESSOR)
        return true
    }

    fun detach(
        source: ResourceGuardRegistration,
        handoffAttemptId: Long?
    ): ResourceRecoveryDetachResult {
        if (!registration.matches(source)) return ResourceRecoveryDetachResult(detached = false)

        val current = attempt
        val preserve = current != null &&
            current.id == handoffAttemptId &&
            current.ownerId === source.ownerId &&
            current.sourceGeneration == source.generation &&
            current.phase == ResourceRecoveryPhase.AWAITING_SUCCESSOR

        registration = null
        if (preserve) {
            return ResourceRecoveryDetachResult(
                detached = true,
                preservedAttemptId = current.id
            )
        }

        attempt = null
        return ResourceRecoveryDetachResult(
            detached = true,
            cancelledAttemptId = current?.id
        )
    }

    fun cancelOwner(ownerId: Any): ResourceRecoveryCancelResult {
        val registrationCancelled = registration?.ownerId === ownerId
        if (registrationCancelled) registration = null

        val current = attempt
        val cancelledAttemptId = current?.takeIf { it.ownerId === ownerId }?.id
        if (cancelledAttemptId != null) attempt = null
        return ResourceRecoveryCancelResult(registrationCancelled, cancelledAttemptId)
    }

    fun claimProcessReclaim(ownerId: Any, attemptId: Long): Boolean {
        val current = attempt ?: return false
        if (current.id != attemptId ||
            current.ownerId !== ownerId ||
            current.phase == ResourceRecoveryPhase.RECLAIM_CLAIMED
        ) {
            return false
        }
        attempt = current.copy(phase = ResourceRecoveryPhase.RECLAIM_CLAIMED)
        return true
    }

    fun finish(attemptId: Long): Boolean {
        if (attempt?.id != attemptId) return false
        attempt = null
        return true
    }

    fun isCurrent(registration: ResourceGuardRegistration): Boolean = this.registration.matches(registration)

    fun isAttemptCurrent(
        ownerId: Any,
        attemptId: Long,
        phase: ResourceRecoveryPhase? = null
    ): Boolean {
        val current = attempt ?: return false
        return current.id == attemptId &&
            current.ownerId === ownerId &&
            (phase == null || current.phase == phase)
    }

    internal fun phase(attemptId: Long): ResourceRecoveryPhase? = attempt?.takeIf { it.id == attemptId }?.phase

    private fun ResourceGuardRegistration?.matches(other: ResourceGuardRegistration): Boolean {
        return this != null && ownerId === other.ownerId && generation == other.generation
    }

    private fun Attempt.isAwaitingSuccessorFor(candidate: ResourceGuardRegistration): Boolean {
        return ownerId === candidate.ownerId && phase == ResourceRecoveryPhase.AWAITING_SUCCESSOR
    }

    private fun Attempt.matches(
        source: ResourceGuardRegistration,
        attemptId: Long,
        expectedPhase: ResourceRecoveryPhase
    ): Boolean {
        return id == attemptId && ownerId === source.ownerId &&
            sourceGeneration == source.generation && phase == expectedPhase
    }
}
