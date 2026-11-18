package com.smartdoorlock.bluetooth

/**
 * Text protocol shared with the ATmega32 firmware (see Firmware/README.md, section 6).
 * Every message is ASCII and ends with '\n'. Commands are case-sensitive.
 * The keypad PIN is NOT part of this protocol and is never sent.
 */
enum class McuCommand(val wire: String) {
    VOICE_OK("VOICE_OK"),
    VOICE_FAIL("VOICE_FAIL"),
    LOCK("LOCK"),
    STATUS("STATUS");

    fun toBytes(): ByteArray = (wire + "\n").toByteArray(Charsets.US_ASCII)
}

/** Responses the ATmega32 can send. */
enum class McuStatus {
    LOCKED,
    WAITING_PIN,
    UNLOCKED,
    ACCESS_DENIED;

    companion object {
        /** Exact, case-sensitive match. Returns null for anything else. */
        fun parse(line: String): McuStatus? = entries.firstOrNull { it.name == line }
    }
}

/**
 * Re-assembles '\n'-terminated lines from arbitrary RFCOMM read chunks.
 * - a line may arrive split over several reads, or several lines in one read
 * - '\r' is ignored (CRLF tolerant)
 * - lines longer than [maxLength] are dropped completely
 */
class LineAssembler(private val maxLength: Int = 64) {
    private val buffer = StringBuilder()
    private var overflow = false

    fun feed(bytes: ByteArray, count: Int = bytes.size): List<String> {
        val lines = mutableListOf<String>()
        for (i in 0 until count) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b == '\n'.code -> {
                    if (!overflow && buffer.isNotEmpty()) lines += buffer.toString()
                    buffer.setLength(0)
                    overflow = false
                }
                b == '\r'.code -> Unit
                overflow -> Unit
                buffer.length >= maxLength -> overflow = true
                else -> buffer.append(b.toChar())
            }
        }
        return lines
    }

    fun reset() {
        buffer.setLength(0)
        overflow = false
    }
}
