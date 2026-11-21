package com.lastwave.app.data.spotify.mirror

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.playback.MusicPlayerState
import com.lastwave.app.playback.PlayableTrack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpotifySilentMirrorEngineTest {

    @Test
    fun disabledByDefault_neverConnectsOrMirrors() = runTest {
        val harness = MirrorTestHarness(testClock = { testScheduler.currentTime })
        harness.engine.start(backgroundScope)

        harness.playerState.value = MusicPlayerState(
            current = CATALOG_TRACK,
            isPlaying = true,
            positionMs = 1_000L,
        )
        runCurrent()

        assertThat(harness.engine.state.value.phase).isEqualTo(MirrorPhase.DISABLED)
        assertThat(harness.engine.isMirroringActive.value).isFalse()
        assertThat(harness.transport.connectCount).isEqualTo(0)
        assertThat(harness.transport.playedUris).isEmpty()
    }

    @Test
    fun matchedTrack_mirrorsSilently_handlesPauseResumeAndDebouncedSeek() = runTest {
        val harness = MirrorTestHarness(testClock = { testScheduler.currentTime })
        harness.settings.value = SpotifyMirrorSettings(enabled = true, pauseWhenExternalDeviceActive = true)
        harness.spotifyConnected.value = true
        harness.engine.start(backgroundScope)
        runCurrent()

        assertThat(harness.engine.state.value.phase).isEqualTo(MirrorPhase.CONNECTED_IDLE)

        // Start playing a catalog-matched track.
        harness.playerState.value = MusicPlayerState(
            current = CATALOG_TRACK,
            isPlaying = true,
            positionMs = 5_000L,
            durationMs = 200_000L,
        )
        runCurrent()

        assertThat(harness.engine.state.value.phase).isEqualTo(MirrorPhase.MIRRORING)
        assertThat(harness.engine.isMirroringActive.value).isTrue()
        assertThat(harness.transport.playedUris).containsExactly(CATALOG_URI to 5_000L)
        assertThat(harness.transport.volumeZeroCount).isGreaterThan(0)
        assertThat(harness.logDao.entries.map { it.mirrorOutcome }).containsExactly(MirrorOutcome.MIRRORED)

        // Small position drift (< 3s) does not trigger seek.
        advanceTimeBy(1_000L)
        harness.playerState.value = harness.playerState.value.copy(positionMs = 6_500L)
        advanceTimeBy(1_000L)
        assertThat(harness.transport.seeks).isEmpty()

        // Rapid manual seeks (> 3s jump) debounce into a single Spotify seek call.
        harness.playerState.value = harness.playerState.value.copy(positionMs = 45_000L)
        advanceTimeBy(200L)
        harness.playerState.value = harness.playerState.value.copy(positionMs = 62_000L)
        advanceTimeBy(700L)
        assertThat(harness.transport.seeks).containsExactly(62_000L)

        // Pausing Onyx Music pauses our virtual Spotify device.
        harness.playerState.value = harness.playerState.value.copy(isPlaying = false)
        runCurrent()
        assertThat(harness.transport.pauseCount).isEqualTo(1)
        assertThat(harness.engine.isMirroringActive.value).isFalse()
        assertThat(harness.engine.state.value.phase).isEqualTo(MirrorPhase.CONNECTED_IDLE)

        // Resuming Onyx Music resumes silent mirroring.
        harness.playerState.value = harness.playerState.value.copy(isPlaying = true, positionMs = 62_000L)
        runCurrent()
        assertThat(harness.engine.isMirroringActive.value).isTrue()
        assertThat(harness.engine.state.value.phase).isEqualTo(MirrorPhase.MIRRORING)
    }

    @Test
    fun localOnlyTrack_pausesSilentMirrorAndLogsLocalOnly() = runTest {
        val harness = MirrorTestHarness(testClock = { testScheduler.currentTime })
        harness.settings.value = SpotifyMirrorSettings(enabled = true)
        harness.spotifyConnected.value = true
        harness.engine.start(backgroundScope)
        runCurrent()

        // First play a catalog track so the virtual receiver is actively playing.
        harness.playerState.value = MusicPlayerState(
            current = CATALOG_TRACK,
            isPlaying = true,
            positionMs = 0L,
        )
        runCurrent()
        assertThat(harness.engine.isMirroringActive.value).isTrue()

        // Switch to an unreleased / non-catalog local track.
        harness.catalogClient.searchResults = emptyList()
        harness.playerState.value = MusicPlayerState(
            current = LOCAL_ONLY_TRACK,
            isPlaying = true,
            positionMs = 0L,
        )
        runCurrent()

        assertThat(harness.engine.state.value.phase).isEqualTo(MirrorPhase.PAUSED_LOCAL_ONLY)
        assertThat(harness.engine.isMirroringActive.value).isFalse()
        assertThat(harness.transport.pauseCount).isEqualTo(1)
        assertThat(harness.logDao.entries.last().isCatalogMatch).isFalse()
        assertThat(harness.logDao.entries.last().mirrorOutcome).isEqualTo(MirrorOutcome.LOCAL_ONLY)
    }

    @Test
    fun externalSpotifyDeviceActive_yieldsWithoutInterruptingAndResumesWhenIdle() = runTest {
        val harness = MirrorTestHarness(
            testClock = { testScheduler.currentTime },
            externalPollIntervalMs = 5_000L,
        )
        harness.transport.remotePlaybackContext = SpotifyPlaybackContext(
            isPlaying = true,
            activeDeviceId = "other-device-living-room",
            activeDeviceName = "Living Room Speaker",
            activeTrackUri = "spotify:track:9999999999999999999999",
            progressMs = 30_000L,
        )
        harness.settings.value = SpotifyMirrorSettings(enabled = true, pauseWhenExternalDeviceActive = true)
        harness.spotifyConnected.value = true
        harness.engine.start(backgroundScope)
        runCurrent()

        harness.playerState.value = MusicPlayerState(
            current = CATALOG_TRACK,
            isPlaying = true,
            positionMs = 0L,
        )
        runCurrent()

        // Must yield cleanly without calling playTrack or pause on the user's external speaker.
        assertThat(harness.engine.state.value.phase).isEqualTo(MirrorPhase.PAUSED_EXTERNAL_DEVICE)
        assertThat(harness.engine.state.value.externalDeviceName).isEqualTo("Living Room Speaker")
        assertThat(harness.engine.isMirroringActive.value).isFalse()
        assertThat(harness.transport.playedUris).isEmpty()
        assertThat(harness.transport.pauseCount).isEqualTo(0)

        // Once the external speaker stops playing, the periodic poll resumes silent mirroring.
        harness.transport.remotePlaybackContext = SpotifyPlaybackContext(
            isPlaying = false,
            activeDeviceId = null,
            activeDeviceName = null,
            activeTrackUri = null,
            progressMs = 0L,
        )
        advanceTimeBy(5_100L)
        runCurrent()

        assertThat(harness.engine.state.value.phase).isEqualTo(MirrorPhase.MIRRORING)
        assertThat(harness.engine.isMirroringActive.value).isTrue()
        assertThat(harness.transport.playedUris).hasSize(1)

        // If the user starts playing on another Spotify device mid-session, Onyx yields without pausing.
        harness.transport.remotePlaybackContext = SpotifyPlaybackContext(
            isPlaying = true,
            activeDeviceId = "desktop-macbook",
            activeDeviceName = "MacBook Pro",
            activeTrackUri = "spotify:track:1111111111111111111111",
            progressMs = 4_000L,
        )
        advanceTimeBy(5_100L)
        runCurrent()

        assertThat(harness.engine.state.value.phase).isEqualTo(MirrorPhase.PAUSED_EXTERNAL_DEVICE)
        assertThat(harness.engine.state.value.externalDeviceName).isEqualTo("MacBook Pro")
        assertThat(harness.engine.isMirroringActive.value).isFalse()
        assertThat(harness.transport.pauseCount).isEqualTo(0)
    }

    @Test
    fun manualUriOverride_promotesLocalOnlyTrackToActiveMirroring() = runTest {
        val harness = MirrorTestHarness(testClock = { testScheduler.currentTime })
        harness.catalogClient.searchResults = emptyList()
        harness.settings.value = SpotifyMirrorSettings(enabled = true)
        harness.spotifyConnected.value = true
        harness.engine.start(backgroundScope)
        runCurrent()

        harness.playerState.value = MusicPlayerState(
            current = LOCAL_ONLY_TRACK,
            isPlaying = true,
            positionMs = 12_000L,
        )
        runCurrent()
        assertThat(harness.engine.state.value.phase).isEqualTo(MirrorPhase.PAUSED_LOCAL_ONLY)

        val ok = harness.engine.applyManualUriOverride("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT")
        runCurrent()

        assertThat(ok).isTrue()
        assertThat(harness.engine.state.value.phase).isEqualTo(MirrorPhase.MIRRORING)
        assertThat(harness.engine.isMirroringActive.value).isTrue()
        assertThat(harness.transport.playedUris.last().first).isEqualTo("spotify:track:4cOdK2wGLETKBW3PvgPWqT")
    }

    @Test
    fun premiumRequired_transitionsToPremiumRequiredState() = runTest {
        val harness = MirrorTestHarness(testClock = { testScheduler.currentTime })
        harness.transport.nextPlayResult = TransportCommandResult.PremiumRequired
        harness.settings.value = SpotifyMirrorSettings(enabled = true)
        harness.spotifyConnected.value = true
        harness.engine.start(backgroundScope)
        runCurrent()

        harness.playerState.value = MusicPlayerState(
            current = CATALOG_TRACK,
            isPlaying = true,
            positionMs = 0L,
        )
        runCurrent()

        assertThat(harness.engine.state.value.phase).isEqualTo(MirrorPhase.PREMIUM_REQUIRED)
        assertThat(harness.engine.isMirroringActive.value).isFalse()
        assertThat(harness.detectedPremiumFlag).isFalse()
    }

    @Test
    fun receiverHtml_enforcesZeroVolumeMuteAndWebAudioGainZero() {
        val html = MutedWebPlaybackReceiver.buildReceiverHtml("Onyx Music")

        assertThat(html).contains("https://sdk.scdn.co/spotify-player.js")
        assertThat(html).contains("volume: 0.0")
        assertThat(html).contains("HTMLMediaElement.prototype")
        assertThat(html).contains("el.muted = true")
        assertThat(html).contains("gain.gain.value = 0")
        assertThat(html).contains("Onyx Music")
    }

    private class MirrorTestHarness(
        testClock: () -> Long,
        externalPollIntervalMs: Long = SpotifySilentMirrorEngine.DEFAULT_EXTERNAL_POLL_INTERVAL_MS,
    ) {
        val playerState = MutableStateFlow(MusicPlayerState())
        val settings = MutableStateFlow(SpotifyMirrorSettings())
        val spotifyConnected = MutableStateFlow(false)
        val matchDao = InMemoryTrackMatchDao()
        val logDao = InMemoryMirrorLogDao()
        val catalogClient = FakeCatalogClient(
            searchResults = listOf(
                SpotifyCatalogCandidate(
                    id = "0VjIjW4GlUZAMYd2vXMi3b",
                    uri = CATALOG_URI,
                    title = "Blinding Lights",
                    artists = listOf("The Weeknd"),
                    durationMs = 200_040L,
                ),
            ),
        )
        val matcher = SpotifyTrackMatcher(matchDao, catalogClient)
        val transport = FakeSilentSpotifyTransport()
        var detectedPremiumFlag: Boolean? = null

        val engine = SpotifySilentMirrorEngine(
            playerState = playerState,
            settingsFlow = settings,
            spotifyConnectedFlow = spotifyConnected,
            tokenProvider = { "test-token" },
            matcher = matcher,
            transport = transport,
            mirrorLogDao = logDao,
            onPremiumStatusDetected = { detectedPremiumFlag = it },
            clockMs = testClock,
            seekDebounceMs = 600L,
            externalPollIntervalMs = externalPollIntervalMs,
        )
    }

    private class FakeSilentSpotifyTransport : SilentSpotifyTransport {
        override val deviceName: String = SilentSpotifyTransport.DEFAULT_DEVICE_NAME
        private val _status = MutableStateFlow(TransportStatus(deviceName = deviceName))
        override val status: StateFlow<TransportStatus> = _status.asStateFlow()

        var connectCount = 0
        var disconnectCount = 0
        var pauseCount = 0
        var volumeZeroCount = 0
        val playedUris = mutableListOf<Pair<String, Long>>()
        val seeks = mutableListOf<Long>()
        var nextPlayResult: TransportCommandResult = TransportCommandResult.Success
        var remotePlaybackContext: SpotifyPlaybackContext? = SpotifyPlaybackContext(
            isPlaying = false,
            activeDeviceId = null,
            activeDeviceName = null,
            activeTrackUri = null,
            progressMs = 0L,
        )

        override suspend fun connect(tokenProvider: suspend () -> String?) {
            connectCount++
            _status.value = TransportStatus(
                state = TransportState.READY,
                deviceId = "onyx-virtual-device-id",
                deviceName = deviceName,
                detail = "Connected as \"$deviceName\"",
            )
        }

        override suspend fun disconnect() {
            disconnectCount++
            _status.value = TransportStatus(
                state = TransportState.DISCONNECTED,
                deviceId = null,
                deviceName = deviceName,
            )
        }

        override suspend fun playTrack(spotifyUri: String, positionMs: Long): TransportCommandResult {
            if (nextPlayResult is TransportCommandResult.Success) {
                playedUris += (spotifyUri to positionMs)
            }
            return nextPlayResult
        }

        override suspend fun pause(): TransportCommandResult {
            pauseCount++
            return TransportCommandResult.Success
        }

        override suspend fun seek(positionMs: Long): TransportCommandResult {
            seeks += positionMs
            return TransportCommandResult.Success
        }

        override suspend fun setVolumeZero(): TransportCommandResult {
            volumeZeroCount++
            return TransportCommandResult.Success
        }

        override suspend fun queryCurrentPlayback(): SpotifyPlaybackContext? = remotePlaybackContext
    }

    private class InMemoryMirrorLogDao : SpotifyMirrorLogDao {
        val entries = mutableListOf<SpotifyMirrorLogEntity>()
        private val flow = MutableStateFlow<List<SpotifyMirrorLogEntity>>(emptyList())

        override suspend fun insert(entity: SpotifyMirrorLogEntity): Long {
            val id = (entries.size + 1).toLong()
            entries += entity.copy(id = id)
            flow.value = entries.reversed()
            return id
        }

        override fun observeCatalogPlayCount(): Flow<Int> = flow.map { list ->
            list.count { it.isCatalogMatch && it.mirrorOutcome == MirrorOutcome.MIRRORED }
        }

        override fun observeLocalOnlyPlayCount(): Flow<Int> = flow.map { list ->
            list.count { !it.isCatalogMatch || it.mirrorOutcome == MirrorOutcome.LOCAL_ONLY }
        }

        override fun observeRecentLogs(limit: Int): Flow<List<SpotifyMirrorLogEntity>> =
            flow.map { it.take(limit) }

        override suspend fun clearAll() {
            entries.clear()
            flow.value = emptyList()
        }
    }

    private companion object {
        const val CATALOG_URI = "spotify:track:0VjIjW4GlUZAMYd2vXMi3b"
        val CATALOG_TRACK = PlayableTrack(
            title = "Blinding Lights (Official Video)",
            artist = "The Weeknd - Topic",
            durationMs = 200_000L,
        )
        val LOCAL_ONLY_TRACK = PlayableTrack(
            title = "Unreleased Bedroom Demo",
            artist = "Indie Friend",
            durationMs = 175_000L,
        )
    }
}
