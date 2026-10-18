package io.github.vaspyyy.guildmark.townsfolk.talk.sources

import io.github.vaspyyy.guildmark.economy.Good
import io.github.vaspyyy.guildmark.economy.Trading
import io.github.vaspyyy.guildmark.season.Season
import io.github.vaspyyy.guildmark.season.SeasonSnow
import io.github.vaspyyy.guildmark.season.Seasons
import io.github.vaspyyy.guildmark.townsfolk.Speech
import io.github.vaspyyy.guildmark.townsfolk.talk.Ask
import io.github.vaspyyy.guildmark.townsfolk.talk.Kind
import io.github.vaspyyy.guildmark.townsfolk.talk.Occasion
import io.github.vaspyyy.guildmark.townsfolk.talk.Topic
import io.github.vaspyyy.guildmark.townsfolk.talk.TopicSource
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome

/**
 * The weather where the speaker stands (section 1): rain, a storm, snow (winter's in the temperate lands as the client
 * shows it, or the cold lands' own), the sun out after rain, a cold or a hot day, a clear night and its moon, and
 * plain fair weather. Rain and snow say how long they've been coming down, from what the overworld has shown since the
 * server came up. Children have their own line for rain, snow and storms.
 */
object WeatherTopics : TopicSource {
    override val id = "weather"

    private val OCCASIONS = Say.TALK + Occasion.BARK

    /** How long after the rain stops the sun coming out is still news (ticks). */
    private const val CLEARING = 1200L

    /** How long it has to rain for it to be worth the most said about it (ticks). */
    private const val SOAKED = 6000L

    // What the sky has done since the server came up: whether it's raining and thundering, and since when (game time,
    // and the overworld clock then), or -1 if it already was when the server came up; and when the last rain stopped
    // and how long it had lasted (-1 if not known)
    private var watched = false
    private var raining = false
    private var thundering = false
    private var rainFrom = -1L
    private var rainClock = -1L
    private var thunderFrom = -1L
    private var thunderClock = -1L
    private var rainTo = -1L
    private var lasted = -1L

    /** Notes the overworld's weather, once a second. */
    fun watch(level: ServerLevel) {
        val rain = level.isRaining
        val thunder = level.isThundering
        val now = level.gameTime
        if (!watched) {
            watched = true
            raining = rain
            thundering = thunder
            return
        }
        if (rain != raining) {
            raining = rain
            if (rain) {
                rainFrom = now
                rainClock = level.overworldClockTime
            } else {
                rainTo = now
                lasted = if (rainFrom >= 0) now - rainFrom else -1L
                rainFrom = -1L
                rainClock = -1L
            }
        }
        if (thunder != thundering) {
            thundering = thunder
            thunderFrom = if (thunder) now else -1L
            thunderClock = if (thunder) level.overworldClockTime else -1L
        }
    }

    fun forget() {
        watched = false
        raining = false
        thundering = false
        rainFrom = -1L
        rainClock = -1L
        thunderFrom = -1L
        thunderClock = -1L
        rainTo = -1L
        lasted = -1L
    }

