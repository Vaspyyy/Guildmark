package io.github.vaspyyy.guildmark.client.atlas

import com.mojang.blaze3d.platform.NativeImage
import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.network.AtlasRoad
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.util.Util
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.storage.LevelResource
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The land the player has seen, for the map. Every chunk the game has loaded around them is sketched
 * (the colour and height of its surface every two blocks) and kept on disk for each world and dimension,
 * along with the pins they've put on the map. The sketches are painted as watercolour on parchment, with
 * the roads the Atlas knows inked over them, in tiles of 32 by 32 chunks that are drawn only while the map
 * is open.
 */
object MapData {
    /** Blocks per sample, samples per chunk side. */
    const val SAMPLE = 2
    private const val PER_CHUNK = 16 / SAMPLE
    private const val CELLS = PER_CHUNK * PER_CHUNK
    /** A chunk's sketch: a colour id per sample, then a height (or a depth, for water) per sample. */
    private const val BYTES = CELLS * 2
    const val TILE_CHUNKS = 32
    const val TILE_BLOCKS = TILE_CHUNKS * 16
    private const val TILE_SAMPLES = TILE_CHUNKS * PER_CHUNK
    /** How many tile textures may be kept at once (each is 256 KB, or a quarter of that zoomed out). */
    private const val MAX_TILES = 48
    private const val MAGIC = 0x474D4131
    private const val VERSION = 1
    private const val MAX_PINS = 64
    /** Chunks sketched per call, and how far around the player to look. */
    private const val BUDGET = 3
    private const val MAX_RADIUS = 16
    private const val SAVE_EVERY = 20 * 60 * 5

    /** A spot the player marked on the map, shown on the compass too. */
    class Pin(val x: Int, val z: Int, val color: Int, var name: String)

    val PIN_COLORS = intArrayOf(0xFFE0B04A.toInt(), 0xFFD0503C.toInt(), 0xFF5C8FD6.toInt(), 0xFF6DB65A.toInt(), 0xFFA46BD0.toInt(), 0xFFEDE6D6.toInt())

    private var world: String? = null
    private var dimension: ResourceKey<Level>? = null
    /** The level the world's name was last worked out for. */
    private var keyedLevel: ClientLevel? = null
    private var levelKey: String? = null
    private val chunks = HashMap<Long, ByteArray>()
    /** Tiles with at least one chunk sketched, and those a known road crosses. */
    private val landTiles = HashSet<Long>()
    private val roadTiles = HashSet<Long>()
    private var roads: List<AtlasRoad> = listOf()
    private val pinList = ArrayList<Pin>()
    val pins: List<Pin> get() = pinList
    private var unsaved = false
    private var ticks = 0
    private val saveLock = Any()

    // ---- Each tick ----

    fun tick() {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level
        val player = minecraft.player
        if (level == null || player == null) return
        if (level !== keyedLevel) {
            keyedLevel = level
            levelKey = worldKey(minecraft)
        }
        val key = levelKey ?: return
        if (key != world || level.dimension() != dimension) switchTo(key, level.dimension())
        ticks++
        if (ticks % 2 != 0 || level.dimensionType().hasCeiling()) return
        val cx = player.blockX shr 4
        val cz = player.blockZ shr 4
        // The patch the player stands in is sketched again now and then, so building and digging show
        if (ticks % 200 == 0) for (dx in -1..1) for (dz in -1..1) loaded(level, cx + dx, cz + dz)?.let { store(cx + dx, cz + dz, sketch(level, it)) }
        var budget = BUDGET
        val radius = min(minecraft.options.effectiveRenderDistance, MAX_RADIUS)
        for (r in 0..radius) {
            for (dx in -r..r) for (dz in -r..r) {
                if (max(Math.abs(dx), Math.abs(dz)) != r) continue
                val x = cx + dx
                val z = cz + dz
                if (chunks.containsKey(ChunkPos.pack(x, z))) continue
                val chunk = loaded(level, x, z) ?: continue
                store(x, z, sketch(level, chunk))
                if (--budget == 0) break
            }
            if (budget == 0) break
        }
        if (unsaved && ticks % SAVE_EVERY == 0) save(false)
    }

