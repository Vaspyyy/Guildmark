package io.github.vaspyyy.guildmark.trade.hunt

import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Claims
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.trade.TradeBooks
import io.github.vaspyyy.guildmark.trade.TradeSounds
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.phys.Vec3
import kotlin.math.hypot

/**
 * Has charge of a hired guard (living-world/trader/design.md, Part 4, "Company"; the pattern of `caravan/Crew.kt`'s `CrewDirector`), each
 * tick in place of a townsperson's day: coming up to the trader and greeting them, then walking three or four blocks behind and to one side
 * at their pace (the [Stride] the Hunt's outlaws walk by, so they quicken when the trader runs and take the same steps), standing at ease
 * when the trader stops and sitting when they sit; at a Hunt's call stepping between the trader and the party with a hand raised, and when
 * their days are up walking off towards the nearest town. The fight itself is the townsfolk guard's own (`MeleeAttackGoal`, which comes
 * before this), at whoever [Company] gives them. Using a guard answers for them: their trader hears how long they are hired for, anyone else
 * whose guard they are, and nobody can talk them out of it.
 */
internal object GuardDirector : Director {
    /** Where a guard keeps: this far behind the trader and this far to one side (the third straight behind, a little further). */
    private const val BEHIND = 3.4
    private const val SIDE = 1.6

    /** Close enough to their place to stop, and how far off it they walk faster to catch up. */
    private const val THERE = 0.9
    private const val SETTLED = 2.8
    private const val BEHINDHAND = 6.0

    /** Where they stand at a Hunt's call: this far in front of the trader, towards the party. */
    private const val SHIELD = 1.8

    /** How near the trader they come before greeting them, and how fast they come up and walk off. */
    private const val MEET = 3.6
    private const val COMING_PACE = 3.6
    private const val LEAVING_PACE = 2.6

    /** The blocks walked between two knocks of the spear's butt on paving. */
    private const val KNOCK = 2.4

    /** How long the trader has stood still on a seat or a mount before the guard sits too. */
    private const val SIT_AFTER = 60L

    /** The ground a spear's butt rings on: stone, brick and the like. */
    private val PAVING: Set<SoundType> = setOf(
        SoundType.STONE, SoundType.DEEPSLATE, SoundType.DEEPSLATE_BRICKS, SoundType.DEEPSLATE_TILES, SoundType.POLISHED_DEEPSLATE,
        SoundType.TUFF, SoundType.TUFF_BRICKS, SoundType.POLISHED_TUFF, SoundType.MUD_BRICKS, SoundType.PACKED_MUD, SoundType.NETHER_BRICKS,
        SoundType.RESIN_BRICKS,
    )

    override fun tick(p: Townsperson) {
        val level = p.level() as? ServerLevel ?: return
        val g = Company.guardOf(p)
        if (g == null) {
            // A guard body nobody hired (a contract ended under it): it goes
            p.director = null
            p.discard()
            return
        }
        val owner = level.server.playerList.getPlayer(g.owner) ?: return
        val now = level.gameTime
        if (p.actDone) p.act(Activity.NONE)
        // D3: on their trader's led wagon (trade/carry/WagonRiders), climbing up, riding or getting down, they do nothing else this tick
        if (seats?.ride(g, owner, now) == true) return
        when (g.state) {
            Company.State.COMING -> coming(level, g, owner, now)
            Company.State.FOLLOW -> follow(level, g, owner, now)
            Company.State.HOLD -> hold(level, g, owner, now)
            Company.State.LEAVING -> leaving(level, g, owner, now)
        }
        step(level, g)
    }

    /** Who has a guard on a seat (build part D3: the Carrier's Wagon's bench and tailboard), asked before anything else each tick: true while they have them. */
    fun interface Seats {
        fun ride(g: Company.Guard, owner: ServerPlayer, now: Long): Boolean
    }

    @Volatile
    var seats: Seats? = null

    override fun interact(p: Townsperson, player: ServerPlayer): Boolean {
        val g = Company.guardOf(p) ?: return true
        p.lookAt(player)
        p.attend(player, 40)
        val name = p.customName ?: p.name
        if (player.uuid != g.owner) {
            // Somebody else's guard: a nod, and whose they are; no talk, no trade, no purse to lift
            if (p.seat == null && (p.activity == Activity.NONE || p.activity.loop)) p.act(Activity.NOD)
            val owner = (p.level() as? ServerLevel)?.server?.playerList?.getPlayer(g.owner)?.name ?: Component.translatable("message.guildmark.trade.guard.someone")
            player.sendOverlayMessage(Component.translatable("message.guildmark.trade.guard.other", name, owner))
            return true
        }
        if (g.state == Company.State.LEAVING) return true
        if (p.seat == null && (p.activity == Activity.NONE || p.activity.loop)) p.act(Activity.NOD)
        val left = TradeBooks.peek(player.level())?.get(g.owner)?.guards?.firstOrNull { it.getLongOr("id", 0L) == g.id }?.getLongOr("left", 0L) ?: 0L
        val hours = left / 1000L
        player.sendOverlayMessage(
            if (hours < 1) Component.translatable("message.guildmark.trade.guard.days_last", name)
            else Component.translatable("message.guildmark.trade.guard.days", name, hours),
        )
        return true
    }

