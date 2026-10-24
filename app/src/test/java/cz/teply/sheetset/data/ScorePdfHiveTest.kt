package cz.teply.sheetset.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class ScorePdfHiveTest {
    @Test
    fun latestFramesAndDeletionProduceLiveScores() {
        val hive = frame(7, score("Old", "shared.pdf")) +
            frame(8, score("Deleted", "deleted.pdf")) +
            frame(7, score("Final", "shared.pdf")) +
            frame(8, null) +
            frame(9, score("Duplicate", "shared.pdf"))

        assertEquals(
            listOf(
                ScorePdfScore(7, "Final", "shared.pdf"),
                ScorePdfScore(9, "Duplicate", "shared.pdf"),
            ),
            ScorePdfHive.readScores(hive),
        )
    }

    @Test
    fun setlistKeepsEveryOrderedOccurrence() {
        val hive = frame(0, setlist("Show", listOf(7, 9, 7)))

        assertEquals(
            listOf(ScorePdfSetlist("Show", listOf(7, 9, 7))),
            ScorePdfHive.readSetlists(hive),
        )
    }

    @Test
    fun emptySetlistIsPreserved() {
        assertEquals(
            listOf(ScorePdfSetlist("Show", emptyList())),
            ScorePdfHive.readSetlists(frame(39, setlist("Show", emptyList()))),
        )
    }

    @Test
    fun listLengthCannotReadIntoFrameCrc() {
        // The fourth list-count byte is missing; the first CRC byte is zero.
        val hive = "1e0000000027000000240200040400000053686f77010a000000006dade3"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        assertThrows(BackupException::class.java) {
            ScorePdfHive.readSetlists(hive)
        }
    }

    @Test
    fun corruptedFrameIsRejected() {
        val hive = frame(7, score("Score", "score.pdf")).also { bytes ->
            bytes[12] = (bytes[12].toInt() xor 1).toByte()
        }

        assertThrows(BackupException::class.java) {
            ScorePdfHive.readScores(hive)
        }
    }

    private fun score(title: String, fileName: String): ByteArray = bytes {
        byte(32)
        byte(6)
        field(0) { string("2026-08-23 12:00:00") }
        field(1) { string(title) }
        field(2) {
            byte(9)
            uint32(0)
        }
        field(3) { dateTime() }
        field(4) { dateTime() }
        field(5) { string(fileName) }
    }

    private fun setlist(name: String, keys: List<Int>): ByteArray = bytes {
        byte(36)
        byte(2)
        field(0) { string(name) }
        field(1) {
            byte(10)
            uint32(keys.size)
            keys.forEach { key ->
                byte(1)
                double(key.toDouble())
            }
        }
    }

    private fun frame(key: Int, value: ByteArray?): ByteArray {
        val payload = bytes {
            byte(0)
            uint32(key)
            value?.let(::raw)
        }
        val length = payload.size + 8
        val withoutCrc = bytes {
            uint32(length)
            raw(payload)
        }
        val crc = CRC32().apply { update(withoutCrc) }.value.toInt()
        return bytes {
            raw(withoutCrc)
            uint32(crc)
        }
    }

    private fun bytes(write: HiveWriter.() -> Unit): ByteArray =
        HiveWriter().apply(write).toByteArray()

    private class HiveWriter {
        private val output = ByteArrayOutputStream()

        fun byte(value: Int) = output.write(value)

        fun uint32(value: Int) = raw(
            ByteBuffer.allocate(Int.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(value)
                .array(),
        )

        fun double(value: Double) = raw(
            ByteBuffer.allocate(Double.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putDouble(value)
                .array(),
        )

        fun string(value: String) {
            val encoded = value.toByteArray(Charsets.UTF_8)
            byte(4)
            uint32(encoded.size)
            raw(encoded)
        }

        fun field(id: Int, write: HiveWriter.() -> Unit) {
            byte(id)
            write()
        }

        fun dateTime() {
            byte(18)
            double(0.0)
            byte(0)
        }

        fun raw(value: ByteArray) = output.write(value)

        fun toByteArray(): ByteArray = output.toByteArray()
    }
}
