package com.lastwave.app.data.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppUpdateManagerTest {

    private val sampleAssets = listOf(
        ReleaseAsset(
            name = "LastWave-v4.2.4-android7.apk",
            downloadUrl = "https://github.com/Clash-Projects/LastWave-Native/releases/download/4.2.4/LastWave-v4.2.4-android7.apk",
        ),
        ReleaseAsset(
            name = "LastWave-v4.2.4-universal-uncompressed.apk",
            downloadUrl = "https://github.com/Clash-Projects/LastWave-Native/releases/download/4.2.4/LastWave-v4.2.4-universal-uncompressed.apk",
        ),
        ReleaseAsset(
            name = "LastWave-v4.2.4-universal.apk",
            downloadUrl = "https://github.com/Clash-Projects/LastWave-Native/releases/download/4.2.4/LastWave-v4.2.4-universal.apk",
        ),
        ReleaseAsset(
            name = "SHA256SUMS.txt",
            downloadUrl = "https://github.com/Clash-Projects/LastWave-Native/releases/download/4.2.4/SHA256SUMS.txt",
        ),
    )

    @Test
    fun classifyAsset_recognizesVariantsCorrectly() {
        assertThat(AppUpdateManager.classifyAsset("LastWave-v4.2.4-android7.apk"))
            .isEqualTo(ApkVariant.ANDROID7)
        assertThat(AppUpdateManager.classifyAsset("LastWave-v4.2.4-legacy.apk"))
            .isEqualTo(ApkVariant.ANDROID7)

        assertThat(AppUpdateManager.classifyAsset("LastWave-v4.2.4-universal-uncompressed.apk"))
            .isEqualTo(ApkVariant.UNCOMPRESSED)
        assertThat(AppUpdateManager.classifyAsset("LastWave-v4.2.4-raw.apk"))
            .isEqualTo(ApkVariant.UNCOMPRESSED)
        assertThat(AppUpdateManager.classifyAsset("LastWave-v4.2.4-beta-raw.apk"))
            .isEqualTo(ApkVariant.UNCOMPRESSED)

        assertThat(AppUpdateManager.classifyAsset("LastWave-v4.2.4-universal.apk"))
            .isEqualTo(ApkVariant.UNIVERSAL)
        assertThat(AppUpdateManager.classifyAsset("LastWave-v4.2.4-release.apk"))
            .isEqualTo(ApkVariant.UNIVERSAL)
        assertThat(AppUpdateManager.classifyAsset("LastWave-v4.2.4-beta.apk"))
            .isEqualTo(ApkVariant.UNIVERSAL)
    }

    @Test
    fun selectBestAsset_standardAndroid10Plus_selectsUniversalApk() {
        val selected = AppUpdateManager.selectBestAsset(
            assets = sampleAssets,
            isUncompressed = false,
            sdkInt = 34, // Android 14
        )
        assertThat(selected).isNotNull()
        assertThat(selected?.name).isEqualTo("LastWave-v4.2.4-universal.apk")
        assertThat(selected?.downloadUrl).contains("LastWave-v4.2.4-universal.apk")
    }

    @Test
    fun selectBestAsset_uncompressedBuild_selectsUncompressedApk() {
        val selected = AppUpdateManager.selectBestAsset(
            assets = sampleAssets,
            isUncompressed = true,
            sdkInt = 34, // Android 14
        )
        assertThat(selected).isNotNull()
        assertThat(selected?.name).isEqualTo("LastWave-v4.2.4-universal-uncompressed.apk")
        assertThat(selected?.downloadUrl).contains("LastWave-v4.2.4-universal-uncompressed.apk")
    }

    @Test
    fun selectBestAsset_legacyAndroid_selectsAndroid7Apk() {
        val selected = AppUpdateManager.selectBestAsset(
            assets = sampleAssets,
            isUncompressed = false,
            sdkInt = 26, // Android 8.0 Oreo
        )
        assertThat(selected).isNotNull()
        assertThat(selected?.name).isEqualTo("LastWave-v4.2.4-android7.apk")
        assertThat(selected?.downloadUrl).contains("LastWave-v4.2.4-android7.apk")
    }

    @Test
    fun selectBestAsset_ignoresNonApkAssets() {
        val nonApkOnly = listOf(
            ReleaseAsset(name = "SHA256SUMS.txt", downloadUrl = "https://example.com/sha"),
            ReleaseAsset(name = "notice.md", downloadUrl = "https://example.com/md"),
        )
        val selected = AppUpdateManager.selectBestAsset(
            assets = nonApkOnly,
            isUncompressed = false,
            sdkInt = 34,
        )
        assertThat(selected).isNull()
    }

    @Test
    fun selectBestAsset_fallbackWhenUniversalMissing_fallsBackToUncompressedOrAndroid7() {
        val assetsWithoutUniversal = listOf(
            ReleaseAsset(name = "LastWave-v4.2.4-android7.apk", downloadUrl = "https://example.com/a7"),
            ReleaseAsset(name = "LastWave-v4.2.4-universal-uncompressed.apk", downloadUrl = "https://example.com/raw"),
        )
        val selected = AppUpdateManager.selectBestAsset(
            assets = assetsWithoutUniversal,
            isUncompressed = false,
            sdkInt = 34,
        )
        assertThat(selected).isNotNull()
        assertThat(selected?.name).isEqualTo("LastWave-v4.2.4-universal-uncompressed.apk")
    }

    private val assetsWithVivo = sampleAssets + ReleaseAsset(
        name = "LastWave-v4.2.4-vivo-origin.apk",
        downloadUrl = "https://example.com/vivo",
    )

    @Test
    fun classifyAsset_recognizesVivoOrigin() {
        assertThat(AppUpdateManager.classifyAsset("LastWave-v4.2.4-vivo-origin.apk"))
            .isEqualTo(ApkVariant.VIVO_ORIGIN)
    }

    @Test
    fun selectBestAsset_vivoBuild_onlySelectsVivoApk() {
        val selected = AppUpdateManager.selectBestAsset(
            assets = assetsWithVivo,
            isUncompressed = false,
            sdkInt = 34,
            isVivoOrigin = true,
        )
        assertThat(selected?.name).isEqualTo("LastWave-v4.2.4-vivo-origin.apk")
    }

    @Test
    fun selectBestAsset_vivoBuild_withoutVivoApk_selectsNothing() {
        val selected = AppUpdateManager.selectBestAsset(
            assets = sampleAssets,
            isUncompressed = false,
            sdkInt = 34,
            isVivoOrigin = true,
        )
        assertThat(selected).isNull()
    }

    @Test
    fun selectBestAsset_normalBuild_neverSelectsVivoApk() {
        val onlyVivo = listOf(ReleaseAsset(name = "LastWave-v4.2.4-vivo-origin.apk", downloadUrl = "https://example.com/vivo"))
        assertThat(
            AppUpdateManager.selectBestAsset(assets = onlyVivo, isUncompressed = false, sdkInt = 34),
        ).isNull()
        assertThat(
            AppUpdateManager.selectBestAsset(assets = assetsWithVivo, isUncompressed = false, sdkInt = 34)?.name,
        ).isEqualTo("LastWave-v4.2.4-universal.apk")
    }

    @Test
    fun isValidApkMagic_acceptsZipApkHeaderAndRejectsCorruptPayloads() {
        assertThat(AppUpdateManager.isValidApkMagic(byteArrayOf(0x50, 0x4B, 0x03, 0x04))).isTrue()
        assertThat(AppUpdateManager.isValidApkMagic(byteArrayOf(0x3C, 0x21, 0x44, 0x4F))).isFalse() // <!DO (HTML error)
        assertThat(AppUpdateManager.isValidApkMagic(byteArrayOf(0x50, 0x4B))).isFalse()
    }

    @Test
    fun normalizeReleaseApiUrl_handlesShorthandGithubWebAndFullApiUrls() {
        assertThat(AppUpdateManager.normalizeReleaseApiUrl("")).isEmpty()
        assertThat(AppUpdateManager.normalizeReleaseApiUrl("leothefleo49/MyOtherApp"))
            .isEqualTo("https://api.github.com/repos/leothefleo49/MyOtherApp/releases/latest")
        assertThat(AppUpdateManager.normalizeReleaseApiUrl("https://github.com/leothefleo49/MyOtherApp/releases"))
            .isEqualTo("https://api.github.com/repos/leothefleo49/MyOtherApp/releases/latest")
        assertThat(AppUpdateManager.normalizeReleaseApiUrl("https://api.github.com/repos/a/b/releases/latest"))
            .isEqualTo("https://api.github.com/repos/a/b/releases/latest")
        assertThat(AppUpdateManager.normalizeReleaseApiUrl("http://insecure.example/feed")).isEmpty()
    }

    @Test
    fun shouldAutoCommitNow_defersSilentCommitWhilePlayingOrBuffering() {
        assertThat(AppUpdateManager.shouldAutoCommitNow(silentBackground = true, isPlaying = true, isBuffering = false)).isFalse()
        assertThat(AppUpdateManager.shouldAutoCommitNow(silentBackground = true, isPlaying = false, isBuffering = true)).isFalse()
        assertThat(AppUpdateManager.shouldAutoCommitNow(silentBackground = true, isPlaying = false, isBuffering = false)).isTrue()
        assertThat(AppUpdateManager.shouldAutoCommitNow(silentBackground = false, isPlaying = true, isBuffering = true)).isTrue()
    }

    @Test
    fun isSelfOwnedInstallerResult_enforcesAndroid12AndAndroid14UpdateOwnershipRules() {
        val pkg = "com.leothefleo49.onyxmusic"
        // Pre-Android 12 never supports silent USER_ACTION_NOT_REQUIRED
        assertThat(AppUpdateManager.isSelfOwnedInstallerResult(30, pkg, pkg, null)).isFalse()
        // Android 12/13: installer of record == packageName
        assertThat(AppUpdateManager.isSelfOwnedInstallerResult(31, pkg, pkg, null)).isTrue()
        assertThat(AppUpdateManager.isSelfOwnedInstallerResult(33, pkg, "com.android.chrome", null)).isFalse()
        // Android 14+: installer of record == packageName AND updateOwner is null or packageName
        assertThat(AppUpdateManager.isSelfOwnedInstallerResult(34, pkg, pkg, pkg)).isTrue()
        assertThat(AppUpdateManager.isSelfOwnedInstallerResult(34, pkg, pkg, null)).isTrue()
        assertThat(AppUpdateManager.isSelfOwnedInstallerResult(35, pkg, pkg, "com.android.vending")).isFalse()
        assertThat(AppUpdateManager.isSelfOwnedInstallerResult(35, pkg, "com.sec.android.app.myfiles", null)).isFalse()
    }
}
