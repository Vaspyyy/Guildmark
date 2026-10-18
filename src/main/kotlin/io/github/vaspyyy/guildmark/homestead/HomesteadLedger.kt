package io.github.vaspyyy.guildmark.homestead

import io.github.vaspyyy.guildmark.guild.TownControl
import io.github.vaspyyy.guildmark.homestead.plan.PlotShape
import io.github.vaspyyy.guildmark.homestead.plan.Stuff
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import java.util.UUID

/**
 * The ledger kept at a homestead's Charter Post: its owner and their party open it there to see the plot, the tier and
 * the next, what the build needs and has, and how the crew is getting on, and on its shipping page what the carters
 * have taken to market (see [HomesteadShipping]); the owner commissions the next tier from it, empties the post's purse
 * or gives the charter up. Anyone else using the post is told whose land it is.
 */
object HomesteadLedger {
    const val COMMISSION = 0
    const val RELINQUISH = 1
    const val REFRESH = 2
    /** Builds round whatever holds the build up (the ledger's button, when something can't be moved). */
    const val BUILD_ROUND = 3
    /** The owner takes the Marks the carters have left in the post's purse. */
    const val COLLECT = 4

    private val lastCheck = HashMap<UUID, Long>()

    /** [player] used the post at [pos]. */
    fun openAt(level: ServerLevel, player: ServerPlayer, pos: BlockPos) {
        val h = Homesteads.get(level).homesteads.firstOrNull { it.post == pos }
        if (h == null) {
            // A post whose charter is gone comes down
            HomesteadWorks.takeDown(level, pos)
            return
        }
        if (!HomesteadProtection.mayUse(level, h, player)) {
            val tier = Component.translatable("gui.guildmark.homestead.tier.${HomesteadTiers.key(h.tier)}")
            player.sendOverlayMessage(Component.translatable("message.guildmark.homestead.whose", h.ownerName, tier).withStyle(ChatFormatting.GOLD))
            return
        }
        send(level, player, h)
        level.playSound(null, pos, HomesteadRegistry.LEDGER.get(), SoundSource.BLOCKS, 0.7f, 1f)
    }

    /** Sends [player] the ledger of [h] as it stands. */
    fun send(level: ServerLevel, player: ServerPlayer, h: Homestead) {
        val site = HomesteadWorks.site(level, h)
        val going = HomesteadTiers.of(if (h.building) h.build.tier else h.tier + 1)
        val next = HomesteadTiers.of(h.tier + 1)
        val bill = going?.bill?.copyOf() ?: IntArray(Stuff.PAID.size)
        val left = site?.left() ?: bill.copyOf()
        val crate = (level.getBlockEntity(h.crate) as? GoodsCrateBlockEntity)?.let(Materials::count) ?: IntArray(Stuff.PAID.size)
        val total = site?.total ?: going?.let { Homesteads.get(level).plan(h, it.n).steps.size } ?: 0
        val laid = site?.laid ?: 0
        val eta = when {
            site != null -> ((site.total - site.laid) / site.rate()).toInt()
            going != null -> (going.days * HomesteadTiers.WORKDAY / HomesteadSite.speed()).toInt()
            else -> 0
        }
        val short = if (site != null && site.build.reach < site.total - site.laid) site.short?.ordinal ?: -1 else -1
        val payload = LedgerPayload(
            h.id, h.ownerName, h.owner == player.uuid, h.tier, if (h.building) h.build.tier else h.tier, site?.stage?.ordinal ?: -1,
            laid, total, next?.marks ?: 0, TownControl.marks(player), going?.crew ?: 0, BuilderCrew.builders(h.id).count { !it.leaving },
            eta, bill, left, crate, short, site?.blocked, h.facing.get2DDataValue(), h.post, map(level, h), HomesteadShipping.view(level, h),
        )
        PacketDistributor.sendToPlayer(player, payload)
    }

    /** A button in the ledger. */
    fun act(player: ServerPlayer, id: Int, action: Int, confirm: Boolean) {
        val level = player.level()
        if (level.dimension() != Level.OVERWORLD) return
        val h = Homesteads.get(level).byId(id) ?: return
        // Only at the post itself
        if (player.distanceToSqr(Vec3.atCenterOf(h.post)) > 10.0 * 10.0) return
        if (!HomesteadProtection.mayUse(level, h, player)) return
        when (action) {
            COMMISSION -> {
                val refusal = HomesteadWorks.commission(level, player, h)
                if (refusal != null) {
                    val reason = Component.translatable(refusal.key, *refusal.args)
                    player.sendOverlayMessage(reason.copy().withStyle(ChatFormatting.RED))
                    level.playSound(null, player.blockPosition(), HomesteadRegistry.REFUSE.get(), SoundSource.PLAYERS, 0.7f, 1f)
                    if (refusal.highlight.isNotEmpty()) HomesteadNet.verdict(player, HomesteadClaims.Verdict(false, reason, refusal.highlight), null, 0)
                }
                send(level, player, h)
            }
            RELINQUISH -> {
                if (h.owner != player.uuid || !confirm) return
                HomesteadWorks.remove(level, h)
                player.sendSystemMessage(Component.translatable("message.guildmark.homestead.relinquished").withStyle(ChatFormatting.GRAY))
            }
            REFRESH -> send(level, player, h)
            BUILD_ROUND -> {
                val site = HomesteadWorks.live(h.id) ?: return
                val step = site.blockedAt
                if (step >= 0) site.skip(step)
                send(level, player, h)
            }
            COLLECT -> {
                HomesteadShipping.collect(level, player, h)
                send(level, player, h)
            }
        }
    }

