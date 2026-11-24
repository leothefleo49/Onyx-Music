package com.lastwave.app.data.update

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.net.Uri
import android.os.Build
import com.lastwave.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateInfo(
    val isChecking: Boolean = false,
    val isUpdateAvailable: Boolean = false,
    val latestVersion: String = "",
    val currentVersion: String = "",
    val releaseNotes: String = "",
    val releaseUrl: String = "",
    val downloadUrl: String? = null,
    val downloadFileName: String? = null,
    val isDismissed: Boolean = false,
    val message: String? = null,
    /** Release title and publish date, shown in the update dialog. */
    val releaseTitle: String = "",
    val publishedAt: String = "",
    val downloadSizeBytes: Long = 0L,
    /** The in-app update dialog is open (on launch, or from Settings / the banner). */
    val showDialog: Boolean = false,
    /** PackageInstaller download/commit state; -1 = idle. */
    val installProgress: Float = -1f,
    val isInstalling: Boolean = false,
    val installMessage: String? = null,
    val autoInstallUpdates: Boolean = false,
)

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long = 0L,
)

enum class ApkVariant {
    UNCOMPRESSED,
    ANDROID7,
    UNIVERSAL,
    /** vivo Origin Island build (separate package); never mixed with the others. */
    VIVO_ORIGIN,
}

