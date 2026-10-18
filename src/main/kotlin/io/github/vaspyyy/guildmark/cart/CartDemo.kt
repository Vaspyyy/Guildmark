package io.github.vaspyyy.guildmark.cart

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsfolk
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Trades
import io.github.vaspyyy.guildmark.townsfolk.Walker
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.item.ItemStack
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Testing the carts (ops only). `/guildmark cart demo` brings a carter who pulls a laden cart round you on a loop,
 * stops and lets go of it, unloads it a sack at a time, loads it with produce (sacks again the next time round),
 * takes it up again and sets off, round and round. `/guildmark cart clear` takes the carts and the demo's carters
 * away. A demo carter is a passer-by, gone once no one's about and after a reload; with carts switched off in the
 * settings they go at once, cart and all.
 */
object CartDemo {
    /** Adds `/guildmark cart demo` and `/guildmark cart clear`. */
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("cart")
                        .then(Commands.literal("demo").executes { demo(it) })
                        .then(Commands.literal("clear").executes { clear(it) }),
                ),
        )
    }

    private fun demo(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        if (!Handcarts.enabled()) {
            source.sendFailure(Component.translatable("commands.guildmark.cart.off"))
            return 0
        }
        val level = source.level
        val player = source.playerOrException
        val ring = ring(level, player.blockPosition())
        if (ring == null) {
            source.sendFailure(Component.translatable("commands.guildmark.cart.no_room"))
            return 0
        }
        val carter = Townsfolk.create(level, ring[0], TownRole.CITIZEN, reason = EntitySpawnReason.COMMAND) ?: return 0
        carter.crowd = true
        carter.leaveAt = Long.MAX_VALUE
        carter.director = Round(ring)
        level.addFreshEntity(carter)
        source.sendSuccess({ Component.translatable("commands.guildmark.cart.demo") }, true)
        return 1
    }

    private fun clear(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val player = source.playerOrException
        val carters = level.getEntitiesOfClass(Townsperson::class.java, player.boundingBox.inflate(REACH)) { it.director is Round }
        carters.forEach {
            it.director = null
            it.discard()
        }
        val carts = Handcarts.clear(level)
        source.sendSuccess({ Component.translatable("commands.guildmark.cart.cleared", carts, carters.size) }, true)
        return carts + carters.size
    }

    /** The loop round [center]: standable spots about [RADIUS] blocks out all the way round; null if there aren't enough. */
    private fun ring(level: ServerLevel, center: BlockPos): List<BlockPos>? {
        val out = ArrayList<BlockPos>()
        for (i in 0 until POINTS) {
            val a = Math.PI * 2 * i / POINTS
            val column = center.offset((cos(a) * RADIUS).roundToInt(), 0, (sin(a) * RADIUS).roundToInt())
            for (dy in DY) {
                val at = column.above(dy)
                if (Places.standable(level, at)) {
                    out.add(at)
                    break
                }
            }
        }
        return if (out.size >= POINTS - 2) out else null
    }

    /** The demo carter's round: a lap with the cart, then it's parked, unloaded, loaded with the other goods, taken up again. */
    private class Round(private val ring: List<BlockPos>) : Director {
        private enum class Stage { HITCH, LAP, STOP, SIDE, UNLOAD, LOAD, FETCH, TAKE }

        private var stage = Stage.HITCH
        private var walker: Walker? = null
        private var wait = 0
        private var leg = 0
        private var tries = 0
        private var sacks = true
        private val goods = ArrayList<ItemStack>()

        override fun tick(p: Townsperson) {
            // Carts switched off: the demo's over (Handcarts takes the cart away)
            if (!Handcarts.enabled()) {
                p.discard()
                return
            }
            val w = walker
            if (w != null) {
                if (stage == Stage.LAP) p.act(Handcarts.PULL)
                if (w.tick() == Walker.State.WALKING) return
                w.stop()
                walker = null
            }
            if (wait > 0) {
                wait--
                return
            }
            when (stage) {
                Stage.HITCH -> {
                    if (Handcarts.hitch(p) == null) {
                        // Nowhere for a cart here: try again in a moment
                        wait = 40
                        return
                    }
                    goods.clear()
                    goods.addAll(lot(sacks))
                    Handcarts.load(p, goods)
                    p.act(Handcarts.PULL)
                    leg = 0
                    stage = Stage.LAP
                }
                Stage.LAP -> {
                    if (leg < ring.size) {
                        walk(p, ring[leg % ring.size], 1.2)
                        leg++
                    } else {
                        // Stop, then let go of it
                        wait = 20
                        stage = Stage.STOP
                    }
                }
                Stage.STOP -> {
                    Handcarts.unhitch(p)
                    p.act(Activity.NONE)
                    wait = 20
                    stage = Stage.SIDE
                }
                Stage.SIDE -> {
                    Handcarts.side(p, p.position())?.let { walk(p, it, 0.5, exact = true) }
                    stage = Stage.UNLOAD
                }
                Stage.UNLOAD -> {
                    faceBed(p)
                    if (goods.isNotEmpty()) {
                        p.act(Activity.PICK_UP)
                        goods.removeAt(goods.size - 1)
                        Handcarts.load(p, goods)
                        wait = 26
                    } else {
                        sacks = !sacks
                        wait = 10
                        stage = Stage.LOAD
                    }
                }
                Stage.LOAD -> {
                    faceBed(p)
                    val lot = lot(sacks)
                    if (goods.size < lot.size) {
                        p.act(Activity.PUT_DOWN)
                        goods.add(lot[goods.size])
                        Handcarts.load(p, goods)
                        wait = 26
                    } else {
                        p.act(Activity.NONE)
                        stage = Stage.FETCH
                    }
                }
                Stage.FETCH -> {
                    Handcarts.fetch(p)?.let { walk(p, it, 0.3, exact = true) }
                    tries = 0
                    stage = Stage.TAKE
                }
                Stage.TAKE -> {
                    // Back to the cart, the shafts taken up
                    Handcarts.parked(p)?.let { p.face(it.yRot) }
                    Handcarts.hitch(p)
                    if (Handcarts.pulling(p)) {
                        // The same load again changes nothing; a fresh cart (the old one went) gets it
                        Handcarts.load(p, goods)
                        p.act(Handcarts.PULL)
                        wait = 15
                        leg = 0
                        stage = Stage.LAP
                    } else if (++tries > 4) {
                        stage = Stage.FETCH
                    } else {
                        wait = 10
                    }
                }
            }
        }

        private fun walk(p: Townsperson, to: BlockPos, near: Double, exact: Boolean = false) {
            walker = Walker(p).also { it.go(to, Trades.LADEN, near, exact) }
        }

        private fun faceBed(p: Townsperson) {
            Handcarts.bed(p)?.let { p.face(p.yawTo(it)) }
        }

        /** A cartload: grain and flour, or the market's produce. */
        private fun lot(sacks: Boolean): List<ItemStack> =
            (if (sacks) SACKS else PRODUCE).map { Handcarts.stack(it) }
    }

    private const val RADIUS = 7.0
    private const val POINTS = 8
    private const val REACH = 128.0
    private val DY = intArrayOf(0, 1, -1, 2, -2, 3, -3)
    private val SACKS = listOf(Good.WHEAT, Good.WHEAT, Good.FLOUR, Good.WHEAT, Good.FLOUR, Good.FLOUR, Good.WHEAT)
    private val PRODUCE = listOf(Good.CARROT, Good.APPLE, Good.BREAD, Good.COD, Good.POTATO, Good.BEETROOT, Good.SALMON)
}
