package com.example.ui.screens

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.core.sync.CategorySyncStatus
import com.example.core.sync.SyncProgressState
import com.example.domain.model.LatencyMeasureType
import com.example.domain.model.SecurityType
import com.example.domain.model.ServerConnectionStatus
import com.example.domain.model.ServerProfile
import com.example.domain.model.TransportType
import com.example.domain.model.VpnConnectionState
import com.example.domain.model.VpnProtocol
import com.example.parser.ConfigParser
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CoralError
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.viewmodel.LatencyTestProgress
import com.example.ui.viewmodel.ProfileConnectionFilter
import com.example.ui.viewmodel.ProfileExtraFilterState
import com.example.ui.viewmodel.ProfileSortMode
import com.example.ui.viewmodel.TestBulkScope
import com.example.util.LocalizationHelper
import com.example.util.QrCodeHelper
import com.example.util.ResolvedServerLocation
import com.example.util.ServerLocationResolver
import kotlinx.coroutines.delay

// Final Profile Screen Design System Colors
private val ProfileScreenBackground = Color(0xFF080E1B)
private val ProfileRowSurface = Color(0xFF111D30)
private val ProfileRowActiveSurface = Color(0xFF152640)
private val ProfileBadgeSurface = Color(0xFF172740)
private val ProfileDividerColor = Color(0xFF1B2C47)
private val ProfilePrimaryText = Color(0xFFF1F5FC)
private val ProfileSecondaryText = Color(0xFF8295B2)
private val ProfileAccentCyan = Color(0xFF00DDF0)
private val ProfileConnectedGreen = Color(0xFF00E69B)

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
    totalProfilesCount: Int = profiles.size,
    connectionState: VpnConnectionState = VpnConnectionState.DISCONNECTED,
    extraFilter: ProfileExtraFilterState = ProfileExtraFilterState(),
    availableCountries: List<ResolvedServerLocation> = emptyList(),
    latencyTestProgress: LatencyTestProgress = LatencyTestProgress(isTesting = isTestingLatency),
    liveMeasuredProfileIds: Set<Long> = emptySet(),
    personalSyncStatus: CategorySyncStatus = CategorySyncStatus(category = "PERSONAL"),
    onSearchQueryChange: (String) -> Unit,
    onProtocolFilterChange: (VpnProtocol?) -> Unit,
    onGroupFilterChange: (String?) -> Unit,
    onFavoritesFilterChange: (Boolean) -> Unit = {},
    onPinnedFilterChange: (Boolean) -> Unit = {},
    onCountryFilterChange: (String?) -> Unit = {},
    onConnectionFilterChange: (ProfileConnectionFilter) -> Unit = {},
    onResetAllFilters: () -> Unit = {},
    onSortModeChange: (ProfileSortMode) -> Unit,
    onSelectProfile: (Long) -> Unit,
    onConnectToProfile: (ServerProfile) -> Unit = { onSelectProfile(it.id) },
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
    onTestByScope: (TestBulkScope) -> Unit = { onTestAllLatency() },
    onCancelLatencyTest: () -> Unit,
    onShowCoreJson: (ServerProfile) -> Unit,
    onShowQrCode: (ServerProfile) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val lazyListState = rememberLazyListState()

    var showImportDialog by remember { mutableStateOf(false) }
    var showAddProfileMenu by remember { mutableStateOf(false) }
    var showFilterMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showBulkTestMenu by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<ServerProfile?>(null) }
    var isCreatingNew by remember { mutableStateOf(false) }
    var pendingDeleteProfile by remember { mutableStateOf<ServerProfile?>(null) }
    var showBulkDeleteConfirm by remember { mutableStateOf(false) }

    // Local search text state for responsive typing + debounced propagation for large datasets
    var localSearchText by remember { mutableStateOf(searchQuery) }
    LaunchedEffect(searchQuery) {
        if (searchQuery != localSearchText) {
            localSearchText = searchQuery
        }
    }
    LaunchedEffect(localSearchText) {
        if (localSearchText != searchQuery && totalProfilesCount >= 250 && localSearchText.isNotEmpty()) {
            delay(140L)
            onSearchQueryChange(localSearchText)
        }
    }
    LaunchedEffect(searchQuery, selectedProtocol, selectedGroup, extraFilter, sortMode) {
        if (profiles.isNotEmpty() && (lazyListState.firstVisibleItemIndex > 0 || lazyListState.firstVisibleItemScrollOffset > 0)) {
            lazyListState.scrollToItem(0)
        }
    }

    val readClipboardText: () -> String = {
        val primaryClipText = runCatching {
            val sysClipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val clipData = sysClipboard?.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                (0 until clipData.itemCount)
                    .mapNotNull { i -> clipData.getItemAt(i)?.coerceToText(context)?.toString()?.trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString("\n")
            } else {
                null
            }
        }.getOrNull()
        primaryClipText?.takeIf { it.isNotBlank() }
            ?: clipboardManager.getText()?.text.orEmpty()
    }

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
                onShowMessage("خطا در خواندن تصویر QR")
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
            onShowMessage("مجوز دوربین برای اسکن کد QR لازم است")
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

    val isVpnConnected = connectionState == VpnConnectionState.CONNECTED
    val activeExtraCount = extraFilter.activeFilterCount + (if (selectedGroup != null) 1 else 0)

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ProfileScreenBackground)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            // SECTION 3 & 10: COMPACT HEADER + PRIMARY ADD CONFIGURATION BUTTON
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "پروفایلهای من",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = ProfilePrimaryText,
                            modifier = Modifier.testTag("profiles_header_title")
                        )
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = ProfileBadgeSurface,
                            border = BorderStroke(1.dp, ProfileDividerColor),
                            modifier = Modifier.testTag("profile_total_count_badge")
                        ) {
                            val totalCountFormatted = LocalizationHelper.formatDigits(
                                maxOf(totalProfilesCount, profiles.size).toString(),
                                usePersianDigits
                            )
                            val countText = if (profiles.size != totalProfilesCount && totalProfilesCount > 0) {
                                "${LocalizationHelper.formatDigits(profiles.size.toString(), usePersianDigits)} / $totalCountFormatted"
                            } else {
                                "$totalCountFormatted سرور"
                            }
                            Text(
                                text = countText,
                                style = MaterialTheme.typography.labelSmall,
                                color = ProfileAccentCyan,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "مدیریت کانفیگهای اتصال",
                        style = MaterialTheme.typography.bodySmall,
                        color = ProfileSecondaryText,
                        modifier = Modifier.testTag("profiles_header_subtitle")
                    )
                }

                // Compact Quick Clipboard Button + Primary "افزودن پروفایل" Button with Secondary Menu
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = ProfileRowSurface,
                        border = BorderStroke(1.dp, ProfileDividerColor),
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                val clip = readClipboardText()
                                onImportBatchText(clip, selectedGroup ?: "Default")
                            }
                            .testTag("import_clipboard_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = stringResource(R.string.import_clipboard),
                                tint = ProfileAccentCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Box {
                        Button(
                            onClick = { showAddProfileMenu = true },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ProfileAccentCyan,
                                contentColor = Color(0xFF04101E)
                            ),
                            modifier = Modifier
                                .height(40.dp)
                                .testTag("open_import_dialog_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "افزودن پروفایل",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showAddProfileMenu,
                            onDismissRequest = { showAddProfileMenu = false },
                            modifier = Modifier
                                .background(ProfileRowSurface)
                                .testTag("add_profile_dropdown_menu")
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "درون‌ریزی از کلیپ‌بورد (Import from clipboard)",
                                        color = ProfilePrimaryText
                                    )
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, tint = ProfileAccentCyan)
                                },
                                onClick = {
                                    showAddProfileMenu = false
                                    val clip = readClipboardText()
                                    onImportBatchText(clip, selectedGroup ?: "Default")
                                },
                                modifier = Modifier.testTag("menu_import_clipboard")
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "اسکن کد QR با دوربین (Scan QR code)",
                                        color = ProfilePrimaryText
                                    )
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.CameraAlt, contentDescription = null, tint = ProfileAccentCyan)
                                },
                                onClick = {
                                    showAddProfileMenu = false
                                    cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                                },
                                modifier = Modifier.testTag("menu_scan_qr_camera")
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "اسکن تصویر QR از گالری (QR from Gallery)",
                                        color = ProfilePrimaryText
                                    )
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Image, contentDescription = null, tint = ProfileAccentCyan)
                                },
                                onClick = {
                                    showAddProfileMenu = false
                                    qrGalleryLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                modifier = Modifier.testTag("menu_scan_qr_gallery")
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "تنظیم دستی پروفایل (Manual configuration)",
                                        color = ProfilePrimaryText
                                    )
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Add, contentDescription = null, tint = ProfileAccentCyan)
                                },
                                onClick = {
                                    showAddProfileMenu = false
                                    isCreatingNew = true
                                },
                                modifier = Modifier.testTag("open_manual_editor_button")
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "درون‌ریزی از فایل (Import from file)",
                                        color = ProfilePrimaryText
                                    )
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.FileOpen, contentDescription = null, tint = ProfileAccentCyan)
                                },
                                onClick = {
                                    showAddProfileMenu = false
                                    filePickerLauncher.launch("*/*")
                                },
                                modifier = Modifier.testTag("menu_import_file")
                            )
                            HorizontalDivider(color = ProfileDividerColor)
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "چسباندن متن کانفیگ / لینک اشتراک",
                                        color = ProfileSecondaryText
                                    )
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.ContentPaste, contentDescription = null, tint = ProfileSecondaryText)
                                },
                                onClick = {
                                    showAddProfileMenu = false
                                    showImportDialog = true
                                },
                                modifier = Modifier.testTag("menu_paste_text_dialog")
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // SECTION 4: COMPACT SEARCH BAR (Pinned above list)
            OutlinedTextField(
                value = localSearchText,
                onValueChange = { newText ->
                    localSearchText = newText
                    if (newText.isEmpty() || totalProfilesCount < 250) {
                        onSearchQueryChange(newText)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("profile_search_input"),
                placeholder = {
                    Text(
                        text = "جستجوی نام، IP، دامنه، پروتکل یا کشور...",
                        style = MaterialTheme.typography.bodySmall,
                        color = ProfileSecondaryText
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = ProfileSecondaryText,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    if (localSearchText.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                localSearchText = ""
                                onSearchQueryChange("")
                            },
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("clear_search_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "پاک کردن جستجو",
                                tint = ProfileSecondaryText,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = ProfilePrimaryText),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = ProfileRowSurface,
                    unfocusedContainerColor = ProfileRowSurface,
                    focusedBorderColor = ProfileAccentCyan,
                    unfocusedBorderColor = ProfileDividerColor,
                    cursorColor = ProfileAccentCyan
                )
            )

            Spacer(modifier = Modifier.height(6.dp))

            // SECTION 5: HORIZONTALLY SCROLLABLE PROTOCOL FILTER CHIPS
            val primaryProtocols = remember {
                listOf(
                    VpnProtocol.VLESS,
                    VpnProtocol.VMESS,
                    VpnProtocol.TROJAN,
                    VpnProtocol.SHADOWSOCKS,
                    VpnProtocol.HYSTERIA2,
                    VpnProtocol.WIREGUARD,
                    VpnProtocol.TUIC
                )
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("protocol_filter_row")
            ) {
                item(key = "ALL") {
                    val isAllSelected = selectedProtocol == null
                    FilterChip(
                        selected = isAllSelected,
                        onClick = { onProtocolFilterChange(null) },
                        label = {
                            Text(
                                text = "همه",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isAllSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = ProfileRowSurface,
                            labelColor = ProfileSecondaryText,
                            selectedContainerColor = ProfileAccentCyan.copy(alpha = 0.16f),
                            selectedLabelColor = ProfileAccentCyan
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isAllSelected,
                            borderColor = ProfileDividerColor,
                            selectedBorderColor = ProfileAccentCyan.copy(alpha = 0.6f)
                        ),
                        modifier = Modifier.testTag("protocol_chip_ALL")
                    )
                }
                items(
                    items = primaryProtocols,
                    key = { it.name }
                ) { proto ->
                    val isSelected = selectedProtocol == proto
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            onProtocolFilterChange(if (isSelected) null else proto)
                        },
                        label = {
                            Text(
                                text = proto.displayName,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = ProfileRowSurface,
                            labelColor = ProfileSecondaryText,
                            selectedContainerColor = ProfileAccentCyan.copy(alpha = 0.16f),
                            selectedLabelColor = ProfileAccentCyan
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = ProfileDividerColor,
                            selectedBorderColor = ProfileAccentCyan.copy(alpha = 0.6f)
                        ),
                        modifier = Modifier.testTag("protocol_chip_${proto.name}")
                    )
                }
            }

            // SECTION 5 (Additional Filters), SECTION 6 (Sorting), SECTION 11 (Bulk Server Testing)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Additional Filters Menu (Favorites, Pinned, Default, Country, Connection status)
                    Box {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (activeExtraCount > 0) ProfileAccentCyan.copy(alpha = 0.16f) else ProfileRowSurface,
                            border = BorderStroke(
                                1.dp,
                                if (activeExtraCount > 0) ProfileAccentCyan.copy(alpha = 0.6f) else ProfileDividerColor
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { showFilterMenu = true }
                                .testTag("extra_filter_menu_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = null,
                                    tint = if (activeExtraCount > 0) ProfileAccentCyan else ProfileSecondaryText,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = if (activeExtraCount > 0) {
                                        "فیلتر (${LocalizationHelper.formatDigits(activeExtraCount.toString(), usePersianDigits)})"
                                    } else {
                                        "فیلترها"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (activeExtraCount > 0) ProfileAccentCyan else ProfilePrimaryText,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = showFilterMenu,
                            onDismissRequest = { showFilterMenu = false },
                            modifier = Modifier
                                .background(ProfileRowSurface)
                                .testTag("extra_filter_dropdown_menu")
                        ) {
                            if (activeExtraCount > 0 || selectedProtocol != null || localSearchText.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "پاک کردن تمام فیلترها",
                                            color = CoralError,
                                            fontWeight = FontWeight.Bold
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Clear,
                                            contentDescription = null,
                                            tint = CoralError
                                        )
                                    },
                                    onClick = {
                                        localSearchText = ""
                                        onResetAllFilters()
                                        showFilterMenu = false
                                    },
                                    modifier = Modifier.testTag("filter_menu_reset")
                                )
                                HorizontalDivider(color = ProfileDividerColor)
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "علاقه‌مندی‌ها (Favorites)",
                                        color = if (extraFilter.favoritesOnly) ProfileAccentCyan else ProfilePrimaryText
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (extraFilter.favoritesOnly) Icons.Default.Check else Icons.Default.StarBorder,
                                        contentDescription = null,
                                        tint = if (extraFilter.favoritesOnly) ProfileAccentCyan else ProfileSecondaryText
                                    )
                                },
                                onClick = {
                                    onFavoritesFilterChange(!extraFilter.favoritesOnly)
                                    showFilterMenu = false
                                },
                                modifier = Modifier.testTag("filter_menu_favorites")
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "سنجاق‌شده‌ها (Pinned)",
                                        color = if (extraFilter.pinnedOnly) ProfileAccentCyan else ProfilePrimaryText
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (extraFilter.pinnedOnly) Icons.Default.Check else Icons.Default.PushPin,
                                        contentDescription = null,
                                        tint = if (extraFilter.pinnedOnly) ProfileAccentCyan else ProfileSecondaryText
                                    )
                                },
                                onClick = {
                                    onPinnedFilterChange(!extraFilter.pinnedOnly)
                                    showFilterMenu = false
                                },
                                modifier = Modifier.testTag("filter_menu_pinned")
                            )
                            DropdownMenuItem(
                                text = {
                                    val isDefaultGroup = selectedGroup.equals("Default", ignoreCase = true)
                                    Text(
                                        text = "گروه پیش‌فرض (Default)",
                                        color = if (isDefaultGroup) ProfileAccentCyan else ProfilePrimaryText
                                    )
                                },
                                leadingIcon = {
                                    val isDefaultGroup = selectedGroup.equals("Default", ignoreCase = true)
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = if (isDefaultGroup) ProfileAccentCyan else Color.Transparent
                                    )
                                },
                                onClick = {
                                    val isDefaultGroup = selectedGroup.equals("Default", ignoreCase = true)
                                    onGroupFilterChange(if (isDefaultGroup) null else "Default")
                                    showFilterMenu = false
                                },
                                modifier = Modifier.testTag("filter_menu_default_group")
                            )

                            if (groups.any { !it.equals("Default", ignoreCase = true) }) {
                                HorizontalDivider(color = ProfileDividerColor)
                                groups.filter { !it.equals("Default", ignoreCase = true) }.forEach { grp ->
                                    val isGrpSelected = selectedGroup.equals(grp, ignoreCase = true)
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "گروه: $grp",
                                                color = if (isGrpSelected) ProfileAccentCyan else ProfilePrimaryText
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = if (isGrpSelected) ProfileAccentCyan else Color.Transparent
                                            )
                                        },
                                        onClick = {
                                            onGroupFilterChange(if (isGrpSelected) null else grp)
                                            showFilterMenu = false
                                        }
                                    )
                                }
                            }

                            HorizontalDivider(color = ProfileDividerColor)
                            // Connection Status Filters
                            ProfileConnectionFilter.entries.forEach { statusFilter ->
                                val isSel = extraFilter.connectionFilter == statusFilter
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "وضعیت: ${statusFilter.labelFa}",
                                            color = if (isSel) ProfileAccentCyan else ProfilePrimaryText
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = if (isSel) ProfileAccentCyan else Color.Transparent
                                        )
                                    },
                                    onClick = {
                                        onConnectionFilterChange(statusFilter)
                                        showFilterMenu = false
                                    },
                                    modifier = Modifier.testTag("filter_menu_status_${statusFilter.name}")
                                )
                            }

                            if (availableCountries.isNotEmpty()) {
                                HorizontalDivider(color = ProfileDividerColor)
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "کشور: همه کشورها",
                                            color = if (extraFilter.countryCode == null) ProfileAccentCyan else ProfilePrimaryText
                                        )
                                    },
                                    onClick = {
                                        onCountryFilterChange(null)
                                        showFilterMenu = false
                                    },
                                    modifier = Modifier.testTag("filter_menu_country_ALL")
                                )
                                availableCountries.forEach { loc ->
                                    val isLocSelected = extraFilter.countryCode == loc.countryCode
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "${loc.flagEmoji} ${loc.nameFa} (${loc.countryCode})",
                                                color = if (isLocSelected) ProfileAccentCyan else ProfilePrimaryText
                                            )
                                        },
                                        onClick = {
                                            onCountryFilterChange(if (isLocSelected) null else loc.countryCode)
                                            showFilterMenu = false
                                        },
                                        modifier = Modifier.testTag("filter_menu_country_${loc.countryCode}")
                                    )
                                }
                            }
                        }
                    }

                    // Compact Sorting Menu
                    Box {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = ProfileRowSurface,
                            border = BorderStroke(1.dp, ProfileDividerColor),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { showSortMenu = true }
                                .testTag("sort_menu_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SwapVert,
                                    contentDescription = null,
                                    tint = ProfileAccentCyan,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = sortMode.labelFa,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ProfilePrimaryText,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false },
                            modifier = Modifier
                                .background(ProfileRowSurface)
                                .testTag("sort_dropdown_menu")
                        ) {
                            val sortModes = listOf(
                                ProfileSortMode.LATENCY,
                                ProfileSortMode.HIGHEST_PING,
                                ProfileSortMode.NAME,
                                ProfileSortMode.RECENTLY_ADDED,
                                ProfileSortMode.DEFAULT,
                                ProfileSortMode.FAVORITES_FIRST
                            )
                            sortModes.forEach { mode ->
                                val isSel = sortMode == mode
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "${mode.labelFa} (${mode.labelEn})",
                                            color = if (isSel) ProfileAccentCyan else ProfilePrimaryText,
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = if (isSel) ProfileAccentCyan else Color.Transparent
                                        )
                                    },
                                    onClick = {
                                        onSortModeChange(mode)
                                        showSortMenu = false
                                    },
                                    modifier = Modifier.testTag("sort_option_${mode.name}")
                                )
                            }
                        }
                    }
                }

                // Bulk Server Testing Controls (Test All / Filtered / Selected + Cancel)
                Box {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isTestingLatency) CoralError.copy(alpha = 0.16f) else ProfileRowSurface,
                            border = BorderStroke(
                                1.dp,
                                if (isTestingLatency) CoralError.copy(alpha = 0.6f) else ProfileDividerColor
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    if (isTestingLatency) {
                                        onCancelLatencyTest()
                                    } else {
                                        onTestAllLatency()
                                    }
                                }
                                .testTag("test_all_profiles_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = null,
                                    tint = if (isTestingLatency) CoralError else ProfileAccentCyan,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = if (isTestingLatency) "لغو تست" else "تست پینگ",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isTestingLatency) CoralError else ProfilePrimaryText,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = ProfileRowSurface,
                            border = BorderStroke(1.dp, ProfileDividerColor),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { showBulkTestMenu = true }
                                .testTag("bulk_test_scope_menu_button")
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "گزینه‌های تست دسته‌جمعی",
                                    tint = ProfileSecondaryText,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    DropdownMenu(
                        expanded = showBulkTestMenu,
                        onDismissRequest = { showBulkTestMenu = false },
                        modifier = Modifier
                            .background(ProfileRowSurface)
                            .testTag("bulk_test_dropdown_menu")
                    ) {
                        DropdownMenuItem(
                            text = { Text("تست همه پروفایل‌ها (Test all)", color = ProfilePrimaryText) },
                            leadingIcon = { Icon(Icons.Default.Speed, contentDescription = null, tint = ProfileAccentCyan) },
                            onClick = {
                                showBulkTestMenu = false
                                onTestByScope(TestBulkScope.ALL)
                            },
                            modifier = Modifier.testTag("bulk_test_all")
                        )
                        DropdownMenuItem(
                            text = { Text("تست پروفایل‌های فیلترشده (Test filtered)", color = ProfilePrimaryText) },
                            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null, tint = ProfileAccentCyan) },
                            onClick = {
                                showBulkTestMenu = false
                                onTestByScope(TestBulkScope.FILTERED)
                            },
                            modifier = Modifier.testTag("bulk_test_filtered")
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "تست پروفایل‌های انتخاب‌شده (${LocalizationHelper.formatDigits(selectedBulkIds.size.toString(), usePersianDigits)})",
                                    color = if (selectedBulkIds.isNotEmpty()) ProfilePrimaryText else ProfileSecondaryText
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.CheckBox,
                                    contentDescription = null,
                                    tint = if (selectedBulkIds.isNotEmpty()) ProfileAccentCyan else ProfileSecondaryText
                                )
                            },
                            enabled = selectedBulkIds.isNotEmpty(),
                            onClick = {
                                showBulkTestMenu = false
                                onTestByScope(TestBulkScope.SELECTED)
                            },
                            modifier = Modifier.testTag("bulk_test_selected")
                        )
                    }
                }
            }

            // Compact Bulk Testing Progress Bar (Section 11)
            if (isTestingLatency || latencyTestProgress.isTesting) {
                val total = latencyTestProgress.totalCount.coerceAtLeast(1)
                val completed = latencyTestProgress.completedCount.coerceIn(0, total)
                val progressFraction = completed.toFloat() / total.toFloat()

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .testTag("latency_test_progress_banner"),
                    shape = RoundedCornerShape(12.dp),
                    color = ProfileRowSurface,
                    border = BorderStroke(1.dp, ProfileAccentCyan.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${latencyTestProgress.scope.labelFa}: ${
                                    LocalizationHelper.formatDigits(completed.toString(), usePersianDigits)
                                } از ${
                                    LocalizationHelper.formatDigits(total.toString(), usePersianDigits)
                                } (سالم: ${
                                    LocalizationHelper.formatDigits(latencyTestProgress.reachableCount.toString(), usePersianDigits)
                                })",
                                style = MaterialTheme.typography.labelSmall,
                                color = ProfilePrimaryText,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "لغو",
                                style = MaterialTheme.typography.labelSmall,
                                color = CoralError,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable { onCancelLatencyTest() }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                    .testTag("cancel_latency_test_button")
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = ProfileAccentCyan,
                            trackColor = ProfileDividerColor
                        )
                    }
                }
            }

            // Non-intrusive Automatic Personal Subscription Sync Banner
            if (personalSyncStatus.state == SyncProgressState.SYNCING) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .testTag("personal_auto_sync_banner"),
                    shape = RoundedCornerShape(10.dp),
                    color = ProfileRowSurface,
                    border = BorderStroke(1.dp, ProfileAccentCyan.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = personalSyncStatus.messageFa.ifBlank { "در حال بروزرسانی اشتراک‌های شخصی..." },
                            style = MaterialTheme.typography.labelSmall,
                            color = ProfileAccentCyan,
                            fontWeight = FontWeight.SemiBold
                        )
                        LinearProgressIndicator(
                            modifier = Modifier
                                .width(64.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = ProfileAccentCyan,
                            trackColor = ProfileDividerColor
                        )
                    }
                }
            }

            // Bulk Selection Bar (when user selects profiles for bulk test or deletion)
            if (selectedBulkIds.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .testTag("bulk_selection_bar"),
                    shape = RoundedCornerShape(12.dp),
                    color = ProfileRowActiveSurface,
                    border = BorderStroke(1.dp, ProfileAccentCyan.copy(alpha = 0.45f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${LocalizationHelper.formatDigits(selectedBulkIds.size.toString(), usePersianDigits)} پروفایل انتخاب‌شده",
                            style = MaterialTheme.typography.labelMedium,
                            color = ProfilePrimaryText,
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = { onTestByScope(TestBulkScope.SELECTED) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("تست انتخاب‌شده‌ها", color = ProfileAccentCyan, style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(
                                onClick = onClearBulkSelection,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(stringResource(R.string.cancel), color = ProfileSecondaryText, style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(
                                onClick = { showBulkDeleteConfirm = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.testTag("confirm_bulk_delete_button")
                            ) {
                                Text(stringResource(R.string.bulk_delete), color = CoralError, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // SECTION 7 & 12: COMPACT SERVER LIST OPTIMIZED FOR 1000+ PROFILES
            if (profiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.empty_profiles_message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ProfileSecondaryText
                        )
                        if (localSearchText.isNotEmpty() || selectedProtocol != null || activeExtraCount > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(
                                onClick = {
                                    localSearchText = ""
                                    onResetAllFilters()
                                }
                            ) {
                                Text("پاک کردن فیلترها", color = ProfileAccentCyan)
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("profiles_lazy_column"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(
                        items = profiles,
                        key = { it.id },
                        contentType = { "compact_profile_row" }
                    ) { profile ->
                        val isActiveProfile = profile.id == activeProfileId
                        val isConnectedProfile = isActiveProfile && isVpnConnected
                        val isLiveMeasured = liveMeasuredProfileIds.contains(profile.id)

                        CompactProfileRow(
                            profile = profile,
                            isSelected = isActiveProfile,
                            isConnected = isConnectedProfile,
                            isBulkChecked = selectedBulkIds.contains(profile.id),
                            isLiveMeasured = isLiveMeasured,
                            usePersianDigits = usePersianDigits,
                            onSelect = { onSelectProfile(profile.id) },
                            onConnect = { onConnectToProfile(profile) },
                            onToggleBulk = { onToggleBulkSelect(profile.id) },
                            onTestLatency = { onTestSingleLatency(profile) },
                            onEdit = { editingProfile = profile },
                            onDuplicate = { onDuplicateProfile(profile) },
                            onRequestDelete = { pendingDeleteProfile = profile },
                            onToggleFavorite = { onToggleFavorite(profile) },
                            onTogglePinned = { onTogglePinned(profile) },
                            onCopyUri = {
                                val uri = ConfigParser.toShareUri(profile)
                                clipboardManager.setText(AnnotatedString(uri))
                                onShowMessage("لینک کانفیگ ${profile.protocol.displayName} کپی شد")
                            },
                            onShareUri = {
                                val uri = ConfigParser.toShareUri(profile)
                                clipboardManager.setText(AnnotatedString(uri))
                                runCatching {
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, profile.remarks)
                                        putExtra(Intent.EXTRA_TEXT, uri)
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, profile.remarks))
                                }.onFailure {
                                    onShowMessage("لینک اشتراک‌گذاری در کلیپ‌بورد کپی شد")
                                }
                            },
                            onShowQr = { onShowQrCode(profile) },
                            onShowJson = { onShowCoreJson(profile) }
                        )
                    }
                }
            }
        }
    }

    // Destructive Single Profile Delete Confirmation Dialog (Section 9)
    pendingDeleteProfile?.let { targetProfile ->
        AlertDialog(
            onDismissRequest = { pendingDeleteProfile = null },
            modifier = Modifier.testTag("delete_confirm_dialog"),
            containerColor = ProfileRowSurface,
            title = {
                Text(
                    text = "حذف پروفایل",
                    color = ProfilePrimaryText,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "آیا از حذف پروفایل «${targetProfile.remarks}» اطمینان دارید؟",
                    color = ProfileSecondaryText
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteProfile(targetProfile)
                        pendingDeleteProfile = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CoralError),
                    modifier = Modifier.testTag("confirm_delete_profile_button")
                ) {
                    Text("حذف پروفایل", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDeleteProfile = null },
                    modifier = Modifier.testTag("cancel_delete_profile_button")
                ) {
                    Text(stringResource(R.string.cancel), color = ProfileSecondaryText)
                }
            }
        )
    }

    // Destructive Bulk Delete Confirmation Dialog
    if (showBulkDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBulkDeleteConfirm = false },
            containerColor = ProfileRowSurface,
            title = {
                Text(
                    text = "حذف گروهی پروفایل‌ها",
                    color = ProfilePrimaryText,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "آیا از حذف ${LocalizationHelper.formatDigits(selectedBulkIds.size.toString(), usePersianDigits)} پروفایل انتخاب‌شده اطمینان دارید؟",
                    color = ProfileSecondaryText
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBulkDeleteConfirm = false
                        onDeleteBulkSelected()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CoralError)
                ) {
                    Text("حذف همه انتخاب‌شده‌ها", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel), color = ProfileSecondaryText)
                }
            }
        )
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

/**
 * SECTION 7, 8 & 9: COMPACT PROFILE ROW DESIGN (76–84dp target height)
 * - Right side (Start in Persian RTL): Country flag, Profile name (+ connected/pinned/fav badge), Protocol & transport info.
 * - Left side (End in Persian RTL): Real latency measurement in ms (color-coded + live/cached distinction), Three-dot contextual menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactProfileRow(
    profile: ServerProfile,
    isSelected: Boolean,
    isConnected: Boolean,
    isBulkChecked: Boolean,
    isLiveMeasured: Boolean,
    usePersianDigits: Boolean,
    onSelect: () -> Unit,
    onConnect: () -> Unit,
    onToggleBulk: () -> Unit,
    onTestLatency: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onRequestDelete: () -> Unit,
    onToggleFavorite: () -> Unit,
    onTogglePinned: () -> Unit,
    onCopyUri: () -> Unit,
    onShareUri: () -> Unit,
    onShowQr: () -> Unit,
    onShowJson: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    val location = remember(profile.remarks, profile.groupName, profile.address, profile.host, profile.sni) {
        ServerLocationResolver.resolve(profile)
    }

    val containerBg = when {
        isConnected -> Color(0xFF12283A)
        isSelected -> ProfileRowActiveSurface
        isBulkChecked -> Color(0xFF162742)
        else -> ProfileRowSurface
    }

    val subtleBorderColor = when {
        isConnected -> ProfileConnectedGreen.copy(alpha = 0.45f)
        isSelected -> ProfileAccentCyan.copy(alpha = 0.45f)
        else -> ProfileDividerColor.copy(alpha = 0.55f)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clip(RoundedCornerShape(14.dp))
            .testTag("profile_card_${profile.id}"),
        shape = RoundedCornerShape(14.dp),
        color = containerBg,
        border = BorderStroke(width = 1.dp, color = subtleBorderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // RIGHT SIDE (Start in RTL): Subtle Cyan Indicator + Country Flag + Profile Name + Protocol/Transport
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .combinedClickable(
                        onClick = onSelect,
                        onLongClick = onToggleBulk
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Subtle vertical cyan/green indicator bar for active/connected profile
                if (isSelected || isConnected) {
                    Box(
                        modifier = Modifier
                            .width(3.5.dp)
                            .height(44.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (isConnected) ProfileConnectedGreen else ProfileAccentCyan)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                // Country Flag Badge (tappable to toggle bulk selection if needed)
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (isBulkChecked) ProfileAccentCyan.copy(alpha = 0.22f) else ProfileBadgeSurface)
                        .border(
                            width = 1.dp,
                            color = if (isBulkChecked) ProfileAccentCyan else ProfileDividerColor,
                            shape = RoundedCornerShape(11.dp)
                        )
                        .clickable { onToggleBulk() },
                    contentAlignment = Alignment.Center
                ) {
                    if (isBulkChecked) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "انتخاب‌شده",
                            tint = ProfileAccentCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Text(
                            text = location.flagEmoji,
                            fontSize = 18.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (profile.isPinned) {
                            Icon(
                                imageVector = Icons.Default.PushPin,
                                contentDescription = "Pinned",
                                tint = ProfileAccentCyan,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                        if (profile.isFavorite) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Favorite",
                                tint = AmberWarning,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                        Text(
                            text = profile.remarks,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = ProfilePrimaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        // Connected or Selected small badge
                        if (isConnected) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = ProfileConnectedGreen.copy(alpha = 0.16f),
                                border = BorderStroke(0.8.dp, ProfileConnectedGreen.copy(alpha = 0.5f)),
                                modifier = Modifier.testTag("connected_badge_${profile.id}")
                            ) {
                                Text(
                                    text = "متصل",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    color = ProfileConnectedGreen,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                )
                            }
                        } else if (isSelected) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = ProfileAccentCyan.copy(alpha = 0.14f),
                                border = BorderStroke(0.8.dp, ProfileAccentCyan.copy(alpha = 0.4f)),
                                modifier = Modifier.testTag("active_badge_${profile.id}")
                            ) {
                                Text(
                                    text = "فعال",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    color = ProfileAccentCyan,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    // Protocol, Transport, Security & Server Endpoint rendered strictly in LTR
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        val portFormatted = LocalizationHelper.formatDigits(profile.port.toString(), usePersianDigits)
                        val transportLabel = if (profile.security != SecurityType.NONE) {
                            "${profile.network.displayName}/${profile.security.displayName}"
                        } else {
                            profile.network.displayName
                        }
                        Text(
                            text = "${profile.protocol.displayName} • $transportLabel • ${profile.address}:$portFormatted",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = ProfileSecondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // LEFT SIDE (End in RTL): Actual Latency in ms + Three-Dot Contextual Menu
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // SECTION 8: REAL VPN LATENCY DISPLAY (Primary) + SEPARATE DIAGNOSTIC METRICS (TCP Connect & Connection Status)
                val realLatencyMs = profile.realVpnLatencyMs
                val displayLatencyMs = realLatencyMs ?: profile.lastLatencyMs
                val isProxyVerified = profile.latencyType == LatencyMeasureType.PROXY_HTTP_DELAY && realLatencyMs != null
                val connStatus = profile.connectionStatus

                val latencyColor = when {
                    displayLatencyMs != null && displayLatencyMs < 160L -> ProfileConnectedGreen // Excellent: green
                    displayLatencyMs != null && displayLatencyMs <= 280L -> ProfileAccentCyan    // Good: cyan
                    displayLatencyMs != null && displayLatencyMs <= 550L -> AmberWarning         // Moderate: amber
                    displayLatencyMs != null -> CoralError                                       // Poor: red
                    connStatus == ServerConnectionStatus.FAILED -> CoralError                    // Failed: red
                    else -> ProfileSecondaryText                                                 // Untested: gray
                }

                val latencyLabel = when {
                    displayLatencyMs != null -> "${LocalizationHelper.formatDigits(displayLatencyMs.toString(), usePersianDigits)} ms"
                    connStatus == ServerConnectionStatus.FAILED -> "ناموفق"
                    else -> "-- ms"
                }

                val measurementSubLabel = when {
                    isProxyVerified -> if (isLiveMeasured) "● HTTP 204" else "HTTP 204"
                    displayLatencyMs != null && profile.latencyType == LatencyMeasureType.TCP_HANDSHAKE -> "TCP"
                    displayLatencyMs != null -> if (isLiveMeasured) "● زنده" else "ذخیره"
                    connStatus == ServerConnectionStatus.FAILED -> "خطای پروکسی"
                    else -> "تست واقعی"
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = latencyColor.copy(alpha = 0.10f),
                    border = BorderStroke(0.8.dp, latencyColor.copy(alpha = 0.30f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onTestLatency() }
                        .testTag("ping_profile_${profile.id}")
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = latencyLabel,
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = latencyColor,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = measurementSubLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            color = latencyColor.copy(alpha = 0.85f)
                        )
                        profile.tcpConnectMs?.let { tcpMs ->
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Text(
                                    text = "TCP: ${LocalizationHelper.formatDigits(tcpMs.toString(), usePersianDigits)}ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 8.sp,
                                    color = ProfileSecondaryText,
                                    modifier = Modifier.testTag("tcp_diag_${profile.id}")
                                )
                            }
                        }
                    }
                }

                // SECTION 9: THREE-DOT CONTEXTUAL ACTION MENU
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("profile_menu_button_${profile.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "منوی عملیات پروفایل",
                            tint = ProfileSecondaryText,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        modifier = Modifier
                            .background(ProfileRowSurface)
                            .testTag("profile_context_menu_${profile.id}")
                    ) {
                        DropdownMenuItem(
                            text = { Text("اتصال به پروفایل (Connect)", color = ProfileConnectedGreen, fontWeight = FontWeight.SemiBold) },
                            leadingIcon = { Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = ProfileConnectedGreen) },
                            onClick = {
                                menuExpanded = false
                                onConnect()
                            },
                            modifier = Modifier.testTag("menu_connect_${profile.id}")
                        )
                        DropdownMenuItem(
                            text = { Text("ویرایش پروفایل (Edit)", color = ProfilePrimaryText) },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = ProfileAccentCyan) },
                            onClick = {
                                menuExpanded = false
                                onEdit()
                            },
                            modifier = Modifier.testTag("edit_profile_${profile.id}")
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (profile.isPinned) "برداشتن سنجاق (Unpin)" else "سنجاق کردن (Pin)",
                                    color = ProfilePrimaryText
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.PushPin,
                                    contentDescription = null,
                                    tint = if (profile.isPinned) ProfileAccentCyan else ProfileSecondaryText
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onTogglePinned()
                            },
                            modifier = Modifier.testTag("menu_pin_${profile.id}")
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (profile.isFavorite) "حذف از علاقه‌مندی‌ها (Remove favorite)" else "افزودن به علاقه‌مندی‌ها (Favorite)",
                                    color = ProfilePrimaryText
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (profile.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                                    contentDescription = null,
                                    tint = if (profile.isFavorite) AmberWarning else ProfileSecondaryText
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onToggleFavorite()
                            },
                            modifier = Modifier.testTag("menu_favorite_${profile.id}")
                        )
                        DropdownMenuItem(
                            text = { Text("کپی کانفیگ (Copy configuration)", color = ProfilePrimaryText) },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, tint = ProfileSecondaryText) },
                            onClick = {
                                menuExpanded = false
                                onCopyUri()
                            },
                            modifier = Modifier.testTag("menu_copy_${profile.id}")
                        )
                        DropdownMenuItem(
                            text = { Text("اشتراک‌گذاری کانفیگ (Share configuration)", color = ProfilePrimaryText) },
                            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = ProfileSecondaryText) },
                            onClick = {
                                menuExpanded = false
                                onShareUri()
                            },
                            modifier = Modifier.testTag("menu_share_${profile.id}")
                        )
                        DropdownMenuItem(
                            text = { Text("تولید کد QR (Generate QR code)", color = ProfilePrimaryText) },
                            leadingIcon = { Icon(Icons.Default.QrCode, contentDescription = null, tint = ProfileSecondaryText) },
                            onClick = {
                                menuExpanded = false
                                onShowQr()
                            },
                            modifier = Modifier.testTag("menu_qr_${profile.id}")
                        )
                        DropdownMenuItem(
                            text = { Text("مشاهده کانفیگ (View configuration)", color = ProfilePrimaryText) },
                            leadingIcon = { Icon(Icons.Default.Code, contentDescription = null, tint = ProfileSecondaryText) },
                            onClick = {
                                menuExpanded = false
                                onShowJson()
                            },
                            modifier = Modifier.testTag("menu_json_${profile.id}")
                        )
                        DropdownMenuItem(
                            text = { Text("تکثیر پروفایل (Duplicate)", color = ProfilePrimaryText) },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, tint = ProfileSecondaryText) },
                            onClick = {
                                menuExpanded = false
                                onDuplicate()
                            },
                            modifier = Modifier.testTag("menu_duplicate_${profile.id}")
                        )
                        HorizontalDivider(color = ProfileDividerColor)
                        DropdownMenuItem(
                            text = { Text("حذف پروفایل (Delete profile)", color = CoralError, fontWeight = FontWeight.SemiBold) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = CoralError) },
                            onClick = {
                                menuExpanded = false
                                onRequestDelete()
                            },
                            modifier = Modifier.testTag("delete_profile_${profile.id}")
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
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var rawText by remember { mutableStateOf("") }
    var groupName by remember { mutableStateOf(defaultGroup) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ProfileRowSurface,
        title = { Text(stringResource(R.string.import_paste_links), color = ProfilePrimaryText) },
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
                    label = { Text("https:// (Sub), vless://, vmess://, trojan://, ss://, hy2://, Base64, JSON") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .testTag("import_uri_text_field")
                )
                Spacer(modifier = Modifier.height(6.dp))
                TextButton(
                    onClick = {
                        val sysClipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        val clip = sysClipboard?.primaryClip?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)?.coerceToText(context)?.toString()
                            ?: clipboardManager.getText()?.text.orEmpty()
                        if (clip.isNotBlank()) {
                            rawText = clip
                        }
                    },
                    modifier = Modifier.testTag("paste_into_import_dialog_button")
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("چسباندن از کلیپ‌بورد (Paste)")
                }
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
            .background(ProfileScreenBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = if (initialProfile == null) stringResource(R.string.editor_title_new)
            else stringResource(R.string.editor_title_edit),
            style = MaterialTheme.typography.headlineMedium,
            color = ProfilePrimaryText,
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

        Text(stringResource(R.string.field_protocol), style = MaterialTheme.typography.labelLarge, color = ProfilePrimaryText)
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
        Text(stringResource(R.string.field_network), style = MaterialTheme.typography.labelLarge, color = ProfilePrimaryText)
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
        Text(stringResource(R.string.field_security), style = MaterialTheme.typography.labelLarge, color = ProfilePrimaryText)
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
                color = ProfilePrimaryText,
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
                                isPinned = initialProfile?.isPinned ?: false,
                                sortOrder = initialProfile?.sortOrder ?: 0,
                                lastLatencyMs = initialProfile?.lastLatencyMs,
                                latencyType = initialProfile?.latencyType ?: LatencyMeasureType.UNTESTED,
                                createdAt = initialProfile?.createdAt ?: System.currentTimeMillis()
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
        modifier = Modifier.testTag("qr_share_modal_dialog"),
        containerColor = ProfileRowSurface,
        title = { Text(profile.remarks, fontWeight = FontWeight.Bold, color = ProfilePrimaryText) },
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
                            .background(Color.White)
                            .padding(8.dp)
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "${profile.protocol.displayName} • ${profile.address}:${profile.port}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ProfileSecondaryText
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dismiss_qr_modal_button")
            ) {
                Text(stringResource(R.string.confirm), color = ProfileAccentCyan)
            }
        }
    )
}
