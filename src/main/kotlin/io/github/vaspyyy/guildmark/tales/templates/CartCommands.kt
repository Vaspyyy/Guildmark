package io.github.vaspyyy.guildmark.tales.templates

import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.cart.Handcarts
import io.github.vaspyyy.guildmark.tales.Sets
import io.github.vaspyyy.guildmark.tales.TaleBodies
import io.github.vaspyyy.guildmark.tales.TaleCommand
import io.github.vaspyyy.guildmark.tales.TaleData
import io.github.vaspyyy.guildmark.tales.TaleEngine
import io.github.vaspyyy.guildmark.tales.TaleLook
import io.github.vaspyyy.guildmark.tales.TaleSceneCommand
import io.github.vaspyyy.guildmark.tales.Tales
import io.github.vaspyyy.guildmark.townsfolk.Comings
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Residents
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt

/**
 * The handcart tale's commands (living-world/stories/design.md 12.4), game masters only:
 *
 *  - `/guildmark tale cart`: the cart tale of the capital you stand in, from the record, in words: the family and who is away, the neighbour
 *    and the guard, the place on the road and the gate, what has happened (found, mended, the choice for the night, the fire, the kills, the
 *    watch paid, first light), and what this run keeps (the camp's layout, the Wheel, the going). It reads; it changes nothing.
 *  - `/guildmark tale scene wheel`: *The Wheel*, played now three blocks before you by the nearest free adult with a cart of their own that
 *    loses its near wheel for it, whatever the hour or the switches say. No tale is needed or touched; the cart goes once no one looks.
 */
internal object CartCommands {
    /** Called once from `TalesT2a.register`, after the `tale scene` command has its own scenes. */
    fun register() {
        TaleSceneCommand.add("wheel", ::wheel)
        TaleCommand.sub += { tale -> tale.then(Commands.literal("cart").executes { show(it) }) }
    }

    // ---- tale cart ----

    private fun show(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        TaleCommand.off()?.let { TaleCommand.say(source, TaleCommand.grey(it)) }
        val here = TaleCommand.here(context) ?: return 0
        val level = source.level
        TaleEngine.use(level)
        val tales = TaleData.get(level).all().filter { it.template == Cart.id && it.capital == here.key }.sortedBy { it.id }
        TaleCommand.say(source, TaleCommand.header("The Handcart by the Wardfire in ${here.name}"))
        val t = tales.lastOrNull { it.open } ?: tales.lastOrNull()
        if (t == null) return TaleCommand.say(source, TaleCommand.plain("No cart tale is on record here."))
        val state = if (t.open) "open, at step ${t.step}" else "ended at ${t.ending} on day ${t.ended}"
        TaleCommand.say(source, line("Tale", "#${t.id}, $state"))
        val away = Cart.moved(level, t)
        val parent = Residents.get(level)[Cart.parentId(t)]
        TaleCommand.say(
            source,
            line(
                "The family", "the ${Cart.family(t)}, household #${Cart.household(t)}, " +
                    (away?.let { "away since market day ${it.day} (today is ${Comings.dated(level)})" } ?: "not away (home, or gone from the record)"),
            ),
        )
        TaleCommand.say(source, line("At the camp", Cart.familySlots(t).joinToString(", ") { slot -> "${t.cast(slot)?.name.orEmpty()} ($slot)" }))
        TaleCommand.say(source, line("The parent's record", parent?.let { "#${it.id}, ${if (it.alive) "alive" else "not alive"}, ${if (it.left >= 0) "left on day ${it.left}" else "at home"}" } ?: "(none)"))
        TaleCommand.say(source, line("Asking", t.cast(Cart.NEIGHBOUR)?.name.orEmpty()))
        TaleCommand.say(source, line("The watch", t.cast(Cart.GUARD)?.name ?: "(no guard: the watch is not offered)"))
        val road = Cart.road(t)
        TaleCommand.say(source, line("On the road", "${road.x} ${road.y} ${road.z}, out of the ${Cart.side(t)} gate"))
        val flags = listOf(
            Cart.MET to "found", Cart.MENDED to "mended", Cart.KEEP to "the road to be kept", Cart.WATCH to "the watch asked for",
            Cart.WAIT to "waiting for first light", Cart.LIT to "the fire lit", Cart.SAFE to "the road kept", Cart.PAID to "the watch paid",
            Cart.HOME to "in the comings' hands", Cart.OWN to "came home on their own", Cart.MADE to "the times made up (a forced start)",
        ).filter { Cart.flag(t, it.first) }.joinToString(", ") { it.second }
        TaleCommand.say(source, line("So far", flags.ifEmpty { "nothing yet" }))
        TaleCommand.say(source, line("Counts", "timber ${Cart.timberGiven(t)}/${Cart.TIMBER}, kills ${Cart.kills(t)}/${Cart.wanted(t)}"))
        if (Cart.ready(t)) TaleCommand.say(source, line("First light", "on clock day ${Cart.dawn(t)} at 06:00 (${if (Cart.firstLight(level, t)) "it has come" else "not yet"})"))
        for ((label, text) in CartWatch.describe(t)) TaleCommand.say(source, line(label, text))
        val body = TaleBodies.body(level, t, Cart.PARENT)
        TaleCommand.say(source, line("The parent's body", body?.let { b -> "${if (b.tale == t.id) "the tale's" else "their own day's"} at ${b.blockPosition().toShortString()}" + cartOf(b) } ?: "not about"))
        TaleCommand.say(source, line("The clock", "${Days.hourOf(Days.offsetOf(level.overworldClockTime))}: dusk at 19:00, first light at 06:00, the close at 02:00"))
        if (!Tales.live) TaleCommand.say(source, TaleCommand.grey("Tales are off, so nothing on the road moves."))
        return 1
    }