    // ---- What they do ----

    /** Coming up to the trader from where they were made (the hall's yard, or out of sight behind them); a greeting once they are near. */
    private fun coming(level: ServerLevel, g: Company.Guard, owner: ServerPlayer, now: Long) {
        val p = g.p
        val d = p.distanceTo(owner).toDouble()
        if (d <= MEET) {
            g.stride.halt()
            p.lookAt(owner)
            if (Company.greet(g)) {
                p.act(Activity.SALUTE)
                p.say("trade_guard_hello_${p.random.nextInt(2)}", force = true)
            }
            g.to(Company.State.FOLLOW, now)
            return
        }
        val pace = Company.paceOf(g.owner)?.speed ?: 0.0
        if (!p.activity.overlay) p.act(Activity.NONE)
        g.stride.go(owner.position(), maxOf(COMING_PACE, pace * 1.15 + 0.5), now, every = 10, moved = 2.0)
    }

    /** Three or four blocks behind the trader and to one side, at their pace; at ease when they stop, seated when they sit. */
    private fun follow(level: ServerLevel, g: Company.Guard, owner: ServerPlayer, now: Long) {
        val p = g.p
        val pace = Company.paceOf(g.owner)
        val sitting = (owner.isPassenger && pace != null && now - pace.stillSince > SIT_AFTER) || owner.isSleeping
        if (sitting) return rest(level, g, owner, now)
        if (g.sat != null || p.seat != null) {
            rise(level, g)
            return
        }
        // Up off the seat first
        if (p.activity == Activity.STAND_UP && !p.actDone) return
        val spot = place(owner, pace, g.slot)
        val d = hypot(spot.x - p.x, spot.z - p.z)
        val moving = (pace?.speed ?: 0.0) > 0.4
        // At ease already, they do not walk round the trader each time the trader turns about
        if (!moving && (d <= THERE || (g.stillSince >= 0 && d <= SETTLED))) {
            if (g.stillSince < 0) {
                g.stillSince = now
                g.stride.halt()
                g.idleAt = now + 30 + p.random.nextInt(30)
                knock(level, g, 0.5f)
            }
            atEase(g, owner, now)
            return
        }
        g.stillSince = -1L
        if (!p.activity.overlay) p.act(Activity.NONE)
        val speed = pace?.speed ?: 0.0
        val bps = if (d > BEHINDHAND) maxOf(speed * 1.3, 4.6) else (speed + (d - THERE) * 1.4).coerceIn(1.2, 7.0)
        g.stride.go(spot, bps, now, every = 6, moved = 1.0)
    }

    /** Standing at ease where they keep: a guard's stand, a look about now and then, arms folded for a while. */
    private fun atEase(g: Company.Guard, owner: ServerPlayer, now: Long) {
        val p = g.p
        if (now < g.idleAt) {
            if (p.activity == Activity.NONE) p.lookAt(owner.position().add(owner.lookAngle.scale(6.0)))
            return
        }
        val r = p.random.nextInt(10)
        val what = when {
            r < 5 -> Activity.STAND_GUARD
            r < 8 -> Activity.LOOK_ABOUT
            else -> Activity.ARMS_FOLDED
        }
        p.act(what)
        g.idleAt = now + if (what.loop) what.ticks * (2L + p.random.nextInt(3)) else what.ticks.toLong() + 40L + p.random.nextInt(80)
    }

    /** The trader sits (rides something standing still, sleeps): the guard sits on a stair close by, or folds their arms and waits. */
    private fun rest(level: ServerLevel, g: Company.Guard, owner: ServerPlayer, now: Long) {
        val p = g.p
        val seat = g.sat
        if (p.seat != null) {
            if (now % 100L == 0L) Claims.claim(level, p.seat!!, p, 400)
            if (p.actDone || p.activity == Activity.NONE) p.act(Activity.SIT)
            return
        }
        if (seat != null) {
            // On the way to the seat's front, then down onto it
            val access = Places.access(level, seat)
            if (access == null || !Places.isSeat(level, level.getBlockState(seat), seat)) {
                g.sat = null
                return
            }
            if (p.distanceToSqr(Vec3.atBottomCenterOf(access)) <= 1.3 * 1.3) {
                g.stride.halt()
                p.sitOn(seat)
                p.act(Activity.SIT_DOWN)
            } else g.stride.go(Vec3.atBottomCenterOf(access), 1.6, now)
            return
        }
        if (g.stillSince < 0) {
            g.stillSince = now
            g.stride.halt()
            val found = Places.seat(level, p.blockPosition(), 4, p)
            if (found != null && Claims.claim(level, found, p, 400) && Places.access(level, found) != null) {
                g.sat = found
                return
            }
            p.act(Activity.ARMS_FOLDED)
        }
        if (p.activity == Activity.NONE) p.act(Activity.ARMS_FOLDED)
        p.lookAt(owner)
    }