    /** Leaving the world: write the map out and let go of everything. */
    fun reset() {
        save(true)
        clear()
        world = null
        dimension = null
        keyedLevel = null
        levelKey = null
    }

    private fun clear() {
        chunks.clear()
        landTiles.clear()
        roadTiles.clear()
        roads = listOf()
        pinList.clear()
        releaseTiles()
        unsaved = false
    }

    private fun loaded(level: ClientLevel, x: Int, z: Int): LevelChunk? = level.chunkSource.getChunk(x, z, ChunkStatus.FULL, false)

    // ---- Sketching ----

    /** The chunk's surface colour and height every two blocks, as a map would show them. */
    private fun sketch(level: ClientLevel, chunk: LevelChunk): ByteArray {
        val out = ByteArray(BYTES)
        val pos = BlockPos.MutableBlockPos()
        val minY = level.minY
        val baseX = chunk.pos.minBlockX
        val baseZ = chunk.pos.minBlockZ
        for (sz in 0 until PER_CHUNK) for (sx in 0 until PER_CHUNK) {
            val x = baseX + sx * SAMPLE + SAMPLE / 2
            val z = baseZ + sz * SAMPLE + SAMPLE / 2
            var y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 1
            var color = MapColor.NONE
            var depth = 0
            if (y > minY) {
                var state = chunk.getBlockState(pos.set(x, y, z))
                do {
                    y--
                    state = chunk.getBlockState(pos.set(x, y, z))
                    color = state.getMapColor(level, pos)
                } while (color == MapColor.NONE && y > minY)
                if (!state.fluidState.isEmpty) {
                    var below = y - 1
                    while (below > minY && depth < 15 && !chunk.getBlockState(pos.set(x, below, z)).fluidState.isEmpty) {
                        depth++
                        below--
                    }
                    color = state.fluidState.createLegacyBlock().getMapColor(level, pos.set(x, y, z))
                }
            }
            val i = sz * PER_CHUNK + sx
            out[i] = color.id.toByte()
            out[CELLS + i] = (if (color == MapColor.WATER) depth else ((y - minY) / 2).coerceIn(0, 255)).toByte()
        }
        return out
    }

    private fun store(x: Int, z: Int, data: ByteArray) {
        val key = ChunkPos.pack(x, z)
        val old = chunks.put(key, data)
        if (old != null && old.contentEquals(data)) return
        unsaved = true
        landTiles.add(tileOf(x, z))
        // This chunk's tile, and any tile next to it whose edge it changes, need painting again
        for (dx in -1..1) for (dz in -1..1) staleTile(tileOf(x + dx, z + dz))
    }

    fun explored(chunkX: Int, chunkZ: Int): Boolean = chunks.containsKey(ChunkPos.pack(chunkX, chunkZ))

    private fun tileOf(chunkX: Int, chunkZ: Int): Long = ChunkPos.pack(Math.floorDiv(chunkX, TILE_CHUNKS), Math.floorDiv(chunkZ, TILE_CHUNKS))

    // ---- Pins ----

    fun addPin(x: Int, z: Int, name: String): Pin? {
        if (pinList.size >= MAX_PINS) return null
        val pin = Pin(x, z, PIN_COLORS[pinList.size % PIN_COLORS.size], name)
        pinList.add(pin)
        unsaved = true
        return pin
    }

    fun removePin(pin: Pin) {
        if (pinList.remove(pin)) unsaved = true
    }

    fun renamePin(pin: Pin, name: String) {
        pin.name = name
        unsaved = true
    }

    // ---- Painting the tiles ----

    private class Tile(val texture: DynamicTexture) {
        var stale = false
        var used = 0L
    }

    /** Painted tiles, close up (a pixel a sample) and far out (a pixel every two samples). */
    private val near = HashMap<Long, Tile>()
    private val far = HashMap<Long, Tile>()
    private var frame = 0L

    private fun staleTile(key: Long) {
        near[key]?.stale = true
        far[key]?.stale = true
    }

