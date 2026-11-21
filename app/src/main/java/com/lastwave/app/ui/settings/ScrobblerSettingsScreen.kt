package com.lastwave.app.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lastwave.app.R
import com.lastwave.app.data.local.ScrobblerPreferences
import com.lastwave.app.data.local.ScrobblerSettings
import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.scrobble.ListenBrainzApi
import com.lastwave.app.data.scrobble.OnyxNotificationScrobblerService
import com.lastwave.app.data.scrobble.ScrobbleAppFilterDao
import com.lastwave.app.data.scrobble.ScrobbleAppFilterEntity
import com.lastwave.app.data.scrobble.ScrobbleEditRuleDao
import com.lastwave.app.data.scrobble.ScrobbleEditRuleEntity
import com.lastwave.app.data.scrobble.ScrobbleRulesEngine
import com.lastwave.app.data.scrobble.ScrobbleSyncEngine
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.shell.FloatingNavDefaults
import com.lastwave.app.util.FileExportHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

@Immutable
data class ExternalAppToggleItem(
    val packageName: String,
    val appName: String,
    val isInstalled: Boolean,
    val isAllowed: Boolean,
)

@Immutable
data class ScrobblerSettingsUiState(
    val settings: ScrobblerSettings = ScrobblerSettings(),
    val notificationAccessGranted: Boolean = false,
    val serviceConnected: Boolean = false,
    val lastFmUsername: String = "",
    val hasLastFmSessionKey: Boolean = false,
    val totalLocalScrobbles: Int = 0,
    val pendingSyncCount: Int = 0,
    val rules: List<ScrobbleEditRuleEntity> = emptyList(),
    val externalApps: List<ExternalAppToggleItem> = emptyList(),
    val isBusy: Boolean = false,
    val statusMessage: String? = null,
)

