package io.github.vaspyyy.guildmark.townsfolk

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
 */
object Lines {
    private fun pick(p: Townsperson, prefix: String, n: Int): String = prefix + p.random.nextInt(n)

    fun greet(p: Townsperson): String = when {
        p.role == TownRole.CHILD -> "child_" + listOf(2, 4, 5)[p.random.nextInt(3)]
        Schedule.dark(p) -> pick(p, "night_", 3)
        else -> pick(p, "greet_", 8)
    }

    /** Something about their trade, if they have one worth talking about. */
    fun trade(p: Townsperson): String? = when (p.role) {
        TownRole.SMITH -> pick(p, "smith_", 3)
        TownRole.BAKER -> pick(p, "baker_", 3)
        TownRole.MERCHANT -> pick(p, "merchant_", 6)
        TownRole.GUARD -> pick(p, "guard_", 5)
        TownRole.NOBLE -> pick(p, "noble_", 4)
        TownRole.INNKEEPER -> pick(p, "inn_", 4)
        TownRole.BARD -> "bard_0"
        TownRole.PRIEST -> pick(p, "priest_", 3)
        TownRole.FARMER -> pick(p, "farmer_", 3)
        TownRole.FISHER -> pick(p, "fisher_", 3)
        TownRole.CHILD -> pick(p, "child_", 6)
        TownRole.CITIZEN -> null
    }

    /** What they say to a player who speaks to them. */
    fun talk(p: Townsperson): String {
        if (p.role == TownRole.CITIZEN && p.random.nextInt(3) == 0) return gossip(p)
        return trade(p)?.takeIf { p.random.nextInt(3) != 0 } ?: greet(p)
    }

    fun gossip(p: Townsperson): String = if (p.role == TownRole.CHILD) pick(p, "child_", 6) else pick(p, "gossip_", 12)

    fun reply(p: Townsperson): String = if (p.role == TownRole.CHILD) "child_4" else pick(p, "reply_", 6)

    fun flee(p: Townsperson): String = pick(p, "flee_", 3)
}

/** How townsfolk notice each other and the players: greetings, chats, crowds round a bard, calling the guard. */
object Social {
    /** When each player was last greeted, so walking down a busy street isn't a din of hellos. */
    private val greeted = HashMap<UUID, Long>()

    /** Now and then someone near [player] and in front of them says hello (or calls their wares, or waves). */
    fun greet(level: ServerLevel, player: ServerPlayer) {
        if (player.isSpectator) return
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
        val line = if (folk.routine.anchored || level.random.nextInt(3) == 0) Lines.trade(folk) ?: Lines.greet(folk) else Lines.greet(folk)
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

    /** Someone close by to chat with, who's free for it; starts the chat for both. */
    fun chat(p: Townsperson): Task? {
        if (!p.role.adult) return null
        val level = p.level() as ServerLevel
        val partner = level.getEntitiesOfClass(Townsperson::class.java, p.boundingBox.inflate(10.0, 3.0, 10.0)) {
            it !== p && it.isAlive && it.role.adult && !it.isSleeping && it.attending == null && it.routine.task.let { task -> task == null || task.leisure }
        }.minByOrNull { p.distanceToSqr(it) } ?: return null
        val turns = 2 + p.random.nextInt(4)
        val seated = p.seat != null && partner.seat != null
        partner.routine.replace(Chat(partner, p, talking = false, length = turns, seated = seated))
        return Chat(p, partner, talking = true, length = turns, seated = seated)
    }

    /** A bard playing within earshot, if there is one. */
    fun performingBard(p: Townsperson): Townsperson? =
        p.level().getEntitiesOfClass(Townsperson::class.java, p.boundingBox.inflate(16.0, 4.0, 16.0)) {
            it.role == TownRole.BARD && it.activity == Activity.LUTE && it !== p
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
