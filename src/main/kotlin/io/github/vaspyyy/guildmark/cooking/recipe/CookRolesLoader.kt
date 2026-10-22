package io.github.vaspyyy.guildmark.cooking.recipe

import com.mojang.logging.LogUtils
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.vaspyyy.guildmark.cooking.Role
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.FileToIdConverter
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener
import net.minecraft.util.profiling.ProfilerFiller
import java.util.Locale
import java.util.Optional

/**
 * One food of a `cook_roles` file as it is written (living-world/cook/design.md 2.2, Appendix B): its [name] (the key the
 * dishes' standard bills use), the item ids or `#tags` it is ([items]), the weight of each role by name ([roles], left as
 * written so that a bad name can be named in the log) and the container the cook is handed back when it goes in ([returns]).
 */
class FoodDef(val name: String, val items: List<String>, val roles: Map<String, Double>, val returns: String?) {
    companion object {
        val CODEC: Codec<FoodDef> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("name").forGetter { it.name },
                Codec.STRING.listOf().optionalFieldOf("items", listOf()).forGetter { it.items },
                Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).optionalFieldOf("roles", mapOf()).forGetter { it.roles },
                Codec.STRING.optionalFieldOf("returns").forGetter { Optional.ofNullable(it.returns) },
            ).apply(i) { name, items, roles, returns -> FoodDef(name, items, roles, returns.orElse(null)) }
        }
    }
}

/** A `cook_roles` file: `{"foods": [...]}`. */
class FoodFile(val foods: List<FoodDef>) {
    companion object {
        val CODEC: Codec<FoodFile> = RecordCodecBuilder.create { i ->
            i.group(
                FoodDef.CODEC.listOf().optionalFieldOf("foods", listOf()).forGetter { it.foods },
            ).apply(i) { foods -> FoodFile(foods) }
        }
    }
}

/**
 * A food of the merged table: its [name], the [share] of each role it brings (in [Role] order, scaled to add up to 1; never
 * changed after it is made), the [returns] container's item id (null: none) and the [file] it came from.
 */
class Food(val name: String, val share: DoubleArray, val returns: String?, val file: String) {
    /** The roles it brings and their weights, in [Role] order, those it does not bring left out. */
    val roles: Map<Role, Double> = LinkedHashMap<Role, Double>().also { m ->
        for (role in Role.entries) if (share[role.ordinal] > 0.0) m[role] = share[role.ordinal]
    }
}

/**
 * What every `cook_roles` file of every pack adds up to (design Appendix B.2), as plain text and numbers so that it can be
 * worked out and tried outside the game. The rules, in the order the files are given:
 *
 *  1. a food's weights are scaled to add up to 1; a role name that is not one of the seven, or a weight that is not a number
 *     from 0 up, drops the food, with a line in the log that names the file and the food;
 *  2. an item id the game does not know is skipped, with a line in the log; a tag is looked at when an item is asked about,
 *     so an unknown tag is simply empty;
 *  3. for an item named twice the later food wins, and an item named by its own id beats one reached through a tag;
 *  4. a food with an empty `roles` takes its items out of the table (a pack can say that bread is not for the pot).
 */
