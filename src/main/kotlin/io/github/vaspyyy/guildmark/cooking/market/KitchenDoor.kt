package io.github.vaspyyy.guildmark.cooking.market

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.CookSounds
import io.github.vaspyyy.guildmark.cooking.Door
import io.github.vaspyyy.guildmark.cooking.Stage
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.cooking.Verdict
import io.github.vaspyyy.guildmark.economy.Shop
import io.github.vaspyyy.guildmark.economy.StallTalk
import io.github.vaspyyy.guildmark.economy.SupplierRank
import io.github.vaspyyy.guildmark.economy.Suppliers
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Days
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.Doing
import io.github.vaspyyy.guildmark.townsfolk.Life
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.Trait
import io.github.vaspyyy.guildmark.townsfolk.talk.TownTalk
import io.github.vaspyyy.guildmark.townsfolk.talk.sources.KitchenRumours
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemStack
import java.util.Locale
import java.util.UUID

/**
 * The keeper's side of a sale at the back door (living-world/cook/design.md 7.7): a [Director] that takes the innkeeper or
 * the baker for the few seconds it takes to buy a dish, in the order the table of 7.7 gives. She turns, tastes a portion with a
 * spoon, says her verdict on the mix (when the batch carries one: [DishMix]), the stale remark if it was stale, a remark by tier
 * (with the tier's motif, a sparkle for a Fine dish or better, and, for a Hallmarked one, the rumour), that it is the first of
 * its kind from this cook or that it fills the request, counts the Marks out and hands them over, and goes back to her day.
 *
 * Nothing is lost whatever happens in the middle. The ledger is written, and the Marks written down as a slip that waits in
 * it ([Owed]), in the one step that takes the portions off the stack; the Pay beat gives them, and a scene cut short (the player
 * logs off, dies or walks off, the keeper is hurt or taken or unloaded, twelve seconds pass) has them given at once if the
 * player is there and alive and left in the ledger for their next login or respawn if not. A refusal (a full door, a dish gone
 * off, a wrong door) is a short scene of its own, a turn, a gesture and the line, and takes nothing.
 */
