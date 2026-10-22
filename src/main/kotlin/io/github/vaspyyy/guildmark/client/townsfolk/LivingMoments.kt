package io.github.vaspyyy.guildmark.client.townsfolk

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.cooking.TownKitchen
import io.github.vaspyyy.guildmark.client.works.WorksClient
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ItemParticleOption
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import java.util.WeakHashMap
import kotlin.math.cos
import kotlin.math.sin

/**
 * What the Living World's work sounds and looks like, on exactly the ticks it happens ([LivingSchedule.MOMENTS],
 * where in the clip each one is too): the ladder creaking under each hand and foot and the ledge at its top under a
 * slapped-down hand, a bite and its crumbs, water
 * scooped and splashed, the well's rope over the curb and its bucket knocking the stone, coins counted out, knocks on
 * a door, the axe's thunk and its chips, the grindstone's rumble and its flour, a sack of grain poured, bells rung,
 * seeds cast, the scythe's swish and the stalks it cuts, a street lamp catching and snuffed, the lamplighter's wick
 * smoking at the top of the pole. Each townsperson's clip time is
 * followed tick to tick and every moment it passes goes off (a climb's go by height, either way up the ladder).
 */
internal object LivingMoments {
    private class Seen(var activity: Activity, var clip: String, var start: Int, var time: Float, var at: Int)

    private val seen = WeakHashMap<Townsperson, Seen>()

    /** Each client tick for each townsperson near enough to be heard (TownsfolkClient.tick). */
    fun tick(p: Townsperson) {
        LivingProps.settle(p)
        val activity = p.shownActivity
        var name = TownsfolkClient.clipName(p, activity)
        var now: Float
        // A climb goes by height, its last two blocks under a ledge by the top-out's clip
        val top = if (activity == Activity.CLIMB) LivingProps.topOutTime(p, p.y) else -1f
        if (top >= 0f) {
            name = TOP_OUT
            now = top
        } else {
            now = time(p, activity)
        }
        val moments = LivingSchedule.MOMENTS[name]
        if (moments == null) {
            seen.remove(p)
            return
        }
        val was = seen[p]
        if (was == null || was.activity != activity || was.clip != name || was.start != p.shownStart || p.tickCount - was.at > 2) {
            // Only just begun: what happens on its first tick happens now. Come upon part way through (or back in
            // earshot after a while away), or on from a climb's rungs to its top: nothing is caught up on.
            val begun = was != null && p.tickCount - was.at <= 2 && p.tickCount - p.shownStart <= 1 && activity != Activity.CLIMB
            seen[p] = Seen(activity, name, p.shownStart, now, p.tickCount)
            if (begun) for (m in moments) if (m.tick <= now) fire(p, name, m)
            return
        }
        val length = if (name == TOP_OUT) LivingProps.TOP_OUT_TICKS else activity.ticks.toFloat()
        var from = was.time
        var to = now
        if (name == TOP_OUT) {
            // By height either way, from one end of it to the other
            from = minOf(was.time, now)
            to = maxOf(was.time, now)
        } else if (activity == Activity.CLIMB) {
            // By height: up or down, whichever way is the shorter round the loop
            var d = now - was.time
            if (d > length / 2) d -= length else if (d < -length / 2) d += length
            if (d < 0f) {
                from = now
                to = now - d
            } else {
                to = was.time + d
            }
        } else if (activity.loop && now < was.time) {
            to = now + length
        }
        if (to > from) for (m in moments) if (passed(m.tick, from, to, length)) fire(p, name, m)
        was.time = now
        was.at = p.tickCount
    }

    /** Whether [tick] (or a lap of it) lies in (from, to]. */
    private fun passed(tick: Float, from: Float, to: Float, length: Float): Boolean =
        tick > from && tick <= to || tick + length > from && tick + length <= to

    /** Where [p] is in their clip this tick. */
    private fun time(p: Townsperson, activity: Activity): Float {
        if (activity == Activity.CLIMB) return LivingProps.climbTime(p.y)
        val into = (p.tickCount - p.shownStart).toFloat()
        return if (activity.loop) Math.floorMod(p.tickCount - p.shownStart, activity.ticks).toFloat() else into.coerceAtMost(activity.ticks.toFloat())
    }