class RoleTable private constructor(
    private val byItem: Map<String, Food?>,
    private val byTag: List<Pair<String, Food?>>,
    private val byName: Map<String, Food>,
    /** How many files it was made from. */
    val files: Int,
) {
    /** The foods that bring a role, by name (lower case). */
    val foods: Collection<Food> get() = byName.values

    /** How many item ids are in the table (those taken out of it do not count). */
    val itemCount: Int get() = byItem.values.count { it != null }

    /** How many tags are in the table (those taken out of it do not count). */
    val tagCount: Int get() = byTag.count { it.second != null }

    /** The food called [name] (any case), or null. */
    fun named(name: String): Food? = byName[name.trim().lowercase(Locale.ROOT)]

    /**
     * What an item is in a pot: the food for the item id [itemId] if one names it (even if that takes it out of the table,
     * which answers null), else the food of the latest tag it is in, as [inTag] says, else null.
     */
    fun of(itemId: String, inTag: (String) -> Boolean): Food? {
        if (byItem.containsKey(itemId)) return byItem[itemId]
        for (i in byTag.indices.reversed()) {
            val (tag, food) = byTag[i]
            if (inTag(tag)) return food
        }
        return null
    }

    /** The item ids the table names, for a food of [name]: its own ids, in the order of the file. */
    fun itemsOf(name: String): List<String> {
        val food = named(name) ?: return listOf()
        return byItem.entries.filter { it.value === food }.map { it.key }
    }

    /** The tags the table names for a food of [name], without the `#`. */
    fun tagsOf(name: String): List<String> {
        val food = named(name) ?: return listOf()
        return byTag.filter { it.second === food }.map { it.first }
    }

    companion object {
        /** Nothing is an ingredient. */
        val EMPTY = RoleTable(mapOf(), listOf(), mapOf(), 0)

        /** The seven role names, as a file writes them. */
        private val ROLE_NAMES = Role.entries.map { it.name.lowercase(Locale.ROOT) }

        /**
         * The share of each role, in [Role] order and adding up to 1, that [parts] (a food and how many of it) make together
         * (design 2.2: add up the roles' weights over the items, divide by the item count); all zero for nothing.
         */
        fun mix(parts: List<Pair<Food, Int>>): DoubleArray {
            val out = DoubleArray(Role.entries.size)
            var count = 0
            for ((food, n) in parts) {
                if (n <= 0) continue
                for (i in out.indices) out[i] += food.share[i] * n
                count += n
            }
            if (count > 0) for (i in out.indices) out[i] /= count
            return out
        }

        /**
         * Merges [files] (each a file's id and its foods, in the order they are to be read) into one table. [known] says whether
         * an item id exists; [log] is told, in words, of every food or item left out and why.
         */
        fun merge(files: List<Pair<String, List<FoodDef>>>, known: (String) -> Boolean, log: (String) -> Unit): RoleTable {
            val items = LinkedHashMap<String, Food?>()
            val tags = ArrayList<Pair<String, Food?>>()
            val names = LinkedHashMap<String, Food>()
            for ((file, defs) in files) {
                for (def in defs) {
                    val key = def.name.trim().lowercase(Locale.ROOT)
                    if (key.isEmpty()) {
                        log("Cook roles $file: a food has no name; it is dropped")
                        continue
                    }
                    var food: Food? = null
                    if (def.roles.isNotEmpty()) {
                        food = make(file, def, key, log) ?: continue
                    }
                    val returns = def.returns
                    if (food != null && returns != null && !known(returns)) {
                        log("Cook roles $file: the food '${def.name}' gives back '$returns', which the game does not know; it gives nothing back")
                        food = Food(food.name, food.share, null, food.file)
                    }
                    for (id in def.items) {
                        if (id.startsWith("#")) {
                            val tag = id.substring(1)
                            tags.removeAll { it.first == tag }
                            tags.add(tag to food)
                        } else if (known(id)) {
                            items[id] = food
                        } else {
                            log("Cook roles $file: the food '${def.name}' names the item '$id', which the game does not know; it is skipped")
                        }
                    }
                    if (food != null) names[key] = food else names.remove(key)
                }
            }
            return RoleTable(items, tags, names, files.size)
        }

        /** The merged food for [def], or null (and why, in [log]) if its roles are not usable. */
        private fun make(file: String, def: FoodDef, key: String, log: (String) -> Unit): Food? {
            val share = DoubleArray(Role.entries.size)
            for ((role, weight) in def.roles) {
                val at = ROLE_NAMES.indexOf(role.trim().lowercase(Locale.ROOT))
                if (at < 0) {
                    log("Cook roles $file: the food '${def.name}' names the role '$role', which is not one of the seven (${ROLE_NAMES.joinToString(", ")}); the food is dropped")
                    return null
                }
                if (!weight.isFinite() || weight < 0.0) {
                    log("Cook roles $file: the food '${def.name}' gives the role '$role' a weight of $weight, which is not a number from 0 up; the food is dropped")
                    return null
                }
                share[at] += weight
            }
            val total = share.sum()
            if (total <= 0.0) {
                log("Cook roles $file: the weights of the food '${def.name}' add up to nothing; the food is dropped")
                return null
            }
            for (i in share.indices) share[i] /= total
            return Food(key, share, def.returns, file)
        }
    }
}

/**
 * Reads every `data/<namespace>/cook_roles/<name>.json` of every pack (living-world/cook/design.md 2.2, Appendix B.2) into
 * the table [Roles] answers from. Registered on `AddServerReloadListenersEvent` by [Roles.register], so `/reload` reads
 * the files again. The files are read in the order of their ids as text, so a pack that wants the last word names its
 * file to sort after `guildmark:base`. The table is server data: nothing in it is sent to a client.
 */
class CookRolesLoader : SimpleJsonResourceReloadListener<FoodFile>(FoodFile.CODEC, FileToIdConverter.json("cook_roles")) {
    override fun apply(preparations: Map<Identifier, FoodFile>, manager: ResourceManager, profiler: ProfilerFiller) {
        val files = preparations.entries.sortedBy { it.key.toString() }.map { (id, file) -> id.toString() to file.foods }
        val table = RoleTable.merge(files, { id -> itemKnown(id) }) { LOGGER.warn("{}", it) }
        Roles.install(table)
        LOGGER.info("Loaded {} cook foods ({} items, {} tags) from {} files", table.foods.size, table.itemCount, table.tagCount, table.files)
    }

    private companion object {
        val LOGGER = LogUtils.getLogger()

        fun itemKnown(id: String): Boolean {
            val key = Identifier.tryParse(id) ?: return false
            return BuiltInRegistries.ITEM.containsKey(key)
        }
    }
}
