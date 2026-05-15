package com.foenichs.bonfire.command

import com.foenichs.bonfire.model.Claim
import com.foenichs.bonfire.service.ClaimService
import com.foenichs.bonfire.service.LimitService
import com.foenichs.bonfire.storage.ClaimRegistry
import com.foenichs.bonfire.ui.Messenger
import com.mojang.brigadier.arguments.StringArgumentType
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.permissions.Permission
import org.bukkit.permissions.PermissionDefault

class ChunkCommand(
    private val service: ClaimService,
    private val registry: ClaimRegistry,
    private val limits: LimitService,
    private val msg: Messenger
) {
    fun register(registrar: Commands) {
        /** Register command permissions to override the default Operator access */
        val pm = Bukkit.getPluginManager()
        listOf(
            "bonfire.command.claim",
            "bonfire.command.owner",
            "bonfire.command.removeplayer"
        ).forEach { node ->
            if (pm.getPermission(node) == null) {
                pm.addPermission(Permission(node, PermissionDefault.FALSE))
            }
        }

        val node = Commands.literal("chunk").executes { ctx ->
            val p = ctx.source.sender as? Player ?: return@executes 0
            val canClaim =
                registry.getAt(p.location.chunk) == null && registry.getOwnedChunks(p.uniqueId) < limits.getLimits(p).maxChunks

            if (!canClaim && !isOwner(p)) {
                msg.sendNoAccess(p)
            } else {
                ctx.source.sender.sendMessage(Component.text("Usage: /chunk <subcommand>", NamedTextColor.RED))
            }
            1
        }.then(Commands.literal("claim").requires { it.sender.hasPermission("bonfire.command.claim") }.executes { ctx ->
            service.tryClaim(ctx.source.sender as Player); 1
        })

            .then(Commands.literal("unclaim").requires { it.sender.hasPermission("bonfire.command.owner") }.executes { ctx ->
                service.tryUnclaim(ctx.source.sender as Player); 1
            })

            .then(
                Commands.literal("setrule").requires { it.sender.hasPermission("bonfire.command.owner") }
                    .then(booleanRuleNode("allowBlockBreak") { it.allowBlockBreak })
                    .then(booleanRuleNode("allowBlockInteract") { it.allowBlockInteract }).then(
                        Commands.literal("allowEntityInteract")
                            .then(Commands.literal("true").requires { (it.sender as? Player)?.let { p -> registry.getAt(p.location.chunk)?.allowEntityInteract != "true" } ?: true }.executes { ctx ->
                                service.setRule(ctx.source.sender as Player, "allowEntityInteract", "true"); 1
                            })
                            .then(Commands.literal("false").requires { (it.sender as? Player)?.let { p -> registry.getAt(p.location.chunk)?.allowEntityInteract != "false" } ?: true }.executes { ctx ->
                                service.setRule(ctx.source.sender as Player, "allowEntityInteract", "false"); 1
                            })
                            .then(Commands.literal("onlyMounts").requires { (it.sender as? Player)?.let { p -> registry.getAt(p.location.chunk)?.allowEntityInteract != "onlyMounts" } ?: true }.executes { ctx ->
                                service.setRule(ctx.source.sender as Player, "allowEntityInteract", "onlyMounts"); 1
                            })
                    )
            )
            .then(
                Commands.literal("addplayer").requires { it.sender.hasPermission("bonfire.command.owner") }
                    .then(
                        Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                            val p = ctx.source.sender as Player
                            val input = b.remaining.lowercase()

                            Bukkit.getOfflinePlayers().forEach { o ->
                                val name = o.name
                                if (name != null && name.lowercase().startsWith(input) && o.uniqueId != p.uniqueId) {
                                    b.suggest(name)
                                }
                            }
                            b.buildFuture()
                        }.then(Commands.literal("always").executes { ctx ->
                            service.addTrust(
                                ctx.source.sender as Player, StringArgumentType.getString(ctx, "target"), "always"
                            ); 1
                        }).then(Commands.literal("whileOnline").executes { ctx ->
                            service.addTrust(
                                ctx.source.sender as Player, StringArgumentType.getString(ctx, "target"), "whileOnline"
                            ); 1
                        })
                    )
            ).then(
                Commands.literal("removeplayer").requires { it.sender.hasPermission("bonfire.command.removeplayer") }.then(
                    Commands.argument("target", StringArgumentType.word()).suggests { ctx, b ->
                        val c = registry.getAt((ctx.source.sender as Player).location.chunk)
                        val input = b.remaining.lowercase()

                        c?.trustedAlways?.forEach { id ->
                            val name = Bukkit.getOfflinePlayer(id).name
                            if (name != null && name.lowercase().startsWith(input)) b.suggest(name)
                        }
                        c?.trustedOnline?.forEach { id ->
                            val name = Bukkit.getOfflinePlayer(id).name
                            if (name != null && name.lowercase().startsWith(input)) b.suggest(name)
                        }
                        b.buildFuture()
                    }.executes { ctx ->
                        service.removeTrust(ctx.source.sender as Player, StringArgumentType.getString(ctx, "target")); 1
                    })
            )

        registrar.register(node.build(), "The core command of Bonfire.")
    }

    /**
     * Boolean rule suggestions and execution
     */
    private fun booleanRuleNode(name: String, property: (Claim) -> Boolean) =
        Commands.literal(name)
            .then(Commands.literal("true").requires { (it.sender as? Player)?.let { p -> registry.getAt(p.location.chunk)?.let { c -> !property(c) } } ?: true }.executes { ctx ->
                service.setRule(ctx.source.sender as Player, name, "true"); 1
            })
            .then(Commands.literal("false").requires { (it.sender as? Player)?.let { p -> registry.getAt(p.location.chunk)?.let { c -> property(c) } } ?: true }.executes { ctx ->
                service.setRule(ctx.source.sender as Player, name, "false"); 1
            })

    private fun isOwner(p: Player?) = p?.let { registry.getAt(it.location.chunk)?.owner == it.uniqueId } ?: false
}