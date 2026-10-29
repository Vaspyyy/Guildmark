package io.github.vaspyyy.guildmark.village.live.trouble

import io.github.vaspyyy.guildmark.block.VillageBoards
import io.github.vaspyyy.guildmark.outlaw.Raids
import io.github.vaspyyy.guildmark.registry.ModAttachments
import io.github.vaspyyy.guildmark.townsfolk.Resident
import io.github.vaspyyy.guildmark.townsfolk.Townsperson
import io.github.vaspyyy.guildmark.village.live.Chron
import io.github.vaspyyy.guildmark.village.live.Dead
import io.github.vaspyyy.guildmark.village.live.VillageData
import io.github.vaspyyy.guildmark.village.live.VillageRec
import io.github.vaspyyy.guildmark.village.live.folk.VillageAdopt
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.DamageTypeTags
import net.minecraft.world.entity.raid.Raider

/**
 * A village's dead and its chronicle (living-world/villages/design.md 11.4). A resident of a village who dies (`Residents.pass`, by
 * way of [io.github.vaspyyy.guildmark.village.live.Villages.died]) goes in its dead list, `Dead(name, day, cause, home)`, the last
 * 24, with the cause the death knew; the chronicle gets a line, and the dead wait for their candle at the next dawn ([Memorial]).
 *
 * A cause is kept as a word of its own ([SIEGE], [RAID], [HELD], [LOST], [FALL], [DROWNED], [BURNED], [SLAIN], or "" when it is not known)
 * and said in the reader's language ([words]); a siege's death keeps the side the horde came from ("siege:east"). A cause another part
 * gives in words of its own is said as given.
 */
object Deaths {
    const val SIEGE = "siege"
    const val RAID = "raid"
    /** Died held captive in an outlaws' camp. */
    const val HELD = "held"
    /** Taken by a raid and never found. */
    const val LOST = "lost"
    const val FALL = "fall"
    const val DROWNED = "drowned"
    const val BURNED = "burned"
    const val SLAIN = "slain"

    private val KNOWN = setOf(SIEGE, RAID, HELD, LOST, FALL, DROWNED, BURNED, SLAIN)

    /** How [p] died, as their death knew it: the siege's horde (and the side it came from), raiders, a captive's end, a fall, water, fire, a blow. */
    fun cause(level: ServerLevel, p: Townsperson): String {
        if (p.captiveOf >= 0) return HELD
        val source = p.lastDamageSource ?: return ""
        val by = source.entity
        if (by != null) {
            if (by.hasData(ModAttachments.SIEGE) && by.getData(ModAttachments.SIEGE) >= 0) {
                return SiegeHorde.sideOf(level, p.blockPosition())?.let { "$SIEGE:$it" } ?: SIEGE
            }
            if (by.entityTags().contains(Raids.TAG) || by is Raider && by.currentRaid != null) return RAID
        }
        return when {
            source.`is`(DamageTypeTags.IS_FALL) -> FALL
            source.`is`(DamageTypeTags.IS_DROWNING) -> DROWNED
            source.`is`(DamageTypeTags.IS_FIRE) -> BURNED
            by != null -> SLAIN
            else -> ""
        }
    }

    /**
     * [r], one of [rec]'s folk, died ([Resident.died] is the day) of [cause]: into the dead list and the chronicle, and waiting for a
     * candle (11.4). With trouble off nothing is written, as before.
     */
    internal fun died(level: ServerLevel, rec: VillageRec, r: Resident, cause: String) {
        if (!Trouble.on()) return
        val day = if (r.died >= 0) r.died else VillageBoards.day(level)
        bury(level, rec, r.name, day, cause, r.home, r.id)
    }

    /** [name] is entered among [rec]'s dead (by a death, or `village dead add`), noted in its chronicle and waits for a candle. */
    internal fun bury(level: ServerLevel, rec: VillageRec, name: String, day: Long, cause: String, home: Int, id: Int) {
        rec.bury(Dead(name, day, cause, home))
        rec.note(day, "died", name)
        VillageData.get(level).setDirty()
        val data = TroubleData.get(level)
        val kept = data.of(rec.key)
        kept.waiting.removeIf { it.name == name && it.day == day }
        kept.waiting.add(TroubleData.Mourned(name, id, home, day))
        while (kept.waiting.size > VillageRec.MAX_DEAD) kept.waiting.removeAt(0)
        data.setDirty()
    }

    /** [cause] in words ("fell in the siege, on the east side"); empty for one not known. */
    fun words(cause: String): MutableComponent {
        if (cause.isEmpty()) return Component.empty()
        val (head, tail) = cause.indexOf(':').let { if (it < 0) cause to "" else cause.substring(0, it) to cause.substring(it + 1) }
        if (head == SIEGE && tail.isNotEmpty()) return Component.translatable("village.guildmark.dead.cause.siege_side", Component.translatable("direction.guildmark.$tail"))
        if (head in KNOWN) return Component.translatable("village.guildmark.dead.cause.$head")
        return Component.literal(cause)
    }

