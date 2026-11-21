package com.lastwave.app.data.spotify.mirror

import android.content.Context
import com.lastwave.app.data.spotify.SpotifyAuthRepository
import com.lastwave.app.data.spotify.SpotifyPreferences
import com.lastwave.app.diagnostics.AppLog
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.MusicPlayerState
import com.lastwave.app.playback.PlayableTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

enum class MirrorPhase {
    DISABLED,
    NOT_SIGNED_IN,
    CONNECTING,
    CONNECTED_IDLE,
    MIRRORING,
    PAUSED_LOCAL_ONLY,
    PAUSED_EXTERNAL_DEVICE,
    PREMIUM_REQUIRED,
    ERROR,
}

data class SpotifyMirrorState(
    val phase: MirrorPhase = MirrorPhase.DISABLED,
    val deviceName: String = SilentSpotifyTransport.DEFAULT_DEVICE_NAME,
    val deviceId: String? = null,
    val externalDeviceName: String? = null,
    val currentSourceTrack: PlayableTrack? = null,
    val lastMatch: SpotifyTrackMatchEntity? = null,
    val statusLabel: String = "Off",
    val lastError: String? = null,
)

/**
 * Pure coroutine-driven mirror state machine, decoupled from Android service/Hilt wiring so every
 * transition (catalog match, local-only fallback, pause/resume, debounced seeks, and external
 * Spotify Connect device yield) can be verified deterministically in JVM unit tests.
 */
