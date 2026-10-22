package io.github.vaspyyy.guildmark.townsfolk.scenes

import io.github.vaspyyy.guildmark.economy.works.Works
import io.github.vaspyyy.guildmark.economy.works.WorksState
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.CityPlaces
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Task
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Walker
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.phys.Vec3
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Children playing builders by a works going up (living-world/design-influence.md section 5, "Watchers"): a patch of
 * street a little way off its lot is their site. They fetch a "stone" from their heap (a block in their arms,
 * CARRY_BLOCK), carry it to their "wall" and lay it (LAY, high and low), knock it in (NAIL), dig the footings (DIG),
 * jump about when it's done, and do it all again; the first of them is the master builder, giving the orders ("I'm the
 * master builder! You carry the stones!"). It's one of the games of their play time ([io.github.vaspyyy.guildmark.townsfolk.Leisure.child]),
 * a few children to a works.
 */
internal object Builders {
    /** How near a works going up a child must be to play builders by it, and how far off its lot their patch is. */
    private const val NEAR = 40.0
    private const val OFF = 8

    /** The most children at one works' game. */
    private const val MOST = 3

    private class Game(val patch: BlockPos, val heap: BlockPos, val wall: BlockPos) {
        val players = LinkedHashSet<Townsperson>()
    }

    private val games = HashMap<Int, Game>()

    /** What children carry and lay: planks and cobbles, the works' own stuff. */
    private val STONES = listOf(Items.OAK_PLANKS, Items.COBBLESTONE, Items.STONE_BRICKS, Items.SPRUCE_PLANKS)

    /**
     * A game of builders for [p] (a child at play) by a works going up near them, now and then; null with the works
     * off, none going up near, or its game full. Asks nothing of the world with the works off.
     */
    fun game(p: Townsperson): Task? {
        if (!Works.enabled() || p.role != TownRole.CHILD) return null
        val level = p.level() as? ServerLevel ?: return null
        val town = p.town ?: (p.life?.places as? CityPlaces)?.capital?.town ?: return null
        val w = Scenes.works(level, town.center.asLong()).firstOrNull { w ->
            w.state == WorksState.BUILDING && w.site?.lot?.let { Vec3.atCenterOf(it.center).distanceToSqr(p.position()) < NEAR * NEAR } == true
        } ?: return null
        if (p.random.nextInt(3) != 0) return null
        return join(level, p, w.id, w.site!!.lot)
    }

    /** [p] joins the game of builders by the works [id] on [lot] (starting one if there's none): null if it's full or there's nowhere to play. */
    fun join(level: ServerLevel, p: Townsperson, id: Int, lot: BoundingBox, force: Boolean = false): Task? {
        val game = games[id] ?: (patch(level, p, lot) ?: return null).also { games[id] = it }
        game.players.removeIf { !it.isAlive || it.isRemoved || it.routine.task !is Playing }
        if (!force && game.players.size >= MOST) return null
        val master = game.players.isEmpty()
        game.players += p
        return Playing(p, game, master, 3 + p.random.nextInt(3))
    }

    /** Their patch: a flat bit of street [OFF] blocks or so off [lot]'s nearest edge, a heap and a "wall" on it. */
    private fun patch(level: ServerLevel, p: Townsperson, lot: BoundingBox): Game? {
        val cx = (lot.minX() + lot.maxX() + 1) / 2.0
        val cz = (lot.minZ() + lot.maxZ() + 1) / 2.0
        var dx = p.x - cx
        var dz = p.z - cz
        val len = sqrt(dx * dx + dz * dz).takeIf { it > 0.5 } ?: 1.0
        dx /= len
        dz /= len
        val reach = maxOf(lot.getXSpan(), lot.getZSpan()) / 2.0 + OFF
        repeat(6) {
            val turn = (p.random.nextDouble() - 0.5) * 1.2
            val ux = dx * kotlin.math.cos(turn) - dz * kotlin.math.sin(turn)
            val uz = dx * kotlin.math.sin(turn) + dz * kotlin.math.cos(turn)
            val x = floor(cx + ux * reach).toInt()
            val z = floor(cz + uz * reach).toInt()
            val patch = ground(level, BlockPos(x, p.blockY, z)) ?: return@repeat
            val heap = ground(level, patch.offset(-(uz * 3).toInt(), 0, (ux * 3).toInt())) ?: return@repeat
            val wall = ground(level, patch.offset((uz * 3).toInt(), 0, -(ux * 3).toInt())) ?: return@repeat
            if (lot.isInside(patch) || lot.isInside(heap) || lot.isInside(wall)) return@repeat
            return Game(patch, heap, wall)
        }
        return null
    }

