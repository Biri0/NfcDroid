package it.rfmariano.nfcdroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import it.rfmariano.nfcdroid.ui.theme.NfcDroidTheme
import java.io.IOException
import kotlinx.coroutines.launch

enum class PendingAction {
    NONE,
    WRITE,
    PROTECT,
    UNPROTECT
}

private enum class EditorStep {
    SCAN,
    EDIT,
    WRITE
}

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {
    companion object {
        private const val NFC_DISABLED_MESSAGE = "NFC is disabled. Enable it to continue using the app."
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
    private var passwordInput by mutableStateOf("")
    private var pendingAction by mutableStateOf(PendingAction.NONE)
    private var currentStep by mutableStateOf(EditorStep.SCAN)
    private var tagSecurityInfo by mutableStateOf<Ntag215Manager.TagSecurityInfo?>(null)
    private var statusMessage by mutableStateOf("Scan an NFC tag to load editable records.")
    private var showNfcDisabledDialog by mutableStateOf(false)
    private var isNfcStateReceiverRegistered = false
    private var isActivityResumed = false
    private val nfcStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == NfcAdapter.ACTION_ADAPTER_STATE_CHANGED) {
                handleNfcAvailabilityChange()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            statusMessage = "NFC is not available on this device."
        } else {
            handleNfcAvailabilityChange()
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
                        currentStep = currentStep,
                        showNfcDisabledDialog = showNfcDisabledDialog,
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
                        onStepChange = ::updateCurrentStep,
                        onOpenNfcSettings = ::openNfcSettings,
                        canAddRecord = newRecordValue.isNotBlank(),
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerNfcStateReceiver()
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        handleNfcAvailabilityChange()
    }

    override fun onPause() {
        isActivityResumed = false
        nfcAdapter?.disableReaderMode(this)
        super.onPause()
    }

    override fun onStop() {
        unregisterNfcStateReceiver()
        super.onStop()
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
        } else if (currentStep == EditorStep.SCAN) {
            loadTag(tag, techSummary, security)
        } else {
            runOnUiThread {
                statusMessage = "Open the Scan step to read a tag."
            }
        }
    }

    private fun armAction(action: PendingAction) {
        if (showNfcDisabledDialog) {
            statusMessage = NFC_DISABLED_MESSAGE
            return
        }
        if (action == PendingAction.WRITE && editableRecords.isEmpty()) {
            statusMessage = "No editable records to write."
            return
        }
        pendingAction = action
        currentTag = null
        syncReaderMode()
        statusMessage = when (action) {
            PendingAction.NONE -> "Scan an NFC tag to load editable records."
            PendingAction.WRITE -> "Write armed. Tap tag to write."
            PendingAction.PROTECT -> "Protection armed. Tap an NTAG215 to set the password."
            PendingAction.UNPROTECT -> "Remove-password armed. Tap an NTAG215 and enter its password."
        }
    }

    private fun handleNfcAvailabilityChange() {
        val adapter = nfcAdapter ?: return
        if (adapter.isEnabled) {
            val wasBlocked = showNfcDisabledDialog
            showNfcDisabledDialog = false
            if (wasBlocked) {
                statusMessage = "NFC enabled. Scan an NFC tag to continue."
            }
            syncReaderMode()
            return
        }

        showNfcDisabledDialog = true
        pendingAction = PendingAction.NONE
        currentTag = null
        adapter.disableReaderMode(this)
        statusMessage = NFC_DISABLED_MESSAGE
    }

    private fun syncReaderMode() {
        val adapter = nfcAdapter ?: return
        if (!isActivityResumed || !adapter.isEnabled) {
            adapter.disableReaderMode(this)
            return
        }
        if (pendingAction == PendingAction.NONE && currentStep != EditorStep.SCAN) {
            adapter.disableReaderMode(this)
            return
        }
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

    private fun updateCurrentStep(step: EditorStep) {
        if (currentStep == step) return
        currentStep = step
        syncReaderMode()
    }

    private fun registerNfcStateReceiver() {
        if (nfcAdapter == null || isNfcStateReceiverRegistered) {
            return
        }
        registerReceiver(
            nfcStateReceiver,
            IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),
            Context.RECEIVER_NOT_EXPORTED
        )
        isNfcStateReceiverRegistered = true
    }

    private fun unregisterNfcStateReceiver() {
        if (!isNfcStateReceiverRegistered) {
            return
        }
        unregisterReceiver(nfcStateReceiver)
        isNfcStateReceiverRegistered = false
    }

    private fun openNfcSettings() {
        val settingsIntent = listOf(
            Intent(Settings.Panel.ACTION_NFC),
            Intent(Settings.ACTION_NFC_SETTINGS),
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        ).firstOrNull { intent -> intent.resolveActivity(packageManager) != null } ?: return
        startActivity(settingsIntent)
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
                syncReaderMode()
                statusMessage = exception.message ?: "Tag operation failed."
            }
        }
    }

    private fun performWrite(tag: Tag, techSummary: String, security: Ntag215Manager.TagSecurityInfo?) {
        val writePreparation = buildMessageForWrite(originalMessage, editableRecords)
        if (writePreparation !is WriteMessagePreparation.Ready) {
            runOnUiThread {
                pendingAction = PendingAction.NONE
                syncReaderMode()
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
                    syncReaderMode()
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
                syncReaderMode()
                statusMessage = "Protected NTAG215 written successfully."
            }
            return
        }

        val ndef = Ndef.get(tag)
        if (ndef == null) {
            runOnUiThread {
                pendingAction = PendingAction.NONE
                syncReaderMode()
                statusMessage = "NDEF unavailable. Detected technologies: $techSummary"
            }
            return
        }

        try {
            ndef.connect()
            if (!ndef.isWritable) {
                runOnUiThread {
                    pendingAction = PendingAction.NONE
                    syncReaderMode()
                    statusMessage = "Tag is read-only."
                }
                return
            }
            if (writeMessage.toByteArray().size > ndef.maxSize) {
                runOnUiThread {
                    pendingAction = PendingAction.NONE
                    syncReaderMode()
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
                syncReaderMode()
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
            syncReaderMode()
            statusMessage = "NTAG215 password set. Reads stay open, writes now require the password. Tech: $techSummary"
        }
    }

    private fun performUnprotect(tag: Tag, techSummary: String) {
        val updatedSecurity = Ntag215Manager.unprotect(tag, passwordInput)
        runOnUiThread {
            currentTag = tag
            tagSecurityInfo = updatedSecurity
            pendingAction = PendingAction.NONE
            syncReaderMode()
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
private fun NfcEditorScreen(
    statusMessage: String,
    records: List<NdefTextCodec.EditableRecord>,
    newRecordType: NdefTextCodec.EditableRecordType,
    newRecordValue: String,
    passwordInput: String,
    securityInfo: Ntag215Manager.TagSecurityInfo?,
    pendingAction: PendingAction,
    currentStep: EditorStep,
    showNfcDisabledDialog: Boolean,
    onRecordChange: (Int, String) -> Unit,
    onRemoveRecord: (Int) -> Unit,
    onNewRecordTypeChange: (NdefTextCodec.EditableRecordType) -> Unit,
    onNewRecordChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onAddRecord: () -> Unit,
    onWrite: () -> Unit,
    onProtect: () -> Unit,
    onUnprotect: () -> Unit,
    onStepChange: (EditorStep) -> Unit,
    onOpenNfcSettings: () -> Unit,
    canAddRecord: Boolean,
    modifier: Modifier = Modifier
) {
    if (showNfcDisabledDialog) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Enable NFC") },
            text = { Text("NFC is required to use this app. Turn it back on to continue.") },
            confirmButton = {
                TextButton(onClick = onOpenNfcSettings) {
                    Text("Open NFC settings")
                }
            },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        )
    }

    val steps = remember { EditorStep.entries }
    val pagerState = rememberPagerState(pageCount = { steps.size })
    val coroutineScope = rememberCoroutineScope()

    fun animateToStep(step: EditorStep) {
        onStepChange(step)
        coroutineScope.launch { pagerState.animateScrollToPage(step.ordinal) }
    }

    LaunchedEffect(currentStep) {
        val targetPage = currentStep.ordinal
        if (pagerState.currentPage != targetPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        val visibleStep = steps[pagerState.currentPage]
        if (visibleStep != currentStep) {
            onStepChange(visibleStep)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.18f)
                    )
                )
            )
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = true
        ) { page ->
            when (steps[page]) {
                EditorStep.SCAN -> ScanPage(
                    currentStep = page,
                    statusMessage = statusMessage,
                    showNfcDisabledDialog = showNfcDisabledDialog,
                    records = records,
                    securityInfo = securityInfo,
                    onOpenNfcSettings = onOpenNfcSettings,
                    onStepSelected = { target ->
                        val step = steps[target]
                        if (step != currentStep) {
                            animateToStep(step)
                        }
                    },
                    onNext = { animateToStep(EditorStep.EDIT) }
                )
                EditorStep.EDIT -> EditPage(
                    currentStep = page,
                    statusMessage = statusMessage,
                    showNfcDisabledDialog = showNfcDisabledDialog,
                    records = records,
                    newRecordType = newRecordType,
                    newRecordValue = newRecordValue,
                    securityInfo = securityInfo,
                    onRecordChange = onRecordChange,
                    onRemoveRecord = onRemoveRecord,
                    onNewRecordTypeChange = onNewRecordTypeChange,
                    onNewRecordChange = onNewRecordChange,
                    onAddRecord = onAddRecord,
                    canAddRecord = canAddRecord,
                    onStepSelected = { target ->
                        val step = steps[target]
                        if (step != currentStep) {
                            animateToStep(step)
                        }
                    },
                    onBack = { animateToStep(EditorStep.SCAN) },
                    onNext = { animateToStep(EditorStep.WRITE) }
                )
                EditorStep.WRITE -> WritePage(
                    currentStep = page,
                    statusMessage = statusMessage,
                    showNfcDisabledDialog = showNfcDisabledDialog,
                    records = records,
                    passwordInput = passwordInput,
                    securityInfo = securityInfo,
                    pendingAction = pendingAction,
                    onPasswordChange = onPasswordChange,
                    onWrite = onWrite,
                    onProtect = onProtect,
                    onUnprotect = onUnprotect,
                    onStepSelected = { target ->
                        val step = steps[target]
                        if (step != currentStep) {
                            animateToStep(step)
                        }
                    },
                    onBack = { animateToStep(EditorStep.EDIT) }
                )
            }
        }
    }
}