    /** Call once per frame the map is drawn, before asking for tiles. */
    fun beginFrame() {
        frame++
        val now = AtlasClient.roads()
        if (now != roads) {
            for (road in roads) roadTilesOf(road) { staleTile(it) }
            roads = now
            roadTiles.clear()
            for (road in now) roadTilesOf(road) {
                staleTile(it)
                roadTiles.add(it)
            }
        }
    }

    /** Every tile a road's straight runs pass over (or near). */
    private inline fun roadTilesOf(road: AtlasRoad, each: (Long) -> Unit) {
        val p = road.points
        for (i in 0 until p.size / 2 - 1) {
            val x0 = Math.floorDiv(min(p[2 * i], p[2 * i + 2]) - SAMPLE * 2, TILE_BLOCKS)
            val x1 = Math.floorDiv(max(p[2 * i], p[2 * i + 2]) + SAMPLE * 2, TILE_BLOCKS)
            val z0 = Math.floorDiv(min(p[2 * i + 1], p[2 * i + 3]) - SAMPLE * 2, TILE_BLOCKS)
            val z1 = Math.floorDiv(max(p[2 * i + 1], p[2 * i + 3]) + SAMPLE * 2, TILE_BLOCKS)
            for (tx in x0..x1) for (tz in z0..z1) each(ChunkPos.pack(tx, tz))
        }
    }

    /**
     * The painted tile at tile coordinates ([tx], [tz]), or null if there's nothing there or it isn't
     * painted yet. Painting is expensive, so at most [budget] tiles are painted (or repainted) per frame;
     * until a tile is, the same tile painted at the other size stands in if there is one.
     */
    fun tile(tx: Int, tz: Int, zoomedOut: Boolean, budget: IntArray): DynamicTexture? {
        val key = ChunkPos.pack(tx, tz)
        if (key !in landTiles && key !in roadTiles) return null
        val cache = if (zoomedOut) far else near
        val step = if (zoomedOut) 2 else 1
        val tile = cache[key]
        if (tile == null) {
            if (budget[0] <= 0) return standIn(key, zoomedOut)
            budget[0]--
            val size = TILE_SAMPLES / step
            val image = NativeImage(size, size, true)
            paint(image, tx, tz, step)
            val made = Tile(DynamicTexture({ "Guildmark map tile" }, image))
            made.used = frame
            cache[key] = made
            evict(cache)
            return made.texture
        }
        if (tile.stale && budget[0] > 0) {
            budget[0]--
            tile.stale = false
            paint(tile.texture.pixels, tx, tz, step)
            tile.texture.upload()
        }
        tile.used = frame
        return tile.texture
    }

    private fun standIn(key: Long, zoomedOut: Boolean): DynamicTexture? {
        val other = (if (zoomedOut) near else far)[key] ?: return null
        other.used = frame
        return other.texture
    }

    private fun evict(cache: HashMap<Long, Tile>) {
        if (cache.size <= MAX_TILES) return
        val oldest = cache.entries.filter { it.value.used < frame }.sortedBy { it.value.used }.take(cache.size - MAX_TILES)
        for (entry in oldest) {
            entry.value.texture.close()
            cache.remove(entry.key)
        }
    }

    /** The map closed: let go of the painted tiles (they're quick to paint again). */
    fun closed() {
        releaseTiles()
        saveSoon()
    }

    private fun releaseTiles() {
        for (tile in near.values) tile.texture.close()
        for (tile in far.values) tile.texture.close()
        near.clear()
        far.clear()
    }

    // Parchment and ink, as RGB
    private const val PAPER_R = 236f
    private const val PAPER_G = 222f
    private const val PAPER_B = 184f
    private const val WASH = 0.86f
    /** Bare parchment shows through here (the paper's colour, so smoothing doesn't darken the edge). */
    private const val CLEAR = 0x00ECDEB8
    private const val INK_R = 58f
    private const val INK_G = 38f
    private const val INK_B = 20f

