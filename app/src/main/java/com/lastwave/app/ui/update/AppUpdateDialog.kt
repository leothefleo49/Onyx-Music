package com.lastwave.app.ui.update

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.update.AppUpdateManager
import com.lastwave.app.data.update.UpdateInfo
import com.lastwave.app.ui.common.OnyxBrandLogo
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

@HiltViewModel
class AppUpdateDialogViewModel @Inject constructor(
    val manager: AppUpdateManager,
) : ViewModel() {
    val info = manager.updateInfo
}

/**
 * In-app update dialog hosted once at the app root. Handles one-tap download,
 * staged silent updates, and foreground OS confirmation prompts.
 */
@Composable
fun AppUpdateDialogHost(viewModel: AppUpdateDialogViewModel = hiltViewModel()) {
    val info by viewModel.info.collectAsStateWithLifecycle()
    val shouldShow = info.showDialog && (info.isUpdateAvailable || info.hasPendingConfirmation || info.isStagedReady)
    if (!shouldShow) return
    val context = LocalContext.current
    val manager = viewModel.manager
    AppUpdateDialog(
        info = info,
        onPrimaryAction = {
            when {
                info.hasPendingConfirmation -> manager.launchPendingConfirmation(context)
                info.isStagedReady -> manager.applyStagedUpdateNow(context)
                else -> manager.downloadAndInstall(context, silentBackground = false)
            }
        },
        onToggleAutoInstall = { enabled ->
            manager.setAutoInstallUpdates(enabled)
        },
        onOpenGitHub = { manager.openReleasePage(context) },
        onClose = { skipThisVersion ->
            if (skipThisVersion && info.latestVersion.isNotBlank()) {
                manager.dismissUpdate(info.latestVersion)
            }
            manager.hideDialog()
        },
    )
}

@Composable
private fun AppUpdateDialog(
    info: UpdateInfo,
    onPrimaryAction: () -> Unit,
    onToggleAutoInstall: (Boolean) -> Unit,
    onOpenGitHub: () -> Unit,
    onClose: (skipThisVersion: Boolean) -> Unit,
) {
    var skipThisVersion by rememberSaveable { mutableStateOf(false) }
    Dialog(
        onDismissRequest = { onClose(skipThisVersion) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .widthIn(max = 560.dp),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OnyxBrandLogo(size = 42.dp, shape = RoundedCornerShape(12.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = when {
                                info.hasPendingConfirmation -> "Confirm one-time update"
                                info.isStagedReady -> "Update ready to apply"
                                else -> "Update arrived"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            buildString {
                                if (info.latestVersion.isNotBlank()) append("v${info.latestVersion}")
                                if (info.currentVersion.isNotBlank()) {
                                    if (isNotEmpty()) append("  •  ")
                                    append("you have v${info.currentVersion}")
                                }
                                formatReleaseDate(info.publishedAt)?.let { append("  •  $it") }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val statusText = when {
                        info.isSelfOwnedInstaller && info.canInstallUnknownApps ->
                            "Zero-tap silent auto-updates active"
                        !info.canInstallUnknownApps ->
                            "Allow 'Install unknown apps' once to enable silent auto-updates"
                        else ->
                            "Confirm this update once so all future updates install silently"
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (info.isSelfOwnedInstaller && info.canInstallUnknownApps) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (info.isSelfOwnedInstaller && info.canInstallUnknownApps) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }

                if (info.releaseTitle.isNotBlank() && !info.releaseTitle.contains(info.latestVersion)) {
                    Text(info.releaseTitle, style = MaterialTheme.typography.titleSmall)
                }

                if (info.isUpdateAvailable) {
                    val linkColor = MaterialTheme.colorScheme.primary
                    val notes = remember(info.releaseNotes, linkColor) {
                        releaseNotesText(
                            info.releaseNotes.ifBlank { "No release notes were published for this version." },
                            linkColor,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            notes,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .heightIn(max = 300.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                        )
                    }
                }

                info.downloadFileName?.let { name ->
                    Text(
                        if (info.downloadSizeBytes > 0) "$name  •  ${formatBytes(info.downloadSizeBytes)}" else name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = info.autoInstallUpdates,
                        onCheckedChange = onToggleAutoInstall,
                    )
                    Text(
                        "Auto-install future updates silently in background (Android 12+)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (info.isUpdateAvailable && !info.hasPendingConfirmation) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = skipThisVersion, onCheckedChange = { skipThisVersion = it })
                        Text("Don't show again for this version", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                if (info.isInstalling) {
                    LinearProgressIndicator(
                        progress = { info.installProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        info.installMessage ?: "Downloading update…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (info.installMessage != null) {
                    val isError = info.installMessage.contains("failed", ignoreCase = true) ||
                        info.installMessage.startsWith("Allow ", ignoreCase = true)
                    Text(
                        info.installMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (info.isUpdateAvailable) {
                        TextButton(onClick = onOpenGitHub) { Text("GitHub") }
                    }
                    TextButton(onClick = { onClose(skipThisVersion) }) { Text("Later") }
                    val primaryLabel = when {
                        info.isInstalling -> "Updating…"
                        info.hasPendingConfirmation -> "Confirm"
                        info.isStagedReady -> "Apply now"
                        else -> "Update now"
                    }
                    Button(onClick = onPrimaryAction, enabled = !info.isInstalling) {
                        Text(primaryLabel)
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatReleaseDate(isoInstant: String): String? = runCatching {
    Instant.parse(isoInstant)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}.getOrNull()
