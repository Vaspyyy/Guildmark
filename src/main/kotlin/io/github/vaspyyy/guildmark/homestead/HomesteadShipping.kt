package io.github.vaspyyy.guildmark.homestead

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.GuildmarkConfig
import io.github.vaspyyy.guildmark.city.Capitals
import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.GoodsList
import io.github.vaspyyy.guildmark.economy.Lot
import io.github.vaspyyy.guildmark.economy.Lots
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.economy.SupplierLink
import io.github.vaspyyy.guildmark.economy.Suppliers
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.village.VillageNames
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.UUIDUtil
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Prediction
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.level.BlockEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.UUID
import java.util.function.Supplier
import kotlin.math.floor

/**
 * One morning's shipment from a homestead (see [HomesteadShipping]): the [day] it went (see [HomesteadShipping.today]),
 * the capital it went to ([place], its name), how many of each good that market took ([sold], by [Good] ordinal) for
 * [marks] Guild Marks, and how many it had no room for ([left]), which stayed in the crates.
 */
class Shipment(val day: Long, val place: String, val sold: IntArray, val marks: Int, val left: IntArray) {
    /** Whether the market took nothing (it had room for none of what was there). */
    val nothing: Boolean get() = sold.all { it <= 0 }
}

/**
 * A homestead's shipping book: whose homestead it is ([owner]), the Marks in its Charter Post's purse until they take
 * them ([purse]), the last morning its crates were shipped ([day], see [HomesteadShipping.today]), its latest
 * shipments, newest first, and the shipping crates found on its land besides those its plan set out ([crates], packed
 * positions).
 */
class Book(
    val owner: UUID,
    var purse: Int = 0,
    var day: Long,
    val shipments: MutableList<Shipment> = ArrayList(),
    val crates: MutableSet<Long> = LinkedHashSet(),
)

/**
 * Every homestead's shipping book, by homestead id, and the purses of charters given up while their owners were away
 * ([owed]: Marks to hand them when they're next in). Kept with the overworld.
 */
class ShippingBooks(private val books: MutableMap<Int, Book> = HashMap(), val owed: MutableMap<UUID, Int> = HashMap()) : SavedData() {
    /** Homestead [id]'s book, if it has one yet. */
    fun of(id: Int): Book? = books[id]

    /** [h]'s book, begun now if it has none: shipped as of [today], so its first shipment is the next morning's. */
    fun open(h: Homestead, today: Long): Book = books.getOrPut(h.id) {
        setDirty()
        Book(h.owner, day = today)
    }

    fun remove(id: Int): Book? = books.remove(id)?.also { setDirty() }

    /** The books whose homestead is no longer among [ids], taken out. */
    fun orphans(ids: Set<Int>): List<Book> {
        val gone = books.keys.filter { it !in ids }
        return gone.mapNotNull { remove(it) }
    }

    fun changed() = setDirty()

