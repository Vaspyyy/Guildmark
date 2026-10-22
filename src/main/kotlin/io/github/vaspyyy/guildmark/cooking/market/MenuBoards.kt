package io.github.vaspyyy.guildmark.cooking.market

import io.github.vaspyyy.guildmark.cooking.CookSounds
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Door
import io.github.vaspyyy.guildmark.cooking.Station
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.cooking.vessel.StationProps
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.SupplierRank
import io.github.vaspyyy.guildmark.economy.Suppliers
import io.github.vaspyyy.guildmark.townsfolk.Furnish
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import java.util.UUID

/**
 * What is chalked on the Menu boards that hang at a capital's inn (living-world/cook/design.md 5.4), and what using a board
 * does. A board looks to itself every five seconds while a player is near (it is ticked only in loaded land, so nothing is
 * scanned): it finds which capital's inn it hangs at, once, and writes the day's lines from the ledger, which is to say from
 * what the seed and the day and the sales so far make true. So it changes as the day does (at the town's new day, the three
 * wanted kinds and the request), as the inn fills (a kind it will take no more of is marked "full", the request when it is
 * filled), and as the pantry does (a cook's stew in the pot, a Purveyor's name), with a scratch of chalk and a little dust when a
 * player is by to see it. A board another keeper has written on ([MenuBoardBlockEntity.setLines]: a cookshop's, a pantry's) is
 * theirs and is left alone. With the back doors off every inn board is blank.
 */
object MenuBoards {
    /** A board that has not yet found out where it hangs, and one that hangs at no inn (its capital is looked for again from time to time). */
    const val UNKNOWN = Long.MIN_VALUE + 1
    private const val ELSEWHERE = Long.MIN_VALUE + 2

    /** Ticks between a board's looks at its lines, and between its looks at where it hangs when it has found nowhere. */
    private const val EVERY = 100L
    private const val AGAIN = 1200L

    /** A board is written only with a player within this many blocks; the dust is for those who are. */
    private const val SEEN = 48.0

    /** A board belongs to an inn if it hangs within this many blocks of the inn's walls (by the door, under the overhang, or inside). */
    private const val ABOUT = 6

    /** What happens when a player uses a board with an empty hand, before it is read out: Part H's till, for a cookshop's. */
    fun interface Use {
        /** Whether it dealt with the use itself (so the board is not read out). */
        fun use(level: ServerLevel, pos: BlockPos, player: ServerPlayer): Boolean
    }

    private val uses = ArrayList<Use>()

    /** Adds a [use] to the ones tried, in the order added, when a board is used. */
    fun onUse(use: Use) {
        uses.add(use)
    }

    /** The lines of an inn's board, and the dish kind of each ("" for none). */
    class Chalk(val lines: List<Component>, val kinds: List<String>)

    /** The ticker of every loaded board, on the server (see [MenuBoardBlock.getTicker]). */
    fun tick(level: ServerLevel, pos: BlockPos, board: MenuBoardBlockEntity) {
        if (board.kept || Math.floorMod(level.gameTime + pos.asLong(), EVERY) != 0L) return
        if (level.getNearestPlayer(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, SEEN, false) == null) return
        val key = homeOf(level, pos, board)
        if (key == UNKNOWN || key == ELSEWHERE) return
        val chalk = chalkFor(level, key)
        val changed = board.chalk(chalk?.lines ?: emptyList(), chalk?.kinds ?: emptyList())
        if (changed && chalk != null) scratch(level, pos, board)
    }

    /**
     * The key of the capital whose inn [board] at [pos] hangs at, or [ELSEWHERE] if it hangs at none, or [UNKNOWN] while that
     * cannot be said yet (the capital's places are worked out the first time they are asked for). Remembered on the board.
     */
    private fun homeOf(level: ServerLevel, pos: BlockPos, board: MenuBoardBlockEntity): Long {
        val now = level.gameTime
        val known = board.home
        if (known != UNKNOWN && (known != ELSEWHERE || now - board.looked < AGAIN)) return known
        board.looked = now
        val capital = Furnish.capitalAt(level, pos)
        if (capital == null) {
            board.home = ELSEWHERE
            return ELSEWHERE
        }
        val key = capital.center.asLong()
        val box = Pantry.innBox(level, key) ?: return UNKNOWN
        board.home = if (box.inflatedBy(ABOUT).isInside(pos)) key else ELSEWHERE
        return board.home
    }

