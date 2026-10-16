package io.github.vaspyyy.guildmark.client.townsfolk

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.magic.MagicRegistry
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsfolk
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent
import net.neoforged.neoforge.common.NeoForge
import java.util.WeakHashMap
import kotlin.math.cos
import kotlin.math.sin

/**
 * The client half of the townsfolk: their renderer, and what their work sounds and looks like. Each clip's
 * moments land on set ticks (the hammer meets the iron on tick 14 of its swing), and the sound and the sparks,
 * flour, dirt or splashes go off on exactly those ticks. Lines are voiced as they're said, and a bard at their
 * lute plays a tune that follows them about.
 */
object TownsfolkClient {
    fun register(modBus: IEventBus) {
        modBus.addListener(EntityRenderersEvent.RegisterRenderers::class.java) { event ->
            event.registerEntityRenderer(Townsfolk.TOWNSPERSON.get(), ::TownsfolkRenderer)
        }
        modBus.addListener(EntityRenderersEvent.RegisterLayerDefinitions::class.java) { event ->
            event.registerLayerDefinition(TownsfolkModel.LAYER, TownsfolkMesh::createBodyLayer)
        }
        modBus.addListener(RegisterParticleProvidersEvent::class.java) { event ->
            event.registerSpriteSet(Townsfolk.FORGE_SPARK.get()) { sprites -> ForgeSparkParticle.Provider(sprites) }
        }
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java) { _ -> tunes.clear() }
    }

    /** The look [p] wears, from the role's looks that art/scripts/townsfolk.py painted. */
    fun lookOf(p: Townsperson): TownsfolkLooks.Look? = TownsfolkLooks.BY_ROLE[p.role.id]?.let { it.getOrNull(p.look) ?: it.firstOrNull() }

    /** The clip [activity] plays for [p]: half the folk talk with one set of gestures, half with another. */
    fun clipName(p: Townsperson, activity: Activity): String = if (activity == Activity.TALK && (p.id and 1) == 1) "talk_b" else activity.clip

    /** Each client tick for each townsperson. */
    fun tick(p: Townsperson) {
        val player = Minecraft.getInstance().player ?: return
        val distance = player.distanceToSqr(p)
        if (distance > HEARD * HEARD) return
        moments(p, p.level())
        if (p.role == TownRole.BARD) tune(p)
    }

    // ---- What the work looks and sounds like ----

    private fun moments(p: Townsperson, level: Level) {
        val activity = p.shownActivity
        val into = p.tickCount - p.shownStart
        if (!activity.loop && into > activity.ticks) return
        val t = if (activity.loop) Math.floorMod(into, activity.ticks) else into
        when (activity) {
            Activity.HAMMER -> if (t == 14) {
                sound(p, "smith.hammer", 1f, 0.92f + p.random.nextFloat() * 0.16f)
                sparks(level, at(p, 0.78, 1.06, 0.125), 9 + p.random.nextInt(7))
            }
            Activity.TEND -> if (t == 12) {
                sound(p, "smith.bellows", 0.7f)
                val forge = at(p, 1.0, 1.05)
                repeat(3) { puff(level, ParticleTypes.FLAME, forge, 0.2, 0.02, 0.04) }
                repeat(2) { puff(level, ParticleTypes.SMOKE, forge, 0.2, 0.05, 0.02) }
            }
            Activity.QUENCH -> {
                val water = at(p, 1.0, 0.95)
                if (t == 20) {
                    sound(p, "smith.quench", 0.9f)
                    repeat(6) { puff(level, ParticleTypes.SPLASH, water, 0.2, 0.1, 0.05) }
                }
                if (t in 20..32) repeat(2) { puff(level, ParticleTypes.CLOUD, water, 0.15, 0.06, 0.03) }
                else if (t in 33..46 && t % 2 == 0) puff(level, ParticleTypes.WHITE_SMOKE, water, 0.15, 0.04, 0.01)
            }
            Activity.KNEAD -> if (t == 8 || t == 22) {
                sound(p, "baker.knead", 0.6f)
                repeat(5) { puff(level, FLOUR, at(p, 0.81, 1.03), 0.25, 0.03, 0.03) }
            }
            Activity.OVEN -> {
                if (t == 26) {
                    sound(p, "baker.oven", 0.8f)
                    repeat(3) { puff(level, ParticleTypes.FLAME, at(p, 0.6, 0.5), 0.12, 0.01, 0.01) }
                }
                if (t in 18..44 && t % 3 == 0) puff(level, ParticleTypes.SMOKE, at(p, 0.55, 0.8), 0.15, 0.04, 0.02)
            }
            Activity.ARRANGE -> if (t == 22) sound(p, "merchant.arrange", 0.5f)
            Activity.HOE -> if (t == 16) {
                sound(p, "farmer.hoe", 0.7f)
                val ground = at(p, 0.81, -0.1, 0.125)
                val state = groundUnder(level, ground)
                repeat(7) { puff(level, BlockParticleOption(ParticleTypes.BLOCK, state), ground.add(0.0, 0.15, 0.0), 0.2, 0.12, 0.05) }
            }
            Activity.HARVEST -> if (t == 20) {
                sound(p, "farmer.harvest", 0.7f)
                val crop = at(p, 0.86, 0.33, 0.06)
                val state = level.getBlockState(BlockPos.containing(crop)).takeUnless { it.isAir } ?: Blocks.WHEAT.defaultBlockState()
                repeat(8) { puff(level, BlockParticleOption(ParticleTypes.BLOCK, state), crop, 0.25, 0.1, 0.05) }
            }
            Activity.CAST -> {
                if (t == 16) sound(p, "fisher.cast", 0.7f)
                if (t == 26) float(p, level)?.let { spot -> repeat(5) { puff(level, ParticleTypes.SPLASH, spot, 0.12, 0.08, 0.04) } }
            }
            Activity.FISH -> if (t == 40 && p.random.nextInt(2) == 0) {
                float(p, level)?.let { spot -> repeat(3) { puff(level, ParticleTypes.FISHING, spot, 0.15, 0.0, 0.02) } }
            }
            Activity.REEL -> if (t == 10) {
                sound(p, "fisher.reel", 0.7f)
                float(p, level)?.let { spot -> repeat(10) { puff(level, ParticleTypes.SPLASH, spot, 0.2, 0.15, 0.06) } }
            }
            Activity.CLAP -> if (t == 6 || t == 12 || t == 18) sound(p, "clap", 0.6f)
            Activity.DRINK, Activity.SIT_DRINK -> if (t == 38) sound(p, "tavern.sip", 0.45f, voicePitch(p))
            Activity.CHEER -> if (t == 12) sound(p, "tavern.clink", 0.7f)
            Activity.POUR -> if (t == 14) sound(p, "tavern.pour", 0.6f)
            Activity.SWEEP -> if (t == 8 || t == 23) {
                sound(p, "sweep", 0.45f)
                val floor = at(p, 0.9, 0.05, if (t == 8) 0.3 else -0.3)
                val state = groundUnder(level, floor.add(0.0, -0.2, 0.0))
                repeat(3) { puff(level, BlockParticleOption(ParticleTypes.BLOCK, state), floor, 0.15, 0.04, 0.03) }
            }
            Activity.STAGGER -> if (t == 30 && p.random.nextInt(2) == 0) sound(p, "hiccup", 0.6f, voicePitch(p))
            Activity.LUTE -> if (t % 8 == 0) {
                // A note rises off the soundboard
                val lute = at(p, 0.45, 0.95, 0.04)
                level.addParticle(ParticleTypes.NOTE, lute.x, lute.y + 0.25, lute.z, p.random.nextInt(25) / 24.0, 0.0, 0.0)
            }
            Activity.READ -> if (t == 88) sound(p, "mage.page", 0.5f)
            Activity.SCRIBE -> if (t == 4 || t == 16 || t == 28) sound(p, "mage.quill", 0.35f)
            Activity.CONJURE -> {
                // Motes gather over the open palm and burst upward
                val palm = at(p, 0.42, 1.15, -0.22)
                if (t in 8..60 && t % 4 == 0) puff(level, ParticleTypes.END_ROD, palm, 0.12, 0.03, 0.01)
                if (t == 40) {
                    sound(p, "mage.conjure", 0.6f)
                    repeat(8) { puff(level, MagicRegistry.ARCANE_MOTE.get(), palm, 0.2, 0.06, 0.03) }
                }
            }
            Activity.SPAR_ATTACK -> if (t == 10 || t == 26) {
                sound(p, "knight.clash", 0.8f)
                sparks(level, at(p, 1.0, 1.25), 3 + p.random.nextInt(3))
            }
            Activity.DRILL -> if (t == 12 || t == 26) {
                sound(p, "knight.post", 0.7f)
                repeat(3) { puff(level, BlockParticleOption(ParticleTypes.BLOCK, Blocks.HAY_BLOCK.defaultBlockState()), at(p, 1.0, 1.1), 0.2, 0.06, 0.04) }
            }
            Activity.SHARPEN -> if (t == 10 || t == 28) {
                sound(p, "knight.whetstone", 0.45f)
                sparks(level, at(p, 0.45, 1.05), 2)
            }
            Activity.DICE -> {
                if (t == 2) sound(p, "thief.shake", 0.45f)
                if (t == 24) sound(p, "thief.dice", 0.5f)
            }
            Activity.COUNT -> if (t == 12 || t == 24 || t == 36) sound(p, "thief.coin", 0.4f)
            Activity.PICK_LOCK -> if (t == 6 || t == 30) sound(p, "thief.lockpick", 0.35f)
            else -> {}
        }
    }

    /**
     * A point [forward] blocks ahead of [p], [up] above their feet and [right] to their right, by the way their
     * body faces (as the clips are drawn: the station a block ahead, its top half a block up).
     */
    private fun at(p: Townsperson, forward: Double, up: Double, right: Double = 0.0): Vec3 {
        val yaw = Math.toRadians(p.yBodyRot.toDouble())
        val scale = if (p.role == TownRole.CHILD) Townsperson.CHILD_SCALE.toDouble() else 1.0
        val f = forward * scale
        val r = right * scale
        return Vec3(p.x - sin(yaw) * f - cos(yaw) * r, p.y + up * scale, p.z + cos(yaw) * f - sin(yaw) * r)
    }

    /** The block the ground at [at] is made of, for clods and dust. */
    private fun groundUnder(level: Level, at: Vec3): BlockState {
        val pos = BlockPos.containing(at)
        for (dy in 0 downTo -1) {
            val state = level.getBlockState(pos.above(dy))
            if (!state.isAir && state.fluidState.isEmpty) return state
        }
        return Blocks.DIRT.defaultBlockState()
    }

    /** Where a fisher's float lies: on the water two to four blocks ahead of them. */
    private fun float(p: Townsperson, level: Level): Vec3? {
        for (reach in doubleArrayOf(3.0, 2.5, 3.5, 2.0, 4.0)) {
            val spot = at(p, reach, 0.0)
            val column = BlockPos.containing(spot)
            for (dy in 0 downTo -3) {
                val pos = column.above(dy)
                val fluid = level.getFluidState(pos)
                if (fluid.`is`(FluidTags.WATER) && !level.getFluidState(pos.above()).`is`(FluidTags.WATER)) {
                    return Vec3(spot.x, pos.y + fluid.getHeight(level, pos).toDouble(), spot.z)
                }
            }
        }
        return null
    }

    private fun sparks(level: Level, at: Vec3, count: Int) {
        val random = level.random
        repeat(count) {
            level.addParticle(
                Townsfolk.FORGE_SPARK.get(), at.x, at.y, at.z,
                (random.nextDouble() - 0.5) * 0.18, 0.05 + random.nextDouble() * 0.16, (random.nextDouble() - 0.5) * 0.18,
            )
        }
    }

    /** One particle near [at], scattered by up to [spread], rising at about [rise] and drifting by up to [drift]. */
    private fun puff(level: Level, type: ParticleOptions, at: Vec3, spread: Double, rise: Double, drift: Double) {
        val random = level.random
        level.addParticle(
            type,
            at.x + (random.nextDouble() - 0.5) * spread, at.y + (random.nextDouble() - 0.5) * spread * 0.5, at.z + (random.nextDouble() - 0.5) * spread,
            (random.nextDouble() - 0.5) * drift, rise * (0.6 + random.nextDouble() * 0.8), (random.nextDouble() - 0.5) * drift,
        )
    }

    private fun sound(p: Townsperson, name: String, volume: Float, pitch: Float = 0.95f + p.random.nextFloat() * 0.1f) {
        Minecraft.getInstance().soundManager.play(
            SimpleSoundInstance(
                id(name), SoundSource.NEUTRAL, volume, pitch, SoundInstance.createUnseededRandom(),
                false, 0, SoundInstance.Attenuation.LINEAR, p.x, p.y + 1.0, p.z, false,
            ),
        )
    }

    private fun id(name: String): Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "townsfolk.$name")

    // ---- Voices ----

    /** How high [p]'s voice sits: everyone a little different, the same each time. */
    private fun voicePitch(p: Townsperson): Float = 0.92f + Math.floorMod(p.uuid.leastSignificantBits, 17L) / 100f

    /**
     * Voices the line [p] has just said. Old men say what only the men's voice has in it a little lower, and a
     * child calling out what children have no line for borrows a woman's voice, higher.
     */
    fun speak(p: Townsperson, line: String) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        if (player.distanceToSqr(p) > HEARD * HEARD) return
        val timbre = lookOf(p)?.timbre ?: if (p.role == TownRole.CHILD) "child" else "man"
        val sounds = minecraft.soundManager
        var pitch = voicePitch(p)
        var voice = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "townsfolk.voice.$timbre.$line")
        if (sounds.getSoundEvent(voice) == null) {
            val stand = when (timbre) {
                "old" -> "man".also { pitch *= 0.9f }
                "child" -> "woman".also { pitch *= 1.3f }
                else -> return
            }
            voice = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "townsfolk.voice.$stand.$line")
            if (sounds.getSoundEvent(voice) == null) return
        }
        sounds.play(Follow(voice, p, SoundSource.NEUTRAL, 1f, pitch))
    }

    // ---- The bard's tunes ----

    private val tunes = WeakHashMap<Townsperson, Follow>()

    /** While a bard plays their lute a tune plays with them, one after another with a breath between. */
    private fun tune(p: Townsperson) {
        val playing = tunes[p]
        if (playing != null && !playing.done(p.tickCount)) return
        if (p.shownActivity != Activity.LUTE) return
        if (playing != null && p.tickCount - playing.endedAt(p.tickCount) < TUNE_GAP) return
        val tune = Follow(id("bard.tune"), p, SoundSource.RECORDS, 0.9f, 1f, whileDoing = Activity.LUTE)
        tune.startedAt = p.tickCount
        tunes[p] = tune
        Minecraft.getInstance().soundManager.play(tune)
    }

    /**
     * A sound that keeps to the one making it: a voice to the speaker's mouth, a tune to the bard's lute. A tune
     * ([whileDoing]) dies away once they stop playing.
     */
    private class Follow(
        sound: Identifier, private val who: Townsperson, source: SoundSource, loudness: Float, pitch: Float,
        private val whileDoing: Activity? = null,
    ) : AbstractTickableSoundInstance(SoundEvent.createVariableRangeEvent(sound), source, SoundInstance.createUnseededRandom()) {
        var startedAt = 0
        private var ended = -1

        init {
            volume = loudness
            this.pitch = pitch
            attenuation = SoundInstance.Attenuation.LINEAR
            follow()
        }

        override fun tick() {
            if (who.isRemoved) {
                stop()
                return
            }
            follow()
            if (whileDoing != null && who.shownActivity != whileDoing) {
                volume -= 0.08f
                if (volume <= 0f) stop()
            }
        }

        private fun follow() {
            x = who.x
            y = who.eyeY
            z = who.z
        }

        /** Whether it has finished (it's given a moment to start before that's judged). */
        fun done(now: Int): Boolean {
            if (isStopped || now - startedAt > 40 && !Minecraft.getInstance().soundManager.isActive(this)) {
                if (ended < 0) ended = now
                return true
            }
            return false
        }

        fun endedAt(now: Int): Int = if (ended < 0) now else ended
    }

    /** Farther than this from the player, townsfolk make no sound or show and say nothing aloud. */
    private const val HEARD = 48.0

    /** Ticks between one tune ending and the bard starting the next. */
    private const val TUNE_GAP = 50

    private val FLOUR = DustParticleOptions(0xF4EEDF, 0.9f)
}
