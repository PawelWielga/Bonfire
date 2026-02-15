package com.foenichs.bonfire.listener.protection

import com.foenichs.bonfire.service.ProtectionService
import com.foenichs.bonfire.storage.ClaimRegistry
import org.bukkit.Material
import org.bukkit.block.data.Directional
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Player
import org.bukkit.entity.Snowman
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockDispenseEvent
import org.bukkit.event.block.BlockFertilizeEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.block.EntityBlockFormEvent
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.event.world.StructureGrowEvent

class WorldProtectionListener(
    private val registry: ClaimRegistry,
    private val protection: ProtectionService
) : Listener {

    /**
     * Liquids flowing into claims
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onLiquidFlow(event: BlockFromToEvent) {
        val fromChunk = event.block.chunk
        val toBlock = event.toBlock
        if (!protection.isWorldActionAllowed(fromChunk, toBlock.chunk) && !protection.checkAllowBlockBreak(toBlock.chunk)) {
            event.isCancelled = true
        }
    }

    /**
     * Fire spreading into claims
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onFireSpread(event: BlockSpreadEvent) {
        if (event.source.type != Material.FIRE) return
        val fromChunk = event.source.chunk
        val toChunk = event.block.chunk
        if (!protection.isWorldActionAllowed(fromChunk, toChunk) && !protection.checkAllowBlockBreak(toChunk)) {
            event.isCancelled = true
        }
    }

    /**
     * Fire from outside destroying blocks inside a claim
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBlockBurn(event: BlockBurnEvent) {
        val igniter = event.ignitingBlock ?: return
        val fromChunk = igniter.chunk
        val toChunk = event.block.chunk
        if (!protection.isWorldActionAllowed(fromChunk, toChunk) && !protection.checkAllowBlockBreak(toChunk)) {
            event.isCancelled = true
        }
    }

    /**
     * Trees and large structures growing into claims
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onStructureGrow(event: StructureGrowEvent) {
        val sourceChunk = event.location.chunk

        val iterator = event.blocks.iterator()
        while (iterator.hasNext()) {
            val state = iterator.next()
            val targetChunk = state.chunk

            // Only block if moving from outside into a protected claim
            if (!protection.isWorldActionAllowed(
                    sourceChunk, targetChunk
                ) && !protection.checkAllowBlockBreak(targetChunk)
            ) {
                event.isCancelled = true
                return
            }
        }
    }

    /**
     * Bone Meal spreading grass/flowers into claims
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onFertilize(event: BlockFertilizeEvent) {
        val sourceChunk = event.block.chunk

        val iterator = event.blocks.iterator()
        while (iterator.hasNext()) {
            val state = iterator.next()
            val targetChunk = state.chunk

            if (!protection.isWorldActionAllowed(
                    sourceChunk, targetChunk
                ) && !protection.checkAllowBlockBreak(targetChunk)
            ) {
                iterator.remove()
            }
        }
    }

    /**
     * Dispensers firing items, fluids, or projectiles across borders
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onDispense(event: BlockDispenseEvent) {
        val block = event.block
        val data = block.blockData

        if (data !is Directional) return

        val targetBlock = block.getRelative(data.facing)
        val fromChunk = block.chunk
        val toChunk = targetBlock.chunk

        if (!protection.isWorldActionAllowed(fromChunk, toChunk) && !protection.checkAllowBlockBreak(toChunk)) {
            event.isCancelled = true
        }
    }

    /**
     * Tags Snowman and ArmorStand when they spawn inside a claim.
     * This allows entities created inside a claim to form blocks within that claim.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntitySpawn(event: EntitySpawnEvent) {
        if (event.entity !is Snowman && event.entity !is ArmorStand) return
        val claim = registry.getAt(event.location.chunk) ?: return
        event.entity.addScoreboardTag("bonfire_origin_${claim.id}")
    }

    /**
     * Entities forming blocks, e.g. using the frost walker enchantment
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityBlockForm(event: EntityBlockFormEvent) {
        val entity = event.entity
        val chunk = event.block.chunk
        val claim = registry.getAt(chunk) ?: return

        if (!claim.allowBlockBreak) {
            when (entity) {
                is Player -> {
                    if (!protection.canBypass(entity, chunk)) {
                        event.isCancelled = true
                    }
                }
                is Snowman, is ArmorStand -> {
                    if (!protection.isOrigin(entity, chunk)) {
                        event.isCancelled = true
                    }
                }
                else -> {
                    event.isCancelled = true
                }
            }
        }
    }
}