class SpotifySilentMirrorEngine(
    private val playerState: StateFlow<MusicPlayerState>,
    private val settingsFlow: Flow<SpotifyMirrorSettings>,
    private val spotifyConnectedFlow: Flow<Boolean>,
    private val tokenProvider: suspend () -> String?,
    private val matcher: SpotifyTrackMatcher,
    private val transport: SilentSpotifyTransport,
    private val mirrorLogDao: SpotifyMirrorLogDao,
    private val onDiagnosticsChanged: ((SpotifyMirrorState) -> Unit)? = null,
    private val onPremiumStatusDetected: (suspend (Boolean) -> Unit)? = null,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val seekDebounceMs: Long = DEFAULT_SEEK_DEBOUNCE_MS,
    private val externalPollIntervalMs: Long = DEFAULT_EXTERNAL_POLL_INTERVAL_MS,
) {
    private val _state = MutableStateFlow(SpotifyMirrorState(deviceName = transport.deviceName))
    val state: StateFlow<SpotifyMirrorState> = _state.asStateFlow()

    private val _isMirroringActive = MutableStateFlow(false)
    /**
     * Lightweight public flag for downstream consumers (such as OMP-8's scrobbler) to avoid
     * double-scrobbling plays mirrored silently on Spotify.
     */
    val isMirroringActive: StateFlow<Boolean> = _isMirroringActive.asStateFlow()

    private val mutex = Mutex()

    private var runnerJob: Job? = null
    private var seekJob: Job? = null
    private var pollJob: Job? = null
    private var activeScope: CoroutineScope? = null

    private var currentSettings: SpotifyMirrorSettings = SpotifyMirrorSettings()
    private var spotifyConnected: Boolean = false
    private var transportConnected: Boolean = false

    private var ownedActivePlayback: Boolean = false
    private var activeTrackKey: String? = null
    private var activeMatch: SpotifyMatchResult? = null
    private var loggedPlayInstanceKey: String? = null
    private var lastSyncedPositionMs: Long = 0L
    private var lastSyncedAtMs: Long = 0L
    private var lastObservedPlaying: Boolean = false
    private var lastExternalCheckAtMs: Long = 0L
    private var lastExternalContext: SpotifyPlaybackContext? = null

    private var isStarted: Boolean = false

    fun start(scope: CoroutineScope) {
        if (isStarted && activeScope === scope && runnerJob?.isActive == true) return
        stop()
        isStarted = true
        activeScope = scope
        runnerJob = scope.launch {
            launch {
                combine(settingsFlow, spotifyConnectedFlow) { settings, connected ->
                    settings to connected
                }
                    .distinctUntilChanged()
                    .collect { (settings, connected) ->
                        mutex.withLock {
                            onConfigurationChanged(settings, connected)
                        }
                    }
            }

            launch {
                transport.status.collect { transportStatus ->
                    mutex.withLock {
                        onTransportStatusChanged(transportStatus)
                    }
                }
            }

            launch {
                playerState.collect { playback ->
                    mutex.withLock {
                        onPlaybackStateChanged(playback)
                    }
                }
            }
        }

        pollJob = scope.launch {
            while (isActive) {
                delay(externalPollIntervalMs)
                mutex.withLock {
                    pollExternalDeviceStatus()
                }
            }
        }
    }

    fun stop() {
        isStarted = false
        seekJob?.cancel()
        seekJob = null
        pollJob?.cancel()
        pollJob = null
        runnerJob?.cancel()
        runnerJob = null
        activeScope = null
    }

    suspend fun shutdown() {
        stop()
        mutex.withLock {
            if (ownedActivePlayback) {
                runCatching { transport.pause() }
                ownedActivePlayback = false
            }
            if (transportConnected) {
                runCatching { transport.disconnect() }
                transportConnected = false
            }
            setMirroringState(
                _state.value.copy(
                    phase = if (currentSettings.enabled) MirrorPhase.CONNECTING else MirrorPhase.DISABLED,
                    statusLabel = if (currentSettings.enabled) "Disconnected" else "Off",
                ),
            )
        }
    }

    suspend fun reconnect() {
        mutex.withLock {
            if (!currentSettings.enabled || !spotifyConnected) return@withLock
            if (transportConnected) {
                runCatching { transport.disconnect() }
                transportConnected = false
            }
            ownedActivePlayback = false
            ensureTransportConnected()
        }
    }

    suspend fun rematchCurrentTrack(): SpotifyMatchResult? = mutex.withLock {
        val track = playerState.value.current ?: _state.value.currentSourceTrack ?: return@withLock null
        matcher.clearMatchForTrack(track)
        activeTrackKey = null
        activeMatch = null
        loggedPlayInstanceKey = null
        val result = resolveAndApply(track, playerState.value, forceRefresh = true)
        result
    }

    suspend fun applyManualUriOverride(rawUriOrUrl: String): Boolean = mutex.withLock {
        val track = playerState.value.current ?: _state.value.currentSourceTrack ?: return@withLock false
        val matched = matcher.setManualOverride(track, rawUriOrUrl, nowMs = clockMs()) ?: return@withLock false
        activeTrackKey = matched.trackKey
        activeMatch = matched
        loggedPlayInstanceKey = null
        applyResolvedMatch(track, matched, playerState.value)
        true
    }

    suspend fun markCurrentTrackLocalOnly(): Boolean = mutex.withLock {
        val track = playerState.value.current ?: _state.value.currentSourceTrack ?: return@withLock false
        val localOnly = matcher.markAsLocalOnly(track, nowMs = clockMs())
        activeTrackKey = localOnly.trackKey
        activeMatch = localOnly
        loggedPlayInstanceKey = null
        applyResolvedMatch(track, localOnly, playerState.value)
        true
    }

    private suspend fun onConfigurationChanged(
        settings: SpotifyMirrorSettings,
        connected: Boolean,
    ) {
        currentSettings = settings
        spotifyConnected = connected

        if (!settings.enabled) {
            pauseOwnedIfActive()
            if (transportConnected) {
                runCatching { transport.disconnect() }
                transportConnected = false
            }
            activeTrackKey = null
            activeMatch = null
            setMirroringState(
                _state.value.copy(
                    phase = MirrorPhase.DISABLED,
                    deviceId = null,
                    externalDeviceName = null,
                    statusLabel = "Off",
                    lastError = null,
                ),
            )
            return
        }

        if (!connected) {
            pauseOwnedIfActive()
            if (transportConnected) {
                runCatching { transport.disconnect() }
                transportConnected = false
            }
            setMirroringState(
                _state.value.copy(
                    phase = MirrorPhase.NOT_SIGNED_IN,
                    deviceId = null,
                    externalDeviceName = null,
                    statusLabel = "Sign in to Spotify to enable",
                    lastError = null,
                ),
            )
            return
        }

        ensureTransportConnected()
        val playback = playerState.value
        val current = playback.current
        if (current != null && transport.status.value.state == TransportState.READY) {
            resolveAndApply(current, playback, forceRefresh = false)
        }
    }

    private suspend fun ensureTransportConnected() {
        if (transportConnected && transport.status.value.state == TransportState.READY) return
        transportConnected = true
        setMirroringState(
            _state.value.copy(
                phase = MirrorPhase.CONNECTING,
                statusLabel = "Connecting \"${transport.deviceName}\"...",
                lastError = null,
            ),
        )
        runCatching { transport.connect(tokenProvider) }.onFailure { error ->
            if (error is CancellationException) throw error
            AppLog.w(TAG, "Silent transport connect failed", error)
            setMirroringState(
                _state.value.copy(
                    phase = MirrorPhase.ERROR,
                    statusLabel = "Connection failed",
                    lastError = error.message,
                ),
            )
        }
    }

    private suspend fun onTransportStatusChanged(transportStatus: TransportStatus) {
        if (!currentSettings.enabled || !spotifyConnected) return

        when (transportStatus.state) {
            TransportState.READY -> {
                runCatching { onPremiumStatusDetected?.invoke(true) }
                val playback = playerState.value
                val track = playback.current
                if (track != null && playback.isPlaying) {
                    resolveAndApply(track, playback, forceRefresh = false)
                } else {
                    setMirroringState(
                        _state.value.copy(
                            phase = MirrorPhase.CONNECTED_IDLE,
                            deviceId = transportStatus.deviceId,
                            statusLabel = "Connected as \"${transport.deviceName}\"",
                            lastError = null,
                        ),
                    )
                }
            }
            TransportState.CONNECTING -> {
                ownedActivePlayback = false
                setMirroringState(
                    _state.value.copy(
                        phase = MirrorPhase.CONNECTING,
                        deviceId = null,
                        statusLabel = "Connecting \"${transport.deviceName}\"...",
                    ),
                )
            }
            TransportState.UNAVAILABLE, TransportState.ERROR -> {
                ownedActivePlayback = false
                if (transportStatus.premiumRequired) {
                    runCatching { onPremiumStatusDetected?.invoke(false) }
                    setMirroringState(
                        _state.value.copy(
                            phase = MirrorPhase.PREMIUM_REQUIRED,
                            deviceId = null,
                            statusLabel = "Spotify Premium required",
                            lastError = transportStatus.detail,
                        ),
                    )
                } else {
                    setMirroringState(
                        _state.value.copy(
                            phase = MirrorPhase.ERROR,
                            deviceId = null,
                            statusLabel = transportStatus.detail ?: "Receiver unavailable",
                            lastError = transportStatus.detail,
                        ),
                    )
                }
            }
            TransportState.DISCONNECTED -> {
                ownedActivePlayback = false
                setMirroringState(
                    _state.value.copy(
                        phase = MirrorPhase.CONNECTING,
                        deviceId = null,
                        statusLabel = "Disconnected",
                    ),
                )
            }
        }
    }

    private suspend fun onPlaybackStateChanged(playback: MusicPlayerState) {
        if (!currentSettings.enabled || !spotifyConnected) return
        val transportStatus = transport.status.value
        val track = playback.current

        if (track == null) {
            seekJob?.cancel()
            pauseOwnedIfActive()
            activeTrackKey = null
            activeMatch = null
            lastObservedPlaying = false
            if (transportStatus.state == TransportState.READY) {
                setMirroringState(
                    _state.value.copy(
                        phase = MirrorPhase.CONNECTED_IDLE,
                        deviceId = transportStatus.deviceId,
                        currentSourceTrack = null,
                        statusLabel = "Connected as \"${transport.deviceName}\"",
                    ),
                )
            }
            return
        }

        val trackKey = SpotifyTrackNormalizer.canonicalTrackKey(track)
        val trackChanged = trackKey != activeTrackKey
        if (trackChanged) {
            seekJob?.cancel()
            loggedPlayInstanceKey = null
            lastObservedPlaying = playback.isPlaying
            resolveAndApply(track, playback, forceRefresh = false)
            return
        }

        val match = activeMatch ?: return

        // Handle play/pause transitions on the same track.
        if (playback.isPlaying != lastObservedPlaying) {
            lastObservedPlaying = playback.isPlaying
            if (playback.isPlaying) {
                applyResolvedMatch(track, match, playback)
            } else {
                seekJob?.cancel()
                pauseOwnedIfActive()
                if (_state.value.phase == MirrorPhase.MIRRORING) {
                    setMirroringState(
                        _state.value.copy(
                            phase = MirrorPhase.CONNECTED_IDLE,
                            deviceId = transportStatus.deviceId,
                            statusLabel = "Connected as \"${transport.deviceName}\"",
                        ),
                    )
                }
            }
            return
        }

        // Detect manual seeks (> 3s jump) while actively mirroring.
        if (playback.isPlaying && ownedActivePlayback && _state.value.phase == MirrorPhase.MIRRORING) {
            val now = clockMs()
            val expectedPos = lastSyncedPositionMs + (now - lastSyncedAtMs).coerceAtLeast(0L)
            val drift = abs(playback.positionMs - expectedPos)
            if (drift > SEEK_DRIFT_THRESHOLD_MS) {
                scheduleDebouncedSeek(playback.positionMs)
            }
        }
    }

    private suspend fun resolveAndApply(
        track: PlayableTrack,
        playback: MusicPlayerState,
        forceRefresh: Boolean,
    ): SpotifyMatchResult {
        val result = try {
            matcher.resolveTrack(
                track = track,
                minConfidence = currentSettings.minMatchConfidence,
                forceRefresh = forceRefresh,
                nowMs = clockMs(),
            )
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.w(TAG, "Track matching failed for '${track.title}'", e)
            val fallbackEntity = SpotifyTrackMatchEntity(
                trackKey = SpotifyTrackNormalizer.canonicalTrackKey(track),
                sourceTitle = track.title,
                sourceArtist = track.artist,
                spotifyUri = null,
                isrc = null,
                matchedTitle = null,
                matchedArtist = null,
                confidence = 0f,
                isCatalogMatch = false,
                resolvedAtMs = clockMs(),
            )
            SpotifyMatchResult.LocalOnly(
                trackKey = fallbackEntity.trackKey,
                entity = fallbackEntity,
                reason = "Lookup failed",
                fromCache = false,
            )
        }

        activeTrackKey = result.trackKey
        activeMatch = result
        applyResolvedMatch(track, result, playback)
        return result
    }

    private suspend fun applyResolvedMatch(
        track: PlayableTrack,
        match: SpotifyMatchResult,
        playback: MusicPlayerState,
    ) {
        val transportStatus = transport.status.value
        val ourDeviceId = transportStatus.deviceId

        when (match) {
            is SpotifyMatchResult.LocalOnly -> {
                pauseOwnedIfActive()
                recordPlayLogOnce(track, match.entity, MirrorOutcome.LOCAL_ONLY)
                setMirroringState(
                    _state.value.copy(
                        phase = MirrorPhase.PAUSED_LOCAL_ONLY,
                        deviceId = ourDeviceId,
                        externalDeviceName = null,
                        currentSourceTrack = track,
                        lastMatch = match.entity,
                        statusLabel = "Local-only track",
                        lastError = null,
                    ),
                )
            }

            is SpotifyMatchResult.Matched -> {
                val uri = match.entity.spotifyUri
                if (uri.isNullOrBlank()) {
                    pauseOwnedIfActive()
                    return
                }
                if (!playback.isPlaying) {
                    pauseOwnedIfActive()
                    setMirroringState(
                        _state.value.copy(
                            phase = if (transportStatus.state == TransportState.READY) {
                                MirrorPhase.CONNECTED_IDLE
                            } else {
                                MirrorPhase.CONNECTING
                            },
                            deviceId = ourDeviceId,
                            currentSourceTrack = track,
                            lastMatch = match.entity,
                            statusLabel = if (transportStatus.state == TransportState.READY) {
                                "Connected as \"${transport.deviceName}\""
                            } else {
                                "Connecting \"${transport.deviceName}\"..."
                            },
                        ),
                    )
                    return
                }

                if (transportStatus.state != TransportState.READY || ourDeviceId.isNullOrBlank()) {
                    setMirroringState(
                        _state.value.copy(
                            phase = MirrorPhase.CONNECTING,
                            currentSourceTrack = track,
                            lastMatch = match.entity,
                            statusLabel = "Waiting for \"${transport.deviceName}\"",
                        ),
                    )
                    return
                }

                // Avoid issuing redundant play commands if we are already mirroring this exact URI.
                if (ownedActivePlayback &&
                    _state.value.phase == MirrorPhase.MIRRORING &&
                    _state.value.lastMatch?.spotifyUri == uri &&
                    _state.value.deviceId == ourDeviceId
                ) {
                    return
                }

                // Respect external Spotify playback before taking Connect focus.
                if (currentSettings.pauseWhenExternalDeviceActive) {
                    val externalDevice = detectActiveExternalDevice(ourDeviceId, forceQuery = false)
                    if (externalDevice != null) {
                        ownedActivePlayback = false
                        recordPlayLogOnce(track, match.entity, MirrorOutcome.EXTERNAL_DEVICE_YIELD)
                        setMirroringState(
                            _state.value.copy(
                                phase = MirrorPhase.PAUSED_EXTERNAL_DEVICE,
                                deviceId = ourDeviceId,
                                externalDeviceName = externalDevice,
                                currentSourceTrack = track,
                                lastMatch = match.entity,
                                statusLabel = "Paused — external device active",
                                lastError = null,
                            ),
                        )
                        return
                    }
                }

                val pos = playback.positionMs.coerceAtLeast(0L)
                when (val result = transport.playTrack(uri, pos)) {
                    TransportCommandResult.Success -> {
                        runCatching { transport.setVolumeZero() }
                        ownedActivePlayback = true
                        lastSyncedPositionMs = pos
                        lastSyncedAtMs = clockMs()
                        recordPlayLogOnce(track, match.entity, MirrorOutcome.MIRRORED)
                        setMirroringState(
                            _state.value.copy(
                                phase = MirrorPhase.MIRRORING,
                                deviceId = ourDeviceId,
                                externalDeviceName = null,
                                currentSourceTrack = track,
                                lastMatch = match.entity,
                                statusLabel = "Connected as \"${transport.deviceName}\"",
                                lastError = null,
                            ),
                        )
                    }
                    TransportCommandResult.PremiumRequired -> {
                        ownedActivePlayback = false
                        runCatching { onPremiumStatusDetected?.invoke(false) }
                        setMirroringState(
                            _state.value.copy(
                                phase = MirrorPhase.PREMIUM_REQUIRED,
                                deviceId = ourDeviceId,
                                currentSourceTrack = track,
                                lastMatch = match.entity,
                                statusLabel = "Spotify Premium required",
                                lastError = "Spotify Connect playback requires Spotify Premium.",
                            ),
                        )
                    }
                    TransportCommandResult.Unauthorized -> {
                        ownedActivePlayback = false
                        setMirroringState(
                            _state.value.copy(
                                phase = MirrorPhase.ERROR,
                                deviceId = ourDeviceId,
                                currentSourceTrack = track,
                                lastMatch = match.entity,
                                statusLabel = "Spotify session expired",
                                lastError = "Reconnect your Spotify account to resume mirroring.",
                            ),
                        )
                    }
                    TransportCommandResult.NoActiveDevice -> {
                        ownedActivePlayback = false
                        setMirroringState(
                            _state.value.copy(
                                phase = MirrorPhase.CONNECTING,
                                deviceId = null,
                                currentSourceTrack = track,
                                lastMatch = match.entity,
                                statusLabel = "Reconnecting \"${transport.deviceName}\"...",
                            ),
                        )
                    }
                    is TransportCommandResult.Failed -> {
                        ownedActivePlayback = false
                        setMirroringState(
                            _state.value.copy(
                                phase = MirrorPhase.ERROR,
                                deviceId = ourDeviceId,
                                currentSourceTrack = track,
                                lastMatch = match.entity,
                                statusLabel = "Mirror playback failed",
                                lastError = result.message,
                            ),
                        )
                    }
                }
            }
        }
    }

    private suspend fun pollExternalDeviceStatus() {
        if (!currentSettings.enabled || !spotifyConnected || !currentSettings.pauseWhenExternalDeviceActive) {
            return
        }
        val ourDeviceId = transport.status.value.deviceId?.takeIf { it.isNotBlank() } ?: return
        val phase = _state.value.phase
        if (phase != MirrorPhase.MIRRORING && phase != MirrorPhase.PAUSED_EXTERNAL_DEVICE) {
            return
        }

        val externalDevice = detectActiveExternalDevice(ourDeviceId, forceQuery = true)
        if (externalDevice != null && phase == MirrorPhase.MIRRORING) {
            // User started playing on another Spotify device: yield immediately without sending pause.
            ownedActivePlayback = false
            setMirroringState(
                _state.value.copy(
                    phase = MirrorPhase.PAUSED_EXTERNAL_DEVICE,
                    externalDeviceName = externalDevice,
                    statusLabel = "Paused — external device active",
                ),
            )
        } else if (externalDevice == null && phase == MirrorPhase.PAUSED_EXTERNAL_DEVICE) {
            val playback = playerState.value
            val track = playback.current
            val match = activeMatch
            if (track != null && playback.isPlaying && match is SpotifyMatchResult.Matched) {
                applyResolvedMatch(track, match, playback)
            }
        }
    }

    private suspend fun detectActiveExternalDevice(
        ourDeviceId: String,
        forceQuery: Boolean,
    ): String? {
        val now = clockMs()
        val cached = lastExternalContext
        val context = if (!forceQuery && cached != null && (now - lastExternalCheckAtMs) < EXTERNAL_CHECK_CACHE_MS) {
            cached
        } else {
            val queried = runCatching { transport.queryCurrentPlayback() }.getOrNull()
            lastExternalContext = queried
            lastExternalCheckAtMs = now
            queried
        } ?: return null

        val activeId = context.activeDeviceId
        if (context.isPlaying && !activeId.isNullOrBlank() && activeId != ourDeviceId) {
            return context.activeDeviceName ?: "Another Spotify device"
        }
        return null
    }

    private fun scheduleDebouncedSeek(targetPositionMs: Long) {
        val scope = activeScope ?: return
        seekJob?.cancel()
        seekJob = scope.launch {
            delay(seekDebounceMs)
            mutex.withLock {
                if (!ownedActivePlayback || _state.value.phase != MirrorPhase.MIRRORING) return@withLock
                val clamped = targetPositionMs.coerceAtLeast(0L)
                when (transport.seek(clamped)) {
                    TransportCommandResult.Success -> {
                        lastSyncedPositionMs = clamped
                        lastSyncedAtMs = clockMs()
                    }
                    else -> Unit
                }
            }
        }
    }

    private suspend fun pauseOwnedIfActive() {
        if (!ownedActivePlayback) return
        ownedActivePlayback = false
        runCatching { transport.pause() }
    }

    private suspend fun recordPlayLogOnce(
        track: PlayableTrack,
        entity: SpotifyTrackMatchEntity,
        outcome: String,
    ) {
        val instanceKey = "${entity.trackKey}|$outcome"
        if (loggedPlayInstanceKey == instanceKey) return
        loggedPlayInstanceKey = instanceKey
        runCatching {
            mirrorLogDao.insert(
                SpotifyMirrorLogEntity(
                    trackKey = entity.trackKey,
                    sourceTitle = track.title,
                    sourceArtist = track.artist,
                    spotifyUri = entity.spotifyUri,
                    matchedTitle = entity.matchedTitle,
                    matchedArtist = entity.matchedArtist,
                    confidence = entity.confidence,
                    isCatalogMatch = entity.isCatalogMatch,
                    mirrorOutcome = outcome,
                    playedAtMs = clockMs(),
                ),
            )
        }
    }

    private fun setMirroringState(next: SpotifyMirrorState) {
        _state.value = next
        _isMirroringActive.value = (next.phase == MirrorPhase.MIRRORING)
        onDiagnosticsChanged?.invoke(next)
    }

    companion object {
        private const val TAG = "SpotifySilentMirror"
        const val SEEK_DRIFT_THRESHOLD_MS = 3_000L
        const val DEFAULT_SEEK_DEBOUNCE_MS = 600L
        const val DEFAULT_EXTERNAL_POLL_INTERVAL_MS = 20_000L
        const val EXTERNAL_CHECK_CACHE_MS = 4_000L
    }
}

