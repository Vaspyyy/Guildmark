package io.github.vaspyyy.guildmark.townsfolk.talk

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.core.BlockPos
import net.minecraft.core.UUIDUtil
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.Optional
import java.util.UUID
import java.util.function.Supplier

/** A score that fades with time: its [value] as it stood on day [day] (see [Memory.faded]). */
class Score(var value: Double = 0.0, var day: Int = 0)

/** One of an acquaintance's three kinds of slight (small, harm, a killing): its [value] as of [day], and the slight that last added to it. */
class Hurt(var value: Double = 0.0, var day: Int = 0, var kind: Slight? = null)

/**
 * A deed a resident saw a player do, as it's kept: its rumour [kind], the [day], its [tone], whether it was big news
 * (remembered 60 days rather than 30) and the [rumour] it began, so a deed seen and heard of counts once.
 */
class Saw(val kind: String, val day: Int, val tone: Int, val big: Boolean, val rumour: Int)

/**
 * A favour counted once that a resident has counted already (M6, see [Memory.favourOnce]): which [favour], and what
 * for ([key]): the works' id for [Favour.FUNDED], the day for the daily ones.
 */
class Thanked(val favour: Favour, val key: Long)

/**
 * What a resident remembers of one [player], as it's kept (see [Memory] for how it's read): the day they first met
 * ([met], -1 if they've only seen them), whether they know the name, the last day they talked or dealt ([seen]) and the
 * last day anything passed between them at all ([last]), on how many [days] they talked or dealt, their [trades] and
 * the Marks [spent], the [favour] felt, the three [slights], the opinion the test command [set], the day they last
 * walked off mid-talk ([off]), the deeds they [saw] and the favours counted once they've [thanked] the player for.
 */
class Acq(val player: UUID) {
    var met = -1
    var named = false
    var seen = -1
    var last = -1
    var days = 0
    var trades = 0
    val spent = Score()
    val favour = Score()
    val slights = Array(3) { Hurt() }
    val set = Score()
    var off = -1
    val saw = ArrayList<Saw>()
    val thanked = ArrayList<Thanked>()
}

/** What one resident ([id]) holds: the players they know, the rumours they've heard (by rumour id) and what befell them, newest last. */
class Mind(val id: Int) {
    val known = HashMap<UUID, Acq>()
    val heard = LinkedHashMap<Int, Heard>()
    val felt = ArrayList<Felt>()

    val empty: Boolean get() = known.isEmpty() && heard.isEmpty() && felt.isEmpty()
}

/**
 * A rumour as its capital keeps it: the [rumour] itself, its heat as of hour [at] (it cools at each dawn after, worked
 * out when read: see [Rumours.heat]); the heat it will have once the crier has cried it ([full], 0 if it isn't held
 * back); whether the crier has cried it as the talk of the town ([cried]); how it began ([by]: [Rumours.TOWN],
 * [Rumours.GUILD], [Rumours.MARKET] or [Rumours.AFAR]); and for one that came from another capital, that capital
 * ([from]) and its id there ([source]).
 */
class Story(var rumour: Rumour, var at: Long, var full: Float, var cried: Boolean, val by: Int, val from: Long = 0L, val source: Int = -1)

/** A rumour on its way in from the capital at [from] (its id [id] there), due to be heard here at hour [at]. */
class Arrival(val from: Long, val id: Int, val at: Long)

/**
 * What the capitals' people remember and what goes round among them, kept with the overworld (`guildmark:town_talk`)
 * and keyed by capital as [io.github.vaspyyy.guildmark.economy.Markets] is: for each capital its rumours, what each of
 * its residents remembers ([Mind]), which rumours each player has heard there and in which version, the rumours on
 * their way in from other capitals, and the last hour its talk was stepped (see [Spread]). Saved only when something
 * changes; the field names are kept short. The townsfolk's own records ([io.github.vaspyyy.guildmark.townsfolk.Resident])
 * aren't touched: a world from before it simply starts with none of this.
 */
class TownTalkData(private val entries: MutableMap<Long, Entry> = HashMap()) : SavedData() {
    /**
     * One capital's talk: its [capital] (packed); the last [hour] stepped; the [next] rumour's id; the last market hour
     * whose news has been taken in ([news]); the day visitors last brought word from afar ([brought]); the day this was
     * [made]; whether the old news it started with is in ([seeded]); and its [rumours], [minds], [players] and
     * [arrivals].
     */
    class Entry(val capital: Long) {
        var hour = Long.MIN_VALUE
        var next = 1
        var news = Long.MIN_VALUE
        var brought = Long.MIN_VALUE
        var made = 0L
        var seeded = false
        val rumours = LinkedHashMap<Int, Story>()
        val minds = HashMap<Int, Mind>()
        val players = HashMap<UUID, HashMap<Int, Int>>()
        val arrivals = ArrayList<Arrival>()

