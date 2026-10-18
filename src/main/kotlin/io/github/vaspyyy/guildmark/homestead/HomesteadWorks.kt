package io.github.vaspyyy.guildmark.homestead

import io.github.vaspyyy.guildmark.guild.TownControl
import io.github.vaspyyy.guildmark.homestead.plan.PlotShape
import io.github.vaspyyy.guildmark.homestead.plan.Stage
import io.github.vaspyyy.guildmark.homestead.plan.StepKind
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.Containers
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CampfireBlock
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import kotlin.math.min

/**
 * Keeps every homestead's build going (each server tick, from Homesteads): winds the work clock, looks in the supply
 * crate, brings the crew in and sends them home, lays what the clock allows that the crew can't get to, cheers the
 * stages and finishes the tier. Also where a tier is commissioned and a charter given up.
 */
object HomesteadWorks {
    private val sites = HashMap<Int, HomesteadSite>()

    /** Homesteads whose land was all loaded, and that someone was near, at the last look. */
    private val loaded = HashSet<Int>()
    private val watched = HashSet<Int>()

    /** Steps the clock lays in one tick at most, when it has fallen behind (or caught up with nobody there). */
    private const val CATCH_UP = 48

    /** How near someone must be for the crew to come and the work to show. */
    private const val NEAR = 72.0

    /** How long the hearths burn on after the last of a homestead's people has left its land (ticks). */
    private const val HEARTH_WAIT = 600L

    /** How near the post someone must be for its hearths to be seen to. */
    private const val SIGHT = 128.0

    /** When each homestead last had one of its own people on its land (game time). */
    private val home = HashMap<Int, Long>()

    /** What each homestead's hearths were last set to (lit or out) and at what tier: they're only set when that changes,
     *  so a fire its people put out or light themselves stays as they left it. */
    private val hearthsSet = HashMap<Int, Pair<Int, Boolean>>()

    /** The build of homestead [id] going on now, if one is. */
    fun live(id: Int): HomesteadSite? = sites[id]

    /** The build of [homestead] going on now, worked out afresh when its tier has changed. */
    fun site(level: ServerLevel, homestead: Homestead): HomesteadSite? {
        if (!homestead.building) {
            sites.remove(homestead.id)
            return null
        }
        val have = sites[homestead.id]
        if (have != null && have.homestead === homestead && have.plan.tier == homestead.build.tier && have.level === level) return have
        val plan = Homesteads.get(level).plan(homestead, homestead.build.tier)
        return HomesteadSite(level, homestead, plan).also { sites[homestead.id] = it }
    }

    fun watched(id: Int): Boolean = id in watched

    fun tick(server: MinecraftServer) {
        val level = server.overworld()
        val data = Homesteads.get(level)
        val now = level.gameTime
        if (now % 20 == 0L) clearStale(level, data)
        if (data.homesteads.isEmpty()) {
            if (sites.isNotEmpty()) for (id in sites.keys.toList()) forget(id)
            return
        }
        val on = Homesteads.enabled()
        for (h in data.homesteads.toList()) {
            if (on && Math.floorMod(now + h.id * 13L, 40L) == 0L) hearths(level, h, now)
            if (!h.building || !on) {
                if (sites.remove(h.id) != null || BuilderCrew.builders(h.id).isNotEmpty()) BuilderCrew.dismiss(h.id)
                continue
            }
            val site = site(level, h) ?: continue
            if (Math.floorMod(now + h.id * 7L, 20L) == 0L) second(level, h, site)
            if (h.id in loaded && !site.finished) catchUp(h, site)
            if (site.finished) finish(level, h, site)
        }
        if (sites.size > data.homesteads.size) {
            val ids = data.homesteads.map { it.id }.toSet()
            for (id in sites.keys.filter { it !in ids }) forget(id)
        }
    }

    /**
     * The fire's in while the homestead's people are home: its hearths are lit while the owner or one of their party is
     * on its land, and go out a while after the last of them leaves (smoke from the chimney means someone's in).
     */
    private fun hearths(level: ServerLevel, h: Homestead, now: Long) {
        if (h.tier < 1) return
        // Nobody within sight of it: nothing to see, and nothing worked out
        val post = h.post
        if (level.players().none { it.distanceToSqr(post.x + 0.5, it.y, post.z + 0.5) < SIGHT * SIGHT }) return
        if (level.players().any { !it.isSpectator && h.covers(it.blockX, it.blockZ) && Homesteads.belongsTo(level, h, it) }) home[h.id] = now
        val since = home[h.id]
        val lit = since != null && now - since < HEARTH_WAIT
        if (hearthsSet[h.id] == (h.tier to lit)) return
        var all = true
        for (pos in Homesteads.places(level, h).hearths) {
            if (!level.isLoaded(pos)) {
                all = false
                continue
            }
            val state = level.getBlockState(pos)
            if (state.block !is CampfireBlock || state.getValue(CampfireBlock.LIT) == lit) continue
            if (lit && state.getValue(CampfireBlock.WATERLOGGED)) continue
            level.setBlock(pos, state.setValue(CampfireBlock.LIT, lit), Block.UPDATE_ALL)
            level.playSound(null, pos, if (lit) SoundEvents.FLINTANDSTEEL_USE else SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, if (lit) 0.6f else 0.3f, 1f)
        }
        // Set once all of them could be: one in a chunk not yet loaded is seen to on a later look
        if (all) hearthsSet[h.id] = h.tier to lit
    }