    companion object {
        private val COUNTS: Codec<Map<String, Int>> = Codec.unboundedMap(Codec.STRING, Codec.INT)

        private fun named(values: IntArray): Map<String, Int> =
            Good.entries.filter { values.getOrElse(it.ordinal) { 0 } > 0 }.associate { it.id to values[it.ordinal] }

        private fun counted(map: Map<String, Int>): IntArray {
            val out = IntArray(Good.entries.size)
            for ((id, n) in map) Good.byId(id)?.let { out[it.ordinal] = n }
            return out
        }

        private val SHIPMENT: Codec<Shipment> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("day").forGetter(Shipment::day),
                Codec.STRING.optionalFieldOf("place", "").forGetter(Shipment::place),
                COUNTS.optionalFieldOf("sold", mapOf()).forGetter { named(it.sold) },
                Codec.INT.optionalFieldOf("marks", 0).forGetter(Shipment::marks),
                COUNTS.optionalFieldOf("left", mapOf()).forGetter { named(it.left) },
            ).apply(i) { day, place, sold, marks, left -> Shipment(day, place, counted(sold), marks, counted(left)) }
        }

        private val BOOK: Codec<Pair<Int, Book>> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("homestead").forGetter { it.first },
                UUIDUtil.CODEC.fieldOf("owner").forGetter { it.second.owner },
                Codec.INT.optionalFieldOf("purse", 0).forGetter { it.second.purse },
                Codec.LONG.fieldOf("day").forGetter { it.second.day },
                SHIPMENT.listOf().optionalFieldOf("shipments", listOf()).forGetter { it.second.shipments },
                Codec.LONG.listOf().optionalFieldOf("crates", listOf()).forGetter { it.second.crates.toList() },
            ).apply(i) { id, owner, purse, day, shipments, crates ->
                id to Book(owner, purse, day, shipments.toMutableList(), LinkedHashSet(crates))
            }
        }

        private val CODEC: Codec<ShippingBooks> = RecordCodecBuilder.create { i ->
            i.group(
                BOOK.listOf().optionalFieldOf("books", listOf()).forGetter { s -> s.books.map { (id, b) -> id to b } },
                Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.INT).optionalFieldOf("owed", mapOf()).forGetter { it.owed },
            ).apply(i) { books, owed -> ShippingBooks(books.toMap(HashMap()), HashMap(owed)) }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "homestead_shipping"), Supplier { ShippingBooks() }, CODEC)

        fun get(level: ServerLevel): ShippingBooks = level.server.overworld().dataStorage.computeIfAbsent(TYPE)
    }
}

/**
 * Shipping from a homestead (design-farmer.md, 3). Each morning at seven the carters of the nearest capital within
 * [REACH] of a homestead's post (one whose market is open: someone has been there) empty its shipping crates: each
 * good that market deals in is sold at its stall lots, which fall as they go, until there's none left or the market
 * has no room for more, and whatever it won't take stays in the crates. The Marks go into the Charter Post's purse,
 * which the owner empties from the ledger; the ledger says what went where for how much, and standing (see
 * [SupplierLink]) counts what came of goods the owner and their party didn't buy at that market this week.
 *
 * While someone is near the post to see it, the morning's carter really comes ([Carter]), and the sale is made as
 * they open the crates; otherwise it's simply made. Either way it's the same sale, made once a morning: a morning
 * missed (a long sleep, the server off) isn't made up for, and a clock set back waits for its next seven o'clock.
 */
object HomesteadShipping {
    /** Blocks from a homestead's post a capital's carters come. */
    const val REACH = 1500.0

    /** Ticks into the day the carters come: seven in the morning. */
    const val MORNING = 1000L

    /** How near the post someone must be for the carter to come in person. */
    const val SEEN = 64.0

    private const val DAY = 24000L

    /** Shipments a book keeps for the ledger. */
    private const val KEPT = 4

    /** Server ticks between looks at whether a homestead's morning has come, and between tidyings of the books. */
    private const val CHECK = 20
    private const val TIDY = 1200

    /** The capitals' names, by centre, as worked out this session (finding a capital may read its chunk from disk). */
    private val names = HashMap<Long, String>()

    /** Whether homesteads ship (GuildmarkConfig homesteadShipping, and the markets themselves). */
    fun enabled(): Boolean = try {
        GuildmarkConfig.HOMESTEAD_SHIPPING.get() && Markets.enabled()
    } catch (e: IllegalStateException) {
        true
    }

    /** Which morning it is: how many seven o'clocks the overworld's clock has passed (-1 before the first). */
    fun today(level: ServerLevel): Long = Math.floorDiv(level.overworldClockTime - MORNING, DAY)

    /** Hooks shipping into the game: the morning's look, crates set down, charters given up, owners logging in. */
    fun register() {
        val bus = NeoForge.EVENT_BUS
        bus.addListener(ServerTickEvent.Post::class.java) { tick(it.server) }
        bus.addListener(ServerStoppedEvent::class.java) { _ ->
            names.clear()
            Carter.reset()
        }
        bus.addListener(EntityJoinLevelEvent::class.java, Carter::onJoin)
        bus.addListener(BlockEvent.EntityPlaceEvent::class.java, ::placed)
        bus.addListener(HomesteadEvent.Removed::class.java) { removed(it.level, it.homestead) }
        bus.addListener(PlayerEvent.PlayerLoggedInEvent::class.java) { (it.entity as? ServerPlayer)?.let(::loggedIn) }
    }

