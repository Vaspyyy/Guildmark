package io.github.vaspyyy.guildmark.cooking.market

import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Door
import io.github.vaspyyy.guildmark.economy.SupplierRank
import io.github.vaspyyy.guildmark.economy.Suppliers
import io.github.vaspyyy.guildmark.economy.Trading
import io.github.vaspyyy.guildmark.registry.ModItems
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import java.util.IdentityHashMap
import java.util.UUID

/**
 * The Marks a back door owes (living-world/cook/design.md 7.7): the one path every Mark a dish earns takes to a player's
 * hand, so none is paid twice or lost whatever a sale's scene does.
 *
 * When a sale is made ([Kitchens.sell]) its Marks are written down at once as a slip in [Kitchens.owing], in the same step
 * that takes the portions off the stack. The scene counts them out ([pay], with the keeper's hands); if it is cut short (the
 * player logs off or dies or walks off, the keeper is hurt or taken, twelve seconds pass) [settle] pays at once if the player
 * is there and alive, and otherwise the slip simply stays where it was written, to be paid at the next login or respawn
 * ([all]). A slip leaves the ledger in the very step that pays it, so it can be paid only once; and because it was saved
 * with the sale, a server that stops in the middle of a scene loses nothing. [sweep] is the last net: a scene whose keeper
 * was unloaded, killed or taken, and so can never finish, has its slip paid a moment after it is over.
 */
object Owed {
    /** A sale's scene, which says when it is over (the keeper gone, taken or hurt, or the time up) for [sweep]. */
    fun interface Scene {
        fun over(level: ServerLevel, now: Long): Boolean
    }

    /** What paying a slip came to: the Marks given, and the rank they raised the player to at the capital, if they did. */
    class Paid(val marks: Int, val up: SupplierRank?)

    /** Slips whose scene is under way, so [sweep] leaves them to it until it is over. */
    private val live = IdentityHashMap<Owing, Scene>()

    init {
        Cooking.onForget { live.clear() }
    }

    /** The whole Marks owed to [player], of every slip. */
    fun total(kitchens: Kitchens, player: UUID): Int = kitchens.owing[player]?.sumOf { it.marks } ?: 0

    /** The slips owed to [player], oldest first. */
    fun slips(kitchens: Kitchens, player: UUID): List<Owing> = kitchens.owing[player]?.toList() ?: emptyList()

    /**
     * Writes down that [marks] whole Marks are owed to [player] for a dish of [kind] sold at [door] of the capital keyed
     * [capital], to be paid by [pay] or [settle] (or, failing both, at the next login). With a [scene] it is left to that scene
     * until it is over. Nothing is written for no Marks.
     */
    fun owe(kitchens: Kitchens, player: UUID, capital: Long, door: Door, kind: DishKind?, marks: Int, scene: Scene? = null): Owing? {
        if (marks <= 0) return null
        val slip = Owing(door, kind, marks, capital)
        kitchens.owing.getOrPut(player) { ArrayList() }.add(slip)
        if (scene != null) live[slip] = scene
        kitchens.changed()
        return slip
    }

    /**
     * Pays [slip] to [player] now: the Marks into their pack (what does not fit drops at their feet), a point of standing a Mark
     * at the capital they were earned in, and, if [told], a line saying the Marks have reached them. Gives null if the slip is
     * not owed any more, which is how a slip is never paid twice.
     */
    fun pay(level: ServerLevel, player: ServerPlayer, slip: Owing, told: Boolean): Paid? {
        val kitchens = Kitchens.get(level)
        val list = kitchens.owing[player.uuid] ?: return null
        if (!list.remove(slip)) return null
        if (list.isEmpty()) kitchens.owing.remove(player.uuid)
        live.remove(slip)
        kitchens.changed()
        val marks = slip.marks
        if (marks <= 0) return Paid(0, null)
        Trading.give(player, ModItems.GUILD_MARK.get(), marks)
        var up: SupplierRank? = null
        if (slip.capital != NO_KEY && Suppliers.enabled()) {
            val (before, after) = Suppliers.gain(level, BlockPos.of(slip.capital), player.uuid, marks * Suppliers.SOLD)
            if (after > before) up = after
        }
        if (told) player.sendSystemMessage(reached(slip).withStyle(ChatFormatting.YELLOW))
        return Paid(marks, up)
    }

    /**
     * A scene for [slip] ended before it could pay (7.7): the Marks go to [player] now if they are on and alive, else they stay
     * owed to them for their next login or respawn. Safe to call for a slip already paid.
     */
    fun settle(level: ServerLevel, player: UUID, slip: Owing?): Paid? {
        slip ?: return null
        live.remove(slip)
        val on = level.server.playerList.getPlayer(player) ?: return null
        if (!on.isAlive || on.hasDisconnected()) return null
        return pay(level, on, slip, told = true)
    }

    /** Pays [player] everything that is owed them, with a line for each (their login, their respawn, `cook owed pay`). The Marks paid. */
    fun all(player: ServerPlayer): Int {
        val level = player.level().server.overworld()
        val kitchens = Kitchens.get(level)
        var marks = 0
        for (slip in slips(kitchens, player.uuid)) marks += pay(level, player, slip, told = true)?.marks ?: 0
        return marks
    }

    /**
     * Every few ticks: a slip of a player who is on and alive, whose scene is over (or that has none), is paid. This is for a
     * scene that can never finish, its keeper unloaded or killed or taken in the middle of it.
     */
    fun sweep(server: MinecraftServer) {
        val level = server.overworld()
        val kitchens = Kitchens.get(level)
        if (kitchens.owing.isEmpty()) return
        val now = level.gameTime
        for ((uuid, slips) in kitchens.owing.entries.toList()) {
            val player = server.playerList.getPlayer(uuid) ?: continue
            if (!player.isAlive || player.hasDisconnected()) continue
            for (slip in slips.toList()) {
                val scene = live[slip]
                if (scene != null && !scene.over(level, now)) continue
                pay(level, player, slip, told = true)
            }
        }
    }

    /** How many slips are in a scene now, for the readout. */
    fun inScenes(): Int = live.size

    /** "The innkeeper's 4 Marks for your Raised Pie have reached you." */
    private fun reached(slip: Owing): MutableComponent = Component.translatable(
        "message.guildmark.cook.door.owed", keeper(slip.door), marks(slip.marks), slip.kind?.let { dish(it) } ?: Component.translatable("message.guildmark.cook.door.dishes"),
    )

    /** The name of the dish [kind] ("Raised Pie"). */
    fun dish(kind: DishKind): Component = Component.translatable("item.guildmark.dish_${kind.id}")

    /** The keeper of [door], as a noun. */
    fun keeper(door: Door): Component = Component.translatable(if (door == Door.BAKERY) "message.guildmark.cook.door.keeper.bakery" else "message.guildmark.cook.door.keeper.inn")

    /** "1 Mark", "4 Marks". */
    fun marks(n: Int): Component = Component.translatable(if (n == 1) "message.guildmark.cook.door.mark" else "message.guildmark.cook.door.marks", n)
}
