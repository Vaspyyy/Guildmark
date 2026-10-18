package io.github.vaspyyy.guildmark.townsfolk

import io.github.vaspyyy.guildmark.city.Capitals
import net.minecraft.server.level.ServerLevel
import kotlin.math.min

/**
 * The passers-by of a capital are visitors, in for the day with something to do there: a stall's wares to buy, a
 * prayer at the temple, a parcel for someone's door, a turn about the sights, a drink at the tavern of an evening.
 * When that's done they make for the edge of town, and slip away out of sight (see [Crowds]). The watch, the
 * minstrels and the children keep their old ways.
 */
object Visitors {
    fun next(p: Townsperson): Task? {
        if (!Days.enabled() || p.role == TownRole.GUARD || p.role == TownRole.BARD || p.role == TownRole.CHILD) return null
        val level = p.level() as? ServerLevel ?: return null
        val life = p.life ?: run {
            if (p.tickCount < p.lifeLooked) return null
            p.lifeLooked = p.tickCount + 200
            val capital = Capitals.covering(level, p.blockPosition()) ?: return null
            val places = CityPlaces.of(level, capital) ?: return null
            Life(Resident.visitor(capital.center, p.role), places, null).also {
                it.script = purposes(p, it)
                p.life = it
                io.github.vaspyyy.guildmark.townsfolk.talk.Witness.visitor(level, capital.center, p)
            }
        }
        while (life.step < life.script.size) {
            val part = life.script[life.step++]
            part.make()?.let { return it }
        }
        // Done what they came for: out of town (they're gone once nobody's looking)
        p.leaveAt = min(p.leaveAt, level.gameTime)
        return away(p, level)
    }

    /** What brought [p] into town. */
    private fun purposes(p: Townsperson, life: Life): List<Acts.Part> {
        val parts = ArrayList<Acts.Part>(3)
        val evening = Schedule.dark(p) || Math.floorMod(p.level().overworldClockTime, 24000L) >= 11000
        when (p.role) {
            TownRole.PRIEST -> parts.add(Acts.Part { Acts.worship(p, life) })
            TownRole.NOBLE -> {
                parts.add(Acts.Part { Acts.stroll(p, life) })
                parts.add(Acts.Part { Acts.shop(p, life) })
            }
            else -> if (evening) parts.add(Acts.Part { Acts.tavern(p, life) }) else {
                parts.add(Acts.Part { Acts.shop(p, life) })
                when (p.random.nextInt(4)) {
                    0 -> parts.add(Acts.Part { Acts.worship(p, life) })
                    1 -> parts.add(Acts.Part { Acts.errand(p, life) })
                    2 -> parts.add(Acts.Part { Acts.stroll(p, life) })
                }
            }
        }
        return parts
    }

    /** Off down the street, away from whoever's about. */
    private fun away(p: Townsperson, level: ServerLevel): Task {
        val from = level.getNearestPlayer(p, 64.0)?.blockPosition() ?: p.blockPosition()
        val street = Places.street(level, p.blockPosition(), 40, p.random, minR = 24, tries = 8)
            ?.takeIf { it.distSqr(from) > p.blockPosition().distSqr(from) }
            ?: return Idle(p, 100)
        val overlay = when {
            !p.carried.isEmpty -> Activity.CARRY_BASKET
            Schedule.dark(p) -> Activity.LANTERN
            else -> Activity.NONE
        }
        return Walk(p, street, Jobs.WALK, near = 2.0, overlay = overlay).andThen(Idle(p, 200))
    }
}
