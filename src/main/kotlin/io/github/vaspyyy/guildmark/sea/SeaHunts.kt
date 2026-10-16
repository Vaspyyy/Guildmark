package io.github.vaspyyy.guildmark.sea

import io.github.vaspyyy.guildmark.quest.QuestGenerator
import io.github.vaspyyy.guildmark.quest.QuestType
import io.github.vaspyyy.guildmark.registry.ModDataComponents
import io.github.vaspyyy.guildmark.ship.Cog
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BiomeTags
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.world.Difficulty
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.vehicle.boat.AbstractBoat
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Decides when the sea's hunters come, once a second for each player on the overworld.
 *
 * Gnashfins come for anyone out on the open sea (aboard a ship or a boat, or swimming), far from any shore: often in a
 * storm or after dark, now and then by day. A pack closes in from out of sight, circles, and takes it in turns to leap
 * at whoever's aboard; half of it killed, the rest scatter. A ship with a Maelgrasp's beak at her stem, they leave be.
 *
 * The Maelgrasp comes only for a ship that's been out over the deeps for a while, far from land, at night or in a
 * storm: at most once a day for a ship, and not again for days for anyone who's met it.
 */
object SeaHunts {
    /** A pack of Gnashfins on the hunt, keeping count of its own. */
    class Pack(val id: UUID, val victim: UUID, val size: Int, val since: Long) {
        /** The entity ids of those still in it. */
        val members = HashSet<Int>()
        /** Those of it at a ship's planks just now. */
        private val gnawers = HashSet<Int>()
        var lost = 0
        private var nextStrike = 0L

        /** Whether the one with entity id [fish] may go and gnaw at a ship's planks (two at once, at most). */
        fun mayGnaw(fish: Int): Boolean {
            if (fish in gnawers) return true
            if (gnawers.size >= MAX_GNAWERS) return false
            gnawers.add(fish)
            return true
        }

        fun doneGnawing(fish: Int) {
            gnawers.remove(fish)
        }

        /** Whether one of the pack may leap now: they take it in turns, a few heartbeats apart. */
        fun mayStrike(now: Long, random: RandomSource): Boolean {
            if (now < nextStrike) return false
            nextStrike = now + STRIKE_GAP + random.nextInt(STRIKE_SPREAD)
            return true
        }
    }

    private val packs = HashMap<UUID, Pack>()
    /** When each player may next be found by a pack. */
    private val nextPack = HashMap<UUID, Long>()
    /** For each ship (by its UUID): how long she's been out over the deeps, and the day the Maelgrasp last thought of her. */
    private val deepTicks = HashMap<UUID, Int>()
    private val triedOn = HashMap<UUID, Long>()

    fun pack(id: UUID?): Pack? = id?.let { packs[it] }

    fun clear() {
        packs.clear()
        nextPack.clear()
        deepTicks.clear()
        triedOn.clear()
    }

    /** Whether [ship] lies still in the water (held, at anchor, or barely moving), so they can get at her planks. */
    fun lyingStill(ship: Cog): Boolean {
        if (ship.held || ship.anchored) return true
        val dx = ship.x - ship.xo
        val dz = ship.z - ship.zo
        return sqrt(dx * dx + dz * dz) < STILL_SPEED
    }

    /** One of [packId]'s was killed: with half the pack gone, the rest of it scatter. */
    fun lost(level: ServerLevel, packId: UUID?, fish: Gnashfin) {
        val pack = pack(packId) ?: return
        pack.members.remove(fish.id)
        pack.lost++
        if (pack.lost * 2 >= pack.size) scatter(level, pack)
    }

    /** Every Gnashfin hunting anyone aboard [ship] flees (the Maelgrasp is coming up under her). */
    fun scatterFrom(level: ServerLevel, ship: Cog) {
        val crew = Seas.crew(ship).map { it.uuid }.toSet()
        for (pack in packs.values.toList()) if (pack.victim in crew) scatter(level, pack)
    }

    private fun scatter(level: ServerLevel, pack: Pack) {
        for (id in pack.members.toList()) (level.getEntity(id) as? Gnashfin)?.flee()
        packs.remove(pack.id)
    }

    // ---- Once a second ----