    /** A homestead gone: its build and crew forgotten. */
    fun forget(id: Int) {
        home.remove(id)
        hearthsSet.remove(id)
        sites.remove(id)
        loaded.remove(id)
        watched.remove(id)
        BuilderCrew.dismiss(id)
        HomesteadProtection.forget(id)
    }

    /** The server has stopped: nothing of its world is kept (another may be opened next). */
    fun reset() {
        home.clear()
        hearthsSet.clear()
        sites.clear()
        loaded.clear()
        watched.clear()
        BuilderCrew.reset()
        HomesteadProtection.reset()
    }

    private fun second(level: ServerLevel, h: Homestead, site: HomesteadSite) {
        site.wind()
        val box = h.reserved(level.minY, level.maxY)
        if (!level.hasChunksAt(box.minX(), box.minZ(), box.maxX(), box.maxZ())) {
            loaded.remove(h.id)
            watched.remove(h.id)
            BuilderCrew.update(level, site, false)
            return
        }
        loaded.add(h.id)
        repair(level, h)
        site.survey()
        val centre = h.frame.centre(0.0, h.post.y.toDouble(), 24.0)
        val near = level.players().any { !it.isSpectator && it.distanceToSqr(centre.x, it.y, centre.z) < NEAR * NEAR }
        if (near) watched.add(h.id) else watched.remove(h.id)
        val wanted = near && site.workday() && level.isPositionEntityTicking(h.post) && !site.finished
        BuilderCrew.update(level, site, wanted)
        val stage = site.finishedStage
        if (stage != null) {
            site.finishedStage = null
            if (near) celebrate(level, h, site, stage)
        }
    }

    /** The clock's laying: whatever is beyond what the crew keeps in hand, as fast as it may. */
    private fun catchUp(h: Homestead, site: HomesteadSite) {
        val reserve = BuilderCrew.reserve(h.id)
        val build = site.build
        if (build.work < 1.0 + reserve) return
        val fx = h.id in watched && build.work - reserve < 4.0
        var budget = CATCH_UP
        var i = build.step
        while (budget > 0 && build.work >= 1.0 + reserve && !site.finished) {
            if (i < build.step) i = build.step
            val end = site.plan.stageStarts[site.stage.ordinal + 1]
            while (i < end && !site.open(i)) i++
            if (i >= end) break
            when (site.lay(i, fx)) {
                Laid.DONE -> budget -= if (site.step(i).kind == StepKind.GROUND) 3 else 1
                Laid.WAIT, Laid.BLOCKED -> i++
                Laid.NEED, Laid.LATER -> break
            }
        }
    }

    private fun celebrate(level: ServerLevel, h: Homestead, site: HomesteadSite, stage: Stage) {
        BuilderCrew.celebrate(level, site, stage)
        if (stage == Stage.TOPPING) {
            val bough = site.plan.steps.indexOfFirst { it.kind == StepKind.TOPPING }
            val at = if (bough >= 0) Vec3.atCenterOf(site.at(bough)) else h.frame.centre(0.0, h.post.y + 8.0, 11.0)
            level.playSound(null, BlockPos.containing(at), HomesteadRegistry.TOPPING.get(), SoundSource.NEUTRAL, 2f, 1f)
            site.firework(at.add(1.5, 0.5, 0.0), false)
            site.firework(at.add(-1.5, 0.5, 0.0), false)
        }
    }

