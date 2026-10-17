package io.github.vaspyyy.guildmark.outlaw

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.block.QuestBoardBlock
import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.guild.GuildNews
import io.github.vaspyyy.guildmark.quest.Contracts
import io.github.vaspyyy.guildmark.quest.QuestNote
import io.github.vaspyyy.guildmark.quest.QuestType
import io.github.vaspyyy.guildmark.registry.ModDataComponents
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Towns
import io.github.vaspyyy.guildmark.townsfolk.Townsfolk
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.village.Standing
import io.github.vaspyyy.guildmark.village.VillageNames
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.UUIDUtil
import net.minecraft.core.particles.ItemParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import java.util.UUID
import java.util.WeakHashMap

/** Someone an outlaw band holds: who they are and the village they were taken from. */
class Captive(
    /** Their entity's id, settled before they're put in the camp so a contract can name them from the first. */
    val id: UUID,
    val name: String,
    /** Their village's board (or its bell, if it has none): where they're walked back to. */
    val home: BlockPos,
    val role: Int,
    val look: Int,
    /** Put in the camp yet: they're only put there once someone's near enough to see it. */
    var placed: Boolean = false,
    /** Cut loose and on their way home. */
    var freed: Boolean = false,
) {
    companion object {
        val CODEC: Codec<Captive> = RecordCodecBuilder.create { i ->
            i.group(
                UUIDUtil.CODEC.fieldOf("id").forGetter(Captive::id),
                Codec.STRING.fieldOf("name").forGetter(Captive::name),
                BlockPos.CODEC.fieldOf("home").forGetter(Captive::home),
                Codec.INT.optionalFieldOf("role", 0).forGetter(Captive::role),
                Codec.INT.optionalFieldOf("look", 0).forGetter(Captive::look),
                Codec.BOOL.optionalFieldOf("placed", false).forGetter(Captive::placed),
                Codec.BOOL.optionalFieldOf("freed", false).forGetter(Captive::freed),
            ).apply(i, ::Captive)
        }
    }
}

/**
 * Villagers the outlaw bands carry off in their raids (see Raids) and keep in camp: knelt by a stake beside the
 * captain's seat, wrists bound behind them, calling out to anyone who comes near. Cut them loose and they get to their
 * feet and keep close behind whoever freed them, and once back in their own village they thank them and go back to
 * their lives there. Their village posts a notice asking for them back (see RESCUE contracts), and brings them home
 * makes a friend of the whole village.
 */
