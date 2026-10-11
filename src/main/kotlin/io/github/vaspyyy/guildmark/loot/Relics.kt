package io.github.vaspyyy.guildmark.loot

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.registry.ModDataComponents
import io.github.vaspyyy.guildmark.registry.ModItems
import io.github.vaspyyy.guildmark.story.HollowKing
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.DamageTypeTags
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.util.RandomSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.EquipmentSlotGroup
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.boss.enderdragon.EnderDragon
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemAttributeModifiers
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.core.RegistryAccess
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent
import java.util.Optional

/** What makes an item a relic: its rarity and its affixes. */
data class RelicData(val rarity: Int, val affixes: List<String>) {
    fun has(affix: Affix): Boolean = affix.id in affixes

    companion object {
        val CODEC: Codec<RelicData> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.fieldOf("rarity").forGetter(RelicData::rarity),
                Codec.STRING.listOf().fieldOf("affixes").forGetter(RelicData::affixes),
            ).apply(i, ::RelicData)
        }
    }
}

/**
 * Relics: randomly rolled weapons and armour with a rarity, a name, enchantments and affixes. They drop
 * from lairs, vaults, champions, sieges, the Hollow King and sealed relic caches.
 */
object Relics {
    private val MELEE = listOf(Items.IRON_SWORD, Items.IRON_AXE, Items.DIAMOND_SWORD, Items.DIAMOND_AXE, Items.NETHERITE_SWORD, Items.NETHERITE_AXE, Items.MACE)
    private val RANGED = listOf(Items.BOW, Items.CROSSBOW, Items.TRIDENT)
    private val ARMOR_COMMON = listOf(Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS,
        Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS)
    private val ARMOR_DIAMOND = listOf(Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS)
    private val ARMOR_NETHERITE = listOf(Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS)

    private val LEGENDARY_WEAPONS = listOf("oathkeeper", "dawnbreaker", "ledgerbane", "marens_promise", "the_last_bell", "crownsplitter")
    private val LEGENDARY_ARMOR = listOf("aegis_of_aldric", "sennas_grace", "brannochs_bulwark", "ivos_stride")
    private val EPIC_SUFFIXES = listOf("wardens", "old_guild", "seven_bells", "long_night", "hollow_court")

    /** A fresh relic of [rarity]. */
    fun generate(random: RandomSource, rarity: Rarity, registries: RegistryAccess): ItemStack {
        val weapon = random.nextBoolean()
        val base: Item = if (weapon) {
            val pool = when (rarity) {
                Rarity.COMMON, Rarity.UNCOMMON -> listOf(Items.IRON_SWORD, Items.IRON_AXE, Items.BOW, Items.CROSSBOW)
                Rarity.RARE -> listOf(Items.DIAMOND_SWORD, Items.DIAMOND_AXE, Items.BOW, Items.CROSSBOW, Items.TRIDENT)
                Rarity.EPIC -> listOf(Items.DIAMOND_SWORD, Items.DIAMOND_AXE, Items.BOW, Items.CROSSBOW, Items.TRIDENT, Items.MACE)
                Rarity.LEGENDARY -> listOf(Items.NETHERITE_SWORD, Items.NETHERITE_AXE, Items.BOW, Items.CROSSBOW, Items.TRIDENT, Items.MACE)
            }
            pool[random.nextInt(pool.size)]
        } else {
            val pool = when (rarity) {
                Rarity.COMMON, Rarity.UNCOMMON -> ARMOR_COMMON
                Rarity.RARE, Rarity.EPIC -> ARMOR_DIAMOND
                Rarity.LEGENDARY -> ARMOR_NETHERITE
            }
            pool[random.nextInt(pool.size)]
        }
        var stack = ItemStack(base)
        stack = EnchantmentHelper.enchantItem(random, stack, rarity.enchantLevel, registries, Optional.empty())

        val melee = base in MELEE
        val candidates = Affix.entries.filter { it.weapon == weapon && (!it.meleeOnly || melee) && (it != Affix.FEATHER || isBoots(base)) }.shuffled(java.util.Random(random.nextLong()))
        val affixes = candidates.take(rarity.affixes)
        val data = RelicData(rarity.ordinal, affixes.map { it.id })
        stack.set(ModDataComponents.RELIC.get(), data)
        applyStats(stack, affixes)

        val baseName = stack.hoverName
        val legend = (if (weapon) LEGENDARY_WEAPONS else LEGENDARY_ARMOR).let { it[random.nextInt(it.size)] }
        val name: Component = when (rarity) {
            Rarity.LEGENDARY -> Component.translatable("relic.guildmark.legendary.$legend")
            Rarity.EPIC -> Component.translatable("relic.guildmark.name_suffixed", affixes.first().prefix, baseName,
                Component.translatable("relic.guildmark.suffix." + EPIC_SUFFIXES[random.nextInt(EPIC_SUFFIXES.size)]))
            Rarity.COMMON -> Component.translatable("relic.guildmark.name_plain", Component.translatable("relic.guildmark.sturdy"), baseName)
            else -> Component.translatable("relic.guildmark.name_plain", affixes.first().prefix, baseName)
        }
        stack.set(DataComponents.CUSTOM_NAME, name.copy().withStyle { it.withColor(rarity.color).withItalic(false) })
        val lore = mutableListOf<Component>(Component.translatable("relic.guildmark.kind", rarity.title).withStyle { it.withColor(rarity.color).withItalic(false) })
        for (affix in affixes) lore.add(affix.line.copy().withStyle { it.withColor(0xA8A8C0).withItalic(false) })
        if (rarity == Rarity.LEGENDARY) lore.add(Component.translatable("relic.guildmark.legendary.$legend.lore").withStyle { it.withColor(0x8C7A5A).withItalic(true) })
        stack.set(DataComponents.LORE, ItemLore(lore))
        if (rarity == Rarity.LEGENDARY) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        return stack
    }