    private fun fire(p: Townsperson, clip: String, m: LivingSchedule.Moment) {
        val level = p.level()
        val at = at(p, m)
        // A works' master builder's handbell is the server's (works.handbell): no crier's bell on top of it
        if (clip == "ring_bell" && WorksClient.master(p)) return
        when (clip) {
            "sit_down" -> if (m.name == "down") sound(level, at, mod("seat"), 0.45f)
            "stand_up" -> if (m.name == "up") sound(level, at, mod("seat"), 0.3f, 1.1f)
            "knock" -> sound(level, at, mod("knock"), 0.85f)
            "climb" -> if (m.name == "grab") sound(level, at, SoundEvents.LADDER_STEP, 0.3f, 1.12f) else sound(level, at, SoundEvents.LADDER_STEP, 0.5f, 0.92f)
            TOP_OUT -> when (m.name) {
                "step" -> sound(level, at, SoundEvents.LADDER_STEP, 0.5f, 0.92f)
                // A hand slapped down on the ledge, a foot put on it: the ledge's own sound
                "palm" -> blockSound(level, at, hit = true, volume = 0.35f)
                "ledge" -> blockSound(level, at, hit = false, volume = 0.5f)
            }
            "eat" -> {
                sound(level, at, SoundEvents.GENERIC_EAT.value(), 0.5f, 0.9f + p.random.nextFloat() * 0.2f)
                val food = p.carried.takeUnless { it.isEmpty }?.item ?: Items.BREAD
                crumbs(p, level, at, food, 5)
            }
            "sit_eat" -> sound(level, at, SoundEvents.GENERIC_EAT.value(), 0.25f, 1.25f)
            "wash" -> when (m.name) {
                "scoop" -> {
                    sound(level, at, mod("wash.scoop"), 0.5f)
                    repeat(6) { puff(level, ParticleTypes.SPLASH, at, 0.3, 0.12, 0.06) }
                }
                "splash" -> {
                    sound(level, at, mod("wash.splash"), 0.55f)
                    repeat(8) { puff(level, ParticleTypes.SPLASH, at, 0.3, 0.1, 0.08) }
                    repeat(4) { puff(level, ParticleTypes.FALLING_WATER, at, 0.25, 0.0, 0.02) }
                }
                "shake" -> {
                    sound(level, at, mod("wash.shake"), 0.45f)
                    repeat(9) { puff(level, ParticleTypes.FALLING_WATER, at, 0.5, 0.0, 0.04) }
                }
            }
            "pick_up" -> sound(level, at, SoundEvents.BUNDLE_REMOVE_ONE, 0.4f, 0.8f)
            "put_down" -> {
                sound(level, at, mod("merchant.arrange"), 0.5f, 0.9f)
                val ground = groundUnder(level, at.add(0.0, -0.3, 0.0))
                repeat(3) { puff(level, BlockParticleOption(ParticleTypes.BLOCK, ground), at.add(0.0, -0.2, 0.0), 0.3, 0.05, 0.04) }
            }
            "hand_over", "receive" -> sound(level, at, SoundEvents.BUNDLE_INSERT, 0.3f)
            "pay" -> sound(level, at, mod("thief.coin"), 0.45f, 0.95f + p.random.nextFloat() * 0.15f)
            "browse" -> when (m.name) {
                "pick" -> sound(level, at, SoundEvents.BUNDLE_REMOVE_ONE, 0.25f, 1.1f)
                "put back" -> sound(level, at, mod("merchant.arrange"), 0.3f, 1.05f)
            }
            "set_up" -> when (m.name) {
                "take" -> sound(level, at, SoundEvents.BUNDLE_REMOVE_ONE, 0.35f, 0.9f)
                "place" -> sound(level, at, mod("merchant.arrange"), 0.5f)
                "tug" -> sound(level, at, SoundEvents.ARMOR_EQUIP_LEATHER.value(), 0.35f, 1.1f)
            }
            "sow" -> {
                sound(level, at, SoundEvents.CROP_PLANTED, 0.3f, 1.1f)
                cast(p, level, at, Items.WHEAT_SEEDS, 8)
            }
            "gather" -> when (m.name) {
                "pull" -> {
                    sound(level, at, mod("farmer.harvest"), 0.55f)
                    val ground = groundUnder(level, at.add(0.0, -0.4, 0.0))
                    repeat(6) { puff(level, BlockParticleOption(ParticleTypes.BLOCK, ground), at.add(0.0, -0.15, 0.0), 0.3, 0.12, 0.05) }
                }
                "in_basket" -> sound(level, at, SoundEvents.COMPOSTER_FILL, 0.3f, 1.1f)
            }
            "scythe" -> when (m.name) {
                "swish" -> sound(level, at, mod("farmer.scythe"), 0.6f)
                "cut" -> {
                    sound(level, at, SoundEvents.CROP_BREAK, 0.5f)
                    val crop = level.getBlockState(BlockPos.containing(at)).takeUnless { it.isAir || !it.fluidState.isEmpty }
                        ?: Blocks.WHEAT.defaultBlockState()
                    repeat(9) { puff(level, BlockParticleOption(ParticleTypes.BLOCK, crop), at, 0.6, 0.08, 0.08) }
                }
            }
            "grind" -> {
                sound(level, at, mod("miller.grind"), 0.5f)
                repeat(5) { puff(level, FLOUR, at, 0.35, 0.02, 0.04) }
            }
            "chop" -> {
                sound(level, at, mod("chop"), 0.8f)
                val wood = level.getBlockState(BlockPos.containing(at.add(0.0, -0.2, 0.0))).takeUnless { it.isAir || !it.fluidState.isEmpty }
                    ?: Blocks.OAK_LOG.defaultBlockState()
                repeat(9) { puff(level, BlockParticleOption(ParticleTypes.BLOCK, wood), at, 0.25, 0.14, 0.12) }
            }
            "pour_sack" -> when (m.name) {
                "swing" -> sound(level, at, SoundEvents.ARMOR_EQUIP_LEATHER.value(), 0.25f, 0.85f)
                "pour" -> {
                    sound(level, at, mod("miller.pour"), 0.6f)
                    grain(level, at, 12)
                    repeat(4) { puff(level, FLOUR, at, 0.4, 0.03, 0.03) }
                }
                "shake" -> {
                    sound(level, at, mod("miller.pour"), 0.25f, 1.1f)
                    grain(level, at, 4)
                    repeat(3) { puff(level, FLOUR, at, 0.3, 0.03, 0.03) }
                }
            }
            // The crier's handbell, rung at each end of the shake (four strokes a loop). One bell, so one pitch: its
            // sounds differ in how the clapper strikes, and the pitch only wavers a hair so the strokes don't drone
            "ring_bell" -> sound(level, at, mod(HANDBELL), 0.8f, bellPitch(level))
            // Waved about as they read out, the bell in the crier's hand knocks once or twice; shaken up high, it rings
            "announce" -> sound(level, at, mod(HANDBELL), if (m.name == "ring") 0.8f else 0.14f, bellPitch(level))
            "light_lamp" -> when (m.name) {
                // The pole's brass end knocks on the lantern's frame as the wick goes in at its little door
                "reach" -> sound(level, at, mod("lamp.tap"), 0.45f)
                "lit" -> {
                    sound(level, at, mod("lamp.light"), 0.6f)
                    repeat(4) { puff(level, ParticleTypes.FLAME, at, 0.12, 0.02, 0.01) }
                    repeat(3) { puff(level, ParticleTypes.SMOKE, at.add(0.0, 0.15, 0.0), 0.1, 0.04, 0.01) }
                }
            }
            // Of a morning: the snuffer's brass bell put over the flame (it clinks on the lantern), the flame hissing out
            // under it, and a wisp of smoke let out of the lantern as it comes away
            "snuff_lamp" -> when (m.name) {
                "clink" -> sound(level, at, mod("lamp.clink"), 0.5f)
                "hiss" -> {
                    sound(level, at, mod("lamp.hiss"), 0.55f)
                    repeat(2) { puff(level, ParticleTypes.SMOKE, at, 0.06, 0.02, 0.01) }
                }
                "smoke" -> repeat(5) { puff(level, ParticleTypes.SMOKE, at, 0.08, 0.05, 0.008) }
            }
            // The wick burning at the top of the pole, carried lit from lamp to lamp: a thread of smoke now and then
            "carry_pole" -> if (p.lantern) puff(level, ParticleTypes.SMOKE, at, 0.02, 0.03, 0.004)
            "draw_water" -> when (m.name) {
                "pull" -> sound(level, at, mod("well.rope"), 0.45f)
                "knock" -> {
                    sound(level, at, mod("well.bucket"), 0.55f)
                    repeat(4) { puff(level, ParticleTypes.SPLASH, at, 0.2, 0.08, 0.05) }
                }
                "scrape" -> sound(level, at, mod("well.scrape"), 0.45f)
                "grab" -> sound(level, at, mod("slosh"), 0.3f, 1.05f)
                "slosh" -> {
                    sound(level, at, mod("slosh"), 0.45f)
                    repeat(2) { puff(level, ParticleTypes.SPLASH, at, 0.15, 0.06, 0.03) }
                }
            }
            // The works' crew. Their sounds carry their own level (works-audio.md), so most play at 1.0; the lighter touches
            // are softer. The beam set down (works.beam_down) and the horn (works.horn, heard to 128 blocks) are the
            // server's, so carry_beam and sound_horn play nothing here
            "mix_mortar" -> {
                sound(level, at, sfx("works.mortar"), 1.0f)
                repeat(2) { puff(level, MORTAR, at, 0.15, 0.06, 0.03) }
            }
            "trowel" -> when (m.name) {
                "scoop" -> sound(level, at, sfx("works.trowel"), 0.6f)
                "spread" -> {
                    sound(level, at, sfx("works.trowel"), 1.0f)
                    repeat(2) { puff(level, MORTAR, at, 0.2, 0.02, 0.02) }
                }
                // The blade's flat tapping the stone down into its bed
                "tap" -> sound(level, at, sfx("works.stone_set"), 0.4f, 1.15f + level.random.nextFloat() * 0.1f)
                "cut" -> sound(level, at, sfx("works.trowel"), 0.6f)
            }
            "hoist" -> sound(level, at, sfx("works.hoist"), 1.0f)
            // Chips of the stone off the chisel's point, the banker's own
            "chisel" -> {
                sound(level, at, sfx("works.chisel"), 1.0f)
                val stone = level.getBlockState(BlockPos.containing(at.add(0.0, -0.1, 0.0))).takeUnless { it.isAir || !it.fluidState.isEmpty }
                    ?: Blocks.STONE.defaultBlockState()
                repeat(5) { puff(level, BlockParticleOption(ParticleTypes.BLOCK, stone), at, 0.12, 0.12, 0.1) }
            }
            // The sighting staff's foot planted in the ground (the master's lines as he waves are the building's)
            "survey" -> if (m.name == "plant") {
                sound(level, at, sfx("works.stake"), 0.5f)
                val ground = groundUnder(level, at.add(0.0, -0.3, 0.0))
                repeat(3) { puff(level, BlockParticleOption(ParticleTypes.BLOCK, ground), at.add(0.0, 0.05, 0.0), 0.2, 0.06, 0.04) }
            }
            "unveil" -> if (m.name == "pull") sound(level, at, sfx("works.unveil"), 1.0f)
            "toast" -> when (m.name) {
                // Raised together: the crew's cups are heard as one clink, not one for every cup
                "raise" -> if (level.gameTime - toastTick > TOAST_TOGETHER || toastAt.distanceToSqr(at) > 256.0) {
                    toastTick = level.gameTime
                    toastAt = at
                    sound(level, at, sfx("works.toast"), 1.0f)
                }
                "drink" -> sound(level, at, mod("tavern.sip"), 0.5f)
            }
            // The torch put into the beacon's coals (the fire's flare and its burning are the beacon's own)
            "light_beacon" -> if (m.name == "dip") {
                sound(level, at, sfx("works.beacon_flare"), 1.0f)
                repeat(5) { puff(level, ParticleTypes.FLAME, at, 0.25, 0.04, 0.02) }
                repeat(3) { puff(level, ParticleTypes.SMOKE, at.add(0.0, 0.2, 0.0), 0.2, 0.05, 0.01) }
            }
            // The streets in hard times. The awning's flap (shut_stall), the ladle in the pot and the bundle set on the
            // handcart are the server's, heard once however many watch
            "shiver" -> when (m.name) {
                "stamp" -> {
                    sound(level, at, sfx("streets.stamp"), 1.0f)
                    val ground = groundUnder(level, at.add(0.0, -0.3, 0.0))
                    repeat(2) { puff(level, BlockParticleOption(ParticleTypes.BLOCK, ground), at.add(0.0, 0.05, 0.0), 0.2, 0.04, 0.03) }
                }
                "puff" -> breath(p, level, at)
            }
            // Turned over above the bare counter and shaken twice: the basket's rattle on the turn, the last crumbs falling
            "empty_basket" -> {
                if (m.tick < 14f) sound(level, at, sfx("streets.basket"), 1.0f)
                crumbs(p, level, at, Items.BREAD, 3)
            }
            "ladle" -> when (m.name) {
                "dip" -> repeat(2) { puff(level, STEW, at, 0.1, 0.06, 0.02) }
                // A few drops off the ladle's lip as it pours into the bowl
                "pour" -> repeat(4) { puff(level, STEW, at, 0.08, -0.04, 0.01) }
            }
            "take_bowl" -> sound(level, at, sfx("streets.bowl"), 1.0f)
            "eat_bowl" -> sound(level, at, sfx("streets.bowl"), 0.6f)
            // Each of the pair pats the other's back
            "embrace" -> sound(level, at, sfx("streets.pat"), 1.0f)
            // ---- Hearthcraft (TownKitchen.kt): the kitchen's clips, their cook.* sounds and steam ----
            "stir", "taste", "flip", "turn_spit", "carve", "slice", "peel" -> TownKitchen.moment(p, clip, m, at)
            // ---- end Hearthcraft ----
            // The caravan crew's clips: their caravan.* sounds (client/caravan's CaravanSounds)
            else -> caravan?.invoke(p, clip, m.name, at)
        }
    }

