package it.rfmariano.nfcdroid.editor

import android.nfc.NdefMessage
import android.nfc.Tag
import androidx.compose.ui.text.input.KeyboardType
import it.rfmariano.nfcdroid.NdefTextCodec
import it.rfmariano.nfcdroid.Ntag215Manager

enum class PendingAction {
    NONE,
    WRITE,
    PROTECT,
    UNPROTECT
}

enum class EditorStep {
    SCAN,
    EDIT,
    WRITE
}

data class EditorUiState(
    val originalMessage: NdefMessage? = null,
    val editableRecords: List<NdefTextCodec.EditableRecord> = emptyList(),
    val newRecordType: NdefTextCodec.EditableRecordType = NdefTextCodec.EditableRecordType.TEXT,
    val newRecordValue: String = "",
    val passwordInput: String = "",
    val pendingAction: PendingAction = PendingAction.NONE,
    val currentStep: EditorStep = EditorStep.SCAN,
    val tagSecurityInfo: Ntag215Manager.TagSecurityInfo? = null,
    val statusMessage: String = "Scan an NFC tag to load editable records.",
    val showNfcDisabledDialog: Boolean = false,
    val canAddRecord: Boolean = newRecordValue.isNotBlank()
)

sealed interface WriteMessagePreparation {
    data class Ready(val message: NdefMessage) : WriteMessagePreparation
    data object Empty : WriteMessagePreparation
    data class Invalid(val reason: String) : WriteMessagePreparation
}

data class TagLoadResult(
    val tag: Tag,
    val originalMessage: NdefMessage?,
    val editableRecords: List<NdefTextCodec.EditableRecord>,
    val tagSecurityInfo: Ntag215Manager.TagSecurityInfo?,
    val statusMessage: String
)

val NdefTextCodec.EditableRecordType.keyboardType: KeyboardType
    get() = when (this) {
        NdefTextCodec.EditableRecordType.TEXT -> KeyboardType.Text
        NdefTextCodec.EditableRecordType.LINK -> KeyboardType.Uri
        NdefTextCodec.EditableRecordType.PHONE -> KeyboardType.Phone
        NdefTextCodec.EditableRecordType.EMAIL -> KeyboardType.Email
    }

fun Ntag215Manager.TagSecurityInfo?.describe(): String {
    if (this == null) return "No NTAG215 security info for the current tag yet."
    if (!isNtag215) return "Current tag is not an NTAG215."
    return if (isWriteProtected) {
        "Password required for writes from page $auth0Page."
    } else {
        "No password configured yet."
    }
}
