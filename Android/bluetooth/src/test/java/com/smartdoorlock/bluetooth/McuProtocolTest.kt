package com.smartdoorlock.bluetooth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class McuProtocolTest {

    @Test
    fun commandsEndWithNewline() {
        assertArrayEquals("VOICE_OK\n".toByteArray(), McuCommand.VOICE_OK.toBytes())
        assertArrayEquals("VOICE_FAIL\n".toByteArray(), McuCommand.VOICE_FAIL.toBytes())
        assertArrayEquals("LOCK\n".toByteArray(), McuCommand.LOCK.toBytes())
        assertArrayEquals("STATUS\n".toByteArray(), McuCommand.STATUS.toBytes())
    }

    @Test
    fun statusParsingIsExactAndCaseSensitive() {
        assertEquals(McuStatus.LOCKED, McuStatus.parse("LOCKED"))
        assertEquals(McuStatus.WAITING_PIN, McuStatus.parse("WAITING_PIN"))
        assertEquals(McuStatus.UNLOCKED, McuStatus.parse("UNLOCKED"))
        assertEquals(McuStatus.ACCESS_DENIED, McuStatus.parse("ACCESS_DENIED"))
        assertNull(McuStatus.parse("locked"))
        assertNull(McuStatus.parse(" LOCKED"))
        assertNull(McuStatus.parse("LOCKED1"))
    }

    @Test
    fun assemblerHandlesSplitAndMultipleLines() {
        val a = LineAssembler()
        assertEquals(emptyList<String>(), a.feed("WAIT".toByteArray()))
        assertEquals(listOf("WAITING_PIN"), a.feed("ING_PIN\n".toByteArray()))
        assertEquals(listOf("UNLOCKED", "LOCKED"), a.feed("UNLOCKED\r\nLOCKED\n".toByteArray()))
    }

    @Test
    fun assemblerDropsOverlongLinesAndEmptyLines() {
        val a = LineAssembler(maxLength = 8)
        assertEquals(listOf("LOCKED"), a.feed("THIS_IS_TOO_LONG\n\nLOCKED\n".toByteArray()))
    }

    @Test
    fun assemblerRespectsCount() {
        val a = LineAssembler()
        val buf = "LOCKED\nGARBAGE".toByteArray()
        assertEquals(listOf("LOCKED"), a.feed(buf, 7))
    }
}