    /**
     * Paints one tile: each sample's colour washed over parchment, shaded by the lie of the land, coasts
     * inked in, and the edge of the known world fading out raggedly instead of stopping square.
     */
    private fun paint(image: NativeImage, tx: Int, tz: Int, step: Int) {
        // The tile's chunks and a ring around it, looked up once
        val span = TILE_CHUNKS + 2
        val grid = arrayOfNulls<ByteArray>(span * span)
        val chunkX0 = tx * TILE_CHUNKS - 1
        val chunkZ0 = tz * TILE_CHUNKS - 1
        for (gz in 0 until span) for (gx in 0 until span) grid[gz * span + gx] = chunks[ChunkPos.pack(chunkX0 + gx, chunkZ0 + gz)]
        fun chunkAt(cx: Int, cz: Int): ByteArray? = grid[(cz + 1) * span + (cx + 1)]
        /** Sample ([sx], [sz]) counted from the tile's corner; may reach one chunk outside it. */
        fun colorAt(sx: Int, sz: Int): Int {
            val data = chunkAt(Math.floorDiv(sx, PER_CHUNK), Math.floorDiv(sz, PER_CHUNK)) ?: return -1
            return data[Math.floorMod(sz, PER_CHUNK) * PER_CHUNK + Math.floorMod(sx, PER_CHUNK)].toInt() and 0xFF
        }
        fun heightAt(sx: Int, sz: Int, fallback: Int): Int {
            val data = chunkAt(Math.floorDiv(sx, PER_CHUNK), Math.floorDiv(sz, PER_CHUNK)) ?: return fallback
            val i = Math.floorMod(sz, PER_CHUNK) * PER_CHUNK + Math.floorMod(sx, PER_CHUNK)
            if ((data[i].toInt() and 0xFF) == MapColor.WATER.id) return fallback
            return data[CELLS + i].toInt() and 0xFF
        }
        val water = MapColor.WATER.id
        val size = TILE_SAMPLES / step
        for (py in 0 until size) for (px in 0 until size) {
            val sx = px * step
            val sz = py * step
            val cx = sx / PER_CHUNK
            val cz = sz / PER_CHUNK
            val data = chunkAt(cx, cz)
            if (data == null) {
                image.setPixel(px, py, CLEAR)
                continue
            }
            val cellX = sx % PER_CHUNK
            val cellZ = sz % PER_CHUNK
            val i = cellZ * PER_CHUNK + cellX
            val id = data[i].toInt() and 0xFF
            if (id == 0) {
                image.setPixel(px, py, CLEAR)
                continue
            }

            // How far (in samples) to the nearest chunk nobody has seen, for the ragged edge
            var edge = 99
            if (chunkAt(cx - 1, cz) == null) edge = min(edge, cellX)
            if (chunkAt(cx + 1, cz) == null) edge = min(edge, PER_CHUNK - 1 - cellX)
            if (chunkAt(cx, cz - 1) == null) edge = min(edge, cellZ)
            if (chunkAt(cx, cz + 1) == null) edge = min(edge, PER_CHUNK - 1 - cellZ)
            if (edge > 2) {
                if (chunkAt(cx - 1, cz - 1) == null) edge = min(edge, max(cellX, cellZ))
                if (chunkAt(cx + 1, cz - 1) == null) edge = min(edge, max(PER_CHUNK - 1 - cellX, cellZ))
                if (chunkAt(cx - 1, cz + 1) == null) edge = min(edge, max(cellX, PER_CHUNK - 1 - cellZ))
                if (chunkAt(cx + 1, cz + 1) == null) edge = min(edge, max(PER_CHUNK - 1 - cellX, PER_CHUNK - 1 - cellZ))
            }
            var alpha = WASH
            if (edge < 3) {
                val worldX = tx * TILE_SAMPLES + sx
                val worldZ = tz * TILE_SAMPLES + sz
                val jitter = (hash(worldX, worldZ) and 0xFF) / 255f
                alpha *= ((edge + jitter * 1.6f - 0.4f) / 2.6f).coerceIn(0f, 1f)
                if (alpha <= 0.02f) {
                    image.setPixel(px, py, CLEAR)
                    continue
                }
            }

            val base = MapColor.byId(id).col
            var r: Float
            var g: Float
            var b: Float
            var mix: Float
            var shade = 0f
            if (id == water) {
                val depth = min(1f, (data[CELLS + i].toInt() and 0xFF) / 10f)
                r = 60f * (1 - 0.3f * depth)
                g = 112f * (1 - 0.3f * depth)
                b = 168f * (1 - 0.3f * depth)
                val grey = (r + g + b) / 3
                r = grey + (r - grey) * 0.9f
                g = grey + (g - grey) * 0.9f
                b = grey + (b - grey) * 0.9f
                mix = 0.62f
            } else {
                r = ((base shr 16) and 0xFF).toFloat()
                g = ((base shr 8) and 0xFF).toFloat()
                b = (base and 0xFF).toFloat()
                val grey = (r + g + b) / 3
                r = grey + (r - grey) * 0.62f
                g = grey + (g - grey) * 0.62f
                b = grey + (b - grey) * 0.62f
                mix = 0.55f
                // Light from the north-west: slopes facing it brighten, those turned away darken
                val height = data[CELLS + i].toInt() and 0xFF
                val north = heightAt(sx - 1, sz - 1, height)
                shade = ((height - north) * 0.24f).coerceIn(-0.35f, 0.35f)
            }
            r = (PAPER_R * (1 - mix) + r * PAPER_R / 235f * mix) * (1 + shade)
            g = (PAPER_G * (1 - mix) + g * PAPER_G / 235f * mix) * (1 + shade)
            b = (PAPER_B * (1 - mix) + b * PAPER_B / 235f * mix) * (1 + shade)
            // Ink along the shore, on the water's side
            if (id == water) {
                fun land(c: Int) = c > 0 && c != water
                if (land(colorAt(sx - 1, sz)) || land(colorAt(sx + 1, sz)) || land(colorAt(sx, sz - 1)) || land(colorAt(sx, sz + 1))) {
                    r = r * 0.5f + 40f * 0.5f
                    g = g * 0.5f + 52f * 0.5f
                    b = b * 0.5f + 70f * 0.5f
                }
            }
            image.setPixel(px, py, argb(alpha, r, g, b))
        }
        inkRoads(image, tx, tz, step)
    }

