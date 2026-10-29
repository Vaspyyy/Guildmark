package io.github.vaspyyy.guildmark.trade.carry

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.caravan.DraughtHorse
import io.github.vaspyyy.guildmark.caravan.Ground
import io.github.vaspyyy.guildmark.caravan.Train
import io.github.vaspyyy.guildmark.caravan.Wagon
import io.github.vaspyyy.guildmark.economy.CaravanRoutes
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.works.WorksSite
import io.github.vaspyyy.guildmark.guild.TownControl
import io.github.vaspyyy.guildmark.network.StoreWagon
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Director
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsfolk
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.trade.Rung
import io.github.vaspyyy.guildmark.trade.Rungs
import io.github.vaspyyy.guildmark.trade.StoreLife
import io.github.vaspyyy.guildmark.trade.Stores
import io.github.vaspyyy.guildmark.trade.TradeConfig
import io.github.vaspyyy.guildmark.trade.TradeSounds
import io.github.vaspyyy.guildmark.trade.TradeStores
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.util.Prediction
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import java.util.Locale
import java.util.UUID
import java.util.function.Consumer
import kotlin.math.hypot

/**
 * The Wagon Bill (living-world/trader/design.md, Part 3; build part D3): a sheet of heavy paper with a red wax seal, bought with the wagon
 * at the Carriers' Store's desk for 240 Marks. It names its wagon's owner. Read (used) by them:
 * - at the Store's yard, with the wagon standing there and its horse bought, the wagon is rolled out ([WagonBill.Rollout]);
 * - anywhere else, it calls the wagon home to the yard (free on its first day, 10 Marks after), where it is after a night.
 *
 * With the wagon switched off (`tradeWagon`) reading it still calls the wagon home, and its tailboard still opens, so a wagon is never lost.
 */
class WagonBillItem(properties: Properties) : Item(properties) {
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        val stack = player.getItemInHand(hand)
        if (player is ServerPlayer) {
            WagonBill.read(player, stack)
            player.cooldowns.addCooldown(stack, COOLDOWN)
        }
        return InteractionResult.SUCCESS
    }

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, builder: Consumer<Component>, flag: TooltipFlag) {
        val name = WagonBill.nameOn(stack)
        if (name.isNotEmpty()) builder.accept(Component.translatable("item.guildmark.wagon_bill.of", name).withStyle(ChatFormatting.GRAY))
        builder.accept(Component.translatable("item.guildmark.wagon_bill.hint").withStyle { it.withColor(HINT) })
        builder.accept(Component.translatable("item.guildmark.wagon_bill.hint2").withStyle { it.withColor(HINT) })
    }

    private companion object {
        const val HINT = 0x8A8A8A
        const val COOLDOWN = 10
    }
}

internal object WagonBill {
    /** What the Store asks for the Bill and the wagon, for a draught horse, and for calling the wagon home after its first day. */
    const val BILL_PRICE = 240
    const val HORSE_PRICE = 80
    const val RECALL_FEE = 10

    /** Blocks from the wagon within which reading the Bill rolls it out (in the yard) or finds it standing by (anywhere). */
    private const val BY = 24.0

    /** What the desk's Wagon tab asks for: buy the Bill, buy a horse, call the wagon to this yard, a new copy of the Bill. */
    const val BUY_BILL = 0
    const val BUY_HORSE = 1
    const val RECALL = 2
    const val COPY = 3

    private const val OWNER = "wagon_owner"
    private const val NAME = "wagon_name"

    /** The yard hand of the rollout: never saved (one loaded from disk goes again). */
    const val HAND_TAG = "guildmark.yard_hand"
    private val LOOKS = intArrayOf(0, 1, 2, 4, 6, 7)

    private val SOLD = listOf("trade_sell_wagon_0", "trade_sell_wagon_1", "trade_sell_wagon_2")

    /** The names a draught horse is sold with. */
    val HORSES = listOf(
        "Bramble", "Clover", "Hazel", "Juniper", "Barley", "Thistle", "Rowan", "Sorrel", "Willow", "Bracken", "Nutmeg", "Tansy", "Russet",
        "Pippin", "Moss", "Ember",
    )