class KitchenDoor private constructor(
    private val player: UUID,
    private val door: Door,
    private val capital: Long,
) : Director, Owed.Scene {
    /** One step of the scene: [activity] for [ticks] with [held] in the keeper's hand, [coins] clinks of coin from its start, and [begin] run as it starts. */
    private class Beat(
        val activity: Activity,
        val ticks: Int,
        val held: ItemStack = ItemStack.EMPTY,
        val coins: Int = 0,
        val begin: (Townsperson, ServerLevel) -> Unit = { _, _ -> },
    )

    private val beats = ArrayDeque<Beat>()

    /** The tick (the keeper's own) the step under way ends; -1 with none. */
    private var beatEnds = -1

    private var keeper: Townsperson? = null
    private var started = 0L
    private var health = 0f

    /** Clinks of coin still to sound, and the tick of the next. */
    private var clinks = 0
    private var clinkAt = 0

    /** A line to say a little later (the role a verdict is about, after the take), and when. */
    private var after: String? = null
    private var afterAt = 0L

    /** The Marks owed for this sale, until they are given; null once paid, or when the sale paid none. */
    private var slip: Owing? = null

    /** Whether the scene is over: the keeper is let go, whatever else follows. */
    private var ended = false

    // ---- The scene, tick by tick ----

    override fun over(level: ServerLevel, now: Long): Boolean {
        if (ended) return true
        val p = keeper ?: return true
        return p.isRemoved || !p.isAlive || p.director !== this || now - started > CUTOFF + FINISH
    }

    override fun tick(p: Townsperson) {
        val level = p.level() as? ServerLevel ?: return
        val now = level.gameTime
        val who = level.getPlayerByUUID(player) as? ServerPlayer
        // Cut short: the player has gone, died or walked off, the keeper is hurt, or twelve seconds pass before the Marks are given
        val late = now - started > (if (slip != null) CUTOFF else CUTOFF + FINISH)
        val hurt = p.hurtTime > 0 || p.health < health - 0.01f
        if (who == null || !who.isAlive || who.hasDisconnected() || who.distanceToSqr(p) > FAR * FAR || hurt || late) return cut(p, level)
        p.navigation.stop()
        // Busy: no calling out to passers-by while she serves
        p.nextSpeech = now + 100
        p.lookControl.setLookAt(who, 30f, 30f)
        p.face(Mth.approachDegrees(p.yBodyRot, p.yawTo(who.position()), TURN_RATE))
        p.attend(who, 40)
        if (beatEnds >= 0 && p.tickCount >= beatEnds) next(p, level)
        if (clinks > 0 && p.tickCount >= clinkAt) {
            clinks--
            clinkAt = p.tickCount + CLINK
            level.playSound(null, p.x, p.y + 1.0, p.z, COIN, SoundSource.NEUTRAL, 0.5f, 0.9f + p.random.nextFloat() * 0.25f)
        }
        val line = after
        if (line != null && now >= afterAt) {
            after = null
            DoorLines.plain(p, line)
        }
    }

    // The customer's own clicks are for the scene; anyone else's go the usual way
    override fun interact(p: Townsperson, player: ServerPlayer): Boolean = player.uuid == this.player

    /** The step under way is over: on to the next, or, with none left, she is let go. */
    private fun next(p: Townsperson, level: ServerLevel) {
        val beat = beats.removeFirstOrNull()
        if (beat == null) {
            beatEnds = -1
            return finish(p, level)
        }
        p.carried = beat.held
        p.act(beat.activity)
        beatEnds = p.tickCount + beat.ticks
        if (beat.coins > 0) {
            clinks = beat.coins
            clinkAt = p.tickCount + CLINK
        }
        beat.begin(p, level)
    }

    /** The last step is through: whatever is still owed is settled, and she goes back to her day. */
    private fun finish(p: Townsperson, level: ServerLevel) {
        ended = true
        val owed = slip
        slip = null
        if (owed != null) Owed.settle(level, player, owed)
        release(p)
    }

    /** The scene cannot finish (7.7): the Marks go to the player now if they are on and alive, else they wait in the ledger for their next login or respawn. */
    private fun cut(p: Townsperson, level: ServerLevel) {
        finish(p, level)
    }

    /** Puts the keeper back to her day, as the market's Serving does. */
    private fun release(p: Townsperson) {
        if (p.director !== this) return
        p.director = null
        p.carried = ItemStack.EMPTY
        p.act(Activity.NONE)
        p.attending?.let { p.stopListening(it) }
        p.routine.interrupt()
    }

    /** Takes [p] from her day for the scene, as the market's Serving does, to deal with [who]. */
    private fun begin(level: ServerLevel, p: Townsperson, who: ServerPlayer) {
        keeper = p
        started = level.gameTime
        health = p.health
        p.routine.interrupt()
        p.director = this
        p.navigation.stop()
        if (p.seat != null) p.standUp()
        p.act(Activity.NONE)
        p.attend(who, 60)
    }

    // ---- A refusal ----

    /** A short scene that takes nothing: she turns, makes [gesture] and says [family], and lets the dish stay in the hand. */
    private fun refuse(level: ServerLevel, p: Townsperson, who: ServerPlayer, gesture: Activity, family: String) {
        begin(level, p, who)
        beats += Beat(Activity.NONE, TURN)
        beats += Beat(gesture, maxOf(gesture.ticks, GESTURE)) { q, _ -> DoorLines.say(q, family) }
        beats += Beat(Activity.NONE, LINGER)
        next(p, level)
    }

    // ---- A sale ----

    /** The scene of a sale that took [sale].taken portions off a stack of [hand]: [taste] is the portion she tastes, [carry] the fraction of a Mark the player carries here now. */
    private fun play(level: ServerLevel, p: Townsperson, who: ServerPlayer, sale: Sale, hand: Int, taste: ItemStack, carry: Double) {
        begin(level, p, who)
        val mark = ItemStack(ModItems.GUILD_MARK.get())
        beats += Beat(Activity.NONE, TURN)
        beats += Beat(Activity.TASTE, TASTING, taste) { q, l -> l.playSound(null, q.x, q.y + 1.2, q.z, CookSounds.get("cook.taste"), SoundSource.NEUTRAL, 0.7f, 1.0f) }
        sale.verdict?.let { v -> beats += Beat(Activity.TALK, VERDICT) { q, l -> verdict(q, l, v) } }
        if (sale.stage == Stage.STALE) beats += Beat(Activity.TALK, STALE) { q, _ -> DoorLines.say(q, "kitchen.stale") }
        beats += Beat(Activity.TALK, REMARK) { q, l -> remark(q, l, sale) }
        if (sale.asked > 0) beats += Beat(Activity.TALK, REMARK) { q, _ -> DoorLines.say(q, "kitchen.request") }
        if (sale.first) beats += Beat(Activity.TALK, REMARK) { q, _ -> DoorLines.say(q, "kitchen.first") }
        if (sale.marks > 0) {
            beats += Beat(Activity.COUNT, COUNTING, mark, minOf(COUNTED, sale.marks))
            beats += Beat(Activity.PAY, HANDING, mark, minOf(HANDED, sale.marks)) { q, l -> pay(q, l, sale, carry) }
        } else {
            // Not a whole Mark yet: it is carried, and she says so
            beats += Beat(Activity.NOD, NODDING) { q, l ->
                DoorLines.say(q, "kitchen.carry")
                pay(q, l, sale, carry)
            }
        }
        if (sale.full && hand > sale.taken) beats += Beat(Activity.SHRUG, GESTURE) { q, _ -> DoorLines.say(q, "kitchen.full") }
        next(p, level)
    }

    /** The verdict on the mix: her take, and the role it is about after it. */
    private fun verdict(p: Townsperson, level: ServerLevel, v: Verdict) {
        val detail = DoorLines.verdictDetail(v)
        val said = DoorLines.say(p, DoorLines.verdictFamily(v), detail)
        if (said == DoorLines.Said.TAKE && detail != null) {
            after = detail
            afterAt = level.gameTime + ROLE_DELAY
        }
    }

    /** The remark by tier, with the tier's motif at half volume; a Fine dish or better makes her glad (and, as ever, it is told). */
    private fun remark(p: Townsperson, level: ServerLevel, sale: Sale) {
        val tier = sale.tier
        val family = when (tier) {
            Tier.HALLMARKED -> "kitchen.tier.hallmark"
            Tier.FINE -> "kitchen.tier.fine"
            Tier.SOUND -> "kitchen.tier.sound"
            else -> "kitchen.tier.plain"
        }
        DoorLines.say(p, family)
        val motif = when (tier) {
            Tier.HALLMARKED -> "cook.tier.hallmark"
            Tier.FINE -> "cook.tier.fine"
            Tier.SOUND -> "cook.tier.sound"
            else -> "cook.tier.plain"
        }
        level.playSound(null, p.x, p.y + 1.2, p.z, CookSounds.get(motif), SoundSource.NEUTRAL, 0.5f, 1.0f)
        if (tier.ordinal < Tier.FINE.ordinal) return
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, p.x, p.y + 1.9, p.z, if (tier == Tier.HALLMARKED) 14 else 6, 0.35, 0.25, 0.35, 0.0)
        val who = level.getPlayerByUUID(player) as? ServerPlayer ?: return
        Residents.get(level).of(p)?.takeIf { it.alive }?.let { TownTalk.relished(level, it, player, tier) }
        val kind = sale.kind
        if (tier == Tier.HALLMARKED && kind != null) KitchenRumours.hallmark(level, p.blockPosition(), who, kind)
    }

    /** The Pay beat: the Marks into the player's hand, the standing and the keeper's record of the deal with them, and what was sold on the action bar. */
    private fun pay(p: Townsperson, level: ServerLevel, sale: Sale, carry: Double) {
        val who = level.getPlayerByUUID(player) as? ServerPlayer ?: return
        val owed = slip
        slip = null
        val paid = if (owed != null) Owed.pay(level, who, owed, told = false) else null
        who.sendOverlayMessage(summary(sale, carry))
        if (sale.marks > 0) Residents.get(level).of(p)?.takeIf { it.alive }?.let { TownTalk.traded(level, it, who, sale.marks, false) }
        val up = paid?.up ?: return
        rise(p, level, up)
    }

    /** What was sold, for the action bar: "Sold 8 Fine Drover's Stew for 5 Marks. First Drover's Stew sold here: paid double." */
    private fun summary(sale: Sale, carry: Double): Component {
        val kind = sale.kind ?: return Component.empty()
        val dish = Owed.dish(kind)
        val line = if (sale.marks > 0) {
            Component.translatable("message.guildmark.cook.door.sold", sale.taken, tierWord(sale.tier), dish, Owed.marks(sale.marks))
        } else {
            Component.translatable("message.guildmark.cook.door.sold.carry", sale.taken, tierWord(sale.tier), dish, "%.2f".format(Locale.ROOT, carry))
        }
        if (sale.first) line.append(" ").append(Component.translatable("message.guildmark.cook.door.first", dish))
        if (sale.asked > 0) line.append(" ").append(Component.translatable("message.guildmark.cook.door.request"))
        if (sale.stage == Stage.STALE) line.append(" ").append(Component.translatable("message.guildmark.cook.door.stale"))
        return line
    }

    /** The player has risen to [rank] here by this sale: the market's usual bow and line, and no keeper greets it again. */
    private fun rise(p: Townsperson, level: ServerLevel, rank: SupplierRank) {
        val data = Suppliers.get(level)
        data[BlockPos.of(capital), player]?.let {
            if (it.greeted < rank.ordinal) {
                it.greeted = rank.ordinal
                data.changed()
            }
        }
        val cheer = cheer(p)
        beats.addFirst(Beat(cheer, maxOf(cheer.ticks, GESTURE)) { q, _ -> q.say(StallTalk.rank(rank), force = true) })
    }

    /** How [p] marks a supplier's rise in standing, by their ways: a cheer, a nod, a bow, a clap, or their thanks (as the market's keepers do). */
    private fun cheer(p: Townsperson): Activity {
        val resident = Days.life(p)?.resident
        return when {
            resident?.has(Trait.CHEERFUL) == true -> Activity.CHEER
            resident?.has(Trait.GRUMPY) == true -> Activity.NOD
            resident?.has(Trait.SHY) == true -> Activity.BOW
            resident?.has(Trait.CHATTY) == true -> Activity.CLAP
            else -> Activity.THANK
        }
    }

    companion object {
        /** Degrees a tick she turns toward the player. */
        private const val TURN_RATE = 20f

        /** Beats, in ticks: the turn, the taste (the clip's own length), a spoken verdict, a remark, the stale remark, counting the Marks out, handing them over, a nod, a gesture at the least, and a moment's linger after a refusal. */
        private const val TURN = 8
        private const val TASTING = 40
        private const val VERDICT = 40
        private const val REMARK = 34
        private const val STALE = 28
        private const val COUNTING = 30
        private const val HANDING = 30
        private const val NODDING = 24
        private const val GESTURE = 24
        private const val LINGER = 20

        /** Ticks after her take of a verdict that the role it is about is shown, and the ticks between two clinks of coin. */
        private const val ROLE_DELAY = 40L
        private const val CLINK = 6

        /** The most clinks of coin while counting the Marks out, and while handing them over. */
        private const val COUNTED = 6
        private const val HANDED = 3

        /** A sale that has not paid in twelve seconds is cut short; one that has is given this much more to finish its bow. */
        private const val CUTOFF = 240L
        private const val FINISH = 80L

        /** Farther than this many blocks from her, the player has walked off. */
        private const val FAR = 8.0

        private val COIN: SoundEvent = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "townsfolk.thief.coin"))

        /** The word for a dish's tier on the action bar ("Fine"). */
        private fun tierWord(tier: Tier): Component = Component.translatable(
            when (tier) {
                Tier.HALLMARKED -> "message.guildmark.cook.door.tier.hallmarked"
                Tier.FINE -> "message.guildmark.cook.door.tier.fine"
                Tier.SOUND -> "message.guildmark.cook.door.tier.sound"
                Tier.SPOILT -> "message.guildmark.cook.door.tier.spoilt"
                Tier.PLAIN -> "message.guildmark.cook.door.tier.plain"
            },
        )

        /**
         * [player] holds out [stack], a dish, to [p], the innkeeper or the baker of the capital keyed [capital] (7.7). She must be
         * at work and awake, or she says the shop's own shut line; under any other director, or with the market's door
         * shut, she says so; else the ledger is asked what the door would take, and either it is refused, with a short scene
         * and the line that fits, or the sale is made in one step and the scene plays it out. Every path leaves the dish in
         * the hand but the portions sold.
         */
        fun offer(level: ServerLevel, player: ServerPlayer, p: Townsperson, life: Life, capital: Long, stack: ItemStack) {
            val door = if (p.role == TownRole.BAKER) Door.BAKERY else Door.INN
            // Shut: asleep, or not at work (the baker on her rest day, either of them out of hours): the shop's own line, as at the stall
            val doing = Days.stretch(life, level.overworldClockTime).doing
            if (p.isSleeping || doing != Doing.WORK) {
                val shop = Shop.kept(p)
                p.say(if (shop != null) StallTalk.shut(p, life, doing, shop, level) else "market.shut.done.inn", force = true)
                if (!p.isSleeping) p.attend(player, 60)
                return
            }
            // Under another director (a festival, a lamplighter, the market's Serving, a kitchen scene): one moment. The customer's own scene says nothing more
            val busy = p.director
            if (busy != null) {
                if (busy is KitchenDoor && busy.player == player.uuid) return
                p.attend(player, 40)
                DoorLines.say(p, "kitchen.busy")
                return
            }
            val kitchens = Kitchens.get(level)
            val deal = Selling.quote(level, kitchens, capital, door, player, stack)
            val refusal = deal.refusal
            if (refusal == Refusal.DOORS_OFF) {
                p.lookAt(player)
                p.attend(player, 60)
                DoorLines.say(p, "kitchen.off")
                return
            }
            if (refusal != null) return refused(level, player, p, door, capital, stack, refusal)
            val hand = stack.count
            val taste = stack.copyWithCount(1)
            val scene = KitchenDoor(player.uuid, door, capital)
            val sale = kitchens.sell(level, capital, door, player, stack)
            if (sale.taken <= 0) return refused(level, player, p, door, capital, stack, sale.refusal ?: Refusal.FULL)
            // Written down with the sale, in this one step: the scene counts the Marks out, or the ledger pays them if the scene is cut
            scene.slip = Owed.owe(kitchens, player.uuid, capital, door, sale.kind, sale.marks, scene)
            val carry = kitchens.rec(capital)?.let { Doors.carried(it, door, player.uuid) } ?: 0.0
            scene.play(level, p, player, sale, hand, taste, carry)
        }

        /** The short scene of a refusal: the gesture and the line that fit [why], and the dish stays in the hand. */
        private fun refused(level: ServerLevel, player: ServerPlayer, p: Townsperson, door: Door, capital: Long, stack: ItemStack, why: Refusal) {
            val scene = KitchenDoor(player.uuid, door, capital)
            when (why) {
                Refusal.FULL -> scene.refuse(level, p, player, Activity.SHRUG, "kitchen.full")
                Refusal.GONE_OFF -> scene.refuse(level, p, player, Activity.WAVE_OFF, "kitchen.gone")
                Refusal.SPOILT -> scene.refuse(level, p, player, Activity.WAVE_OFF, "kitchen.spoilt")
                Refusal.WRONG_DOOR -> scene.refuse(level, p, player, Activity.POINT, if (door == Door.BAKERY) "kitchen.baker.no" else "kitchen.inn.no")
                Refusal.NOT_A_DISH -> scene.refuse(level, p, player, Activity.SHRUG, if (stack.item === CookRegistry.GRUEL.get()) "kitchen.gruel" else "kitchen.mess")
                Refusal.DOORS_OFF -> {
                    p.lookAt(player)
                    DoorLines.say(p, "kitchen.off")
                }
            }
        }
    }
}
