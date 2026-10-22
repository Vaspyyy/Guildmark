package io.github.vaspyyy.guildmark.progression

import io.github.vaspyyy.guildmark.progression.Bonus.*

/**
 * What every node of the skill tree gives. The slots run clockwise from the top: the fighting skills
 * across the top, the crafts down the right, coin and the guild at the bottom, the wild trades up the
 * left, with the two schools of magic at slots 4 (prayer) and 16 (sorcery). Slot 11 is kept for later.
 *
 * Node ids end up in save files: rename a node's effects freely, never its id.
 */
object TreeContent {
    private fun e(bonus: Bonus, amount: Double) = Effect(bonus, amount)
    private fun flag(bonus: Bonus) = Effect(bonus, 1.0)
    private fun xp(skill: Skill, amount: Double) = Effect(SKILL_XP, amount, skill = skill)
    private fun groupXp(group: SkillGroup, amount: Double) = Effect(SKILL_XP, amount, group = group)

    private class Branch(val spec: BranchSpec) {
        fun a(vararg effects: Effect) { spec.a = effects.toList() }
        fun b(vararg effects: Effect) { spec.b = effects.toList() }
        fun c(vararg effects: Effect) { spec.c = effects.toList() }
        fun notable(id: String, vararg effects: Effect) { spec.notables.add(Named(id, effects.toList())) }
        fun keystone(id: String, vararg effects: Effect) { spec.keystone = Named(id, effects.toList()) }
    }

    private fun TreeBuilder.branch(slot: Int, id: String, skill: Skill?, body: Branch.() -> Unit) {
        val spec = BranchSpec(id, slot, skill)
        Branch(spec).body()
        branch(spec)
    }

    /** The slot each branch (and ring node) sits at. */
    const val BLADES = 0
    const val HEAVY_ARMS = 1
    const val GUARDING = 2
    const val ARMOR_SLOT = 3
    const val PRAYER = 4
    const val SMITHING = 5
    const val ENCHANTING = 6
    const val ALCHEMY = 7
    const val COOKING = 8
    const val TRADE = 9
    const val GUILD = 10
    const val RESERVE = 11
    const val FARMING = 12
    const val FISHING = 13
    const val WOODCUTTING = 14
    const val MINING = 15
    const val SORCERY = 16
    const val STEALTH_SLOT = 17
    const val ARCHERY = 18
    const val EVASION = 19