    /** The tier is up: the homestead is that tier now, and everyone near hears of it. */
    private fun finish(level: ServerLevel, h: Homestead, site: HomesteadSite) {
        val from = h.tier
        h.tier = site.plan.tier
        h.build = Build(h.tier)
        sites.remove(h.id)
        val data = Homesteads.get(level)
        data.changed()
        data.placesChanged(h)
        (level.getBlockEntity(h.post) as? CharterPostBlockEntity)?.set(h.color, h.ownerName, h.tier)
        val name = Component.translatable("gui.guildmark.homestead.tier.${HomesteadTiers.key(h.tier)}")
        if (h.id in watched) {
            val centre = h.frame.centre(0.0, h.land.height(0, 12) + 1.0, 14.0)
            level.playSound(null, BlockPos.containing(centre), HomesteadRegistry.FINISHED.get(), SoundSource.NEUTRAL, 3f, 1f)
            for (k in 0 until 4) {
                val x = (k - 1.5) * 5.0
                site.firework(h.frame.centre(x, h.land.height(x.toInt(), 6) + 1.0, 6.0), k % 2 == 0)
            }
            BuilderCrew.finished(level, h.id)
            for (player in level.players()) {
                if (player.distanceToSqr(centre) > 80.0 * 80.0) continue
                player.connection.send(ClientboundSetTitlesAnimationPacket(10, 60, 20))
                player.connection.send(ClientboundSetTitleTextPacket(Component.translatable("message.guildmark.homestead.finished_title", name)))
                player.connection.send(ClientboundSetSubtitleTextPacket(Component.translatable("message.guildmark.homestead.finished_subtitle", h.ownerName)))
            }
        } else {
            BuilderCrew.dismiss(h.id)
        }
        level.server.playerList.getPlayer(h.owner)?.let { owner ->
            if (owner.distanceToSqr(h.post.x.toDouble(), owner.y, h.post.z.toDouble()) > 80.0 * 80.0) {
                owner.sendSystemMessage(Component.translatable("message.guildmark.homestead.finished_away", name).withStyle(ChatFormatting.GOLD))
            }
        }
        HomesteadEvent.post(HomesteadEvent.TierChanged(level, h, from, h.tier))
        HomesteadNet.syncAll(level.server)
    }

    // ---- Commissioning, finishing by command, giving up ----

    /** Why a tier can't be commissioned now (a lang key and its arguments), or null if it can. */
    class Refusal(val key: String, vararg val args: Any, val highlight: List<BlockPos> = listOf())

    /** Commissions [h]'s next tier for [player], paying its Marks. */
    fun commission(level: ServerLevel, player: ServerPlayer, h: Homestead): Refusal? {
        if (!Homesteads.enabled()) return Refusal("message.guildmark.homestead.disabled")
        if (h.owner != player.uuid) return Refusal("message.guildmark.homestead.not_owner", h.ownerName)
        if (h.building) return Refusal("message.guildmark.homestead.already_building")
        val tier = HomesteadTiers.of(h.tier + 1) ?: return Refusal("message.guildmark.homestead.top_tier")
        val box = h.reserved(level.minY, level.maxY)
        if (!level.hasChunksAt(box.minX(), box.minZ(), box.maxX(), box.maxZ())) return Refusal("message.guildmark.homestead.too_far")
        val free = player.isCreative
        if (!free && TownControl.marks(player) < tier.marks) return Refusal("message.guildmark.homestead.need_marks", tier.marks)
        val inWay = obstacles(level, h, tier.n)
        if (inWay.isNotEmpty()) return Refusal("message.guildmark.homestead.in_the_way", inWay.size, highlight = inWay.take(32))
        if (!free) TownControl.pay(player, tier.marks)
        begin(level, h, tier.n)
        level.playSound(null, h.post, HomesteadRegistry.CLAIM.get(), SoundSource.PLAYERS, 1f, 1.1f)
        player.sendSystemMessage(
            Component.translatable(
                "message.guildmark.homestead.commissioned",
                Component.translatable("gui.guildmark.homestead.tier.${tier.key}"), tier.marks,
            ).withStyle(ChatFormatting.GOLD),
        )
        return null
    }

    /** Starts tier [n] going up (paid for, or by command). */
    fun begin(level: ServerLevel, h: Homestead, n: Int) {
        h.build = Build(n, clock = level.gameTime, dayTime = level.overworldClockTime)
        sites.remove(h.id)
        site(level, h)?.survey()
        Homesteads.get(level).changed()
        HomesteadNet.syncAll(level.server)
    }

    /** Lays the whole of the build going on now at once (the test commands). */
    fun finishNow(level: ServerLevel, h: Homestead) {
        val site = site(level, h) ?: return
        BuilderCrew.dismiss(h.id)
        for (i in 0 until site.total) if (!site.isDone(i)) {
            if (site.lay(i, fx = false, free = true) != Laid.DONE) site.skip(i)
        }
        finish(level, h, site)
    }