object Captives {
    /** The one target a rescue note takes in a pool. */
    val CAPTIVE: Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "captive")

    /** The most a band keeps at once. */
    const val MAX = 2

    /** Where in a camp a captive might be kept, as (blocks right of the fire, blocks towards the captain's tent). */
    private val SPOTS = listOf(-2 to 4, 2 to 4, -3 to 6, 3 to 6, -4 to 3, 4 to 3)

    /** How near a player must be for a captive to call out to them. */
    private const val PLEAD_RANGE = 12.0

    /** How near their village's board a freed captive must come to be home. */
    private const val HOME_RANGE = 14.0

    /** Out of sight and further behind than this, a freed captive catches up with their rescuer at once. */
    private const val CATCH_UP = 40.0

    /** How far from a board its own villagers can be posted for. */
    private const val NOTICE_RANGE = 64.0

    /** What bringing someone home does for the rescuer's standing with their village. */
    private const val STANDING_GAIN = 30

    private val nextPlead = WeakHashMap<Townsperson, Long>()

    /** A captive's notice as a board pins it: the band that holds them, who they are and whose band it is. */
    class Held(val site: Int, val name: String, val captain: Int)

    // ---- Being taken ----

    /** [band] carries off [name] from the village at [home]; they're kept in its camp until someone frees them. */
    fun take(level: ServerLevel, band: OutlawBand, name: String, home: BlockPos, role: TownRole, look: Int): Captive {
        val captive = Captive(UUID.randomUUID(), name, home, role.ordinal, look)
        band.captives.add(captive)
        OutlawCamps.get(level).setDirty()
        if (band.built && level.isPositionEntityTicking(band.pos)) place(level, band, captive)
        return captive
    }

    /** What a villager would be as one of the townsfolk, by their profession's id. */
    fun roleFor(profession: Identifier?): TownRole {
        val path = profession?.path ?: return TownRole.CITIZEN
        return when {
            path == "farmer" -> TownRole.FARMER
            path == "fisherman" -> TownRole.FISHER
            path.endsWith("smith") || path == "armorer" -> TownRole.SMITH
            path == "butcher" -> TownRole.BAKER
            else -> TownRole.CITIZEN
        }
    }

    /** Puts anyone [band] holds who isn't in the camp yet there; called while someone's near the camp. */
    fun tend(level: ServerLevel, band: OutlawBand) {
        for (captive in band.captives) if (!captive.placed && !captive.freed) place(level, band, captive)
    }

    /** Kneels [captive] by a stake in [band]'s camp, facing the fire. */
    private fun place(level: ServerLevel, band: OutlawBand, captive: Captive): Boolean {
        val fire = band.pos
        val facing = band.facing
        val taken = band.captives.filter { it.placed && it !== captive }.size
        val spot = SPOTS.drop(taken).asSequence()
            .map { (a, b) -> fire.relative(facing.clockWise, a).relative(facing, b) }
            .firstOrNull { open(level, it) } ?: return false
        // The stake stands behind them, away from the fire
        val away = Direction.getApproximateNearest((spot.x - fire.x).toDouble(), 0.0, (spot.z - fire.z).toDouble())
        val stake = spot.relative(away)
        if (level.getBlockState(stake).canBeReplaced() && level.getFluidState(stake).isEmpty) {
            level.setBlock(stake, Blocks.SPRUCE_FENCE.defaultBlockState(), 3)
        }
        val toFire = away.opposite
        val person = Townsfolk.create(level, spot, TownRole.byId(captive.role), captive.look, toFire.toYRot(), EntitySpawnReason.EVENT) ?: return false
        person.setUUID(captive.id)
        person.captiveOf = band.id
        person.home = captive.home
        person.post = spot
        person.postFacing = toFire
        person.customName = Component.literal(captive.name)
        if (!level.addFreshEntity(person)) return false
        person.act(Activity.BOUND)
        captive.placed = true
        OutlawCamps.get(level).setDirty()
        return true
    }

    /** Room for someone to kneel at [pos]: open air there and above, solid ground beneath. */
    private fun open(level: ServerLevel, pos: BlockPos): Boolean =
        level.getBlockState(pos).canBeReplaced() && level.getFluidState(pos).isEmpty &&
            level.getBlockState(pos.above()).canBeReplaced() && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)

    // ---- Held, freed and home again ----

    /** Each tick, in place of their day's round: knelt and bound, or on their way home. */
    fun tick(p: Townsperson) {
        val level = p.level() as? ServerLevel ?: return
        if (p.rescuer == null) bound(level, p) else homeward(level, p)
    }

    private fun bound(level: ServerLevel, p: Townsperson) {
        p.navigation.stop()
        p.post?.let { post ->
            if (p.distanceToSqr(post.x + 0.5, post.y.toDouble(), post.z + 0.5) > 0.25) p.snapTo(post.x + 0.5, post.y.toDouble(), post.z + 0.5, p.yRot, 0f)
        }
        p.postFacing?.let { if (p.tickCount % 40 == 0) p.face(it) }
        // Calling out to whoever's come near
        val player = level.getNearestPlayer(p, PLEAD_RANGE)?.takeIf { !it.isSpectator && p.hasLineOfSight(it) }
        if (player != null) p.lookAt(player)
        if (p.activity == Activity.PLEAD && !p.actDone) return
        if (player != null && level.gameTime >= (nextPlead[p] ?: 0L)) {
            nextPlead[p] = level.gameTime + 160 + p.random.nextInt(140)
            p.act(Activity.PLEAD)
            p.say("captive_help_${p.random.nextInt(3)}", force = true)
            return
        }
        p.act(Activity.BOUND)
    }

    private fun homeward(level: ServerLevel, p: Townsperson) {
        val home = p.home ?: return release(level, p)
        when (p.activity) {
            // Finding their feet; once up, a word and they're off
            Activity.FREED -> {
                p.navigation.stop()
                if (!p.actDone) return
                p.say("captive_freed_1", force = true)
                p.act(Activity.NONE)
            }
            // Home: thanks, and back to their own life
            Activity.THANK -> {
                p.navigation.stop()
                if (p.actDone) release(level, p)
                return
            }
            else -> {}
        }
        if (horizontal(p.x, p.z, home) < HOME_RANGE) return homecoming(level, p, home)
        val rescuer = p.rescuer?.let { level.getPlayerByUUID(it) }?.takeIf { it.isAlive && !it.isSpectator }
        if (rescuer == null) {
            // Their rescuer's gone off (or out of the world): they wait where they are
            p.navigation.stop()
            p.act(Activity.NONE)
            return
        }
        val distance = p.distanceTo(rescuer)
        when {
            distance > CATCH_UP && !p.hasLineOfSight(rescuer) -> catchUp(level, p, rescuer)
            distance > 3.5 -> if (p.navigation.isDone || p.tickCount % 20 == 0) p.navigation.moveTo(rescuer, if (distance > 10) 1.0 else 0.75)
            else -> {
                p.navigation.stop()
                p.lookAt(rescuer)
            }
        }
        if (p.activity != Activity.NONE) p.act(Activity.NONE)
    }

    /** Fallen far behind out of sight: they turn up on the ground a few steps behind [player]. */
    private fun catchUp(level: ServerLevel, p: Townsperson, player: Player) {
        val behind = player.blockPosition().relative(player.direction.opposite, 3)
        for (dy in 3 downTo -4) {
            val pos = behind.above(dy)
            if (open(level, pos)) {
                p.navigation.stop()
                p.snapTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, player.yRot, 0f)
                return
            }
        }
    }

    /** [player] uses [p]: a captive is cut loose; a freed one has a word for them. */
    fun interact(level: ServerLevel, player: ServerPlayer, p: Townsperson) {
        if (p.rescuer != null) {
            p.lookAt(player)
            p.say("captive_freed_1", force = true)
            return
        }
        val home = p.home ?: return
        p.rescuer = player.uuid
        p.post = null
        p.act(Activity.FREED)
        p.lookAt(player)
        p.say("captive_freed_0", force = true)
        level.playSound(null, p.blockPosition(), SoundEvents.LEAD_BREAK, SoundSource.NEUTRAL, 1f, 0.9f)
        level.sendParticles(ItemParticleOption(ParticleTypes.ITEM, Items.STRING), p.x, p.y + 0.7, p.z, 10, 0.25, 0.2, 0.25, 0.05)
        record(level, p)?.freed = true
        OutlawCamps.get(level).setDirty()
        // Every contract on them now points home
        for (stack in contracts(player, p.uuid)) {
            val state = stack.get(ModDataComponents.CONTRACT_STATE.get()) ?: continue
            Contracts.retarget(stack, state.copy(destination = home))
        }
        player.sendOverlayMessage(Component.translatable("message.guildmark.captive_freed", p.name, villageName(level, home)))
    }

    /** Back in their village: thanks for whoever brought them, one contract on them done, and word goes round. */
    private fun homecoming(level: ServerLevel, p: Townsperson, home: BlockPos) {
        p.navigation.stop()
        val rescuer = p.rescuer?.let { level.getPlayerByUUID(it) }
        rescuer?.let { p.lookAt(it) }
        p.act(Activity.THANK)
        p.say("captive_home_${p.random.nextInt(2)}", force = true)
        level.playSound(null, p.blockPosition(), SoundEvents.VILLAGER_CELEBRATE, SoundSource.NEUTRAL, 1f, 1f)
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, p.x, p.y + 1.2, p.z, 12, 0.6, 0.5, 0.6, 0.0)
        // One homecoming, one job done: the rescuer's own contract on them if they hold one, else the one running out
        // soonest of those held close by
        val held = level.players().filter { it.distanceToSqr(p) <= 48.0 * 48.0 }.flatMap { player -> contracts(player, p.uuid).map { player to it } }
        held.minWithOrNull(compareBy({ it.first.uuid != p.rescuer }, { it.second.get(ModDataComponents.CONTRACT_STATE.get())?.deadline ?: Long.MAX_VALUE }))
            ?.let { (player, stack) ->
                val note = stack.get(ModDataComponents.QUEST_NOTE.get()) ?: return@let
                Contracts.setProgress(stack, note.count)
                player.sendOverlayMessage(Component.translatable("message.guildmark.rescue_done", p.name))
            }
        if (rescuer != null && level.getBlockState(home).block is QuestBoardBlock) Standing.add(level, home, rescuer, STANDING_GAIN)
        GuildNews.add(level, "news.guildmark.outlaw.rescued", p.name.string, villageName(level, home).string)
    }

    /** Home and thanked: the band's hold on them is over and they live in their village from now on. */
    private fun release(level: ServerLevel, p: Townsperson) {
        forget(level, p)
        p.captiveOf = -1
        p.rescuer = null
        p.town = p.home?.let { Towns.at(level, it) }
        p.act(Activity.NONE)
    }

    /** A captive died: the band no longer holds them, and anyone sent for them hears of it. */
    fun onDeath(level: ServerLevel, p: Townsperson) {
        forget(level, p)
        for (player in level.players()) {
            if (contracts(player, p.uuid).isNotEmpty()) player.sendSystemMessage(Component.translatable("message.guildmark.captive_lost", p.name))
        }
    }

    private fun record(level: ServerLevel, p: Townsperson): Captive? =
        OutlawCamps.get(level).band(p.captiveOf)?.captives?.firstOrNull { it.id == p.uuid }

    private fun forget(level: ServerLevel, p: Townsperson) {
        val camps = OutlawCamps.get(level)
        camps.band(p.captiveOf)?.captives?.removeIf { it.id == p.uuid }
        camps.setDirty()
    }

    // ---- Notices and contracts ----

    /** The people taken from the village of the board at [board], whom bands still hold. */
    fun held(level: ServerLevel, board: BlockPos): List<Held> {
        if (level.dimension() != Level.OVERWORLD || !Outlaws.enabled()) return listOf()
        return OutlawCamps.get(level).bands.filter { it.built && !it.slain }.flatMap { band ->
            band.captives.filter { !it.freed && horizontal(board.x + 0.5, board.z + 0.5, it.home) < NOTICE_RANGE }.map { Held(band.id, it.name, band.name) }
        }
    }

    /** [note] made out for [held]: who they are and whose band holds them. */
    fun dress(note: QuestNote, held: Held): QuestNote =
        note.copy(subject = held.name, site = held.site, story = "quest.guildmark.held_by|#${Outlaws.NAME_KEY}.${held.captain}")

    /** A rescue note on someone no longer held: freed, home, dead, or their band broken. */
    fun stale(level: ServerLevel, note: QuestNote): Boolean {
        if (note.type != QuestType.RESCUE || note.site < 0 || level.dimension() != Level.OVERWORLD) return false
        val band = OutlawCamps.get(level).band(note.site) ?: return true
        return band.slain || band.captives.none { it.name == note.subject && !it.freed }
    }

    /**
     * The band and captive [note] names, while they're still held; a note naming no one (one made for testing) goes
     * after the nearest captive held from the village of [board], or failing that anyone held anywhere near.
     */
    fun quarry(level: ServerLevel, board: BlockPos, note: QuestNote): Pair<OutlawBand, Captive>? {
        if (level.dimension() != Level.OVERWORLD) return null
        val camps = OutlawCamps.get(level)
        if (note.site >= 0) {
            val band = camps.band(note.site)?.takeIf { it.built && !it.slain } ?: return null
            val captive = band.captives.firstOrNull { it.name == note.subject && !it.freed } ?: return null
            return band to captive
        }
        val all = camps.bands.filter { it.built && !it.slain }.flatMap { band -> band.captives.filter { !it.freed }.map { band to it } }
        return all.minByOrNull { (_, captive) -> horizontal(board.x + 0.5, board.z + 0.5, captive.home) }
    }

    /** [player]'s open rescue contracts on the captive [id]. */
    fun contracts(player: Player, id: UUID): List<ItemStack> {
        val out = ArrayList<ItemStack>()
        val inventory = player.inventory
        for (slot in 0 until inventory.containerSize) {
            val stack = inventory.getItem(slot)
            val note = stack.get(ModDataComponents.QUEST_NOTE.get()) ?: continue
            if (note.type != QuestType.RESCUE) continue
            val state = stack.get(ModDataComponents.CONTRACT_STATE.get()) ?: continue
            if (state.bound == id && state.progress < note.count && !state.isExpired(player.level().gameTime)) out.add(stack)
        }
        return out
    }

    private fun villageName(level: ServerLevel, home: BlockPos): Component =
        if (level.getBlockState(home).block is QuestBoardBlock) Component.literal(VillageNames.of(home)) else Component.translatable("message.guildmark.captive_village")

    /** The board of the village whose bell is at or near [bell], or the bell itself when it has none. */
    fun homeOf(level: ServerLevel, bell: BlockPos): BlockPos = VillageBoards.villageBoard(level, bell) ?: bell

    private fun horizontal(x: Double, z: Double, pos: BlockPos): Double {
        val dx = x - (pos.x + 0.5)
        val dz = z - (pos.z + 0.5)
        return Math.sqrt(dx * dx + dz * dz)
    }
}
