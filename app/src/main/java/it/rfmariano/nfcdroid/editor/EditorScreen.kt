package it.rfmariano.nfcdroid.editor

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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import it.rfmariano.nfcdroid.NdefTextCodec
import it.rfmariano.nfcdroid.Ntag215Manager
import it.rfmariano.nfcdroid.ui.theme.NfcDroidTheme
import kotlinx.coroutines.launch

@Composable
fun EditorScreen(
    uiState: EditorUiState,
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
    modifier: Modifier = Modifier
) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        EditorScreenContent(
            uiState = uiState,
            onRecordChange = onRecordChange,
            onRemoveRecord = onRemoveRecord,
            onNewRecordTypeChange = onNewRecordTypeChange,
            onNewRecordChange = onNewRecordChange,
            onPasswordChange = onPasswordChange,
            onAddRecord = onAddRecord,
            onWrite = onWrite,
            onProtect = onProtect,
            onUnprotect = onUnprotect,
            onStepChange = onStepChange,
            onOpenNfcSettings = onOpenNfcSettings,
            modifier = Modifier.padding(innerPadding)
        )
    }
}

@Composable
private fun EditorScreenContent(
    uiState: EditorUiState,
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
    modifier: Modifier = Modifier
) {
    if (uiState.showNfcDisabledDialog) {
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

    LaunchedEffect(uiState.currentStep) {
        val targetPage = uiState.currentStep.ordinal
        if (pagerState.currentPage != targetPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        val visibleStep = steps[pagerState.currentPage]
        if (visibleStep != uiState.currentStep) {
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
                    statusMessage = uiState.statusMessage,
                    showNfcDisabledDialog = uiState.showNfcDisabledDialog,
                    records = uiState.editableRecords,
                    securityInfo = uiState.tagSecurityInfo,
                    onOpenNfcSettings = onOpenNfcSettings,
                    onStepSelected = { target ->
                        val step = steps[target]
                        if (step != uiState.currentStep) {
                            animateToStep(step)
                        }
                    },
                    onNext = { animateToStep(EditorStep.EDIT) }
                )

                EditorStep.EDIT -> EditPage(
                    currentStep = page,
                    statusMessage = uiState.statusMessage,
                    showNfcDisabledDialog = uiState.showNfcDisabledDialog,
                    records = uiState.editableRecords,
                    newRecordType = uiState.newRecordType,
                    newRecordValue = uiState.newRecordValue,
                    securityInfo = uiState.tagSecurityInfo,
                    onRecordChange = onRecordChange,
                    onRemoveRecord = onRemoveRecord,
                    onNewRecordTypeChange = onNewRecordTypeChange,
                    onNewRecordChange = onNewRecordChange,
                    onAddRecord = onAddRecord,
                    canAddRecord = uiState.canAddRecord,
                    onStepSelected = { target ->
                        val step = steps[target]
                        if (step != uiState.currentStep) {
                            animateToStep(step)
                        }
                    },
                    onBack = { animateToStep(EditorStep.SCAN) },
                    onNext = { animateToStep(EditorStep.WRITE) }
                )

                EditorStep.WRITE -> WritePage(
                    currentStep = page,
                    statusMessage = uiState.statusMessage,
                    showNfcDisabledDialog = uiState.showNfcDisabledDialog,
                    records = uiState.editableRecords,
                    passwordInput = uiState.passwordInput,
                    securityInfo = uiState.tagSecurityInfo,
                    pendingAction = uiState.pendingAction,
                    onPasswordChange = onPasswordChange,
                    onWrite = onWrite,
                    onProtect = onProtect,
                    onUnprotect = onUnprotect,
                    onStepSelected = { target ->
                        val step = steps[target]
                        if (step != uiState.currentStep) {
                            animateToStep(step)
                        }
                    },
                    onBack = { animateToStep(EditorStep.EDIT) }
                )
            }
        }
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f)
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        }
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(22.dp),
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
        item {
            SummaryTile(
                label = "Editable records",
                value = if (records.isEmpty()) "None loaded" else "${records.size} loaded"
            )
        }
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
            SummaryTile(
                label = "Records ready",
                value = if (records.isEmpty()) "Nothing to write yet" else "${records.size} record(s) prepared"
            )
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${record.type.displayName} record ${index + 1}",
                    style = MaterialTheme.typography.titleMedium
                )
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
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
                    keyboardOptions = KeyboardOptions(keyboardType = recordPasswordKeyboardType()),
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
        NdefTextCodec.EditableRecordType.entries.toList().chunked(2).forEach { rowTypes ->
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

private fun recordPasswordKeyboardType() = androidx.compose.ui.text.input.KeyboardType.Password

@Preview(showBackground = true)
@Composable
private fun EditorScreenPreview() {
    NfcDroidTheme {
        EditorScreenContent(
            uiState = EditorUiState(
                editableRecords = listOf(
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
                passwordInput = "A1B2",
                tagSecurityInfo = Ntag215Manager.TagSecurityInfo(
                    isNtag215 = true,
                    isWriteProtected = true,
                    auth0Page = 4,
                    protectRead = false,
                    pack = byteArrayOf(0x00, 0x00)
                ),
                statusMessage = "Tag loaded. Swipe through scan, edit, and write when ready."
            ),
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
            onOpenNfcSettings = {}
        )
    }
}
