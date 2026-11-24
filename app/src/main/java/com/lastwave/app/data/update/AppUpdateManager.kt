package com.lastwave.app.data.update

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.lastwave.app.BuildConfig
import com.lastwave.app.MainShellActivity
import com.lastwave.app.R
import com.lastwave.app.data.repository.ThemeRepository
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.ui.common.renderOnyxLogoBitmap
import com.lastwave.app.util.AppVisibility
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

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
    /** Whether Android grants REQUEST_INSTALL_PACKAGES to Onyx Music. */
    val canInstallUnknownApps: Boolean = false,
    /** Whether Android recognizes Onyx Music as its own installer / update owner (zero-tap ready). */
    val isSelfOwnedInstaller: Boolean = false,
    /** True when a verified update APK is downloaded and ready to apply when playback pauses (or on tap). */
    val isStagedReady: Boolean = false,
    /** True when Android returned STATUS_PENDING_USER_ACTION for one-time confirmation. */
    val hasPendingConfirmation: Boolean = false,
    /** Custom GitHub Releases API URL override (blank = default leothefleo49/Onyx-Music). */
    val customReleaseApiUrl: String = "",
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
    private val themeRepository: dagger.Lazy<ThemeRepository>,
    private val musicPlayer: dagger.Lazy<MusicPlayer>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile private var lastAutoAttemptedVersion: String? = null
    @Volatile private var lastCheckElapsedMs = 0L
    @Volatile private var pendingConfirmationIntent: Intent? = null
    @Volatile private var pendingConfirmationAutoLaunched = false
    @Volatile private var pendingOwnershipClaimAfterPermission = false
    @Volatile private var stagedVersion: String? = null
    private var deferredCommitWatcher: Job? = null

    private val _updateInfo = MutableStateFlow(
        UpdateInfo(
            currentVersion = getCurrentVersion(),
            autoInstallUpdates = prefs.getBoolean(KEY_AUTO_INSTALL, true),
            canInstallUnknownApps = hasInstallPermission(),
            isSelfOwnedInstaller = isSelfOwnedInstaller(),
            customReleaseApiUrl = prefs.getString(KEY_CUSTOM_FEED_URL, "").orEmpty(),
        ),
    )
    val updateInfo: StateFlow<UpdateInfo> = _updateInfo.asStateFlow()

    init {
        activeInstance = this
        checkForUpdate(isSilent = true)
        startPeriodicCheckLoop()
    }

    private fun startPeriodicCheckLoop() {
        scope.launch {
            while (isActive) {
                delay(PERIODIC_CHECK_INTERVAL_MS)
                val info = _updateInfo.value
                if (!info.isInstalling && !info.isChecking && !info.hasPendingConfirmation) {
                    checkForUpdate(isSilent = true)
                }
            }
        }
    }

    fun isAutoInstallEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_INSTALL, true)

    fun setAutoInstallUpdates(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_INSTALL, enabled).apply()
        _updateInfo.update { it.copy(autoInstallUpdates = enabled) }
        if (enabled && _updateInfo.value.isUpdateAvailable && !_updateInfo.value.isInstalling) {
            if (hasInstallPermission()) {
                downloadAndInstall(context, silentBackground = true)
            }
        }
    }

    fun getCustomReleaseApiUrl(): String = prefs.getString(KEY_CUSTOM_FEED_URL, "").orEmpty()

    fun setCustomReleaseApiUrl(rawInput: String): String {
        val normalized = normalizeReleaseApiUrl(rawInput)
        prefs.edit()
            .putString(KEY_CUSTOM_FEED_URL, normalized)
            .remove(KEY_CACHED_ETAG)
            .remove(KEY_CACHED_RELEASE_JSON)
            .apply()
        lastAutoAttemptedVersion = null
        stagedVersion = null
        _updateInfo.update {
            it.copy(
                customReleaseApiUrl = normalized,
                isStagedReady = false,
                message = if (normalized.isBlank()) {
                    "Reset to default Onyx Music release feed"
                } else {
                    "Update feed set to $normalized"
                },
            )
        }
        checkForUpdate(isSilent = false)
        return normalized
    }

    fun hasInstallPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    fun isSelfOwnedInstaller(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return runCatching {
            val source = context.packageManager.getInstallSourceInfo(context.packageName)
            val updateOwner = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                source.updateOwnerPackageName
            } else {
                null
            }
            isSelfOwnedInstallerResult(
                sdkInt = Build.VERSION.SDK_INT,
                packageName = context.packageName,
                installingPkg = source.installingPackageName,
                updateOwnerPkg = updateOwner,
            )
        }.getOrDefault(false)
    }

    fun refreshInstallCapabilities() {
        val canInstall = hasInstallPermission()
        val selfOwned = isSelfOwnedInstaller()
        _updateInfo.update {
            it.copy(
                currentVersion = getCurrentVersion(),
                canInstallUnknownApps = canInstall,
                isSelfOwnedInstaller = selfOwned,
            )
        }
    }

    /**
     * Called from [MainShellActivity.onResume]. Refreshes install ownership,
     * delivers any pending OS confirmation directly from the resumed Activity
     * (never blocked by Android 10+ / One UI background-start restrictions),
     * continues one-tap ownership setup after returning from system Settings,
     * applies any staged update when idle, and runs a throttled ETag update check.
     */
    fun onAppForegrounded(activity: Activity? = AppVisibility.resumedActivity) {
        refreshInstallCapabilities()

        // Confirm a completed silent auto-update cleanly on the next open.
        val autoUpdatedTo = prefs.getString(KEY_JUST_AUTO_UPDATED_TO, null)
        if (!autoUpdatedTo.isNullOrBlank()) {
            prefs.edit().remove(KEY_JUST_AUTO_UPDATED_TO).apply()
            mainHandler.post {
                Toast.makeText(
                    context.applicationContext,
                    "Onyx Music auto-updated to v$autoUpdatedTo",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

        // If user just returned from "Install unknown apps" Settings after tapping Claim Ownership,
        // automatically finish Step 2 immediately.
        if (pendingOwnershipClaimAfterPermission && hasInstallPermission()) {
            pendingOwnershipClaimAfterPermission = false
            if (!isSelfOwnedInstaller()) {
                claimSilentUpdateOwnership(activity ?: context)
                return
            }
        }

        // Layer 1 confirmation delivery: launch pending OS prompt directly from foreground Activity.
        val pending = pendingConfirmationIntent
        if (pending != null && !pendingConfirmationAutoLaunched && activity != null && !activity.isFinishing) {
            pendingConfirmationAutoLaunched = true
            runCatching { activity.startActivity(pending) }
            return
        }

        // If an update APK is already staged and music is paused/idle, commit silently now.
        val stagedFile = existingStagedApk()
        if (stagedFile != null && isAutoInstallEnabled() && !_updateInfo.value.isInstalling &&
            !isMusicActivelyPlaying()
        ) {
            scope.launch {
                commitApkFiles(
                    apkFiles = listOf(stagedFile),
                    targetVersion = stagedVersion ?: _updateInfo.value.latestVersion,
                    silentBackground = true,
                )
            }
            return
        }

        // Throttled resume check (15 min cooldown, zero GitHub quota cost via ETag 304).
        val now = SystemClock.elapsedRealtime()
        if (lastCheckElapsedMs == 0L || now - lastCheckElapsedMs >= RESUME_CHECK_COOLDOWN_MS) {
            checkForUpdate(isSilent = true)
        }
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
            refreshInstallCapabilities()
            _updateInfo.update {
                it.copy(
                    isChecking = true,
                    message = if (!isSilent) "Checking for updates…" else it.message,
                )
            }
            try {
                val feedUrl = activeReleaseApiUrl()
                val cachedEtag = prefs.getString(KEY_CACHED_ETAG, null)
                val cachedBody = prefs.getString(KEY_CACHED_RELEASE_JSON, null)

                val requestBuilder = Request.Builder()
                    .url(feedUrl)
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "OnyxMusic-Android")
                if (!cachedEtag.isNullOrBlank() && !cachedBody.isNullOrBlank()) {
                    requestBuilder.header("If-None-Match", cachedEtag)
                }

                val body = withContext(Dispatchers.IO) {
                    okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                        when {
                            response.code == 304 && !cachedBody.isNullOrBlank() -> cachedBody
                            response.isSuccessful -> {
                                val raw = response.body?.string().orEmpty()
                                val etag = response.header("ETag")
                                if (raw.isNotBlank()) {
                                    prefs.edit()
                                        .putString(KEY_CACHED_RELEASE_JSON, raw)
                                        .apply {
                                            if (!etag.isNullOrBlank()) putString(KEY_CACHED_ETAG, etag)
                                        }
                                        .apply()
                                }
                                raw
                            }
                            else -> {
                                val code = response.code
                                _updateInfo.update {
                                    it.copy(
                                        isChecking = false,
                                        message = if (!isSilent) "Could not check updates (HTTP $code)" else null,
                                    )
                                }
                                return@withContext null
                            }
                        }
                    }
                } ?: return@launch

                lastCheckElapsedMs = SystemClock.elapsedRealtime()
                val json = JSONObject(body)
                val tagName = json.optString("tag_name", "")
                val releaseUrl = json.optString("html_url", DEFAULT_RELEASES_HTML_URL)
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
                val customFeedActive = getCustomReleaseApiUrl().isNotBlank()
                val hasNewer = isNewerVersion(tagName, currentVersion) ||
                    (customFeedActive && tagName.isNotBlank() &&
                        tagName.removePrefix("v").removePrefix("V") != currentVersion.removePrefix("v").removePrefix("V"))
                val cleanTag = tagName.removePrefix("v").removePrefix("V")
                val dismissedVersion = prefs.getString("dismissed_version", null)
                val isDismissed = dismissedVersion == cleanTag
                val canInstall = hasInstallPermission()
                val selfOwned = isSelfOwnedInstaller()

                val alreadyAttemptedForTag = lastAutoAttemptedVersion == cleanTag && cleanTag.isNotBlank()
                val canAutoInstall = isSilent && hasNewer && !isDismissed && isAutoInstallEnabled() &&
                    !bestAsset?.downloadUrl.isNullOrBlank() && canInstall
                val autoOpen = isSilent && hasNewer && !isDismissed && !alreadyAttemptedForTag && !canAutoInstall
                if (autoOpen) lastAutoAttemptedVersion = cleanTag

                val alreadyStaged = hasNewer && stagedApkFile(cleanTag).let { it.isFile && it.length() > MIN_APK_BYTES }
                if (alreadyStaged) stagedVersion = cleanTag

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
                        canInstallUnknownApps = canInstall,
                        isSelfOwnedInstaller = selfOwned,
                        isStagedReady = alreadyStaged,
                        message = if (!isSilent) {
                            if (hasNewer) "New version $cleanTag available!" else "You're on the latest version ($currentVersion)"
                        } else null,
                    )
                }

                if (canAutoInstall && !alreadyAttemptedForTag) {
                    lastAutoAttemptedVersion = cleanTag
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
        val targetUrl = _updateInfo.value.releaseUrl.ifBlank { DEFAULT_RELEASES_HTML_URL }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun openDownload(context: Context) {
        val targetUrl = _updateInfo.value.downloadUrl?.takeIf { it.isNotBlank() }
            ?: return openReleasePage(context)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    /**
     * Opens Android's per-app "Install unknown apps" toggle for Onyx Music.
     */
    fun openUnknownSourcesSettings(hostContext: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).apply {
            if (hostContext !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { hostContext.startActivity(intent) }
    }

    /**
     * One-time zero-download ownership claim:
     * Streams Onyx Music's own already-installed APK (`applicationInfo.sourceDir`)
     * into a PackageInstaller session with `setRequestUpdateOwnership(true)`.
     * Confirming that ONE system prompt transfers Android's installer-of-record &
     * update owner from Chrome/Files to Onyx Music immediately, so every future
     * release applies 100% silently with zero taps.
     */
    fun claimSilentUpdateOwnership(hostContext: Context) {
        refreshInstallCapabilities()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !hasInstallPermission()) {
            pendingOwnershipClaimAfterPermission = true
            _updateInfo.update {
                it.copy(installMessage = "Step 1 of 2: Enable 'Allow from this source', then press Back to finish.")
            }
            openUnknownSourcesSettings(hostContext)
            return
        }
        if (_updateInfo.value.isInstalling) return
        scope.launch {
            val appInfo = context.applicationInfo
            val files = buildList {
                appInfo.sourceDir?.let(::File)?.takeIf { it.isFile && it.canRead() }?.let(::add)
                appInfo.splitSourceDirs?.forEach { path ->
                    File(path).takeIf { it.isFile && it.canRead() }?.let(::add)
                }
            }
            if (files.isEmpty()) {
                _updateInfo.update {
                    it.copy(installMessage = "Could not locate installed APK to claim ownership.")
                }
                return@launch
            }
            _updateInfo.update {
                it.copy(
                    isInstalling = true,
                    installProgress = 0.5f,
                    installMessage = "Preparing one-time silent update ownership transfer…",
                )
            }
            commitApkFiles(
                apkFiles = files,
                targetVersion = getCurrentVersion(),
                silentBackground = false,
            )
        }
    }

    /**
     * Launches a pending OS confirmation intent from a foreground Activity/Context.
     */
    fun launchPendingConfirmation(hostContext: Context): Boolean {
        val pending = pendingConfirmationIntent ?: return false
        val target = (hostContext as? Activity) ?: AppVisibility.resumedActivity
        return runCatching {
            if (target != null && !target.isFinishing) {
                target.startActivity(pending)
            } else {
                pending.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                hostContext.startActivity(pending)
            }
            pendingConfirmationAutoLaunched = true
            true
        }.getOrDefault(false)
    }

    /**
     * Immediately applies a previously downloaded & staged update APK.
     */
    fun applyStagedUpdateNow(hostContext: Context) {
        val staged = existingStagedApk()
        if (staged == null) {
            downloadAndInstall(hostContext, silentBackground = false)
            return
        }
        deferredCommitWatcher?.cancel()
        scope.launch {
            commitApkFiles(
                apkFiles = listOf(staged),
                targetVersion = stagedVersion ?: _updateInfo.value.latestVersion,
                silentBackground = false,
            )
        }
    }

    /**
     * Downloads and verifies the release APK, then either commits it silently
     * immediately (if idle or user-initiated) or stages it to install the moment
     * playback pauses (so silent background updates never cut off a song mid-listen).
     */
    fun downloadAndInstall(hostContext: Context, silentBackground: Boolean = false) {
        val url = _updateInfo.value.downloadUrl?.takeIf { it.isNotBlank() }
            ?: return openReleasePage(hostContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !hasInstallPermission()) {
            _updateInfo.update {
                it.copy(
                    showDialog = true,
                    canInstallUnknownApps = false,
                    installMessage = "Allow Onyx Music to install unknown apps, then tap Update again.",
                )
            }
            if (!silentBackground) {
                openUnknownSourcesSettings(hostContext)
            }
            return
        }
        if (_updateInfo.value.isInstalling) return

        val targetVersion = _updateInfo.value.latestVersion.ifBlank { "latest" }
        val existingStaged = stagedApkFile(targetVersion).takeIf { it.isFile && it.length() > MIN_APK_BYTES }
        if (existingStaged != null && verifyApkFile(existingStaged)) {
            stagedVersion = targetVersion
            scope.launch {
                handleVerifiedApkReady(existingStaged, targetVersion, silentBackground)
            }
            return
        }

        scope.launch {
            _updateInfo.update {
                it.copy(
                    isInstalling = true,
                    installProgress = 0f,
                    hasPendingConfirmation = false,
                    installMessage = "Downloading update…",
                )
            }
            val tempFile = File(context.cacheDir, "onyx_update_tmp_${System.currentTimeMillis()}.apk")
            try {
                val request = Request.Builder().url(url).header("User-Agent", "OnyxMusic-Android").build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body ?: error("Empty update file")
                    val total = body.contentLength()
                    var copied = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                copied += read
                                if (total > 0L) {
                                    _updateInfo.update {
                                        it.copy(installProgress = (copied.toFloat() / total).coerceIn(0f, 0.94f))
                                    }
                                }
                            }
                            output.flush()
                        }
                    }
                    if (total > 0L && copied != total) {
                        error("Incomplete download ($copied / $total bytes)")
                    }
                    if (copied < MIN_APK_BYTES) {
                        error("Downloaded file is too small to be a valid APK")
                    }
                }

                if (!verifyApkFile(tempFile)) {
                    error("Downloaded file failed APK integrity check")
                }

                cleanupOldStagedApks(keepName = stagedApkFile(targetVersion).name)
                val finalStaged = stagedApkFile(targetVersion)
                if (finalStaged.exists()) finalStaged.delete()
                if (!tempFile.renameTo(finalStaged)) {
                    tempFile.copyTo(finalStaged, overwrite = true)
                    tempFile.delete()
                }
                stagedVersion = targetVersion
                handleVerifiedApkReady(finalStaged, targetVersion, silentBackground)
            } catch (error: Exception) {
                runCatching { tempFile.delete() }
                // Allow automatic retry on next foreground/periodic check after a transient network drop.
                if (silentBackground) lastAutoAttemptedVersion = null
                _updateInfo.update {
                    it.copy(
                        isInstalling = false,
                        installProgress = -1f,
                        showDialog = !silentBackground || it.showDialog,
                        installMessage = "Update download failed: ${error.message ?: "unknown error"}",
                    )
                }
            }
        }
    }

    private suspend fun handleVerifiedApkReady(
        apkFile: File,
        targetVersion: String,
        silentBackground: Boolean,
    ) {
        val playerState = runCatching { musicPlayer.get().state.value }.getOrNull()
        val commitNow = shouldAutoCommitNow(
            silentBackground = silentBackground,
            isPlaying = playerState?.isPlaying == true,
            isBuffering = playerState?.isBuffering == true,
        )
        if (!commitNow) {
            _updateInfo.update {
                it.copy(
                    isInstalling = false,
                    installProgress = 1f,
                    isStagedReady = true,
                    installMessage = "v$targetVersion downloaded — will auto-install as soon as playback pauses.",
                )
            }
            watchForIdlePlaybackAndCommit(apkFile, targetVersion)
            return
        }
        commitApkFiles(
            apkFiles = listOf(apkFile),
            targetVersion = targetVersion,
            silentBackground = silentBackground,
        )
    }

    private fun watchForIdlePlaybackAndCommit(apkFile: File, targetVersion: String) {
        deferredCommitWatcher?.cancel()
        deferredCommitWatcher = scope.launch {
            var idleSinceMs = 0L
            while (isActive) {
                delay(1_000L)
                if (!apkFile.isFile || !isAutoInstallEnabled()) return@launch
                if (!isMusicActivelyPlaying()) {
                    if (idleSinceMs == 0L) {
                        idleSinceMs = SystemClock.elapsedRealtime()
                    } else if (SystemClock.elapsedRealtime() - idleSinceMs >= PLAYBACK_IDLE_SETTLE_MS) {
                        commitApkFiles(
                            apkFiles = listOf(apkFile),
                            targetVersion = targetVersion,
                            silentBackground = true,
                        )
                        return@launch
                    }
                } else {
                    idleSinceMs = 0L
                }
            }
        }
    }

    private suspend fun commitApkFiles(
        apkFiles: List<File>,
        targetVersion: String,
        silentBackground: Boolean,
    ) {
        var session: PackageInstaller.Session? = null
        try {
            val installer = context.packageManager.packageInstaller
            abandonStaleSessions(installer)

            val totalSize = apkFiles.sumOf { it.length() }
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            ).apply {
                setAppPackageName(context.packageName)
                setAppLabel("Onyx Music")
                runCatching {
                    val tState = themeRepository.get().uiState.value
                    val p = tState.colorScheme.primary
                    val op = tState.colorScheme.onPrimary
                    val pArgb = Color.argb(
                        (p.alpha * 255f).toInt(), (p.red * 255f).toInt(), (p.green * 255f).toInt(), (p.blue * 255f).toInt(),
                    )
                    val opArgb = Color.argb(
                        (op.alpha * 255f).toInt(), (op.red * 255f).toInt(), (op.green * 255f).toInt(), (op.blue * 255f).toInt(),
                    )
                    setAppIcon(
                        renderOnyxLogoBitmap(
                            context = context,
                            appIcon = tState.appIcon,
                            primaryArgb = pArgb,
                            onPrimaryArgb = opArgb,
                            sizePx = 192,
                        ),
                    )
                }
                if (totalSize > 0L) setSize(totalSize)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    setRequestUpdateOwnership(true)
                }
            }

            _updateInfo.update {
                it.copy(
                    isInstalling = true,
                    installProgress = 0.96f,
                    installMessage = "Applying update…",
                )
            }

            val sessionId = installer.createSession(params)
            session = installer.openSession(sessionId)
            apkFiles.forEachIndexed { idx, file ->
                val splitName = if (idx == 0) "base.apk" else "split_$idx.apk"
                FileInputStream(file).use { input ->
                    session.openWrite(splitName, 0L, file.length()).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                        runCatching { session.fsync(output) }
                    }
                }
            }

            // Record foreground state so PackageReplacedReceiver knows whether to relaunch/notify cleanly.
            prefs.edit()
                .putBoolean(KEY_RELAUNCH_AFTER_UPDATE, AppVisibility.isVisible)
                .putString(KEY_PENDING_TARGET_VERSION, targetVersion)
                .apply()

            val callbackFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val callback = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, UpdateInstallReceiver::class.java)
                    .setAction(UpdateInstallReceiver.ACTION_INSTALL_RESULT)
                    .setPackage(context.packageName),
                callbackFlags,
            )
            pendingConfirmationIntent = null
            pendingConfirmationAutoLaunched = false
            session.commit(callback.intentSender)
            _updateInfo.update {
                it.copy(
                    isInstalling = false,
                    installProgress = 1f,
                    isStagedReady = true,
                    installMessage = "Installing update…",
                )
            }
        } catch (error: Exception) {
            runCatching { session?.abandon() }
            _updateInfo.update {
                it.copy(
                    isInstalling = false,
                    installProgress = -1f,
                    showDialog = !silentBackground || it.showDialog,
                    installMessage = "Install session failed: ${error.message ?: "unknown error"}",
                )
            }
        } finally {
            runCatching { session?.close() }
        }
    }

    private fun abandonStaleSessions(installer: PackageInstaller) {
        runCatching {
            installer.mySessions.forEach { info ->
                if (!info.isActive) {
                    runCatching { installer.abandonSession(info.sessionId) }
                }
            }
        }
    }

    private fun isMusicActivelyPlaying(): Boolean {
        val state = runCatching { musicPlayer.get().state.value }.getOrNull() ?: return false
        return state.isPlaying || state.isBuffering
    }

    private fun stagedApkFile(version: String): File {
        val safe = version.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "latest" }
        return File(context.cacheDir, "$STAGED_APK_PREFIX$safe.apk")
    }

    private fun existingStagedApk(): File? {
        val v = stagedVersion ?: _updateInfo.value.latestVersion.takeIf { it.isNotBlank() } ?: return null
        return stagedApkFile(v).takeIf { it.isFile && it.length() > MIN_APK_BYTES }
    }

    private fun cleanupOldStagedApks(keepName: String? = null) {
        runCatching {
            context.cacheDir.listFiles { f ->
                f.isFile && f.name.startsWith(STAGED_APK_PREFIX) && f.name != keepName
            }?.forEach { runCatching { it.delete() } }
        }
    }

    private fun verifyApkFile(file: File): Boolean {
        if (!file.isFile || file.length() < MIN_APK_BYTES) return false
        return runCatching {
            val header = ByteArray(4)
            FileInputStream(file).use { input ->
                if (input.read(header) != 4) return@runCatching false
            }
            isValidApkMagic(header)
        }.getOrDefault(false)
    }

    internal fun onInstallerPendingUserAction(confirmIntent: Intent?) {
        pendingConfirmationIntent = confirmIntent
        pendingConfirmationAutoLaunched = false
        _updateInfo.update {
            it.copy(
                isInstalling = false,
                hasPendingConfirmation = confirmIntent != null,
                showDialog = true,
                installMessage = "Tap Confirm once — all future updates will install silently with zero taps.",
            )
        }
        if (confirmIntent == null) return

        // Layer 1: Direct launch from live foreground Activity (never blocked by Android 10+ / One UI).
        val activity = AppVisibility.resumedActivity
        if (activity != null && !activity.isFinishing) {
            pendingConfirmationAutoLaunched = true
            mainHandler.post {
                val launched = runCatching { activity.startActivity(confirmIntent) }.isSuccess
                if (!launched) {
                    postUpdateConfirmationNotification(context, confirmIntent)
                }
            }
            return
        }

        // Layer 3: App is backgrounded — post a high-priority tap-to-confirm notification so the
        // prompt is never silently dropped by Android's background activity launch restrictions.
        postUpdateConfirmationNotification(context, confirmIntent)
    }

    internal fun onInstallerStatus(status: Int, statusText: String) {
        if (status == PackageInstaller.STATUS_SUCCESS) {
            pendingConfirmationIntent = null
            pendingConfirmationAutoLaunched = false
            cleanupOldStagedApks()
            cancelUpdateNotification(context)
        }
        refreshInstallCapabilities()
        _updateInfo.update {
            it.copy(
                isInstalling = false,
                installProgress = if (status == PackageInstaller.STATUS_SUCCESS) 1f else -1f,
                hasPendingConfirmation = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) it.hasPendingConfirmation else false,
                installMessage = statusText,
            )
        }
    }

    private fun activeReleaseApiUrl(): String =
        getCustomReleaseApiUrl().ifBlank { DEFAULT_RELEASE_API_URL }

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
        internal const val PREFS_NAME = "onyx_updates"
        internal const val KEY_AUTO_INSTALL = "auto_install_updates"
        internal const val KEY_CUSTOM_FEED_URL = "custom_release_api_url"
        internal const val KEY_CACHED_ETAG = "github_release_etag"
        internal const val KEY_CACHED_RELEASE_JSON = "github_release_json"
        internal const val KEY_RELAUNCH_AFTER_UPDATE = "relaunch_after_update"
        internal const val KEY_PENDING_TARGET_VERSION = "pending_target_version"
        internal const val KEY_JUST_AUTO_UPDATED_TO = "just_auto_updated_to"
        internal const val STAGED_APK_PREFIX = "onyx_staged_update_"
        internal const val UPDATE_CHANNEL_ID = "onyx_updates_v1"
        internal const val UPDATE_NOTIFICATION_ID = 4190

        const val DEFAULT_RELEASE_API_URL = "https://api.github.com/repos/leothefleo49/Onyx-Music/releases/latest"
        const val DEFAULT_RELEASES_HTML_URL = "https://github.com/leothefleo49/Onyx-Music/releases"

        private const val MIN_APK_BYTES = 100L * 1024L
        private const val RESUME_CHECK_COOLDOWN_MS = 15L * 60L * 1000L
        private const val PERIODIC_CHECK_INTERVAL_MS = 2L * 60L * 60L * 1000L
        private const val PLAYBACK_IDLE_SETTLE_MS = 2_000L

        @Volatile internal var activeInstance: AppUpdateManager? = null

        /** Checks ZIP/APK magic header `PK\x03\x04`. */
        fun isValidApkMagic(header: ByteArray): Boolean =
            header.size >= 4 &&
                header[0] == 0x50.toByte() &&
                header[1] == 0x4B.toByte() &&
                header[2] == 0x03.toByte() &&
                header[3] == 0x04.toByte()

        /**
         * Normalizes a user-provided custom release feed into a GitHub Releases Latest API URL.
         * Accepts blank (reset to default), `owner/repo`, `https://github.com/owner/repo`,
         * or a full `https://...` JSON API URL.
         */
        fun normalizeReleaseApiUrl(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return ""
            val githubWebRegex = Regex("""^https?://github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)(?:/.*)?$""", RegexOption.IGNORE_CASE)
            githubWebRegex.matchEntire(trimmed)?.let { m ->
                val owner = m.groupValues[1]
                val repo = m.groupValues[2].removeSuffix(".git")
                return "https://api.github.com/repos/$owner/$repo/releases/latest"
            }
            val shorthandRegex = Regex("""^([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)$""")
            shorthandRegex.matchEntire(trimmed)?.let { m ->
                val owner = m.groupValues[1]
                val repo = m.groupValues[2].removeSuffix(".git")
                return "https://api.github.com/repos/$owner/$repo/releases/latest"
            }
            return if (trimmed.startsWith("https://", ignoreCase = true)) trimmed else ""
        }

        /**
         * Silent background updates wait for playback to pause so we never interrupt a song mid-listen;
         * explicit user-triggered updates or idle sessions commit immediately.
         */
        fun shouldAutoCommitNow(
            silentBackground: Boolean,
            isPlaying: Boolean,
            isBuffering: Boolean,
        ): Boolean = !silentBackground || (!isPlaying && !isBuffering)

        /**
         * Pure check for whether Android will grant `USER_ACTION_NOT_REQUIRED` silently:
         * requires API 31+ (`S`), installer of record == this package, and on API 34+
         * update owner must be null or this package.
         */
        fun isSelfOwnedInstallerResult(
            sdkInt: Int,
            packageName: String,
            installingPkg: String?,
            updateOwnerPkg: String?,
        ): Boolean {
            if (sdkInt < Build.VERSION_CODES.S) return false
            if (installingPkg != packageName) return false
            return if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                updateOwnerPkg == null || updateOwnerPkg == packageName
            } else {
                true
            }
        }

        internal fun ensureUpdateNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.getNotificationChannel(UPDATE_CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                UPDATE_CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Onyx Music automatic update confirmations and status"
            }
            runCatching { nm.createNotificationChannel(channel) }
        }

        internal fun postUpdateConfirmationNotification(context: Context, confirmIntent: Intent) {
            ensureUpdateNotificationChannel(context)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pendingIntent = PendingIntent.getActivity(
                context,
                UPDATE_NOTIFICATION_ID,
                confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                piFlags,
            )
            val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_lastwave_logo_mono)
                .setContentTitle("Onyx Music update ready")
                .setContentText("Tap once to confirm — future updates install silently")
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .build()
            runCatching { nm.notify(UPDATE_NOTIFICATION_ID, notification) }
        }

        internal fun postUpdatedSuccessNotification(context: Context, versionName: String) {
            ensureUpdateNotificationChannel(context)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val launch = Intent(context, MainShellActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val pendingIntent = PendingIntent.getActivity(context, UPDATE_NOTIFICATION_ID + 1, launch, piFlags)
            val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_lastwave_logo_mono)
                .setContentTitle("Onyx Music updated")
                .setContentText("Automatically updated to v$versionName")
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .build()
            runCatching { nm.notify(UPDATE_NOTIFICATION_ID, notification) }
        }

        internal fun cancelUpdateNotification(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            runCatching { nm.cancel(UPDATE_NOTIFICATION_ID) }
        }

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
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            val manager = AppUpdateManager.activeInstance
            if (manager != null) {
                manager.onInstallerPendingUserAction(confirm)
            } else if (confirm != null) {
                AppUpdateManager.postUpdateConfirmationNotification(context.applicationContext, confirm)
            }
            return
        }

        val text = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "Onyx Music updated!"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Update cancelled"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Update failed: not enough storage space"
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Update failed: APK signature or version conflict"
            else -> "Update failed${message?.let { ": $it" }.orEmpty()}"
        }
        AppUpdateManager.activeInstance?.onInstallerStatus(status, text)
        if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
            Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val ACTION_INSTALL_RESULT = "com.leothefleo49.onyxmusic.INSTALL_RESULT"
    }
}