    /** "Joran Marlow, fell in the siege, on the east side (day 12)": one of the dead, as the stone and the commands read them. */
    fun line(d: Dead): MutableComponent {
        val out = Component.literal(d.name).withStyle(ChatFormatting.WHITE)
        val how = words(d.cause)
        if (d.cause.isNotEmpty()) out.append(Component.literal(", ").withStyle(ChatFormatting.GRAY)).append(how.withStyle(ChatFormatting.GRAY))
        out.append(Component.translatable("village.guildmark.dead.on_day", d.day).withStyle(ChatFormatting.DARK_GRAY))
        return out
    }

    /** One line of the chronicle, as `village info` and `village dead` read it. */
    fun chron(c: Chron): MutableComponent {
        // A siege's and a raid's say how it went ("held", "lost", "beaten", "taken", "over"); the rest name whom or what it was about
        val what = when (c.kind) {
            "siege", "raid" -> Component.translatable("village.guildmark.chronicle.${c.kind}.${c.arg}")
            // A project by its name ("Palisade Walls")
            "built" -> Component.translatable("village.guildmark.chronicle.built", Component.translatable("advance.guildmark.${c.arg}"))
            else -> Component.translatable("village.guildmark.chronicle.${c.kind}", c.arg)
        }
        return Component.translatable("village.guildmark.chronicle.line", c.day, what).withStyle(ChatFormatting.GRAY)
    }

    /** The memorial stone's reading of [rec]'s dead, the newest first ("Here are remembered: ..."), with the candles that burn. */
    fun reading(level: ServerLevel, rec: VillageRec): List<Component> {
        val out = ArrayList<Component>()
        if (rec.dead.isEmpty()) {
            out.add(Component.translatable("village.guildmark.memorial.none", rec.name).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC))
            return out
        }
        out.add(Component.translatable("village.guildmark.memorial.heading", rec.name).withStyle(ChatFormatting.GOLD))
        for (d in rec.dead.asReversed()) out.add(Component.literal("  ").append(line(d)))
        val waiting = TroubleData.peek(level)?.peek(rec.key)?.waiting.orEmpty()
        if (waiting.isNotEmpty()) {
            out.add(Component.translatable("village.guildmark.memorial.waiting", waiting.joinToString(", ") { it.name }).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC))
        }
        return out
    }

    /** What `village info` adds: the dead, the chronicle, the memorial stone, what a siege broke and the watch. */
    fun lines(level: ServerLevel, rec: VillageRec): List<Component> {
        val out = ArrayList<Component>()
        if (!Trouble.on()) {
            if (rec.dead.isNotEmpty()) out.add(label("Dead: ").append(plain("${rec.dead.size} kept (Shelter and the Watch is off: nobody new is entered, no candle laid).")))
            return out
        }
        val kept = TroubleData.peek(level)?.peek(rec.key)
        if (rec.dead.isEmpty()) {
            out.add(label("Dead: ").append(grey("none yet.")))
        } else {
            out.add(label("Dead (${rec.dead.size} of ${VillageRec.MAX_DEAD} kept, the newest first): "))
            for (d in rec.dead.asReversed().take(SHOWN)) out.add(Component.literal("  ").append(line(d)))
            if (rec.dead.size > SHOWN) out.add(grey("  and ${rec.dead.size - SHOWN} more: /guildmark village dead lists them all."))
        }
        val stone = kept?.stone?.takeIf { it != TroubleData.NONE }?.let { BlockPos.of(it) }
        val waiting = kept?.waiting.orEmpty()
        val memorial = label("Memorial stone: ").append(plain(if (stone == null) "none yet (set at the green at the dawn after the first death)" else "at ${stone.toShortString()}"))
        if (kept != null && kept.candles.isNotEmpty()) memorial.append(plain("; candles laid for ${kept.candles.takeLast(4).joinToString(", ") { it.name }}"))
        if (waiting.isNotEmpty()) memorial.append(plain("; waiting for a candle at dawn: ${waiting.joinToString(", ") { it.name }}"))
        out.add(memorial)
        if (rec.chronicle.isEmpty()) {
            out.add(label("Chronicle: ").append(grey("nothing has happened here yet.")))
        } else {
            out.add(label("Chronicle (the last ${rec.chronicle.size}, the newest first):"))
            for (c in rec.chronicle.asReversed()) out.add(Component.literal("  ").append(chron(c)))
        }
        Watch.line(level, rec)?.let { out.add(label("Watch: ").append(plain(it))) }
        if (VillageAdopt.adopting(rec)) out.add(label("Golems: ").append(plain(Golems.line(level, rec))))
        val broken = kept?.damage?.size ?: 0
        if (broken > 0) {
            val from = kept?.mendFrom ?: TroubleData.NONE
            out.add(label("Damage: ").append(plain("$broken blocks broken by a siege; " + if (from == TroubleData.NONE) "no mend set." else "the mend sets out on day $from.")))
        }
        return out
    }

    private const val SHOWN = 6

    private fun label(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.YELLOW)
    private fun plain(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.WHITE)
    private fun grey(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GRAY)
}
