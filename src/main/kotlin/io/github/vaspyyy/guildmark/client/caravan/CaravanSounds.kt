package io.github.vaspyyy.guildmark.client.caravan

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.caravan.Bodies
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.sounds.SoundEngine
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.phys.Vec3

/**
 * What the caravans sound like, worked out on each client from what it sees (caravan-art.md's sounds): the wheels'
 * roll by the ground under them, the hooves by the ground under each, the horse's own noises on its clips' ticks, and
 * the crew's on theirs (LivingMoments hands the caravan clips' moments here). The server plays only what the records
 * or the fight decide (the band's whistle and whoop, the load smashed, the overturn, a crate set down).
 */
internal object CaravanSounds {
    /** What the wheels and hooves are on: earth (grass, gravel, sand), stone (cobble, brick, a paved road) or planks. */
    enum class Ground(val roll: String, val hoof: String) {
        EARTH("roll", "hoof"),
        STONE("roll_stone", "hoof_stone"),
        WOOD("roll_planks", "hoof_wood"),
    }

    private val STONE_TYPES: Set<SoundType> = setOf(
        SoundType.STONE, SoundType.DEEPSLATE, SoundType.DEEPSLATE_BRICKS, SoundType.DEEPSLATE_TILES, SoundType.POLISHED_DEEPSLATE,
        SoundType.TUFF, SoundType.TUFF_BRICKS, SoundType.POLISHED_TUFF, SoundType.MUD_BRICKS, SoundType.NETHER_BRICKS,
        SoundType.BASALT, SoundType.CALCITE, SoundType.DRIPSTONE_BLOCK, SoundType.PACKED_MUD, SoundType.GILDED_BLACKSTONE,
        SoundType.RESIN_BRICKS, SoundType.METAL, SoundType.IRON, SoundType.COPPER,
    )
    private val WOOD_TYPES: Set<SoundType> = setOf(SoundType.WOOD, SoundType.NETHER_WOOD, SoundType.BAMBOO_WOOD, SoundType.CHERRY_WOOD, SoundType.LADDER, SoundType.SCAFFOLDING)

    /** What's underfoot at [x], [y], [z] (the block a wheel or a hoof is on). */
    fun ground(level: Level, x: Double, y: Double, z: Double): Ground {
        val pos = BlockPos.containing(x, y - 0.15, z)
        var state = level.getBlockState(pos)
        // A carpet of snow or a path's top: what's under it if there's nothing to stand on here
        if (state.isAir || state.getCollisionShape(level, pos).isEmpty) state = level.getBlockState(pos.below())
        val type = state.soundType
        return when {
            type in WOOD_TYPES || state.`is`(BlockTags.PLANKS) || state.`is`(BlockTags.WOODEN_SLABS) || state.`is`(BlockTags.WOODEN_STAIRS) -> Ground.WOOD
            type in STONE_TYPES -> Ground.STONE
            else -> Ground.EARTH
        }
    }

    /** Plays `caravan.[name]` at [at] here, as it happens. */
    fun play(level: Level, at: Vec3, name: String, volume: Float, pitch: Float = 0.95f + level.random.nextFloat() * 0.1f) {
        level.playLocalSound(at.x, at.y, at.z, Bodies.sound(name), SoundSource.NEUTRAL, volume, pitch, false)
    }

    private val townsfolk = HashMap<String, SoundEvent>()

