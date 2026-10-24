package cz.teply.sheetset.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.util.LinkedHashMap
import java.util.zip.CRC32

internal data class ScorePdfScore(
    val key: Long,
    val title: String,
    val fileName: String,
)

internal data class ScorePdfSetlist(
    val name: String,
    val scoreKeys: List<Long>,
)

internal object ScorePdfHive {
    private const val SCORE_ADAPTER = 32
    private const val SETLIST_ADAPTER = 36
    private const val MAX_RECORDS = 20_000
    private const val MAX_ITEMS = 20_000
    private const val MAX_STRING_BYTES = 1_048_576
    private const val UINT_MAX = 0xFFFF_FFFFL

    fun readScores(bytes: ByteArray): List<ScorePdfScore> = readFrames(bytes) { key, reader ->
        reader.requireByte(SCORE_ADAPTER)
        require(reader.byte() >= 6)
        reader.requireByte(0)
        reader.stringValue()
        reader.requireByte(1)
        val title = reader.stringValue().trim().take(MAX_TITLE_LENGTH).ifEmpty { "Untitled PDF" }
        reader.requireByte(2)
        reader.stringListValue()
        reader.requireByte(3)
        reader.dateTimeValue()
        reader.requireByte(4)
        reader.dateTimeValue()
        reader.requireByte(5)
        val fileName = reader.stringValue()
        requireSafePdfName(fileName)
        ScorePdfScore(key, title, fileName)
    }

    fun readSetlists(bytes: ByteArray): List<ScorePdfSetlist> = readFrames(bytes) { _, reader ->
        reader.requireByte(SETLIST_ADAPTER)
        require(reader.byte() >= 2)
        reader.requireByte(0)
        val name = reader.stringValue().trim().take(MAX_TITLE_LENGTH).ifEmpty { "Untitled setlist" }
        reader.requireByte(1)
        ScorePdfSetlist(name, reader.integerListValue())
    }

    private fun <T> readFrames(
        bytes: ByteArray,
        readValue: (Long, HiveReader) -> T,
    ): List<T> = try {
        val values = LinkedHashMap<Long, T>()
        var offset = 0
        var frames = 0
        while (offset < bytes.size) {
            require(bytes.size - offset >= Int.SIZE_BYTES)
            val frameLength = uint32(bytes, offset)
            require(frameLength in 8..bytes.size - offset)
            val crcOffset = offset + frameLength - Int.SIZE_BYTES
            val expectedCrc = uint32Long(bytes, crcOffset)
            val actualCrc = CRC32().apply {
                update(bytes, offset, frameLength - Int.SIZE_BYTES)
            }.value
            require(actualCrc == expectedCrc)
            val reader = HiveReader(bytes, offset + Int.SIZE_BYTES, crcOffset)
            val key = reader.numericKey()
            if (reader.remaining == 0) {
                values.remove(key)
            } else {
                values[key] = readValue(key, reader)
            }
            offset += frameLength
            frames++
            require(frames <= MAX_RECORDS)
        }
        values.values.toList()
    } catch (error: BackupException) {
        throw error
    } catch (error: Exception) {
        throw BackupException("ScorePDF metadata is invalid", error)
    }

    private fun requireSafePdfName(name: String) {
        require(
            name.isNotBlank() &&
                name.length <= 255 &&
                '/' !in name &&
                '\\' !in name &&
                '\u0000' !in name &&
                name.endsWith(".pdf", ignoreCase = true),
        )
    }

    private fun uint32(bytes: ByteArray, offset: Int): Int {
        val value = uint32Long(bytes, offset)
        require(value <= Int.MAX_VALUE)
        return value.toInt()
    }

    private fun uint32Long(bytes: ByteArray, offset: Int): Long {
        require(offset >= 0 && bytes.size - offset >= Int.SIZE_BYTES)
        return ByteBuffer.wrap(bytes, offset, Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int.toLong() and UINT_MAX
    }

    private class HiveReader(
        private val bytes: ByteArray,
        private var offset: Int,
        private val limit: Int,
    ) {
        val remaining: Int get() = limit - offset

        fun byte(): Int {
            requireAvailable(1)
            return bytes[offset++].toInt() and 0xFF
        }

        fun requireByte(expected: Int) {
            require(byte() == expected)
        }

        fun numericKey(): Long {
            requireByte(0)
            return uint32Value()
        }

        fun stringValue(): String {
            requireByte(4)
            return string()
        }

        fun stringListValue() {
            requireByte(9)
            val count = itemCount()
            repeat(count) { string() }
        }

        fun dateTimeValue() {
            requireByte(18)
            double()
            byte()
        }

        fun integerListValue(): List<Long> = when (byte()) {
            6 -> List(itemCount()) { exactInteger(double()) }
            10 -> List(itemCount()) {
                requireByte(1)
                exactInteger(double())
            }
            else -> throw IllegalArgumentException("Unexpected Hive list type")
        }

        private fun string(): String {
            val count = uint32Value()
            require(count <= MAX_STRING_BYTES)
            val length = count.toInt()
            requireAvailable(length)
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val value = decoder.decode(ByteBuffer.wrap(bytes, offset, length)).toString()
            offset += length
            return value
        }

        private fun itemCount(): Int {
            val count = uint32Value()
            require(count <= MAX_ITEMS)
            return count.toInt()
        }

        private fun uint32Value(): Long {
            requireAvailable(Int.SIZE_BYTES)
            val value = uint32Long(bytes, offset)
            offset += Int.SIZE_BYTES
            return value
        }

        private fun double(): Double {
            requireAvailable(Double.SIZE_BYTES)
            val value = ByteBuffer.wrap(bytes, offset, Double.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .double
            offset += Double.SIZE_BYTES
            return value
        }

        private fun exactInteger(value: Double): Long {
            require(value.isFinite() && value >= 0.0 && value <= UINT_MAX.toDouble())
            val integer = value.toLong()
            require(integer.toDouble() == value)
            return integer
        }

        private fun requireAvailable(count: Int) {
            require(count >= 0 && remaining >= count)
        }
    }
}
