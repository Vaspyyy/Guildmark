package io.github.vaspyyy.guildmark.client.trade

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.trade.TradeSounds
import io.github.vaspyyy.guildmark.trade.carry.Carry
import io.github.vaspyyy.guildmark.trade.carry.LadenState
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap
import net.minecraft.client.Minecraft
import net.minecraft.client.entity.ClientAvatarEntity
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.util.context.ContextKey
import net.minecraft.world.entity.Avatar
import net.minecraft.world.entity.LivingEntity
import net.neoforged.neoforge.client.renderstate.AvatarRenderStateModifier
import kotlin.math.floor
import kotlin.math.min

/**
 * How one trader's pack stands on their back at this moment, as the layer draws it (build part D1): which of the three packs, how far it
 * has come up onto the back (settling a little past full when it has just been put on), and where its weight is swinging to.
 */
internal class LadenFrame(
    val level: Int,
    val grow: Float,
    val pitch: Float,
    val roll: Float,
    val bounce: Float,
    val dangle: Float,
    val sprint: Float,
)

/**
 * What this client keeps of one trader's pack, worked out only while they are drawn and stepped once for each tick that has passed since:
 * the pack coming up onto the back and going down off it (or being swapped for the next size with a pop), and its weight swinging on the
 * shoulders as they speed up, slow down, turn, jump and land. The numbers the stride itself gives (the sway of the hips, the lift of each
 * step, the rattle of a sprint) are worked out by the layer when it draws, from the walk the body is in.
 */
internal class LadenLook : Look() {
    private val motion = Motion()
    private val pitch = Hinge(PULL, DRAG, 0.42f)
    private val roll = Hinge(PULL, DRAG, 0.36f)
    private val bounce = Hinge(0.5f, 0.4f, 2.5f)
    private val dangle = Hinge(0.14f, 0.07f, 0.9f)

    /** The pack on show (a level 1 to 3, or 0 for none) and how far up it is (0 to 1), now and a tick ago. */
    private var shown = 0
    private var appear = 0f
    private var appearO = 0f
    private var leaving = false
    private var sprint = 0f
    private var sprintO = 0f

    /** Steps what is kept of [e] up to this tick; [laden] is what the server has told this client of their load (null for none). */
    fun update(e: LivingEntity, laden: LadenState?) {
        val want = laden?.level ?: 0
        val steps = motion.advance(e)
        if (steps < 0) {
            // Seen for the first time, or again after so long that it is not worth working out: as it stands now, with no arrival to play
            pitch.rest()
            roll.rest()
            bounce.rest()
            dangle.rest()
            shown = want
            appear = if (want > 0) 1f else 0f
            appearO = appear
            leaving = false
            sprint = if (e.isSprinting) 1f else 0f
            sprintO = sprint
            return
        }
        for (i in 0 until steps) step(e, want, i == 0)
    }

    private fun step(e: LivingEntity, want: Int, fresh: Boolean) {
        appearO = appear
        leaving = want == 0
        when {
            want == 0 -> {
                appear -= 1f / VANISH
                if (appear <= 0f) {
                    appear = 0f
                    appearO = 0f
                    shown = 0
                }
            }
            shown == 0 -> {
                shown = want
                appear = 0f
                appearO = 0f
            }
            shown != want -> {
                // Another size: the new pack pops onto the back from nearly full
                shown = want
                appear = SWAP_FROM
                appearO = appear
            }
        }
        if (want > 0) appear = min(1f, appear + 1f / GROW)
        sprintO = sprint
        sprint += ((if (e.isSprinting && motion.moved > 0.05f) 1f else 0f) - sprint) * 0.2f
        // Only the first step of several works with how the body really moved; the rest let the weight go on swinging
        val forward = if (fresh) motion.forward else 0f
        val left = if (fresh) motion.left else 0f
        val up = if (fresh) motion.up else 0f
        pitch.step(PUSH_PITCH * forward)
        roll.step(PUSH_ROLL * left)
        bounce.step(-PUSH_BOUNCE * up)
        dangle.step(PUSH_DANGLE * forward + PUSH_DANGLE * 0.8f * left - 0.9f * up)
    }

    /** How the pack stands [partial] of the way through this tick, or null if there is none to draw. */
    fun frame(partial: Float): LadenFrame? {
        if (shown == 0) return null
        val a = Mth.lerp(partial, appearO, appear)
        if (a <= 0.002f) return null
        val grow = if (leaving) Ease.smooth(a) else Ease.back(a)
        return LadenFrame(
            level = shown, grow = grow, pitch = pitch.at(partial), roll = roll.at(partial), bounce = bounce.at(partial),
            dangle = dangle.at(partial), sprint = Mth.lerp(partial, sprintO, sprint),
        )
    }

