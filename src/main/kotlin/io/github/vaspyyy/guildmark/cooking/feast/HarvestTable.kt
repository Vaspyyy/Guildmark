package io.github.vaspyyy.guildmark.cooking.feast

import io.github.vaspyyy.guildmark.cooking.CookSounds
import io.github.vaspyyy.guildmark.cooking.CookStats
import io.github.vaspyyy.guildmark.cooking.Cooking
import io.github.vaspyyy.guildmark.cooking.DishData
import io.github.vaspyyy.guildmark.cooking.DishKind
import io.github.vaspyyy.guildmark.cooking.Door
import io.github.vaspyyy.guildmark.cooking.Tier
import io.github.vaspyyy.guildmark.cooking.dish.DishTooltip
import io.github.vaspyyy.guildmark.cooking.dish.Dishes
import io.github.vaspyyy.guildmark.cooking.dish.Plate
import io.github.vaspyyy.guildmark.cooking.dish.PlateBlock
import io.github.vaspyyy.guildmark.cooking.dish.PlateBlockEntity
import io.github.vaspyyy.guildmark.cooking.market.Index
import io.github.vaspyyy.guildmark.cooking.market.Kitchens
import io.github.vaspyyy.guildmark.cooking.market.Pricing
import io.github.vaspyyy.guildmark.cooking.recipe.Tastes
import io.github.vaspyyy.guildmark.economy.Markets
import io.github.vaspyyy.guildmark.festival.FestivalContest
import io.github.vaspyyy.guildmark.festival.FestivalPlan
import io.github.vaspyyy.guildmark.festival.FestivalRestores
import io.github.vaspyyy.guildmark.festival.FestivalSite
import io.github.vaspyyy.guildmark.festival.Festivities
import io.github.vaspyyy.guildmark.season.Season
import io.github.vaspyyy.guildmark.season.Seasons
import io.github.vaspyyy.guildmark.townsfolk.Activity
import io.github.vaspyyy.guildmark.townsfolk.Residents
import io.github.vaspyyy.guildmark.townsfolk.TownRole
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.townsfolk.talk.sources.KitchenLines
import io.github.vaspyyy.guildmark.townsfolk.talk.sources.KitchenRumours
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.level.ExplosionEvent
import net.neoforged.neoforge.event.level.block.BreakBlockEvent
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Harvest Table at Sheafmoot (living-world/cook/design.md 6.8, Part L): the cook-off beside the Produce Show, held at a
 * capital's Sheafmoot only. What a dish is worth ([score]) is worked out here, and the trestle's plates are dressed here
 * ([show], [clear]); the entries are the festival contest's own (`FestivalContest.taste`, `cookResults`), in memory only.
 *
 * The trestle is a few top oak slabs beside the show bench, each with a Plate (Part D's) on it, put up and taken down with the
 * festival's other decorations (`FestivalPlan`'s trestle, through `FestivalRestores`). Its plates are always laid for show, the
 * town's ([Plate.show]): nothing on them can be taken, eaten or broken off, and a player's dish is never laid there.
 */
object HarvestTable {
    /** The language key of the table's name, "Harvest Table". */
    const val TITLE = "festival.guildmark.harvest_table"

    // =============================================================================================== what a dish is worth

    /** A tier's points before its kind and the season: Plain 4, Sound 8, Fine 13, Hallmarked 20; Spoilt nothing. */
    fun tierPoints(tier: Tier): Int = when (tier) {
        Tier.SPOILT -> 0
        Tier.PLAIN -> 4
        Tier.SOUND -> 8
        Tier.FINE -> 13
        Tier.HALLMARKED -> 20
    }

    /** What a kind asks of its cook, as a factor: 1 + 0.15 for each step of its complexity (2.5), pottage 1 to the roast's 1.6. */
    fun kindFactor(kind: DishKind): Double = 1.0 + KIND_STEP * kind.complexity

    /** Whether autumn favours [kind] (the season table, 2.8: stew, roots, pie, tart and roast), so it scores [AUTUMN] times more at Sheafmoot. */
    fun autumn(kind: DishKind): Boolean = Tastes.season(kind, Season.AUTUMN) > 1.0

