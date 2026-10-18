package io.github.vaspyyy.guildmark.homestead

import io.github.vaspyyy.guildmark.city.CityStyle
import io.github.vaspyyy.guildmark.city.WorldCanvas
import io.github.vaspyyy.guildmark.city.plan.Dir
import io.github.vaspyyy.guildmark.city.plan.Rect
import io.github.vaspyyy.guildmark.dragon.DragonRoosts
import io.github.vaspyyy.guildmark.field.FieldBosses
import io.github.vaspyyy.guildmark.homestead.plan.HomesteadLayout
import io.github.vaspyyy.guildmark.homestead.plan.PlotLand
import io.github.vaspyyy.guildmark.homestead.plan.PlotShape
import io.github.vaspyyy.guildmark.lair.Lairs
import io.github.vaspyyy.guildmark.outlaw.OutlawCamps
import io.github.vaspyyy.guildmark.story.HollowThrone
import io.github.vaspyyy.guildmark.townsfolk.TownZones
import io.github.vaspyyy.guildmark.city.Capitals
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.StructureTags
import net.minecraft.world.entity.ai.village.poi.PoiManager
import net.minecraft.world.entity.ai.village.poi.PoiTypes
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.DyeItem
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.structure.BoundingBox
import kotlin.math.max

/**
 * Staking a homestead with a charter: whether the land chosen will do, and why not if it won't (the [Verdict], shown to
 * the player in words and as the places at fault picked out on the ground), and the claim itself.
 *
 * A homestead goes on open land in the Overworld, one to a player: clear of towns, capitals and villages, of outlaw
 * camps, lairs and troll camps, of dragons' roosts and the Hollow Throne, and of other homesteads; on ground that's dry
 * and gentle enough for every tier's buildings and fields, with nothing anyone built standing on it. The plot reaches
 * the way the player faces, the Charter Post at its gate where they stand.
 */
object HomesteadClaims {
    /** What a survey of a spot found: [ok], or the [reason] it won't do and the places at fault ([highlight]). */
    class Verdict(val ok: Boolean, val reason: Component, val highlight: List<BlockPos> = listOf(), val land: PlotLand? = null)

    /** Room kept between a homestead's land and anyone else's. */
    private const val APART = 8
    private const val TOWN_MARGIN = 16
    private const val VILLAGE = 64
    private const val CAMP = 48
    private const val LAIR = 40
    private const val ROOST = 64

    private fun no(key: String, vararg args: Any, highlight: List<BlockPos> = listOf()) =
        Verdict(false, Component.translatable("message.guildmark.homestead.refuse.$key", *args), highlight)

    /** Whether [player] may stake a homestead with its post at [post], the plot reaching [facing]. */
    fun survey(level: ServerLevel, player: ServerPlayer, post: BlockPos, facing: Direction): Verdict {
        if (!Homesteads.enabled()) return no("disabled")
        if (level.dimension() != Level.OVERWORLD) return no("overworld")
        Homesteads.of(level, player.uuid)?.let { return no("one", it.post.x, it.post.z) }
        val frame = PlotFrame(post, facing)
        val box = frame.box(PlotShape.RESERVED, post.y - 16, post.y + 40)
        if (!level.hasChunksAt(box.minX(), box.minZ(), box.maxX(), box.maxZ())) return no("far")
        if (post.y + 40 > level.maxY || post.y - 24 < level.minY) return no("height")

        // Everyone else's land
        val data = Homesteads.get(level)
        for (other in data.homesteads) {
            val theirs = other.reserved(level.minY, level.maxY)
            if (inflate(theirs, APART).intersects(box.minX(), box.minZ(), box.maxX(), box.maxZ())) {
                return no("neighbour", other.ownerName, highlight = outline(level, theirs))
            }
        }
        val wide = inflate(box, TOWN_MARGIN)
        for (zone in TownZones.get(level).zones) {
            if (wide.intersects(zone.center.x - zone.radius, zone.center.z - zone.radius, zone.center.x + zone.radius, zone.center.z + zone.radius)) return no("town")
        }
        if (Capitals.enabled()) {
            for (capital in Capitals.around(level, wide.minX(), wide.minZ(), wide.maxX(), wide.maxZ())) {
                val grounds = Capitals.grounds(capital)
                if (wide.intersects(grounds.minX(), grounds.minZ(), grounds.maxX(), grounds.maxZ())) return no("capital")
            }
        }
        val middle = frame.world(0, post.y, 24)
        if (village(level, box, middle)) return no("village")
        for (band in OutlawCamps.get(level).bands) if (!band.slain && near(box, band.pos, CAMP)) return no("outlaws")
        if (level.dimension() == Level.OVERWORLD) {
            for (lair in Lairs.get(level).lairs) if (near(box, lair.pos, LAIR)) return no("lair")
        }
        for (site in FieldBosses.get(level).sites) if (near(box, site.pos, CAMP)) return no("trolls")
        for (roost in DragonRoosts.get(level).roosts) if (near(box, roost.pos, ROOST)) return no("roost")
        HollowThrone.get(level).altar.orElse(null)?.let { if (near(box, it, ROOST)) return no("throne") }

        // The land itself, for the layout this spot will have
        val land = read(level, frame)
        val layout = HomesteadLayout(seedFor(player, post), land)
        for (check in layout.checks()) {
            val corners = corners(level, frame, check.lot, land)
            if (land.wet(check.lot)) return no("wet", Component.translatable("gui.guildmark.homestead.lot.${check.what}"), highlight = corners)
            if (land.stray(check.lot, check.y) > check.most) {
                return no("steep", Component.translatable("gui.guildmark.homestead.lot.${check.what}"), highlight = corners)
            }
        }
        // The gate, the post and the crate stand on firm, dry ground at much one height
        val gate = land.height(0, 0)
        for (x in intArrayOf(PlotShape.POST_X, PlotShape.CRATE_X)) {
            if (land.wet(x, 0) || kotlin.math.abs(land.height(x, 0) - gate) > 2) return no("gate", highlight = listOf(frame.world(x, land.height(x, 0) - 1, 0)))
        }
        // Nothing anyone built
        val built = built(level, frame, land)
        if (built.isNotEmpty()) return no("built", built.size, highlight = built.take(24))
        return Verdict(true, Component.translatable("message.guildmark.homestead.claim_ok"), land = land)
    }

