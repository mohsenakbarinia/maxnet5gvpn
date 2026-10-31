package com.example.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.LatencyMeasureType
import com.example.domain.model.SecurityType
import com.example.domain.model.ServerProfile
import com.example.domain.model.TransportType
import com.example.domain.model.VpnProtocol
import com.example.parser.ConfigParser
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CoralError
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.viewmodel.ProfileSortMode
import com.example.util.LocalizationHelper
import com.example.util.QrCodeHelper

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfilesScreen(
    profiles: List<ServerProfile>,
    activeProfileId: Long?,
    groups: List<String>,
    searchQuery: String,
    selectedProtocol: VpnProtocol?,
    selectedGroup: String?,
    sortMode: ProfileSortMode,
    selectedBulkIds: Set<Long>,
    isTestingLatency: Boolean,
    usePersianDigits: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onProtocolFilterChange: (VpnProtocol?) -> Unit,
    onGroupFilterChange: (String?) -> Unit,
    onSortModeChange: (ProfileSortMode) -> Unit,
    onSelectProfile: (Long) -> Unit,
    onSaveProfile: (ServerProfile) -> Unit,
    onDuplicateProfile: (ServerProfile) -> Unit,
    onDeleteProfile: (ServerProfile) -> Unit,
    onToggleFavorite: (ServerProfile) -> Unit,
    onTogglePinned: (ServerProfile) -> Unit,
    onToggleBulkSelect: (Long) -> Unit,
    onDeleteBulkSelected: () -> Unit,
    onClearBulkSelection: () -> Unit,
    onImportBatchText: (String, String) -> Unit,
    onDecodeQrBitmap: (android.graphics.Bitmap) -> Unit,
    onTestSingleLatency: (ServerProfile) -> Unit,
    onTestAllLatency: () -> Unit,
    onCancelLatencyTest: () -> Unit,
    onShowCoreJson: (ServerProfile) -> Unit,
    onShowQrCode: (ServerProfile) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    var showImportDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<ServerProfile?>(null) }
    var isCreatingNew by remember { mutableStateOf(false) }

    // Zero-permission Android Photo Picker for QR image import from gallery
    val qrGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    if (bitmap != null) onDecodeQrBitmap(bitmap)
                }
            }.onFailure {
                onShowMessage("Could not read QR image")
            }
        }
    }

    // Camera QR capture preview launcher (TakePicturePreview)
    val cameraQrLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            onDecodeQrBitmap(bitmap)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            cameraQrLauncher.launch(null)
        } else {
            onShowMessage("Camera permission is required to scan QR codes")
        }
    }

    // Config file picker (.txt, .json, .conf)
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (!text.isNullOrBlank()) {
                    onImportBatchText(text, selectedGroup ?: "Default")
                }
            }
        }
    }

    if (isCreatingNew || editingProfile != null) {
        BackHandler {
            isCreatingNew = false
            editingProfile = null
        }
        ManualProfileEditorScreen(
            initialProfile = editingProfile,
            onSave = { updated ->
                onSaveProfile(updated)
                isCreatingNew = false
                editingProfile = null
            },
            onCancel = {
                isCreatingNew = false
                editingProfile = null
            }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        // Search Field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("profile_search_input"),
            placeholder = { Text(stringResource(R.string.search_profiles_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Quick Import & Creation Actions
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 2.dp)
        ) {
            item {
                Button(
                    onClick = { showImportDialog = true },
                    modifier = Modifier.testTag("open_import_dialog_button")
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.import_config))
                }
            }
            item {
                OutlinedButton(
                    onClick = { isCreatingNew = true },
                    modifier = Modifier.testTag("open_manual_editor_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.new_manual_profile))
                }
            }
            item {
                OutlinedButton(
                    onClick = {
                        val clip = clipboardManager.getText()?.text.orEmpty()
                        if (clip.isBlank()) {
                            onShowMessage("Clipboard is empty")
                        } else {
                            onImportBatchText(clip, selectedGroup ?: "Default")
                        }
                    },
                    modifier = Modifier.testTag("import_clipboard_button")
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.import_clipboard))
                }
            }
            item {
                OutlinedButton(
                    onClick = {
                        cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                    }
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.import_qr_camera))
                }
            }
            item {
                OutlinedButton(
                    onClick = {
                        qrGalleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                ) {
                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.import_qr_gallery))
                }
            }
            item {
                OutlinedButton(
                    onClick = { filePickerLauncher.launch("*/*") }
                ) {
                    Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.import_file))
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Protocol & Sort Filter Chips
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = selectedProtocol == null,
                    onClick = { onProtocolFilterChange(null) },
                    label = { Text(stringResource(R.string.filter_all)) }
                )
            }
            items(VpnProtocol.entries) { proto ->
                FilterChip(
                    selected = selectedProtocol == proto,
                    onClick = {
                        onProtocolFilterChange(if (selectedProtocol == proto) null else proto)
                    },
                    label = { Text(proto.displayName) }
                )
            }
        }

        // Group Filter & Latency Test Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(groups) { grp ->
                    val isSelected = (selectedGroup == null && grp == "Default") || selectedGroup == grp
                    AssistChip(
                        onClick = {
                            onGroupFilterChange(if (grp == "Default" || selectedGroup == grp) null else grp)
                        },
                        label = {
                            Text(
                                text = grp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(
                    onClick = {
                        val nextOrd = (sortMode.ordinal + 1) % ProfileSortMode.entries.size
                        onSortModeChange(ProfileSortMode.entries[nextOrd])
                    },
                    label = { Text(sortMode.labelEn) }
                )

                AssistChip(
                    onClick = {
                        if (isTestingLatency) onCancelLatencyTest() else onTestAllLatency()
                    },
                    label = {
                        Text(
                            if (isTestingLatency) stringResource(R.string.cancel_testing)
                            else stringResource(R.string.test_all_profiles)
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    modifier = Modifier.testTag("test_all_profiles_button")
                )
            }
        }

        // Bulk selection bar if any items are selected
        if (selectedBulkIds.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${selectedBulkIds.size} selected",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Row {
                        TextButton(onClick = onClearBulkSelection) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(
                            onClick = onDeleteBulkSelected,
                            modifier = Modifier.testTag("confirm_bulk_delete_button")
                        ) {
                            Text(stringResource(R.string.bulk_delete), color = CoralError)
                        }
                    }
                }
            }
        }

        // Profiles LazyColumn with stable keys (supports 1,000+ profiles smoothly)
        if (profiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.empty_profiles_message),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("profiles_lazy_column"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(
                    items = profiles,
                    key = { it.id }
                ) { profile ->
                    ProfileItemCard(
                        profile = profile,
                        isSelected = profile.id == activeProfileId,
                        isBulkChecked = selectedBulkIds.contains(profile.id),
                        usePersianDigits = usePersianDigits,
                        onSelect = { onSelectProfile(profile.id) },
                        onToggleBulk = { onToggleBulkSelect(profile.id) },
                        onTestLatency = { onTestSingleLatency(profile) },
                        onEdit = { editingProfile = profile },
                        onDuplicate = { onDuplicateProfile(profile) },
                        onDelete = { onDeleteProfile(profile) },
                        onToggleFavorite = { onToggleFavorite(profile) },
                        onTogglePinned = { onTogglePinned(profile) },
                        onCopyUri = {
                            val uri = ConfigParser.toShareUri(profile)
                            clipboardManager.setText(AnnotatedString(uri))
                            onShowMessage("Copied ${profile.protocol.displayName} share link")
                        },
                        onShowQr = { onShowQrCode(profile) },
                        onShowJson = { onShowCoreJson(profile) }
                    )
                }
            }
        }
    }

    if (showImportDialog) {
        PasteConfigImportDialog(
            defaultGroup = selectedGroup ?: "Default",
            onImport = { text, grp ->
                onImportBatchText(text, grp)
                showImportDialog = false
            },
            onDismiss = { showImportDialog = false }
        )
    }
}

