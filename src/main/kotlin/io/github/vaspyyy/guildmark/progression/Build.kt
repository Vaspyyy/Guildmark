package io.github.vaspyyy.guildmark.progression

/**
 * Everything a player's stats, skill levels and learned nodes add up to, bonus by bonus. Worked out
 * from the synced data, so the client's copy matches the server's.
 */
class Build private constructor(
    private val totals: DoubleArray,
    private val xpGroup: DoubleArray,
    private val xpSkill: DoubleArray,
) {
    operator fun get(bonus: Bonus): Double = totals[bonus.ordinal]

    /** A flag bonus (a keystone's rule) is in play. */
    fun has(bonus: Bonus): Boolean = totals[bonus.ordinal] > 0.0

    /** Extra share of skill XP [skill] earns: for every skill, for its group and for it alone. */
    fun skillXp(skill: Skill): Double = totals[Bonus.SKILL_XP.ordinal] + xpGroup[skill.group.ordinal] + xpSkill[skill.ordinal]

    companion object {
        val EMPTY = Build(DoubleArray(Bonus.entries.size), DoubleArray(SkillGroup.entries.size), DoubleArray(Skill.entries.size))

        fun of(skills: SkillState, sheet: CharacterSheet): Build {
            val totals = DoubleArray(Bonus.entries.size)
            val xpGroup = DoubleArray(SkillGroup.entries.size)
            val xpSkill = DoubleArray(Skill.entries.size)
            fun add(effect: Effect) {
                when {
                    effect.bonus != Bonus.SKILL_XP -> totals[effect.bonus.ordinal] += effect.amount
                    effect.skill != null -> xpSkill[effect.skill.ordinal] += effect.amount
                    effect.group != null -> xpGroup[effect.group.ordinal] += effect.amount
                    else -> totals[Bonus.SKILL_XP.ordinal] += effect.amount
                }
            }
            for (effect in SkillTree.effects(sheet.nodes)) add(effect)
            for (skill in Skill.entries) add(skill.passiveAt(skills.level(skill)))
            for (stat in Stat.entries) for (effect in stat.effectsAt(sheet.stat(stat))) add(effect)
            return Build(totals, xpGroup, xpSkill)
        }
    }
}
