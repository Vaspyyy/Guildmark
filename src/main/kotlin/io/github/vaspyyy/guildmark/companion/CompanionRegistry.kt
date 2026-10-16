package io.github.vaspyyy.guildmark.companion

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.magic.CatalystItem
import io.github.vaspyyy.guildmark.magic.School
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.Rarity
import net.minecraft.world.item.ToolMaterial
import net.neoforged.neoforge.attachment.AttachmentType
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister
import net.neoforged.neoforge.registries.NeoForgeRegistries
import java.util.function.UnaryOperator

/**
 * Everything the companions register. Sounds are listed in assets/guildmark/sounds.json (art/audio/make_companion_audio.py
 * and the voices of art/audio/make_cast_voices.py); the keepsakes are painted by art/scripts/companion_art.py and the
 * companions themselves are looks of the townsfolk model (art/scripts/townsfolk.py, role "companion").
 */
object CompanionRegistry {
    val ITEMS: DeferredRegister.Items = DeferredRegister.createItems(Guildmark.MOD_ID)
    val ENTITIES: DeferredRegister<EntityType<*>> = DeferredRegister.create(Registries.ENTITY_TYPE, Guildmark.MOD_ID)
    val SOUNDS: DeferredRegister<SoundEvent> = DeferredRegister.create(Registries.SOUND_EVENT, Guildmark.MOD_ID)
    val ATTACHMENTS: DeferredRegister<AttachmentType<*>> = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, Guildmark.MOD_ID)

    /** A player's companions. Kept through death, synced to its player. */
    val COMPANIONS: DeferredHolder<AttachmentType<*>, AttachmentType<CompanionState>> = ATTACHMENTS.register("companions") { ->
        AttachmentType.builder { -> CompanionState() }
            .serialize(CompanionState.CODEC.fieldOf("companions"))
            .copyOnDeath()
            .sync({ holder, player -> holder === player }, CompanionState.STREAM_CODEC)
            .build()
    }

    /**
     * A companion in the world. Never saved: the player's [CompanionState] is what lasts, and Companions puts them
     * back beside the player (or where they wait) whenever they're missing.
     */
    val COMPANION: DeferredHolder<EntityType<*>, EntityType<CompanionEntity>> = ENTITIES.register("companion") { ->
        EntityType.Builder.of(::CompanionEntity, MobCategory.MISC)
            .sized(0.6f, 1.95f)
            .eyeHeight(1.62f)
            .noLootTable()
            .noSave()
            .clientTrackingRange(10)
            .build(ResourceKey.create(Registries.ENTITY_TYPE, id("companion")))
    }

    // ---- The keepsakes, given when a companion is sworn ----

    /** Bram's: his old captain's greatsword, reforged. */
    val GREYHART: DeferredItem<Item> = ITEMS.registerSimpleItem("greyhart", UnaryOperator<Item.Properties> {
        it.sword(ToolMaterial.NETHERITE, 6f, -3.2f).rarity(Rarity.EPIC).fireResistant()
    })

    /** Ysolde's: the Lamplighters' censer, a flame in it that never goes out. */
    val LAMPLIGHTERS_CENSER: DeferredItem<CatalystItem> = ITEMS.registerItem("lamplighters_censer", { CatalystItem(School.PRAYER, 1.65f, it) }, UnaryOperator<Item.Properties> {
        it.stacksTo(1).rarity(Rarity.EPIC).fireResistant()
    })

    /** Kestrel's: a recurve of black yew that hits harder than any bow of the shops. */
    val THORNWOOD_BOW: DeferredItem<ThornwoodBow> = ITEMS.registerItem("thornwood_bow", ::ThornwoodBow, UnaryOperator<Item.Properties> {
        it.durability(768).enchantable(1).rarity(Rarity.EPIC)
    })

    /** Lucan's: an apprentice's staff with the star he unpicked from his robe sewn back on. */
    val UNPICKED_STAR: DeferredItem<CatalystItem> = ITEMS.registerItem("unpicked_star", { CatalystItem(School.SORCERY, 1.6f, it) }, UnaryOperator<Item.Properties> {
        it.stacksTo(1).rarity(Rarity.EPIC).fireResistant()
    })

    /** The keepsakes, in the order the creative tab shows them. */
    val TAB: List<DeferredItem<out Item>> = listOf(GREYHART, LAMPLIGHTERS_CENSER, THORNWOOD_BOW, UNPICKED_STAR)

    // ---- Sounds ----

    private fun sound(name: String): DeferredHolder<SoundEvent, SoundEvent> =
        SOUNDS.register(name) { -> SoundEvent.createVariableRangeEvent(id(name)) }

    val APPROVE = sound("companion.approve")
    val DISAPPROVE = sound("companion.disapprove")
    val DOWNED = sound("companion.downed")
    val REVIVE = sound("companion.revive")
    val CHAPTER = sound("companion.chapter")
    val SWORN = sound("companion.sworn")

    fun id(name: String): Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name)
}

/** Kestrel's Thornwood Bow: a bow whose arrows land half again as hard. */
class ThornwoodBow(properties: Properties) : BowItem(properties) {
    override fun shootProjectile(
        shooter: LivingEntity, projectile: Projectile, index: Int, velocity: Float, inaccuracy: Float, angle: Float, target: LivingEntity?,
    ) {
        (projectile as? AbstractArrow)?.setBaseDamage(DAMAGE)
        super.shootProjectile(shooter, projectile, index, velocity, inaccuracy, angle, target)
    }

    private companion object {
        /** A plain arrow's is 2. */
        const val DAMAGE = 3.0
    }
}
