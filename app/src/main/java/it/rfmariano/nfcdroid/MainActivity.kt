package it.rfmariano.nfcdroid

import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
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

enum class PendingAction {
    NONE,
    WRITE,
    PROTECT,
    UNPROTECT
}

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {
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
    private var passwordInput by mutableStateOf("")
    private var pendingAction by mutableStateOf(PendingAction.NONE)
    private var tagSecurityInfo by mutableStateOf<Ntag215Manager.TagSecurityInfo?>(null)
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
                        passwordInput = passwordInput,
                        securityInfo = tagSecurityInfo,
                        pendingAction = pendingAction,
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
                        onPasswordChange = { passwordInput = it },
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
                        onWrite = { armAction(PendingAction.WRITE) },
                        onProtect = { armAction(PendingAction.PROTECT) },
                        onUnprotect = { armAction(PendingAction.UNPROTECT) },
                        canAddRecord = newRecordValue.isNotBlank(),
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
        val security = try {
            Ntag215Manager.inspect(tag)
        } catch (_: Exception) {
            null
        }

        if (pendingAction != PendingAction.NONE) {
            performPendingAction(tag, techSummary, security)
        } else {
            loadTag(tag, techSummary, security)
        }
    }

    private fun armAction(action: PendingAction) {
        if (action == PendingAction.WRITE && editableRecords.isEmpty()) {
            statusMessage = "No editable records to write."
            return
        }
        pendingAction = action
        currentTag = null
        statusMessage = when (action) {
            PendingAction.NONE -> "Scan an NFC tag to load editable records."
            PendingAction.WRITE -> "Write armed. Tap tag to write."
            PendingAction.PROTECT -> "Protection armed. Tap an NTAG215 to set the password."
            PendingAction.UNPROTECT -> "Remove-password armed. Tap an NTAG215 and enter its password."
        }
    }

    private fun performPendingAction(
        tag: Tag,
        techSummary: String,
        security: Ntag215Manager.TagSecurityInfo?
    ) {
        try {
            when (pendingAction) {
                PendingAction.WRITE -> performWrite(tag, techSummary, security)
                PendingAction.PROTECT -> performProtect(tag, techSummary)
                PendingAction.UNPROTECT -> performUnprotect(tag, techSummary)
                PendingAction.NONE -> loadTag(tag, techSummary, security)
            }
        } catch (exception: Exception) {
            runOnUiThread {
                pendingAction = PendingAction.NONE
                statusMessage = exception.message ?: "Tag operation failed."
            }
        }
    }

    private fun performWrite(tag: Tag, techSummary: String, security: Ntag215Manager.TagSecurityInfo?) {
        val writePreparation = buildMessageForWrite(originalMessage, editableRecords)
        if (writePreparation !is WriteMessagePreparation.Ready) {
            runOnUiThread {
                pendingAction = PendingAction.NONE
                statusMessage = when (writePreparation) {
                    WriteMessagePreparation.Empty -> "Cannot write empty NDEF message."
                    is WriteMessagePreparation.Invalid -> writePreparation.reason
                    is WriteMessagePreparation.Ready -> "Preparing write."
                }
            }
            return
        }

        val writeMessage = writePreparation.message
        if (security?.isWriteProtected == true) {
            if (passwordInput.isBlank()) {
                runOnUiThread {
                    pendingAction = PendingAction.NONE
                    statusMessage = "This tag is password protected. Enter the password, then tap again."
                }
                return
            }
            val updatedSecurity = Ntag215Manager.writeProtectedNdef(tag, writeMessage, passwordInput)
            runOnUiThread {
                originalMessage = writeMessage
                editableRecords = NdefTextCodec.editableRecordsFromMessage(writeMessage)
                currentTag = tag
                tagSecurityInfo = updatedSecurity
                pendingAction = PendingAction.NONE
                statusMessage = "Protected NTAG215 written successfully."
            }
            return
        }

        val ndef = Ndef.get(tag)
        if (ndef == null) {
            runOnUiThread {
                pendingAction = PendingAction.NONE
                statusMessage = "NDEF unavailable. Detected technologies: $techSummary"
            }
            return
        }

        try {
            ndef.connect()
            if (!ndef.isWritable) {
                runOnUiThread {
                    pendingAction = PendingAction.NONE
                    statusMessage = "Tag is read-only."
                }
                return
            }
            if (writeMessage.toByteArray().size > ndef.maxSize) {
                runOnUiThread {
                    pendingAction = PendingAction.NONE
                    statusMessage = "NDEF message too large for this tag."
                }
                return
            }
            ndef.writeNdefMessage(writeMessage)
            runOnUiThread {
                originalMessage = writeMessage
                editableRecords = NdefTextCodec.editableRecordsFromMessage(writeMessage)
                currentTag = tag
                tagSecurityInfo = security
                pendingAction = PendingAction.NONE
                statusMessage = "Tag written successfully."
            }
        } finally {
            try {
                ndef.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun performProtect(tag: Tag, techSummary: String) {
        val updatedSecurity = Ntag215Manager.protect(tag, passwordInput, protectRead = false)
        runOnUiThread {
            currentTag = tag
            tagSecurityInfo = updatedSecurity
            pendingAction = PendingAction.NONE
            statusMessage = "NTAG215 password set. Reads stay open, writes now require the password. Tech: $techSummary"
        }
    }

    private fun performUnprotect(tag: Tag, techSummary: String) {
        val updatedSecurity = Ntag215Manager.unprotect(tag, passwordInput)
        runOnUiThread {
            currentTag = tag
            tagSecurityInfo = updatedSecurity
            pendingAction = PendingAction.NONE
            statusMessage = "NTAG215 password removed. Tag is writable again. Tech: $techSummary"
        }
    }

    private fun loadTag(tag: Tag, techSummary: String, security: Ntag215Manager.TagSecurityInfo?) {
        val ndef = Ndef.get(tag)
        if (ndef == null) {
            runOnUiThread {
                currentTag = tag
                originalMessage = null
                editableRecords = emptyList()
                tagSecurityInfo = security
                statusMessage = buildString {
                    append("NDEF unavailable. Detected technologies: ")
                    append(techSummary)
                    if (security?.isNtag215 == true) {
                        append(". NTAG215 ")
                        append(if (security.isWriteProtected) "is write-protected." else "has no password.")
                    }
                }
            }
            return
        }

        try {
            ndef.connect()
            val message = ndef.cachedNdefMessage ?: ndef.ndefMessage
            val parsedRecords = NdefTextCodec.editableRecordsFromMessage(message)
            runOnUiThread {
                currentTag = tag
                originalMessage = message
                editableRecords = parsedRecords
                tagSecurityInfo = security
                statusMessage = buildStatusMessage(parsedRecords, techSummary, security)
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

    private fun buildStatusMessage(
        parsedRecords: List<NdefTextCodec.EditableRecord>,
        techSummary: String,
        security: Ntag215Manager.TagSecurityInfo?
    ): String {
        val base = if (parsedRecords.isEmpty()) {
            "NDEF tag loaded. No editable records found."
        } else {
            "NDEF tag loaded: ${parsedRecords.size} editable record(s)."
        }
        val securityText = when {
            security?.isNtag215 != true -> ""
            security.isWriteProtected -> " NTAG215 is write-protected. Enter password only when writing or removing protection."
            else -> " NTAG215 has no password configured."
        }
        return "$base Tech: $techSummary.$securityText"
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
    passwordInput: String,
    securityInfo: Ntag215Manager.TagSecurityInfo?,
    pendingAction: PendingAction,
    onRecordChange: (Int, String) -> Unit,
    onRemoveRecord: (Int) -> Unit,
    onNewRecordTypeChange: (NdefTextCodec.EditableRecordType) -> Unit,
    onNewRecordChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onAddRecord: () -> Unit,
    onWrite: () -> Unit,
    onProtect: () -> Unit,
    onUnprotect: () -> Unit,
    canAddRecord: Boolean,
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
        Text(text = securityInfo.describe())

        OutlinedTextField(
            value = passwordInput,
            onValueChange = onPasswordChange,
            label = { Text("NTAG215 password (4 chars or 8 hex)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onWrite, modifier = Modifier.weight(1f)) {
                Text(if (pendingAction == PendingAction.WRITE) "Tap tag to write" else "Write tag")
            }
            OutlinedButton(onClick = onProtect, modifier = Modifier.weight(1f)) {
                Text(if (pendingAction == PendingAction.PROTECT) "Tap to protect" else "Protect tag")
            }
        }

        OutlinedButton(onClick = onUnprotect, modifier = Modifier.fillMaxWidth()) {
            Text(if (pendingAction == PendingAction.UNPROTECT) "Tap to remove password" else "Remove password")
        }

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
        OutlinedButton(onClick = onAddRecord, enabled = canAddRecord) {
            Text("Add ${newRecordType.displayName.lowercase()} record")
        }
    }
}

private fun Ntag215Manager.TagSecurityInfo?.describe(): String {
    if (this == null) return "No NTAG215 security info for the current tag yet."
    if (!isNtag215) return "Current tag is not an NTAG215."
    return if (isWriteProtected) {
        "NTAG215 status: password enabled for writes from page $auth0Page."
    } else {
        "NTAG215 status: no password configured."
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
            passwordInput = "A1B2",
            securityInfo = Ntag215Manager.TagSecurityInfo(
                isNtag215 = true,
                isWriteProtected = true,
                auth0Page = 4,
                protectRead = false,
                pack = byteArrayOf(0x00, 0x00)
            ),
            pendingAction = PendingAction.NONE,
            onRecordChange = { _, _ -> },
            onRemoveRecord = {},
            onNewRecordTypeChange = {},
            onNewRecordChange = {},
            onPasswordChange = {},
            onAddRecord = {},
            onWrite = {},
            onProtect = {},
            onUnprotect = {},
            canAddRecord = false
        )
    }
}
