package it.rfmariano.nfcdroid

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import java.nio.charset.StandardCharsets
import kotlin.jvm.JvmName

object NdefTextCodec {
    private const val TEXT_LANGUAGE_CODE = "en"
    private const val TEL_SCHEME = "tel:"
    private const val MAILTO_SCHEME = "mailto:"

    enum class EditableRecordType(
        val displayName: String,
        val newRecordLabel: String
    ) {
        TEXT("Text", "New text record"),
        LINK("Link", "New link"),
        PHONE("Phone", "New phone number"),
        EMAIL("Email", "New email address")
    }

    data class EditableRecord(
        val originalRecordIndex: Int?,
        val type: EditableRecordType,
        val value: String
    )

    @JvmName("trimEditableRecordsForSave")
    fun trimForSave(records: List<EditableRecord>): List<EditableRecord> =
        records.map { record -> record.copy(value = record.value.trim()) }

    fun trimForSave(textRecords: List<String>): List<String> = textRecords.map { it.trim() }

    fun parseTextRecords(message: NdefMessage?): List<String> {
        return editableRecordsFromMessage(message)
            .filter { it.type == EditableRecordType.TEXT }
            .map { it.value }
    }

    fun editableRecordsFromMessage(message: NdefMessage?): List<EditableRecord> {
        if (message == null) return emptyList()
        return message.records.mapIndexedNotNull { index, record ->
            parseEditableRecord(record, index)
        }
    }

    fun buildMessage(records: List<EditableRecord>): NdefMessage {
        val normalizedRecords = trimForSave(records)
        val ndefRecords = normalizedRecords.map(::toNdefRecord)
        return NdefMessage(ndefRecords.toTypedArray())
    }

    fun buildTextMessage(textRecords: List<String>): NdefMessage {
        val records = textRecords.map { value ->
            EditableRecord(
                originalRecordIndex = null,
                type = EditableRecordType.TEXT,
                value = value
            )
        }
        return buildMessage(records)
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
        editableRecords: List<EditableRecord>
    ): NdefMessage {
        val normalizedRecords = trimForSave(editableRecords)
        val replacements = normalizedRecords
            .filter { it.originalRecordIndex != null }
            .associateBy { it.originalRecordIndex!! }
        val retainedEditableIndices = replacements.keys
        val originalEditableIndices = originalMessage.records.mapIndexedNotNull { index, record ->
            if (parseEditableRecord(record, index) != null) index else null
        }.toSet()
        val removedEditableIndices = originalEditableIndices - retainedEditableIndices

        val patched = mutableListOf<NdefRecord>()
        originalMessage.records.forEachIndexed { index, record ->
            val replacement = replacements[index]
            if (replacement != null) {
                patched += toNdefRecord(replacement)
            } else if (index !in removedEditableIndices) {
                patched += record
            }
        }

        normalizedRecords
            .filter { it.originalRecordIndex == null }
            .forEach { patched += toNdefRecord(it) }

        return NdefMessage(patched.toTypedArray())
    }

    private fun parseEditableRecord(record: NdefRecord, index: Int): EditableRecord? {
        if (record.tnf != NdefRecord.TNF_WELL_KNOWN) return null
        return when {
            record.type.contentEquals(NdefRecord.RTD_TEXT) -> {
                EditableRecord(
                    originalRecordIndex = index,
                    type = EditableRecordType.TEXT,
                    value = decodeTextPayload(record.payload)
                )
            }

            record.type.contentEquals(NdefRecord.RTD_URI) -> uriToEditableRecord(index,
                decodeUriPayload(record.payload)
            )

            else -> null
        }
    }

    private fun uriToEditableRecord(index: Int, uri: String): EditableRecord {
        return when {
            uri.hasScheme(TEL_SCHEME) -> {
                EditableRecord(
                    originalRecordIndex = index,
                    type = EditableRecordType.PHONE,
                    value = removeScheme(uri, TEL_SCHEME)
                )
            }

            uri.hasScheme(MAILTO_SCHEME) -> {
                EditableRecord(
                    originalRecordIndex = index,
                    type = EditableRecordType.EMAIL,
                    value = removeScheme(uri, MAILTO_SCHEME)
                )
            }

            else -> {
                EditableRecord(
                    originalRecordIndex = index,
                    type = EditableRecordType.LINK,
                    value = uri
                )
            }
        }
    }

    private fun decodeUriPayload(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        val prefixIndex = payload[0].toInt() and 0xFF
        val prefix = URI_PREFIXES.getOrElse(prefixIndex) { "" }
        val remainder = String(payload.copyOfRange(1, payload.size), StandardCharsets.UTF_8)
        return prefix + remainder
    }

    private fun toNdefRecord(record: EditableRecord): NdefRecord {
        val trimmedValue = record.value.trim()
        require(trimmedValue.isNotEmpty()) { "${record.type.displayName} records cannot be blank." }

        return when (record.type) {
            EditableRecordType.TEXT -> NdefRecord.createTextRecord(TEXT_LANGUAGE_CODE, trimmedValue)
            EditableRecordType.LINK -> NdefRecord.createUri(trimmedValue)
            EditableRecordType.PHONE -> NdefRecord.createUri(TEL_SCHEME + trimmedValue)
            EditableRecordType.EMAIL -> NdefRecord.createUri(MAILTO_SCHEME + trimmedValue)
        }
    }

    private fun String.hasScheme(prefix: String): Boolean {
        return regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)
    }

    private fun removeScheme(value: String, prefix: String): String {
        return if (value.hasScheme(prefix)) value.substring(prefix.length) else value
    }

    private val URI_PREFIXES = listOf(
        "",
        "http://www.",
        "https://www.",
        "http://",
        "https://",
        "tel:",
        "mailto:",
        "ftp://anonymous:anonymous@",
        "ftp://ftp.",
        "ftps://",
        "sftp://",
        "smb://",
        "nfs://",
        "ftp://",
        "dav://",
        "news:",
        "telnet://",
        "imap:",
        "rtsp://",
        "urn:",
        "pop:",
        "sip:",
        "sips:",
        "tftp:",
        "btspp://",
        "btl2cap://",
        "btgoep://",
        "tcpobex://",
        "irdaobex://",
        "file://",
        "urn:epc:id:",
        "urn:epc:tag:",
        "urn:epc:pat:",
        "urn:epc:raw:",
        "urn:epc:",
        "urn:nfc:"
    )
}
