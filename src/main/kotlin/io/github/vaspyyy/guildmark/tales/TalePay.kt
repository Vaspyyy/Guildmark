package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.companion.Companions
import io.github.vaspyyy.guildmark.companion.Deed
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.economy.Trading
import io.github.vaspyyy.guildmark.party.Parties
import io.github.vaspyyy.guildmark.progression.Milestones
import io.github.vaspyyy.guildmark.progression.Progression
import io.github.vaspyyy.guildmark.quest.Contracts
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.season.Seasons
import io.github.vaspyyy.guildmark.village.Standing
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Prediction
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import java.util.UUID

/**
 * Stage 6 of `Tales.finish` (living-world/stories/design.md 6.7), Part E: pay. [run] pays each part that did a real thing
 * (7.2) what the ending is worth, through `Contracts.payOut` so that the talent, `payMultiplier`, the standing, town-control and
 * festival bonuses and the guild XP are a contract's own (7.3); gives the keepsake with it (7.6); and puts the pay of a player who
 * is not on into [TaleData.owed] to be paid on their next login (7.4). Runs once for a tale (the engine guards it) and may run
 * again after a failure: what each part has been paid is kept in [Part.paid], so nobody is paid twice.
 *
 * Who is paid and how much is [TaleShares] (8.0, which corrects 7.2): the player whose deed settled the ending is paid the ending's
 * full points, any other player who did a real step half, each less what the tale's own contract notes have already paid them.
 */
object TalePay {
    /** Whether the ending [ending] leaves a keepsake: A and B the full one, C the lesser, W none (7.6). */
    fun keepsakeFor(ending: String): Boolean = ending == Ending.A || ending == Ending.B || ending == Ending.C

    // ---- Stage 6 ----

    fun run(c: Ctx, t: Tale) {
        val level = c.level
        // A tale the world ended at W gives back whatever its players still have in it; the engine zeroes escrow when a step is met
        if (t.ending == Ending.W) refund(c, t)
        val payees = TaleShares.shares(TaleWords.size(t), t.ending, t.parts)
        if (payees.isEmpty()) return
        val origin = c.here.capital.boardAnchor
        val paidIn = HashSet<UUID>()
        var lead: ServerPlayer? = null
        var leadPaid: Delivered? = null
        for (s in payees) {
            paidIn.add(s.part.player)
            val player = level.server.playerList.getPlayer(s.part.player)
            if (player == null) {
                later(c.data, t, s)
                continue
            }
            val paid = deliver(level, player, t, s, origin, c.day)
            if (lead == null && paid != null) {
                lead = player
                leadPaid = paid
            }
        }
        // A party mate who did nothing real gets the usual quarter of the first payee's pay, as at any contract; one paid for a part of their own gets no more
        if (lead != null && leadPaid != null) share(lead, leadPaid, paidIn)
        c.data.changed()
    }

    /** What was paid to a player: the points, and the Marks `payOut` put in their pack for them. */
    private class Delivered(val points: Int, val marks: Int)

    /**
     * Pays [s] to [player], who is on: the points still owing through `payOut` (the milestone, the companion's deed and the village's
     * standing after it, as a contract's turn-in has them), the keepsake, and the words. The part is marked paid whatever came of it.
     * Null if there was nothing to pay (the tale's notes had paid it all).
     */
    private fun deliver(level: ServerLevel, player: ServerPlayer, t: Tale, s: TaleShares.Share, origin: BlockPos?, day: Long): Delivered? {
        val points = s.owing
        s.part.paid = maxOf(s.part.paid, s.points)
        val keepsake = if (keepsakeFor(t.ending)) keepsake(level, t, t.ending, day) else null
        return give(level, player, points, origin, TaleWords.town(level, t.capital), TaleWords.sentence(level, t), keepsake, t.id)
    }