    /** Plays a moment of a clip this has no case for: [clip]'s moment [moment], by [Townsperson], at a point of the world. */
    var caravan: ((Townsperson, String, String, Vec3) -> Unit)? = null

    /** Where moment [m] happens in the world: its point of the clip's space, set by where [p] stands and faces. */
    private fun at(p: Townsperson, m: LivingSchedule.Moment): Vec3 {
        val yaw = Math.toRadians(p.yBodyRot.toDouble())
        val scale = if (p.role == TownRole.CHILD) Townsperson.CHILD_SCALE.toDouble() else 1.0
        // The clip's space: px, y down from the feet 24 px up, facing -z, the character's right at -x
        val f = -m.z / 16.0 * scale
        val r = -m.x / 16.0 * scale
        val up = (24.0 - m.y) / 16.0 * scale
        return Vec3(p.x - sin(yaw) * f - cos(yaw) * r, p.y + up, p.z + cos(yaw) * f - sin(yaw) * r)
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

    /** Crumbs off a bite, falling from the mouth and a little forward. */
    private fun crumbs(p: Townsperson, level: Level, at: Vec3, food: Item, count: Int) {
        val yaw = Math.toRadians(p.yBodyRot.toDouble())
        val random = level.random
        repeat(count) {
            val out = 0.03 + random.nextDouble() * 0.05
            level.addParticle(
                ItemParticleOption(ParticleTypes.ITEM, food), at.x, at.y - 0.05, at.z,
                -sin(yaw) * out + (random.nextDouble() - 0.5) * 0.04, 0.02 + random.nextDouble() * 0.05, cos(yaw) * out + (random.nextDouble() - 0.5) * 0.04,
            )
        }
    }

    /** Seed flung from the hand in a fan ahead, falling to the furrows. */
    private fun cast(p: Townsperson, level: Level, at: Vec3, seed: Item, count: Int) {
        val yaw = Math.toRadians(p.yBodyRot.toDouble())
        val random = level.random
        repeat(count) {
            val fan = (random.nextDouble() - 0.5) * 1.2
            val out = 0.08 + random.nextDouble() * 0.1
            level.addParticle(
                ItemParticleOption(ParticleTypes.ITEM, seed), at.x, at.y, at.z,
                -sin(yaw + fan) * out, 0.02 + random.nextDouble() * 0.06, cos(yaw + fan) * out,
            )
        }
    }

    /** A breath of white let out at the mouth on a cold day, drifting forward and thinning. */
    private fun breath(p: Townsperson, level: Level, at: Vec3) {
        val yaw = Math.toRadians(p.yBodyRot.toDouble())
        val random = level.random
        repeat(3) {
            val out = 0.012 + random.nextDouble() * 0.012
            level.addParticle(ParticleTypes.WHITE_SMOKE, at.x, at.y, at.z, -sin(yaw) * out, 0.004 + random.nextDouble() * 0.006, cos(yaw) * out)
        }
    }

    /** Grain streaming down into a hopper. */
    private fun grain(level: Level, at: Vec3, count: Int) {
        val random = level.random
        repeat(count) {
            level.addParticle(
                ItemParticleOption(ParticleTypes.ITEM, Items.WHEAT), at.x + (random.nextDouble() - 0.5) * 0.2, at.y + 0.35 + random.nextDouble() * 0.2,
                at.z + (random.nextDouble() - 0.5) * 0.2, (random.nextDouble() - 0.5) * 0.02, -0.05 - random.nextDouble() * 0.05, (random.nextDouble() - 0.5) * 0.02,
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

    private fun sound(level: Level, at: Vec3, event: SoundEvent, volume: Float, pitch: Float = 0.95f + level.random.nextFloat() * 0.1f) {
        level.playLocalSound(at.x, at.y, at.z, event, SoundSource.NEUTRAL, volume, pitch, false)
    }

    /** The sound of the block just under [at] (a hand or a foot put down on it): its hit, or its step. */
    private fun blockSound(level: Level, at: Vec3, hit: Boolean, volume: Float) {
        val state = level.getBlockState(BlockPos.containing(at.x, at.y - 0.12, at.z))
        if (state.isAir) return
        val type = state.soundType
        sound(level, at, if (hit) type.hitSound else type.stepSound, type.volume * volume, type.pitch * (0.9f + level.random.nextFloat() * 0.15f))
    }

    /** The clip a climb's last two blocks under a ledge play (LivingProps.TOP_OUT), by its name here. */
    private const val TOP_OUT = "climb_top"

    /** The town crier's handbell (townsfolk.crier.handbell: heard about as far as the crier's voice, 48 blocks). */
    private const val HANDBELL = "crier.handbell"

    /** The handbell's pitch: one bell, so it barely varies. */
    private fun bellPitch(level: Level): Float = 0.99f + level.random.nextFloat() * 0.02f

    /** When and where a toast was last raised, so a crew raising their cups together clinks once. */
    private var toastTick = -1000L
    private var toastAt: Vec3 = Vec3.ZERO

    /** How many ticks apart two cups raised count as raised together. */
    private const val TOAST_TOGETHER = 8L

    private val events = HashMap<String, SoundEvent>()

    /** The mod's townsfolk.[name] sound. */
    private fun mod(name: String): SoundEvent =
        events.getOrPut(name) { SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "townsfolk.$name")) }

    private val whole = HashMap<String, SoundEvent>()

    /** The mod's sound [id], given whole: the works' and the streets' (works.*, streets.*). */
    private fun sfx(id: String): SoundEvent =
        whole.getOrPut(id) { SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, id)) }

    private val FLOUR = DustParticleOptions(0xF4EEDF, 0.9f)

    /** Lime mortar, wet: flecks of it off the hoe and the trowel. */
    private val MORTAR = BlockParticleOption(ParticleTypes.BLOCK, Blocks.CALCITE.defaultBlockState())

    /** The alms pot's stew, in drops. */
    private val STEW = ItemParticleOption(ParticleTypes.ITEM, Items.RABBIT_STEW)
}
