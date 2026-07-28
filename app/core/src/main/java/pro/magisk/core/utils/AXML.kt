/**
 * Low-level parser / patcher for Android's binary XML (AXML) format
 * used inside APKs. Walks the chunk tree to find the string pool,
 * applies a caller-supplied transformation to every string, and
 * rewrites the byte-level header offsets so the result remains valid.
 * Used by [AppMigration] to replace package names and activity class
 * names in `AndroidManifest.xml`.
 */
package pro.magisk.core.utils

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.nio.charset.Charset

/**
 * Parser for Android's binary XML (AXML) format.
 *
 * Walks the chunk tree to locate the string pool, applies a
 * caller-supplied transformation to every string, and rewrites
 * the byte-level offsets so the result remains valid.
 *
 * @property bytes The raw AXML byte array, updated after a patch.
 */
class AXML(b: ByteArray) {

    var bytes = b
        private set

    companion object {
        private const val CHUNK_SIZE_OFF = 4
        private const val STRING_INDICES_OFF = 7 * 4
        private val UTF_16LE = Charset.forName("UTF-16LE")
    }

    /**
     * Apply [mapFn] to every string in the binary XML's string pool.
     *
     * String pool header layout:
     * ```
     * 0:  0x1C0001
     * 1:  chunk size
     * 2:  number of strings
     * 3:  number of styles (assert as 0)
     * 4:  flags
     * 5:  offset to string data
     * 6:  offset to style data (assert as 0)
     * ```
     * Followed by an array of uint32_t offsets into the string data.
     *
     * @return `true` if the pool was found and patched.
     */
    fun patchStrings(mapFn: (String) -> String): Boolean {
        val buffer = ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN)

        fun findStringPool(): Int {
            var offset = 8
            while (offset < bytes.size) {
                if (buffer.getInt(offset) == 0x1C0001)
                    return offset
                offset += buffer.getInt(offset + CHUNK_SIZE_OFF)
            }
            return -1
        }

        val start = findStringPool()
        if (start < 0)
            return false

        buffer.position(start + 4)
        val intBuf = buffer.asIntBuffer()
        val size = intBuf.get()
        val count = intBuf.get()
        intBuf.get()
        intBuf.get()
        val dataOff = start + intBuf.get()
        intBuf.get()

        val strList = ArrayList<String>(count)
        for (i in 0 until count) {
            val off = dataOff + intBuf.get()
            val len = buffer.getShort(off)
            strList.add(String(bytes, off + 2, len * 2, UTF_16LE))
        }

        val strArr = strList.toTypedArray()
        for (i in strArr.indices) {
            strArr[i] = mapFn(strArr[i])
        }

        val baos = RawByteStream()
        baos.write(bytes, 0, dataOff)

        val offList = IntArray(count)
        for (i in 0 until count) {
            offList[i] = baos.size() - dataOff
            val str = strArr[i]
            baos.write(str.length.toShortBytes())
            baos.write(str.toByteArray(UTF_16LE))
            baos.write(0)
            baos.write(0)
        }
        baos.align()

        val sizeDiff = baos.size() - start - size
        val newBuffer = ByteBuffer.wrap(baos.buffer).order(LITTLE_ENDIAN)

        newBuffer.putInt(CHUNK_SIZE_OFF, buffer.getInt(CHUNK_SIZE_OFF) + sizeDiff)
        newBuffer.putInt(start + CHUNK_SIZE_OFF, size + sizeDiff)
        newBuffer.position(start + STRING_INDICES_OFF)
        val newIntBuf = newBuffer.asIntBuffer()
        offList.forEach { newIntBuf.put(it) }

        val nextOff = start + size
        baos.write(bytes, nextOff, bytes.size - nextOff)

        bytes = baos.toByteArray()
        return true
    }

    private fun Int.toShortBytes(): ByteArray {
        val b = ByteBuffer.allocate(2).order(LITTLE_ENDIAN)
        b.putShort(this.toShort())
        return b.array()
    }

    private class RawByteStream : ByteArrayOutputStream() {
        val buffer: ByteArray get() = buf

        fun align(alignment: Int = 4) {
            val newCount = (count + alignment - 1) / alignment * alignment
            for (i in 0 until (newCount - count))
                write(0)
        }
    }
}