private fun Ntag215Manager.TagSecurityInfo?.describe(): String {
    if (this == null) return "No NTAG215 security info for the current tag yet."
    if (!isNtag215) return "Current tag is not an NTAG215."
    return if (isWriteProtected) {
        "Password required for writes from page $auth0Page."
    } else {
        "No password configured yet."
    }
}

@Composable
private fun StateBanner(
    pendingAction: PendingAction,
    showNfcDisabledDialog: Boolean,
    statusMessage: String
) {
    val tone = when {
        showNfcDisabledDialog -> MaterialTheme.colorScheme.tertiaryContainer
        pendingAction == PendingAction.NONE -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val title = when {
        showNfcDisabledDialog -> "NFC is off"
        pendingAction == PendingAction.WRITE -> "Ready to write"
        pendingAction == PendingAction.PROTECT -> "Ready to protect"
        pendingAction == PendingAction.UNPROTECT -> "Ready to remove password"
        else -> "Waiting for a tag"
    }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = tone
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            Text(text = statusMessage, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun StepperHeader(
    currentStep: Int,
    onStepSelected: (Int) -> Unit
) {
    val labels = listOf("Scan", "Edit", "Write")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == currentStep
            Surface(
                onClick = { onStepSelected(index) },
                shape = RoundedCornerShape(20.dp),
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                tonalElevation = if (selected) 3.dp else 0.dp,
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "${index + 1}",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = label, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun ScanPage(
    currentStep: Int,
    statusMessage: String,
    showNfcDisabledDialog: Boolean,
    records: List<NdefTextCodec.EditableRecord>,
    securityInfo: Ntag215Manager.TagSecurityInfo?,
    onOpenNfcSettings: () -> Unit,
    onStepSelected: (Int) -> Unit,
    onNext: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            CompactTopBlock(
                currentStep = currentStep,
                pendingAction = PendingAction.NONE,
                showNfcDisabledDialog = showNfcDisabledDialog,
                statusMessage = statusMessage,
                title = "Scan a tag",
                description = "Bring an NFC tag close to your device to inspect what is already stored on it.",
                onStepSelected = onStepSelected
            )
        }
        item {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (showNfcDisabledDialog) "NFC disabled" else "Scanner listening",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        text = if (showNfcDisabledDialog) {
                            "Turn NFC back on, then return here."
                        } else {
                            "Hold the phone near a tag. The app will load records automatically."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FilledTonalButton(onClick = onOpenNfcSettings) {
                        Text("Open NFC settings")
                    }
                }
            }
        }
        item { SummaryTile(label = "Status", value = statusMessage) }
        item { SummaryTile(label = "Editable records", value = if (records.isEmpty()) "None loaded" else "${records.size} loaded") }
        item { SummaryTile(label = "Security", value = securityInfo.describe()) }
        item { InlinePagerNavBar(currentStep = currentStep, onNext = onNext) }
    }
}

@Composable
private fun EditPage(
    currentStep: Int,
    statusMessage: String,
    showNfcDisabledDialog: Boolean,
    records: List<NdefTextCodec.EditableRecord>,
    newRecordType: NdefTextCodec.EditableRecordType,
    newRecordValue: String,
    securityInfo: Ntag215Manager.TagSecurityInfo?,
    onRecordChange: (Int, String) -> Unit,
    onRemoveRecord: (Int) -> Unit,
    onNewRecordTypeChange: (NdefTextCodec.EditableRecordType) -> Unit,
    onNewRecordChange: (String) -> Unit,
    onAddRecord: () -> Unit,
    canAddRecord: Boolean,
    onStepSelected: (Int) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            CompactTopBlock(
                currentStep = currentStep,
                pendingAction = PendingAction.NONE,
                showNfcDisabledDialog = showNfcDisabledDialog,
                statusMessage = statusMessage,
                title = "Edit content",
                description = "Review the records already on the tag, adjust them, or add new ones before writing.",
                onStepSelected = onStepSelected
            )
        }
        item { SummaryTile(label = "Security", value = securityInfo.describe()) }
        if (records.isEmpty()) {
            item {
                EmptyStateCard(
                    title = "No editable records yet",
                    message = "Scan a tag to load existing content, or create a new record below."
                )
            }
        }
        itemsIndexed(records) { index, record ->
            RecordEditorCard(
                index = index,
                record = record,
                onRecordChange = onRecordChange,
                onRemoveRecord = onRemoveRecord
            )
        }
        item {
            AddRecordCard(
                newRecordType = newRecordType,
                newRecordValue = newRecordValue,
                onNewRecordTypeChange = onNewRecordTypeChange,
                onNewRecordChange = onNewRecordChange,
                onAddRecord = onAddRecord,
                canAddRecord = canAddRecord
            )
        }
        item { InlinePagerNavBar(currentStep = currentStep, onBack = onBack, onNext = onNext) }
    }
}