    private fun tick(server: MinecraftServer) {
        if (server.tickCount % CHECK != 0) return
        val level = server.overworld()
        val books = ShippingBooks.get(level)
        val homesteads = Homesteads.all(level)
        if (server.tickCount % TIDY == 0) {
            for (book in books.orphans(homesteads.map { it.id }.toSet())) owe(level, book.owner, book.purse)
        }
        if (!enabled()) {
            Carter.dismissAll()
            return
        }
        val today = today(level)
        for (h in homesteads) {
            val book = books.open(h, today)
            // The clock set back: the next seven o'clock ships
            if (book.day > today) {
                book.day = today
                books.changed()
            }
            if (book.day >= today || Carter.coming(h.id)) continue
            due(level, h, book, today)
        }
    }

    /** [h]'s morning has come: a carter sent while someone's there to see it, otherwise the crates emptied now. */
    private fun due(level: ServerLevel, h: Homestead, book: Book, today: Long) {
        val target = target(level, h)
        if (target == null) {
            // No market within reach: nothing goes this morning
            book.day = today
            ShippingBooks.get(level).changed()
            return
        }
        // The market worked out up to this hour first, so the goods sell into it as it stands now
        if (target.entry.market.hour < Markets.hourOf(level)) return
        if (watched(level, h) && wanted(level, h, book, target) && Carter.send(level, h, book, forced = false)) return
        ship(level, h, book, target)
    }

    /** Whether someone is near [h]'s post to see a carter come. */
    private fun watched(level: ServerLevel, h: Homestead): Boolean {
        val post = Vec3.atCenterOf(h.post)
        return level.players().any { !it.isSpectator && it.distanceToSqr(post) < SEEN * SEEN }
    }

    /** Whether [h]'s crates (those loaded) hold anything [target]'s market would take now: else no carter comes for it. */
    private fun wanted(level: ServerLevel, h: Homestead, book: Book, target: Target): Boolean {
        val held = held(crates(level, h, book, load = false))
        val share = Suppliers.share(level, target.entry.capital, h.owner)
        return Good.TRADED.any { held[it.ordinal] > 0 && Lots.sell(target.entry.market, target.entry.town, it, share) != null }
    }

    // ---- Where the goods go ----

    /** Where a homestead's goods go: [entry]'s market, in the capital called [place] (empty if its name couldn't be found). */
    class Target(val entry: Markets.Entry, val place: String)

    /** The market of the nearest capital within [REACH] of [h]'s post, of those whose market is open. */
    fun target(level: ServerLevel, h: Homestead): Target? {
        val post = h.post
        fun away(entry: Markets.Entry): Double {
            val dx = (entry.capital.x - post.x).toDouble()
            val dz = (entry.capital.z - post.z).toDouble()
            return dx * dx + dz * dz
        }
        val entry = Markets.get(level).all().filter { away(it) <= REACH * REACH }.minByOrNull(::away) ?: return null
        val place = names.getOrPut(entry.market.capital) { Capitals.at(level, entry.capital)?.let { VillageNames.of(it.boardAnchor) } ?: "" }
        return Target(entry, place)
    }

    /** [place] for a sentence: the capital's name, or "the capital" if it couldn't be found. */
    fun placeName(place: String): Component =
        if (place.isEmpty()) Component.translatable("gui.guildmark.homestead.ship.the_capital") else Component.literal(place)

    // ---- The crates ----