@Composable
private fun ProfileItemCard(
    profile: ServerProfile,
    isSelected: Boolean,
    isBulkChecked: Boolean,
    usePersianDigits: Boolean,
    onSelect: () -> Unit,
    onToggleBulk: () -> Unit,
    onTestLatency: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit,
    onTogglePinned: () -> Unit,
    onCopyUri: () -> Unit,
    onShowQr: () -> Unit,
    onShowJson: () -> Unit
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(18.dp)
            )
            .clickable { onSelect() }
            .testTag("profile_card_${profile.id}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onToggleBulk,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (isBulkChecked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                            contentDescription = "Select",
                            tint = if (isBulkChecked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (profile.isPinned) {
                                Icon(
                                    imageVector = Icons.Default.PushPin,
                                    contentDescription = "Pinned",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                text = profile.remarks,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = "${profile.protocol.displayName} • ${profile.network.displayName}/${profile.security.displayName} • ${profile.address}:${
                                LocalizationHelper.formatDigits(profile.port.toString(), usePersianDigits)
                            }",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Latency Display
                val latencyText = when {
                    profile.lastLatencyMs != null -> "${LocalizationHelper.formatDigits(profile.lastLatencyMs.toString(), usePersianDigits)} ms"
                    profile.latencyType == LatencyMeasureType.TIMEOUT -> "Timeout"
                    else -> "Ping"
                }
                val latencyTint = when {
                    profile.lastLatencyMs != null && profile.lastLatencyMs < 180 -> EmeraldAccent
                    profile.lastLatencyMs != null -> AmberWarning
                    profile.latencyType == LatencyMeasureType.TIMEOUT -> CoralError
                    else -> MaterialTheme.colorScheme.primary
                }
                TextButton(
                    onClick = onTestLatency,
                    modifier = Modifier.testTag("ping_profile_${profile.id}")
                ) {
                    Text(
                        text = latencyText,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = latencyTint,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Action Icon Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = profile.groupName,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Row {
                    IconButton(onClick = onTogglePinned, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = stringResource(R.string.pin_profile),
                            tint = if (profile.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onToggleFavorite, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = if (profile.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = stringResource(R.string.favorite_profile),
                            tint = if (profile.isFavorite) AmberWarning else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onCopyUri, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = stringResource(R.string.export_share_uri),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onShowQr, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Default.QrCode,
                            contentDescription = "QR Code",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onShowJson, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = stringResource(R.string.export_xray_json),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("edit_profile_${profile.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.edit_profile),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onDuplicate, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.duplicate_profile),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("delete_profile_${profile.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.delete_profile),
                            tint = CoralError,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PasteConfigImportDialog(
    defaultGroup: String,
    onImport: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var rawText by remember { mutableStateOf("") }
    var groupName by remember { mutableStateOf(defaultGroup) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_paste_links)) },
        text = {
            Column {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text(stringResource(R.string.field_group)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = rawText,
                    onValueChange = { rawText = it },
                    label = { Text("vless://, vmess://, trojan://, ss://, or Xray JSON") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .testTag("import_uri_text_field")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onImport(rawText, groupName.ifBlank { "Default" }) },
                modifier = Modifier.testTag("confirm_import_uri_button")
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ManualProfileEditorScreen(
    initialProfile: ServerProfile?,
    onSave: (ServerProfile) -> Unit,
    onCancel: () -> Unit
) {
    var remarks by remember { mutableStateOf(initialProfile?.remarks ?: "") }
    var groupName by remember { mutableStateOf(initialProfile?.groupName ?: "Default") }
    var protocol by remember { mutableStateOf(initialProfile?.protocol ?: VpnProtocol.VLESS) }
    var address by remember { mutableStateOf(initialProfile?.address ?: "") }
    var portStr by remember { mutableStateOf((initialProfile?.port ?: 443).toString()) }
    var credential by remember { mutableStateOf(initialProfile?.credential ?: "") }
    var encryption by remember { mutableStateOf(initialProfile?.encryption ?: "none") }
    var alterIdStr by remember { mutableStateOf((initialProfile?.alterId ?: 0).toString()) }
    var flow by remember { mutableStateOf(initialProfile?.flow ?: "") }
    var network by remember { mutableStateOf(initialProfile?.network ?: TransportType.TCP) }
    var security by remember { mutableStateOf(initialProfile?.security ?: SecurityType.TLS) }
    var host by remember { mutableStateOf(initialProfile?.host ?: "") }
    var path by remember { mutableStateOf(initialProfile?.path ?: "") }
    var serviceName by remember { mutableStateOf(initialProfile?.serviceName ?: "") }
    var sni by remember { mutableStateOf(initialProfile?.sni ?: "") }
    var alpn by remember { mutableStateOf(initialProfile?.alpn ?: "h2,http/1.1") }
    var fingerprint by remember { mutableStateOf(initialProfile?.fingerprint ?: "chrome") }
    var realityPubKey by remember { mutableStateOf(initialProfile?.realityPublicKey ?: "") }
    var realityShortId by remember { mutableStateOf(initialProfile?.realityShortId ?: "") }
    var realitySpiderX by remember { mutableStateOf(initialProfile?.realitySpiderX ?: "/") }
    var wgPubKey by remember { mutableStateOf(initialProfile?.wireGuardPublicKey ?: "") }
    var allowInsecure by remember { mutableStateOf(initialProfile?.allowInsecure ?: false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = if (initialProfile == null) stringResource(R.string.editor_title_new)
            else stringResource(R.string.editor_title_edit),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = remarks,
            onValueChange = { remarks = it },
            label = { Text(stringResource(R.string.field_remarks)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("editor_remarks_input")
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = groupName,
            onValueChange = { groupName = it },
            label = { Text(stringResource(R.string.field_group)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))

        Text(stringResource(R.string.field_protocol), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VpnProtocol.entries.forEach { proto ->
                FilterChip(
                    selected = protocol == proto,
                    onClick = { protocol = proto },
                    label = { Text(proto.displayName) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text(stringResource(R.string.field_address)) },
                singleLine = true,
                modifier = Modifier
                    .weight(2f)
                    .testTag("editor_address_input")
            )
            OutlinedTextField(
                value = portStr,
                onValueChange = { portStr = it },
                label = { Text(stringResource(R.string.field_port)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .weight(1f)
                    .testTag("editor_port_input")
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = credential,
            onValueChange = { credential = it },
            label = { Text(stringResource(R.string.field_uuid_password)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("editor_credential_input")
        )

        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = encryption,
                onValueChange = { encryption = it },
                label = { Text(stringResource(R.string.field_encryption)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            if (protocol == VpnProtocol.VMESS) {
                OutlinedTextField(
                    value = alterIdStr,
                    onValueChange = { alterIdStr = it },
                    label = { Text(stringResource(R.string.field_alter_id)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            } else if (protocol == VpnProtocol.VLESS) {
                OutlinedTextField(
                    value = flow,
                    onValueChange = { flow = it },
                    label = { Text(stringResource(R.string.field_flow)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(stringResource(R.string.field_network), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TransportType.entries.forEach { net ->
                FilterChip(
                    selected = network == net,
                    onClick = { network = net },
                    label = { Text(net.displayName) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text(stringResource(R.string.field_host)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = if (network == TransportType.GRPC) serviceName else path,
                onValueChange = {
                    if (network == TransportType.GRPC) serviceName = it else path = it
                },
                label = { Text(stringResource(R.string.field_path)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(stringResource(R.string.field_security), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SecurityType.entries.forEach { sec ->
                FilterChip(
                    selected = security == sec,
                    onClick = { security = sec },
                    label = { Text(sec.displayName) }
                )
            }
        }

        if (security != SecurityType.NONE) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = sni,
                onValueChange = { sni = it },
                label = { Text(stringResource(R.string.field_sni)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = alpn,
                    onValueChange = { alpn = it },
                    label = { Text(stringResource(R.string.field_alpn)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = fingerprint,
                    onValueChange = { fingerprint = it },
                    label = { Text(stringResource(R.string.field_fingerprint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (security == SecurityType.REALITY) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = realityPubKey,
                onValueChange = { realityPubKey = it },
                label = { Text(stringResource(R.string.field_reality_pbk)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = realityShortId,
                    onValueChange = { realityShortId = it },
                    label = { Text(stringResource(R.string.field_reality_sid)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = realitySpiderX,
                    onValueChange = { realitySpiderX = it },
                    label = { Text(stringResource(R.string.field_reality_spx)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (protocol == VpnProtocol.WIREGUARD) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = wgPubKey,
                onValueChange = { wgPubKey = it },
                label = { Text("WireGuard Peer Public Key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.field_allow_insecure),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = allowInsecure,
                onCheckedChange = { allowInsecure = it }
            )
        }

        if (allowInsecure) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = CoralError.copy(alpha = 0.15f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = CoralError)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.insecure_warning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = CoralError
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.cancel))
            }
            Button(
                onClick = {
                    val portNum = portStr.toIntOrNull()?.coerceIn(1, 65535) ?: 443
                    val cleanAddress = address.trim()
                    val cleanCred = credential.trim()
                    if (cleanAddress.isNotEmpty() && cleanCred.isNotEmpty()) {
                        onSave(
                            ServerProfile(
                                id = initialProfile?.id ?: 0L,
                                remarks = remarks.trim().ifBlank { "${protocol.displayName}-$cleanAddress:$portNum" },
                                groupName = groupName.trim().ifBlank { "Default" },
                                protocol = protocol,
                                address = cleanAddress,
                                port = portNum,
                                credential = cleanCred,
                                encryption = encryption.trim().ifBlank { "none" },
                                alterId = alterIdStr.toIntOrNull() ?: 0,
                                flow = flow.trim(),
                                network = network,
                                host = host.trim(),
                                path = path.trim(),
                                serviceName = serviceName.trim(),
                                security = security,
                                sni = sni.trim(),
                                alpn = alpn.trim(),
                                fingerprint = fingerprint.trim().ifBlank { "chrome" },
                                realityPublicKey = realityPubKey.trim(),
                                realityShortId = realityShortId.trim(),
                                realitySpiderX = realitySpiderX.trim(),
                                wireGuardPublicKey = wgPubKey.trim(),
                                allowInsecure = allowInsecure,
                                isFavorite = initialProfile?.isFavorite ?: false,
                                isPinned = initialProfile?.isPinned ?: false
                            )
                        )
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("save_manual_profile_button")
            ) {
                Text(stringResource(R.string.save_profile))
            }
        }
        Spacer(modifier = Modifier.height(30.dp))
    }
}

@Composable
fun QrShareModalDialog(
    profile: ServerProfile,
    onDismiss: () -> Unit
) {
    val uri = remember(profile) { ConfigParser.toShareUri(profile) }
    val qrBitmap = remember(uri) { QrCodeHelper.generateQrBitmap(uri, 512) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(profile.remarks, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "Share QR Code",
                        modifier = Modifier
                            .size(220.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(androidx.compose.ui.graphics.Color.White)
                            .padding(8.dp)
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "${profile.protocol.displayName} • ${profile.address}:${profile.port}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.confirm))
            }
        }
    )
}
