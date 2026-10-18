package io.github.vaspyyy.guildmark.townsfolk

import io.github.vaspyyy.guildmark.economy.Caravans
import io.github.vaspyyy.guildmark.faction.Faction
import io.github.vaspyyy.guildmark.faction.Factions
import io.github.vaspyyy.guildmark.townsfolk.talk.Exchange
import io.github.vaspyyy.guildmark.townsfolk.talk.OldLines
import io.github.vaspyyy.guildmark.townsfolk.talk.Street
import io.github.vaspyyy.guildmark.townsfolk.talk.Talk
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * What townsfolk say: line keys under say.guildmark. (the text, and the voice clip of the same name per timbre,
 * townsfolk.voice.<timbre>.<line>). See art/audio/make_townsfolk_voices.py.
 *
 * With townsfolk talk on, only what's true now is said (living-world/design-voices.md section 5, "The old lines";
 * [OldLines] says when each is), and a word to a player comes from the topics first ([Street.word]).
 */
object Lines {
    private fun pick(p: Townsperson, prefix: String, n: Int): String = prefix + p.random.nextInt(n)

    /** One of [prefix]_0 to _[n - 1]: any, talk off; else one true for [p] now (to [player]), or null if none is. */
    private fun truly(p: Townsperson, prefix: String, n: Int, player: Player? = null): String? {
        if (!Talk.enabled()) return pick(p, prefix, n)
        val pool = OldLines.pool(p, (0 until n).map { "$prefix$it" }, player)
        return if (pool.isEmpty()) null else pool[p.random.nextInt(pool.size)]
    }

    /** The greetings that are true at some hour or other: by day, of a morning, of a fine day, of an evening, at night. */
    private val GREETINGS = listOf("greet_0", "greet_1", "greet_2", "greet_7", "night_0", "night_1", "night_2")

    fun greet(p: Townsperson): String = when {
        p.role == TownRole.CHILD -> "child_" + listOf(2, 4, 5)[p.random.nextInt(3)]
        Talk.enabled() -> OldLines.pool(p, GREETINGS).let { if (it.isEmpty()) "night_1" else it[p.random.nextInt(it.size)] }
        Schedule.dark(p) -> pick(p, "night_", 3)
        else -> pick(p, "greet_", 8)
    }

    /** Something about their trade, if they have one worth talking about (and, talk on, something about it is true now). */
    fun trade(p: Townsperson, player: Player? = null): String? = when (p.role) {
        TownRole.SMITH -> truly(p, "smith_", 3, player)
        TownRole.BAKER -> truly(p, "baker_", 3, player)
        TownRole.MERCHANT -> truly(p, "merchant_", 6, player)
        TownRole.GUARD -> truly(p, "guard_", 5, player)
        TownRole.NOBLE -> truly(p, "noble_", 4, player)
        TownRole.INNKEEPER -> truly(p, "inn_", 4, player)
        TownRole.BARD -> "bard_0"
        TownRole.PRIEST -> truly(p, "priest_", 3, player)
        TownRole.FARMER -> truly(p, "farmer_", 3, player)
        TownRole.FISHER -> truly(p, "fisher_", 3, player)
        TownRole.CHILD -> truly(p, "child_", 6, player)
        TownRole.MAGE -> truly(p, "mage_", 6, player)
        TownRole.KNIGHT -> truly(p, "knight_", 6, player)
        TownRole.THIEF -> truly(p, "thief_", 6, player)
        TownRole.CITIZEN -> null
    }

    /** What they say to a player who speaks to them: talk on, what's on their mind for them, or a greeting that knows them. */
    fun talk(p: Townsperson, player: Player? = null): String {
        if (Talk.enabled()) Street.word(p, player)?.let { return it }
        // Talk off, one time in three the road's news, while it has any (talk on, it comes through the topics already)
        if (!Talk.enabled() && p.role != TownRole.CHILD && p.random.nextInt(3) == 0) Caravans.talk(p, player as? ServerPlayer)?.let { return it }
        player?.let { kin(p, it) }?.let { return it }
        if (p.role == TownRole.CITIZEN && p.random.nextInt(3) == 0) return gossip(p)
        return trade(p, player)?.takeIf { p.random.nextInt(3) != 0 } ?: greet(p)
    }