    /** What a dish of [kind] at [tier] scores: the tier, the kind and the autumn, rounded. A Hallmarked roast is 38, a Fine pie 23. */
    fun score(kind: DishKind, tier: Tier): Int =
        Math.round(tierPoints(tier) * kindFactor(kind) * (if (autumn(kind)) AUTUMN else 1.0)).toInt()

    /** The tier a dish of [kind] was marked at, from its [points] (each tier of a kind scores differently). */
    fun tierOf(kind: DishKind, points: Int): Tier = Tier.entries.lastOrNull { it != Tier.SPOILT && score(kind, it) <= points } ?: Tier.PLAIN

    /** How a dish's points are reached, as the host and `cook feast score` tell it: "Hallmarked 20 × Spit Roast 1.6 × autumn 1.2". */
    fun working(kind: DishKind, tier: Tier): Component = Component.translatable(
        if (autumn(kind)) "message.guildmark.feast.working.autumn" else "message.guildmark.feast.working",
        DishTooltip.tierWord(tier), tierPoints(tier), dishName(kind), factor(kind),
    )

    /** The kind's own name, "Spit Roast". */
    fun dishName(kind: DishKind): MutableComponent = Component.translatable("item.guildmark.dish_${kind.id}")

    /** The kind's factor as it is written: "1", "1.15", "1.6". */
    fun factor(kind: DishKind): String = BigDecimal.valueOf(kindFactor(kind)).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    // =============================================================================================== each festival's table

    /** One of the town's own entries: whether it is the inn's (else the bakery's), and the one dish it is laid on its plate as. */
    class TownDish(val inn: Boolean, val kind: DishKind, val tier: Tier)

    /**
     * A dish being tasted at the host's, or waiting to be: whose it is, a portion like it for the host's hands, what the host made
     * of it (judged as soon as it was shown), and how far the tasting has got.
     */
    private class Scene(val player: UUID, val dish: ItemStack, val tasted: FestivalContest.Tasted) {
        var beat = Beat.TURN
        var since = 0L
        var started = 0L
        var said = false
    }

    /** The tasting's steps: the host turns to the cook, is handed the dish, tastes a crumb, hands it back and shows what they thought; a refusal is the turn and a gesture. */
    private enum class Beat { TURN, RECEIVE, TASTE, HAND_BACK, REACT, GESTURE }

    /**
     * What the table keeps for one festival (by its plan): the town's dishes and how many times they were thrown, which entry each
     * plate shows and what was laid there last, the tasting under way and those waiting, and when the host next calls the cooks.
     */
    private class Table {
        val town = LinkedHashMap<UUID, TownDish>()
        var seeded = false
        var rolls = 0
        var onPlate: Array<UUID?> = arrayOf()
        var laid: Array<ItemStack> = arrayOf()
        var scene: Scene? = null
        val waiting = ArrayDeque<Scene>()
        var callAt = 0L
    }

    private val tables = HashMap<FestivalPlan, Table>()

    /** Counts since the server started, for `cook perf`: plates dressed, dishes tasted and refused. */
    private var dressed = 0
    private var tasted = 0
    private var refused = 0

    private fun table(plan: FestivalPlan): Table = tables[plan] ?: Table().also {
        // A festival that is over keeps nothing here
        if (tables.size >= 4) tables.keys.removeIf { p -> Festivities.all().none { s -> s.live && s.plan === p } }
        tables[plan] = it
    }

    // =============================================================================================== the town's two entries

    /**
     * Enters the town's two cooks when the fair opens at [site], once (again only by [reroll]), and lays the plates: the inn's (32
     * to 62 points) and the bakery's (30 to 58), each raised by 0 to 10 with how dear food has been in the capital this week ([lift]),
     * seeded by the world, the capital and the year. Each is one dish, a kind from its door's list at the tier its score is worth
     * ([townTier]), named for the town's innkeeper or baker. They take a place and are never paid. A capital's Sheafmoot only, with
     * the table's switch on.
     */
    fun townEntries(level: ServerLevel, site: FestivalSite) {
        if (!site.contest.cooking()) return
        val table = table(site.plan)
        if (!table.seeded) seed(level, site, table)
        show(level, site.plan, site.contest)
    }

    /** Throws the town's two entries at [site] again (`cook feast town reroll`). False if the table is not held there. */
    fun reroll(level: ServerLevel, site: FestivalSite): Boolean {
        if (!site.contest.cooking()) return false
        val table = table(site.plan)
        table.rolls++
        seed(level, site, table)
        show(level, site.plan, site.contest)
        return true
    }