    /** Plays the townsfolk's own `townsfolk.[name]` (a seat taken). */
    private fun townsfolk(level: Level, at: Vec3, name: String, volume: Float) {
        val event = townsfolk.getOrPut(name) { SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "townsfolk.$name")) }
        level.playLocalSound(at.x, at.y, at.z, event, SoundSource.NEUTRAL, volume, 0.95f + level.random.nextFloat() * 0.1f, false)
    }

    /**
     * A moment of one of the crew's clips (LivingSchedule.MOMENTS; LivingMoments.fire hands over the ones it has no
     * case for): [moment] of [clip], played by [p], happening at [at].
     */
    fun crew(p: Townsperson, clip: String, moment: String, at: Vec3) {
        val level = p.level()
        when (clip) {
            "drive_urge" -> when (moment) {
                "click" -> play(level, at, "click", 0.5f)
                "flick" -> play(level, at, "reins_flick", 0.5f)
            }
            "drive_brake" -> when (moment) {
                "haul" -> play(level, at, "reins_haul", 0.45f)
                "brake" -> play(level, at, "brake", 0.7f)
            }
            "jump_down" -> when (moment) {
                "push" -> play(level, at, "creak", 0.3f)
                "land" -> play(level, at, "land", 0.6f)
            }
            "climb_up" -> when (moment) {
                "grab" -> play(level, at, "grab", 0.5f)
                "step" -> play(level, at, "grab", 0.4f, 0.8f)
                "sit" -> townsfolk(level, at, "seat", 0.45f)
            }
            "cut_loose" -> when (moment) {
                "saw" -> play(level, at, "knife_saw", 0.5f)
                "cut" -> play(level, at, "trace_cut", 0.7f)
                "sheathe" -> play(level, at, "sheathe", 0.45f)
                "slap" -> play(level, at, "slap", 0.8f)
            }
            "hand_down" -> when (moment) {
                "lift" -> play(level, at, "crate_lift", 0.5f)
                "handoff" -> play(level, at, "handoff", 0.5f)
            }
            "cower" -> if (moment == "flinch") play(level, at, "gasp", 0.45f)
            "catch_breath" -> when (moment) {
                "gasp" -> play(level, at, "pant", 0.5f)
                "cough" -> play(level, at, "cough", 0.5f)
            }
            "recount" -> when (moment) {
                "point", "sweep" -> play(level, at, "swish", 0.35f)
                "rush" -> play(level, at, "stomp", 0.5f)
                "clap" -> play(level, at, "smack", 0.5f)
            }
            "wounded" -> if (moment == "wince") play(level, at, "wince", 0.45f)
            "grumble" -> when (moment) {
                "huff" -> play(level, at, "huff", 0.4f)
                "sweep" -> play(level, at, "swish", 0.35f)
            }
        }
    }

    /**
     * A moment of the horse's clips (DraughtHorseMesh.MOMENTS): a hoof down (on whatever it's on, louder at the
     * bolt), a stamp, a snort, a neigh (hurt, if it's been struck), coming down on the ground, grass torn up.
     */
    fun horse(level: Level, at: Vec3, clip: String, what: String, ground: Ground, weight: Float) {
        val w = weight.coerceIn(0.3f, 1f)
        when (what) {
            "hoof" -> play(level, at, ground.hoof, (if (clip == "bolt") 0.62f else 0.42f) * w, 0.9f + level.random.nextFloat() * 0.18f)
            "stamp" -> play(level, at, "horse_stamp", 0.55f * w)
            "snort" -> play(level, at, "horse_snort", 0.5f * w)
            "neigh" -> play(level, at, if (clip == "hit" || clip == "collapse") "horse_hurt" else "horse_neigh", 0.85f * w)
            "thud" -> play(level, at, "horse_thud", 0.9f * w, 0.85f + level.random.nextFloat() * 0.15f)
            "tear" -> play(level, at, "horse_tear", 0.4f * w)
        }
    }

    // ---- The wheels' roll ----

    /**
     * Keeps [loop] going for [follow] (or starts one of `caravan.[name]`), to ease toward [volume] and [pitch]; null
     * if the sound engine won't have it.
     */
    fun keep(loop: Loop?, name: String, follow: Entity): Loop? {
        val minecraft = Minecraft.getInstance()
        if (loop != null && !loop.isStopped && (minecraft.soundManager.isActive(loop) || loop.age < START_GRACE)) return loop
        val fresh = Loop(name, Bodies.sound(name), follow)
        return if (minecraft.soundManager.play(fresh) == SoundEngine.PlayResult.NOT_STARTED) null else fresh
    }

    /**
     * A sound going round and round under a wagon, following it: easing toward [target] and [pitchTo]; once it's sat
     * silent a while, or the wagon's gone, it stops, to be started afresh when wanted.
     */
    class Loop(val name: String, sound: SoundEvent, private val follow: Entity) :
        AbstractTickableSoundInstance(sound, SoundSource.NEUTRAL, SoundInstance.createUnseededRandom()) {
        var target = 0f
        var pitchTo = 1f
        var age = 0
        private var silent = 0

        /** Stops it at once (the wagon gone from this client). */
        fun stopNow() = stop()

        init {
            looping = true
            delay = 0
            volume = 0.001f
            attenuation = SoundInstance.Attenuation.LINEAR
            x = follow.x
            y = follow.y + 0.6
            z = follow.z
        }

        override fun canStartSilent(): Boolean = true

        override fun tick() {
            age++
            if (follow.isRemoved) {
                stop()
                return
            }
            x = follow.x
            y = follow.y + 0.6
            z = follow.z
            volume += (target - volume) * if (target > volume) 0.18f else 0.12f
            pitch += (pitchTo - pitch) * 0.12f
            silent = if (volume < 0.004f) silent + 1 else 0
            if (silent > STOP_AFTER) stop()
        }
    }

    /** Ticks a loop gets to be heard of by the sound engine before it's taken for lost; ticks silent before it stops. */
    private const val START_GRACE = 10
    private const val STOP_AFTER = 60
}