    private companion object {
        /** The hinge's pull home and the air's drag on it: a swing of the weight takes about a second, and dies away in three or four. */
        const val PULL = 0.28f
        const val DRAG = 0.30f

        /** How hard speeding up, slowing, turning and landing push the weight (radians of speed per block per tick per tick). */
        const val PUSH_PITCH = 0.9f
        const val PUSH_ROLL = 1.0f
        const val PUSH_BOUNCE = 4.0f
        const val PUSH_DANGLE = 2.2f

        /** Ticks for a pack to come up onto the back and to go down off it, and where a swapped one starts from. */
        const val GROW = 7f
        const val VANISH = 6f
        const val SWAP_FROM = 0.55f
    }
}

/**
 * The client's side of the laden look: the render-state modifier that gives each trader's pack its frame, and the clink of the pots.
 * Nothing here does anything until the packs' art is in ([LadenArt.table]).
 */
internal object LadenClient {
    val FRAME: ContextKey<LadenFrame> = ContextKey(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "laden_frame"))

    private val looks = Looks { LadenLook() }

    /** Fills in the pack for each player drawn who wears one; registered beside the other modifiers of players. */
    val AVATAR = object : AvatarRenderStateModifier() {
        override fun <T> accept(entity: T, state: AvatarRenderState) where T : Avatar, T : ClientAvatarEntity {
            state.setRenderData(FRAME, null)
            if (LadenArt.packs == null) return
            val look = looks.of(entity)
            look.update(entity, entity.getExistingDataOrNull(Carry.LADEN))
            state.setRenderData(FRAME, look.frame(state.partialTick))
        }
    }

    /** Lets go of everything kept (on leaving a world). */
    fun reset() {
        looks.clear()
        LadenSounds.reset()
    }
}

/**
 * The pots on a tall frame (and the lantern on a frame) clink as their wearer walks, once for each step (design Part 3, "Your back"):
 * quietly, a little louder and oftener at a sprint, and lower for a heavier load (`trade.pack_clink`, pitch by weight). It is worked out
 * for every laden player near enough to hear, in view or not, from the stride their body is in, and plays nothing while the sound has no
 * line in `sounds.json` (the sounds are another stream's, and a sound the game does not know would only fill the log).
 */
internal object LadenSounds {
    /** How far away a pack can be heard, in blocks. */
    private const val HEAR = 16.0

    /** The load, in Marks, at which the pitch has gone as low as it goes. */
    private const val HEAVY = 900f

    private val steps = Int2IntOpenHashMap()
    private val quiet = Int2LongOpenHashMap()
    private val random = RandomSource.create()

    fun tick() {
        if (LadenArt.packs == null) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val me = mc.player ?: return
        if (mc.isPaused) return
        val sound = TradeSounds.get("trade.pack_clink")
        if (mc.soundManager.getSoundEvent(sound.location()) == null) return
        val now = level.gameTime
        for (p in level.players()) {
            val laden = p.getExistingDataOrNull(Carry.LADEN)
            if (laden == null || laden.level < 2 || p.isSpectator) {
                steps.remove(p.id)
                continue
            }
            if (p.distanceToSqr(me) > HEAR * HEAR) continue
            val count = floor(p.walkAnimation.position() * STRIDE / Math.PI).toInt()
            val known = steps.containsKey(p.id)
            val before = steps.get(p.id)
            steps.put(p.id, count)
            if (!known || before == count || p.walkAnimation.speed() < 0.12f) continue
            if (now < quiet.get(p.id)) continue
            val sprint = p.isSprinting
            // The tall frame's pots on every step, the frame's lantern on a sprint and now and then besides
            if (laden.level == 2 && !sprint && random.nextInt(3) != 0) continue
            val weight = min(1f, laden.marks / HEAVY)
            val volume = (0.16f + 0.26f * (if (sprint) 1f else 0f)) * (if (laden.level == 3) 1f else 0.55f) * (0.65f + 0.35f * weight)
            val pitch = 1.22f - 0.42f * weight + (random.nextFloat() - 0.5f) * 0.12f
            level.playLocalSound(p, sound, SoundSource.PLAYERS, volume, pitch)
            quiet.put(p.id, now + 4L)
        }
    }

    fun reset() {
        steps.clear()
        quiet.clear()
    }

    /** The walk animation's step in radians per unit of its position, as the legs swing (cos of position times this). */
    private const val STRIDE = 0.6662f
}