@HiltViewModel
class ScrobblerSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scrobblerPreferences: ScrobblerPreferences,
    private val sessionPreferences: SessionPreferences,
    private val syncEngine: ScrobbleSyncEngine,
    private val editRuleDao: ScrobbleEditRuleDao,
    private val appFilterDao: ScrobbleAppFilterDao,
    private val listenBrainzApi: ListenBrainzApi,
    private val fileExportHelper: FileExportHelper,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScrobblerSettingsUiState())
    val uiState: StateFlow<ScrobblerSettingsUiState> = _uiState.asStateFlow()

    init {
        refreshPermissionStatus()
        viewModelScope.launch {
            combine(
                scrobblerPreferences.settings,
                sessionPreferences.session,
                syncEngine.totalScrobbleCount,
                syncEngine.pendingSyncCount,
                editRuleDao.observeAll(),
            ) { settings, session, total, pending, rules ->
                _uiState.value.copy(
                    settings = settings,
                    lastFmUsername = session.username,
                    hasLastFmSessionKey = session.sessionKey.isNotBlank(),
                    totalLocalScrobbles = total,
                    pendingSyncCount = pending,
                    rules = rules,
                )
            }.collect { updated ->
                _uiState.value = updated
                rebuildAppList(updated.settings, appFilterDao.getAll())
            }
        }
        viewModelScope.launch {
            appFilterDao.observeAll().collect { filters ->
                rebuildAppList(_uiState.value.settings, filters)
            }
        }
    }

    fun refreshPermissionStatus() {
        val granted = OnyxNotificationScrobblerService.isNotificationAccessGranted(context)
        _uiState.update {
            it.copy(
                notificationAccessGranted = granted,
                serviceConnected = OnyxNotificationScrobblerService.isConnectedFlag,
            )
        }
    }

    private fun rebuildAppList(
        settings: ScrobblerSettings,
        filters: List<ScrobbleAppFilterEntity>,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val filterMap = filters.associateBy { it.packageName }
            val pm = context.packageManager
            val merged = LinkedHashMap<String, ExternalAppToggleItem>()

            for ((pkg, defaultLabel) in ScrobbleRulesEngine.KNOWN_MUSIC_PLAYERS) {
                if (pkg == context.packageName) continue
                val appInfo = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getApplicationInfo(pkg, 0)
                    }
                }.getOrNull()
                val installed = appInfo != null
                val label = appInfo?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() }
                    ?.takeIf { it.isNotBlank() } ?: defaultLabel
                val explicit = filterMap[pkg]
                val allowed = when {
                    pkg in settings.blockedPackages -> false
                    explicit != null -> explicit.isAllowed
                    pkg in settings.selectedPackages -> true
                    else -> settings.autoScrobbleKnownPlayers
                }
                merged[pkg] = ExternalAppToggleItem(
                    packageName = pkg,
                    appName = label,
                    isInstalled = installed,
                    isAllowed = allowed,
                )
            }

            // Include any custom saved filter packages not in the built-in known map
            for (custom in filters) {
                if (custom.packageName == context.packageName || custom.packageName in merged) continue
                merged[custom.packageName] = ExternalAppToggleItem(
                    packageName = custom.packageName,
                    appName = custom.appName.ifBlank { custom.packageName },
                    isInstalled = true,
                    isAllowed = custom.isAllowed && custom.packageName !in settings.blockedPackages,
                )
            }

            val sorted = merged.values.sortedWith(
                compareByDescending<ExternalAppToggleItem> { it.isInstalled }
                    .thenBy { it.appName.lowercase() },
            )
            _uiState.update { it.copy(externalApps = sorted) }
        }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { scrobblerPreferences.setEnabled(enabled) }
    }

    fun setSubmitNowPlaying(enabled: Boolean) {
        viewModelScope.launch { scrobblerPreferences.setSubmitNowPlaying(enabled) }
    }

    fun setScrobblePercent(percent: Int) {
        viewModelScope.launch { scrobblerPreferences.setScrobblePercent(percent) }
    }

    fun setAutoScrobbleKnownPlayers(enabled: Boolean) {
        viewModelScope.launch { scrobblerPreferences.setAutoScrobbleKnownPlayers(enabled) }
    }

    fun setTwoWayLoveSync(enabled: Boolean) {
        viewModelScope.launch { scrobblerPreferences.setTwoWayLoveSync(enabled) }
    }

    fun setExternalAppAllowed(item: ExternalAppToggleItem, allowed: Boolean) {
        viewModelScope.launch {
            syncEngine.setAppAllowed(item.packageName, item.appName, allowed)
        }
    }

    fun saveRule(
        matchArtist: String,
        matchTitle: String,
        matchAlbum: String,
        replaceArtist: String,
        replaceTitle: String,
        replaceAlbum: String,
        isRegex: Boolean,
        applyRetroactively: Boolean,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val rule = ScrobbleEditRuleEntity(
                matchArtist = matchArtist.trim(),
                matchTitle = matchTitle.trim(),
                matchAlbum = matchAlbum.trim(),
                replaceArtist = replaceArtist.trim(),
                replaceTitle = replaceTitle.trim(),
                replaceAlbum = replaceAlbum.trim(),
                isRegex = isRegex,
                enabled = true,
            )
            val updated = syncEngine.saveEditRule(rule, applyRetroactively = applyRetroactively)
            val msg = if (applyRetroactively && updated > 0) {
                "Rule saved and applied to $updated past scrobbles"
            } else {
                "Auto-fix rule saved"
            }
            _uiState.update { it.copy(statusMessage = msg) }
        }
    }

    fun toggleRuleEnabled(rule: ScrobbleEditRuleEntity, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            syncEngine.saveEditRule(rule.copy(enabled = enabled), applyRetroactively = false)
        }
    }

    fun deleteRule(ruleId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            syncEngine.deleteEditRule(ruleId)
            _uiState.update { it.copy(statusMessage = "Rule removed") }
        }
    }

    fun setListenBrainzEnabled(enabled: Boolean) {
        viewModelScope.launch { scrobblerPreferences.setListenBrainzEnabled(enabled) }
    }

    fun validateAndSaveListenBrainzToken(token: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isBusy = true) }
            val result = listenBrainzApi.validateToken(token)
            if (result.valid) {
                scrobblerPreferences.setListenBrainzConfig(
                    enabled = true,
                    token = token.trim(),
                    username = result.username.orEmpty(),
                )
                val userLabel = result.username?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        statusMessage = "ListenBrainz connected$userLabel",
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        statusMessage = "ListenBrainz token invalid: ${result.message.ifBlank { "check token" }}",
                    )
                }
            }
        }
    }

    fun flushQueueNow() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isBusy = true) }
            val report = syncEngine.flushPendingQueue()
            val syncedTotal = report.lastFmSynced + report.listenBrainzSynced
            val msg = when {
                syncedTotal > 0 -> "Synced $syncedTotal queued scrobbles (${report.remainingPending} remaining)"
                report.remainingPending == 0 -> "All scrobbles are synced"
                else -> "${report.remainingPending} scrobbles still waiting for network or session"
            }
            _uiState.update { it.copy(isBusy = false, statusMessage = msg) }
        }
    }

    fun pullLovedTracksNow() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isBusy = true) }
            val result = syncEngine.pullLovedTracksFromLastFm()
            val msg = result.fold(
                onSuccess = { count -> "Synced $count loved tracks from Last.fm" },
                onFailure = { err -> err.message ?: "Could not fetch loved tracks" },
            )
            _uiState.update { it.copy(isBusy = false, statusMessage = msg) }
        }
    }

    fun exportJsonToUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isBusy = true) }
            val outcome = runCatching {
                val payload = syncEngine.exportJson()
                fileExportHelper.writeTextToUri(uri, payload)
            }
            _uiState.update {
                it.copy(
                    isBusy = false,
                    statusMessage = outcome.fold(
                        onSuccess = { bytes -> "Exported JSON (${bytes / 1024} KB)" },
                        onFailure = { err -> "Export failed: ${err.message}" },
                    ),
                )
            }
        }
    }

    fun exportCsvToUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isBusy = true) }
            val outcome = runCatching {
                val payload = syncEngine.exportCsv()
                fileExportHelper.writeTextToUri(uri, payload)
            }
            _uiState.update {
                it.copy(
                    isBusy = false,
                    statusMessage = outcome.fold(
                        onSuccess = { bytes -> "Exported CSV (${bytes / 1024} KB)" },
                        onFailure = { err -> "Export failed: ${err.message}" },
                    ),
                )
            }
        }
    }

    fun importFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isBusy = true) }
            val raw = fileExportHelper.readTextFromUri(uri)
            if (raw.isNullOrBlank()) {
                _uiState.update {
                    it.copy(isBusy = false, statusMessage = "Could not read selected file")
                }
                return@launch
            }
            val result = runCatching { syncEngine.importScrobbles(raw) }
            _uiState.update {
                it.copy(
                    isBusy = false,
                    statusMessage = result.fold(
                        onSuccess = { res ->
                            "Imported ${res.events.size} plays (${res.formatDetected}, ${res.skippedCount} skipped)"
                        },
                        onFailure = { err -> "Import failed: ${err.message}" },
                    ),
                )
            }
        }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScrobblerSettingsScreen(
    onBack: () -> Unit,
    viewModel: ScrobblerSettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissionStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var showAddRuleDialog by remember { mutableStateOf(false) }
    var lbTokenInput by remember(uiState.settings.listenBrainzToken) {
        mutableStateOf(uiState.settings.listenBrainzToken)
    }
    var showLbToken by remember { mutableStateOf(false) }
    var sliderPercent by remember(uiState.settings.scrobblePercent) {
        mutableFloatStateOf(uiState.settings.scrobblePercent.toFloat())
    }

    val exportJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::exportJsonToUri) }

    val exportCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> uri?.let(viewModel::exportCsvToUri) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importFromUri) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .adaptiveContentWidth(maxWidth = 860.dp)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.scrobbler_settings_title),
                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = 22.sp),
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.weight(1f),
                    )
                    if (uiState.isBusy) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(20.dp),
                        )
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .adaptiveContentWidth(maxWidth = 860.dp),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = FloatingNavDefaults.contentBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            uiState.statusMessage?.let { message ->
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        onClick = viewModel::clearStatusMessage,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            // 1. Notification Listener Permission Status Card
            item {
                val granted = uiState.notificationAccessGranted
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = if (granted) {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    } else {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (granted) Icons.Filled.NotificationsActive else Icons.Filled.NotificationsOff,
                                contentDescription = null,
                                tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (granted) {
                                        stringResource(R.string.scrobbler_permission_granted)
                                    } else {
                                        stringResource(R.string.scrobbler_permission_missing)
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = if (granted && uiState.serviceConnected) {
                                        "System listener active for external apps"
                                    } else {
                                        stringResource(R.string.scrobbler_permission_subtitle)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        FilledTonalButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        OnyxNotificationScrobblerService.notificationListenerSettingsIntent(),
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.scrobbler_open_android_settings))
                        }
                    }
                }
            }

            // 2. Core Scrobbler Behavior & Threshold Slider
            item {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "System-wide Scrobbling",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        ToggleSettingRow(
                            title = "Scrobble external apps",
                            subtitle = "Track Spotify, Apple Music, YouTube Music, SoundCloud, and more",
                            checked = uiState.settings.enabled,
                            onCheckedChange = viewModel::setEnabled,
                        )
                        ToggleSettingRow(
                            title = "Submit Now Playing",
                            subtitle = "Send live now-playing status as soon as playback starts",
                            checked = uiState.settings.submitNowPlaying,
                            onCheckedChange = viewModel::setSubmitNowPlaying,
                        )
                        ToggleSettingRow(
                            title = "Auto-allow known music players",
                            subtitle = "Automatically watch popular players unless explicitly blocked",
                            checked = uiState.settings.autoScrobbleKnownPlayers,
                            onCheckedChange = viewModel::setAutoScrobbleKnownPlayers,
                        )
                        ToggleSettingRow(
                            title = "Two-way Last.fm Love sync",
                            subtitle = "Sync heart/unheart state with Last.fm loved tracks",
                            checked = uiState.settings.twoWayLoveSync,
                            onCheckedChange = viewModel::setTwoWayLoveSync,
                        )

                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Scrobble threshold",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "${sliderPercent.roundToInt()}% (max 4m, min 30s)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Slider(
                            value = sliderPercent,
                            onValueChange = { sliderPercent = it },
                            onValueChangeFinished = {
                                viewModel.setScrobblePercent(sliderPercent.roundToInt())
                            },
                            valueRange = 25f..90f,
                            steps = 12,
                        )
                    }
                }
            }

            // 3. Last.fm & ListenBrainz Sync Status
            item {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "Sync Services",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = buildString {
                                append("Last.fm: ")
                                if (uiState.lastFmUsername.isNotBlank()) {
                                    append(uiState.lastFmUsername)
                                    append(if (uiState.hasLastFmSessionKey) " (session active)" else " (read-only)")
                                } else {
                                    append("Not connected")
                                }
                                append(" \u00b7 ")
                                append("${uiState.pendingSyncCount} queued")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            FilledTonalButton(
                                onClick = viewModel::flushQueueNow,
                                modifier = Modifier.weight(1f),
                                enabled = !uiState.isBusy,
                            ) {
                                Icon(Icons.Filled.CloudSync, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.scrobbler_sync_now))
                            }
                            OutlinedButton(
                                onClick = viewModel::pullLovedTracksNow,
                                modifier = Modifier.weight(1f),
                                enabled = !uiState.isBusy && uiState.lastFmUsername.isNotBlank(),
                            ) {
                                Icon(Icons.Filled.Favorite, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.scrobbler_pull_loved))
                            }
                        }

                        Spacer(Modifier.height(4.dp))

                        ToggleSettingRow(
                            title = "ListenBrainz scrobbling",
                            subtitle = if (uiState.settings.listenBrainzUsername.isNotBlank()) {
                                "Connected as ${uiState.settings.listenBrainzUsername}"
                            } else {
                                "Submit playing_now and listens with your ListenBrainz user token"
                            },
                            checked = uiState.settings.listenBrainzEnabled,
                            onCheckedChange = viewModel::setListenBrainzEnabled,
                        )

                        OutlinedTextField(
                            value = lbTokenInput,
                            onValueChange = { lbTokenInput = it },
                            label = { Text("ListenBrainz user token") },
                            singleLine = true,
                            visualTransformation = if (showLbToken) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { showLbToken = !showLbToken }) {
                                    Icon(
                                        imageVector = if (showLbToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = "Toggle token visibility",
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { viewModel.validateAndSaveListenBrainzToken(lbTokenInput) },
                            enabled = !uiState.isBusy && lbTokenInput.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Validate & Save ListenBrainz Token")
                        }
                    }
                }
            }

            // 4. External Music App Filters
            item {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "External Music Apps",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Onyx Music playback is tracked internally. Toggle external players below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        uiState.externalApps.forEach { app ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = app.appName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = if (app.isInstalled) {
                                            "Installed \u00b7 ${app.packageName}"
                                        } else {
                                            app.packageName
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Switch(
                                    checked = app.isAllowed,
                                    onCheckedChange = { allowed ->
                                        viewModel.setExternalAppAllowed(app, allowed)
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // 5. Pano Auto-Edit Rules Manager
            item {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Pano Auto-Edit Rules",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "Automatically rewrite artist, track, or album tags with exact or regex rules",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { showAddRuleDialog = true }) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = stringResource(R.string.scrobbler_add_rule),
                                )
                            }
                        }

                        if (uiState.rules.isEmpty()) {
                            Text(
                                text = "No auto-edit rules yet. Tap + or edit any track from Scrobble History.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            uiState.rules.forEach { rule ->
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            val matchSummary = listOfNotNull(
                                                rule.matchArtist.takeIf { it.isNotBlank() }?.let { "Artist: $it" },
                                                rule.matchTitle.takeIf { it.isNotBlank() }?.let { "Title: $it" },
                                                rule.matchAlbum.takeIf { it.isNotBlank() }?.let { "Album: $it" },
                                            ).joinToString(" | ")
                                            val replaceSummary = listOfNotNull(
                                                rule.replaceArtist.takeIf { it.isNotBlank() }?.let { "Artist -> $it" },
                                                rule.replaceTitle.takeIf { it.isNotBlank() }?.let { "Title -> $it" },
                                                rule.replaceAlbum.takeIf { it.isNotBlank() }?.let { "Album -> $it" },
                                            ).joinToString(" | ")
                                            Text(
                                                text = if (rule.isRegex) "[Regex] $matchSummary" else matchSummary,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (replaceSummary.isNotBlank()) {
                                                Text(
                                                    text = replaceSummary,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                        Switch(
                                            checked = rule.enabled,
                                            onCheckedChange = { viewModel.toggleRuleEnabled(rule, it) },
                                        )
                                        IconButton(onClick = { viewModel.deleteRule(rule.id) }) {
                                            Icon(
                                                imageVector = Icons.Filled.Delete,
                                                contentDescription = "Delete rule",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 6. Import & Export History
            item {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "Import & Export History",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "${uiState.totalLocalScrobbles} local scrobbles stored. Supports Onyx JSON, CSV, Last.fm JSON, and Spotify Extended Streaming History JSON.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                onClick = { exportJsonLauncher.launch("onyx-scrobbles.json") },
                                modifier = Modifier.weight(1f),
                                enabled = !uiState.isBusy,
                            ) {
                                Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.scrobbler_export_json))
                            }
                            OutlinedButton(
                                onClick = { exportCsvLauncher.launch("onyx-scrobbles.csv") },
                                modifier = Modifier.weight(1f),
                                enabled = !uiState.isBusy,
                            ) {
                                Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.scrobbler_export_csv))
                            }
                        }
                        FilledTonalButton(
                            onClick = {
                                importLauncher.launch(
                                    arrayOf("application/json", "text/csv", "text/plain", "*/*"),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !uiState.isBusy,
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.scrobbler_import_history))
                        }
                    }
                }
            }
        }
    }

    if (showAddRuleDialog) {
        AddEditRuleDialog(
            onDismiss = { showAddRuleDialog = false },
            onSave = { ma, mt, malb, ra, rt, ralb, regex, retro ->
                viewModel.saveRule(ma, mt, malb, ra, rt, ralb, regex, retro)
                showAddRuleDialog = false
            },
        )
    }
}