    /** The town's entries at [site], by entry id (none until the fair has opened there with the table held). */
    fun town(site: FestivalSite): Map<UUID, TownDish> = tables[site.plan]?.town ?: mapOf()

    private fun seed(level: ServerLevel, site: FestivalSite, table: Table) {
        site.contest.clearTown()
        table.town.clear()
        table.seeded = true
        val year = Seasons.today(level).year
        val random = RandomSource.create(level.seed * 31L + site.key * 1_000_003L + year * 7_919L + table.rolls * 104_729L)
        val lift = lift(level, site.key)
        for (inn in booleanArrayOf(true, false)) {
            val (low, high) = if (inn) INN_POINTS else BAKERY_POINTS
            val points = (low + random.nextDouble() * (high - low) + lift).roundToInt()
            val door = if (inn) Door.INN else Door.BAKERY
            val kinds = DishKind.entries.filter { door in it.doors }
            val kind = kinds[random.nextInt(kinds.size)]
            val id = UUID.nameUUIDFromBytes("guildmark:harvest_table:${door.name.lowercase(Locale.ROOT)}".toByteArray())
            site.contest.addTown(id, cookName(level, site.key, inn), points)
            table.town[id] = TownDish(inn, kind, townTier(points))
        }
    }

    /** The capital [key]'s price index this week (Part B's: the meal's price as a multiple of the plain one's, 0.90 to 1.15); the low end with no market to read. */
    fun index(level: ServerLevel, key: Long): Double =
        Markets.find(level, key)?.let { Index.of(Kitchens.get(level).rec(key), it) } ?: Pricing.LOW

    /** How many points the town's cooks gain at the capital [key]: 0 where food is cheap to 10 where it is dear (a town that eats well cooks well). */
    fun lift(level: ServerLevel, key: Long): Double = (10.0 * (index(level, key) - Pricing.LOW) / (Pricing.HIGH - Pricing.LOW)).coerceIn(0.0, 10.0)

    /**
     * The tier a town entry's one dish is laid at: the one whose best three kinds a cook's score is nearest (Plain about 22, Sound 43,
     * Fine 71, Hallmarked 108), so the cut is halfway between them.
     */
    fun townTier(points: Int): Tier = when {
        points < 33 -> Tier.PLAIN
        points < 57 -> Tier.SOUND
        points < 90 -> Tier.FINE
        else -> Tier.HALLMARKED
    }

    /** The town's innkeeper (or baker, not [inn]) by its record, else "the inn's cook" ("the baker"), as a language key after a "#". */
    private fun cookName(level: ServerLevel, key: Long, inn: Boolean): String {
        val role = if (inn) TownRole.INNKEEPER else TownRole.BAKER
        val r = Residents.get(level).of(BlockPos.of(key)).firstOrNull { it.alive && it.role == role && it.given.isNotEmpty() }
        return r?.name ?: "#$TITLE.${if (inn) "inn_cook" else "baker"}"
    }

    // =============================================================================================== the host's tasting

    /**
     * The host [p] of [site] was shown [stack] by [player] for the Harvest Table and judged it [tasted]: the tasting plays, or waits
     * its turn behind the one under way (a few may wait; any more are told at once). A refusal is a turn to the cook and a gesture
     * with the host's words, or, while the host is tasting, only the words. True: the host has dealt with it.
     */
    fun judge(level: ServerLevel, site: FestivalSite, p: Townsperson, player: ServerPlayer, stack: ItemStack, tasted: FestivalContest.Tasted): Boolean {
        val table = table(site.plan)
        counted(level, site, table, tasted)
        val now = level.gameTime
        table.scene?.let { if (now - it.started > LIMIT) cut(level, site, p, table) }
        val scene = Scene(player.uuid, stack.copyWithCount(1), tasted)
        when {
            table.scene == null -> start(level, p, table, scene)
            !tasted.judged -> site.contest.tell(player, tasted, p.name)
            table.waiting.size < WAITING -> {
                table.waiting.addLast(scene)
                player.sendSystemMessage(Component.translatable("message.guildmark.festival.host", p.name, Component.translatable("message.guildmark.feast.next")).withColor(site.kind.color))
            }
            else -> {
                site.contest.tell(player, tasted, p.name)
                show(level, site.plan, site.contest)
            }
        }
        return true
    }