/**
 * Application-scoped manager coordinating Spotify silent Connect mirroring alongside
 * [MusicPlaybackService] and [MusicPlayer].
 */
@Singleton
class SpotifySilentMirrorManager @Inject constructor(
    musicPlayer: MusicPlayer,
    private val authRepository: SpotifyAuthRepository,
    private val spotifyPreferences: SpotifyPreferences,
    private val mirrorPreferences: SpotifyMirrorPreferences,
    private val trackMatcher: SpotifyTrackMatcher,
    private val transport: SilentSpotifyTransport,
    private val mirrorLogDao: SpotifyMirrorLogDao,
) {
    private val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val engine = SpotifySilentMirrorEngine(
        playerState = musicPlayer.state,
        settingsFlow = mirrorPreferences.settings,
        spotifyConnectedFlow = spotifyPreferences.isConnected,
        tokenProvider = { authRepository.getPersonalToken() },
        matcher = trackMatcher,
        transport = transport,
        mirrorLogDao = mirrorLogDao,
        onDiagnosticsChanged = { mirrorState ->
            mirrorPreferences.updateDiagnostics { prev ->
                prev.copy(
                    transportState = transport.status.value.state,
                    deviceName = mirrorState.deviceName,
                    deviceId = mirrorState.deviceId,
                    activeExternalDeviceName = mirrorState.externalDeviceName,
                    lastSourceTitle = mirrorState.currentSourceTrack?.title ?: prev.lastSourceTitle,
                    lastSourceArtist = mirrorState.currentSourceTrack?.artist ?: prev.lastSourceArtist,
                    lastMatchedUri = mirrorState.lastMatch?.spotifyUri,
                    lastConfidence = mirrorState.lastMatch?.confidence ?: 0f,
                    lastIsCatalogMatch = mirrorState.lastMatch?.isCatalogMatch,
                    lastNote = mirrorState.statusLabel,
                    lastError = mirrorState.lastError,
                )
            }
        },
        onPremiumStatusDetected = { isPremium ->
            spotifyPreferences.setStreamingScopeGranted(isPremium)
            spotifyPreferences.saveAccountProduct(if (isPremium) "premium" else "free")
        },
    )

    /** Public signal read by OMP-8's scrobbler to prevent double-scrobbling mirrored plays. */
    val isMirroringActive: StateFlow<Boolean> = engine.isMirroringActive

    /** Live mirror state for Settings UI and diagnostics. */
    val state: StateFlow<SpotifyMirrorState> = engine.state

    @Volatile private var attachedToService: Boolean = false

    fun attach(serviceContext: Context, serviceScope: CoroutineScope) {
        attachedToService = true
        transport.bindHostContext(serviceContext)
        engine.start(serviceScope)
        AppLog.i(TAG, "Attached Spotify silent mirror to MusicPlaybackService")
    }

    /**
     * Starts observing preferences and playback when the user opens Silent Mirror settings
     * before [MusicPlaybackService] has been created. Once the service attaches, ownership
     * transfers cleanly to the service lifecycle.
     */
    fun ensureObserving() {
        if (!attachedToService) {
            engine.start(fallbackScope)
        }
    }

    fun detach() {
        attachedToService = false
        engine.stop()
        transport.bindHostContext(null)
        fallbackScope.launch {
            engine.shutdown()
        }
        AppLog.i(TAG, "Detached Spotify silent mirror from MusicPlaybackService")
    }

    suspend fun reconnect() = engine.reconnect()

    suspend fun rematchCurrentTrack(): SpotifyMatchResult? = engine.rematchCurrentTrack()

    suspend fun applyManualUriOverride(rawUriOrUrl: String): Boolean =
        engine.applyManualUriOverride(rawUriOrUrl)

    suspend fun markCurrentTrackLocalOnly(): Boolean = engine.markCurrentTrackLocalOnly()

    suspend fun clearMatchCache() = trackMatcher.clearAllCachedMatches()

    private companion object {
        const val TAG = "SpotifySilentMirrorMgr"
    }
}