    /** A relic rolled for a source of the given [strength] (roughly a rank, 0 to 6). */
    fun roll(level: ServerLevel, strength: Int, floor: Rarity = Rarity.COMMON): ItemStack =
        generate(level.random, Rarity.roll(level.random, strength, floor), level.registryAccess())

    /** A sealed cache that opens into a relic of at least [floor] rarity. */
    fun cache(floor: Rarity): ItemStack {
        val stack = ItemStack(ModItems.RELIC_CACHE.get())
        stack.set(ModDataComponents.CACHE_TIER.get(), floor.ordinal)
        return stack
    }

    /** The cache a finished contract of [rank] can pay out: better ranks seal better relics. */
    fun cacheForRank(rank: Int): ItemStack = cache(when {
        rank >= 5 -> Rarity.EPIC
        rank >= 3 -> Rarity.RARE
        rank >= 1 -> Rarity.UNCOMMON
        else -> Rarity.COMMON
    })

    /** Arms a champion with a relic it wields or wears, and always drops when it falls. */
    fun armChampion(level: ServerLevel, mob: net.minecraft.world.entity.Mob, tier: Int) {
        val relic = roll(level, tier + 1, Rarity.UNCOMMON)
        val slot = relic.get(DataComponents.EQUIPPABLE)?.slot() ?: run {
            val held = mob.getItemBySlot(EquipmentSlot.MAINHAND)
            if (held.isEmpty || held.`is`(Items.IRON_SWORD)) EquipmentSlot.MAINHAND else EquipmentSlot.OFFHAND
        }
        mob.setItemSlot(slot, relic)
        mob.setGuaranteedDrop(slot)
    }

    private fun isBoots(item: Item) = item == Items.IRON_BOOTS || item == Items.CHAINMAIL_BOOTS || item == Items.DIAMOND_BOOTS || item == Items.NETHERITE_BOOTS