@Singleton
class AppUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val themeRepository: dagger.Lazy<com.lastwave.app.data.repository.ThemeRepository>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("onyx_updates", Context.MODE_PRIVATE)

    private val _updateInfo = MutableStateFlow(
        UpdateInfo(
            currentVersion = getCurrentVersion(),
            autoInstallUpdates = prefs.getBoolean(KEY_AUTO_INSTALL, true),
        )
    )
    val updateInfo: StateFlow<UpdateInfo> = _updateInfo.asStateFlow()

    init {
        activeInstance = this
        checkForUpdate(isSilent = true)
    }

    fun isAutoInstallEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_INSTALL, true)

    fun setAutoInstallUpdates(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_INSTALL, enabled).apply()
        _updateInfo.update { it.copy(autoInstallUpdates = enabled) }
    }

    fun isUncompressedBuild(): Boolean {
        val pref = prefs.getString("preferred_variant", null)
        if (pref != null) {
            return pref.equals("uncompressed", ignoreCase = true) || pref.equals("raw", ignoreCase = true)
        }
        val buildType = runCatching { BuildConfig.BUILD_TYPE }.getOrDefault("")
        return buildType.equals("rawRelease", ignoreCase = true) ||
               buildType.contains("raw", ignoreCase = true) ||
               buildType.contains("uncompressed", ignoreCase = true)
    }

    fun getPreferredVariant(): String? = prefs.getString("preferred_variant", null)

    fun setPreferredVariant(variant: String?) {
        if (variant == null) {
            prefs.edit().remove("preferred_variant").apply()
        } else {
            prefs.edit().putString("preferred_variant", variant).apply()
        }
        checkForUpdate(isSilent = true)
    }

    fun getCurrentVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: BuildConfig.VERSION_NAME
    } catch (_: Exception) {
        BuildConfig.VERSION_NAME
    }

    fun checkForUpdate(isSilent: Boolean = false) {
        scope.launch {
            _updateInfo.update {
                it.copy(
                    isChecking = true,
                    message = if (!isSilent) "Checking for updates..." else it.message,
                )
            }
            try {
                val request = Request.Builder()
                    .url("https://api.github.com/repos/leothefleo49/Onyx-Music/releases/latest")
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "OnyxMusic-Android")
                    .build()

                val response = withContext(Dispatchers.IO) {
                    okHttpClient.newCall(request).execute()
                }

                if (!response.isSuccessful) {
                    val code = response.code
                    _updateInfo.update {
                        it.copy(
                            isChecking = false,
                            message = if (!isSilent) "Could not check updates (HTTP $code)" else null,
                        )
                    }
                    return@launch
                }

                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val tagName = json.optString("tag_name", "")
                val releaseUrl = json.optString("html_url", "https://github.com/leothefleo49/Onyx-Music/releases")
                val releaseNotes = json.optString("body", "")
                val releaseTitle = json.optString("name", "")
                val publishedAt = json.optString("published_at", "")

                val apkAssets = mutableListOf<ReleaseAsset>()
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name", "").trim()
                        val download = asset.optString("browser_download_url", "").trim()
                        if (name.endsWith(".apk", ignoreCase = true) && download.isNotBlank()) {
                            apkAssets.add(
                                ReleaseAsset(
                                    name = name,
                                    downloadUrl = download,
                                    sizeBytes = asset.optLong("size", 0L),
                                ),
                            )
                        }
                    }
                }

                val bestAsset = selectBestAsset(
                    assets = apkAssets,
                    isUncompressed = isUncompressedBuild(),
                    sdkInt = Build.VERSION.SDK_INT,
                    isVivoOrigin = BuildConfig.VIVO_ORIGIN,
                )
                val downloadUrl = bestAsset?.downloadUrl ?: releaseUrl
                val downloadFileName = bestAsset?.name

                val currentVersion = getCurrentVersion()
                val hasNewer = isNewerVersion(tagName, currentVersion)
                val cleanTag = tagName.removePrefix("v").removePrefix("V")
                val dismissedVersion = prefs.getString("dismissed_version", null)
                val isDismissed = dismissedVersion == cleanTag

                // When auto-install is enabled (default on Android 12+) and install permission is already
                // granted, begin background download & silent session commit automatically.
                val canAutoInstall = isSilent && hasNewer && !isDismissed && isAutoInstallEnabled() &&
                    !bestAsset?.downloadUrl.isNullOrBlank() &&
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls())
                val autoOpen = isSilent && hasNewer && !isDismissed && !autoDialogShown && !canAutoInstall
                if (autoOpen) autoDialogShown = true
                _updateInfo.update {
                    it.copy(
                        isChecking = false,
                        isUpdateAvailable = hasNewer,
                        latestVersion = cleanTag,
                        currentVersion = currentVersion,
                        releaseNotes = releaseNotes,
                        releaseTitle = releaseTitle,
                        publishedAt = publishedAt,
                        downloadSizeBytes = bestAsset?.sizeBytes ?: 0L,
                        showDialog = it.showDialog || autoOpen,
                        releaseUrl = releaseUrl,
                        downloadUrl = downloadUrl,
                        downloadFileName = downloadFileName,
                        isDismissed = isDismissed,
                        autoInstallUpdates = isAutoInstallEnabled(),
                        message = if (!isSilent) {
                            if (hasNewer) "New version $cleanTag available!" else "You're on the latest version ($currentVersion)"
                        } else null,
                    )
                }
                if (canAutoInstall && !autoDialogShown) {
                    autoDialogShown = true
                    downloadAndInstall(context, silentBackground = true)
                }
            } catch (e: Exception) {
                _updateInfo.update {
                    it.copy(
                        isChecking = false,
                        message = if (!isSilent) "Check failed: ${e.message ?: "Network error"}" else null,
                    )
                }
            }
        }
    }

    fun dismissUpdate(version: String) {
        val clean = version.removePrefix("v").removePrefix("V")
        prefs.edit().putString("dismissed_version", clean).apply()
        _updateInfo.update { it.copy(isDismissed = true) }
    }

    /** Settings' "Update Ready" and the banner open the in-app dialog, not the browser. */
    fun openUpdate(context: Context) = showDialog()

    fun showDialog() = _updateInfo.update { it.copy(showDialog = true) }

    fun hideDialog() = _updateInfo.update { it.copy(showDialog = false) }

    /** The release page, for the dialog's GitHub button. */
    fun openReleasePage(context: Context) {
        val targetUrl = _updateInfo.value.releaseUrl.ifBlank {
            "https://github.com/leothefleo49/Onyx-Music/releases"
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) { }
    }

    @Volatile private var autoDialogShown = false
    /**
     * Update now: opens this build's APK download in the browser, as before;
     * the listener installs it from there, so no install permission is needed.
     * A release without an APK for this build opens its release page instead.
     */
    fun openDownload(context: Context) {
        val targetUrl = _updateInfo.value.downloadUrl?.takeIf { it.isNotBlank() }
            ?: return openReleasePage(context)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) { }
    }

    /**
     * Downloads the release APK into a PackageInstaller session and commits it.
     * On Android 12+ (API 31+), requests `USER_ACTION_NOT_REQUIRED` (backed by
     * `UPDATE_PACKAGES_WITHOUT_USER_ACTION`) and on Android 14+ requests update
     * ownership so once Onyx Music is its own installer of record (after one
     * confirmed in-app update), subsequent updates apply silently without a system prompt.
     */
    fun downloadAndInstall(context: Context, silentBackground: Boolean = false) {
        val url = _updateInfo.value.downloadUrl?.takeIf { it.isNotBlank() }
            ?: return openReleasePage(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            _updateInfo.update {
                it.copy(
                    showDialog = true,
                    installMessage = "Allow Onyx Music to install unknown apps, then tap Update again.",
                )
            }
            if (!silentBackground) {
                runCatching {
                    context.startActivity(
                        Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            return
        }
        if (_updateInfo.value.isInstalling) return
        scope.launch {
            _updateInfo.update {
                it.copy(isInstalling = true, installProgress = 0f, installMessage = "Downloading update…")
            }
            var session: android.content.pm.PackageInstaller.Session? = null
            try {
                val request = Request.Builder().url(url).header("User-Agent", "OnyxMusic-Android").build()
                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body ?: error("Empty update file")
                val total = body.contentLength()

                val installer = context.packageManager.packageInstaller
                val params = android.content.pm.PackageInstaller.SessionParams(
                    android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL,
                ).apply {
                    setAppPackageName(context.packageName)
                    setAppLabel("Onyx Music")
                    runCatching {
                        val tState = themeRepository.get().uiState.value
                        val p = tState.colorScheme.primary
                        val op = tState.colorScheme.onPrimary
                        val pArgb = android.graphics.Color.argb(
                            (p.alpha * 255f).toInt(), (p.red * 255f).toInt(), (p.green * 255f).toInt(), (p.blue * 255f).toInt(),
                        )
                        val opArgb = android.graphics.Color.argb(
                            (op.alpha * 255f).toInt(), (op.red * 255f).toInt(), (op.green * 255f).toInt(), (op.blue * 255f).toInt(),
                        )
                        setAppIcon(
                            com.lastwave.app.ui.common.renderOnyxLogoBitmap(
                                context = context,
                                appIcon = tState.appIcon,
                                primaryArgb = pArgb,
                                onPrimaryArgb = opArgb,
                                sizePx = 192,
                            ),
                        )
                    }
                    if (total > 0L) setSize(total)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setRequireUserAction(
                            android.content.pm.PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED,
                        )
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        setRequestUpdateOwnership(true)
                    }
                }
                val sessionId = installer.createSession(params)
                session = installer.openSession(sessionId)
                body.byteStream().use { input ->
                    session.openWrite("onyx-update.apk", 0L, if (total > 0L) total else -1L).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0L) {
                                _updateInfo.update { it.copy(installProgress = (copied.toFloat() / total).coerceIn(0f, 0.98f)) }
                            }
                        }
                        output.flush()
                        runCatching { session.fsync(output) }
                    }
                }
                val callback = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(context, UpdateInstallReceiver::class.java)
                        .setAction(UpdateInstallReceiver.ACTION_INSTALL_RESULT)
                        .setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                _updateInfo.update {
                    it.copy(installProgress = 1f, installMessage = "Applying update…")
                }
                session.commit(callback.intentSender)
                _updateInfo.update {
                    it.copy(isInstalling = false, installMessage = "Installing update…")
                }
            } catch (error: Exception) {
                runCatching { session?.abandon() }
                _updateInfo.update {
                    it.copy(
                        isInstalling = false,
                        installProgress = -1f,
                        showDialog = true,
                        installMessage = "Update download failed: ${error.message ?: "unknown error"}",
                    )
                }
            } finally {
                runCatching { session?.close() }
            }
        }
    }

    internal fun onInstallerStatus(status: Int, statusText: String) {
        _updateInfo.update {
            it.copy(
                isInstalling = false,
                installProgress = if (status == android.content.pm.PackageInstaller.STATUS_SUCCESS) 1f else -1f,
                installMessage = statusText,
            )
        }
    }

    fun isNewerVersion(remote: String, local: String): Boolean {
        if (remote.isBlank() || local.isBlank()) return false
        val cleanRemote = remote.removePrefix("v").removePrefix("V").substringBefore("-")
        val cleanLocal = local.removePrefix("v").removePrefix("V").substringBefore("-")
        if (cleanRemote == cleanLocal) return false

        val rParts = cleanRemote.split(".").mapNotNull { it.toIntOrNull() }
        val cParts = cleanLocal.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(rParts.size, cParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    companion object {
        private const val KEY_AUTO_INSTALL = "auto_install_updates"
        @Volatile internal var activeInstance: AppUpdateManager? = null

        fun classifyAsset(name: String): ApkVariant {
            val lower = name.lowercase()
            return when {
                lower.contains("vivo") -> ApkVariant.VIVO_ORIGIN
                lower.contains("uncompressed") || lower.contains("-raw") || lower.contains("_raw") -> ApkVariant.UNCOMPRESSED
                lower.contains("android7") || lower.contains("legacy") -> ApkVariant.ANDROID7
                else -> ApkVariant.UNIVERSAL
            }
        }

        fun selectBestAsset(
            assets: List<ReleaseAsset>,
            isUncompressed: Boolean,
            sdkInt: Int,
            isVivoOrigin: Boolean = false,
        ): ReleaseAsset? {
            // The vivo build is a different package: installing the other APK
            // would add a second app instead of updating, so each build only
            // ever picks from its own pool.
            val apkAssets = assets.filter {
                it.name.endsWith(".apk", ignoreCase = true) && it.downloadUrl.isNotBlank() &&
                    (classifyAsset(it.name) == ApkVariant.VIVO_ORIGIN) == isVivoOrigin
            }
            if (apkAssets.isEmpty()) return null
            if (isVivoOrigin) return apkAssets.first()

            val target = when {
                sdkInt < 29 -> ApkVariant.ANDROID7
                isUncompressed -> ApkVariant.UNCOMPRESSED
                else -> ApkVariant.UNIVERSAL
            }

            // Direct match for target variant, preferring "universal" naming if present
            val matches = apkAssets.filter { classifyAsset(it.name) == target }
            if (matches.isNotEmpty()) {
                return matches.maxByOrNull { asset ->
                    val lower = asset.name.lowercase()
                    when (target) {
                        ApkVariant.UNIVERSAL -> if (lower.contains("universal")) 2 else 1
                        ApkVariant.UNCOMPRESSED -> if (lower.contains("universal")) 2 else 1
                        ApkVariant.ANDROID7 -> if (lower.contains("android7")) 2 else 1
                        ApkVariant.VIVO_ORIGIN -> 1
                    }
                }
            }

            // Fallback hierarchy if target variant was not present in this release:
            return when (target) {
                ApkVariant.UNCOMPRESSED -> {
                    apkAssets.firstOrNull { classifyAsset(it.name) == ApkVariant.UNIVERSAL }
                        ?: apkAssets.firstOrNull { classifyAsset(it.name) == ApkVariant.ANDROID7 }
                        ?: apkAssets.firstOrNull()
                }
                ApkVariant.UNIVERSAL -> {
                    apkAssets.firstOrNull { classifyAsset(it.name) == ApkVariant.UNCOMPRESSED }
                        ?: apkAssets.firstOrNull { classifyAsset(it.name) == ApkVariant.ANDROID7 }
                        ?: apkAssets.firstOrNull()
                }
                ApkVariant.ANDROID7 -> {
                    apkAssets.firstOrNull { classifyAsset(it.name) == ApkVariant.UNIVERSAL }
                        ?: apkAssets.firstOrNull()
                }
                ApkVariant.VIVO_ORIGIN -> apkAssets.firstOrNull()
            }
        }
    }
}

/** Receives PackageInstaller's result for the in-app update session. */
class UpdateInstallReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            android.content.pm.PackageInstaller.EXTRA_STATUS,
            android.content.pm.PackageInstaller.STATUS_FAILURE,
        )
        val message = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE)
        val text = when (status) {
            android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> "Confirm the update once so future updates can install silently"
            android.content.pm.PackageInstaller.STATUS_SUCCESS -> "Update installed"
            else -> "Update failed${message?.let { ": $it" }.orEmpty()}"
        }
        AppUpdateManager.activeInstance?.onInstallerStatus(status, text)
        if (status == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            runCatching {
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirm != null) context.startActivity(confirm)
            }
        }
        android.widget.Toast.makeText(context.applicationContext, text, android.widget.Toast.LENGTH_LONG).show()
    }

    companion object {
        const val ACTION_INSTALL_RESULT = "com.leothefleo49.onyxmusic.INSTALL_RESULT"
    }
}
