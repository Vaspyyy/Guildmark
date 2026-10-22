package io.github.vaspyyy.guildmark.progression

import net.minecraft.core.Holder
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.ai.attributes.Attribute
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** How a bonus's amount reads in a tooltip. */
enum class Shown {
    /** 0.05 reads "+5%". */
    PERCENT,
    /** 2 reads "+2", 0.5 reads "+0.5". */
    FLAT,
    /** No amount: the line says it all. */
    FLAG,
}

/**
 * Everything a stat, a skill level or a tree node can add to. The ones with an [attribute] become
 * vanilla attribute modifiers; the rest are read where they matter (combat, drops, contracts...).
 *
 * Amounts are changes to the quantity named: [ROLL_COST] -0.1 means rolls cost 10% less, [DAMAGE_TAKEN]
 * -0.1 means 10% less damage taken. [good] says which way helps, for the tooltip's colour.
 */
enum class Bonus(
    val id: String,
    val shown: Shown,
    val attribute: Holder<Attribute>? = null,
    val operation: AttributeModifier.Operation = AttributeModifier.Operation.ADD_VALUE,
    val good: Boolean = true,
) {
    // ---- Vanilla attributes ----
    MAX_HEALTH("max_health", Shown.FLAT, Attributes.MAX_HEALTH),
    HEALTH_PCT("health_pct", Shown.PERCENT, Attributes.MAX_HEALTH, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL),
    ARMOR("armor", Shown.FLAT, Attributes.ARMOR),
    TOUGHNESS("toughness", Shown.FLAT, Attributes.ARMOR_TOUGHNESS),
    ATTACK_DAMAGE("attack_damage", Shown.FLAT, Attributes.ATTACK_DAMAGE),
    MOVE_SPEED("move_speed", Shown.PERCENT, Attributes.MOVEMENT_SPEED, AttributeModifier.Operation.ADD_MULTIPLIED_BASE),
    LUCK("luck", Shown.FLAT, Attributes.LUCK),
    BREAK_SPEED("break_speed", Shown.PERCENT, Attributes.BLOCK_BREAK_SPEED, AttributeModifier.Operation.ADD_MULTIPLIED_BASE),
    BLOCK_REACH("block_reach", Shown.FLAT, Attributes.BLOCK_INTERACTION_RANGE),
    ENTITY_REACH("entity_reach", Shown.FLAT, Attributes.ENTITY_INTERACTION_RANGE),
    KNOCKBACK_RESIST("knockback_resist", Shown.PERCENT, Attributes.KNOCKBACK_RESISTANCE),
    SAFE_FALL("safe_fall", Shown.FLAT, Attributes.SAFE_FALL_DISTANCE),
    FALL_DAMAGE("fall_damage", Shown.PERCENT, Attributes.FALL_DAMAGE_MULTIPLIER, AttributeModifier.Operation.ADD_MULTIPLIED_BASE, good = false),
    OXYGEN("oxygen", Shown.FLAT, Attributes.OXYGEN_BONUS),
    SNEAK_SPEED("sneak_speed", Shown.PERCENT, Attributes.SNEAKING_SPEED, AttributeModifier.Operation.ADD_MULTIPLIED_BASE),
    SWIM_SPEED("swim_speed", Shown.PERCENT, Attributes.WATER_MOVEMENT_EFFICIENCY),
    UNDERWATER_MINING("underwater_mining", Shown.PERCENT, Attributes.SUBMERGED_MINING_SPEED),
    JUMP("jump", Shown.PERCENT, Attributes.JUMP_STRENGTH, AttributeModifier.Operation.ADD_MULTIPLIED_BASE),
    STEP("step", Shown.FLAT, Attributes.STEP_HEIGHT),
    BURN_TIME("burn_time", Shown.PERCENT, Attributes.BURNING_TIME, AttributeModifier.Operation.ADD_MULTIPLIED_BASE, good = false),
    SURE_FOOTED("sure_footed", Shown.PERCENT, Attributes.MOVEMENT_EFFICIENCY),

    // ---- Dealing damage ----
    MELEE_DAMAGE("melee_damage", Shown.PERCENT),
    SWORD_DAMAGE("sword_damage", Shown.PERCENT),
    HEAVY_DAMAGE("heavy_damage", Shown.PERCENT),
    ARROW_DAMAGE("arrow_damage", Shown.PERCENT),
    UNARMED_DAMAGE("unarmed_damage", Shown.PERCENT),
    RIPOSTE_DAMAGE("riposte_damage", Shown.PERCENT),
    BACKSTAB_DAMAGE("backstab_damage", Shown.PERCENT),
    SKILL_DAMAGE("skill_damage", Shown.PERCENT),
    CHARGED_DAMAGE("charged_damage", Shown.PERCENT),
    COUNTER_DAMAGE("counter_damage", Shown.PERCENT),
    STANCE_DAMAGE("stance_damage", Shown.PERCENT),
    UNDEAD_DAMAGE("undead_damage", Shown.PERCENT),
    BOSS_DAMAGE("boss_damage", Shown.PERCENT),
    BEAST_DAMAGE("beast_damage", Shown.PERCENT),
    EXECUTE_DAMAGE("execute_damage", Shown.PERCENT),
    FIRST_STRIKE("first_strike", Shown.PERCENT),
    DESPERATE_DAMAGE("desperate_damage", Shown.PERCENT),
    AMBUSH_DAMAGE("ambush_damage", Shown.PERCENT),
    LIFESTEAL("lifesteal", Shown.PERCENT),
    DOUBLE_STRIKE("double_strike", Shown.PERCENT),
    ARROW_REFUND("arrow_refund", Shown.PERCENT),
    DRAW_SPEED("draw_speed", Shown.PERCENT),

    // ---- Taking damage ----
    DAMAGE_TAKEN("damage_taken", Shown.PERCENT, good = false),
    MAGIC_TAKEN("magic_taken", Shown.PERCENT, good = false),
    FIRE_TAKEN("fire_taken", Shown.PERCENT, good = false),
    BLAST_TAKEN("blast_taken", Shown.PERCENT, good = false),
    PROJECTILE_TAKEN("projectile_taken", Shown.PERCENT, good = false),
    POISE("poise", Shown.FLAT),
    THORNS("thorns", Shown.PERCENT),
    /** Health mended every five seconds out of a fight. */
    REGEN("regen", Shown.FLAT),
    /** Once between rests, a killing blow leaves you standing instead. */
    UNDYING("undying", Shown.FLAG),

    // ---- Stamina and focus ----
    STAMINA("stamina", Shown.FLAT),
    STAMINA_REGEN("stamina_regen", Shown.PERCENT),
    ROLL_COST("roll_cost", Shown.PERCENT, good = false),
    ATTACK_COST("attack_cost", Shown.PERCENT, good = false),
    BLOCK_COST("block_cost", Shown.PERCENT, good = false),
    SPRINT_COST("sprint_cost", Shown.PERCENT, good = false),
    FOCUS("focus", Shown.FLAT),
    FOCUS_REGEN("focus_regen", Shown.PERCENT),
    FOCUS_ON_HIT("focus_on_hit", Shown.FLAT),
    SKILL_COST("skill_cost", Shown.PERCENT, good = false),
    PARRY_WINDOW("parry_window", Shown.FLAT),
    PARRY_STAMINA("parry_stamina", Shown.FLAT),
    IFRAMES("iframes", Shown.FLAT),
    DODGE_STAMINA("dodge_stamina", Shown.FLAT),
    TEMPO("tempo", Shown.PERCENT),
    /** Armour points you can wear before rolls turn heavy. */
    ROLL_LOAD("roll_load", Shown.FLAT),
    /** Every roll is the slow heavy roll. */
    HEAVY_ROLL("heavy_roll", Shown.FLAG, good = false),
    /** Stamina comes back at the full rate while guarding. */
    GUARD_REGEN("guard_regen", Shown.FLAG),

    // ---- The Warden's Flask ----
    /** Extra share of greatest health each draught gives back. */
    FLASK_HEAL("flask_heal", Shown.PERCENT),
    FLASK_CHARGES("flask_charges", Shown.FLAT),

    // ---- The guild and the roads ----
    CONTRACT_MARKS("contract_marks", Shown.PERCENT),
    DEADLINE_DAYS("deadline_days", Shown.FLAT),
    GUILD_XP("guild_xp", Shown.PERCENT),
    /** More skill XP: for one skill, one group of skills, or all of them (see [Effect]). */
    SKILL_XP("skill_xp", Shown.PERCENT),
    STANDING("standing", Shown.PERCENT),
    TRADE_REFUND("trade_refund", Shown.PERCENT),
    /** The share added to the pay of a haul ticket when it is delivered (the Trade tree's brokers; `Rungs.perk` keeps the sum within a tenth). */
    HAUL_PAY("haul_pay", Shown.PERCENT),
    /** Hours taken off the age of the Word of the other towns. */
    WORD_FRESH("word_fresh", Shown.FLAT),
    /** The share taken off the Hunt's chance against the loads you carry. */
    ROAD_WARY("road_wary", Shown.PERCENT),

    // ---- Working the land and the forge ----
    ORE_DOUBLE("ore_double", Shown.PERCENT),
    STONE_SPEED("stone_speed", Shown.PERCENT),
    LOG_SPEED("log_speed", Shown.PERCENT),
    DIRT_SPEED("dirt_speed", Shown.PERCENT),
    LOG_DOUBLE("log_double", Shown.PERCENT),
    /** The logs above the one you cut come down with it. */
    FELLER("feller", Shown.FLAG),
    /** An ore you mine brings the rest of its vein with it. */
    VEIN_MINER("vein_miner", Shown.FLAG),
    CROP_EXTRA("crop_extra", Shown.PERCENT),
    /** Crops you harvest plant themselves again. */
    REPLANT("replant", Shown.FLAG),
    TWINS("twins", Shown.PERCENT),
    FISH_EXTRA("fish_extra", Shown.PERCENT),
    FISH_TREASURE("fish_treasure", Shown.PERCENT),
    SMELT_EXTRA("smelt_extra", Shown.PERCENT),
    COOK_EXTRA("cook_extra", Shown.PERCENT),
    CRAFT_DURABILITY("craft_durability", Shown.PERCENT),
    ANVIL_COST("anvil_cost", Shown.PERCENT, good = false),
    POTION_DURATION("potion_duration", Shown.PERCENT),
    POTION_POTENCY("potion_potency", Shown.PERCENT),
    FOOD_SATURATION("food_saturation", Shown.PERCENT),
    FOOD_HEAL("food_heal", Shown.FLAT),
    /** The dishes you cook come out better: added to a batch's quality, which runs from 0 to 1 (Hearthcraft, 2.5). */
    COOK_CRAFT("cook_craft", Shown.PERCENT),
    /** The dishes you cook keep longer before they go stale, by this much of their keeping time (Hearthcraft, 3.2). */
    COOK_KEEP("cook_keep", Shown.PERCENT),
    XP_ORBS("xp_orbs", Shown.PERCENT),
    ENCHANT_REFUND("enchant_refund", Shown.PERCENT),

    // ---- Stealth ----
    /** How much harder you are to notice while sneaking. */
    STEALTH("stealth", Shown.PERCENT),

    // ---- Magic ----
    SORCERY_DAMAGE("sorcery_damage", Shown.PERCENT),
    /** Prayers hurt more and heal more. */
    PRAYER_POWER("prayer_power", Shown.PERCENT),
    SPELL_COST("spell_cost", Shown.PERCENT, good = false),
    CAST_SPEED("cast_speed", Shown.PERCENT),
    /** More spells attuned at once. */
    SPELL_SLOTS("spell_slots", Shown.FLAT);

    companion object {
        private val BY_ID = entries.associateBy { it.id }
        fun byId(id: String): Bonus? = BY_ID[id]
    }
}

