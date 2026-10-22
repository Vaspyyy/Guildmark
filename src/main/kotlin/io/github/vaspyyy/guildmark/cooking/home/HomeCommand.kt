package io.github.vaspyyy.guildmark.cooking.home

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.cooking.CookRegistry
import io.github.vaspyyy.guildmark.cooking.vessel.Stations
import io.github.vaspyyy.guildmark.homestead.Homestead
import io.github.vaspyyy.guildmark.homestead.Homesteads
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.storage.loot.LootParams
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets
import net.minecraft.world.level.storage.loot.parameters.LootContextParams
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent

/**
 * `/guildmark cook homestead ...` and `/guildmark cook herb ...` (living-world/cook/design.md 6.7, 10.3; operators only, like
 * all of `cook`): the homestead's kitchen as Part K keeps it, and the herb. They hang off the root that Part 0's `CookCommand`
 * makes (Brigadier joins nodes of the same name), and are registered from [HomesteadKitchens.register].
 *
 * - `cook homestead` says, for the homestead you stand on (else your own, else the nearest of yours), every item of its kitchen
 *   with what became of it: where its station stands, why it was kept or not found, or that it is still to be looked at.
 * - `cook homestead kit` (or `kit preview`) says what each cell would do if the kitchen were fitted now, and places nothing.
 * - `cook homestead kit refit` takes back the verdicts that left something out (kept, not found) and fits the kitchen again, at once.
 * - `cook homestead free` says whether the fire of the station you look at is free, and for how long yet.
 * - `cook herb grow` ripens the herb you look at; `cook herb drops [n]` rolls the wild herb's loot n times and says what fell.
 */
object HomeCommand {
    /** How far the herb you look at may be, blocks. */
    private const val REACH = 6.0

    /** The rolls of `herb drops` when none is given, and the most that may be asked. */
    private const val ROLLS = 2000
    private const val MOST_ROLLS = 200000