    override fun offer(ask: Ask, out: MutableList<Topic>) {
        if (ask.occasion !in OCCASIONS) return
        val level = ask.level
        if (level.dimension() != Level.OVERWORLD) return
        val p = ask.speaker
        val pos = p.blockPosition()
        val biome = level.getBiome(pos).value()
        val fall = biome.getPrecipitationAt(pos, level.seaLevel)
        val wet = level.isRaining && fall != Biome.Precipitation.NONE
        val date = if (Seasons.enabled()) Seasons.today(level) else null
        val temperate = Seasons.temperate(biome) >= 0.5f
        // Winter's rain falls as snow in the temperate lands, as the client shows it
        val snowing = wet && (fall == Biome.Precipitation.SNOW || date != null && Seasons.snowEnabled() && date.season == Season.WINTER && temperate)
        val child = Say.child(p)
        val hour = Say.hour(level)
        val day = Say.day(level)
        val light = hour >= 6.0 && hour < 19.0
        val now = level.gameTime
        if (wet) {
            if (level.isThundering) {
                val detail = since(level, thunderFrom, thunderClock)?.let { Speech.of("talk.detail.sky.storm", it) }
                add(ask, out, if (child) "child.weather" else "sky.storm", "sky.storm.$thunderFrom", 0.6, -1, detail)
            } else if (snowing) {
                val lying = date != null && SeasonSnow.cover(date) >= 0.45f
                val detail = if (lying) "talk.detail.sky.snow.lying" else since(level, rainFrom, rainClock)?.let { Speech.of("talk.detail.sky.snow", it) }
                add(ask, out, if (child) "child.weather" else "sky.snow", "sky.snow.$rainFrom", 0.5, 0, detail)
            } else {
                val soaked = if (rainFrom >= 0) ((now - rainFrom).toDouble() / SOAKED).coerceIn(0.0, 1.0) else 1.0
                val detail = since(level, rainFrom, rainClock)?.let { Speech.of("talk.detail.sky.rain", it) }
                add(ask, out, if (child) "child.weather" else "sky.rain", "sky.rain.$rainFrom", 0.4 + 0.2 * soaked, -1, detail)
            }
            return
        }
        if (child) return
        // The sun out after rain
        if (rainTo >= 0 && now - rainTo in 0..CLEARING && light) {
            add(ask, out, "sky.clearing", "sky.clearing.$rainTo", 0.5, 1, spell(lasted)?.let { Speech.of("talk.detail.sky.clearing", it) })
        }
        // A clear night, under the open sky
        if ((hour >= 21.0 || hour < 4.0) && level.canSeeSky(pos.above())) {
            val phase = Math.floorMod(Math.floorDiv(level.overworldClockTime, 24000L), 8L).toInt()
            val moon = when (phase) {
                0 -> "full"
                4 -> "new"
                in 1..3 -> "waning"
                else -> "waxing"
            }
            add(ask, out, "sky.night", "sky.night.$day", if (phase == 0 || phase == 4) 0.3 else 0.2, 1, "talk.detail.sky.moon.$moon")
        }
        val winter = date != null && date.season == Season.WINTER && temperate
        val summer = date != null && date.season == Season.SUMMER && temperate
        // Cold: the temperate winter, or the cold lands any time
        if (winter || biome.baseTemperature < 0.15f) {
            add(ask, out, "sky.cold", "sky.cold.$day", 0.4, -1, coal(ask) ?: if (winter && date != null) Speech.of("talk.detail.sky.winter", date.day) else null)
        }
        // Hot: a summer's afternoon, or the hot lands by day
        if (summer && hour >= 11.0 && hour < 17.0 || biome.baseTemperature > 1.0f && light) {
            add(ask, out, "sky.hot", "sky.hot.$day", 0.35, 0, "talk.detail.sky.hot")
        }
        if (light) add(ask, out, "sky.fine", "sky.fine.$day", 0.15, 1, null)
    }

    private fun add(ask: Ask, out: MutableList<Topic>, family: String, id: String, weight: Double, tone: Int, detail: String?) {
        val say = Say.line(ask, family, detail) ?: return
        out.add(Topic(if (family == "child.weather") "child.$id" else id, Kind.WEATHER, weight, say, tone = tone))
    }

    /** What a lot of coal costs at the market today, the cold's own price ("Coal: 4 for 1 Mark."). */
    private fun coal(ask: Ask): String? {
        val entry = Say.market(ask) ?: return null
        val lot = Trading.quote(entry, Good.COAL).buy ?: return null
        val name = Good.COAL.key + ".many"
        return if (lot.marks == 1) Speech.of("talk.detail.market.price.one", name, lot.count) else Speech.of("talk.detail.market.price", name, lot.count, lot.marks)
    }

    /**
     * Since when it's been coming down, from game time [from] and the clock then ([clock]), as a word for a detail
     * ("since this morning", "since last night"); null if it was already when the server came up.
     */
    private fun since(level: ServerLevel, from: Long, clock: Long): String? {
        if (from < 0 || clock < 0) return null
        val elapsed = level.gameTime - from
        if (elapsed >= 48000L) return Say.WORD + "since.days"
        // Days as the townsfolk's go, from two in the morning
        val started = Math.floorDiv(clock + 4000L, 24000L)
        val today = Math.floorDiv(level.overworldClockTime + 4000L, 24000L)
        val at = (Math.floorMod(clock, 24000L) / 1000.0 + 6.0) % 24.0
        if (started != today) return Say.WORD + if (at >= 18.0 && elapsed < 14000L) "since.night" else "since.yesterday"
        return Say.WORD + "since." + when {
            at >= 2.0 && at < 5.0 -> "early"
            at >= 5.0 && at < 7.0 -> "dawn"
            at >= 7.0 && at < 11.0 -> "morning"
            at >= 11.0 && at < 13.0 -> "noon"
            at >= 13.0 && at < 17.0 -> "afternoon"
            at >= 17.0 && at < 21.0 -> "evening"
            else -> "nightfall"
        }
    }

    /** How long a rain of [ticks] lasted, as a word ("a few hours"); null if not known. */
    private fun spell(ticks: Long): String? {
        if (ticks < 0) return null
        return Say.WORD + "spell." + when {
            ticks < 1500L -> "hour"
            ticks < 5000L -> "hours"
            ticks < 12000L -> "half"
            ticks < 30000L -> "day"
            else -> "days"
        }
    }
}