/**
 * One bonus at one amount. A [Bonus.SKILL_XP] effect names the [skill] or [group] it's for, or neither
 * for every skill.
 */
class Effect(val bonus: Bonus, val amount: Double, val skill: Skill? = null, val group: SkillGroup? = null) {
    /** Whether this effect helps (for the tooltip's colour). */
    val helps: Boolean get() = bonus.shown == Shown.FLAG && bonus.good || (amount > 0) == bonus.good

    /** The tooltip line, like "+4% sword damage" or "Rolls are always heavy". */
    fun line(): Component {
        val key = "bonus.guildmark.${bonus.id}"
        if (bonus.shown == Shown.FLAG) return Component.translatable(key)
        val amount = format(amount, bonus.shown)
        return when {
            skill != null -> Component.translatable("$key.skill", amount, skill.title)
            group != null -> Component.translatable("$key.group", amount, group.title)
            else -> Component.translatable(key, amount)
        }
    }

    companion object {
        /** "+5%", "-10%", "+2", "+0.5". */
        fun format(amount: Double, shown: Shown): String {
            val sign = if (amount < 0) "-" else "+"
            return when (shown) {
                Shown.PERCENT -> {
                    val pct = abs(amount) * 100
                    sign + (if (abs(pct - pct.roundToInt()) < 0.05) pct.roundToInt().toString() else "%.1f".format(Locale.ROOT, pct)) + "%"
                }
                Shown.FLAT -> {
                    val v = abs(amount)
                    sign + (if (abs(v - v.roundToInt()) < 0.05) v.roundToInt().toString() else "%.1f".format(Locale.ROOT, v))
                }
                Shown.FLAG -> ""
            }
        }
    }
}