    /**
     * Stakes the homestead if the land will do: the post and the supply crate put down, the corner stakes knocked in with
     * the owner's colour (a dye held in the other hand picks it), the charter used up.
     */
    fun claim(level: ServerLevel, player: ServerPlayer, post: BlockPos, facing: Direction): Verdict {
        val verdict = survey(level, player, post, facing)
        if (!verdict.ok) {
            refuse(level, player, verdict)
            return verdict
        }
        val land = verdict.land ?: return verdict
        return claimOn(level, player, post, facing, land)
    }

    /** Stakes the homestead on [land] (surveyed already, or not at all: the test commands). */
    fun claimOn(level: ServerLevel, player: ServerPlayer, post: BlockPos, facing: Direction, land: PlotLand): Verdict {
        val data = Homesteads.get(level)
        val frame = PlotFrame(post, facing)
        val color = (if (player.offhandItem.item is DyeItem) DyeColor.getColor(player.offhandItem) else null)?.id ?: favourite(player)
        val style = CityStyle.forBiome(level.getBiome(frame.world(0, post.y, 20))).ordinal
        // The post stands where the land is, not where the charter was held
        val at = frame.world(PlotShape.POST_X, land.height(PlotShape.POST_X, 0), 0)
        val h = Homestead(data.nextId, player.uuid, player.name.string, at, facing, seedFor(player, post), style, color, level.gameTime, land)
        data.add(h)
        val road = h.frame.direction(Dir.N)
        level.setBlock(h.post, HomesteadRegistry.CHARTER_POST.get().defaultBlockState().setValue(CharterPostBlock.FACING, road), Block.UPDATE_ALL)
        (level.getBlockEntity(h.post) as? CharterPostBlockEntity)?.set(h.color, h.ownerName, 0)
        level.setBlock(h.crate, HomesteadRegistry.SUPPLY_CRATE.get().defaultBlockState().setValue(GoodsCrateBlock.FACING, road), Block.UPDATE_ALL)
        // The stakes go in at once, with a rap for each
        val plan = data.plan(h, 0)
        val site = HomesteadSite(level, h, plan)
        for (i in 0 until site.total) site.lay(i, fx = true, free = true)
        h.build = Build(0)
        data.changed()
        level.playSound(null, h.post, HomesteadRegistry.CLAIM.get(), SoundSource.PLAYERS, 1.2f, 1f)
        level.sendParticles(ParticleTypes.END_ROD, h.post.x + 0.5, h.post.y + 1.4, h.post.z + 0.5, 30, 0.3, 0.8, 0.3, 0.05)
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, h.post.x + 0.5, h.post.y + 0.8, h.post.z + 0.5, 12, 0.6, 0.4, 0.6, 0.0)
        player.sendSystemMessage(Component.translatable("message.guildmark.homestead.claimed").withStyle(ChatFormatting.GOLD))
        HomesteadEvent.post(HomesteadEvent.Claimed(level, h))
        HomesteadNet.syncAll(level.server)
        return Verdict(true, Component.translatable("message.guildmark.homestead.claim_ok"), land = land)
    }

    /** Tells [player] why not: the reason over the hotbar, a polite shake of the head, the places at fault picked out. */
    fun refuse(level: ServerLevel, player: ServerPlayer, verdict: Verdict) {
        player.sendOverlayMessage(verdict.reason.copy().withStyle(ChatFormatting.RED))
        level.playSound(null, player.blockPosition(), HomesteadRegistry.REFUSE.get(), SoundSource.PLAYERS, 0.8f, 1f)
        HomesteadNet.verdict(player, verdict, null, null)
    }

    /**
     * The homestead's seed (which way its farm buildings lie, the trees, the beasts): the same for the same player and
     * spot, so the survey judges the very layout the claim will build.
     */
    private fun seedFor(player: ServerPlayer, post: BlockPos): Long {
        var s = player.uuid.mostSignificantBits xor (player.uuid.leastSignificantBits * 31) xor (post.x.toLong() shl 20) xor post.z.toLong()
        s = (s xor (s ushr 33)) * -0xae502812aa7333L
        return s xor (s ushr 29)
    }

    /** A colour for someone who held no dye: theirs every time. */
    private fun favourite(player: ServerPlayer): Int {
        val bright = intArrayOf(14, 11, 13, 4, 1, 10, 9, 3, 5, 2, 6, 12)
        return bright[Math.floorMod(player.uuid.hashCode(), bright.size)]
    }

    /**
     * The ground of each column of the plot as it lies: from the top of whatever stands there down through air, water,
     * plants and trees to the land, and whether there was water over it.
     */
    fun read(level: ServerLevel, frame: PlotFrame): PlotLand = PlotLand.read { x, z ->
        val wx = frame.worldX(x, z)
        val wz = frame.worldZ(x, z)
        val top = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz)
        val cursor = BlockPos.MutableBlockPos(wx, top - 1, wz)
        var wet = false
        var n = 0
        while (n++ < 96 && cursor.y > level.minY) {
            val state = level.getBlockState(cursor)
            if (!WorldCanvas.loose(state)) break
            if (!state.fluidState.isEmpty) wet = true
            cursor.move(Direction.DOWN)
        }
        (cursor.y + 1) to wet
    }

    /** Things someone made on the plot's land (anything not the wild), nearest the post first. */
    private fun built(level: ServerLevel, frame: PlotFrame, land: PlotLand): List<BlockPos> {
        val found = ArrayList<BlockPos>()
        val r = PlotShape.RESERVED
        val cursor = BlockPos.MutableBlockPos()
        for (x in r.x0..r.x1) for (z in r.z0..r.z1) {
            val wx = frame.worldX(x, z)
            val wz = frame.worldZ(x, z)
            val ground = land.height(x, z)
            val top = max(ground + 1, level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz))
            for (y in ground - 3 until minOf(top, ground + 24)) {
                cursor.set(wx, y, wz)
                val state = level.getBlockState(cursor)
                if (state.isAir || HomesteadSite.wild(state)) continue
                found.add(cursor.immutable())
                break
            }
        }
        val post = frame.post
        return found.sortedBy { it.distSqr(post) }
    }

    /** A village nearby: its bell in a loaded chunk within reach, or any of its streets on the plot. */
    private fun village(level: ServerLevel, box: BoundingBox, middle: BlockPos): Boolean {
        val r = VILLAGE + 24
        for (cx in ((middle.x - r) shr 4)..((middle.x + r) shr 4)) for (cz in ((middle.z - r) shr 4)..((middle.z + r) shr 4)) {
            if (level.chunkSource.getChunkNow(cx, cz) == null) continue
            val bells = level.poiManager.getInChunk({ it.`is`(PoiTypes.MEETING) }, ChunkPos(cx, cz), PoiManager.Occupancy.ANY)
            if (bells.anyMatch { near(box, it.pos, VILLAGE) }) return true
        }
        for (x in intArrayOf(box.minX(), (box.minX() + box.maxX()) / 2, box.maxX())) for (z in intArrayOf(box.minZ(), (box.minZ() + box.maxZ()) / 2, box.maxZ())) {
            val pos = BlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), z)
            if (level.structureManager().getStructureWithPieceAt(pos, StructureTags.VILLAGE).isValid) return true
        }
        return false
    }

    /** Whether [pos] is within [margin] of [box], across the ground. */
    private fun near(box: BoundingBox, pos: BlockPos, margin: Int): Boolean =
        pos.x >= box.minX() - margin && pos.x <= box.maxX() + margin && pos.z >= box.minZ() - margin && pos.z <= box.maxZ() + margin

    private fun inflate(box: BoundingBox, by: Int): BoundingBox =
        BoundingBox(box.minX() - by, box.minY(), box.minZ() - by, box.maxX() + by, box.maxY(), box.maxZ() + by)

    private fun corners(level: ServerLevel, frame: PlotFrame, r: Rect, land: PlotLand): List<BlockPos> =
        listOf(r.x0 to r.z0, r.x1 to r.z0, r.x0 to r.z1, r.x1 to r.z1).map { (x, z) -> frame.world(x, land.height(x, z) - 1, z) }

    /** Points round the edge of [box] on the ground, every few blocks, to show where someone else's land is. */
    private fun outline(level: ServerLevel, box: BoundingBox): List<BlockPos> {
        val out = ArrayList<BlockPos>()
        fun at(x: Int, z: Int) = out.add(BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z))
        var x = box.minX()
        while (x <= box.maxX()) {
            at(x, box.minZ())
            at(x, box.maxZ())
            x += 6
        }
        var z = box.minZ()
        while (z <= box.maxZ()) {
            at(box.minX(), z)
            at(box.maxX(), z)
            z += 6
        }
        return out.take(32)
    }

    /** Dyes by id, for the commands. */
    fun dye(id: Int): DyeColor = DyeColor.byId(id)
}
