package it.rfmariano.nfcdroid

import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import android.os.Handler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import it.rfmariano.nfcdroid.ui.theme.NfcDroidTheme
import java.io.IOException

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {
    private enum class WriteState {
        IDLE,
        WAITING_REMOVAL,
        WAITING_NEXT_SCAN
    }

    private sealed interface WriteMessagePreparation {
        data class Ready(val message: NdefMessage) : WriteMessagePreparation
        data object Empty : WriteMessagePreparation
        data class Invalid(val reason: String) : WriteMessagePreparation
    }

    private var nfcAdapter: NfcAdapter? = null
    private var currentTag: Tag? = null
    private var originalMessage: NdefMessage? = null
    private var editableRecords by mutableStateOf<List<NdefTextCodec.EditableRecord>>(emptyList())
    private var newRecordType by mutableStateOf(NdefTextCodec.EditableRecordType.TEXT)
    private var newRecordValue by mutableStateOf("")
    private var writeState by mutableStateOf(WriteState.IDLE)
    private var armedTagId: ByteArray? = null
    private var statusMessage by mutableStateOf("Scan an NFC tag to load editable records.")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            statusMessage = "NFC is not available on this device."
        }
        setContent {
            NfcDroidTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NfcEditorScreen(
                        statusMessage = statusMessage,
                        records = editableRecords,
                        newRecordType = newRecordType,
                        newRecordValue = newRecordValue,
                        onRecordChange = { index, value ->
                            editableRecords = editableRecords.toMutableList().also {
                                it[index] = it[index].copy(value = value)
                            }
                        },
                        onRemoveRecord = { index ->
                            editableRecords = editableRecords.toMutableList().also { it.removeAt(index) }
                        },
                        onNewRecordTypeChange = { newRecordType = it },
                        onNewRecordChange = { newRecordValue = it },
                        onAddRecord = {
                            if (newRecordValue.isBlank()) {
                                statusMessage = "${newRecordType.displayName} value cannot be blank."
                            } else {
                                editableRecords = editableRecords +
                                    NdefTextCodec.EditableRecord(
                                        originalRecordIndex = null,
                                        type = newRecordType,
                                        value = newRecordValue
                                    )
                                newRecordValue = ""
                            }
                        },
                        onWrite = { armWrite() },
                        canAddRecord = newRecordValue.isNotBlank(),
                        isWriteArmed = writeState != WriteState.IDLE,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter ?: return
        adapter.enableReaderMode(
            this,
            this,
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V,
            null
        )
    }

    override fun onPause() {
        nfcAdapter?.disableReaderMode(this)
        super.onPause()
    }

    override fun onTagDiscovered(tag: Tag) {
        val techSummary = tag.techList.joinToString(", ") { it.substringAfterLast('.') }
        if (writeState == WriteState.WAITING_REMOVAL) {
            runOnUiThread { statusMessage = "Write armed. Remove card from the phone first." }
            return
        }
        if (
            writeState == WriteState.WAITING_NEXT_SCAN &&
            armedTagId != null &&
            !armedTagId!!.contentEquals(tag.id)
        ) {
            runOnUiThread { statusMessage = "Different tag detected. Tap the original card to write." }
            return
        }

        val ndef = Ndef.get(tag)
        if (ndef == null) {
            runOnUiThread {
                currentTag = tag
                originalMessage = null
                editableRecords = emptyList()
                statusMessage = "NDEF unavailable. Detected technologies: $techSummary"
            }
            return
        }

        try {
            ndef.connect()
            val message = ndef.cachedNdefMessage ?: ndef.ndefMessage
            val parsedRecords = NdefTextCodec.editableRecordsFromMessage(message)
            val shouldWriteNow = writeState == WriteState.WAITING_NEXT_SCAN
            val recordsSnapshot = editableRecords
            val writePreparation = if (shouldWriteNow) {
                buildMessageForWrite(message, recordsSnapshot)
            } else {
                null
            }
            if (shouldWriteNow && writePreparation != null && writePreparation !is WriteMessagePreparation.Ready) {
                runOnUiThread {
                    writeState = WriteState.IDLE
                    armedTagId = null
                    statusMessage = when (writePreparation) {
                        WriteMessagePreparation.Empty -> "Cannot write empty NDEF message."
                        is WriteMessagePreparation.Invalid -> writePreparation.reason
                        is WriteMessagePreparation.Ready -> "Preparing write."
                    }
                }
                return
            }
            val writeMessage = (writePreparation as? WriteMessagePreparation.Ready)?.message
            runOnUiThread {
                currentTag = tag
                originalMessage = message
                editableRecords = parsedRecords
                statusMessage = if (shouldWriteNow) {
                    "Writing to tag..."
                } else if (parsedRecords.isEmpty()) {
                    "NDEF tag loaded. No editable records found. Tech: $techSummary"
                } else {
                    "NDEF tag loaded: ${parsedRecords.size} editable record(s). Tech: $techSummary"
                }
            }
            if (shouldWriteNow) {
                if (!ndef.isWritable) {
                    runOnUiThread {
                        writeState = WriteState.IDLE
                        armedTagId = null
                        statusMessage = "Tag is read-only."
                    }
                    return
                }
                val requiredSize = writeMessage!!.toByteArray().size
                if (requiredSize > ndef.maxSize) {
                    runOnUiThread {
                        writeState = WriteState.IDLE
                        armedTagId = null
                        statusMessage = "NDEF message too large for this tag."
                    }
                    return
                }
                ndef.writeNdefMessage(writeMessage)
                val updatedRecords = NdefTextCodec.editableRecordsFromMessage(writeMessage)
                runOnUiThread {
                    originalMessage = writeMessage
                    editableRecords = updatedRecords
                    writeState = WriteState.IDLE
                    armedTagId = null
                    statusMessage = "Tag written successfully."
                }
            }
        } catch (ioException: IOException) {
            runOnUiThread {
                statusMessage = "Failed to read tag: ${ioException.message ?: "I/O error"}"
            }
        } catch (formatException: FormatException) {
            runOnUiThread {
                statusMessage = "Invalid NDEF data: ${formatException.message ?: "format error"}"
            }
        } finally {
            try {
                ndef.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun armWrite() {
        if (writeState != WriteState.IDLE) {
            statusMessage = "Write is already armed. Remove and tap card to continue."
            return
        }

        if (editableRecords.isEmpty()) {
            statusMessage = "No editable records to write."
            return
        }

        val tag = currentTag
        if (tag == null) {
            writeState = WriteState.WAITING_NEXT_SCAN
            armedTagId = null
            statusMessage = "Write armed. Tap card to write."
            return
        }

        writeState = WriteState.WAITING_REMOVAL
        armedTagId = tag.id
        val ignoreStarted = nfcAdapter?.ignore(
            tag,
            250,
            NfcAdapter.OnTagRemovedListener {
                runOnUiThread {
                    if (writeState == WriteState.WAITING_REMOVAL) {
                        writeState = WriteState.WAITING_NEXT_SCAN
                        currentTag = null
                        statusMessage = "Card removed. Tap card again to write."
                    }
                }
            },
            Handler(mainLooper)
        ) ?: false

        if (ignoreStarted) {
            statusMessage = "Write armed. Remove card from the phone."
        } else {
            writeState = WriteState.WAITING_NEXT_SCAN
            statusMessage = "Write armed. Remove card, then tap again to write."
        }
    }

    private fun buildMessageForWrite(
        baseMessage: NdefMessage?,
        recordsToWrite: List<NdefTextCodec.EditableRecord>
    ): WriteMessagePreparation {
        val trimmedRecords = NdefTextCodec.trimForSave(recordsToWrite)
        if (trimmedRecords.isEmpty()) {
            return WriteMessagePreparation.Empty
        }

        return try {
            val message = if (baseMessage != null) {
                NdefTextCodec.patchMessage(baseMessage, trimmedRecords)
            } else {
                NdefTextCodec.buildMessage(trimmedRecords)
            }
            WriteMessagePreparation.Ready(message)
        } catch (illegalArgumentException: IllegalArgumentException) {
            WriteMessagePreparation.Invalid(
                illegalArgumentException.message ?: "Record data is invalid."
            )
        }
    }
}

@Composable
fun NfcEditorScreen(
    statusMessage: String,
    records: List<NdefTextCodec.EditableRecord>,
    newRecordType: NdefTextCodec.EditableRecordType,
    newRecordValue: String,
    onRecordChange: (Int, String) -> Unit,
    onRemoveRecord: (Int) -> Unit,
    onNewRecordTypeChange: (NdefTextCodec.EditableRecordType) -> Unit,
    onNewRecordChange: (String) -> Unit,
    onAddRecord: () -> Unit,
    onWrite: () -> Unit,
    canAddRecord: Boolean,
    isWriteArmed: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = statusMessage)

        records.forEachIndexed { index, record ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = record.value,
                    onValueChange = { onRecordChange(index, it) },
                    label = { Text("${record.type.displayName} record ${index + 1}") },
                    keyboardOptions = KeyboardOptions(keyboardType = record.type.keyboardType),
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = { onRemoveRecord(index) }) {
                    Text("Remove")
                }
            }
        }

        Text("Add new record")
        RecordTypePicker(
            selectedType = newRecordType,
            onTypeSelected = onNewRecordTypeChange
        )
        OutlinedTextField(
            value = newRecordValue,
            onValueChange = onNewRecordChange,
            label = { Text(newRecordType.newRecordLabel) },
            keyboardOptions = KeyboardOptions(keyboardType = newRecordType.keyboardType),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onAddRecord, enabled = canAddRecord) {
                Text("Add ${newRecordType.displayName.lowercase()} record")
            }
            Button(onClick = onWrite) {
                Text(if (isWriteArmed) "Write armed" else "Write tag")
            }
        }
    }
}