    fun register() {
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent::class.java) { event ->
            if (event.loadedFromDisk() && event.entity is Townsperson && HAND_TAG in event.entity.entityTags()) event.isCanceled = true
        }
    }

    // ---- The Bill itself ----

    /** A Bill for [owner]'s wagon (their name [name] on it). */
    fun stack(owner: UUID, name: String): ItemStack {
        val stack = ItemStack(ModItems.WAGON_BILL.get())
        val tag = CompoundTag()
        tag.putString(OWNER, owner.toString())
        tag.putString(NAME, name)
        CustomData.set(DataComponents.CUSTOM_DATA, stack, tag)
        return stack
    }

    fun ownerOn(stack: ItemStack): UUID? {
        val raw = stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getStringOr(OWNER, "") ?: return null
        return runCatching { UUID.fromString(raw) }.getOrNull()
    }

    fun nameOn(stack: ItemStack): String = stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getStringOr(NAME, "") ?: ""

    /** Whether [player] carries a Bill of their own wagon. */
    fun carries(player: ServerPlayer): Boolean {
        val inv = player.inventory
        for (i in 0 until inv.containerSize) {
            val s = inv.getItem(i)
            if (s.`is`(ModItems.WAGON_BILL.get()) && ownerOn(s) == player.uuid) return true
        }
        return false
    }

    private fun give(player: ServerPlayer, stack: ItemStack) {
        player.inventory.placeItemBackInInventory(stack, Prediction.SERVER_ONLY)
    }

    // ---- Reading it ----

    /** [player] reads [stack]: the wagon it names rolled out at its yard, or called home, or found standing by. */
    fun read(player: ServerPlayer, stack: ItemStack) {
        val level = player.level()
        val data = TradeWagons.peek(level)
        var owner = ownerOn(stack)
        // A Bill naming nobody (from the creative tabs) becomes the reader's, if they have a wagon
        if (owner == null && data?.get(player.uuid) != null) {
            owner = player.uuid
            CustomData.update(DataComponents.CUSTOM_DATA, stack) { it.putString(OWNER, player.uuid.toString()); it.putString(NAME, player.name.string) }
        }
        if (owner == null) return say(player, Component.translatable("message.guildmark.trade.wagon.bill_blank"))
        if (owner != player.uuid) {
            val name = nameOn(stack).ifEmpty { "?" }
            return say(player, Component.translatable("message.guildmark.trade.wagon.bill_other", name))
        }
        val rec = data?.get(owner) ?: return say(player, Component.translatable("message.guildmark.trade.wagon.bill_gone"))
        rec.ownerName = player.name.string
        val clock = level.server.overworld().overworldClockTime
        val at = rec.at
        val here = at != null && rec.dimension == level.dimension()
        val near = here && at.distanceTo(player.position()) <= BY
        when (rec.state) {
            WagonRec.State.YARD -> when {
                !rec.arrived(clock) -> say(player, Component.translatable("message.guildmark.trade.wagon.coming", placeOf(level, rec), clockLeft(rec.due - clock)))
                near -> rollOutOrSay(player, rec)
                else -> say(player, Component.translatable("message.guildmark.trade.wagon.waits_yard", placeOf(level, rec), where(at)))
            }
            WagonRec.State.PARKED, WagonRec.State.LED -> {
                if (near && !player.isShiftKeyDown) {
                    val key = if (rec.state == WagonRec.State.LED) "message.guildmark.trade.wagon.bill_led" else "message.guildmark.trade.wagon.bill_here"
                    return say(player, Component.translatable(key))
                }
                sendHome(player, rec, null)
            }
        }
    }

    /** At the yard: the wagon rolled out if it can be, or why not. */
    private fun rollOutOrSay(player: ServerPlayer, rec: WagonRec) {
        val level = player.level()
        when {
            !TradeConfig.wagon() -> say(player, Component.translatable("message.guildmark.trade.wagon.off_yard"))
            !rec.horse -> say(player, Component.translatable("message.guildmark.trade.wagon.no_horse_yard"))
            else -> {
                val sh = LedShow.of(rec.owner)?.takeIf { it.level === level } ?: LedShow.make(level, rec)
                if (sh == null) return say(player, Component.translatable("message.guildmark.trade.wagon.bill_gone"))
                if (sh.scene != null) return say(player, Component.translatable("message.guildmark.trade.wagon.busy"))
                if (!rollOut(sh, player)) say(player, Component.translatable("message.guildmark.trade.wagon.busy"))
            }
        }
    }

    private fun say(player: ServerPlayer, c: Component) {
        player.sendOverlayMessage(c)
    }

    private fun where(at: Vec3?): String = at?.let { "%d %d %d".format(Locale.ROOT, Mth.floor(it.x), Mth.floor(it.y), Mth.floor(it.z)) } ?: "?"

    /** Minutes and seconds of game time, the way a wait is said. */
    private fun clockLeft(ticks: Long): String {
        val s = (ticks.coerceAtLeast(0L) / 20L).toInt()
        return "%d:%02d".format(Locale.ROOT, s / 60, s % 60)
    }

    /** The name of the town [rec]'s yard is in. */
    fun placeOf(level: ServerLevel, rec: WagonRec): String = if (rec.capital == 0L) "" else Stores.placeOf(level.server.overworld(), rec.capital)

    // ---- Calling it home ----

    /** Where a wagon is kept in a town: its back axle and its heading, in the town whose heart is [capital]. */
    class Yard(val capital: Long, val rear: Vec3, val yaw: Float)

    /**
     * Calls [rec]'s wagon home to a yard (the Store of [capital], or the nearest yard to [player] if null): its bodies go in a puff, it is there
     * after the night. Free on its first day, [RECALL_FEE] Marks after (brought all the same if they cannot pay). True if it was sent.
     */
    fun sendHome(player: ServerPlayer, rec: WagonRec, capital: Long?, now: Boolean = false, free: Boolean = false): Boolean {
        val level = player.level()
        val overworld = level.server.overworld()
        val data = TradeWagons.peek(level) ?: return false
        val yard = (if (capital != null) yardAt(overworld, rec.owner, capital) else null) ?: yardFor(overworld, rec.owner, player)
        if (yard == null) {
            // No town at all to send it to: it is set down beside its owner, which is as home as it gets
            LedShow.bringBeside(player, led = true)
            say(player, Component.translatable("message.guildmark.trade.wagon.no_yard"))
            return true
        }
        val fee = if (free) 0 else feeFor(level, rec)
        var paid = true
        if (fee > 0) {
            if (TownControl.marks(player) >= fee) TownControl.pay(player, fee) else paid = false
        }
        LedShow.dropOf(rec.owner, puff = true)
        val clock = overworld.overworldClockTime
        rec.state = WagonRec.State.YARD
        rec.dimension = Level.OVERWORLD
        rec.at = yard.rear
        rec.yaw = yard.yaw
        rec.capital = yard.capital
        rec.due = if (now) 0L else clock + (TradeWagons.DAY - Math.floorMod(clock, TradeWagons.DAY))
        data.changed()
        val place = placeOf(overworld, rec)
        val line: MutableComponent = Component.translatable(if (now) "message.guildmark.trade.wagon.sent_now" else "message.guildmark.trade.wagon.sent", place)
        player.sendSystemMessage(line.withStyle(ChatFormatting.GOLD))
        when {
            fee == 0 && !free -> player.sendSystemMessage(Component.translatable("message.guildmark.trade.wagon.fee_free").withStyle(ChatFormatting.GRAY))
            fee > 0 && paid -> player.sendSystemMessage(Component.translatable("message.guildmark.trade.wagon.fee_paid", fee).withStyle(ChatFormatting.GRAY))
            fee > 0 -> player.sendSystemMessage(Component.translatable("message.guildmark.trade.wagon.fee_short", fee).withStyle(ChatFormatting.GRAY))
        }
        return true
    }

    /** What calling [rec]'s wagon home costs now: nothing on its first day. */
    fun feeFor(level: ServerLevel, rec: WagonRec): Int = if (level.server.overworld().gameTime - rec.bought < TradeWagons.DAY) 0 else RECALL_FEE

    /**
     * The yard nearest [player] for [owner]'s wagon: their nearest Store's (the street before its door), else the nearest capital's wagon stand
     * (beside the caravans' own), else null.
     */
    fun yardFor(level: ServerLevel, owner: UUID, player: ServerPlayer): Yard? {
        val from = if (player.level().dimension() == Level.OVERWORLD) player.position() else TradeWagons.peek(level)?.get(owner)?.let { r ->
            r.at?.takeIf { r.dimension == Level.OVERWORLD }
        }
        val stores = TradeStores.peek(level)?.of(owner).orEmpty().mapNotNull { rec -> Stores.site(level, owner, rec.capital)?.let { rec.capital to it } }
        val store = stores.minByOrNull { (_, site) -> from?.distanceToSqr(Vec3.atCenterOf(site.origin)) ?: 0.0 }
        if (store != null) return storeYard(level, store.first, store.second)
        val capitals = Markets.get(level).all().map { it.market.capital }
        val capital = capitals.minByOrNull { c -> from?.distanceToSqr(Vec3.atCenterOf(BlockPos.of(c))) ?: 0.0 } ?: return null
        return standYard(level, capital)
    }

    /** The yard of [owner]'s Store in [capital], else that capital's wagon stand. */
    fun yardAt(level: ServerLevel, owner: UUID, capital: Long): Yard? {
        Stores.site(level, owner, capital)?.let { return storeYard(level, capital, it) }
        return standYard(level, capital)
    }

    /**
     * The Store's yard: the street three blocks out from its door ([Stores.yard]), the wagon along the street (whichever way has room), its
     * bed over the spot.
     */
    fun storeYard(level: ServerLevel, capital: Long, site: WorksSite): Yard {
        val spot = site.origin.relative(site.facing, 3)
        val c = Vec3(spot.x + 0.5, spot.y.toDouble(), spot.z + 0.5)
        val ways = listOf(site.facing.clockWise, site.facing.counterClockWise)
        var first: Yard? = null
        for (d in ways) {
            val fx = d.stepX.toDouble()
            val fz = d.stepZ.toDouble()
            val rear = Vec3(c.x - fx * BED_MIDDLE, c.y, c.z - fz * BED_MIDDLE)
            val y = yardY(level, rear, site.origin.y.toDouble())
            val yard = Yard(capital, Vec3(rear.x, y, rear.z), LedShow.yawOf(fx, fz))
            if (first == null) first = yard
            if (fits(level, yard)) return yard
        }
        return first!!
    }

    /** The capital's wagon stand ([CaravanRoutes.Leg.stand]): beside where the caravans' own wagon stands, else behind it, else on it. */
    fun standYard(level: ServerLevel, capital: Long): Yard {
        val leg = CaravanRoutes.legs(level, capital)?.firstOrNull()
        if (leg == null) {
            // Its roads not worked out yet: by the capital's heart, where it is settled onto the ground when it is first seen
            val heart = BlockPos.of(capital)
            return Yard(capital, Vec3(heart.x + 3.5, heart.y.toDouble(), heart.z + 0.5), 0f)
        }
        val stand = leg.stand
        val yaw = leg.yaw(leg.length)
        val (fx, fz) = LedShow.forward(yaw)
        val rx = -fz
        val rz = fx
        val half = Train.LENGTH / 2.0
        val tries = listOf(
            Vec3(stand.x + rx * 3.5 - fx * half, stand.y, stand.z + rz * 3.5 - fz * half),
            Vec3(stand.x - rx * 3.5 - fx * half, stand.y, stand.z - rz * 3.5 - fz * half),
            Vec3(stand.x - fx * (Train.LENGTH + 5.0), stand.y, stand.z - fz * (Train.LENGTH + 5.0)),
        )
        var first: Yard? = null
        for (rear in tries) {
            val yard = Yard(capital, Vec3(rear.x, yardY(level, rear, stand.y), rear.z), yaw)
            if (first == null) first = yard
            if (fits(level, yard)) return yard
        }
        return first!!
    }

    /** Blocks from the back axle to the middle of the bed. */
    private const val BED_MIDDLE = 0.8

    private fun yardY(level: ServerLevel, at: Vec3, ref: Double): Double =
        if (level.isLoaded(BlockPos.containing(at))) Ground.stand(level, at.x, at.z, ref + 0.6) else ref

    /** Whether a wagon fits in [yard] (or nobody can see it now: it is settled when it is first seen). */
    private fun fits(level: ServerLevel, yard: Yard): Boolean {
        if (!level.isLoaded(BlockPos.containing(yard.rear))) return true
        val (fx, fz) = LedShow.forward(yard.yaw)
        return LedShow.clear(level, yard.rear, fx, fz)
    }

    // ---- At the Store's desk ----

    /**
     * [player] asks the desk of their Store in [capital] for [what] ([BUY_BILL], [BUY_HORSE], [RECALL], [COPY]), its clerk [clerk] there if near.
     * Null if it was done, else why not (said in red at the desk). Nothing is taken for what is not given.
     */
    fun desk(player: ServerPlayer, what: Int, capital: Long, owner: UUID, clerk: Townsperson?): Component? {
        if (owner != player.uuid) return Component.translatable("gui.guildmark.trade.store.wagon.why.not_yours")
        return try {
            when (what) {
                BUY_BILL -> buyBill(player, capital, clerk)
                BUY_HORSE -> buyHorse(player, capital, clerk)
                RECALL -> recallHere(player, capital, clerk)
                COPY -> copyBill(player, clerk)
                else -> null
            }
        } catch (e: RuntimeException) {
            Guildmark.LOGGER.warn("The Store's wagon desk failed", e)
            Component.translatable("gui.guildmark.trade.store.wagon.why.failed")
        }
    }

    private fun buyBill(player: ServerPlayer, capital: Long, clerk: Townsperson?): Component? {
        val level = player.level()
        if (!TradeConfig.wagon()) return Component.translatable("gui.guildmark.trade.store.wagon.why.off")
        if (!Rungs.reached(player, Rung.FACTOR)) return Component.translatable("gui.guildmark.trade.store.wagon.why.factor")
        if (TradeWagons.peek(level)?.get(player.uuid) != null) return Component.translatable("gui.guildmark.trade.store.wagon.why.owned")
        if (TownControl.marks(player) < BILL_PRICE) return Component.translatable("gui.guildmark.trade.store.wagon.why.short", BILL_PRICE)
        val overworld = level.server.overworld()
        val yard = yardAt(overworld, player.uuid, capital)
            ?: return Component.translatable("gui.guildmark.trade.store.wagon.why.failed")
        TownControl.pay(player, BILL_PRICE)
        val data = TradeWagons.get(level)
        val rec = data.make(player.uuid)
        rec.ownerName = player.name.string
        rec.state = WagonRec.State.YARD
        rec.dimension = Level.OVERWORLD
        rec.at = yard.rear
        rec.yaw = yard.yaw
        rec.capital = capital
        rec.due = 0L
        rec.bought = overworld.gameTime
        rec.colour = TradeWagons.colourOf(overworld, player.uuid)
        data.changed()
        give(player, stack(player.uuid, player.name.string))
        handOver(player, clerk, SOLD[level.random.nextInt(SOLD.size)])
        level.playSound(null, player.blockPosition(), TradeSounds.get("trade.seal_press"), SoundSource.PLAYERS, 0.8f, 1.0f)
        player.sendSystemMessage(Component.translatable("message.guildmark.trade.wagon.bought", BILL_PRICE).withStyle(ChatFormatting.GOLD))
        return null
    }

    private fun buyHorse(player: ServerPlayer, capital: Long, clerk: Townsperson?): Component? {
        val level = player.level()
        if (!TradeConfig.wagon()) return Component.translatable("gui.guildmark.trade.store.wagon.why.off")
        val data = TradeWagons.peek(level)
        val rec = data?.get(player.uuid) ?: return Component.translatable("gui.guildmark.trade.store.wagon.why.no_wagon")
        if (rec.horse) return Component.translatable("gui.guildmark.trade.store.wagon.why.has_horse")
        if (TownControl.marks(player) < HORSE_PRICE) return Component.translatable("gui.guildmark.trade.store.wagon.why.short", HORSE_PRICE)
        TownControl.pay(player, HORSE_PRICE)
        rec.horse = true
        rec.horseName = HORSES[level.random.nextInt(HORSES.size)]
        rec.coat = level.random.nextInt(3)
        data.changed()
        // Its bodies are made again with the horse beside the wagon (in its shafts, if the wagon is out on the road)
        LedShow.of(rec.owner)?.let { LedShow.drop(it) }
        handOver(player, clerk, null)
        val near = clerk ?: player
        level.playSound(null, near.blockPosition(), TradeSounds.get("trade.harness_buckle"), SoundSource.PLAYERS, 0.7f, 1.0f)
        val where = if (rec.state == WagonRec.State.YARD && rec.capital == capital) "message.guildmark.trade.wagon.horse_here" else "message.guildmark.trade.wagon.horse_sent"
        player.sendSystemMessage(Component.translatable(where, rec.horseName, HORSE_PRICE).withStyle(ChatFormatting.GOLD))
        return null
    }

    private fun recallHere(player: ServerPlayer, capital: Long, clerk: Townsperson?): Component? {
        val data = TradeWagons.peek(player.level())
        val rec = data?.get(player.uuid) ?: return Component.translatable("gui.guildmark.trade.store.wagon.why.no_wagon")
        val clock = player.level().server.overworld().overworldClockTime
        if (rec.state == WagonRec.State.YARD && rec.capital == capital) {
            return Component.translatable(if (rec.arrived(clock)) "gui.guildmark.trade.store.wagon.why.here" else "gui.guildmark.trade.store.wagon.why.coming")
        }
        if (!sendHome(player, rec, capital)) return Component.translatable("gui.guildmark.trade.store.wagon.why.failed")
        clerk?.let {
            it.lookAt(player)
            if (it.director == null) it.act(Activity.NOD)
        }
        return null
    }

    private fun copyBill(player: ServerPlayer, clerk: Townsperson?): Component? {
        if (TradeWagons.peek(player.level())?.get(player.uuid) == null) return Component.translatable("gui.guildmark.trade.store.wagon.why.no_wagon")
        if (carries(player)) return Component.translatable("gui.guildmark.trade.store.wagon.why.has_bill")
        give(player, stack(player.uuid, player.name.string))
        handOver(player, clerk, null)
        player.level().playSound(null, player.blockPosition(), TradeSounds.get("trade.seal_press"), SoundSource.PLAYERS, 0.6f, 1.1f)
        return null
    }

    private fun handOver(player: ServerPlayer, clerk: Townsperson?, line: String?) {
        if (clerk == null) return
        clerk.lookAt(player)
        if (clerk.director == null) clerk.act(Activity.HAND_OVER)
        if (line != null) StoreLife.say(clerk, line)
    }

    /** What the desk's Wagon tab shows [player] at their Store in [capital]. */
    fun view(player: ServerPlayer, capital: Long): StoreWagon {
        val level = player.level()
        val rec = TradeWagons.peek(level)?.get(player.uuid)
        val clock = level.server.overworld().overworldClockTime
        val factor = Rungs.reached(player, Rung.FACTOR)
        if (rec == null) {
            return StoreWagon(TradeConfig.wagon(), factor, StoreWagon.NONE, "", 0, 0, 0, -1, 0, false, "", 0, 0, false, BILL_PRICE, HORSE_PRICE)
        }
        val at = rec.at
        val state = when (rec.state) {
            WagonRec.State.YARD -> when {
                !rec.arrived(clock) -> StoreWagon.COMING
                rec.capital == capital -> StoreWagon.HERE
                else -> StoreWagon.YARD
            }
            WagonRec.State.PARKED -> StoreWagon.PARKED
            WagonRec.State.LED -> StoreWagon.LED
        }
        val far = if (at != null && rec.dimension == level.dimension()) at.distanceTo(player.position()).toInt() else -1
        return StoreWagon(
            TradeConfig.wagon(), factor, state, placeOf(level, rec), at?.let { Mth.floor(it.x) } ?: 0, at?.let { Mth.floor(it.y) } ?: 0,
            at?.let { Mth.floor(it.z) } ?: 0, far, if (state == StoreWagon.COMING) ((rec.due - clock).coerceAtLeast(0L) / 20L).toInt() else 0,
            rec.horse, rec.horseName, rec.used(), feeFor(level, rec), carries(player), BILL_PRICE, HORSE_PRICE,
        )
    }

    // ---- The rollout at the yard ----

    /** The rollout begun on [sh] (its bodies in the yard) for [player]: false if it cannot be. */
    fun rollOut(sh: LedShow.Shown, player: ServerPlayer): Boolean {
        if (sh.scene != null || sh.led || sh.horse == null || sh.harnessed) return false
        val scene = Rollout(player.uuid)
        sh.scene = scene
        return true
    }

    private enum class Stage { APPEAR, BACK, LEAD, BACK_IN, HITCH, DONE }

    /**
     * The wagon rolled out of the yard (build part D3): a yard hand comes out, the wagon is pushed back out of its place, the horse is led round
     * off its nosebag and backed into the shafts, buckled in, the brake put on, the lantern lit if it is dusk, the pennant run up, and the hand
     * tips their hat and goes. Every stage has an end however things stand, and [stop] finishes it at once: the horse in the shafts, the record parked.
     */
    private class Rollout(val reader: UUID) : LedShow.Scene {
        private var stage = Stage.APPEAR
        private var age = 0
        private var hand: Townsperson? = null
        private var hitched = false
        private var gone = false

        /** How far the wagon is pushed back, and how far it has gone. */
        private var back = 0.0
        private var backed = 0.0

        /** The way the horse is led round: a curve from where it stood to before the shafts, its length, and how far along it is. */
        private val curve = arrayOfNulls<Vec3>(4)
        private var curveLength = 1.0
        private var along = 0.0
        private val samples = DoubleArray(SAMPLES + 1)

        /** Where the horse is backed from and to. */
        private var inFrom: Vec3 = Vec3.ZERO
        private var inTo: Vec3 = Vec3.ZERO

        override fun tick(sh: LedShow.Shown): Boolean {
            val w = sh.wagon
            val h = sh.horse
            if (h == null || w.isRemoved || h.isRemoved) {
                finish(sh)
                return false
            }
            age++
            when (stage) {
                Stage.APPEAR -> appear(sh, w)
                Stage.BACK -> back(sh, w)
                Stage.LEAD -> lead(sh, w, h)
                Stage.BACK_IN -> backIn(sh, w, h)
                Stage.HITCH -> hitch(sh, w, h)
                Stage.DONE -> if (!done(sh, w)) {
                    finish(sh)
                    return false
                }
            }
            return true
        }

        override fun stop(sh: LedShow.Shown) {
            finish(sh)
        }

        private fun next(to: Stage) {
            stage = to
            age = 0
        }

        private fun appear(sh: LedShow.Shown, w: Wagon) {
            val level = sh.level
            val at = w.point(0f, 0f, -98f)
            val yaw = Mth.wrapDegrees(w.yRot + 180f)
            val p = try {
                Townsfolk.create(level, BlockPos.containing(at), TownRole.CITIZEN, LOOKS[level.random.nextInt(LOOKS.size)], yaw, EntitySpawnReason.EVENT)
            } catch (e: RuntimeException) {
                Guildmark.LOGGER.warn("The yard hand could not be made", e)
                null
            }
            if (p != null) {
                p.setPermanentlyInvulnerable(true)
                p.goalSelector.removeAllGoals { it.javaClass.simpleName == "FleeGoal" }
                p.addTag(HAND_TAG)
                p.setDropChance(EquipmentSlot.MAINHAND, 0f)
                p.setDropChance(EquipmentSlot.OFFHAND, 0f)
                p.director = Hand
                hold(p, true)
                put(sh, p, at, yaw)
                if (level.addFreshEntity(p)) {
                    hand = p
                    level.sendParticles(ParticleTypes.POOF, p.x, p.y + 0.6, p.z, 6, 0.3, 0.4, 0.3, 0.02)
                }
            }
            // Room behind for it to be pushed back out of its place, or it stays where it stands
            val (fx, fz) = LedShow.forward(w.yRot)
            val rear = w.position()
            back = if (LedShow.clear(level, Vec3(rear.x - fx * BACK, rear.y, rear.z - fz * BACK), fx, fz)) BACK else 0.0
            w.flag(Wagon.FLAG_BRAKE, false)
            next(if (back > 0.0) Stage.BACK else Stage.LEAD)
        }

        /** The wagon pushed back out of its place by its shafts. */
        private fun back(sh: LedShow.Shown, w: Wagon) {
            val step = back / BACK_TICKS
            if (backed < back) {
                sh.s -= step
                backed += step
                LedShow.place(sh, snap = false)
            }
            hand?.let { p ->
                if (age == 1) p.act(Activity.PULL_CART)
                put(sh, p, w.point(0f, 0f, -98f), Mth.wrapDegrees(w.yRot + 180f))
            }
            if (age >= BACK_TICKS) next(Stage.LEAD)
        }

        /** The horse led round off its nosebag to before the shafts, the hand at its head. */
        private fun lead(sh: LedShow.Shown, w: Wagon, h: DraughtHorse) {
            if (age == 1) {
                h.flag(DraughtHorse.FLAG_NOSEBAG, false)
                w.flag(Wagon.FLAG_NOSEBAG, false)
                h.move(DraughtHorse.Move.WALK)
                hand?.act(Activity.NONE)
                val (fx, fz) = LedShow.forward(h.yRot)
                val p0 = h.position()
                curve[0] = p0
                curve[1] = Vec3(p0.x + fx * 3.75, p0.y, p0.z + fz * 3.75)
                curve[2] = w.point(0f, 0f, -83f)
                curve[3] = w.point(0f, 0f, -123f)
                var len = 0.0
                var last = bez(0.0)
                samples[0] = 0.0
                for (k in 1..SAMPLES) {
                    val b = bez(k / SAMPLES.toDouble())
                    len += hypot(b.x - last.x, b.z - last.z)
                    samples[k] = len
                    last = b
                }
                curveLength = len.coerceAtLeast(0.01)
                along = 0.0
            }
            along = (along + LEAD_SPEED).coerceAtMost(curveLength)
            val t = tAt(along)
            val at = bez(t)
            val tan = tangent(t)
            val yaw = LedShow.yawOf(tan.x, tan.z)
            h.place(sh.level, at, yaw, at.y, false)
            hand?.let { p ->
                val (fx, fz) = LedShow.forward(yaw)
                // At its head, on its right, the side away from the wagon
                put(sh, p, Vec3(at.x + fx * 1.5 - fz * 0.9, at.y, at.z + fz * 1.5 + fx * 0.9), yaw)
            }
            if (along >= curveLength || age > LEAD_MOST) {
                inFrom = h.position()
                inTo = w.point(0f, 0f, -83f)
                next(Stage.BACK_IN)
            }
        }

        /** The horse backed into the shafts, the hand before it pushing it back by the bridle. */
        private fun backIn(sh: LedShow.Shown, w: Wagon, h: DraughtHorse) {
            val f = (age / BACK_IN_TICKS.toDouble()).coerceAtMost(1.0)
            val e = f * f * (3 - 2 * f)
            val at = inFrom.lerp(inTo, e)
            h.place(sh.level, at, w.yRot, w.y, false)
            if (age == 1) h.move(DraughtHorse.Move.WALK)
            val (fx, fz) = LedShow.forward(w.yRot)
            hand?.let { put(sh, it, Vec3(at.x + fx * 2.1, at.y, at.z + fz * 2.1), Mth.wrapDegrees(w.yRot + 180f)) }
            if (age >= BACK_IN_TICKS) {
                h.move(DraughtHorse.Move.IDLE)
                w.flag(Wagon.FLAG_IN_SHAFTS, true)
                next(Stage.HITCH)
            }
        }

        /** Buckled in: the hand at its shoulder, the traces made fast. */
        private fun hitch(sh: LedShow.Shown, w: Wagon, h: DraughtHorse) {
            val (fx, fz) = LedShow.forward(h.yRot)
            hand?.let { p ->
                if (age == 1) p.act(Activity.HITCH)
                // Beside its shoulder on its right, facing it
                put(sh, p, Vec3(h.x - fz * 1.0 + fx * 0.3, h.y, h.z + fx * 1.0 + fz * 0.3), Mth.wrapDegrees(h.yRot - 90f))
            }
            if (age == TRACE_UP_AT && !hitched && !w.isRemoved) w.flag(Wagon.FLAG_TRACE_UP, true)
            if (age == HITCH_AT) harness(sh)
            if (age >= HITCH_TICKS) next(Stage.DONE)
        }

        /** The horse in the shafts and the record parked, its pennant up: what [finish] makes sure of too. */
        private fun harness(sh: LedShow.Shown) {
            if (hitched) return
            val w = sh.wagon
            if (!w.isRemoved) {
                w.flag(Wagon.FLAG_TRACE_UP, false)
                w.flag(Wagon.FLAG_IN_SHAFTS, false)
            }
            val h = sh.horse ?: return
            hitched = true
            if (!w.isRemoved && !h.isRemoved) {
                w.horse(h)
                h.wagon(w)
                h.flag(DraughtHorse.FLAG_REINS_TIED, true)
                h.flag(DraughtHorse.FLAG_NOSEBAG, false)
                h.move(DraughtHorse.Move.IDLE)
                LedShow.reseed(sh)
                LedShow.place(sh, snap = false)
                sh.level.playSound(null, h.x, h.y + 1.0, h.z, TradeSounds.get("trade.harness_buckle"), SoundSource.NEUTRAL, 0.8f, 1.0f)
            }
            val rec = LedShow.recOf(sh) ?: return
            LedShow.keep(sh, rec, WagonRec.State.PARKED)
            rec.state = WagonRec.State.PARKED
            TradeWagons.peek(sh.level)?.changed()
        }

        /** The brake on, the lantern lit if it is dusk, the pennant run up, the hand's hat tipped and off they go. False once they have gone. */
        private fun done(sh: LedShow.Shown, w: Wagon): Boolean {
            val level = sh.level
            if (age == 1) {
                harness(sh)
                LedShow.brake(sh)
                LedShow.recOf(sh)?.let { rec ->
                    sh.raised = level.gameTime
                    LedShow.lantern(sh, rec)
                }
                sh.sendNow = true
                val staff = w.point(-14f, -44f, -40f)
                level.playSound(null, staff.x, staff.y, staff.z, TradeSounds.get("trade.canvas_flap"), SoundSource.NEUTRAL, 0.7f, 1.1f)
                hand?.let { p ->
                    val reader = level.server.playerList.getPlayer(reader)?.takeIf { it.level() === level && it.distanceToSqr(p) < 24.0 * 24.0 }
                    if (reader != null) p.lookAt(reader)
                    p.act(Activity.TIP_HAT)
                }
            }
            val p = hand ?: return age < 20
            if (age == 30) {
                hold(p, false)
                val (fx, fz) = LedShow.forward(w.yRot)
                val away = Vec3(p.x + fx * 10.0, p.y, p.z + fz * 10.0)
                p.navigation.moveTo(away.x, away.y, away.z, 0.6)
            }
            if (age > 30 && (age > LEAVE_TICKS || p.navigation.isDone)) return false
            return true
        }

        /** Puts everything right at once: the horse in the shafts, the record parked, the pennant up, the hand gone. */
        private fun finish(sh: LedShow.Shown) {
            if (gone) return
            gone = true
            harness(sh)
            if (!sh.wagon.isRemoved) {
                if (sh.raised < 0L) {
                    sh.raised = sh.level.gameTime
                    sh.sendNow = true
                }
                sh.wagon.flag(Wagon.FLAG_BRAKE, true)
                LedShow.recOf(sh)?.let { LedShow.lantern(sh, it) }
            }
            hand?.let { p ->
                hand = null
                if (!p.isRemoved) {
                    (p.level() as? ServerLevel)?.sendParticles(ParticleTypes.POOF, p.x, p.y + 0.6, p.z, 8, 0.3, 0.4, 0.3, 0.02)
                    p.director = null
                    p.discard()
                }
            }
        }

        private fun bez(t: Double): Vec3 {
            val a = curve[0]!!
            val b = curve[1]!!
            val c = curve[2]!!
            val d = curve[3]!!
            val u = 1 - t
            val k0 = u * u * u
            val k1 = 3 * u * u * t
            val k2 = 3 * u * t * t
            val k3 = t * t * t
            return Vec3(
                a.x * k0 + b.x * k1 + c.x * k2 + d.x * k3,
                a.y * k0 + b.y * k1 + c.y * k2 + d.y * k3,
                a.z * k0 + b.z * k1 + c.z * k2 + d.z * k3,
            )
        }

        private fun tangent(t: Double): Vec3 {
            val a = curve[0]!!
            val b = curve[1]!!
            val c = curve[2]!!
            val d = curve[3]!!
            val u = 1 - t
            val k0 = 3 * u * u
            val k1 = 6 * u * t
            val k2 = 3 * t * t
            return Vec3(
                (b.x - a.x) * k0 + (c.x - b.x) * k1 + (d.x - c.x) * k2,
                0.0,
                (b.z - a.z) * k0 + (c.z - b.z) * k1 + (d.z - c.z) * k2,
            )
        }

        /** The curve's parameter [s] blocks along it. */
        private fun tAt(s: Double): Double {
            for (k in 1..SAMPLES) {
                if (samples[k] >= s) {
                    val span = samples[k] - samples[k - 1]
                    val f = if (span < 1e-9) 0.0 else (s - samples[k - 1]) / span
                    return ((k - 1) + f) / SAMPLES
                }
            }
            return 1.0
        }
    }

    /** The yard hand's day: the rollout drives them; a one-shot settles back. */
    private object Hand : Director {
        override fun tick(p: Townsperson) {
            if (p.actDone) p.act(Activity.NONE)
        }
    }

    /** [p] carried by the scene's hand ([on]) or on their own feet again. */
    private fun hold(p: Townsperson, on: Boolean) {
        p.noPhysics = on
        p.isNoGravity = on
        p.deltaMovement = Vec3.ZERO
        if (on) p.navigation.stop()
    }

    /** [p] stood at [at] on the ground there, facing [yaw]. */
    private fun put(sh: LedShow.Shown, p: Townsperson, at: Vec3, yaw: Float) {
        val y = Ground.stand(sh.level, at.x, at.z, sh.wagon.y + 0.6)
        p.setPos(at.x, y, at.z)
        p.deltaMovement = Vec3.ZERO
        p.yRot = yaw
        p.yBodyRot = yaw
        p.yHeadRot = yaw
    }

    /** How far back the wagon is pushed and in how long; the horse's pace led round, and the longest it may take; backing in and buckling up. */
    private const val BACK = 2.5
    private const val BACK_TICKS = 50
    private const val LEAD_SPEED = 0.08
    private const val LEAD_MOST = 200
    private const val BACK_IN_TICKS = 40
    private const val HITCH_TICKS = 50
    private const val HITCH_AT = 25
    private const val LEAVE_TICKS = 110
    private const val SAMPLES = 16

    /** The hitch's tick the right trace is taken up in the hand (HITCH's grab, twelve before its clip at [HITCH_AT]): the wagon draws it drawn up from then. */
    private const val TRACE_UP_AT = 13
}