    /**
     * A faction member's word for [player]: warm to one of their own, cold to someone their faction threw out
     * or who sides with its rival (the Starwatch is cold to nobody). Null for everyone else.
     */
    fun kin(p: Townsperson, player: Player): String? {
        val faction = Faction.of(p.role) ?: return null
        val state = Factions.state(player)
        if (state.member(faction)) return pick(p, "${p.role.id}_kin_", 2)
        val cold = state.isShut(faction) || faction.rival?.let { state.member(it) } == true
        return if (cold && faction != Faction.STARWATCH) "${p.role.id}_cold_0" else null
    }

    /** Small talk; talk on, only what's true now ("Have you seen my cat?" always is), and one time in three the road's news while it has any. */
    fun gossip(p: Townsperson): String {
        if (p.role == TownRole.CHILD) return pick(p, "child_", 6)
        if (p.random.nextInt(3) == 0) Caravans.talk(p)?.let { return it }
        return truly(p, "gossip_", 12) ?: "gossip_5"
    }

    fun reply(p: Townsperson): String = if (p.role == TownRole.CHILD) "child_4" else pick(p, "reply_", 6)

    fun flee(p: Townsperson): String = pick(p, "flee_", 3)
}

/** How townsfolk notice each other and the players: greetings, chats, crowds round a bard, calling the guard. */
object Social {
    /** When each player was last greeted, so walking down a busy street isn't a din of hellos. */
    private val greeted = HashMap<UUID, Long>()

    /**
     * Now and then someone near [player] and in front of them says hello (or calls their wares, or waves). With street
     * talk on (townsfolkTalk and townsfolkBarks), they notice the player as they know them, from the topics ([Street]).
     */
    fun greet(level: ServerLevel, player: ServerPlayer) {
        if (player.isSpectator) return
        if (Street.on()) {
            Street.greet(level, player)
            return
        }
        val last = greeted[player.uuid] ?: 0L
        if (level.gameTime - last < 140) return
        val look = player.getViewVector(1f)
        val folk = level.getEntitiesOfClass(Townsperson::class.java, player.boundingBox.inflate(6.0, 2.0, 6.0)) {
            it.isAlive && !it.isSleeping && it.speech.isEmpty() && level.gameTime >= it.nextSpeech
        }.filter {
            val to = it.position().subtract(player.position()).normalize()
            to.x * look.x + to.z * look.z > 0.5 && player.hasLineOfSight(it)
        }.minByOrNull { it.distanceToSqr(player) } ?: return
        greeted[player.uuid] = level.gameTime
        if (greeted.size > 256) greeted.clear()
        if (level.random.nextInt(3) == 0) return
        val line = Lines.kin(folk, player)?.takeIf { level.random.nextBoolean() }
            ?: if (folk.routine.anchored || level.random.nextInt(3) == 0) Lines.trade(folk) ?: Lines.greet(folk) else Lines.greet(folk)
        if (!folk.say(line)) return
        val task = folk.routine.task
        if (task == null || task.leisure && !folk.routine.anchored) {
            folk.attend(player, 40)
            if (folk.activity.overlay) folk.act(if (folk.role == TownRole.CHILD && level.random.nextBoolean()) Activity.JUMP else Activity.WAVE)
        }
    }

    /** [victim] was hurt by [attacker]: the guards nearby go for them. */
    fun alarm(level: ServerLevel, victim: Townsperson, attacker: LivingEntity) {
        if (attacker is Townsperson || attacker is Player && (attacker.isCreative || attacker.isSpectator)) return
        for (guard in level.getEntitiesOfClass(Townsperson::class.java, victim.boundingBox.inflate(24.0, 8.0, 24.0)) { it.role == TownRole.GUARD && it.isAlive }) {
            if (guard.target == null && guard !== victim) guard.target = attacker
        }
    }