@Composable
private fun RecordTypePicker(
    selectedType: NdefTextCodec.EditableRecordType,
    onTypeSelected: (NdefTextCodec.EditableRecordType) -> Unit
) {
    NdefTextCodec.EditableRecordType.values().toList().chunked(2).forEach { rowTypes ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            rowTypes.forEach { type ->
                val buttonModifier = Modifier.weight(1f)
                if (type == selectedType) {
                    Button(onClick = { onTypeSelected(type) }, modifier = buttonModifier) {
                        Text(type.displayName)
                    }
                } else {
                    OutlinedButton(onClick = { onTypeSelected(type) }, modifier = buttonModifier) {
                        Text(type.displayName)
                    }
                }
            }
            if (rowTypes.size == 1) {
                Column(modifier = Modifier.weight(1f)) {}
            }
        }
    }
}

private val NdefTextCodec.EditableRecordType.keyboardType: KeyboardType
    get() = when (this) {
        NdefTextCodec.EditableRecordType.TEXT -> KeyboardType.Text
        NdefTextCodec.EditableRecordType.LINK -> KeyboardType.Uri
        NdefTextCodec.EditableRecordType.PHONE -> KeyboardType.Phone
        NdefTextCodec.EditableRecordType.EMAIL -> KeyboardType.Email
    }

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    NfcDroidTheme {
        NfcEditorScreen(
            statusMessage = "Scan an NFC tag to load editable records.",
            records = listOf(
                NdefTextCodec.EditableRecord(
                    originalRecordIndex = 0,
                    type = NdefTextCodec.EditableRecordType.TEXT,
                    value = "Record A"
                ),
                NdefTextCodec.EditableRecord(
                    originalRecordIndex = 1,
                    type = NdefTextCodec.EditableRecordType.LINK,
                    value = "https://example.com"
                )
            ),
            newRecordType = NdefTextCodec.EditableRecordType.TEXT,
            newRecordValue = "",
            onRecordChange = { _, _ -> },
            onRemoveRecord = {},
            onNewRecordTypeChange = {},
            onNewRecordChange = {},
            onAddRecord = {},
            onWrite = {},
            canAddRecord = false,
            isWriteArmed = false
        )
    }
}
