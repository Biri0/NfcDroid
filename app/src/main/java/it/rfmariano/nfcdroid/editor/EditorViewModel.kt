package it.rfmariano.nfcdroid.editor

import android.nfc.NdefMessage
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.rfmariano.nfcdroid.NdefTextCodec
import it.rfmariano.nfcdroid.Ntag215Manager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class EditorViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    fun onRecordChange(index: Int, value: String) {
        _uiState.update { state ->
            state.copy(
                editableRecords = state.editableRecords.toMutableList().also {
                    it[index] = it[index].copy(value = value)
                }
            )
        }
    }

    fun onRemoveRecord(index: Int) {
        _uiState.update { state ->
            state.copy(
                editableRecords = state.editableRecords.toMutableList().also { it.removeAt(index) }
            )
        }
    }

    fun onNewRecordTypeChange(type: NdefTextCodec.EditableRecordType) {
        _uiState.update { it.copy(newRecordType = type) }
    }

    fun onNewRecordValueChange(value: String) {
        _uiState.update { it.copy(newRecordValue = value) }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(passwordInput = value) }
    }

    fun onAddRecord() {
        _uiState.update { state ->
            if (state.newRecordValue.isBlank()) {
                state.copy(statusMessage = "${state.newRecordType.displayName} value cannot be blank.")
            } else {
                state.copy(
                    editableRecords = state.editableRecords +
                        NdefTextCodec.EditableRecord(
                            originalRecordIndex = null,
                            type = state.newRecordType,
                            value = state.newRecordValue
                        ),
                    newRecordValue = ""
                )
            }
        }
    }

    fun armAction(action: PendingAction, nfcDisabledMessage: String) {
        _uiState.update { state ->
            if (state.showNfcDisabledDialog) {
                return@update state.copy(statusMessage = nfcDisabledMessage)
            }
            if (action == PendingAction.WRITE && state.editableRecords.isEmpty()) {
                return@update state.copy(statusMessage = "No editable records to write.")
            }
            state.copy(
                pendingAction = action,
                statusMessage = when (action) {
                    PendingAction.NONE -> "Scan an NFC tag to load editable records."
                    PendingAction.WRITE -> "Write armed. Tap tag to write."
                    PendingAction.PROTECT -> "Protection armed. Tap an NTAG215 to set the password."
                    PendingAction.UNPROTECT -> "Remove-password armed. Tap an NTAG215 and enter its password."
                }
            )
        }
    }

    fun updateCurrentStep(step: EditorStep) {
        _uiState.update { state -> if (state.currentStep == step) state else state.copy(currentStep = step) }
    }

    fun setNfcAvailability(isEnabled: Boolean, nfcDisabledMessage: String) {
        _uiState.update { state ->
            if (isEnabled) {
                state.copy(
                    showNfcDisabledDialog = false,
                    statusMessage = if (state.showNfcDisabledDialog) {
                        "NFC enabled. Scan an NFC tag to continue."
                    } else {
                        state.statusMessage
                    }
                )
            } else {
                state.copy(
                    showNfcDisabledDialog = true,
                    pendingAction = PendingAction.NONE,
                    statusMessage = nfcDisabledMessage
                )
            }
        }
    }

    fun setStatusMessage(message: String) {
        _uiState.update { it.copy(statusMessage = message) }
    }

    fun clearPendingAction() {
        _uiState.update { it.copy(pendingAction = PendingAction.NONE) }
    }

    fun applyTagLoadResult(result: TagLoadResult) {
        _uiState.update {
            it.copy(
                originalMessage = result.originalMessage,
                editableRecords = result.editableRecords,
                tagSecurityInfo = result.tagSecurityInfo,
                statusMessage = result.statusMessage
            )
        }
    }

    fun applyWriteSuccess(message: NdefMessage, securityInfo: Ntag215Manager.TagSecurityInfo?, statusMessage: String) {
        _uiState.update {
            it.copy(
                originalMessage = message,
                editableRecords = NdefTextCodec.editableRecordsFromMessage(message),
                tagSecurityInfo = securityInfo,
                pendingAction = PendingAction.NONE,
                statusMessage = statusMessage
            )
        }
    }

    fun applyProtectionSuccess(securityInfo: Ntag215Manager.TagSecurityInfo, statusMessage: String) {
        _uiState.update {
            it.copy(
                tagSecurityInfo = securityInfo,
                pendingAction = PendingAction.NONE,
                statusMessage = statusMessage
            )
        }
    }

    fun onTagOperationError(message: String) {
        _uiState.update {
            it.copy(
                pendingAction = PendingAction.NONE,
                statusMessage = message
            )
        }
    }

    fun buildMessageForWrite(): WriteMessagePreparation {
        val state = _uiState.value
        val trimmedRecords = NdefTextCodec.trimForSave(state.editableRecords)
        if (trimmedRecords.isEmpty()) {
            return WriteMessagePreparation.Empty
        }

        return try {
            val message = if (state.originalMessage != null) {
                NdefTextCodec.patchMessage(state.originalMessage, trimmedRecords)
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

    fun runAsync(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