    fun build(t: TreeBuilder) {
        t.root()

        // ---- The ring: one small node at every slot, a loop round the root ----
        t.ring(BLADES, "blades", listOf(e(MAX_HEALTH, 2.0)))
        t.ring(HEAVY_ARMS, "heavy_arms", listOf(e(STAMINA, 4.0)))
        t.ring(GUARDING, "guarding", listOf(e(POISE, 3.0)))
        t.ring(ARMOR_SLOT, "armor", listOf(e(ARMOR, 1.0)))
        t.ring(PRAYER, "prayer", listOf(e(FOCUS, 4.0)))
        t.ring(SMITHING, "smithing", listOf(groupXp(SkillGroup.CRAFT, 0.05)))
        t.ring(ENCHANTING, "enchanting", listOf(e(FOCUS, 4.0)))
        t.ring(ALCHEMY, "alchemy", listOf(e(FLASK_HEAL, 0.02)))
        t.ring(COOKING, "cooking", listOf(e(MAX_HEALTH, 2.0)))
        t.ring(TRADE, "trade", listOf(e(CONTRACT_MARKS, 0.02)))
        t.ring(GUILD, "guild", listOf(e(GUILD_XP, 0.03)))
        t.ring(RESERVE, "reserve", listOf(e(MOVE_SPEED, 0.02)))
        t.ring(FARMING, "farming", listOf(groupXp(SkillGroup.WILD, 0.05)))
        t.ring(FISHING, "fishing", listOf(e(SWIM_SPEED, 0.05)))
        t.ring(WOODCUTTING, "woodcutting", listOf(e(STAMINA, 4.0)))
        t.ring(MINING, "mining", listOf(e(BREAK_SPEED, 0.04)))
        t.ring(SORCERY, "sorcery", listOf(e(FOCUS, 4.0)))
        t.ring(STEALTH_SLOT, "stealth", listOf(e(SNEAK_SPEED, 0.04)))
        t.ring(ARCHERY, "archery", listOf(e(DRAW_SPEED, 0.03)))
        t.ring(EVASION, "evasion", listOf(e(ROLL_COST, -0.03)))

        // ---- Four roads from the root ----
        t.gate(BLADES, "sword", "icon/blades", listOf(e(MELEE_DAMAGE, 0.04), e(STAMINA, 5.0)))
        t.gate(SMITHING, "hand", "icon/smithing", listOf(groupXp(SkillGroup.CRAFT, 0.10), e(CRAFT_DURABILITY, 0.05)))
        t.gate(GUILD, "coin", "icon/trade", listOf(e(CONTRACT_MARKS, 0.05), e(GUILD_XP, 0.05)))
        t.gate(MINING, "wilds", "icon/woodcutting", listOf(groupXp(SkillGroup.WILD, 0.10), e(MOVE_SPEED, 0.03)))

        // ---- Fighting ----
        t.branch(BLADES, "blades", Skill.BLADES) {
            a(e(SWORD_DAMAGE, 0.04))
            b(e(ATTACK_COST, -0.04))
            c(e(SWORD_DAMAGE, 0.05), e(TEMPO, 0.01))
            notable("duelist", e(RIPOSTE_DAMAGE, 0.20), e(PARRY_WINDOW, 1.0))
            notable("flowing_steel", e(ATTACK_COST, -0.10), e(TEMPO, 0.03))
            notable("crimson_edge", e(SWORD_DAMAGE, 0.10), e(LIFESTEAL, 0.03))
            notable("steel_tempest", e(SKILL_DAMAGE, 0.20), e(SKILL_COST, -0.15))
            keystone("blade_dancer", e(ATTACK_COST, -0.25), e(TEMPO, 0.06), e(SWORD_DAMAGE, 0.10), e(CHARGED_DAMAGE, -0.30), e(POISE, -10.0))
        }
        t.branch(HEAVY_ARMS, "heavy_arms", Skill.HEAVY_ARMS) {
            a(e(HEAVY_DAMAGE, 0.04))
            b(e(POISE, 3.0))
            c(e(HEAVY_DAMAGE, 0.05), e(STANCE_DAMAGE, 0.03))
            notable("crushing_blows", e(STANCE_DAMAGE, 0.20))
            notable("brute_force", e(CHARGED_DAMAGE, 0.20), e(POISE, 5.0))
            notable("headsman", e(EXECUTE_DAMAGE, 0.25))
            notable("warlord", e(HEAVY_DAMAGE, 0.10), e(KNOCKBACK_RESIST, 0.10), e(MAX_HEALTH, 4.0))
            keystone("juggernaut", e(CHARGED_DAMAGE, 0.30), e(POISE, 25.0), e(HEAVY_DAMAGE, 0.10), flag(UNDYING), e(ROLL_COST, 0.40), e(MOVE_SPEED, -0.05))
        }
        t.branch(GUARDING, "guarding", Skill.GUARDING) {
            a(e(BLOCK_COST, -0.05))
            b(e(PARRY_STAMINA, 3.0))
            c(e(BLOCK_COST, -0.05), e(POISE, 2.0))
            notable("steadfast", e(BLOCK_COST, -0.12))
            notable("keen_parry", e(PARRY_WINDOW, 2.0))
            notable("retaliation", e(COUNTER_DAMAGE, 0.30), e(THORNS, 0.05))
            notable("unbroken", e(BLOCK_COST, -0.10), e(PARRY_STAMINA, 10.0), e(ARMOR, 2.0))
            keystone("bulwark", flag(GUARD_REGEN), e(BLOCK_COST, -0.25), e(DAMAGE_TAKEN, -0.05), e(MELEE_DAMAGE, -0.10))
        }
        t.branch(ARMOR_SLOT, "armor", Skill.ARMOR) {
            a(e(ARMOR, 1.0))
            b(e(DAMAGE_TAKEN, -0.01))
            c(e(TOUGHNESS, 1.0), e(POISE, 2.0))
            notable("well_fitted", e(ROLL_LOAD, 3.0))
            notable("hardened", e(DAMAGE_TAKEN, -0.05), e(PROJECTILE_TAKEN, -0.10))
            notable("iron_skin", e(ARMOR, 3.0), e(TOUGHNESS, 2.0))
            notable("warded", e(MAGIC_TAKEN, -0.10), e(FIRE_TAKEN, -0.10), e(BLAST_TAKEN, -0.15))
            keystone("iron_fortress", e(ARMOR, 6.0), e(POISE, 30.0), e(DAMAGE_TAKEN, -0.15), flag(HEAVY_ROLL), e(MOVE_SPEED, -0.08))
        }
        t.branch(STEALTH_SLOT, "stealth", Skill.STEALTH) {
            a(e(STEALTH, 0.05))
            b(e(SNEAK_SPEED, 0.05))
            c(e(BACKSTAB_DAMAGE, 0.08), e(AMBUSH_DAMAGE, 0.03))
            notable("soft_steps", e(SNEAK_SPEED, 0.15), e(STEALTH, 0.10))
            notable("assassin", e(BACKSTAB_DAMAGE, 0.30))
            notable("ambusher", e(AMBUSH_DAMAGE, 0.20))
            notable("unseen", e(STEALTH, 0.15), e(FIRST_STRIKE, 0.20))
            keystone("shadow", e(BACKSTAB_DAMAGE, 0.75), e(AMBUSH_DAMAGE, 0.30), e(STEALTH, 0.25), e(HEALTH_PCT, -0.20))
        }
        t.branch(ARCHERY, "archery", Skill.ARCHERY) {
            a(e(ARROW_DAMAGE, 0.04))
            b(e(DRAW_SPEED, 0.04))
            c(e(ARROW_DAMAGE, 0.05), e(ARROW_REFUND, 0.03))
            notable("fletcher", e(ARROW_REFUND, 0.15))
            notable("quick_draw", e(DRAW_SPEED, 0.15))
            notable("hunters_eye", e(BEAST_DAMAGE, 0.20), e(FIRST_STRIKE, 0.25))
            notable("eagle_eye", e(ARROW_DAMAGE, 0.12), e(BOSS_DAMAGE, 0.08))
            keystone("deadeye", e(ARROW_DAMAGE, 0.35), e(ARROW_REFUND, 0.15), e(MELEE_DAMAGE, -0.20))
        }
        t.branch(EVASION, "evasion", Skill.EVASION) {
            a(e(ROLL_COST, -0.04))
            b(e(MOVE_SPEED, 0.01))
            c(e(ROLL_COST, -0.04), e(DODGE_STAMINA, 2.0))
            notable("light_feet", e(MOVE_SPEED, 0.04), e(SAFE_FALL, 1.0))
            notable("ghost_step", e(IFRAMES, 1.0))
            notable("slipstream", e(DODGE_STAMINA, 8.0), e(ROLL_COST, -0.08))
            notable("acrobat", e(STEP, 0.5), e(FALL_DAMAGE, -0.25), e(JUMP, 0.08))
            keystone("wraith_step", e(IFRAMES, 2.0), e(MOVE_SPEED, 0.08), e(ROLL_COST, -0.20), e(HEALTH_PCT, -0.15))
        }

        // ---- Magic ----
        t.branch(SORCERY, "sorcery", Skill.SORCERY) {
            a(e(SORCERY_DAMAGE, 0.04))
            b(e(SPELL_COST, -0.04))
            c(e(SORCERY_DAMAGE, 0.05), e(FOCUS_REGEN, 0.03))
            notable("spellweaver", e(CAST_SPEED, 0.10), e(SPELL_SLOTS, 1.0))
            notable("deep_reserves", e(FOCUS, 10.0), e(FOCUS_REGEN, 0.10))
            notable("starseer", e(SORCERY_DAMAGE, 0.10), e(BOSS_DAMAGE, 0.05))
            notable("still_mind", e(MAGIC_TAKEN, -0.10), e(SPELL_SLOTS, 1.0))
            keystone("archmage", e(SORCERY_DAMAGE, 0.30), e(SPELL_COST, -0.15), e(SPELL_SLOTS, 1.0), e(MELEE_DAMAGE, -0.25), e(HEALTH_PCT, -0.10))
        }
        t.branch(PRAYER, "prayer", Skill.PRAYER) {
            a(e(PRAYER_POWER, 0.04))
            b(e(SPELL_COST, -0.04))
            c(e(PRAYER_POWER, 0.05), e(UNDEAD_DAMAGE, 0.03))
            notable("devout", e(SPELL_SLOTS, 1.0), e(FOCUS, 8.0))
            notable("mercy", e(PRAYER_POWER, 0.10), e(FLASK_HEAL, 0.05))
            notable("bane_of_the_hollow", e(UNDEAD_DAMAGE, 0.25))
            notable("zealot", e(CAST_SPEED, 0.10), e(PRAYER_POWER, 0.08))
            keystone("lightbearer", e(PRAYER_POWER, 0.30), e(UNDEAD_DAMAGE, 0.20), e(SPELL_SLOTS, 1.0), e(STEALTH, -0.30))
        }

        // ---- Crafts ----
        t.branch(SMITHING, "smithing", Skill.SMITHING) {
            a(e(CRAFT_DURABILITY, 0.05))
            b(xp(Skill.SMITHING, 0.10))
            c(e(SMELT_EXTRA, 0.03), e(ANVIL_COST, -0.05))
            notable("hot_bellows", e(SMELT_EXTRA, 0.10))
            notable("tempered", e(CRAFT_DURABILITY, 0.20))
            notable("thrifty_smith", e(ANVIL_COST, -0.25))
            notable("armsmaster", e(ARMOR, 1.0), e(ATTACK_DAMAGE, 1.0), e(CRAFT_DURABILITY, 0.10))
            keystone("master_artisan", e(CRAFT_DURABILITY, 0.50), e(SMELT_EXTRA, 0.15), e(ANVIL_COST, -0.25))
        }
        t.branch(ENCHANTING, "enchanting", Skill.ENCHANTING) {
            a(e(XP_ORBS, 0.05))
            b(xp(Skill.ENCHANTING, 0.10))
            c(e(ENCHANT_REFUND, 0.04))
            notable("studious", e(XP_ORBS, 0.15))
            notable("frugal_scholar", e(ENCHANT_REFUND, 0.15))
            notable("runic_ward", e(MAGIC_TAKEN, -0.10), e(FOCUS, 10.0))
            notable("attuned", e(FOCUS_REGEN, 0.15), e(SKILL_DAMAGE, 0.10))
            keystone("arcane_siphon", e(XP_ORBS, 0.50), e(ENCHANT_REFUND, 0.25), e(FOCUS_REGEN, 0.20))
        }
        t.branch(ALCHEMY, "alchemy", Skill.ALCHEMY) {
            a(e(POTION_DURATION, 0.05))
            b(xp(Skill.ALCHEMY, 0.10))
            c(e(POTION_DURATION, 0.05), e(POTION_POTENCY, 0.02))
            notable("long_draught", e(POTION_DURATION, 0.15))
            notable("concentrate", e(POTION_POTENCY, 0.10))
            notable("physician", e(FLASK_HEAL, 0.05), e(REGEN, 1.0))
            notable("purity", e(MAGIC_TAKEN, -0.10), e(BURN_TIME, -0.25))
            keystone("philosopher", e(POTION_DURATION, 0.50), e(POTION_POTENCY, 0.15), e(FLASK_CHARGES, 1.0))
        }
        t.branch(COOKING, "cooking", Skill.COOKING) {
            a(e(FOOD_SATURATION, 0.05))
            b(xp(Skill.COOKING, 0.10))
            c(e(COOK_EXTRA, 0.04), e(COOK_CRAFT, 0.01))
            notable("hearty_meals", e(FOOD_SATURATION, 0.15))
            notable("second_helping", e(COOK_EXTRA, 0.15))
            notable("comfort_food", e(FOOD_HEAL, 2.0))
            notable("camp_cook", e(REGEN, 1.0), e(STAMINA_REGEN, 0.05), e(COOK_KEEP, 0.50))
            keystone("feast_maker", e(FOOD_HEAL, 4.0), e(FOOD_SATURATION, 0.40), e(COOK_EXTRA, 0.20), e(COOK_CRAFT, 0.03))
        }

        // ---- Coin and the guild ----
        t.branch(TRADE, "trade", Skill.TRADE) {
            a(e(CONTRACT_MARKS, 0.03))
            b(xp(Skill.TRADE, 0.10))
            c(e(STANDING, 0.05))
            notable("silver_tongue", e(STANDING, 0.15), e(WORD_FRESH, 3.0))
            notable("shrewd_bargainer", e(TRADE_REFUND, 0.10))
            notable("guild_broker", e(CONTRACT_MARKS, 0.08), e(GUILD_XP, 0.05), e(HAUL_PAY, 0.03))
            notable("well_connected", e(DEADLINE_DAYS, 1.0), e(STANDING, 0.10), e(WORD_FRESH, 2.0), e(ROAD_WARY, 0.10))
            keystone("merchant_prince", e(CONTRACT_MARKS, 0.15), e(TRADE_REFUND, 0.15), e(STANDING, 0.25), e(HAUL_PAY, 0.05))
        }
        t.branch(GUILD, "guild", null) {
            spec.ranks = mapOf(
                "e" to AdventurerRank.F, "a1" to AdventurerRank.F, "b1" to AdventurerRank.F, "a2" to AdventurerRank.E,
                "b2" to AdventurerRank.E, "a3" to AdventurerRank.E, "b3" to AdventurerRank.D, "m4" to AdventurerRank.D,
                "a5" to AdventurerRank.C, "b5" to AdventurerRank.C, "a6" to AdventurerRank.C, "b6" to AdventurerRank.B,
                "m7" to AdventurerRank.A, "k" to AdventurerRank.S,
            )
            a(e(CONTRACT_MARKS, 0.03))
            b(e(GUILD_XP, 0.04))
            c(e(MAX_HEALTH, 2.0))
            notable("haggler", e(CONTRACT_MARKS, 0.10))
            notable("pathfinder", e(DEADLINE_DAYS, 1.0), e(MOVE_SPEED, 0.03))
            notable("fortunes_favour", e(LUCK, 2.0))
            notable("veteran", e(MAX_HEALTH, 4.0), e(ATTACK_DAMAGE, 1.0))
            keystone("guild_champion", e(CONTRACT_MARKS, 0.20), e(GUILD_XP, 0.20), e(DEADLINE_DAYS, 1.0))
        }

        // ---- The wild trades ----
        t.branch(FARMING, "farming", Skill.FARMING) {
            a(e(CROP_EXTRA, 0.03))
            b(xp(Skill.FARMING, 0.10))
            c(e(TWINS, 0.03))
            notable("green_thumb", e(CROP_EXTRA, 0.10))
            notable("herdsman", e(TWINS, 0.15))
            notable("harvest_home", e(FOOD_SATURATION, 0.10), e(CROP_EXTRA, 0.05))
            notable("ploughman", e(DIRT_SPEED, 0.30), e(SURE_FOOTED, 0.10))
            keystone("green_hand", flag(REPLANT), e(CROP_EXTRA, 0.20), e(TWINS, 0.10))
        }
        t.branch(FISHING, "fishing", Skill.FISHING) {
            a(e(FISH_EXTRA, 0.03))
            b(xp(Skill.FISHING, 0.10))
            c(e(FISH_TREASURE, 0.03))
            notable("patient_angler", e(FISH_EXTRA, 0.10))
            notable("treasure_hunter", e(FISH_TREASURE, 0.10))
            notable("sea_legs", e(SWIM_SPEED, 0.25), e(OXYGEN, 2.0))
            notable("lucky_cast", e(LUCK, 1.0), e(FISH_EXTRA, 0.10))
            keystone("seas_favour", e(FISH_EXTRA, 0.30), e(FISH_TREASURE, 0.20), e(SWIM_SPEED, 0.15))
        }
        t.branch(WOODCUTTING, "woodcutting", Skill.WOODCUTTING) {
            a(e(LOG_SPEED, 0.05))
            b(xp(Skill.WOODCUTTING, 0.10))
            c(e(LOG_DOUBLE, 0.04))
            notable("lumberjack", e(LOG_SPEED, 0.20))
            notable("woodsmans_share", e(LOG_DOUBLE, 0.15))
            notable("forest_stride", e(SURE_FOOTED, 0.25), e(MOVE_SPEED, 0.03))
            notable("woodsmans_axe", e(HEAVY_DAMAGE, 0.08), e(LOG_SPEED, 0.10))
            keystone("timberfall", flag(FELLER), e(LOG_DOUBLE, 0.10))
        }
        t.branch(MINING, "mining", Skill.MINING) {
            a(e(STONE_SPEED, 0.05))
            b(xp(Skill.MINING, 0.10))
            c(e(ORE_DOUBLE, 0.03))
            notable("prospector", e(ORE_DOUBLE, 0.10))
            notable("stonebreaker", e(STONE_SPEED, 0.20))
            notable("deep_delver", e(BLAST_TAKEN, -0.20), e(UNDERWATER_MINING, 0.40), e(OXYGEN, 1.0))
            notable("lucky_strike", e(ORE_DOUBLE, 0.10), e(LUCK, 1.0))
            keystone("vein_breaker", flag(VEIN_MINER), e(ORE_DOUBLE, 0.10))
        }

        t.finish()
    }
}