    /**
     * [h]'s shipping crates, nearest the gate first: those its plan set out, those set down on its land since, and any
     * others where its land is loaded now (they're remembered from then on). With [load], a crate's chunk is loaded to
     * get at it (a morning's shipment with nobody about); otherwise only the crates loaded now are counted.
     */
    fun crates(level: ServerLevel, h: Homestead, book: Book, load: Boolean): List<GoodsCrateBlockEntity> {
        val found = LinkedHashMap<Long, GoodsCrateBlockEntity>()
        val before = book.crates.size
        val known = Homesteads.places(level, h).shipping.map { it.asLong() } + book.crates
        for (packed in known) {
            val pos = BlockPos.of(packed)
            if (!level.isLoaded(pos)) {
                if (!load) continue
                level.getChunk(pos)
            }
            val crate = level.getBlockEntity(pos) as? GoodsCrateBlockEntity
            if (crate != null && crate.kind == GoodsCrateBlock.Kind.SHIPPING) found[packed] = crate else book.crates.remove(packed)
        }
        val box = h.reserved(level.minY, level.maxY)
        for (cx in (box.minX() shr 4)..(box.maxX() shr 4)) for (cz in (box.minZ() shr 4)..(box.maxZ() shr 4)) {
            val chunk = level.chunkSource.getChunkNow(cx, cz) ?: continue
            for (entity in chunk.blockEntities.values) {
                if (entity !is GoodsCrateBlockEntity || entity.isRemoved || entity.kind != GoodsCrateBlock.Kind.SHIPPING) continue
                val pos = entity.blockPos
                if (!h.covers(pos.x, pos.z) || found.containsKey(pos.asLong())) continue
                found[pos.asLong()] = entity
                book.crates.add(pos.asLong())
            }
        }
        if (book.crates.size != before) ShippingBooks.get(level).changed()
        val gate = h.frame.centre(0.0, h.post.y.toDouble(), 0.0)
        return found.values.sortedBy { Vec3.atCenterOf(it.blockPos).distanceToSqr(gate) }
    }

    /** How many of each good the market deals in [crates] hold, by [Good] ordinal. */
    private fun held(crates: List<GoodsCrateBlockEntity>): IntArray {
        val held = IntArray(Good.entries.size)
        for (crate in crates) for (i in 0 until crate.containerSize) {
            val stack = crate.getItem(i)
            Good.of(stack.item)?.let { held[it.ordinal] += stack.count }
        }
        return held
    }

    /** A shipping crate set down on a homestead's land: remembered, so its goods go even with its chunk unloaded. */
    private fun placed(event: BlockEvent.EntityPlaceEvent) {
        val level = event.level as? ServerLevel ?: return
        if (!event.placedBlock.`is`(HomesteadRegistry.SHIPPING_CRATE.get())) return
        val h = Homesteads.at(level, event.pos) ?: return
        val books = ShippingBooks.get(level)
        if (books.open(h, today(level)).crates.add(event.pos.asLong())) books.changed()
    }

    // ---- The sale ----

    /**
     * What a shipment took: the [shipment] as the ledger tells it, and what came out of each crate ([taken]: where the
     * crate stands and how many of each good, by [Good] ordinal), for the carter to load.
     */
    class Sale(val shipment: Shipment, val taken: List<Pair<BlockPos, IntArray>>)

    /**
     * The carter has opened [h]'s crates: the morning's sale, if it's still to be made (or anyway, [forced] by the test
     * command). Null if nothing was due or there's no market within reach.
     */
    fun opened(level: ServerLevel, h: Homestead, forced: Boolean): Sale? {
        val today = today(level)
        val book = ShippingBooks.get(level).open(h, today)
        if (!forced && book.day >= today) return null
        val target = target(level, h)
        if (target == null) {
            book.day = today
            ShippingBooks.get(level).changed()
            return null
        }
        return ship(level, h, book, target)
    }

