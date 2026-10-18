package io.github.vaspyyy.guildmark.townsfolk.scenes

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.github.vaspyyy.guildmark.economy.fortune.Times
import io.github.vaspyyy.guildmark.economy.works.WorksState
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Acts
import io.github.vaspyyy.guildmark.townsfolk.CapitalCommand
import io.github.vaspyyy.guildmark.townsfolk.Jobs
import io.github.vaspyyy.guildmark.townsfolk.Labour
import io.github.vaspyyy.guildmark.townsfolk.Later
import io.github.vaspyyy.guildmark.townsfolk.Life
import io.github.vaspyyy.guildmark.townsfolk.Lookout
import io.github.vaspyyy.guildmark.townsfolk.Places
import io.github.vaspyyy.guildmark.townsfolk.Task
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Trades
import io.github.vaspyyy.guildmark.townsfolk.Ways
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * `/guildmark capital scene <name>` (op): plays one of M6's scenes now, near you, whatever the switches say, so each
 * can be seen without first making hard times or raising a works. It takes whoever's free nearby (and stands in for
 * what isn't there yet: a site ahead of you where no works is going up, a table in front of you where no alms table
 * stands, a household of neighbours at the nearest house). The names:
 *
 * - `watch`: passers-by stop to watch the works going up nearest you (or a stand-in site ten blocks ahead).
 * - `builders`: the children nearest play builders by it.
 * - `topping`: the town gathers round it for its topping out, a stand-in master builder calling the cheers.
 * - `queue`: a line at the nearest bakery's door, the loaves out in half a minute.
 * - `plenty`, `haggle`, `basket`: a shopper at the nearest stall as in good times (an armful home), hard times (a
 *   haggle, and maybe no sale), and at a stall sold out in hard times (the empty basket turned over).
 * - `shiver`: the cold bites for two minutes: folk out of doors near you shiver, and those out strolling hurry home.
 * - `grumble`: passers-by stop at the market board and grumble at hard times.
 * - `welcome`, `return`, `farewell`: neighbours greet a stand-in household at the nearest house door, welcome one back
 *   with a hug, or hug one goodbye and wave it off.
 * - `shut`: the nearest stall's keeper lets its awning down, tidies and sits by it, then puts it back up.
 * - `alms`: a stand-in alms table in front of you, someone behind it to ladle, and a line of the hungry.
 * - `labour`: a few citizens wait about a stand-in site as labourers between loads.
 * - `lookout`: the nearest guard keeps a watch where they stand, as on a tower's platform.
 */
internal object SceneCommand {
    val NAMES = listOf(
        "watch", "builders", "topping", "queue", "plenty", "haggle", "basket", "shiver", "grumble",
        "welcome", "return", "farewell", "shut", "alms", "labour", "lookout",
    )

    /** Stand-in works' ids, counted down from here so they're never a real works'. */
    private var standIn = -100

