package io.github.vaspyyy.guildmark.progression

import com.mojang.serialization.Codec
import io.netty.buffer.ByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec

/** Which part of the skill tree a skill's branch grows in. */
enum class SkillGroup(val id: String) {
    COMBAT("combat"),
    CRAFT("craft"),
    WILD("wild"),
    SOCIAL("social");

    val title: Component get() = Component.translatable("skill_group.guildmark.$id")
}

/**
 * Skills grow by doing: swinging a sword, mining ore, brewing, trading. Each level gives a small bonus of
 * its own, guild XP equal to the new level, and at every [MILESTONES] level a skill tree point. A skill's
 * branch of the tree opens further the higher the skill.
 */
enum class Skill(
    val id: String,
    val group: SkillGroup,
    /** What every level past the first adds, without any tree nodes. */
    val passive: Bonus,
    val perLevel: Double,
) {
    BLADES("blades", SkillGroup.COMBAT, Bonus.SWORD_DAMAGE, 0.0025),
    HEAVY_ARMS("heavy_arms", SkillGroup.COMBAT, Bonus.HEAVY_DAMAGE, 0.0025),
    ARCHERY("archery", SkillGroup.COMBAT, Bonus.ARROW_DAMAGE, 0.0025),
    GUARDING("guarding", SkillGroup.COMBAT, Bonus.BLOCK_COST, -0.002),
    EVASION("evasion", SkillGroup.COMBAT, Bonus.ROLL_COST, -0.002),
    ARMOR("armor", SkillGroup.COMBAT, Bonus.DAMAGE_TAKEN, -0.001),
    STEALTH("stealth", SkillGroup.COMBAT, Bonus.STEALTH, 0.0025),
    SORCERY("sorcery", SkillGroup.COMBAT, Bonus.SORCERY_DAMAGE, 0.0025),
    PRAYER("prayer", SkillGroup.COMBAT, Bonus.PRAYER_POWER, 0.0025),
    SMITHING("smithing", SkillGroup.CRAFT, Bonus.CRAFT_DURABILITY, 0.003),
    ALCHEMY("alchemy", SkillGroup.CRAFT, Bonus.POTION_DURATION, 0.003),
    COOKING("cooking", SkillGroup.CRAFT, Bonus.FOOD_SATURATION, 0.003),
    ENCHANTING("enchanting", SkillGroup.CRAFT, Bonus.XP_ORBS, 0.002),
    MINING("mining", SkillGroup.WILD, Bonus.STONE_SPEED, 0.003),
    WOODCUTTING("woodcutting", SkillGroup.WILD, Bonus.LOG_SPEED, 0.003),
    FARMING("farming", SkillGroup.WILD, Bonus.CROP_EXTRA, 0.0015),
    FISHING("fishing", SkillGroup.WILD, Bonus.FISH_EXTRA, 0.0015),
    TRADE("trade", SkillGroup.SOCIAL, Bonus.CONTRACT_MARKS, 0.001);

    val title: Component get() = Component.translatable("skill.guildmark.$id")
    val description: Component get() = Component.translatable("skill.guildmark.$id.desc")

    /** The passive bonus a skill at [level] gives. */
    fun passiveAt(level: Int): Effect = Effect(passive, perLevel * (level - 1))

    companion object {
        const val MAX = 100
        val MILESTONES = intArrayOf(25, 50, 75, 100)

        /** Skill XP from [level] to the next. */
        fun xpToNext(level: Int): Int = 10 + 4 * level + level * level * 3 / 25

        /** Total skill XP at the start of each level; a skill starts at level 1 with none. */
        private val TOTAL = IntArray(MAX + 1).also { t -> for (l in 2..MAX) t[l] = t[l - 1] + xpToNext(l - 1) }

        fun xpAt(level: Int): Int = TOTAL[level.coerceIn(1, MAX)]

        fun levelOf(xp: Int): Int {
            var level = 1
            while (level < MAX && xp >= TOTAL[level + 1]) level++
            return level
        }

        /** The tree points the milestones up to [level] give. */
        fun milestonesAt(level: Int): Int = MILESTONES.count { level >= it }

        fun byId(id: String): Skill? = entries.firstOrNull { it.id == id }
    }
}

/** A player's skill XP, by skill id. Immutable: replace it with setData so it syncs to the client. */
data class SkillState(val xp: Map<String, Int> = mapOf()) {
    fun xp(skill: Skill): Int = xp[skill.id] ?: 0
    fun level(skill: Skill): Int = Skill.levelOf(xp(skill))

    /** Share of the way from this level to the next, 0 to 1 (1 at the top). */
    fun progress(skill: Skill): Float {
        val level = level(skill)
        if (level >= Skill.MAX) return 1f
        return (xp(skill) - Skill.xpAt(level)).toFloat() / Skill.xpToNext(level)
    }

    /** Tree points from every skill's milestones. */
    fun milestones(): Int = Skill.entries.sumOf { Skill.milestonesAt(level(it)) }

    companion object {
        val CODEC: Codec<SkillState> = Codec.unboundedMap(Codec.STRING, Codec.INT).xmap(::SkillState, SkillState::xp)
        val STREAM_CODEC: StreamCodec<ByteBuf, SkillState> = ByteBufCodecs.fromCodec(CODEC)
    }
}