    /**
     * Someone close by to chat with, who's free for it; starts the chat for both. With street talk on, its turns carry
     * what's on their minds for each other ([Exchange]).
     */
    fun chat(p: Townsperson): Task? {
        if (!p.role.adult) return null
        val level = p.level() as ServerLevel
        val partner = level.getEntitiesOfClass(Townsperson::class.java, p.boundingBox.inflate(10.0, 3.0, 10.0)) {
            it !== p && it.isAlive && it.role.adult && !it.isSleeping && it.attending == null && it.director == null &&
                it.routine.task.let { task -> task == null || task.leisure }
        }.minByOrNull { p.distanceToSqr(it) } ?: return null
        val turns = 2 + p.random.nextInt(4)
        val seated = p.seat != null && partner.seat != null
        val talk = if (Street.on()) Exchange(p, partner, turns) else null
        partner.routine.replace(Chat(partner, p, talking = false, length = turns, seated = seated, talk = talk))
        return Chat(p, partner, talking = true, length = turns, seated = seated, talk = talk)
    }

    /** A bard playing within earshot, or a mage of the Starwatch conjuring lights in the square, if there is one. */
    fun performingBard(p: Townsperson): Townsperson? =
        p.level().getEntitiesOfClass(Townsperson::class.java, p.boundingBox.inflate(16.0, 4.0, 16.0)) {
            it !== p && (it.role == TownRole.BARD && it.activity == Activity.LUTE || it.role == TownRole.MAGE && it.activity == Activity.CONJURE)
        }.firstOrNull()

    /** The tavern [p] goes to of an evening: theirs if set, else where the nearest innkeeper keeps the bar. */
    fun tavern(p: Townsperson): BlockPos? {
        p.evening?.let { return it }
        if (p.tickCount % 20 != 0 && p.routine.phase != Phase.EVENING) return null
        val keeper = p.level().getEntitiesOfClass(Townsperson::class.java, p.boundingBox.inflate(64.0, 16.0, 64.0)) {
            it.role == TownRole.INNKEEPER && !it.crowd && it.isAlive
        }.minByOrNull { p.distanceToSqr(it) } ?: return null
        val bar = keeper.post ?: keeper.home ?: keeper.blockPosition()
        if (!p.crowd) p.evening = bar
        return bar
    }

    /** A player near a child, someone worth tagging along after. */
    fun adventurer(p: Townsperson): Player? {
        val player = p.level().getNearestPlayer(p, 10.0) ?: return null
        if (player.isSpectator) return null
        // Only one child at a time follows anyone about
        val taken = p.level().getEntitiesOfClass(Townsperson::class.java, player.boundingBox.inflate(12.0)) { (it.routine.task as? Follow) != null }
        return if (taken.isEmpty()) player else null
    }

    /** Another child near enough to play tag with. */
    fun playmate(p: Townsperson): Townsperson? =
        p.level().getEntitiesOfClass(Townsperson::class.java, p.boundingBox.inflate(14.0, 3.0, 14.0)) {
            it !== p && it.role == TownRole.CHILD && it.isAlive && !it.isSleeping && it.routine.task.let { task -> task == null || task.leisure }
        }.randomOrNull()

    /** The nearest other townsperson within [r] blocks. */
    fun nearestFolk(p: Townsperson, r: Double): Townsperson? =
        p.level().getEntitiesOfClass(Townsperson::class.java, p.boundingBox.inflate(r, 2.0, r)) { it !== p && it.isAlive }
            .minByOrNull { p.distanceToSqr(it) }

    fun center(pos: BlockPos): Vec3 = Vec3.atBottomCenterOf(pos)
}
