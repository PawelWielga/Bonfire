package com.foenichs.bonfire.service

import com.foenichs.bonfire.model.Claim
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

interface ClaimMapService {
    fun refreshAll()
    fun updateClaim(claim: Claim)
    fun removeClaim(id: Int, worldId: UUID)
    fun shutdown() {}
}

internal inline fun createSafeClaimMapService(
    logger: Logger,
    integrationName: String,
    factory: () -> ClaimMapService
): ClaimMapService? {
    return try {
        factory().safely(logger)
    } catch (e: Exception) {
        logger.log(Level.WARNING, "Failed to initialize $integrationName map integration.", e)
        null
    } catch (e: LinkageError) {
        logger.log(Level.WARNING, "$integrationName map integration is incompatible and will be disabled.", e)
        null
    }
}

private fun ClaimMapService.safely(logger: Logger): ClaimMapService =
    SafeClaimMapService(this, logger)

private class SafeClaimMapService(
    private val delegate: ClaimMapService,
    private val logger: Logger
) : ClaimMapService {
    override fun refreshAll() {
        execute("refresh claim markers") { delegate.refreshAll() }
    }

    override fun updateClaim(claim: Claim) {
        execute("update markers for claim ${claim.id}") { delegate.updateClaim(claim) }
    }

    override fun removeClaim(id: Int, worldId: UUID) {
        execute("remove markers for claim $id") { delegate.removeClaim(id, worldId) }
    }

    override fun shutdown() {
        execute("shut down") { delegate.shutdown() }
    }

    private inline fun execute(operation: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Map integration ${delegate.javaClass.simpleName} failed to $operation.", e)
        } catch (e: LinkageError) {
            logger.log(Level.WARNING, "Map integration ${delegate.javaClass.simpleName} is incompatible while trying to $operation.", e)
        }
    }
}
