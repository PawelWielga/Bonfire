package com.foenichs.bonfire.listener.protection

import com.destroystokyo.paper.event.entity.PlayerNaturallySpawnCreaturesEvent
import com.foenichs.bonfire.Bonfire
import com.foenichs.bonfire.service.ProtectionService
import com.foenichs.bonfire.storage.ClaimRegistry
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.data.Directional
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.FallingBlock
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
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.event.world.StructureGrowEvent
import org.bukkit.inventory.ItemStack

class WorldProtectionListener(
    private val plugin: Bonfire,
    private val registry: ClaimRegistry,
    private val protection: ProtectionService
) : Listener {

    init {
        // Handle falling blocks crossing claim borders
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            Bukkit.getWorlds().forEach { world ->
                world.getEntitiesByClass(FallingBlock::class.java).forEach { entity ->
                    if (!entity.isValid || entity.isOnGround) return@forEach

                    val chunk = entity.location.chunk
                    val claim = registry.getAt(chunk) ?: return@forEach

                    if (!claim.allowBlockBreak && !protection.isOrigin(entity, chunk)) {
                        entity.world.dropItemNaturally(entity.location, ItemStack(entity.blockData.material))
                        entity.remove()
                    }
                }
            }
        }, 1L, 1L)
    }

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
     * Tags Snowman, ArmorStand and FallingBlock when they spawn inside a claim.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntitySpawn(event: EntitySpawnEvent) {
        val entity = event.entity
        if (entity !is Snowman && entity !is ArmorStand && entity !is FallingBlock) return

        val claim = registry.getAt(event.location.chunk)
        if (claim != null) {
            entity.addScoreboardTag("bonfire_origin_${claim.id}")
        }
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

    /**
     * Drop falling blocks when entering claims they don't originate from.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        val entity = event.entity
        if (entity !is FallingBlock) return
        if (event.to == Material.AIR) return

        val chunk = event.block.chunk
        val claim = registry.getAt(chunk) ?: return

        if (!claim.allowBlockBreak && !protection.isOrigin(entity, chunk)) {
            event.isCancelled = true
            entity.world.dropItemNaturally(entity.location, ItemStack(entity.blockData.material))
            entity.remove()
        }
    }

    /**
     * Unauthorized players spawning mobs in claims
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPlayerNaturallySpawnCreatures(event: PlayerNaturallySpawnCreaturesEvent) {
        val player = event.player
        val chunk = player.location.chunk
        val claim = registry.getAt(chunk) ?: return

        if (
            claim.allowEntityInteract == "false" ||
            claim.allowEntityInteract == "onlyMounts"
        ) {
            if (!protection.canBypass(player, chunk)) {
                event.isCancelled = true
            }
        }
    }
}