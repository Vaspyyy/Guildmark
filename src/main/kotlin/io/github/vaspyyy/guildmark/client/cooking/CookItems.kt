package io.github.vaspyyy.guildmark.client.cooking

import net.minecraft.client.renderer.item.ItemModelResolver
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.util.RandomSource
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level

/**
 * What a renderer needs of the items on a station (the chunks in a pot, the pieces in a pan, the items on a tray or a heap, the
 * dishes on a plate), found once and kept: a flat picture of an item, which is its sprite's place in the atlas it lives in, and its
 * full model for the few things that are drawn as the game draws an item (a dish on a plate).
 *
 * A pot of twelve chunks drawn as twelve full item models would be several hundred quads each frame, for a thing 2 px across; drawn
 * as the item's own sprite on one quad it is twelve. The sprite is the item model's particle material (an item's own picture
 * for a flat item; a block item's texture), which is what a chunk of it looks like. What is kept is dropped when the resources
 * reload (the atlas it points into is made again).
 */
object CookItems {
    /** An item's picture: the corners of its sprite in [atlas], and which atlas. */
    class Flat(val u0: Float, val v0: Float, val u1: Float, val v1: Float, val atlas: Identifier)

    private val flats = HashMap<Int, Flat>()
    private val states = HashMap<Int, ItemStackRenderState>()

    /** What a missing picture is: nothing is drawn for it. */
    private val NONE = Flat(0f, 0f, 0f, 0f, Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/items.png"))

    /** The picture of the item whose registry id is [id], or null if it has none (air, an id the game does not know, a model with no sprite). */
    fun flat(id: Int, resolver: ItemModelResolver, level: Level?): Flat? {
        val found = flats[id]
        if (found != null) return if (found === NONE) null else found
        val made = makeFlat(id, resolver, level)
        flats[id] = made ?: NONE
        return made
    }

    private fun makeFlat(id: Int, resolver: ItemModelResolver, level: Level?): Flat? {
        val item = BuiltInRegistries.ITEM.byId(id)
        if (item === Items.AIR) return null
        val state = ItemStackRenderState()
        resolver.updateForTopItem(state, ItemStack(item), ItemDisplayContext.FIXED, level, null, id)
        if (state.isEmpty) return null
        val material = state.pickParticleMaterial(RandomSource.create(id.toLong())) ?: return null
        val sprite = material.sprite()
        return Flat(sprite.u0, sprite.v0, sprite.u1, sprite.v1, sprite.atlasLocation())
    }

    /** The full model of the item whose registry id is [id], as the game draws an item standing in the world; null if it has none. */
    fun state(id: Int, resolver: ItemModelResolver, level: Level?): ItemStackRenderState? {
        val found = states[id]
        if (found != null) return if (found.isEmpty) null else found
        val item = BuiltInRegistries.ITEM.byId(id)
        val state = ItemStackRenderState()
        if (item !== Items.AIR) resolver.updateForTopItem(state, ItemStack(item), ItemDisplayContext.FIXED, level, null, id)
        states[id] = state
        return if (state.isEmpty) null else state
    }

    /**
     * The full model of [stack] (a dish as it looks today, a stack that is not just its item), as the game draws an item standing in the
     * world; null if it has none. A new one is made each time, so the caller keeps it for as long as the stack is the same one.
     */
    fun stateOf(stack: ItemStack, resolver: ItemModelResolver, level: Level?, seed: Int): ItemStackRenderState? {
        if (stack.isEmpty) return null
        val state = ItemStackRenderState()
        resolver.updateForTopItem(state, stack, ItemDisplayContext.FIXED, level, null, seed)
        return if (state.isEmpty) null else state
    }

    /** How many times the resources have reloaded: a model a block keeps for itself (a dish on a plate) is made again when this has moved on. */
    var generation = 0
        private set

    /** The resources reloaded: every picture is looked up again. */
    fun clear() {
        flats.clear()
        states.clear()
        generation++
    }
}