    /**
     * `cook feast enter`: [player]'s dish, [tasted] at [site] as if shown to the host, with no host near and no tasting: told at once,
     * in the host's words, and laid on the trestle.
     */
    fun enter(level: ServerLevel, site: FestivalSite, player: ServerPlayer, tasted: FestivalContest.Tasted) {
        counted(level, site, table(site.plan), tasted)
        site.contest.tell(player, tasted, Component.translatable("festival.guildmark.host"))
        if (tasted.judged) show(level, site.plan, site.contest)
    }

    private fun counted(level: ServerLevel, site: FestivalSite, table: Table, tasted: FestivalContest.Tasted) {
        if (!tasted.judged) {
            refused++
            return
        }
        this.tasted++
        // The table came on in the middle of the fair: the town's cooks enter now
        if (!table.seeded) townEntries(level, site)
    }

    /** Whether the host of [site] is tasting a dish now (their own word with anyone else waits). */
    fun tasting(site: FestivalSite): Boolean = tables[site.plan]?.scene != null

    /**
     * The host [p]'s tick at [site]: while a tasting is under way it has them (true), turned to the cook through it; else, now and
     * then while the fair is on, they call the cooks to the table, and the festival goes on with them as ever (false).
     */
    fun hosting(level: ServerLevel, site: FestivalSite, p: Townsperson): Boolean {
        val table = tables[site.plan] ?: return false
        val now = level.gameTime
        val s = table.scene ?: return call(level, site, p, table, now)
        val who = level.server.playerList.getPlayer(s.player)
        if (who == null || !who.isAlive || who.level() !== level || who.distanceToSqr(p) > FAR * FAR || !site.contest.open || now - s.started > LIMIT) {
            cut(level, site, p, table)
            return table.scene != null
        }
        p.navigation.stop()
        p.nextSpeech = maxOf(p.nextSpeech, now + 60)
        p.lookControl.setLookAt(who, 30f, 30f)
        p.attend(who, 40)
        val yaw = p.yawTo(who.position())
        p.face(Mth.approachDegrees(p.yRot, yaw, TURN_RATE))
        val e = now - s.since
        when (s.beat) {
            Beat.TURN -> if (abs(Mth.wrapDegrees(yaw - p.yRot)) < 3f || e >= TURN) next(level, site, p, table, s, who, if (s.tasted.judged) Beat.RECEIVE else Beat.GESTURE)
            Beat.RECEIVE -> if (e >= Activity.RECEIVE.ticks) next(level, site, p, table, s, who, Beat.TASTE)
            Beat.TASTE -> {
                // The spoon is at the mouth at 32, after the sip and the nod (the clip's own moment, Part M3)
                if (e >= VERDICT_AT && !s.said) verdict(level, site, p, s, who)
                if (e >= Activity.TASTE.ticks) next(level, site, p, table, s, who, Beat.HAND_BACK)
            }
            Beat.HAND_BACK -> if (e >= Activity.HAND_OVER.ticks) next(level, site, p, table, s, who, Beat.REACT)
            Beat.REACT, Beat.GESTURE -> if (e >= p.activity.ticks.coerceAtLeast(GESTURE)) done(level, site, p, table)
        }
        return true
    }

    private fun start(level: ServerLevel, p: Townsperson, table: Table, s: Scene) {
        table.scene = s
        s.beat = Beat.TURN
        s.since = level.gameTime
        s.started = level.gameTime
        p.carried = ItemStack.EMPTY
        if (p.activity != Activity.NONE) p.act(Activity.NONE)
    }

    private fun next(level: ServerLevel, site: FestivalSite, p: Townsperson, table: Table, s: Scene, who: ServerPlayer, beat: Beat) {
        s.beat = beat
        s.since = level.gameTime
        when (beat) {
            Beat.RECEIVE -> {
                // The dish is held out and taken; the cook's own never leaves their hand (nothing is taken)
                p.carried = s.dish
                p.carriedUntil = p.tickCount + CARRY_MOST
                p.act(Activity.RECEIVE)
            }
            Beat.TASTE -> p.act(Activity.TASTE)
            Beat.HAND_BACK -> p.act(Activity.HAND_OVER)
            Beat.REACT -> {
                p.carried = ItemStack.EMPTY
                p.act(reaction(s.tasted.tier))
                if (s.tasted.tier == Tier.HALLMARKED) applause(level, site, p)
            }
            Beat.GESTURE -> {
                p.act(gesture(s.tasted.refusal))
                site.contest.tell(who, s.tasted, p.name)
                s.said = true
            }
            Beat.TURN -> {}
        }
    }