    fun init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { register(it.dispatcher) }
    }

    private fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val kit = Commands.literal("kit")
            .executes { kit(it, false) }
            .then(Commands.literal("preview").executes { kit(it, false) })
            .then(Commands.literal("refit").executes { kit(it, true) })
        val homestead = Commands.literal("homestead")
            .executes { status(it) }
            .then(kit)
            .then(Commands.literal("free").executes { free(it) })
        val herb = Commands.literal("herb")
            .then(Commands.literal("grow").executes { grow(it) })
            .then(
                Commands.literal("drops").executes { drops(it, ROLLS) }
                    .then(Commands.argument("rolls", IntegerArgumentType.integer(1, MOST_ROLLS)).executes { drops(it, IntegerArgumentType.getInteger(it, "rolls")) }),
            )
        dispatcher.register(
            Commands.literal("guildmark")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("cook").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).then(homestead).then(herb)),
        )
    }

    // ---- The homestead's kitchen ----

    /** The homestead [player] means: the one whose land they stand on, else their own, else the nearest they belong to; null after saying why there is none. */
    private fun target(context: CommandContext<CommandSourceStack>): Homestead? {
        val source = context.source
        val player = source.player
        if (player == null) {
            source.sendFailure(Component.literal("Run this as a player, on a homestead."))
            return null
        }
        val level = source.level
        Homesteads.at(level, player.blockPosition())?.let { return it }
        Homesteads.of(level, player.uuid)?.let { return it }
        val near = Homesteads.all(level)
            .filter { Homesteads.belongsTo(level, it, player) }
            .minByOrNull { it.post.distSqr(player.blockPosition()) }
        if (near == null) source.sendFailure(Component.literal("Stand on a homestead's land first (or hold a charter, or belong to a party that does)."))
        return near
    }

    private fun status(context: CommandContext<CommandSourceStack>): Int {
        val home = target(context) ?: return 0
        val source = context.source
        val level = source.level
        say(source, header("Homestead ${home.id}: ${home.ownerName}, tier ${home.tier}"))
        if (!HomesteadKitchens.on()) say(source, grey("Homestead kitchens are off (setting), or homesteads are: nothing is fitted, lit or chalked, and nothing is written."))
        if (home.tier < 1) {
            say(source, grey("Nothing is built yet, so there is no kitchen."))
            return 1
        }
        var done = 0
        var kept = 0
        var missing = 0
        var waiting = 0
        val rows = HomesteadKitchens.rows(level, home)
        for (row in rows) {
            val what = when (row.status) {
                HomeKitchenData.Status.DONE -> {
                    done++
                    word("done", ChatFormatting.GREEN)
                }
                HomeKitchenData.Status.KEPT -> {
                    kept++
                    word("kept", ChatFormatting.YELLOW)
                }
                HomeKitchenData.Status.NOT_FOUND -> {
                    missing++
                    word("not found", ChatFormatting.RED)
                }
                null -> {
                    waiting++
                    word("to be looked at", ChatFormatting.GRAY)
                }
            }
            val line = line().append(label(row.item.key.padEnd(10))).append(what).append(plain("  ${row.item.thing.word}"))
            row.where?.let { line.append(grey("  at ${it.x} ${it.y} ${it.z}")) }
            if (row.cells.size > 1) line.append(grey("  (${row.cells.size} cells)"))
            if (row.note.isNotEmpty()) line.append(grey("  ${row.note}"))
            say(source, line)
        }
        say(source, line().append(label("Items: ")).append(plain("$done done, $kept kept, $missing not found, $waiting to be looked at")))
        val board = rows.firstOrNull { it.item.key == "f.menu" && it.status == HomeKitchenData.Status.DONE }?.where
        if (board != null) say(source, line().append(label("Pantry board: ")).append(plain("${HomesteadKitchens.pantryLines(level, board).size} lines at ${board.x} ${board.y} ${board.z}")))
        return 1
    }

    private fun kit(context: CommandContext<CommandSourceStack>, refit: Boolean): Int {
        val home = target(context) ?: return 0
        val source = context.source
        val level = source.level
        if (!HomesteadKitchens.on()) {
            source.sendFailure(Component.literal("Homestead kitchens are off (setting), or homesteads are: nothing is fitted until they are on."))
            return 0
        }
        if (home.tier < 1) {
            source.sendFailure(Component.literal("Nothing is built on this homestead yet, so there is no kitchen to fit."))
            return 0
        }
        if (refit) {
            val taken = HomesteadKitchens.retake(level, home)
            val fitted = HomesteadKitchens.raise(level, home)
            say(source, header("Refit of homestead ${home.id}"))
            say(source, line().append(label("Looked at again: ")).append(plain("$taken verdicts taken back (kept or not found)")))
            say(source, line().append(label("Fitted: ")).append(plain("$fitted items had a station set in them")))
            return fitted
        }
        say(source, header("Kit of homestead ${home.id}: what fitting it now would do"))
        var any = false
        for (row in HomesteadKitchens.rows(level, home)) {
            if (row.status == HomeKitchenData.Status.DONE) continue
            any = true
            val verdict = when (row.status) {
                HomeKitchenData.Status.KEPT -> "kept before (refit looks again)"
                HomeKitchenData.Status.NOT_FOUND -> "not found before (refit looks again)"
                else -> "to be looked at"
            }
            say(source, line().append(label(row.item.key)).append(plain("  ${row.item.thing.word}  ")).append(grey(verdict)))
            for (text in HomesteadKitchens.preview(level, home, row.item)) say(source, grey("    $text"))
        }
        if (!any) say(source, grey("Every item has a station in it already."))
        return 1
    }

    private fun free(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.player
        if (player == null) {
            source.sendFailure(Component.literal("Run this as a player, looking at a station."))
            return 0
        }
        val level = source.level
        val station = Stations.lookedAt(player)
        if (station == null) {
            source.sendFailure(Component.literal("Look at a station first (within six blocks)."))
            return 0
        }
        val fire = HomesteadKitchens.fireAt(level, station.blockPos)
        say(source, header("Free fire at ${station.blockPos.x} ${station.blockPos.y} ${station.blockPos.z}"))
        if (!HomesteadKitchens.on()) say(source, grey("Homestead kitchens are off (setting), or homesteads are: no fire is free."))
        val home = fire.home
        if (home == null) {
            say(source, line().append(label("Land: ")).append(plain("not on a homestead's land, so its fire burns fuel")))
            return 1
        }
        say(source, line().append(label("Land: ")).append(plain("homestead ${home.id}, ${home.ownerName}'s, tier ${home.tier}")))
        val verdict = when {
            fire.free && fire.since == 0L -> "one of its people is on the land now: free"
            fire.free -> "its people were last on the land ${fire.since / 20} s ago, within the ${fire.grace / 20} s grace: free"
            fire.since < 0L -> "none of its people has been on the land since this world was loaded (or since the last reset): it burns fuel"
            else -> "its people were last on the land ${fire.since / 20} s ago, past the ${fire.grace / 20} s grace: it burns fuel"
        }
        say(source, line().append(label("Fire: ")).append(plain(verdict)))
        say(source, line().append(label("Asked so far: ")).append(plain("${HomesteadKitchens.asks()} times, ${HomesteadKitchens.grants()} free")))
        return 1
    }

    // ---- The herb ----

    private fun grow(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player: ServerPlayer = source.player ?: run {
            source.sendFailure(Component.literal("Run this as a player, looking at a herb."))
            return 0
        }
        val level = source.level
        val hit = player.pick(REACH, 1.0f, false)
        if (hit !is BlockHitResult || hit.type != HitResult.Type.BLOCK) {
            source.sendFailure(Component.literal("Look at a herb first (within six blocks)."))
            return 0
        }
        val pos = hit.blockPos
        val state = level.getBlockState(pos)
        if (state.block !is HerbBlock) {
            source.sendFailure(Component.literal("That is not a herb."))
            return 0
        }
        if (state.getValue(HerbBlock.AGE) >= HerbBlock.MAX) {
            say(source, grey("It is ripe already."))
            return 1
        }
        level.setBlock(pos, state.setValue(HerbBlock.AGE, HerbBlock.MAX), Block.UPDATE_ALL)
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.x + 0.5, pos.y + 0.4, pos.z + 0.5, 6, 0.25, 0.2, 0.25, 0.0)
        say(source, plain("The herb at ${pos.x} ${pos.y} ${pos.z} is ripe."))
        return 1
    }

    /** Rolls the loot the wild herb adds to grass and ferns [rolls] times and says how many sprigs fell, against the 4% the data gives. */
    private fun drops(context: CommandContext<CommandSourceStack>, rolls: Int): Int {
        val source = context.source
        val level = source.level
        val at = source.position
        val registries = level.server.reloadableRegistries()
        say(source, header("Wild herb: $rolls breaks"))
        for ((name, state) in listOf("inject/herb_wild" to Blocks.SHORT_GRASS.defaultBlockState(), "inject/herb_wild_tall" to Blocks.TALL_GRASS.defaultBlockState())) {
            val table = registries.getLootTable(ResourceKey.create(Registries.LOOT_TABLE, Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, name)))
            val params = LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, at)
                .withParameter(LootContextParams.BLOCK_STATE, state)
                .withParameter(LootContextParams.TOOL, ItemStack.EMPTY)
                .create(LootContextParamSets.BLOCK)
            var sprigs = 0
            var breaks = 0
            for (i in 0 until rolls) {
                var fell = 0
                for (stack in table.getRandomItems(params)) if (stack.`is`(CookRegistry.HERB_SPRIG.get())) fell += stack.count
                if (fell > 0) breaks++
                sprigs += fell
            }
            val share = breaks * 100.0 / rolls
            say(source, line().append(label("${state.block.name.string}: ")).append(plain("$sprigs sprigs fell in $breaks of $rolls breaks (${"%.2f".format(share)}%)")))
        }
        return 1
    }

    // ---- Words ----

    private fun line(): MutableComponent = Component.empty()
    private fun plain(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.WHITE)
    private fun grey(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GRAY)
    private fun label(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.YELLOW)
    private fun word(s: String, color: ChatFormatting): MutableComponent = Component.literal(s).withStyle(color)
    private fun header(s: String): MutableComponent = Component.literal("== $s ==").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)

    private fun say(source: CommandSourceStack, c: Component) {
        source.sendSuccess({ c }, false)
    }
}