@Composable
private fun WritePage(
    currentStep: Int,
    statusMessage: String,
    showNfcDisabledDialog: Boolean,
    records: List<NdefTextCodec.EditableRecord>,
    passwordInput: String,
    securityInfo: Ntag215Manager.TagSecurityInfo?,
    pendingAction: PendingAction,
    onPasswordChange: (String) -> Unit,
    onWrite: () -> Unit,
    onProtect: () -> Unit,
    onUnprotect: () -> Unit,
    onStepSelected: (Int) -> Unit,
    onBack: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            CompactTopBlock(
                currentStep = currentStep,
                pendingAction = pendingAction,
                showNfcDisabledDialog = showNfcDisabledDialog,
                statusMessage = statusMessage,
                title = "Write and secure",
                description = "Arm the next action, then tap the destination tag to write or manage protection.",
                onStepSelected = onStepSelected
            )
        }
        item {
            SummaryTile(label = "Records ready", value = if (records.isEmpty()) "Nothing to write yet" else "${records.size} record(s) prepared")
        }
        item {
            Button(
                onClick = onWrite,
                enabled = records.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (pendingAction == PendingAction.WRITE) "Tap tag to write" else "Write records")
            }
        }
        item {
            SecurityToolsCard(
                securityInfo = securityInfo,
                passwordInput = passwordInput,
                pendingAction = pendingAction,
                onPasswordChange = onPasswordChange,
                onProtect = onProtect,
                onUnprotect = onUnprotect
            )
        }
        item { InlinePagerNavBar(currentStep = currentStep, onBack = onBack) }
    }
}

