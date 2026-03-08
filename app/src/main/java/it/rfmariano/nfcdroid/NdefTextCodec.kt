package it.rfmariano.nfcdroid

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import java.nio.charset.StandardCharsets

object NdefTextCodec {
    data class EditableTextRecord(
        val originalRecordIndex: Int?,
        val text: String
    )

    fun trimForSave(textRecords: List<String>): List<String> = textRecords.map { it.trim() }

    fun parseTextRecords(message: NdefMessage?): List<String> {
        return editableTextRecordsFromMessage(message).map { it.text }
    }

    fun editableTextRecordsFromMessage(message: NdefMessage?): List<EditableTextRecord> {
        if (message == null) return emptyList()
        return message.records.mapIndexedNotNull { index, record ->
            parseTextRecord(record)?.let { text ->
                EditableTextRecord(originalRecordIndex = index, text = text)
            }
        }
    }

    fun buildTextMessage(textRecords: List<String>): NdefMessage {
        val records = trimForSave(textRecords).map { value ->
            NdefRecord.createTextRecord("en", value)
        }
        return NdefMessage(records.toTypedArray())
    }

    fun decodeTextPayload(payload: ByteArray): String {
        if (payload.isEmpty()) return ""

        val statusByte = payload[0].toInt() and 0xFF
        val isUtf16 = statusByte and 0x80 != 0
        val languageCodeLength = statusByte and 0x3F
        val textStart = 1 + languageCodeLength
        if (textStart > payload.size) return ""

        val textBytes = payload.copyOfRange(textStart, payload.size)
        val charset = if (isUtf16) StandardCharsets.UTF_16BE else StandardCharsets.UTF_8
        return String(textBytes, charset)
    }

    fun patchMessage(
        originalMessage: NdefMessage,
        editableTextRecords: List<EditableTextRecord>
    ): NdefMessage {
        val normalizedRecords = editableTextRecords.map { record ->
            record.copy(text = record.text.trim())
        }
        val replacements = normalizedRecords
            .filter { it.originalRecordIndex != null }
            .associateBy { it.originalRecordIndex!! }
        val retainedTextIndices = replacements.keys
        val originalTextIndices = originalMessage.records.mapIndexedNotNull { index, record ->
            if (parseTextRecord(record) != null) index else null
        }.toSet()
        val removedTextIndices = originalTextIndices - retainedTextIndices

        val patched = mutableListOf<NdefRecord>()
        originalMessage.records.forEachIndexed { index, record ->
            val replacement = replacements[index]
            if (replacement != null) {
                patched += NdefRecord.createTextRecord("en", replacement.text)
            } else if (index !in removedTextIndices) {
                patched += record
            }
        }

        normalizedRecords
            .filter { it.originalRecordIndex == null }
            .forEach { patched += NdefRecord.createTextRecord("en", it.text) }

        return NdefMessage(patched.toTypedArray())
    }

    private fun parseTextRecord(record: NdefRecord): String? {
        if (record.tnf != NdefRecord.TNF_WELL_KNOWN) return null
        if (!record.type.contentEquals(NdefRecord.RTD_TEXT)) return null

        return decodeTextPayload(record.payload)
    }
}