    /** The `scene` node, for [CapitalCommand]'s `capital` branch. */
    fun node(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("scene").then(
        Commands.argument("name", StringArgumentType.word())
            .suggests { _, builder -> SharedSuggestionProvider.suggest(NAMES, builder) }
            .executes { play(it.source, StringArgumentType.getString(it, "name")) },
    )

    private fun play(source: CommandSourceStack, name: String): Int {
        val player = source.playerOrException
        val level = source.level
        Scenes.init()
        val here = Scenes.here(level, player) ?: return fail(source, "You're not in a capital, or its places are still being worked out: try again in a second.")
        val said = when (name) {
            "watch" -> watch(level, player, here)
            "builders" -> builders(level, player, here)
            "topping" -> topping(level, player, here)
            "queue" -> BreadQueue.play(level, player, here).let { n ->
                if (n < 0) null else "$n joined the line at the bakery's door; the loaves come out in half a minute."
            }
            "plenty" -> shopper(level, player, here, Times.THRIVING, bare = false)
            "haggle" -> shopper(level, player, here, Times.HARD, bare = false)
            "basket" -> shopper(level, player, here, Times.HARD, bare = true)
            "shiver" -> shiver(level, player, here)
            "grumble" -> grumble(level, here)
            "welcome" -> neighbours(level, player, here, Neighbours.Kind.ARRIVED)
            "return" -> neighbours(level, player, here, Neighbours.Kind.RETURNED)
            "farewell" -> neighbours(level, player, here, Neighbours.Kind.LEFT)
            "shut" -> if (ShutStall.play(level, player, here)) "The nearest stall's keeper lets its awning down, tidies and sits by it." else null
            "alms" -> Alms.play(level, player, here).let { n -> if (n < 0) null else "A stand-in alms table in front of you for a minute and a half; $n in its line." }
            "labour" -> labour(level, player, here)
            "lookout" -> lookout(level, player)
            else -> return fail(source, "No scene called $name: one of ${NAMES.joinToString(", ")}.")
        } ?: return fail(source, "Nobody free near enough for $name, or nowhere for it here.")
        source.sendSuccess({ Component.literal(said).withStyle(ChatFormatting.GRAY) }, false)
        return 1
    }

    private fun fail(source: CommandSourceStack, message: String): Int {
        source.sendFailure(Component.literal(message))
        return 0
    }

    // --- The works ---

    /** The works going up nearest [player] (within 64 blocks), or a stand-in site: a lot ten blocks ahead of them. */
    private fun site(level: ServerLevel, player: ServerPlayer, here: Scenes.Here): Pair<Watchers.Site, Boolean>? {
        val pos = player.position()
        Scenes.works(level, here.key).filter { it.state == WorksState.BUILDING && it.site != null }
            .minByOrNull { Vec3.atCenterOf(it.site!!.lot.center).distanceToSqr(pos) }
            ?.takeIf { Vec3.atCenterOf(it.site!!.lot.center).distanceToSqr(pos) < 64.0 * 64.0 }
            ?.let { w -> Watchers.site(w)?.let { return it to true } }
        val ahead = player.blockPosition().relative(player.direction, 10)
        val ground = (3 downTo -4).asSequence().map { ahead.above(it) }.firstOrNull { level.isLoaded(it) && Places.standable(level, it) } ?: return null
        val lot = BoundingBox(ground.x - 3, ground.y, ground.z - 3, ground.x + 3, ground.y + 7, ground.z + 3)
        return Watchers.Site(standIn--, lot, 0.6) to false
    }

    private fun watch(level: ServerLevel, player: ServerPlayer, here: Scenes.Here): String? {
        val (site, real) = site(level, player, here) ?: return null
        var n = 0
        repeat(3) { if (Watchers.start(level, site, force = true) != null) n++ }
        if (n == 0) return null
        return "$n stopped to watch " + (if (real) "the works going up." else "a stand-in site ten blocks ahead (no works going up near).")
    }

    private fun builders(level: ServerLevel, player: ServerPlayer, here: Scenes.Here): String? {
        val (site, real) = site(level, player, here) ?: return null
        val at = Vec3.atCenterOf(site.lot.center)
        val children = level.getEntitiesOfClass(Townsperson::class.java, AABB.of(site.lot).inflate(40.0, 8.0, 40.0)) {
            it.role == TownRole.CHILD && Aside.free(it, seated = true)
        }.sortedBy { it.distanceToSqr(at) }.take(3)
        var n = 0
        for (child in children) {
            val game = Builders.join(level, child, site.id, site.lot, force = true) ?: continue
            if (child.seat != null) child.standUp()
            child.routine.replace(game)
            n++
        }
        if (n == 0) return null
        return "$n children play builders by " + (if (real) "the works going up." else "a stand-in site ten blocks ahead.")
    }

    private fun topping(level: ServerLevel, player: ServerPlayer, here: Scenes.Here): String? {
        val (site, real) = site(level, player, here) ?: return null
        val g = ToppingOut.start(level, site.id, site.lot)
        val center = g.center
        val near = level.getEntitiesOfClass(Townsperson::class.java, AABB.of(site.lot).inflate(48.0, 8.0, 48.0)) {
            !site.lot.isInside(it.blockPosition()) && it.role != TownRole.GUARD && Aside.free(it, seated = true)
        }.sortedBy { it.distanceToSqr(center) }.toMutableList()
        // A stand-in master builder on the lot, calling the cheers
        val master = near.firstOrNull { it.role.adult }
        if (master != null) {
            near.remove(master)
            val middle = BlockPos.containing(center).let { c -> (0 downTo -2).asSequence().map { c.above(it) }.firstOrNull { Places.standable(level, it) } ?: c }
            val ring = { Vec3(center.x + 6.0, center.y + 1.5, center.z) }
            Aside.take(master, Aside(listOf(
                GoTo({ middle }, Jobs.WALK, near = 1.0, most = 400),
                Wait({ _, t -> t >= 120 }, 121, at = ring),
                Play(Activity.HURRAH, first = { ToppingOut.cheer(level, site.id) }),
                Wait({ _, t -> t >= 160 }, 161, at = ring),
                Play(Activity.TOAST, first = { ToppingOut.cheer(level, site.id) }),
                Wait({ _, t -> t >= 160 }, 161, at = ring),
                Play(Activity.HURRAH, first = { ToppingOut.cheer(level, site.id) }),
            ), most = 1500, fresh = true), seated = true)
        }
        var n = 0
        for (p in near.take(10)) {
            if (p.seat != null) {
                p.routine.interrupt()
                p.standUp()
            }
            if (ToppingOut.join(level, g, p)) n++
        }
        if (n == 0) return null
        return "$n gather for the topping out of " + (if (real) "the works going up" else "a stand-in site ten blocks ahead") +
            (if (master != null) ", ${master.name.string} calling the cheers." else ".")
    }

    private fun labour(level: ServerLevel, player: ServerPlayer, here: Scenes.Here): String? {
        val (site, _) = site(level, player, here) ?: return null
        val at = BlockPos.containing(Vec3.atCenterOf(site.lot.center)).relative(player.direction.opposite, 6)
        val free = level.getEntitiesOfClass(Townsperson::class.java, AABB(at).inflate(32.0, 8.0, 32.0)) {
            it.role == TownRole.CITIZEN && it.resident >= 0 && it.life != null && Aside.free(it, seated = true)
        }.sortedBy { it.distanceToSqr(Vec3.atCenterOf(at)) }.take(3)
        var n = 0
        for (p in free) {
            val life = p.life ?: continue
            if (p.seat != null) p.standUp()
            p.routine.replace(Ways.to(p, life.places, at, Jobs.WALK, near = 3.0).andThen(Labour.waitAbout(p, life, at)).andThen(Later(p) { Labour.waitAbout(p, life, at) }))
            n++
        }
        if (n == 0) return null
        return "$n citizens wait about a stand-in site as labourers do between loads (the building's crew gives them their work, once it's in)."
    }

    private fun lookout(level: ServerLevel, player: ServerPlayer): String? {
        val guard = level.getEntitiesOfClass(Townsperson::class.java, player.boundingBox.inflate(32.0, 8.0, 32.0)) {
            it.role == TownRole.GUARD && it.isAlive && it.director == null && !it.isSleeping && it.target == null
        }.minByOrNull { it.distanceToSqr(player) } ?: return null
        if (guard.seat != null) guard.standUp()
        guard.routine.replace(Lookout.standIn(level, guard))
        return "${guard.name.string} keeps a watch where they stand, as on a tower's platform: a look out, a walk round, the beacon's torch, and riders called."
    }

    // --- The streets ---

    /** A shopper at the stall nearest [player], as [times] go: buying there (or, if [bare], finding it sold out). */
    private fun shopper(level: ServerLevel, player: ServerPlayer, here: Scenes.Here, times: Times, bare: Boolean): String? {
        val stall = here.places.stalls.minByOrNull { it.customer.pos.distSqr(player.blockPosition()) } ?: return null
        if (stall.customer.pos.distSqr(player.blockPosition()) > 48.0 * 48.0) return null
        val at = Vec3.atCenterOf(stall.customer.pos)
        val p = level.getEntitiesOfClass(Townsperson::class.java, AABB(stall.customer.pos).inflate(24.0, 6.0, 24.0)) {
            it.role.adult && it.role != TownRole.GUARD && it.role != TownRole.MERCHANT && it.life != null && Aside.free(it, seated = true)
        }.minByOrNull { it.distanceToSqr(at) } ?: return null
        val life = p.life ?: return null
        Shopping.force(p, times, 1800)
        val wares = if (bare) listOf() else Trades.wares(Trades.market(p, life), stall.trade).ifEmpty { listOf(Items.BREAD) }
        val visit = Shopping.at(p, life, stall, wares, times, next = { null }) ?: return null
        if (p.seat != null) p.standUp()
        p.routine.replace(visit.andThen(Later(p) { home(p, life) }))
        return when {
            bare -> "${p.name.string} finds the nearest stall sold out, as in hard times."
            times == Times.HARD -> "${p.name.string} haggles at the nearest stall, as in hard times."
            else -> "${p.name.string} buys an armful at the nearest stall, as in good times."
        }
    }

    /** What's in [p]'s hands taken home (an armful in both arms, a basket on the hip), and put down there. */
    private fun home(p: Townsperson, life: Life): Task? {
        val goods = p.carried
        if (goods.isEmpty) return null
        val door = life.home?.front ?: return Acts.hold(p, ItemStack.EMPTY)
        val overlay = if (Shopping.armful(goods)) Activity.CARRY else Activity.CARRY_BASKET
        return Ways.to(p, life.places, door.inside, Trades.LADEN, overlay = overlay)
            .andThen(Acts.gesture(p, Activity.PUT_DOWN)).andThen(Acts.hold(p, ItemStack.EMPTY))
    }

    private fun shiver(level: ServerLevel, player: ServerPlayer, here: Scenes.Here): String? {
        Cold.force(level, here.key, 2400)
        var n = 0
        repeat(3) { if (Cold.start(level, player, here, force = true) != null) n++ }
        return "The cold bites here for two minutes: $n shivering now, more as they pass, and those out strolling hurry home."
    }

    private fun grumble(level: ServerLevel, here: Scenes.Here): String? {
        var n = 0
        repeat(2) { if (Grumbling.start(level, here, Times.HARD, force = true) != null) n++ }
        if (n == 0) return null
        return "$n stopped at the market board to grumble at hard times."
    }

    private fun neighbours(level: ServerLevel, player: ServerPlayer, here: Scenes.Here, kind: Neighbours.Kind): String? {
        val n = Neighbours.play(level, player, here, kind)
        if (n <= 0) return null
        return "A stand-in household comes to the nearest house door, and $n of the neighbours " + when (kind) {
            Neighbours.Kind.ARRIVED -> "welcome them in."
            Neighbours.Kind.RETURNED -> "welcome them back."
            Neighbours.Kind.LEFT -> "see them off."
        }
    }
}
