package it.rfmariano.nfcdroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class ExampleUnitTest {
    @Test
    fun buildTextMessage_trimsOnSave() {
        val normalized = NdefTextCodec.trimForSave(listOf("  first  ", "\tsecond\t"))
        assertEquals(listOf("first", "second"), normalized)
    }

    @Test
    fun trimForSave_keepsRecordOrder() {
        val normalized = NdefTextCodec.trimForSave(listOf(" one ", " three ", " new four "))
        assertEquals(listOf("one", "three", "new four"), normalized)
    }

    @Test
    fun decodeTextPayload_utf8_keepsFullString() {
        val text = "rfmariano.it"
        val lang = "en".toByteArray(StandardCharsets.US_ASCII)
        val payload = byteArrayOf(lang.size.toByte()) + lang + text.toByteArray(StandardCharsets.UTF_8)
        assertEquals(text, NdefTextCodec.decodeTextPayload(payload))
    }

    @Test
    fun decodeTextPayload_utf16_keepsFullString() {
        val text = "rfmariano.it"
        val lang = "en".toByteArray(StandardCharsets.US_ASCII)
        val status = (0x80 or lang.size).toByte()
        val payload = byteArrayOf(status) + lang + text.toByteArray(StandardCharsets.UTF_16BE)
        assertEquals(text, NdefTextCodec.decodeTextPayload(payload))
    }

    @Test
    fun parsePassword_supportsFourAsciiCharacters() {
        assertEquals("A1b!", String(Ntag215Manager.parsePassword("A1b!"), StandardCharsets.UTF_8))
    }

    @Test
    fun parsePassword_supportsEightHexDigits() {
        val parsed = Ntag215Manager.parsePassword("41424344")
        assertTrue(parsed.contentEquals(byteArrayOf(0x41, 0x42, 0x43, 0x44)))
    }

    @Test
    fun derivePack_xorsPasswordPairs() {
        val pack = Ntag215Manager.derivePack(byteArrayOf(0x41, 0x42, 0x43, 0x44))
        assertTrue(pack.contentEquals(byteArrayOf(0x02, 0x06)))
    }

    @Test
    fun decodeSecurityInfo_detectsWriteProtection() {
        val info = Ntag215Manager.decodeSecurityInfo(
            byteArrayOf(
                0x00,
                0x00,
                0x00,
                0x04,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x12,
                0x34,
                0x00,
                0x00
            )
        )

        assertTrue(info.isWriteProtected)
        assertEquals(4, info.auth0Page)
        assertFalse(info.protectRead)
        assertTrue(info.pack.contentEquals(byteArrayOf(0x12, 0x34)))
    }
}
