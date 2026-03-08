package it.rfmariano.nfcdroid

import org.junit.Assert.assertEquals
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
}