    /** The known roads in ink over the tile: finished ones solid, those still being built dashed. */
    private fun inkRoads(image: NativeImage, tx: Int, tz: Int, step: Int) {
        val size = image.width
        val blocks = (SAMPLE * step).toFloat()
        val x0 = tx * TILE_BLOCKS
        val z0 = tz * TILE_BLOCKS
        for (road in roads) {
            val p = road.points
            var along = 0f
            var lastX = Int.MIN_VALUE
            var lastZ = Int.MIN_VALUE
            for (i in 0 until p.size / 2 - 1) {
                val ax = (p[2 * i] - x0) / blocks
                val az = (p[2 * i + 1] - z0) / blocks
                val bx = (p[2 * i + 2] - x0) / blocks
                val bz = (p[2 * i + 3] - z0) / blocks
                val length = max(abs(bx - ax), abs(bz - az))
                if (max(ax, bx) < -1 || min(ax, bx) > size + 1 || max(az, bz) < -1 || min(az, bz) > size + 1) {
                    along += length
                    continue
                }
                val steps = max(1, ceil(length).toInt())
                for (n in 0..steps) {
                    val t = n.toFloat() / steps
                    val x = floor(ax + (bx - ax) * t).toInt()
                    val z = floor(az + (bz - az) * t).toInt()
                    if (x == lastX && z == lastZ) continue
                    if (road.finished || (along + length * t) % 6f < 3.5f) {
                        // Stepping corner to corner, fill the step in so the line stays unbroken
                        if (x != lastX && z != lastZ && abs(x - lastX) <= 1 && abs(z - lastZ) <= 1) ink(image, x, lastZ, 0.82f)
                        ink(image, x, z, 0.82f)
                    }
                    lastX = x
                    lastZ = z
                }
                along += length
            }
        }
    }