        val pos: BlockPos get() = BlockPos.of(capital)

        /** Resident [id]'s mind, made if [make] and they have none yet. */
        fun mind(id: Int, make: Boolean): Mind? = minds[id] ?: if (make) Mind(id).also { minds[id] = it } else null
    }

    operator fun get(capital: BlockPos): Entry? = entries[capital.asLong()]

    operator fun get(capital: Long): Entry? = entries[capital]

    fun all(): Collection<Entry> = entries.values

    fun changed() = setDirty()

    /** [capital]'s talk, made now (from hour [hour] and day [day] on) if it has none yet. */
    fun open(capital: BlockPos, hour: Long, day: Long): Entry {
        entries[capital.asLong()]?.let { return it }
        val entry = Entry(capital.asLong())
        entry.hour = hour
        entry.news = hour
        entry.made = day
        entries[entry.capital] = entry
        setDirty()
        return entry
    }

    companion object {
        private fun <T> some(list: List<T>): Optional<List<T>> = if (list.isEmpty()) Optional.empty() else Optional.of(list)

        private val SCORE: Codec<Score> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.DOUBLE.fieldOf("v").forGetter(Score::value),
                Codec.INT.optionalFieldOf("d", 0).forGetter(Score::day),
            ).apply(i) { v, d -> Score(v, d) }
        }

        private fun score(s: Score): Optional<Score> = if (s.value == 0.0) Optional.empty() else Optional.of(s)

        private val HURT: Codec<Hurt> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.DOUBLE.optionalFieldOf("v", 0.0).forGetter(Hurt::value),
                Codec.INT.optionalFieldOf("d", 0).forGetter(Hurt::day),
                Codec.STRING.optionalFieldOf("k", "").forGetter { it.kind?.name ?: "" },
            ).apply(i) { v, d, k -> Hurt(v, d, Slight.entries.firstOrNull { it.name == k }) }
        }

        private val SAW: Codec<Saw> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("k").forGetter(Saw::kind),
                Codec.INT.fieldOf("d").forGetter(Saw::day),
                Codec.INT.optionalFieldOf("t", 0).forGetter(Saw::tone),
                Codec.BOOL.optionalFieldOf("b", false).forGetter(Saw::big),
                Codec.INT.optionalFieldOf("r", -1).forGetter(Saw::rumour),
            ).apply(i) { k, d, t, b, r -> Saw(k, d, t, b, r) }
        }

        /** A favour counted once, as it's kept: the favour's name (one this build doesn't know is let go) and what it was for. */
        private class Thank(val favour: String, val key: Long)

        private val THANKED: Codec<Thank> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("f").forGetter(Thank::favour),
                Codec.LONG.fieldOf("k").forGetter(Thank::key),
            ).apply(i) { f, k -> Thank(f, k) }
        }

        private val ACQ: Codec<Acq> = RecordCodecBuilder.create { i ->
            i.group(
                UUIDUtil.CODEC.fieldOf("p").forGetter(Acq::player),
                Codec.INT.optionalFieldOf("m", -1).forGetter(Acq::met),
                Codec.BOOL.optionalFieldOf("n", false).forGetter(Acq::named),
                Codec.INT.optionalFieldOf("s", -1).forGetter(Acq::seen),
                Codec.INT.optionalFieldOf("l", -1).forGetter(Acq::last),
                Codec.INT.optionalFieldOf("d", 0).forGetter(Acq::days),
                Codec.INT.optionalFieldOf("t", 0).forGetter(Acq::trades),
                SCORE.optionalFieldOf("sp").forGetter { score(it.spent) },
                SCORE.optionalFieldOf("f").forGetter { score(it.favour) },
                HURT.listOf().optionalFieldOf("x").forGetter { a -> if (a.slights.all { it.value == 0.0 }) Optional.empty() else Optional.of(a.slights.toList()) },
                SCORE.optionalFieldOf("o").forGetter { score(it.set) },
                Codec.INT.optionalFieldOf("w", -1).forGetter(Acq::off),
                SAW.listOf().optionalFieldOf("e").forGetter { some(it.saw) },
                THANKED.listOf().optionalFieldOf("u").forGetter { a -> some(a.thanked.map { Thank(it.favour.name, it.key) }) },
            ).apply(i) { p, m, n, s, l, d, t, sp, f, x, o, w, e, u ->
                Acq(p).also { a ->
                    a.met = m
                    a.named = n
                    a.seen = s
                    a.last = l
                    a.days = d
                    a.trades = t
                    sp.ifPresent { a.spent.value = it.value; a.spent.day = it.day }
                    f.ifPresent { a.favour.value = it.value; a.favour.day = it.day }
                    x.ifPresent { list -> list.take(3).forEachIndexed { k, h -> a.slights[k] = h } }
                    o.ifPresent { a.set.value = it.value; a.set.day = it.day }
                    a.off = w
                    e.ifPresent { a.saw.addAll(it) }
                    u.ifPresent { list -> for (th in list) Favour.entries.firstOrNull { it.name == th.favour }?.let { a.thanked.add(Thanked(it, th.key)) } }
                }
            }
        }

        private val HEARD: Codec<Heard> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("r").forGetter(Heard::rumour),
                Codec.LONG.fieldOf("h").forGetter(Heard::hour),
                Codec.INT.optionalFieldOf("f", 0).forGetter(Heard::from),
                Codec.STRING.optionalFieldOf("v", Venue.STREET.name).forGetter { it.venue.name },
                Codec.INT.optionalFieldOf("w", 0).forGetter(Heard::warp),
                Codec.INT.optionalFieldOf("t", 0).forGetter(Heard::told),
            ).apply(i) { r, h, f, v, w, t -> Heard(r, h, f, Venue.entries.firstOrNull { it.name == v } ?: Venue.STREET, w, t) }
        }

        private val FELT: Codec<Felt> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("e").forGetter { it.experience.name },
                Codec.INT.fieldOf("d").forGetter(Felt::day),
                Codec.INT.optionalFieldOf("a", -1).forGetter(Felt::about),
            ).apply(i) { e, d, a -> Felt(Experience.entries.firstOrNull { it.name == e } ?: Experience.FRIGHTENED, d, a) }
        }

        private val MIND: Codec<Mind> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("i").forGetter(Mind::id),
                ACQ.listOf().optionalFieldOf("q").forGetter { some(it.known.values.toList()) },
                HEARD.listOf().optionalFieldOf("k").forGetter { some(it.heard.values.toList()) },
                FELT.listOf().optionalFieldOf("e").forGetter { some(it.felt) },
            ).apply(i) { id, q, k, e ->
                Mind(id).also { m ->
                    q.ifPresent { list -> for (a in list) m.known[a.player] = a }
                    k.ifPresent { list -> for (h in list) m.heard[h.rumour] = h }
                    e.ifPresent { m.felt.addAll(it) }
                }
            }
        }

        /** Where a rumour that came from another capital came from. */
        private class Source(val from: Long, val id: Int)

        private val SOURCE: Codec<Source> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("f").forGetter(Source::from),
                Codec.INT.fieldOf("i").forGetter(Source::id),
            ).apply(i) { f, id -> Source(f, id) }
        }

        private val STORY: Codec<Story> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("i").forGetter { it.rumour.id },
                Codec.STRING.fieldOf("k").forGetter { it.rumour.kind },
                UUIDUtil.CODEC.optionalFieldOf("a").forGetter { Optional.ofNullable(it.rumour.about) },
                Codec.INT.optionalFieldOf("r", -1).forGetter { it.rumour.resident },
                Codec.STRING.listOf().optionalFieldOf("g").forGetter { some(it.rumour.args) },
                Codec.LONG.fieldOf("b").forGetter { it.rumour.born },
                Codec.FLOAT.fieldOf("h").forGetter { it.rumour.heat },
                Codec.STRING.optionalFieldOf("f", Fade.SMALL.name).forGetter { it.rumour.fade.name },
                Codec.STRING.optionalFieldOf("s", Scope.TOWN.name).forGetter { it.rumour.scope.name },
                Codec.INT.optionalFieldOf("t", 0).forGetter { it.rumour.tone },
                Codec.LONG.optionalFieldOf("o").forGetter { s -> Optional.ofNullable(s.rumour.origin?.asLong()) },
                Codec.LONG.fieldOf("at").forGetter(Story::at),
                Codec.FLOAT.optionalFieldOf("x", 0f).forGetter(Story::full),
                Codec.BOOL.optionalFieldOf("c", false).forGetter(Story::cried),
                Codec.INT.optionalFieldOf("y", Rumours.TOWN).forGetter(Story::by),
                SOURCE.optionalFieldOf("z").forGetter { s -> if (s.source < 0) Optional.empty() else Optional.of(Source(s.from, s.source)) },
            ).apply(i) { id, kind, about, resident, args, born, heat, fade, scope, tone, origin, at, full, cried, by, source ->
                val rumour = Rumour(
                    id, kind, about.orElse(null), resident, args.orElse(listOf()), born, heat,
                    Fade.entries.firstOrNull { it.name == fade } ?: Fade.SMALL, Scope.entries.firstOrNull { it.name == scope } ?: Scope.TOWN,
                    tone, origin.map { BlockPos.of(it) }.orElse(null),
                )
                Story(rumour, at, full, cried, by, source.map { it.from }.orElse(0L), source.map { it.id }.orElse(-1))
            }
        }

        /** A player's rumours heard at one capital, as rumour id and version in turn. */
        private class Ear(val player: UUID, val heard: List<Int>)

        private val EAR: Codec<Ear> = RecordCodecBuilder.create { i ->
            i.group(
                UUIDUtil.CODEC.fieldOf("p").forGetter(Ear::player),
                Codec.INT.listOf().optionalFieldOf("h", listOf()).forGetter(Ear::heard),
            ).apply(i) { p, h -> Ear(p, h) }
        }

        private val ARRIVAL: Codec<Arrival> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("f").forGetter(Arrival::from),
                Codec.INT.fieldOf("i").forGetter(Arrival::id),
                Codec.LONG.fieldOf("a").forGetter(Arrival::at),
            ).apply(i) { f, id, a -> Arrival(f, id, a) }
        }

        private val ENTRY: Codec<Entry> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("c").forGetter(Entry::capital),
                Codec.LONG.fieldOf("h").forGetter(Entry::hour),
                Codec.INT.optionalFieldOf("n", 1).forGetter(Entry::next),
                Codec.LONG.optionalFieldOf("w", Long.MIN_VALUE).forGetter(Entry::news),
                Codec.LONG.optionalFieldOf("v", Long.MIN_VALUE).forGetter(Entry::brought),
                Codec.LONG.optionalFieldOf("m", 0L).forGetter(Entry::made),
                Codec.BOOL.optionalFieldOf("z", false).forGetter(Entry::seeded),
                STORY.listOf().optionalFieldOf("r").forGetter { some(it.rumours.values.toList()) },
                MIND.listOf().optionalFieldOf("p").forGetter { e -> some(e.minds.values.filter { !it.empty }) },
                EAR.listOf().optionalFieldOf("q").forGetter { e ->
                    some(e.players.entries.filter { it.value.isNotEmpty() }.map { (p, h) -> Ear(p, h.entries.flatMap { listOf(it.key, it.value) }) })
                },
                ARRIVAL.listOf().optionalFieldOf("a").forGetter { some(it.arrivals) },
            ).apply(i) { c, h, n, w, v, m, z, r, p, q, a ->
                Entry(c).also { e ->
                    e.hour = h
                    e.next = n
                    e.news = w
                    e.brought = v
                    e.made = m
                    e.seeded = z
                    r.ifPresent { list -> for (s in list) e.rumours[s.rumour.id] = s }
                    p.ifPresent { list -> for (mind in list) e.minds[mind.id] = mind }
                    q.ifPresent { list ->
                        for (ear in list) {
                            val heard = HashMap<Int, Int>()
                            for (k in 0 until ear.heard.size - 1 step 2) heard[ear.heard[k]] = ear.heard[k + 1]
                            e.players[ear.player] = heard
                        }
                    }
                    a.ifPresent { e.arrivals.addAll(it) }
                }
            }
        }

        private val CODEC: Codec<TownTalkData> = RecordCodecBuilder.create { i ->
            i.group(
                ENTRY.listOf().optionalFieldOf("e", listOf()).forGetter { it.entries.values.toList() },
            ).apply(i) { list -> TownTalkData(list.associateBy { it.capital }.toMutableMap()) }
        }

        private val TYPE = SavedDataType(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "town_talk"), Supplier { TownTalkData() }, CODEC)

        fun get(level: ServerLevel): TownTalkData = level.server.overworld().dataStorage.computeIfAbsent(TYPE)
    }
}