    /**
     * Where things of someone's (anything but the wild and the homestead's own earlier tiers) stand where tier [n] will
     * go, nearest the post first.
     */
    fun obstacles(level: ServerLevel, h: Homestead, n: Int): List<BlockPos> {
        val data = Homesteads.get(level)
        val frame = h.frame
        val ours = LongOpenHashSet()
        ours.add(h.post.asLong())
        ours.add(h.crate.asLong())
        for (t in 0 until n) for (s in data.plan(h, t).steps) {
            val pos = frame.world(s.x, s.y, s.z)
            when (s.kind) {
                StepKind.BLOCK, StepKind.SHIPPING -> ours.add(pos.asLong())
                StepKind.STAKE -> {
                    ours.add(pos.asLong())
                    ours.add(pos.above().asLong())
                }
                StepKind.GROUND -> ours.add(pos.below().asLong())
                else -> {}
            }
        }
        val found = LinkedHashSet<BlockPos>()
        fun look(pos: BlockPos) {
            if (pos.asLong() in ours || level.isOutsideBuildHeight(pos)) return
            val state = level.getBlockState(pos)
            if (state.isAir || HomesteadSite.wild(state)) return
            if (state.`is`(HomesteadRegistry.SHIPPING_CRATE.get())) return
            found.add(pos)
        }
        for (s in data.plan(h, n).steps) {
            val pos = frame.world(s.x, s.y, s.z)
            when (s.kind) {
                StepKind.BLOCK, StepKind.SHIPPING -> look(pos)
                StepKind.GROUND -> {
                    val top = min(level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.x, pos.z), pos.y + 24)
                    for (y in pos.y - 1 until top) look(BlockPos(pos.x, y, pos.z))
                }
                else -> {}
            }
            if (found.size > 400) break
        }
        return found.sortedBy { it.distSqr(h.post) }
    }

    /**
     * Gives up [h]: the crew sent home, the post, the crate (its contents dropped) and the stakes taken down, the
     * charter ended. What was built stays, and is anyone's now.
     */
    fun remove(level: ServerLevel, h: Homestead) {
        forget(h.id)
        val data = Homesteads.get(level)
        val posts = listOf(h.post, h.crate)
        for (pos in posts) {
            if (!level.isLoaded(pos)) {
                data.stale.add(pos)
                continue
            }
            takeDown(level, pos)
        }
        // The plot's corner stakes, wherever they stand now (moved out already, if the next tier was begun)
        val corners = (PlotShape.corners(h.tier) + PlotShape.corners(h.build.tier)).distinct()
        for ((x, z) in corners) {
            val y = h.layout.stakeHeight(x, z)
            val pos = h.frame.world(x, y, z)
            if (!level.isLoaded(pos)) continue
            for (p in listOf(pos.above(), pos)) {
                val state = level.getBlockState(p)
                if (state.`is`(net.minecraft.tags.BlockTags.BANNERS) || state.`is`(net.minecraft.tags.BlockTags.FENCES)) level.destroyBlock(p, false)
            }
        }
        data.remove(h)
        data.changed()
        HomesteadEvent.post(HomesteadEvent.Removed(level, h))
        HomesteadNet.syncAll(level.server)
    }

    /** Takes down a post or a supply crate whose charter has ended (a crate's contents dropped where it stood). */
    fun takeDown(level: ServerLevel, pos: BlockPos) {
        val state = level.getBlockState(pos)
        if (state.`is`(HomesteadRegistry.SUPPLY_CRATE.get())) {
            (level.getBlockEntity(pos) as? GoodsCrateBlockEntity)?.let { Containers.dropContents(level, pos, it) }
        } else if (!state.`is`(HomesteadRegistry.CHARTER_POST.get())) return
        level.levelEvent(2001, pos, Block.getId(state))
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)
    }

    /** Posts and crates left over from charters given up while their chunk was away, taken down once it's back. */
    private fun clearStale(level: ServerLevel, data: Homesteads) {
        if (data.stale.isEmpty()) return
        val it = data.stale.iterator()
        var changed = false
        while (it.hasNext()) {
            val pos = it.next()
            if (!level.isLoaded(pos)) continue
            if (data.homesteads.none { h -> h.post == pos || h.crate == pos }) takeDown(level, pos)
            it.remove()
            changed = true
        }
        if (changed) data.changed()
    }

    /** The post and the crate put back if anything took them (a command, a creative player's pick). */
    private fun repair(level: ServerLevel, h: Homestead) {
        val post = level.getBlockState(h.post)
        if (!post.`is`(HomesteadRegistry.CHARTER_POST.get())) {
            level.setBlock(h.post, HomesteadRegistry.CHARTER_POST.get().defaultBlockState().setValue(CharterPostBlock.FACING, h.frame.direction(io.github.vaspyyy.guildmark.city.plan.Dir.N)), Block.UPDATE_ALL)
            (level.getBlockEntity(h.post) as? CharterPostBlockEntity)?.set(h.color, h.ownerName, h.tier)
        }
        val crate = level.getBlockState(h.crate)
        if (!crate.`is`(HomesteadRegistry.SUPPLY_CRATE.get())) {
            level.setBlock(h.crate, HomesteadRegistry.SUPPLY_CRATE.get().defaultBlockState().setValue(GoodsCrateBlock.FACING, h.frame.direction(io.github.vaspyyy.guildmark.city.plan.Dir.N)), Block.UPDATE_ALL)
        }
    }
}