    private fun ink(image: NativeImage, x: Int, y: Int, alpha: Float) {
        if (x < 0 || y < 0 || x >= image.width || y >= image.height) return
        val under = image.getPixel(x, y)
        val ua = (under ushr 24) / 255f * (1 - alpha)
        val out = alpha + ua
        fun mix(ink: Float, shift: Int): Float = (ink * alpha + ((under shr shift) and 0xFF) * ua) / out
        image.setPixel(x, y, argb(out, mix(INK_R, 16), mix(INK_G, 8), mix(INK_B, 0)))
    }

    private fun argb(alpha: Float, r: Float, g: Float, b: Float): Int =
        ((alpha * 255).toInt().coerceIn(0, 255) shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)

    private fun hash(x: Int, z: Int): Int {
        var h = x * 374761393 + z * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        return h xor (h ushr 16)
    }

    // ---- On disk ----

    /** A name for the world being played, for its folder: the save's folder name, or the server's address. */
    private fun worldKey(minecraft: Minecraft): String? {
        val server = minecraft.singleplayerServer
        val raw = when {
            server != null -> "local_" + (server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().fileName?.toString() ?: "world")
            minecraft.currentServer != null -> "server_" + minecraft.currentServer!!.ip
            else -> return null
        }
        return raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)
    }

    private fun file(): Path? {
        val w = world ?: return null
        val d = dimension ?: return null
        return Minecraft.getInstance().gameDirectory.toPath().resolve("guildmark").resolve("maps").resolve(w).resolve(d.identifier().toDebugFileName() + ".dat")
    }

    private fun switchTo(key: String, newDimension: ResourceKey<Level>) {
        save(true)
        clear()
        world = key
        dimension = newDimension
        val path = file() ?: return
        if (!Files.exists(path)) return
        try {
            DataInputStream(BufferedInputStream(GZIPInputStream(Files.newInputStream(path)))).use { input ->
                if (input.readInt() != MAGIC) return
                input.readInt()
                repeat(input.readInt()) {
                    val chunk = input.readLong()
                    val data = ByteArray(BYTES)
                    input.readFully(data)
                    chunks[chunk] = data
                    landTiles.add(tileOf(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)))
                }
                repeat(input.readInt()) {
                    val x = input.readInt()
                    val z = input.readInt()
                    val color = input.readInt()
                    pinList.add(Pin(x, z, color, input.readUTF()))
                }
            }
        } catch (e: IOException) {
            Guildmark.LOGGER.warn("Couldn't read the map at {}", path, e)
        }
    }

    /** Write the map out: now ([sync]), or in the background with a copy of it. */
    private fun save(sync: Boolean) {
        if (!unsaved) return
        val path = file() ?: return
        unsaved = false
        val snapshot = HashMap(chunks)
        val pinsNow = pinList.map { Pin(it.x, it.z, it.color, it.name) }
        val write = Runnable {
            synchronized(saveLock) {
                try {
                    Files.createDirectories(path.parent)
                    val temp = path.resolveSibling(path.fileName.toString() + ".tmp")
                    DataOutputStream(BufferedOutputStream(GZIPOutputStream(Files.newOutputStream(temp)))).use { out ->
                        out.writeInt(MAGIC)
                        out.writeInt(VERSION)
                        out.writeInt(snapshot.size)
                        for ((chunk, data) in snapshot) {
                            out.writeLong(chunk)
                            out.write(data)
                        }
                        out.writeInt(pinsNow.size)
                        for (pin in pinsNow) {
                            out.writeInt(pin.x)
                            out.writeInt(pin.z)
                            out.writeInt(pin.color)
                            out.writeUTF(pin.name)
                        }
                    }
                    try {
                        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    } catch (_: IOException) {
                        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
                    }
                } catch (e: IOException) {
                    Guildmark.LOGGER.warn("Couldn't save the map to {}", path, e)
                }
            }
        }
        if (sync) write.run() else Util.ioPool().execute(write)
    }

    /** Pins changed, or the map closed: save soon rather than at the next autosave. */
    fun saveSoon() {
        save(false)
    }
}