    /** The charter in [player]'s hand asks about a spot (see ClaimCheckPayload); a few times a second at most. */
    fun check(player: ServerPlayer, post: BlockPos, facing: Int) {
        val level = player.level()
        val now = level.gameTime
        if ((lastCheck[player.uuid] ?: -100L) + 6L > now) return
        if (lastCheck.size > 512) lastCheck.clear()
        lastCheck[player.uuid] = now
        if (!player.mainHandItem.`is`(HomesteadRegistry.CHARTER.get()) && !player.offhandItem.`is`(HomesteadRegistry.CHARTER.get())) return
        if (player.distanceToSqr(Vec3.atCenterOf(post)) > 12.0 * 12.0) return
        val dir = Direction.from2DDataValue(facing)
        HomesteadNet.verdict(player, HomesteadClaims.survey(level, player, post, dir), post, facing)
    }

    /**
     * The charter's land from above, as a map would show it: each column's top block's map colour, shaded by how it
     * lies against the column before it.
     */
    private fun map(level: ServerLevel, h: Homestead): ByteArray {
        val r = PlotShape.RESERVED
        val size = r.width
        val out = ByteArray(size * r.depth)
        val frame = h.frame
        val cursor = BlockPos.MutableBlockPos()
        val tops = IntArray(size * r.depth)
        for (z in r.z0..r.z1) for (x in r.x0..r.x1) {
            val wx = frame.worldX(x, z)
            val wz = frame.worldZ(x, z)
            val i = (z - r.z0) * size + (x - r.x0)
            if (!level.hasChunkAt(wx, wz)) continue
            val top = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz) - 1
            tops[i] = top
            cursor.set(wx, top, wz)
            val state = level.getBlockState(cursor)
            val color = state.getMapColor(level, cursor)
            if (color == MapColor.NONE) continue
            val before = if (z > r.z0) tops[i - size] else top
            val brightness = when {
                state.fluidState.isEmpty.not() -> MapColor.Brightness.NORMAL
                top > before -> MapColor.Brightness.HIGH
                top < before -> MapColor.Brightness.LOW
                else -> MapColor.Brightness.NORMAL
            }
            out[i] = color.getPackedId(brightness)
        }
        return out
    }
}

/** Sending homesteads' news to clients. */
object HomesteadNet {
    fun register(event: RegisterPayloadHandlersEvent) {
        event.registrar("1")
            .playToClient(HomesteadSyncPayload.TYPE, HomesteadSyncPayload.STREAM_CODEC, HomesteadSyncPayload::handle)
            .playToClient(ClaimVerdictPayload.TYPE, ClaimVerdictPayload.STREAM_CODEC, ClaimVerdictPayload::handle)
            .playToClient(LedgerPayload.TYPE, LedgerPayload.STREAM_CODEC, LedgerPayload::handle)
            .playToServer(ClaimCheckPayload.TYPE, ClaimCheckPayload.STREAM_CODEC, ClaimCheckPayload::handle)
            .playToServer(LedgerActionPayload.TYPE, LedgerActionPayload.STREAM_CODEC, LedgerActionPayload::handle)
    }

    private fun payload(server: MinecraftServer): HomesteadSyncPayload =
        HomesteadSyncPayload(
            Homesteads.get(server.overworld()).homesteads.map {
                HomesteadSyncPayload.Entry(
                    it.id, it.owner, it.ownerName, it.post, it.facing.get2DDataValue(), it.tier, if (it.building) it.build.tier else it.tier, it.color,
                )
            },
        )

    /** Every player hears of every homestead as it now stands. */
    fun syncAll(server: MinecraftServer) {
        val payload = payload(server)
        for (player in server.playerList.players) PacketDistributor.sendToPlayer(player, payload)
    }

    fun sync(player: ServerPlayer) {
        PacketDistributor.sendToPlayer(player, payload(player.level().server))
    }

    fun verdict(player: ServerPlayer, verdict: HomesteadClaims.Verdict, post: BlockPos?, facing: Int?) {
        PacketDistributor.sendToPlayer(player, ClaimVerdictPayload(verdict.ok, verdict.reason, verdict.highlight, post, facing ?: 0))
    }
}