@Composable
private fun CompactTopBlock(
    currentStep: Int,
    pendingAction: PendingAction,
    showNfcDisabledDialog: Boolean,
    statusMessage: String,
    title: String,
    description: String,
    onStepSelected: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StepperHeader(currentStep = currentStep, onStepSelected = onStepSelected)
        StateBanner(
            pendingAction = pendingAction,
            showNfcDisabledDialog = showNfcDisabledDialog,
            statusMessage = statusMessage
        )
        PageHeader(title = title, description = description)
    }
}

@Composable
private fun InlinePagerNavBar(
    currentStep: Int,
    onBack: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = { onBack?.invoke() }, enabled = onBack != null) {
                Text("Back")
            }
            Text(
                text = "Step ${currentStep + 1} of 3",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = { onNext?.invoke() }, enabled = onNext != null) {
                Text(if (currentStep == 2 || onNext == null) "Done" else "Next")
            }
        }
    }
}

@Composable
private fun PageHeader(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = title, style = MaterialTheme.typography.headlineMedium)
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SummaryTile(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text = label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun EmptyStateCard(title: String, message: String) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            Text(text = message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RecordEditorCard(
    index: Int,
    record: NdefTextCodec.EditableRecord,
    onRecordChange: (Int, String) -> Unit,
    onRemoveRecord: (Int) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "${record.type.displayName} record ${index + 1}", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { onRemoveRecord(index) }) {
                    Text("Remove")
                }
            }
            OutlinedTextField(
                value = record.value,
                onValueChange = { onRecordChange(index, it) },
                label = { Text(record.type.newRecordLabel) },
                keyboardOptions = KeyboardOptions(keyboardType = record.type.keyboardType),
                modifier = Modifier.fillMaxWidth(),
                minLines = if (record.type == NdefTextCodec.EditableRecordType.TEXT) 3 else 1
            )
        }
    }
}