/**
 * Triggered by Android immediately after Onyx Music finishes replacing itself
 * (including 100% silent zero-tap updates). Cleans up staged APK cache files,
 * records the post-update toast marker, posts a clean confirmation notification,
 * and relaunches the UI when the update started while the user was in-app.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(AppUpdateManager.PREFS_NAME, Context.MODE_PRIVATE)
        val newVersion = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { BuildConfig.VERSION_NAME }

        // Clean up any staged APK cache files left from the update.
        runCatching {
            appContext.cacheDir.listFiles { f ->
                f.isFile && (f.name.startsWith(AppUpdateManager.STAGED_APK_PREFIX) || f.name.startsWith("onyx_update_tmp_"))
            }?.forEach { runCatching { it.delete() } }
        }

        val shouldRelaunch = prefs.getBoolean(AppUpdateManager.KEY_RELAUNCH_AFTER_UPDATE, false)
        prefs.edit()
            .remove(AppUpdateManager.KEY_RELAUNCH_AFTER_UPDATE)
            .remove(AppUpdateManager.KEY_PENDING_TARGET_VERSION)
            .putString(AppUpdateManager.KEY_JUST_AUTO_UPDATED_TO, newVersion)
            .apply()

        AppUpdateManager.postUpdatedSuccessNotification(appContext, newVersion)
        if (shouldRelaunch) {
            runCatching {
                val launch = Intent(appContext, MainShellActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                appContext.startActivity(launch)
            }
        }
    }
}