    /** Stat affixes become attribute modifiers on top of the item's own. */
    private fun applyStats(stack: ItemStack, affixes: List<Affix>) {
        val equippable = stack.get(DataComponents.EQUIPPABLE)
        val group = if (equippable != null) EquipmentSlotGroup.bySlot(equippable.slot()) else EquipmentSlotGroup.MAINHAND
        val slotName = equippable?.slot()?.serializedName ?: "mainhand"
        var modifiers = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)
        fun add(affix: Affix, attribute: net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute>, amount: Double, op: AttributeModifier.Operation) {
            val id = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "relic_${affix.id}_$slotName")
            modifiers = modifiers.withModifierAdded(attribute, AttributeModifier(id, amount, op), group)
        }
        for (affix in affixes) when (affix) {
            Affix.KEEN -> add(affix, Attributes.ATTACK_DAMAGE, 2.0, AttributeModifier.Operation.ADD_VALUE)
            Affix.QUICK -> add(affix, Attributes.ATTACK_SPEED, 0.15, AttributeModifier.Operation.ADD_MULTIPLIED_BASE)
            Affix.VIGOR -> add(affix, Attributes.MAX_HEALTH, 4.0, AttributeModifier.Operation.ADD_VALUE)
            Affix.SWIFT -> add(affix, Attributes.MOVEMENT_SPEED, 0.06, AttributeModifier.Operation.ADD_MULTIPLIED_BASE)
            Affix.FORTUNE -> add(affix, Attributes.LUCK, 1.0, AttributeModifier.Operation.ADD_VALUE)
            else -> {}
        }
        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, modifiers)
    }

    private fun relicOf(stack: ItemStack?): RelicData? = stack?.get(ModDataComponents.RELIC.get())

    private fun armorCount(entity: LivingEntity, affix: Affix): Int = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
        .count { relicOf(entity.getItemBySlot(it))?.has(affix) == true }

    private fun isBoss(entity: LivingEntity): Boolean = entity is HollowKing || entity is WitherBoss || entity is EnderDragon ||
        entity.hasData(ModAttachments.LAIR_BOSS)

    /** Only real blows count: not thorns, magic or lightning (which relics cause themselves). */
    private fun fromWeapon(source: DamageSource): Boolean = source.entity != null &&
        !source.`is`(DamageTypes.THORNS) && !source.`is`(DamageTypes.MAGIC) && !source.`is`(DamageTypes.INDIRECT_MAGIC) && !source.`is`(DamageTypes.LIGHTNING_BOLT)

    /** Before damage lands: weapon bonuses for the attacker, armour protection for the victim. */
    fun onIncomingDamage(event: LivingIncomingDamageEvent) {
        val victim = event.entity
        var amount = event.amount
        if (fromWeapon(event.source)) relicOf(event.source.weaponItem)?.let { relic ->
            if (relic.has(Affix.EXECUTE) && victim.health < victim.maxHealth * 0.3f) amount *= 1.5f
            if (relic.has(Affix.KINGSLAYER) && isBoss(victim)) amount *= 1.35f
        }
        val warding = armorCount(victim, Affix.WARDING)
        if (warding > 0) amount *= (1f - 0.08f * warding).coerceAtLeast(0.6f)
        val feather = armorCount(victim, Affix.FEATHER)
        if (feather > 0 && event.source.`is`(DamageTypeTags.IS_FALL)) amount *= 0.5f
        if (amount != event.amount) event.amount = amount
    }

    /** After damage lands: on-hit effects of the attacker's relic, and thorns from the victim's. */
    fun onDamaged(event: LivingDamageEvent.Post) {
        val victim = event.entity
        val level = victim.level() as? ServerLevel ?: return
        val attacker = event.source.entity as? LivingEntity
        val dealt = event.inflictedDamage
        if (!fromWeapon(event.source)) return
        relicOf(event.source.weaponItem)?.let { relic ->
            if (relic.has(Affix.LIFESTEAL) && attacker != null) attacker.heal(dealt * 0.12f)
            if (relic.has(Affix.FROST)) victim.addEffect(MobEffectInstance(MobEffects.SLOWNESS, 60, 1))
            if (relic.has(Affix.VENOM)) victim.addEffect(MobEffectInstance(MobEffects.POISON, 80, 0))
            if (relic.has(Affix.EMBER)) victim.igniteForSeconds(4f)
            if (relic.has(Affix.THUNDER) && level.random.nextFloat() < 0.12f) {
                EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED)?.let { bolt ->
                    bolt.snapTo(victim.x, victim.y, victim.z)
                    bolt.setVisualOnly(true)
                    level.addFreshEntity(bolt)
                }
                // The blow that called the lightning just landed; let the bolt's damage through too
                victim.invulnerableTime = 0
                victim.hurtServer(level, level.damageSources().magic(), 6f)
            }
        }
        if (attacker != null && attacker != victim && event.source.directEntity == attacker) {
            val bramble = armorCount(victim, Affix.BRAMBLE)
            if (bramble > 0) {
                attacker.hurtServer(level, level.damageSources().thorns(victim), dealt * 0.15f * bramble)
                level.sendParticles(ParticleTypes.CRIT, attacker.x, attacker.y + 1.0, attacker.z, 6, 0.3, 0.3, 0.3, 0.1)
            }
        }
    }

    /** Opening a sealed cache. */
    fun open(level: ServerLevel, player: net.minecraft.world.entity.player.Player, cache: ItemStack): ItemStack {
        val floor = Rarity.of(cache.getOrDefault(ModDataComponents.CACHE_TIER.get(), 0))
        val relic = generate(level.random, Rarity.roll(level.random, floor.ordinal + 1, floor), level.registryAccess())
        level.playSound(null, player.blockPosition(), SoundEvents.CHEST_OPEN, SoundSource.PLAYERS, 1.0f, 1.2f)
        if (Rarity.of(relic.get(ModDataComponents.RELIC.get())!!.rarity) >= Rarity.EPIC) {
            level.playSound(null, player.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.7f, 1.4f)
        }
        level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, player.x, player.y + 1.0, player.z, 30, 0.4, 0.6, 0.4, 0.3)
        player.sendSystemMessage(Component.translatable("message.guildmark.cache_opened", relic.hoverName))
        return relic
    }
}