    private fun ground(level: ServerLevel, at: BlockPos): BlockPos? {
        for (dy in intArrayOf(0, 1, -1, 2, -2)) {
            val pos = at.above(dy)
            if (level.isLoaded(pos) && Places.outdoorGround(level, pos)) return pos
        }
        return null
    }

    /**
     * A child's game of builders: [rounds] of fetching a stone from the heap and laying it on the wall, knocking it in,
     * a dig at the footings, and a jump for joy; the [master] points out where it goes and gives the orders.
     */
    private class Playing(p: Townsperson, private val game: Game, private val master: Boolean, private var rounds: Int) : Task(p), Scened {
        private enum class S { TO_HEAP, PICK, TO_WALL, LAY, KNOCK, DIG, CHEER }

        private val walker = Walker(p)
        private var s = S.TO_HEAP
        private var t = 0
        private val stone = ItemStack(STONES[p.random.nextInt(STONES.size)])

        override val leisure: Boolean get() = true

        override fun start() {
            p.act(Activity.PLAY)
            walker.go(game.heap, Jobs.RUN, near = 1.2)
            if (master) Words.say(p, "child.builders", "children playing builders by the works")
        }

        override fun run(): Boolean {
            t++
            when (s) {
                S.TO_HEAP -> if (walker.tick() != Walker.State.WALKING || t > 300) into(S.PICK, Activity.PICK_UP, game.heap)
                S.PICK -> {
                    if (t == 8) p.carried = stone.copy()
                    if (p.actDone) {
                        p.act(Activity.CARRY_BLOCK)
                        walker.go(game.wall, Jobs.WALK, near = 1.2)
                        s = S.TO_WALL
                        t = 0
                    }
                }
                S.TO_WALL -> if (walker.tick() != Walker.State.WALKING || t > 300) {
                    into(S.LAY, if (p.random.nextBoolean()) Activity.LAY_LOW else Activity.LAY_HIGH, game.wall)
                    if (master && p.random.nextInt(3) == 0) Words.say(p, "child.builders", "children playing builders by the works")
                }
                S.LAY -> {
                    if (t == 10) p.carried = ItemStack.EMPTY
                    if (p.actDone) into(S.KNOCK, Activity.NAIL, game.wall)
                }
                S.KNOCK -> {
                    face(game.wall)
                    if (t >= Activity.NAIL.ticks * 3) {
                        if (p.random.nextInt(2) == 0) into(S.DIG, Activity.DIG, game.patch) else into(S.CHEER, if (master) Activity.POINT else Activity.JUMP, game.wall)
                    }
                }
                S.DIG -> {
                    face(game.patch)
                    if (t >= Activity.DIG.ticks * 3) into(S.CHEER, if (p.random.nextBoolean()) Activity.JUMP else Activity.CLAP, game.wall)
                }
                S.CHEER -> if (p.actDone) {
                    if (--rounds <= 0) return false
                    p.act(Activity.PLAY)
                    walker.go(game.heap, Jobs.RUN, near = 1.2)
                    s = S.TO_HEAP
                    t = 0
                }
            }
            return age < 3600
        }

        private fun into(next: S, activity: Activity, at: BlockPos) {
            walker.stop()
            face(at)
            p.act(activity)
            s = next
            t = 0
        }

        private fun face(at: BlockPos) {
            val there = Vec3(at.x + 0.5, at.y + 0.5, at.z + 0.5)
            p.face(p.yawTo(there))
            p.lookAt(there)
        }

        override fun stop() {
            walker.stop()
            p.carried = ItemStack.EMPTY
            game.players.remove(p)
            if (p.activity != Activity.NONE) p.act(Activity.NONE)
        }
    }

    fun forget() = games.clear()
}