    /**
     * The one place pay is made: [points] through `payOut` at [origin] (none: no bonus and no standing), the milestone, the companion's
     * deed and the village's standing after it, then the [keepsake] of tale [tale] if there is one, with the words that say what
     * really happened (the Marks and XP `payOut` gave, the standing that really rose). Null if [points] is nothing.
     */
    private fun give(
        level: ServerLevel, player: ServerPlayer, points: Int, origin: BlockPos?, town: String, sentence: Component,
        keepsake: TaleKeepsake?, tale: Int,
    ): Delivered? {
        var done: Delivered? = null
        if (points > 0) {
            val paid = Contracts.payOut(level, player, player.blockPosition(), origin, points, shareWithParty = false, tell = false)
            Milestones.award(player, Milestones.PAID)
            Companions.deed(player, Deed.CONTRACT)
            val before = origin?.let { Standing.points(level, it, player) } ?: 0
            if (origin != null) Standing.add(level, origin, player, points)
            val rose = (origin?.let { Standing.points(level, it, player) } ?: 0) - before
            val key = if (paid.xp > 0) "message.guildmark.tale.paid" else "message.guildmark.tale.paid.marks"
            player.sendSystemMessage(Component.translatable(key, sentence, paid.marks, paid.xp).withStyle(ChatFormatting.GOLD))
            if (rose > 0 && town.isNotEmpty()) {
                player.sendSystemMessage(Component.translatable("message.guildmark.tale.standing", town, rose).withStyle(ChatFormatting.GRAY))
            }
            done = Delivered(points, paid.marks)
        } else if (keepsake != null) {
            player.sendSystemMessage(Component.translatable("message.guildmark.tale.settled", sentence).withStyle(ChatFormatting.GOLD))
        }
        if (keepsake != null && handOut(player.uuid, tale, keepsake.ending)) {
            val stack = TaleKeepsake.stack(keepsake)
            player.inventory.placeItemBackInInventory(stack, Prediction.SERVER_ONLY)
            player.sendSystemMessage(Component.translatable("message.guildmark.tale.keepsake", stack.hoverName).withStyle(ChatFormatting.GRAY))
        }
        return done
    }

    /**
     * The quarter of [lead]'s pay the party's other members nearby are given (`Parties.PAY_SHARE`), for those who were not in [paid]
     * (paid for a part of their own): the Marks (at least one) and the XP of its points, as `Parties.sharePay` gives them for a contract.
     */
    private fun share(lead: ServerPlayer, pay: Delivered, paid: Set<UUID>) {
        val mates = Parties.nearbyMates(lead).filter { it.uuid !in paid }
        if (mates.isEmpty()) return
        val marks = (pay.marks / Parties.PAY_SHARE).coerceAtLeast(1)
        val xp = pay.points * Progression.XP_PER_MARK / Parties.PAY_SHARE
        for (mate in mates) {
            mate.inventory.placeItemBackInInventory(ItemStack(ModItems.GUILD_MARK.get(), marks), Prediction.SERVER_ONLY)
            mate.sendOverlayMessage(Component.translatable("message.guildmark.tale.share", marks, lead.displayName))
            Progression.addXp(mate, xp)
        }
    }

    /** What [Tales.pay] does: pays [player] their share of the ended tale [t], less what they have been paid; its other players are left to their own call. */
    fun pay(level: ServerLevel, player: ServerPlayer, t: Tale) {
        if (t.ending.isEmpty()) return
        val s = TaleShares.shares(TaleWords.size(t), t.ending, t.parts).firstOrNull { it.part.player == player.uuid } ?: return
        deliver(level, player, t, s, origin(level, t), t.ended.takeIf { it != TaleData.NEVER } ?: 0L)
        TaleData.get(level).changed()
    }

    /** The board anchor of [t]'s capital, where its standing and bonuses are, or null while the capital cannot be found. */
    private fun origin(level: ServerLevel, t: Tale): BlockPos? = CaravanRoutes.capital(level, t.capital)?.boardAnchor

    // ---- The keepsake ----

    /** The keepsakes handed out this session, by player, tale and ending, so that a stage that runs again after a failure does not give a second. */
    private val handedOut = HashSet<String>()

    private fun handOut(player: UUID, tale: Int, ending: String): Boolean = handedOut.add("$player/$tale/$ending")

    /** What [t] ended [ending] leaves: its template and word, the town, the day, the season and year if the calendar is on, the names and the lead. */
    fun keepsake(level: ServerLevel, t: Tale, ending: String, day: Long): TaleKeepsake {
        val ended = t.ended.takeIf { it != TaleData.NEVER } ?: day
        val date = if (Seasons.enabled()) Seasons.dateOn(level.server, ended) else null
        return TaleKeepsake(
            t.template, ending, TaleWords.word(t, ending), TaleWords.town(level, t.capital), ended,
            date?.season?.id.orEmpty(), date?.year ?: 0, TaleWords.names(t).take(2), TaleWords.lead(level, t),
        )
    }