    /**
     * The lines the inn of the capital keyed [key] chalks today, or null for a blank board (the back doors off, no market, no
     * wanted kinds): the heading, the three kinds the inn wants (greyed and marked once it will take no more of one today), the
     * request (greyed once it is filled), then, if the pantry holds a pot dish of a cook, "Tonight's pot" and, if a Purveyor sold the
     * most today, "Cook of the day". Read from the ledger and written nowhere.
     */
    fun chalkFor(level: ServerLevel, key: Long): Chalk? {
        if (!Cooking.doors() || !Markets.enabled()) return null
        val entry = Markets.find(level, key) ?: return null
        val kitchens = Kitchens.get(level)
        val day = Cooking.today(level)
        val wanted = kitchens.wanted(level, key, day)
        if (wanted.isEmpty()) return null
        val rec = kitchens.rec(key)?.takeIf { it.day == day }
        val lines = ArrayList<Component>()
        val kinds = ArrayList<String>()
        lines.add(Component.translatable("gui.guildmark.cook.menu.wanted"))
        kinds.add("")
        for (kind in wanted) {
            val name = Owed.dish(kind)
            lines.add(if (full(rec, kind, entry.town.people)) Component.translatable("gui.guildmark.cook.menu.full", name).withStyle(ChatFormatting.GRAY) else name)
            kinds.add(kind.id)
        }
        kitchens.request(level, key, day)?.let { ask ->
            val name = Owed.dish(ask.kind)
            lines.add(
                if (ask.filled) Component.translatable("gui.guildmark.cook.menu.request.filled", name).withStyle(ChatFormatting.GRAY)
                else Component.translatable("gui.guildmark.cook.menu.request", name, ask.portions.toString()),
            )
            kinds.add(ask.kind.id)
        }
        tonightsPot(kitchens, key)?.let { lot ->
            lines.add(Component.translatable("gui.guildmark.cook.menu.pot", tierWord(lot.tier), Owed.dish(lot.kind), lot.cookName))
            kinds.add(lot.kind.id)
        }
        cookOfTheDay(level, rec, key)?.let { name ->
            lines.add(Component.translatable("gui.guildmark.cook.menu.cook", name))
            kinds.add("")
        }
        return Chalk(lines, kinds)
    }

    /** Whether the inn will take no more of [kind] today: its day is full, or this kind's share of it is (the same sums the sale makes, 5.2). */
    private fun full(rec: Rec?, kind: DishKind, people: Int): Boolean {
        rec ?: return false
        return rec.soldAt(Door.INN) >= Doors.cap(Door.INN, people) || rec.sold[Door.INN.ordinal][kind.ordinal] >= Doors.kindCap(Door.INN, people)
    }

    /** The pot dish of a cook that the inn's pantry holds: the best tier, the newest of that tier; none if it holds none. */
    private fun tonightsPot(kitchens: Kitchens, key: Long): Lot? {
        var best: Lot? = null
        for (lot in kitchens.lots(key, Door.INN)) {
            if (lot.cook == null || lot.cookName.isEmpty() || lot.portions <= 0 || lot.kind.station != Station.STEWPOT) continue
            if (best == null || lot.tier.ordinal >= best.tier.ordinal) best = lot
        }
        return best
    }

    /** The name of the cook who sold the most portions today at the inn and the bakery, if they are a Purveyor of this capital (6.3). */
    private fun cookOfTheDay(level: ServerLevel, rec: Rec?, key: Long): String? {
        rec ?: return null
        if (!Suppliers.enabled()) return null
        val top = rec.mine.entries
            .map { it.key to it.value.getOrElse(Door.INN.ordinal) { 0 } + it.value.getOrElse(Door.BAKERY.ordinal) { 0 } }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<UUID, Int>> { it.second }.thenBy { it.first })
            .firstOrNull() ?: return null
        if (Suppliers.rank(level, BlockPos.of(key), top.first) < SupplierRank.PURVEYOR.ordinal) return null
        return level.server.playerList.getPlayer(top.first)?.name?.string
            ?: level.server.services().nameToIdCache().get(top.first).map { it.name }.orElse(null)
    }

    /** The word for a dish's tier ("Fine"), the same the keeper's action bar line uses. */
    private fun tierWord(tier: Tier): Component = Component.translatable(
        when (tier) {
            Tier.HALLMARKED -> "message.guildmark.cook.door.tier.hallmarked"
            Tier.FINE -> "message.guildmark.cook.door.tier.fine"
            Tier.SOUND -> "message.guildmark.cook.door.tier.sound"
            Tier.SPOILT -> "message.guildmark.cook.door.tier.spoilt"
            Tier.PLAIN -> "message.guildmark.cook.door.tier.plain"
        },
    )

    /** The scratch of chalk and a little dust in front of the slate as it is written up. */
    private fun scratch(level: ServerLevel, pos: BlockPos, board: MenuBoardBlockEntity) {
        val front = board.blockState.getValue(StationProps.FACING)
        level.sendParticles(ParticleTypes.WHITE_ASH, pos.x + 0.5 + front.stepX * 0.3, pos.y + 0.7, pos.z + 0.5 + front.stepZ * 0.3, 7, 0.22, 0.14, 0.22, 0.01)
        level.playSound(null, pos, CookSounds.get("cook.chalk"), SoundSource.BLOCKS, 0.8f, 0.95f + level.random.nextFloat() * 0.1f)
    }

    /**
     * [player] uses the board at [pos] with an empty hand: whatever has been added with [onUse] is tried first (a cookshop's
     * owner takes the till), else the board is read out, so a slate can be read plainly as well as seen.
     */
    fun read(level: ServerLevel, pos: BlockPos, player: ServerPlayer) {
        if (uses.any { it.use(level, pos, player) }) return
        if (!Cooking.enabled()) {
            player.sendOverlayMessage(Component.translatable("gui.guildmark.cook.menu.off"))
            return
        }
        val lines = (level.getBlockEntity(pos) as? MenuBoardBlockEntity)?.lines ?: emptyList()
        if (lines.isEmpty()) {
            player.sendOverlayMessage(Component.translatable("gui.guildmark.cook.menu.bare"))
            return
        }
        player.sendSystemMessage(lines[0].copy().withStyle(ChatFormatting.GOLD))
        for (i in 1 until lines.size) player.sendSystemMessage(Component.literal("  ").append(lines[i]))
    }
}
