package it.rfmariano.nfcdroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.FormatException
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import it.rfmariano.nfcdroid.editor.EditorScreen
import it.rfmariano.nfcdroid.editor.EditorStep
import it.rfmariano.nfcdroid.editor.EditorViewModel
import it.rfmariano.nfcdroid.editor.PendingAction
import it.rfmariano.nfcdroid.editor.TagLoadResult
import it.rfmariano.nfcdroid.editor.WriteMessagePreparation
import it.rfmariano.nfcdroid.ui.theme.NfcDroidTheme
import java.io.IOException

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {
    companion object {
        private const val NFC_DISABLED_MESSAGE = "NFC is disabled. Enable it to continue using the app."
    }

    private val viewModel: EditorViewModel by viewModels()

    private var nfcAdapter: NfcAdapter? = null
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
            viewModel.setStatusMessage("NFC is not available on this device.")
        } else {
            handleNfcAvailabilityChange()
        }

        setContent {
            val uiState by viewModel.uiState.collectAsState()
            NfcDroidTheme {
                EditorScreen(
                    uiState = uiState,
                    onRecordChange = viewModel::onRecordChange,
                    onRemoveRecord = viewModel::onRemoveRecord,
                    onNewRecordTypeChange = viewModel::onNewRecordTypeChange,
                    onNewRecordChange = viewModel::onNewRecordValueChange,
                    onPasswordChange = viewModel::onPasswordChange,
                    onAddRecord = viewModel::onAddRecord,
                    onWrite = { armAction(PendingAction.WRITE) },
                    onProtect = { armAction(PendingAction.PROTECT) },
                    onUnprotect = { armAction(PendingAction.UNPROTECT) },
                    onStepChange = ::updateCurrentStep,
                    onOpenNfcSettings = ::openNfcSettings
                )
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

        val uiState = viewModel.uiState.value
        if (uiState.pendingAction != PendingAction.NONE) {
            performPendingAction(tag, techSummary, security)
        } else if (uiState.currentStep == EditorStep.SCAN) {
            loadTag(tag, techSummary, security)
        } else {
            viewModel.setStatusMessage("Open the Scan step to read a tag.")
        }
    }

    private fun armAction(action: PendingAction) {
        viewModel.armAction(action, NFC_DISABLED_MESSAGE)
        syncReaderMode()
    }

    private fun updateCurrentStep(step: EditorStep) {
        viewModel.updateCurrentStep(step)
        syncReaderMode()
    }

    private fun handleNfcAvailabilityChange() {
        val adapter = nfcAdapter ?: return
        val isEnabled = adapter.isEnabled
        viewModel.setNfcAvailability(isEnabled, NFC_DISABLED_MESSAGE)

        if (isEnabled) {
            syncReaderMode()
        } else {
            adapter.disableReaderMode(this)
        }
    }

    private fun syncReaderMode() {
        val adapter = nfcAdapter ?: return
        val uiState = viewModel.uiState.value
        if (!isActivityResumed || !adapter.isEnabled) {
            adapter.disableReaderMode(this)
            return
        }
        if (uiState.pendingAction == PendingAction.NONE && uiState.currentStep != EditorStep.SCAN) {
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
            when (viewModel.uiState.value.pendingAction) {
                PendingAction.WRITE -> performWrite(tag, techSummary, security)
                PendingAction.PROTECT -> performProtect(tag, techSummary)
                PendingAction.UNPROTECT -> performUnprotect(tag, techSummary)
                PendingAction.NONE -> loadTag(tag, techSummary, security)
            }
        } catch (exception: Exception) {
            viewModel.onTagOperationError(exception.message ?: "Tag operation failed.")
            syncReaderMode()
        }
    }

    private fun performWrite(tag: Tag, techSummary: String, security: Ntag215Manager.TagSecurityInfo?) {
        when (val writePreparation = viewModel.buildMessageForWrite()) {
            WriteMessagePreparation.Empty -> {
                viewModel.onTagOperationError("Cannot write empty NDEF message.")
                syncReaderMode()
                return
            }

            is WriteMessagePreparation.Invalid -> {
                viewModel.onTagOperationError(writePreparation.reason)
                syncReaderMode()
                return
            }

            is WriteMessagePreparation.Ready -> {
                val writeMessage = writePreparation.message
                val passwordInput = viewModel.uiState.value.passwordInput

                if (security?.isWriteProtected == true) {
                    if (passwordInput.isBlank()) {
                        viewModel.onTagOperationError(
                            "This tag is password protected. Enter the password, then tap again."
                        )
                        syncReaderMode()
                        return
                    }

                    val updatedSecurity = Ntag215Manager.writeProtectedNdef(tag, writeMessage, passwordInput)
                    viewModel.applyWriteSuccess(
                        message = writeMessage,
                        securityInfo = updatedSecurity,
                        statusMessage = "Protected NTAG215 written successfully."
                    )
                    syncReaderMode()
                    return
                }

                val ndef = Ndef.get(tag)
                if (ndef == null) {
                    viewModel.onTagOperationError("NDEF unavailable. Detected technologies: $techSummary")
                    syncReaderMode()
                    return
                }

                try {
                    ndef.connect()
                    if (!ndef.isWritable) {
                        viewModel.onTagOperationError("Tag is read-only.")
                        syncReaderMode()
                        return
                    }
                    if (writeMessage.toByteArray().size > ndef.maxSize) {
                        viewModel.onTagOperationError("NDEF message too large for this tag.")
                        syncReaderMode()
                        return
                    }
                    ndef.writeNdefMessage(writeMessage)
                    viewModel.applyWriteSuccess(
                        message = writeMessage,
                        securityInfo = security,
                        statusMessage = "Tag written successfully."
                    )
                    syncReaderMode()
                } finally {
                    try {
                        ndef.close()
                    } catch (_: IOException) {
                    }
                }
            }
        }
    }

    private fun performProtect(tag: Tag, techSummary: String) {
        val updatedSecurity = Ntag215Manager.protect(tag, viewModel.uiState.value.passwordInput, protectRead = false)
        viewModel.applyProtectionSuccess(
            securityInfo = updatedSecurity,
            statusMessage = "NTAG215 password set. Reads stay open, writes now require the password. Tech: $techSummary"
        )
        syncReaderMode()
    }

    private fun performUnprotect(tag: Tag, techSummary: String) {
        val updatedSecurity = Ntag215Manager.unprotect(tag, viewModel.uiState.value.passwordInput)
        viewModel.applyProtectionSuccess(
            securityInfo = updatedSecurity,
            statusMessage = "NTAG215 password removed. Tag is writable again. Tech: $techSummary"
        )
        syncReaderMode()
    }

    private fun loadTag(tag: Tag, techSummary: String, security: Ntag215Manager.TagSecurityInfo?) {
        val ndef = Ndef.get(tag)
        if (ndef == null) {
            viewModel.applyTagLoadResult(
                TagLoadResult(
                    tag = tag,
                    originalMessage = null,
                    editableRecords = emptyList(),
                    tagSecurityInfo = security,
                    statusMessage = buildString {
                        append("NDEF unavailable. Detected technologies: ")
                        append(techSummary)
                        if (security?.isNtag215 == true) {
                            append(". NTAG215 ")
                            append(if (security.isWriteProtected) "is write-protected." else "has no password.")
                        }
                    }
                )
            )
            return
        }

        try {
            ndef.connect()
            val message = ndef.cachedNdefMessage ?: ndef.ndefMessage
            val parsedRecords = NdefTextCodec.editableRecordsFromMessage(message)
            viewModel.applyTagLoadResult(
                TagLoadResult(
                    tag = tag,
                    originalMessage = message,
                    editableRecords = parsedRecords,
                    tagSecurityInfo = security,
                    statusMessage = buildStatusMessage(parsedRecords, techSummary, security)
                )
            )
        } catch (ioException: IOException) {
            viewModel.setStatusMessage("Failed to read tag: ${ioException.message ?: "I/O error"}")
        } catch (formatException: FormatException) {
            viewModel.setStatusMessage("Invalid NDEF data: ${formatException.message ?: "format error"}")
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
}