    fun tick(level: ServerLevel) {
        if (level.gameTime % CHECK_EVERY != 0L) return
        prune(level)
        if (!Seas.enabled() || level.difficulty == Difficulty.PEACEFUL) return
        val now = level.gameTime
        for (player in level.players()) {
            if (!player.isAlive || player.isSpectator) continue
            tryPack(level, player, now)
            tryMaelgrasp(level, player, now)
        }
        // A ship nobody's aboard any more is forgotten
        if (now % (CHECK_EVERY * 60L) == 0L) {
            val aboard = level.players().mapNotNull { (it.vehicle as? Cog)?.uuid }.toSet()
            deepTicks.keys.retainAll(aboard)
        }
    }

    /** Packs that have all fled or died, or have hunted long enough, are let go. */
    private fun prune(level: ServerLevel) {
        val it = packs.values.iterator()
        while (it.hasNext()) {
            val pack = it.next()
            pack.members.removeIf { id -> (level.getEntity(id) as? Gnashfin)?.isAlive != true }
            if (pack.members.isEmpty()) {
                it.remove()
            } else if (level.gameTime - pack.since > PACK_LIFE) {
                for (id in pack.members) (level.getEntity(id) as? Gnashfin)?.flee()
                it.remove()
            }
        }
    }

    // ---- Gnashfins ----

    private fun tryPack(level: ServerLevel, player: Player, now: Long) {
        val vehicle = player.vehicle
        val ship = vehicle as? Cog
        val atSea = ship != null || vehicle is AbstractBoat || (vehicle == null && player.isInWater)
        if (!atSea) return
        if ((nextPack[player.uuid] ?: 0L) > now) return
        if (ship != null && ship.figurehead) return
        // One pack at a time for anyone aboard the same ship
        val hunted = packs.values.map { it.victim }.toSet()
        if (player.uuid in hunted) return
        if (ship != null && Seas.crew(ship).any { it.uuid in hunted }) return
        val random = level.random
        var chance = when {
            level.isThundering -> THUNDER_CHANCE
            level.isDarkOutside -> NIGHT_CHANCE
            level.isRaining -> RAIN_CHANCE
            else -> DAY_CHANCE
        }
        // Out after them on a contract, they find you the sooner (as if you knew where to look)
        if (onTheirTrail(player, now)) chance = (chance / CONTRACT_LURE).coerceAtLeast(1)
        if (random.nextInt(chance) != 0) return
        val pos = player.blockPosition()
        if (!level.getBiome(pos).`is`(BiomeTags.IS_OCEAN)) return
        if (!openSea(level, player.x, player.z, PACK_OPEN)) return
        val night = level.isDarkOutside || level.isThundering
        val deep = level.getBiome(pos).`is`(BiomeTags.IS_DEEP_OCEAN)
        if (spawnPack(level, player, (3 + random.nextInt(2) + if (night && deep) 1 else 0)) > 0) nextPack[player.uuid] = now + PACK_COOLDOWN
    }

    /** Whether [player] carries a contract to hunt Gnashfins that isn't done or run out. */
    private fun onTheirTrail(player: Player, now: Long): Boolean {
        val gnashfin = EntityType.getKey(SeaRegistry.GNASHFIN.get())
        val inventory = player.inventory
        for (slot in 0 until inventory.containerSize) {
            val stack = inventory.getItem(slot)
            val note = stack.get(ModDataComponents.QUEST_NOTE.get()) ?: continue
            if (note.type != QuestType.HUNT || note.target != gnashfin) continue
            val state = stack.get(ModDataComponents.CONTRACT_STATE.get()) ?: continue
            if (!state.isExpired(now) && state.progress < note.count) return true
        }
        return false
    }

    /** Sends a pack of [count] for [player], closing in from out of sight. Gives how many came. */
    fun spawnPack(level: ServerLevel, player: Player, count: Int): Int {
        val random = level.random
        val packId = UUID.randomUUID()
        val pack = Pack(packId, player.uuid, count, level.gameTime)
        val tier = QuestGenerator.tierAt(player.blockX, player.blockZ)
        // Out of sight in one quarter of the sea round them, a little apart from each other
        val heading = random.nextFloat() * Mth.TWO_PI
        var tries = 0
        while (pack.members.size < count && tries++ < count * 6) {
            val angle = heading + (random.nextFloat() - 0.5f) * 1.2f
            val far = SPAWN_NEAR + random.nextDouble() * (SPAWN_FAR - SPAWN_NEAR)
            val x = player.x + cos(angle) * far
            val z = player.z + sin(angle) * far
            val at = BlockPos.containing(x, player.y, z)
            if (!level.hasChunkAt(at) || !level.isPositionEntityTicking(at)) continue
            val surface = Seas.surface(level, x, z) ?: continue
            if (Seas.depth(level, x, z, surface, SPAWN_DEPTH) < SPAWN_DEPTH) continue
            val fish = SeaRegistry.GNASHFIN.get().create(level, EntitySpawnReason.EVENT) ?: continue
            fish.snapTo(x, surface - 1.5, z, random.nextFloat() * 360f, 0f)
            fish.scaleTo(tier)
            fish.hunt(packId, player)
            if (level.addFreshEntity(fish)) pack.members.add(fish.id)
        }
        if (pack.members.isEmpty()) return 0
        packs[packId] = pack
        return pack.members.size
    }