    /**
     * Ships [h]'s crates to [target]'s market now: each good it deals in sold at its stall lots, which fall as they go,
     * until there's none left or no room for more (the last few short of a lot fetch what they're worth at the lot's
     * rate, if that comes to a Mark). What isn't sold stays where it is. The Marks go into the post's purse, and the
     * ledger, the owner and their standing hear of it.
     */
    fun ship(level: ServerLevel, h: Homestead, book: Book, target: Target): Sale {
        val today = today(level)
        val crates = crates(level, h, book, load = true)
        val m = target.entry.market
        val town = target.entry.town
        val held = held(crates)
        val sold = IntArray(Good.entries.size)
        val earned = IntArray(Good.entries.size)
        // The owner's standing as a supplier there pays a little more, as it does selling by hand
        val share = Suppliers.share(level, target.entry.capital, h.owner)
        for (good in Good.TRADED) {
            var have = held[good.ordinal]
            while (have > 0) {
                val lot = Lots.sell(m, town, good, share) ?: break
                val part = if (lot.count <= have) lot else Lot(have, floor(have * lot.each).toInt())
                if (part.marks < 1) break
                Lots.sold(m, good, part)
                have -= part.count
                sold[good.ordinal] += part.count
                earned[good.ordinal] += part.marks
            }
        }
        // Out of the crates, nearest the gate first
        val owing = sold.copyOf()
        val taken = ArrayList<Pair<BlockPos, IntArray>>()
        for (crate in crates) {
            val out = IntArray(Good.entries.size)
            for (i in 0 until crate.containerSize) {
                val stack = crate.getItem(i)
                val good = Good.of(stack.item) ?: continue
                val n = minOf(stack.count, owing[good.ordinal])
                if (n <= 0) continue
                crate.removeItem(i, n)
                owing[good.ordinal] -= n
                out[good.ordinal] += n
            }
            if (out.any { it > 0 }) taken.add(crate.blockPos to out)
        }
        val marks = earned.sum()
        val shipment = Shipment(today, target.place, sold, marks, IntArray(Good.entries.size) { held[it] - sold[it] })
        book.day = today
        if (held.any { it > 0 }) {
            book.purse += marks
            book.shipments.add(0, shipment)
            while (book.shipments.size > KEPT) book.shipments.removeAt(book.shipments.size - 1)
        }
        ShippingBooks.get(level).changed()
        if (marks > 0) {
            Markets.get(level).changed()
            standing(level, h, target, sold, earned)
            tell(level, h, shipment)
        }
        return Sale(shipment, taken)
    }

    /**
     * Standing for a shipment, at the rate for selling at the stalls (see [SupplierLink.earned]): the Marks of each good
     * less the share that came of what the owner and their party bought at that market in the last week.
     */
    private fun standing(level: ServerLevel, h: Homestead, target: Target, sold: IntArray, earned: IntArray) {
        var marks = 0.0
        for (good in Good.TRADED) {
            val n = sold[good.ordinal]
            if (n <= 0) continue
            val bought = SupplierLink.boughtByParty(level, h.owner, target.entry.capital, good).coerceIn(0, n)
            marks += earned[good.ordinal].toDouble() * (n - bought) / n
        }
        val whole = floor(marks + 1e-6).toInt()
        if (whole > 0) SupplierLink.earned(level, h.owner, target.entry.capital, whole)
    }

    /** [h]'s owner, if they're in, hears what the carter took and what the market had no room for. */
    private fun tell(level: ServerLevel, h: Homestead, s: Shipment) {
        val owner = level.server.playerList.getPlayer(h.owner) ?: return
        val place = placeName(s.place)
        owner.sendSystemMessage(Component.translatable("message.guildmark.homestead.shipped", GoodsList.of(s.sold), place, s.marks).withStyle(ChatFormatting.GOLD))
        if (s.left.any { it > 0 }) {
            owner.sendSystemMessage(Component.translatable("message.guildmark.homestead.ship_no_room", place, GoodsList.of(s.left)).withStyle(ChatFormatting.GRAY))
        }
    }

    // ---- The purse ----

    /** [player], [h]'s owner at its post, takes what's in its purse. */
    fun collect(level: ServerLevel, player: ServerPlayer, h: Homestead) {
        if (h.owner != player.uuid) return
        val books = ShippingBooks.get(level)
        val book = books.of(h.id) ?: return
        val marks = book.purse
        if (marks <= 0) return
        book.purse = 0
        books.changed()
        give(player, marks)
        level.playSound(null, h.post, HomesteadRegistry.PURSE.get(), SoundSource.BLOCKS, 0.8f, 1f)
        player.sendSystemMessage(Component.translatable("message.guildmark.homestead.purse_taken", marks).withStyle(ChatFormatting.GOLD))
    }