    /** The verdict, as the spoon comes down: the host's own words by tier, the tier's motif, the points told to the cook, the plates laid. */
    private fun verdict(level: ServerLevel, site: FestivalSite, p: Townsperson, s: Scene, who: ServerPlayer) {
        s.said = true
        val t = s.tasted
        voice(p, "kitchen.tier.${family(t.tier)}")
        level.playSound(null, p.x, p.y + 1.2, p.z, CookSounds.get("cook.tier.${family(t.tier)}"), SoundSource.NEUTRAL, 0.5f, 1.0f)
        if (t.tier.ordinal >= Tier.FINE.ordinal) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, p.x, p.y + 1.9, p.z, if (t.tier == Tier.HALLMARKED) 14 else 6, 0.35, 0.25, 0.35, 0.0)
        }
        site.contest.tell(who, t, p.name)
        show(level, site.plan, site.contest)
        val kind = t.dish
        if (t.tier == Tier.HALLMARKED && kind != null) KitchenRumours.hallmark(level, p.blockPosition(), who, kind)
    }

    /** The tasting under way is over: on to the next that waits. */
    private fun done(level: ServerLevel, site: FestivalSite, p: Townsperson, table: Table) {
        p.carried = ItemStack.EMPTY
        table.scene = null
        while (true) {
            val next = table.waiting.removeFirstOrNull() ?: return
            val who = level.server.playerList.getPlayer(next.player)
            if (who != null && who.isAlive && who.level() === level && who.distanceToSqr(p) <= FAR * FAR) {
                start(level, p, table, next)
                return
            }
            // Gone off meanwhile: told at once, wherever they are
            told(level, site, p, next)
        }
    }

    /**
     * The tasting under way cannot finish (the cook went off, the fair closed, or it ran too long): what the host made of the dish is
     * told at once if it was not yet, and the next goes on; with the fair closed, every one waiting is told now.
     */
    private fun cut(level: ServerLevel, site: FestivalSite, p: Townsperson, table: Table) {
        table.scene?.let { if (!it.said) told(level, site, p, it) }
        p.carried = ItemStack.EMPTY
        if (p.activity != Activity.NONE && !p.activity.loop) p.act(Activity.NONE)
        table.scene = null
        if (!site.contest.open) {
            while (true) told(level, site, p, table.waiting.removeFirstOrNull() ?: return)
        }
        done(level, site, p, table)
    }

    private fun told(level: ServerLevel, site: FestivalSite, p: Townsperson, s: Scene) {
        s.said = true
        level.server.playerList.getPlayer(s.player)?.let { site.contest.tell(it, s.tasted, p.name) }
        if (s.tasted.judged) show(level, site.plan, site.contest)
    }

    /** While the fair is on, every few minutes (and as it opens), the host calls the cooks to the Harvest Table. */
    private fun call(level: ServerLevel, site: FestivalSite, p: Townsperson, table: Table, now: Long): Boolean {
        if (!site.contest.open || !site.contest.cooking() || site.playing || now < table.callAt) return false
        if (p.distanceToSqr(site.plan.host.pos) > 1.0 || p.speech.isNotEmpty()) return false
        table.callAt = now + CALL_EVERY + level.random.nextInt(CALL_EVERY.toInt() / 2)
        p.act(Activity.CALL_OUT)
        voice(p, "kitchen.judge.open")
        return false
    }

    /** A Hallmarked dish: those of the festival's cast close by clap it. */
    private fun applause(level: ServerLevel, site: FestivalSite, p: Townsperson) {
        val near = level.getEntitiesOfClass(Townsperson::class.java, p.boundingBox.inflate(APPLAUSE, 3.0, APPLAUSE)) { it !== p && it.director === site.director && !it.activity.loop }
        for (q in near.take(8)) q.act(if (q.role == TownRole.CHILD) Activity.JUMP else Activity.CLAP)
    }

    /** The family a tier's verdict is said in: kitchen.tier.<this>. */
    private fun family(tier: Tier): String = when (tier) {
        Tier.HALLMARKED -> "hallmark"
        Tier.FINE -> "fine"
        Tier.SOUND -> "sound"
        else -> "plain"
    }

    /** How the host shows what they thought, the dish handed back: a nod for an honest or a sound one, a clap for a fine one, a bow to a master's. */
    private fun reaction(tier: Tier): Activity = when (tier) {
        Tier.HALLMARKED -> Activity.BOW
        Tier.FINE -> Activity.CLAP
        else -> Activity.NOD
    }

    /** The host's gesture with a refusal: a nod at a kind already marked, a wave at what is no dish (or gone off), a shrug for the rest. */
    private fun gesture(refusal: String?): Activity = when (refusal) {
        "message.guildmark.feast.seen" -> Activity.NOD
        "message.guildmark.feast.not_dish", "message.guildmark.feast.gone" -> Activity.WAVE_OFF
        else -> Activity.SHRUG
    }

    /**
     * Has [p] say [family] (a kitchen family of Part J's: `kitchen.tier.*`, `kitchen.judge.*`) in their own voice: a take when one is
     * recorded, else its wording as words over their head (the host's judging is the Harvest Table's, so it is said with Kitchen Talk
     * off too, as the back door's keepers say theirs). Whether anyone was near enough to hear.
     */
    fun voice(p: Townsperson, family: String): Boolean {
        if (KitchenLines.say(p, family, "harvest table", force = true)) return true
        val key = KitchenLines.wording(p, null, family) ?: return false
        return p.say(key, force = true)
    }

    // =============================================================================================== the trestle's plates

    /**
     * Lays the best entries of [contest] on the trestle of [plan], one to a plate: an entry keeps its plate while it stays among the
     * best, one that falls out of them gives its plate to the newcomer, and the rest stay bare. A player's plate shows their best
     * dish, a town entry's its own; each is named for its cook and their score ("Ransom: Hallmarked Spit Roast, 72 points"). With the
     * table not held here (or its switch off) every plate is bare, and it is still the town's. Plates in ground not loaded wait.
     */
    fun show(level: ServerLevel, plan: FestivalPlan, contest: FestivalContest) {
        val plates = plan.trestle
        if (plates.isEmpty()) return
        val table = table(plan)
        val n = plates.size
        if (table.onPlate.size != n) {
            table.onPlate = arrayOfNulls(n)
            table.laid = Array(n) { ItemStack.EMPTY }
        }
        val top = if (contest.cooking()) contest.cookResults().take(n) else listOf()
        val best = top.mapTo(HashSet()) { it.id }
        for (i in 0 until n) if (table.onPlate[i] !in best) table.onPlate[i] = null
        for (e in top) {
            if (e.id in table.onPlate) continue
            val free = table.onPlate.indexOfFirst { it == null }
            if (free >= 0) table.onPlate[free] = e.id
        }
        for (i in 0 until n) {
            val entry = table.onPlate[i]?.let { id -> top.firstOrNull { it.id == id } }
            lay(level, plates[i], entry?.let { plateOf(level, contest, table, it) } ?: ItemStack.EMPTY, table, i)
        }
    }

    /** How many of the trestle's plates at [site] show a dish now (as last laid). */
    fun dressed(site: FestivalSite): Int = tables[site.plan]?.laid?.count { !it.isEmpty } ?: 0

    /** Bares every plate on the trestle of [plan] (left dressed from before a restart, say); they stay the town's. */
    fun clear(level: ServerLevel, plan: FestivalPlan) {
        for (pos in plan.trestle) Plate.show(level, pos, ItemStack.EMPTY)
        tables[plan]?.let {
            it.onPlate = arrayOf()
            it.laid = arrayOf()
        }
    }

    /** Lays [stack] on the plate at [pos], the [i]th: a clink and a puff where it changes, nothing where it stays as it was. */
    private fun lay(level: ServerLevel, pos: BlockPos, stack: ItemStack, table: Table, i: Int) {
        if (!level.isLoaded(pos) || !Plate.show(level, pos, stack)) return
        val before = table.laid[i]
        if (ItemStack.matches(before, stack)) return
        table.laid[i] = stack.copy()
        dressed++
        if (stack.isEmpty && before.isEmpty) return
        level.playSound(null, pos, CookSounds.get("cook.plate"), SoundSource.BLOCKS, 0.6f, if (stack.isEmpty) 0.85f else 1.0f + level.random.nextFloat() * 0.1f)
        level.sendParticles(if (stack.isEmpty) ParticleTypes.POOF else ParticleTypes.HAPPY_VILLAGER, pos.x + 0.5, pos.y + 0.25, pos.z + 0.5, 3, 0.2, 0.1, 0.2, 0.0)
    }

    /** The dish [entry]'s plate shows: their best (a town entry's own), made up fresh today and named for them and their score. */
    private fun plateOf(level: ServerLevel, contest: FestivalContest, table: Table, entry: FestivalContest.Entry): ItemStack {
        val kind: DishKind
        val tier: Tier
        if (entry.npc) {
            val dish = table.town[entry.id] ?: return ItemStack.EMPTY
            kind = dish.kind
            tier = dish.tier
        } else {
            val (k, points) = contest.dishesOf(entry.id).maxByOrNull { it.value } ?: return ItemStack.EMPTY
            kind = k
            tier = tierOf(k, points)
        }
        val stack = Dishes.make(kind, DishData(tier, Cooking.today(level), if (entry.npc) "" else entry.name, null, 0), 1)
        stack.set(DataComponents.CUSTOM_NAME, Component.translatable("message.guildmark.feast.plate", FestivalContest.named(entry.name), stack.hoverName, entry.score))
        return stack
    }

    /** Whether [pos] holds a plate of a festival's laid for show (the trestle's): nobody breaks it, nor does a blast. */
    private fun guarded(level: ServerLevel, pos: BlockPos, state: BlockState): Boolean =
        state.block is PlateBlock && (level.getBlockEntity(pos) as? PlateBlockEntity)?.forShow == true && FestivalRestores.recordAt(level, pos) != null

    // =============================================================================================== registration

    /** Part L's registration: its line for `cook perf`, the `cook feast` branch, and the trestle's plates kept whole. */
    fun register() {
        CookStats.readout("Harvest Table") { stats() }
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { FeastCommand.register(it.dispatcher) }
        Cooking.onForget {
            tables.clear()
            dressed = 0
        }
        NeoForge.EVENT_BUS.addListener(BreakBlockEvent::class.java) { event ->
            val level = event.level as? ServerLevel ?: return@addListener
            if (event.player?.isCreative == true || !guarded(level, event.pos, event.state)) return@addListener
            event.isCanceled = true
            event.player?.sendOverlayMessage(Component.translatable("message.guildmark.cook.plate_show"))
        }
        NeoForge.EVENT_BUS.addListener(ExplosionEvent.Detonate::class.java) { event ->
            val level = event.level as? ServerLevel ?: return@addListener
            if (tables.isEmpty()) return@addListener
            event.affectedBlocks.removeIf { guarded(level, it, level.getBlockState(it)) }
        }
    }

    private fun stats(): String =
        "${tables.size} tables kept, ${tables.values.count { it.scene != null }} tasting now; $tasted dishes judged, $refused refused; $dressed plates dressed"

    /** Each step of a kind's complexity, and autumn's favour. */
    private const val KIND_STEP = 0.15
    const val AUTUMN = 1.2

    /** The town's two entries' points before the town's lift: the inn's, and the bakery's. */
    private val INN_POINTS = 32.0 to 62.0
    private val BAKERY_POINTS = 30.0 to 58.0

    /**
     * The tasting, in ticks: the most a turn to the cook takes and its rate in degrees a tick, the TASTE clip's tick the verdict is
     * said at, the least a gesture lasts, the most the dish is in the host's hands, and the most a whole tasting may take before it is
     * cut short. How far off the cook may stand, how many may wait their turn, how often the host calls the cooks, and how near the
     * cast must be to clap a Hallmarked dish.
     */
    private const val TURN = 12L
    private const val TURN_RATE = 15f
    private const val VERDICT_AT = 32L
    private const val GESTURE = 24
    private const val CARRY_MOST = 140
    private const val LIMIT = 300L
    private const val FAR = 8.0
    private const val WAITING = 3
    private const val CALL_EVERY = 3000L
    private const val APPLAUSE = 8.0
}
