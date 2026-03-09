package com.foenichs.bonfire.listener.protection

import com.destroystokyo.paper.event.entity.EntityKnockbackByEntityEvent
import com.foenichs.bonfire.service.ProtectionService
import com.foenichs.bonfire.service.VisualService
import com.foenichs.bonfire.storage.ClaimRegistry
import org.bukkit.entity.*
import org.bukkit.event.Cancellable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.player.PlayerEggThrowEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.vehicle.VehicleDamageEvent
import org.bukkit.event.vehicle.VehicleDestroyEvent

class EntityProtectionListener(
    private val registry: ClaimRegistry,
    private val protection: ProtectionService,
    private val visualService: VisualService
) : Listener {

    /**
     * Apply attribute exceptions
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerMove(event: PlayerMoveEvent) {
        val player = event.player
        val chunk = player.location.chunk
        val claim = registry.getAt(chunk) ?: run {
            visualService.clearEntityException(player)
            return
        }
        if (protection.canBypass(player, chunk)) {
            visualService.clearEntityException(player)
            return
        }

        val allowEntity = claim.allowEntityInteract
        if (allowEntity != "false" && allowEntity != "onlyMounts") {
            visualService.clearEntityException(player)
            return
        }

        val target = player.getTargetEntity(5)
        val isPet = target != null && protection.ownsEntity(player, target)
        val isMountOrVehicle = target is Vehicle || target is Steerable

        val shouldApply = when (allowEntity) {
            "false" -> isPet
            "onlyMounts" -> isPet || isMountOrVehicle
            else -> false
        }

        if (shouldApply) {
            visualService.setEntityException(player)
        } else {
            visualService.clearEntityException(player)
        }
    }

    /**
     * Mobs targeting players
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityTarget(event: EntityTargetLivingEntityEvent) {
        val target = event.target as? Player ?: return

        // Authorized players can be targeted normally
        if (protection.canBypass(target, target.location.chunk)) return

        val claim = registry.getAt(target.location.chunk) ?: return
        if (claim.allowEntityInteract == "false" || claim.allowEntityInteract == "onlyMounts") {
            event.target = null
            event.isCancelled = true
        }
    }

    /**
     * Breaking item frames, paintings, or leash knots
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onHangingBreak(event: HangingBreakByEntityEvent) {
        val remover = event.remover as? Player ?: return

        // Authorized players can break these normally
        if (protection.canBypass(remover, event.entity.location.chunk)) return

        event.isCancelled = true
    }

    /**
     * Direct damage or explosions affecting entities
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity
        val victimChunk = victim.location.chunk
        val claim = registry.getAt(victimChunk) ?: return

        // Resolve the responsible player (damager)
        val damager = when (val attacker = event.damager) {
            is Player -> attacker
            is Projectile -> attacker.shooter as? Player
            is TNTPrimed -> attacker.source as? Player
            is Creeper -> (attacker.igniter as? Player) ?: (attacker.target as? Player)
            else -> null
        }

        // Authorized damagers can always deal damage
        if (damager != null && protection.canBypass(damager, victimChunk)) return

        // Mobs and world damage can only affect authorized players
        if (damager == null && victim is Player && protection.canBypass(victim, victimChunk)) return

        // Enforcement for unauthorized actors
        if (claim.allowEntityInteract == "false" || claim.allowEntityInteract == "onlyMounts") {

            // Allow if a player is interacting with entities they own
            if (damager != null && protection.ownsEntity(damager, victim)) return

            // Block explosions and all other damage from unauthorized sources
            if (event.cause == DamageCause.ENTITY_EXPLOSION || event.cause == DamageCause.BLOCK_EXPLOSION) {
                event.isCancelled = true
                return
            }

            event.isCancelled = true
        }
    }

    /**
     * Knockback caused by entities
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityKnockback(event: EntityKnockbackByEntityEvent) {
        val victim = event.entity
        val victimChunk = victim.location.chunk
        val claim = registry.getAt(victimChunk) ?: return

        // Responsible player
        val damager = when (val source = event.hitBy) {
            is Player -> source
            is Projectile -> source.shooter as? Player
            else -> null
        }

        // Authorized damagers can deal knockback normally
        if (damager != null && protection.canBypass(damager, victimChunk)) return

        // Only authorized players can be knocked back
        if (damager == null && victim is Player && protection.canBypass(victim, victimChunk)) return

        if (claim.allowEntityInteract == "false" || claim.allowEntityInteract == "onlyMounts") {
            // Allow if the damager owns the victim
            if (damager != null && protection.ownsEntity(damager, victim)) return

            event.isCancelled = true
        }
    }

    /**
     * General entity interaction
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityInteract(event: PlayerInteractEntityEvent) {
        processInteract(event.player, event.rightClicked, event)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityInteractAt(event: PlayerInteractAtEntityEvent) {
        processInteract(event.player, event.rightClicked, event)
    }

    private fun processInteract(player: Player, entity: Entity, event: Cancellable) {
        // Authorized players and pet owners are not restricted
        if (protection.ownsEntity(player, entity)) return
        if (protection.canBypass(player, entity.location.chunk)) return

        val claim = registry.getAt(entity.location.chunk) ?: return
        if (claim.allowEntityInteract == "false") {
            event.isCancelled = true
        } else if (claim.allowEntityInteract == "onlyMounts") {
            if (entity !is Vehicle && entity !is Steerable) {
                event.isCancelled = true
            }
        }
    }

    /**
     * Placing entities (boats, armor stands, etc.)
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityPlace(event: EntityPlaceEvent) {
        val player = event.player ?: return
        val chunk = event.entity.location.chunk

        if (protection.canBypass(player, chunk)) return

        val claim = registry.getAt(chunk) ?: return
        if (claim.allowEntityInteract == "false" || claim.allowEntityInteract == "onlyMounts") {
            event.isCancelled = true
        }
    }

    /**
     * Damaging vehicles like boats or minecarts
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onVehicleDamage(event: VehicleDamageEvent) {
        val attacker = event.attacker ?: return
        if (isVehicleActionBlocked(attacker, event.vehicle.location.chunk)) {
            event.isCancelled = true
        }
    }

    /**
     * Destroying vehicles like boats or minecarts
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onVehicleDestroy(event: VehicleDestroyEvent) {
        val attacker = event.attacker ?: return
        if (isVehicleActionBlocked(attacker, event.vehicle.location.chunk)) {
            event.isCancelled = true
        }
    }

    /**
     * Helper logic for vehicle protection
     */
    private fun isVehicleActionBlocked(attacker: Entity, chunk: org.bukkit.Chunk): Boolean {
        val player = when (attacker) {
            is Player -> attacker
            is Projectile -> attacker.shooter as? Player
            else -> null
        }
        if (player != null && protection.canBypass(player, chunk)) return false
        val claim = registry.getAt(chunk) ?: return false
        return claim.allowEntityInteract == "false" || claim.allowEntityInteract == "onlyMounts"
    }

    /**
     * Eggs spawning chickens
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPlayerEggThrow(event: PlayerEggThrowEvent) {
        val player = event.player
        val chunk = event.egg.location.chunk
        val claim = registry.getAt(chunk) ?: return

        if (
            claim.allowEntityInteract == "false" ||
            claim.allowEntityInteract == "onlyMounts"
        ) {
            if (!protection.canBypass(player, chunk)) {
                event.isHatching = false
                event.numHatches = 0
            }
        }
    }
}