    /** A charter given up: no more carters, and its purse goes back to its owner, now or when they're next in. */
    private fun removed(level: ServerLevel, h: Homestead) {
        Carter.dismiss(h.id)
        val book = ShippingBooks.get(level).remove(h.id) ?: return
        owe(level, h.owner, book.purse)
    }

    /** [marks] from a purse to [owner]: into their pack if they're in, else kept for them. */
    private fun owe(level: ServerLevel, owner: UUID, marks: Int) {
        if (marks <= 0) return
        val player = level.server.playerList.getPlayer(owner)
        if (player != null) {
            give(player, marks)
            player.sendSystemMessage(Component.translatable("message.guildmark.homestead.purse_returned", marks).withStyle(ChatFormatting.GOLD))
            return
        }
        val books = ShippingBooks.get(level)
        books.owed.merge(owner, marks, Int::plus)
        books.changed()
    }

    /** In again: any purse kept for them handed over, and a word if their post's purse has Marks waiting. */
    private fun loggedIn(player: ServerPlayer) {
        val level = player.level().server.overworld()
        val books = ShippingBooks.get(level)
        books.owed.remove(player.uuid)?.let { marks ->
            books.changed()
            give(player, marks)
            player.sendSystemMessage(Component.translatable("message.guildmark.homestead.purse_returned", marks).withStyle(ChatFormatting.GOLD))
        }
        val h = Homesteads.of(level, player.uuid) ?: return
        val purse = books.of(h.id)?.purse ?: 0
        if (purse > 0) player.sendSystemMessage(Component.translatable("message.guildmark.homestead.purse_waiting", purse).withStyle(ChatFormatting.GOLD))
    }

    private fun give(player: ServerPlayer, marks: Int) {
        var left = marks
        val most = ItemStack(ModItems.GUILD_MARK.get()).maxStackSize
        while (left > 0) {
            val n = minOf(left, most)
            player.inventory.placeItemBackInInventory(ItemStack(ModItems.GUILD_MARK.get(), n), Prediction.SERVER_ONLY)
            left -= n
        }
    }

    // ---- The ledger ----

    /** How [h]'s shipping stands, for its ledger. */
    fun view(level: ServerLevel, h: Homestead): ShippingView {
        val today = today(level)
        val book = ShippingBooks.get(level).open(h, today)
        val target = target(level, h)
        val crates = crates(level, h, book, load = false)
        val goods = IntArray(Good.entries.size)
        var others = 0
        for (crate in crates) for (i in 0 until crate.containerSize) {
            val stack = crate.getItem(i)
            if (stack.isEmpty) continue
            val good = Good.of(stack.item)
            if (good != null) goods[good.ordinal] += stack.count else others += stack.count
        }
        val next = if (book.day < today) 0 else ((today + 1) * DAY + MORNING - level.overworldClockTime).coerceIn(0L, DAY).toInt()
        return ShippingView(
            enabled(), book.purse, target != null, target?.place ?: "", next, Carter.at(h.id)?.id ?: -1, crates.map { it.blockPos }, goods, others,
            book.shipments.map { note(it, today) },
        )
    }

    /** A shipment as the ledger words it: "This morning: the carter took 64 Wheat and 20 Carrots to Hollowmere: 31 Marks." */
    private fun note(s: Shipment, today: Long): Component {
        val ago = today - s.day
        val time = when {
            ago <= 0 -> Component.translatable("gui.guildmark.homestead.ship.when.today")
            ago == 1L -> Component.translatable("gui.guildmark.homestead.ship.when.yesterday")
            else -> Component.translatable("gui.guildmark.homestead.ship.when.ago", ago)
        }
        val place = placeName(s.place)
        val body = when {
            s.nothing -> Component.translatable("gui.guildmark.homestead.ship.no_room", place, GoodsList.of(s.left))
            s.left.any { it > 0 } -> Component.translatable("gui.guildmark.homestead.ship.took_some", GoodsList.of(s.sold), place, s.marks, GoodsList.of(s.left))
            else -> Component.translatable("gui.guildmark.homestead.ship.took", GoodsList.of(s.sold), place, s.marks)
        }
        return Component.translatable("gui.guildmark.homestead.ship.note", time, body)
    }
}
