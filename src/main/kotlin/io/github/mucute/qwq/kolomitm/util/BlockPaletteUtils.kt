package io.github.mucute.qwq.kolomitm.util

import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtUtils
import java.io.ByteArrayOutputStream
import java.util.*


object BlockPaletteUtils {

    fun createHash(block: NbtMap): Int {
        if (block.getString("name") == "minecraft:unknown") {
            return -2 // This is special case
        }
        // Order required
        val states = TreeMap(block.getCompound("states"))
        val statesBuilder = NbtMap.builder()
        statesBuilder.putAll(states)

        val tag = NbtMap.builder()
            .putString("name", block.getString("name"))
            .putCompound("states", statesBuilder.build())
            .build()

        val bytes: ByteArray?
        ByteArrayOutputStream().use { stream ->
            NbtUtils.createWriterLE(stream).use { outputStream ->
                outputStream.writeTag(tag)
                bytes = stream.toByteArray()
            }
        }

        return fnv1a_32(bytes!!)
    }

    private const val FNV1_32_INIT = -0x7ee3623b
    private const val FNV1_PRIME_32 = 0x01000193

    @Suppress("FunctionName")
    private fun fnv1a_32(data: ByteArray): Int {
        var hash = FNV1_32_INIT
        for (datum in data) {
            hash = hash xor (datum.toInt() and 0xff)
            hash *= FNV1_PRIME_32
        }
        return hash
    }

    private class Entry {
        private val id = 0
        private val meta: Int? = null
        private val name: String? = null
        private val states: MutableMap<String?, BlockState?>? = null
    }

    private class BlockState {
        private val `val`: Any? = null
        private val type = 0
    }

}