@Composable
private fun AddRecordCard(
    newRecordType: NdefTextCodec.EditableRecordType,
    newRecordValue: String,
    onNewRecordTypeChange: (NdefTextCodec.EditableRecordType) -> Unit,
    onNewRecordChange: (String) -> Unit,
    onAddRecord: () -> Unit,
    canAddRecord: Boolean
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "Add a new record", style = MaterialTheme.typography.titleLarge)
            RecordTypePicker(
                selectedType = newRecordType,
                onTypeSelected = onNewRecordTypeChange
            )
            OutlinedTextField(
                value = newRecordValue,
                onValueChange = onNewRecordChange,
                label = { Text(newRecordType.newRecordLabel) },
                keyboardOptions = KeyboardOptions(keyboardType = newRecordType.keyboardType),
                modifier = Modifier.fillMaxWidth(),
                minLines = if (newRecordType == NdefTextCodec.EditableRecordType.TEXT) 3 else 1
            )
            Button(onClick = onAddRecord, enabled = canAddRecord, modifier = Modifier.fillMaxWidth()) {
                Text("Add ${newRecordType.displayName.lowercase()} record")
            }
        }
    }
}

@Composable
private fun SecurityToolsCard(
    securityInfo: Ntag215Manager.TagSecurityInfo?,
    passwordInput: String,
    pendingAction: PendingAction,
    onPasswordChange: (String) -> Unit,
    onProtect: () -> Unit,
    onUnprotect: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "NTAG215 security tools", style = MaterialTheme.typography.titleLarge)
            if (securityInfo?.isNtag215 != true) {
                Text(
                    text = "Scan an NTAG215 tag to reveal password and protection tools.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = securityInfo.describe(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = onPasswordChange,
                    label = { Text("NTAG215 password (4 chars or 8 hex)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(onClick = onProtect, modifier = Modifier.weight(1f)) {
                        Text(if (pendingAction == PendingAction.PROTECT) "Tap to protect" else "Protect tag")
                    }
                    OutlinedButton(onClick = onUnprotect, modifier = Modifier.weight(1f)) {
                        Text(if (pendingAction == PendingAction.UNPROTECT) "Tap to unlock" else "Remove password")
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordTypePicker(
    selectedType: NdefTextCodec.EditableRecordType,
    onTypeSelected: (NdefTextCodec.EditableRecordType) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NdefTextCodec.EditableRecordType.values().toList().chunked(2).forEach { rowTypes ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                rowTypes.forEach { type ->
                    FilterChip(
                        selected = type == selectedType,
                        onClick = { onTypeSelected(type) },
                        label = { Text(type.displayName) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (rowTypes.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
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
            statusMessage = "Tag loaded. Swipe through scan, edit, and write when ready.",
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
            currentStep = EditorStep.SCAN,
            showNfcDisabledDialog = false,
            onRecordChange = { _, _ -> },
            onRemoveRecord = {},
            onNewRecordTypeChange = {},
            onNewRecordChange = {},
            onPasswordChange = {},
            onAddRecord = {},
            onWrite = {},
            onProtect = {},
            onUnprotect = {},
            onStepChange = {},
            onOpenNfcSettings = {},
            canAddRecord = false
        )
    }
}