    // ---- The Maelgrasp ----

    private fun tryMaelgrasp(level: ServerLevel, player: Player, now: Long) {
        val ship = player.vehicle as? Cog ?: return
        // Once a second for the ship, whoever's aboard her
        if (Seas.crew(ship).firstOrNull() !== player) return
        val key = ship.uuid
        val pos = ship.blockPosition()
        if (!level.getBiome(pos).`is`(BiomeTags.IS_DEEP_OCEAN) || !openSea(level, ship.x, ship.z, DEEP_OPEN)) {
            deepTicks.remove(key)
            return
        }
        val out = (deepTicks[key] ?: 0) + CHECK_EVERY
        deepTicks[key] = out
        if (out < DEEP_FOR) return
        if (!level.isDarkOutside && !level.isThundering) return
        val day = Math.floorDiv(level.overworldClockTime, 24000L)
        if (triedOn[key] == day) return
        triedOn[key] = day
        if (Seas.crew(ship).any { metLately(it, now) }) return
        if (level.random.nextFloat() >= MAELGRASP_CHANCE) return
        if (level.getEntitiesOfClass(Maelgrasp::class.java, ship.boundingBox.inflate(64.0)).isNotEmpty()) return
        Maelgrasp.summon(level, ship, true)
    }

    /** Whether [player] met the Maelgrasp less than [MET_COOLDOWN] ago. */
    fun metLately(player: Player, now: Long): Boolean {
        val met = player.getData(SeaRegistry.MAELGRASP_MET)
        return met != Long.MIN_VALUE && now - met in 0 until MET_COOLDOWN
    }

    /**
     * Whether ([x], [z]) is out on open water: water to every side out to [radius] blocks, as far as the loaded world
     * shows (any of it not loaded, it can't be sure, and says not).
     */
    fun openSea(level: ServerLevel, x: Double, z: Double, radius: Double): Boolean {
        for (ring in 1..2) {
            val r = radius * ring / 2.0
            for (i in 0 until 8) {
                val a = i * Mth.TWO_PI / 8f
                val px = x + cos(a) * r
                val pz = z + sin(a) * r
                if (!level.hasChunkAt(BlockPos.containing(px, level.seaLevel.toDouble(), pz))) return false
                if (Seas.surface(level, px, pz) == null) return false
            }
        }
        return true
    }

    private const val CHECK_EVERY = 20
    private const val MAX_GNAWERS = 2
    /** Ticks between one of a pack's leaps and the next, and how much more it may be. */
    private const val STRIKE_GAP = 40
    private const val STRIKE_SPREAD = 50
    /** Blocks a tick: slower than this, a ship lies still. */
    private const val STILL_SPEED = 0.02
    /** One in this many seconds out on the open sea, a pack finds you: in a storm, after dark, in the rain, by day. */
    private const val THUNDER_CHANCE = 40
    private const val NIGHT_CHANCE = 60
    private const val RAIN_CHANCE = 120
    private const val DAY_CHANCE = 600
    /** How much sooner a pack finds someone out on a contract to hunt them. */
    private const val CONTRACT_LURE = 4
    /** Ticks after a pack finds someone before another can. */
    private const val PACK_COOLDOWN = 3600L
    /** Ticks a pack hunts before it gives up. */
    private const val PACK_LIFE = 6000L
    /** Blocks of open water all round that make the open sea, for a pack and for the Maelgrasp. */
    private const val PACK_OPEN = 20.0
    private const val DEEP_OPEN = 48.0
    /** How far out a pack is sent from, and how deep the water must be there. */
    private const val SPAWN_NEAR = 22.0
    private const val SPAWN_FAR = 30.0
    private const val SPAWN_DEPTH = 3
    /** Ticks a ship must have been out over the deeps before the Maelgrasp may come for her. */
    private const val DEEP_FOR = 1200
    private const val MAELGRASP_CHANCE = 0.4f
    /** Ticks after meeting it before the Maelgrasp comes for anyone again (three days). */
    const val MET_COOLDOWN = 72000L
}
