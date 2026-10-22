package io.github.vaspyyy.guildmark.tales

import io.github.vaspyyy.guildmark.registry.ModItems
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomModelData

/**
 * What of the tales' art exists, and the one place that turns a look into something a person can hold (living-world/stories/
 * design.md 13.2 and 13.8). Part G drew the pictures (`items/keepsake.json`, `items/tale_prop.json`, `items/tale_quill.json`);
 * the sets below say which looks are in those files, so code asks them and a look that is not drawn falls back to something
 * that exists, with nothing else changed. Nothing here is loaded by the game itself: a missing picture is a plain item, never a crash.
 */
object TaleArt {
    /**
     * The looks of the `guildmark:tale_prop` item that are drawn (every `when` of `items/tale_prop.json`): what a person holds
     * in a scene, and nothing else (the item is in no creative tab and no loot).
     */
    val PROPS: Set<String> = setOf(
        "note_sealed", "ransom_pouch", "slate", "thread_paper", "locket", "blanket", "washing",
        "lantern_paper_dark", "lantern_paper_lit", "miners_lamp_dark", "miners_lamp_lit", "posy",
    )

    /**
     * The sixteen looks of `guildmark:keepsake` that are drawn (every `when` of `items/keepsake.json`): a template's own id for a
     * full keepsake, and `<template>_c` for the four lesser ones that are a different object. A tale with no look here (the
     * probe) hands a keepsake that is drawn as the contract is, the file's fallback.
     */
    val KEEPSAKES: Set<String> = setOf(
        "note", "note_c", "chair", "wagon", "child", "fence", "lute", "moot", "cart", "lantern",
        "informer", "informer_c", "locket", "locket_c", "brother", "brother_c",
    )

    /** The tales' sounds that are recorded (Part H's); none yet, so the stage and the banner play what stands in. */
    val SOUNDS: Set<String> = setOf()

    /**
     * What a person carries as the prop [look]: the drawn `tale_prop` of that look when it is in [PROPS], a keepsake's own picture
     * when it is one of the two looks used again as props (`child`, the wooden horse, and `informer`, the tally stick, 13.2.3),
     * and the vanilla [standIn] when it is not drawn (13.2.3 names one for each look). A fresh stack every call.
     */
    fun prop(look: String, standIn: Item): ItemStack = when {
        look in PROPS -> ItemStack(ModItems.TALE_PROP.get()).also { it.set(DataComponents.CUSTOM_MODEL_DATA, model(look)) }
        look in KEEPSAKES -> ItemStack(ModItems.KEEPSAKE.get()).also { it.set(DataComponents.CUSTOM_MODEL_DATA, model(look)) }
        else -> ItemStack(standIn)
    }

    /** The look of the keepsake of [template] that ended [ending]: its lesser one if C has one drawn, else its own; [template] itself if it is not drawn at all. */
    fun keepsake(template: String, ending: String): String =
        if (ending == Ending.C && "${template}_c" in KEEPSAKES) "${template}_c" else template

    /** The custom model data a look is chosen by, as the heist prizes' is (`faction/FactionItems.kt`): the look is its one string. */
    internal fun model(look: String): CustomModelData = CustomModelData(listOf(), listOf(), listOf(look), listOf())
}