@Composable
private fun ToggleSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AddEditRuleDialog(
    onDismiss: () -> Unit,
    onSave: (
        matchArtist: String,
        matchTitle: String,
        matchAlbum: String,
        replaceArtist: String,
        replaceTitle: String,
        replaceAlbum: String,
        isRegex: Boolean,
        applyRetroactively: Boolean,
    ) -> Unit,
) {
    var matchArtist by remember { mutableStateOf("") }
    var matchTitle by remember { mutableStateOf("") }
    var matchAlbum by remember { mutableStateOf("") }
    var replaceArtist by remember { mutableStateOf("") }
    var replaceTitle by remember { mutableStateOf("") }
    var replaceAlbum by remember { mutableStateOf("") }
    var isRegex by remember { mutableStateOf(false) }
    var applyRetro by remember { mutableStateOf(true) }

    val canSave = matchArtist.isNotBlank() || matchTitle.isNotBlank() || matchAlbum.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scrobbler_add_rule)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = matchArtist,
                    onValueChange = { matchArtist = it },
                    label = { Text("Match artist") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = replaceArtist,
                    onValueChange = { replaceArtist = it },
                    label = { Text("Replace artist with") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = matchTitle,
                    onValueChange = { matchTitle = it },
                    label = { Text("Match title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = replaceTitle,
                    onValueChange = { replaceTitle = it },
                    label = { Text("Replace title with") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = matchAlbum,
                    onValueChange = { matchAlbum = it },
                    label = { Text("Match album") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = replaceAlbum,
                    onValueChange = { replaceAlbum = it },
                    label = { Text("Replace album with") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isRegex, onCheckedChange = { isRegex = it })
                    Text("Use regular expressions (Regex)", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = applyRetro, onCheckedChange = { applyRetro = it })
                    Text(
                        stringResource(R.string.scrobbler_apply_retroactive),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        matchArtist,
                        matchTitle,
                        matchAlbum,
                        replaceArtist,
                        replaceTitle,
                        replaceAlbum,
                        isRegex,
                        applyRetro,
                    )
                },
                enabled = canSave,
            ) {
                Text("Save Rule")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
