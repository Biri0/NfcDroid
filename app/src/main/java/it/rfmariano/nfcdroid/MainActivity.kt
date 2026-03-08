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

    private var nfcAdapter: NfcAdapter? = null
    private var currentTag: Tag? = null
    private var originalMessage: NdefMessage? = null
    private var editableTextRecords by mutableStateOf<List<NdefTextCodec.EditableTextRecord>>(emptyList())
    private var newRecordValue by mutableStateOf("")
    private var writeState by mutableStateOf(WriteState.IDLE)
    private var armedTagId: ByteArray? = null
    private var statusMessage by mutableStateOf("Scan an NFC tag to load text records.")

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
                        records = editableTextRecords.map { it.text },
                        newRecordValue = newRecordValue,
                        onRecordChange = { index, value ->
                            editableTextRecords = editableTextRecords.toMutableList().also {
                                it[index] = it[index].copy(text = value)
                            }
                        },
                        onRemoveRecord = { index ->
                            editableTextRecords = editableTextRecords.toMutableList().also { it.removeAt(index) }
                        },
                        onNewRecordChange = { newRecordValue = it },
                        onAddRecord = {
                            editableTextRecords = editableTextRecords +
                                NdefTextCodec.EditableTextRecord(originalRecordIndex = null, text = newRecordValue)
                            newRecordValue = ""
                        },
                        onWrite = { armWrite() },
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
                editableTextRecords = emptyList()
                statusMessage = "NDEF unavailable. Detected technologies: $techSummary"
            }
            return
        }

        try {
            ndef.connect()
            val message = ndef.cachedNdefMessage ?: ndef.ndefMessage
            val parsedRecords = NdefTextCodec.editableTextRecordsFromMessage(message)
            val shouldWriteNow = writeState == WriteState.WAITING_NEXT_SCAN
            val recordsSnapshot = editableTextRecords
            val writeMessage = if (shouldWriteNow) buildMessageForWrite(message, recordsSnapshot) else null
            if (shouldWriteNow && writeMessage == null) {
                runOnUiThread {
                    writeState = WriteState.IDLE
                    armedTagId = null
                    statusMessage = "Cannot write empty NDEF message."
                }
                return
            }
            runOnUiThread {
                currentTag = tag
                originalMessage = message
                editableTextRecords = parsedRecords
                statusMessage = if (shouldWriteNow) {
                    "Writing to tag..."
                } else if (parsedRecords.isEmpty()) {
                    "NDEF tag loaded. No text records found. Tech: $techSummary"
                } else {
                    "NDEF tag loaded: ${parsedRecords.size} text record(s). Tech: $techSummary"
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
                val updatedRecords = NdefTextCodec.editableTextRecordsFromMessage(writeMessage)
                runOnUiThread {
                    originalMessage = writeMessage
                    editableTextRecords = updatedRecords
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

        if (editableTextRecords.isEmpty()) {
            statusMessage = "No editable text records to write."
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
        recordsToWrite: List<NdefTextCodec.EditableTextRecord>
    ): NdefMessage? {
        return try {
            if (baseMessage != null) {
                NdefTextCodec.patchMessage(baseMessage, recordsToWrite)
            } else {
                val trimmedRecords = NdefTextCodec.trimForSave(recordsToWrite.map { it.text })
                if (trimmedRecords.isEmpty()) {
                    null
                } else {
                    NdefTextCodec.buildTextMessage(trimmedRecords)
                }
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

@Composable
fun NfcEditorScreen(
    statusMessage: String,
    records: List<String>,
    newRecordValue: String,
    onRecordChange: (Int, String) -> Unit,
    onRemoveRecord: (Int) -> Unit,
    onNewRecordChange: (String) -> Unit,
    onAddRecord: () -> Unit,
    onWrite: () -> Unit,
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

        records.forEachIndexed { index, value ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { onRecordChange(index, it) },
                    label = { Text("Text record ${index + 1}") },
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = { onRemoveRecord(index) }) {
                    Text("Remove")
                }
            }
        }

        OutlinedTextField(
            value = newRecordValue,
            onValueChange = onNewRecordChange,
            label = { Text("New text record") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onAddRecord) {
                Text("Add record")
            }
            Button(onClick = onWrite) {
                Text(if (isWriteArmed) "Write armed" else "Write tag")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    NfcDroidTheme {
        NfcEditorScreen(
            statusMessage = "Scan an NFC tag to load text records.",
            records = listOf("Record A", "Record B"),
            newRecordValue = "",
            onRecordChange = { _, _ -> },
            onRemoveRecord = {},
            onNewRecordChange = {},
            onAddRecord = {},
            onWrite = {},
            isWriteArmed = false
        )
    }
}