    private fun cartOf(b: io.github.vaspyyy.guildmark.townsfolk.Townsperson): String = when {
        Handcarts.pulling(b) -> ", the shafts in hand"
        else -> Handcarts.parked(b)?.let { if (it.broken) ", the cart parked with its near wheel off" else ", the cart parked, whole" } ?: ", no cart"
    }

    private fun line(label: String, text: String) = TaleCommand.line().append(TaleCommand.label("$label: ")).append(TaleCommand.plain(text))

    // ---- tale scene wheel ----

    /** The Wheel three blocks before [player]: the nearest free adult's own cart, square to the player's look, its near wheel toward them. */
    private fun wheel(level: ServerLevel, player: ServerPlayer): String {
        if (!Handcarts.enabled()) throw TaleSceneCommand.Unplayable("Handcarts are off (handcarts in the config).")
        val look = player.lookAngle
        val len = sqrt(look.x * look.x + look.z * look.z)
        if (len < 0.1) throw TaleSceneCommand.Unplayable("Look along the ground, not up or down.")
        val ahead = Vec3(look.x / len, 0.0, look.z / len)
        val at = player.position().add(ahead.scale(3.0))
        val ground = Sets.standable(level, net.minecraft.core.BlockPos.containing(at)) ?: throw TaleSceneCommand.Unplayable("There is nowhere for a cart three blocks before you.")
        val centre = Vec3(at.x, ground.y.toDouble(), at.z)
        val r = ahead.scale(-1.0)
        val f = Vec3(r.z, 0.0, -r.x)
        val p = Sets.freeAdults(level, player.position(), 24.0).firstOrNull() ?: throw TaleSceneCommand.Unplayable("Nobody is free within 24 blocks to mend a wheel.")
        val cart = Handcarts.hitch(p) ?: throw TaleSceneCommand.Unplayable("There is no room for a cart by ${p.name.string}.")
        Handcarts.unhitch(p)
        val yaw = Math.toDegrees(kotlin.math.atan2(-f.x, f.z)).toFloat()
        cart.setPos(centre.x, centre.y, centre.z)
        cart.yRot = yaw
        cart.yRotO = yaw
        cart.settle(level)
        Handcarts.heap(p, Handcarts.household(3).filter { it.place != CartScenes.BUNDLE_PLACE })
        cart.broken = true
        // Nobody's from now on: it stays while it is seen, and goes once no one looks
        Handcarts.leave(p)
        fun spot(along: Double, side: Double, up: Double = 0.0) = Vec3(centre.x + r.x * along + f.x * side, centre.y + up, centre.z + r.z * along + f.z * side)
        val stage = CartScenes.wheel(level, spot(0.88, 0.66, 0.62), p, listOf(), player, stand = spot(1.9, 0.66)) { cart.broken = false }
        if (!stage.start(level)) {
            cart.discard()
            throw TaleSceneCommand.Unplayable("${p.name.string} could not be taken for it.")
        }
        TaleLook.note(stage)
        return "The Wheel: ${p.name.string} mends the cart three blocks before you (about fourteen seconds)."
    }
}
