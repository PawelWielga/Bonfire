package com.foenichs.bonfire.service

import com.flowpowered.math.vector.Vector2d
import com.foenichs.bonfire.Bonfire
import com.foenichs.bonfire.model.Claim
import com.foenichs.bonfire.storage.ClaimRegistry
import de.bluecolored.bluemap.api.BlueMapAPI
import de.bluecolored.bluemap.api.markers.ExtrudeMarker
import de.bluecolored.bluemap.api.markers.MarkerSet
import de.bluecolored.bluemap.api.math.Color
import de.bluecolored.bluemap.api.math.Shape
import org.bukkit.Bukkit
import java.util.UUID
import java.util.function.Consumer
import java.util.logging.Level

class BlueMapService(
    private val plugin: Bonfire,
    private val registry: ClaimRegistry
) : ClaimMapService {

    private var api: BlueMapAPI? = null
    private val markerSetId = "bonfire_claims"
    private val markerSetLabel = "Bonfire Claims"

    private val enableListener = Consumer<BlueMapAPI> { blueMap ->
        api = blueMap
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { refreshSafely() }, 40L)
    }
    private val disableListener = Consumer<BlueMapAPI> {
        api = null
    }

    init {
        BlueMapAPI.onEnable(enableListener)
        BlueMapAPI.onDisable(disableListener)
    }

    override fun refreshAll() {
        val blueMap = api ?: return

        clearMarkerSets(blueMap)
        if (!plugin.config.getBoolean("bluemap.enable-markers", true)) return

        val claimsByWorld = registry.getAll().groupBy { it.chunks.firstOrNull()?.worldUuid }
        claimsByWorld.forEach { (worldId, claims) ->
            if (worldId == null) return@forEach

            blueMap.getWorld(worldId).ifPresent { world ->
                world.maps.forEach { map ->
                    val markerSet = MarkerSet.builder().label(markerSetLabel).build()
                    map.markerSets[markerSetId] = markerSet

                    claims.forEach { claim ->
                        try {
                            createMarker(markerSet, claim)
                        } catch (_: ArrayStoreException) {
                            plugin.logger.severe("[Bonfire] Dependency Error: flow-math library is conflicting.")
                            return@forEach
                        } catch (e: Exception) {
                            plugin.logger.log(Level.WARNING, "Failed to create BlueMap marker for claim ${claim.id}.", e)
                        }
                    }
                }
            }
        }
    }

    override fun updateClaim(claim: Claim) {
        val blueMap = api ?: return
        val worldId = claim.chunks.firstOrNull()?.worldUuid ?: return

        if (!plugin.config.getBoolean("bluemap.enable-markers", true)) return

        blueMap.getWorld(worldId).ifPresent { world ->
            world.maps.forEach { map ->
                val markerSet = map.markerSets[markerSetId] ?: return@forEach
                createMarker(markerSet, claim)
            }
        }
    }

    override fun removeClaim(id: Int, worldId: UUID) {
        val blueMap = api ?: return
        blueMap.getWorld(worldId).ifPresent { world ->
            world.maps.forEach { map ->
                map.markerSets[markerSetId]?.remove("claim_$id")
            }
        }
    }

    override fun shutdown() {
        api?.let(::clearMarkerSets)
        BlueMapAPI.unregisterListener(enableListener)
        BlueMapAPI.unregisterListener(disableListener)
        api = null
    }

    private fun refreshSafely() {
        try {
            refreshAll()
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "Failed to refresh BlueMap claim markers.", e)
        } catch (e: LinkageError) {
            plugin.logger.log(Level.WARNING, "BlueMap integration is incompatible while refreshing claim markers.", e)
        }
    }

    private fun clearMarkerSets(blueMap: BlueMapAPI) {
        blueMap.maps.forEach { map ->
            map.markerSets.remove(markerSetId)
        }
    }

    private fun createMarker(markerSet: MarkerSet, claim: Claim) {
        val id = claim.id ?: return
        val polygon = ClaimPolygonTracer.trace(claim) ?: return
        val ownerName = Bukkit.getOfflinePlayer(claim.owner).name ?: "Unknown"

        val labelTemplate = plugin.config.getString("bluemap.label", "Claimed by \$name")!!
        val label = labelTemplate.replace("\$name", ownerName)
        val listed = plugin.config.getBoolean("bluemap.list-markers", false)
        val viewDist = plugin.config.getDouble("bluemap.view-distance", 1000.0)

        val outerShape = Shape(polygon.outer.map { Vector2d(it.x, it.z) })
        val holes = polygon.holes
            .map { hole -> Shape(hole.map { Vector2d(it.x, it.z) }) }
            .toTypedArray()

        val marker = ExtrudeMarker.builder()
            .label(label)
            .shape(outerShape, -64f, 320f)
            .lineColor(getAccentColor(0.4f))
            .fillColor(getAccentColor(0.1f))
            .depthTestEnabled(true)
            .lineWidth(2)
            .listed(listed)
            .minDistance(10.0)
            .maxDistance(viewDist)
            .holes(*holes)
            .build()

        markerSet.put("claim_$id", marker)
    }

    private fun getAccentColor(alpha: Float): Color {
        val rgb = plugin.config.getString("bluemap.accent-color", "255, 221, 161")!!
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }

        return if (rgb.size == 3) {
            Color(rgb[0], rgb[1], rgb[2], alpha)
        } else {
            Color(255, 221, 161, alpha)
        }
    }
}
