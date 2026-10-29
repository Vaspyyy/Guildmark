package io.github.vaspyyy.guildmark.tales

import com.mojang.brigadier.context.CommandContext
import io.github.vaspyyy.guildmark.tales.templates.CartCommands
import io.github.vaspyyy.guildmark.tales.templates.CartWatch
import io.github.vaspyyy.guildmark.tales.templates.ChildCommands
import io.github.vaspyyy.guildmark.tales.templates.ChildWatch
import io.github.vaspyyy.guildmark.townsfolk.Residents
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.player.Player
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent

/**
 * What the second group of tales (T2a: the child, the handcart) adds to the engine, in one place so that `Tales.register` has one line for
 * it: the child's and the cart's looks at the road once a second ([ChildWatch], [CartWatch]), the forgetting when the server stops,
 * `/guildmark tale child` and `tale cart` (each tale's record in words), `tale scene wheel`, and `/guildmark tale protect list|clear`, which
 * shows the households the tales protect from leaving and lets them go (6.5). It also tells the tales of a monster killed and a wardfire
 * kindled ([killed], [kindled]), which the cart's camp counts and the mine's fire will hear (T5).
 */
internal object TalesT2a {
    /** Called once from `Tales.register`, before the commands are built. */
    fun register() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { if (it.server.tickCount % 20 == 11) TaleEngine.guarded("the child's watch") { ChildWatch.watch(it.server) } }
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { if (it.server.tickCount % 20 == 13) TaleEngine.guarded("the cart's watch") { CartWatch.watch(it.server) } }
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent::class.java) { _ ->
            ChildWatch.forgetAll()
            CartWatch.forgetAll()
        }
        ChildCommands.register()
        CartCommands.register()
        TaleCommand.sub += { tale ->
            tale.then(
                Commands.literal("protect")
                    .executes { list(it) }
                    .then(Commands.literal("list").executes { list(it) })
                    .then(Commands.literal("clear").executes { clear(it) }),
            )
        }
    }

    /** From `Tales.onKill`: a monster [killer] (or their companion) killed is told to the tales that count kills (the cart's night on the road). */
    fun killed(level: ServerLevel, victim: LivingEntity, killer: Player) {
        if (victim.type.category != MobCategory.MONSTER || !Tales.listening(level, Event.Kind.KILL)) return
        Tales.tell(level, Event.Kill(victim, killer))
    }

    /** From `Tales.kindled`: a wardfire [player] kindled at [pos] is told to the tales that hear it (the cart's camp; the mine's fire, T5). */
    fun kindled(level: ServerLevel, pos: BlockPos, player: ServerPlayer) {
        if (!Tales.listening(level, Event.Kind.KINDLED)) return
        Tales.tell(level, Event.Kindled(pos, player))
    }

    /** The households this capital's tales protect from leaving: the family, the lodging and the market day the protection ends. */
    private fun list(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        TaleCommand.off()?.let { TaleCommand.say(source, TaleCommand.grey(it)) }
        if (!Tales.live) return 0
        val here = TaleCommand.here(context) ?: return 0
        val level = source.level
        val town = TaleData.get(level).find(here.key)
        val today = TaleEngine.today(level, here.key)
        if (town == null || town.protect.isEmpty()) return TaleCommand.say(source, TaleCommand.grey("No household in ${here.name} is protected by a tale."))
        TaleCommand.say(source, TaleCommand.header("${here.name}: households a tale protects (today is market day $today)"))
        val people = Residents.get(level).of(here.capital.center)
        for (p in town.protect.sortedBy { it.until }) {
            val family = people.filter { it.home == p.home && it.alive }.let { home -> (home.firstOrNull { it.role.adult } ?: home.firstOrNull())?.family }
            val left = p.until - today
            TaleCommand.say(
                source,
                TaleCommand.plain("lodging ${p.home}: ").append(TaleCommand.label(family ?: "(nobody lives there)"))
                    .append(TaleCommand.grey(if (left >= 0) ", protected to day ${p.until} ($left more)" else ", ran out on day ${p.until}")),
            )
        }
        return town.protect.size
    }

    /** Lets every household of this capital go from its protection: the comings may send them away again. */
    private fun clear(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        TaleCommand.off()?.let { TaleCommand.say(source, TaleCommand.grey(it)) }
        if (!Tales.live) return 0
        val here = TaleCommand.here(context) ?: return 0
        val level = source.level
        val data = TaleData.get(level)
        val town = data.find(here.key)
        if (town == null || town.protect.isEmpty()) return TaleCommand.say(source, TaleCommand.grey("No household in ${here.name} is protected by a tale."))
        val n = town.protect.size
        town.protect.clear()
        data.changed()
        return TaleCommand.say(source, TaleCommand.plain("Let $n household${if (n == 1) "" else "s"} of ${here.name} go from the protection of the tales."))
    }
}
