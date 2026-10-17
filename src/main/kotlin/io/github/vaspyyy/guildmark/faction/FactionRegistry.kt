package io.github.vaspyyy.guildmark.faction

import com.mojang.serialization.Codec
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.magic.CatalystItem
import io.github.vaspyyy.guildmark.magic.School
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.registries.Registries
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
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
 * Everything the factions register, kept apart so the faction package stands on its own. Sounds are listed in
 * assets/guildmark/sounds.json (art/audio/make_faction_audio.py), the art comes from art/scripts/faction_art.py
 * and art/scripts/renegade.py.
 */
object FactionRegistry {
    val ITEMS: DeferredRegister.Items = DeferredRegister.createItems(Guildmark.MOD_ID)
    val ENTITIES: DeferredRegister<EntityType<*>> = DeferredRegister.create(Registries.ENTITY_TYPE, Guildmark.MOD_ID)
    val SOUNDS: DeferredRegister<SoundEvent> = DeferredRegister.create(Registries.SOUND_EVENT, Guildmark.MOD_ID)
    val COMPONENTS: DeferredRegister.DataComponents = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Guildmark.MOD_ID)
    val ATTACHMENTS: DeferredRegister<AttachmentType<*>> = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, Guildmark.MOD_ID)

    /** Where a player stands with each faction. Kept through death, synced to its player for the build and the character sheet. */
    val FACTIONS: DeferredHolder<AttachmentType<*>, AttachmentType<FactionState>> = ATTACHMENTS.register("factions") { ->
        AttachmentType.builder { -> FactionState() }
            .serialize(FactionState.CODEC.fieldOf("factions"))
            .copyOnDeath()
            .sync({ holder, player -> holder === player }, FactionState.STREAM_CODEC)
            .build()
    }

    /** The work between chapters each faction has given a player (see WorkOrders). Kept through death. */
    val WORK_ORDERS: DeferredHolder<AttachmentType<*>, AttachmentType<WorkOrders>> = ATTACHMENTS.register("work_orders") { ->
        AttachmentType.builder { -> WorkOrders() }
            .serialize(WorkOrders.CODEC.fieldOf("orders"))
            .copyOnDeath()
            .build()
    }

    /** The Guild Marks in a lifted purse, rolled when it was lifted. */
    val PURSE_MARKS: DeferredHolder<DataComponentType<*>, DataComponentType<Int>> = COMPONENTS.registerComponentType(
        "purse_marks",
        UnaryOperator<DataComponentType.Builder<Int>> { it.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT) },
    )

    /** Which stolen prize this is (see [Heist.prize]): its look and its name. */
    val PRIZE: DeferredHolder<DataComponentType<*>, DataComponentType<String>> = COMPONENTS.registerComponentType(
        "heist_prize",
        UnaryOperator<DataComponentType.Builder<String>> { it.persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8) },
    )

    // ---- Items ----

    val PURSE: DeferredItem<PurseItem> = ITEMS.registerItem("purse", ::PurseItem, UnaryOperator<Item.Properties> { it.stacksTo(16) })

    val HEIST_PRIZE: DeferredItem<HeistPrizeItem> = ITEMS.registerItem("heist_prize", ::HeistPrizeItem, UnaryOperator<Item.Properties> {
        it.stacksTo(1).rarity(Rarity.RARE).fireResistant()
    })

    /** The Starwatch's finest staff, Idris's gift at the end of its questline: a tier above the Echo Staff. */
    val STARWATCH_STAFF: DeferredItem<CatalystItem> = ITEMS.registerItem("starwatch_staff", { CatalystItem(School.SORCERY, 1.65f, it) }, UnaryOperator<Item.Properties> {
        it.stacksTo(1).rarity(Rarity.EPIC).fireResistant()
    })

    /** Hearthbrand, forged in the first Wardfire: Corra's gift. A longsword that comes enchanted with fire. */
    val HEARTHBRAND: DeferredItem<Item> = ITEMS.registerSimpleItem("hearthbrand", UnaryOperator<Item.Properties> {
        it.sword(ToolMaterial.NETHERITE, 3f, -2.4f).rarity(Rarity.EPIC).fireResistant()
    })

    /** Widow's Kiss, the first Right Hand's dagger: Odile's gift. */
    val WIDOWS_KISS: DeferredItem<Item> = ITEMS.registerSimpleItem("widows_kiss", UnaryOperator<Item.Properties> {
        it.sword(ToolMaterial.NETHERITE, 1f, -1.8f).rarity(Rarity.EPIC).fireResistant()
    })

    // ---- Creatures ----

    /** A floating ward-light: the Starwatch's test, and the renegade's summoned sentries (see WardLight). */
    val WARD_LIGHT: DeferredHolder<EntityType<*>, EntityType<WardLight>> = ENTITIES.register("ward_light") { ->
        EntityType.Builder.of(::WardLight, MobCategory.MISC)
            .sized(0.6f, 0.6f)
            .noLootTable()
            .noSave()
            .clientTrackingRange(10)
            .updateInterval(2)
            .build(key("ward_light"))
    }

    /** A knight of the Hearthsworn who spars, and stands at a held Wardfire (see SwornKnight). */
    val SWORN_KNIGHT: DeferredHolder<EntityType<*>, EntityType<SwornKnight>> = ENTITIES.register("sworn_knight") { ->
        EntityType.Builder.of(::SwornKnight, MobCategory.MISC)
            .sized(0.6f, 1.95f)
            .eyeHeight(1.62f)
            .noLootTable()
            .noSave()
            .clientTrackingRange(10)
            .build(key("sworn_knight"))
    }

    /** Silvan Marrow, the Unbound: the Starwatch's last chapter (see Renegade). */
    val RENEGADE: DeferredHolder<EntityType<*>, EntityType<Renegade>> = ENTITIES.register("renegade") { ->
        EntityType.Builder.of(::Renegade, MobCategory.MONSTER)
            .sized(0.7f, 2.3f)
            .eyeHeight(2.0f)
            .noLootTable()
            .noSave()
            .fireImmune()
            .clientTrackingRange(10)
            .build(key("renegade"))
    }

    // ---- Sounds ----

    private fun sound(name: String): DeferredHolder<SoundEvent, SoundEvent> =
        SOUNDS.register(name) { -> SoundEvent.createVariableRangeEvent(id(name)) }

    val WARD_LIGHT_HUM = sound("faction.ward_light.hum")
    val WARD_LIGHT_PASS = sound("faction.ward_light.pass")
    val WARD_LIGHT_BREAK = sound("faction.ward_light.break")
    val WARD_LIGHT_SPAWN = sound("faction.ward_light.spawn")
    val WARD_LIGHT_SHOOT = sound("faction.ward_light.shoot")
    val PICKPOCKET_LIFT = sound("faction.pickpocket.lift")
    val PICKPOCKET_CAUGHT = sound("faction.pickpocket.caught")
    val PURSE_OPEN = sound("faction.purse.open")
    val HEIST_SUSPICION = sound("faction.heist.suspicion")
    val HEIST_ALARM = sound("faction.heist.alarm")
    val HEIST_LOOT = sound("faction.heist.loot")
    val SPAR_SALUTE = sound("faction.spar.salute")
    val SPAR_YIELD = sound("faction.spar.yield")
    val HOLD_HORN = sound("faction.hold.horn")
    val HOLD_DRAIN = sound("faction.hold.drain")
    val HOLD_DAWN = sound("faction.hold.dawn")
    val RANK_STARWATCH = sound("faction.rank.starwatch")
    val RANK_HEARTHSWORN = sound("faction.rank.hearthsworn")
    val RANK_QUIET_HAND = sound("faction.rank.quiet_hand")
    val EXPELLED = sound("faction.expelled")
    val RENEGADE_STONES = sound("faction.renegade.stones")
    val RENEGADE_AMBIENT = sound("faction.renegade.ambient")
    val RENEGADE_HURT = sound("faction.renegade.hurt")
    val RENEGADE_SHOVE = sound("faction.renegade.shove")
    val RENEGADE_DEATH = sound("faction.renegade.death")
    val RENEGADE_VOICE_INTRO = sound("renegade.voice_intro")
    val RENEGADE_VOICE_PHASE2 = sound("renegade.voice_phase2")
    val RENEGADE_VOICE_DEATH = sound("renegade.voice_death")

    /** The sound of rising in (or joining) [faction]. */
    fun rankSound(faction: Faction): SoundEvent = when (faction) {
        Faction.STARWATCH -> RANK_STARWATCH.get()
        Faction.HEARTHSWORN -> RANK_HEARTHSWORN.get()
        Faction.QUIET_HAND -> RANK_QUIET_HAND.get()
    }

    /** The faction rewards, in the order the creative tab shows them. */
    val TAB: List<DeferredItem<out Item>> = listOf(STARWATCH_STAFF, HEARTHBRAND, WIDOWS_KISS, PURSE)

    fun id(name: String): Identifier = Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name)

    private fun key(name: String): ResourceKey<EntityType<*>> = ResourceKey.create(Registries.ENTITY_TYPE, id(name))
}