    /** Up off the seat as the trader goes on. */
    private fun rise(level: ServerLevel, g: Company.Guard) {
        val p = g.p
        if (p.seat != null) {
            val pos = p.seat
            p.standUp()
            Claims.release(level, pos, p)
            p.act(Activity.STAND_UP)
        } else {
            Claims.release(level, g.sat, p)
        }
        g.sat = null
        g.stillSince = -1L
    }

    /**
     * At a Hunt's call: the hand up for a moment where they stand, then a step in front of the trader towards the party, facing it, and a
     * guard's stand there. In a fight the townsfolk guard's own attack takes them; with nobody to fight near, they keep the trader's front.
     */
    private fun hold(level: ServerLevel, g: Company.Guard, owner: ServerPlayer, now: Long) {
        val p = g.p
        if (!Hunt.active(g.owner)) {
            g.to(Company.State.FOLLOW, now)
            return
        }
        val party = Hunt.partyAt(g.owner) ?: g.holdFrom ?: owner.position().add(owner.lookAngle.scale(8.0))
        if (now < g.haltUntil) {
            g.stride.halt()
            p.lookControl.setLookAt(party.x, party.y + 1.5, party.z, 30f, 30f)
            return
        }
        val dx = party.x - owner.x
        val dz = party.z - owner.z
        val len = hypot(dx, dz).coerceAtLeast(1e-3)
        val ux = dx / len
        val uz = dz / len
        val side = when (g.slot % 3) {
            0 -> 0.9
            1 -> -0.9
            else -> 0.0
        }
        val spot = Vec3(owner.x + ux * SHIELD - uz * side, owner.y, owner.z + uz * SHIELD + ux * side)
        val d = hypot(spot.x - p.x, spot.z - p.z)
        if (d <= THERE) {
            if (g.stillSince < 0) {
                g.stillSince = now
                g.stride.halt()
                knock(level, g, 0.7f)
            }
            p.face(p.yawTo(party))
            p.lookControl.setLookAt(party.x, party.y + 1.5, party.z, 30f, 30f)
            if (p.activity != Activity.STAND_GUARD && (p.activity == Activity.NONE || p.actDone)) p.act(Activity.STAND_GUARD)
            return
        }
        g.stillSince = -1L
        if (!p.activity.overlay) p.act(Activity.NONE)
        g.stride.go(spot, (2.5 + d * 0.8).coerceAtMost(6.0), now, every = 5, moved = 0.8)
    }

    /** Their days are up: after a salute, off towards the nearest town (or away from the trader where there is none), until nobody sees them. */
    private fun leaving(level: ServerLevel, g: Company.Guard, owner: ServerPlayer, now: Long) {
        val p = g.p
        if (now - g.since < Activity.SALUTE.ticks + 10) {
            g.stride.halt()
            return
        }
        val capital = HuntOdds.nearestCapital(level, p.blockPosition())
        val to = if (capital != 0L) Vec3.atBottomCenterOf(BlockPos.of(capital))
        else p.position().add(p.position().subtract(owner.position()).normalize().scale(48.0))
        if (!p.activity.overlay) p.act(Activity.NONE)
        g.stride.go(to, LEAVING_PACE, now, every = 20)
    }

    // ---- The spear's butt ----

    /** Counts the blocks walked, and knocks the spear's butt on paving every few steps. */
    private fun step(level: ServerLevel, g: Company.Guard) {
        val p = g.p
        val moved = hypot(p.x - g.lastX, p.z - g.lastZ)
        g.lastX = p.x
        g.lastZ = p.z
        if (moved > 1.0 || !p.onGround()) return
        g.walked += moved
        if (g.walked >= KNOCK) {
            g.walked = 0.0
            knock(level, g, 0.4f)
        }
    }

    /** The spear's butt on the stone under [g] (nothing on earth, sand or grass), at [volume]. */
    private fun knock(level: ServerLevel, g: Company.Guard, volume: Float) {
        val p = g.p
        val under = p.blockPosition().below()
        val state = level.getBlockState(under)
        if (state.getSoundType(level, under, p) !in PAVING) return
        level.playSound(null, p.x, p.y, p.z, TradeSounds.get("trade.guard_step"), SoundSource.NEUTRAL, volume, 0.95f + p.random.nextFloat() * 0.1f)
    }

    // ---- Where they keep ----

    /** Where guard [slot] keeps behind the trader: behind and to the right, behind and to the left, or straight behind a little further. */
    private fun place(owner: ServerPlayer, pace: Company.Pace?, slot: Int): Vec3 {
        var hx = pace?.hx ?: 0.0
        var hz = pace?.hz ?: 1.0
        // Standing, the trader's back is where their face is not
        if (pace == null || pace.speed < 0.4) {
            val yaw = Math.toRadians(owner.yRot.toDouble())
            hx = -Math.sin(yaw)
            hz = Math.cos(yaw)
        }
        val back = if (slot % 3 == 2) BEHIND + 1.2 else BEHIND
        val side = when (slot % 3) {
            0 -> SIDE
            1 -> -SIDE
            else -> 0.0
        }
        return Vec3(owner.x - hx * back - hz * side, owner.y, owner.z - hz * back + hx * side)
    }
}