    // ---- Away, and refunds ----

    /**
     * Gives back every part's escrow in full (7.5 rule 3): to a player who is on, their Marks in the pack and a message; to one who is
     * not, a line in [TaleData.owed] for their next login. Then the escrow is nothing. Nobody loses Marks to a tale that did not happen.
     */
    fun refund(c: Ctx, t: Tale) {
        val level = c.level
        var any = false
        for (p in t.parts) {
            val marks = p.escrow
            if (marks <= 0) continue
            any = true
            p.escrow = 0
            val player = level.server.playerList.getPlayer(p.player)
            if (player != null) {
                Trading.give(player, ModItems.GUILD_MARK.get(), marks)
                player.sendSystemMessage(Component.translatable("message.guildmark.tale.refund", marks).withStyle(ChatFormatting.GOLD))
            } else {
                TaleOwed.add(c.data, TaleData.Owed(p.player, t.id, t.template, 0, t.ending, marks))
            }
        }
        if (any) c.data.changed()
    }

    /** A player who is not on is owed [s]: a line for their next login with the points still owing, and the part is marked paid. */
    private fun later(data: TaleData, t: Tale, s: TaleShares.Share) {
        val points = s.owing
        s.part.paid = maxOf(s.part.paid, s.points)
        TaleOwed.add(data, TaleData.Owed(s.part.player, t.id, t.template, points, t.ending, 0))
    }

    /**
     * Pays [player] what is owed them in [TaleData.owed], once each, oldest first (7.4): pay earned while they were away, and Marks
     * given back. Each line is taken off before it is paid. Called on login and when the market board or a works screen opens, and
     * with tales switched off too: what was earned is paid.
     */
    fun owed(level: ServerLevel, player: ServerPlayer) {
        if (!Tales.READY) return
        val data = TaleData.get(level)
        val lines = data.owed.filter { it.player == player.uuid }
        if (lines.isEmpty()) return
        data.owed.removeAll(lines)
        data.changed()
        if (lines.any { !TaleOwed.refunds(it) }) player.sendSystemMessage(Component.translatable("message.guildmark.tale.owed").withStyle(ChatFormatting.GRAY))
        for (l in lines) {
            // A line that fails is put back for the next time and the others are still paid: nothing earned is lost to one bad line
            try {
                payLine(level, player, data, l)
            } catch (e: RuntimeException) {
                Guildmark.LOGGER.warn("Tale {}: could not pay a line owed to {}, kept for later", l.tale, player.name.string, e)
                TaleOwed.add(data, l)
            }
        }
    }

    /** Pays one line of [owed]: the Marks given back, or the pay earned and its keepsake. */
    private fun payLine(level: ServerLevel, player: ServerPlayer, data: TaleData, l: TaleData.Owed) {
        if (TaleOwed.refunds(l)) {
            Trading.give(player, ModItems.GUILD_MARK.get(), l.marks)
            player.sendSystemMessage(Component.translatable("message.guildmark.tale.refund", l.marks).withStyle(ChatFormatting.GOLD))
            return
        }
        // The tale is kept for forty days after it ended; a player away longer is paid without its words, its town's bonus and its standing
        val t = data[l.tale]
        val sentence: Component = t?.let { TaleWords.sentence(level, it) } ?: Component.translatable("message.guildmark.tale.unknown")
        val keepsake = if (keepsakeFor(l.ending)) {
            t?.let { keepsake(level, it, l.ending, 0L) }
                ?: TaleKeepsake(l.template, l.ending, Templates.byId(l.template)?.ending(l.ending)?.word ?: l.ending.lowercase())
        } else null
        give(level, player, l.points, t?.let { origin(level, it) }, t?.let { TaleWords.town(level, it.capital) }.orEmpty(), sentence, keepsake, l.tale)
    }

    /** Pays what is owed on login, from a listener of this file's own (so it also runs with tales switched off). Called once, from setup. */
    fun listen() {
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent::class.java) { event ->
            val player = event.entity as? ServerPlayer ?: return@addListener
            owed(player.level().server.overworld(), player)
        }
    }
}
