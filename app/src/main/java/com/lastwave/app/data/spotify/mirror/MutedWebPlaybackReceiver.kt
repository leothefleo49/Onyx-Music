package com.lastwave.app.data.spotify.mirror

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lastwave.app.diagnostics.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service-managed offscreen Android [WebView] hosting Spotify's official Web Playback SDK
 * (`sdk.scdn.co/spotify-player.js`) as Connect device `"Onyx Music"` at permanent zero volume.
 *
 * Audio isolation guarantees:
 * 1. Initializes Spotify.Player with `volume: 0.0`.
 * 2. Patches `HTMLMediaElement` prototypes and DOM listeners before the SDK loads so every
 *    `<audio>`/`<video>` element stays permanently `muted = true` and `volume = 0`, which also
 *    prevents Chromium's Android media pipeline from requesting Android AudioFocus away from
 *    Onyx Music's Media3/Oboe player.
 * 3. Wraps `AudioContext` / `webkitAudioContext` destination connections through a permanent
 *    zero-gain node as defense-in-depth.
 * 4. Grants only [PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID] (Widevine EME) and denies all
 *    other browser permissions.
 */
@Singleton
class MutedWebPlaybackReceiver @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val okHttpClient: OkHttpClient,
) : SilentSpotifyTransport {

    override val deviceName: String = SilentSpotifyTransport.DEFAULT_DEVICE_NAME

    private val _status = MutableStateFlow(TransportStatus(deviceName = deviceName))
    override val status: StateFlow<TransportStatus> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Volatile private var hostContextRef: WeakReference<Context>? = null
    @Volatile private var tokenProvider: (suspend () -> String?)? = null
    private var webView: WebView? = null

    override fun bindHostContext(context: Context?) {
        hostContextRef = context?.let { WeakReference(it) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun connect(tokenProvider: suspend () -> String?) {
        this.tokenProvider = tokenProvider
        val currentState = _status.value.state
        if (currentState == TransportState.READY && webView != null && !_status.value.deviceId.isNullOrBlank()) {
            return
        }
        _status.value = TransportStatus(
            state = TransportState.CONNECTING,
            deviceId = _status.value.deviceId,
            deviceName = deviceName,
            detail = "Initializing silent Connect receiver",
        )

        withContext(Dispatchers.Main.immediate) {
            destroyWebViewOnMain()
            val ctx = hostContextRef?.get() ?: appContext
            try {
                val wv = WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.userAgentString = DESKTOP_WEB_UA
                    isSoundEffectsEnabled = false

                    webViewClient = object : WebViewClient() {}
                    webChromeClient = object : WebChromeClient() {
                        override fun onPermissionRequest(request: PermissionRequest) {
                            val protectedMedia = request.resources.filter {
                                it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
                            }
                            if (protectedMedia.isNotEmpty()) {
                                request.grant(protectedMedia.toTypedArray())
                            } else {
                                request.deny()
                            }
                        }
                    }

                    addJavascriptInterface(OnyxSpotifyBridge(), JS_BRIDGE_NAME)
                    loadDataWithBaseURL(
                        SPOTIFY_ORIGIN_BASE_URL,
                        buildReceiverHtml(deviceName),
                        "text/html",
                        "utf-8",
                        null,
                    )
                }
                webView = wv
                AppLog.i(TAG, "Mounted offscreen Spotify Web Playback SDK receiver")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.w(TAG, "Offscreen WebView unavailable for silent Spotify receiver", e)
                _status.value = TransportStatus(
                    state = TransportState.UNAVAILABLE,
                    deviceName = deviceName,
                    detail = "WebView DRM runtime unavailable on this device",
                )
            }
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.Main.immediate) {
            runCatching {
                webView?.evaluateJavascript(
                    "if (window.__onyxDisconnect) { window.__onyxDisconnect(); }",
                    null,
                )
            }
            destroyWebViewOnMain()
        }
        tokenProvider = null
        _status.value = TransportStatus(
            state = TransportState.DISCONNECTED,
            deviceName = deviceName,
            detail = "Disconnected",
        )
    }

    override suspend fun playTrack(spotifyUri: String, positionMs: Long): TransportCommandResult {
        val deviceId = _status.value.deviceId?.takeIf { it.isNotBlank() }
            ?: return TransportCommandResult.NoActiveDevice
        val payload = json.encodeToString(
            PlayTrackRequestDto(
                uris = listOf(spotifyUri),
                position_ms = positionMs.coerceAtLeast(0L),
            ),
        )
        val url = "$PLAYER_ENDPOINT/play".toHttpUrl().newBuilder()
            .addQueryParameter("device_id", deviceId)
            .build()
        val result = executePut(url.toString(), payload)
        if (result is TransportCommandResult.Success) {
            enforceWebViewZeroVolume()
        }
        return result
    }

    override suspend fun pause(): TransportCommandResult {
        val deviceId = _status.value.deviceId?.takeIf { it.isNotBlank() }
            ?: return TransportCommandResult.NoActiveDevice
        val url = "$PLAYER_ENDPOINT/pause".toHttpUrl().newBuilder()
            .addQueryParameter("device_id", deviceId)
            .build()
        return executePut(url.toString(), "")
    }

    override suspend fun seek(positionMs: Long): TransportCommandResult {
        val deviceId = _status.value.deviceId?.takeIf { it.isNotBlank() }
            ?: return TransportCommandResult.NoActiveDevice
        val url = "$PLAYER_ENDPOINT/seek".toHttpUrl().newBuilder()
            .addQueryParameter("position_ms", positionMs.coerceAtLeast(0L).toString())
            .addQueryParameter("device_id", deviceId)
            .build()
        return executePut(url.toString(), "")
    }

    override suspend fun setVolumeZero(): TransportCommandResult {
        enforceWebViewZeroVolume()
        val deviceId = _status.value.deviceId?.takeIf { it.isNotBlank() }
            ?: return TransportCommandResult.NoActiveDevice
        val url = "$PLAYER_ENDPOINT/volume".toHttpUrl().newBuilder()
            .addQueryParameter("volume_percent", "0")
            .addQueryParameter("device_id", deviceId)
            .build()
        return executePut(url.toString(), "")
    }

    override suspend fun queryCurrentPlayback(): SpotifyPlaybackContext? = withContext(Dispatchers.IO) {
        val token = fetchTokenOrNull() ?: return@withContext null
        val request = Request.Builder()
            .url(PLAYER_ENDPOINT)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .get()
            .build()
        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (response.code == 204) {
                    return@withContext SpotifyPlaybackContext(
                        isPlaying = false,
                        activeDeviceId = null,
                        activeDeviceName = null,
                        activeTrackUri = null,
                        progressMs = 0L,
                    )
                }
                if (!response.isSuccessful) {
                    return@withContext null
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@withContext null
                val dto = json.decodeFromString<PlayerStateResponseDto>(body)
                SpotifyPlaybackContext(
                    isPlaying = dto.is_playing == true,
                    activeDeviceId = dto.device?.id?.takeIf { it.isNotBlank() },
                    activeDeviceName = dto.device?.name?.takeIf { it.isNotBlank() },
                    activeTrackUri = dto.item?.uri?.takeIf { it.isNotBlank() },
                    progressMs = dto.progress_ms ?: 0L,
                )
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.w(TAG, "Failed querying Spotify active playback state: ${e.message}")
            null
        }
    }

    private suspend fun executePut(url: String, jsonBody: String): TransportCommandResult =
        withContext(Dispatchers.IO) {
            val token = fetchTokenOrNull() ?: return@withContext TransportCommandResult.Unauthorized
            val body = jsonBody.toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .put(body)
                .build()
            try {
                okHttpClient.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful || response.code == 204 -> TransportCommandResult.Success
                        response.code == 401 -> TransportCommandResult.Unauthorized
                        response.code == 403 -> {
                            val err = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                            if (err.contains("PREMIUM_REQUIRED", ignoreCase = true) ||
                                err.contains("premium", ignoreCase = true)
                            ) {
                                TransportCommandResult.PremiumRequired
                            } else {
                                TransportCommandResult.Failed(403, "Spotify rejected Connect command (403)")
                            }
                        }
                        response.code == 404 -> TransportCommandResult.NoActiveDevice
                        else -> TransportCommandResult.Failed(
                            response.code,
                            "Spotify Connect HTTP ${response.code}",
                        )
                    }
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                TransportCommandResult.Failed(0, e.message ?: "Network error")
            }
        }

    private suspend fun fetchTokenOrNull(): String? = runCatching {
        tokenProvider?.invoke()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun enforceWebViewZeroVolume() {
        scope.launch {
            runCatching {
                webView?.evaluateJavascript(
                    "if (window.__onyxForceMute) { window.__onyxForceMute(); }",
                    null,
                )
            }
        }
    }

    private fun destroyWebViewOnMain() {
        val current = webView ?: return
        webView = null
        runCatching {
            current.stopLoading()
            current.removeJavascriptInterface(JS_BRIDGE_NAME)
            current.loadUrl("about:blank")
            current.onPause()
            current.destroy()
        }
    }

    private inner class OnyxSpotifyBridge {
        @JavascriptInterface
        fun requestToken(callbackId: String) {
            scope.launch {
                val token = fetchTokenOrNull().orEmpty()
                val quotedId = quoteJsString(callbackId)
                val quotedToken = quoteJsString(token)
                runCatching {
                    webView?.evaluateJavascript(
                        "window.__onyxDeliverToken && window.__onyxDeliverToken($quotedId, $quotedToken);",
                        null,
                    )
                }
            }
        }

        @JavascriptInterface
        fun onReady(deviceId: String) {
            val cleanId = deviceId.trim()
            if (cleanId.isEmpty()) return
            AppLog.i(TAG, "Spotify silent Connect receiver ready (deviceId=${cleanId.take(8)}...)")
            _status.value = TransportStatus(
                state = TransportState.READY,
                deviceId = cleanId,
                deviceName = deviceName,
                detail = "Connected as \"$deviceName\"",
            )
            enforceWebViewZeroVolume()
        }

        @JavascriptInterface
        fun onNotReady(deviceId: String) {
            AppLog.w(TAG, "Spotify silent Connect receiver went offline")
            _status.value = TransportStatus(
                state = TransportState.CONNECTING,
                deviceId = null,
                deviceName = deviceName,
                detail = "Reconnecting silent device",
            )
        }

        @JavascriptInterface
        fun onError(kind: String, message: String) {
            val isPremium = kind.equals("account_error", ignoreCase = true) ||
                message.contains("premium", ignoreCase = true)
            AppLog.w(TAG, "Spotify Web Playback SDK $kind: $message")
            _status.value = TransportStatus(
                state = if (kind.equals("initialization_error", ignoreCase = true)) {
                    TransportState.UNAVAILABLE
                } else {
                    TransportState.ERROR
                },
                deviceId = null,
                deviceName = deviceName,
                detail = if (isPremium) "Spotify Premium required" else message.ifBlank { kind },
                premiumRequired = isPremium,
            )
        }
    }

    @Serializable
    private data class PlayTrackRequestDto(
        val uris: List<String>,
        val position_ms: Long,
    )

    @Serializable
    private data class PlayerStateResponseDto(
        val is_playing: Boolean? = null,
        val progress_ms: Long? = null,
        val device: DeviceDto? = null,
        val item: ItemDto? = null,
    )

    @Serializable
    private data class DeviceDto(
        val id: String? = null,
        val name: String? = null,
    )

    @Serializable
    private data class ItemDto(
        val uri: String? = null,
    )

    companion object {
        private const val TAG = "MutedWebPlayback"
        private const val JS_BRIDGE_NAME = "OnyxSpotifyBridge"
        private const val SPOTIFY_ORIGIN_BASE_URL = "https://open.spotify.com/"
        private const val PLAYER_ENDPOINT = "https://api.spotify.com/v1/me/player"
        private const val DESKTOP_WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        internal fun quoteJsString(value: String): String = buildString(value.length + 2) {
            append('"')
            for (ch in value) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '<' -> append("\\u003C")
                    '>' -> append("\\u003E")
                    '\u2028' -> append("\\u2028")
                    '\u2029' -> append("\\u2029")
                    else -> append(ch)
                }
            }
            append('"')
        }

        /**
         * Generates the minimal offscreen HTML document that enforces zero-audio / zero-focus
         * isolation before bootstrapping Spotify's Web Playback SDK.
         */
        internal fun buildReceiverHtml(deviceName: String): String {
            val quotedDeviceName = quoteJsString(deviceName)
            return """
                <!DOCTYPE html>
                <html>
                <head><meta charset="utf-8" /></head>
                <body>
                <script>
                (function() {
                  // 1. Force every HTMLMediaElement to remain muted at volume 0 so zero audio plays
                  //    and Chromium skips requesting Android AudioFocus.
                  function muteElement(el) {
                    if (!el) return;
                    try {
                      el.defaultMuted = true;
                      el.muted = true;
                      el.volume = 0;
                    } catch (_) {}
                  }

                  var origPlay = HTMLMediaElement.prototype.play;
                  HTMLMediaElement.prototype.play = function() {
                    muteElement(this);
                    return origPlay.apply(this, arguments);
                  };

                  var mutedDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'muted');
                  if (mutedDesc && mutedDesc.configurable) {
                    Object.defineProperty(HTMLMediaElement.prototype, 'muted', {
                      get: function() { return true; },
                      set: function() {
                        if (mutedDesc.set) { mutedDesc.set.call(this, true); }
                      }
                    });
                  }

                  var volDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'volume');
                  if (volDesc && volDesc.configurable) {
                    Object.defineProperty(HTMLMediaElement.prototype, 'volume', {
                      get: function() { return 0; },
                      set: function() {
                        if (volDesc.set) { volDesc.set.call(this, 0); }
                      }
                    });
                  }

                  ['play', 'playing', 'loadedmetadata', 'volumechange'].forEach(function(evt) {
                    document.addEventListener(evt, function(e) {
                      if (e && e.target && (e.target.tagName === 'AUDIO' || e.target.tagName === 'VIDEO')) {
                        muteElement(e.target);
                      }
                    }, true);
                  });

                  // 2. Defense-in-depth WebAudio gain-0 wrapper so WebAudio graphs stay silent.
                  var NativeAudioCtx = window.AudioContext || window.webkitAudioContext;
                  if (NativeAudioCtx && NativeAudioCtx.prototype) {
                    var origConnect = AudioNode.prototype.connect;
                    AudioNode.prototype.connect = function(dest) {
                      try {
                        if (dest && this.context && dest === this.context.destination) {
                          if (!this.context.__onyxZeroGain) {
                            var gain = this.context.createGain();
                            gain.gain.value = 0;
                            origConnect.call(gain, this.context.destination);
                            this.context.__onyxZeroGain = gain;
                          }
                          return origConnect.call(this, this.context.__onyxZeroGain);
                        }
                      } catch (_) {}
                      return origConnect.apply(this, arguments);
                    };
                  }

                  var pendingCallbacks = {};
                  var callbackSeq = 0;
                  var player = null;

                  window.__onyxDeliverToken = function(id, token) {
                    var cb = pendingCallbacks[id];
                    delete pendingCallbacks[id];
                    if (cb) cb(token || '');
                  };

                  window.__onyxForceMute = function() {
                    try {
                      document.querySelectorAll('audio, video').forEach(muteElement);
                      if (player && player.setVolume) player.setVolume(0);
                    } catch (_) {}
                  };

                  window.__onyxDisconnect = function() {
                    try {
                      if (player && player.disconnect) player.disconnect();
                    } catch (_) {}
                  };

                  window.onSpotifyWebPlaybackSDKReady = function() {
                    try {
                      player = new Spotify.Player({
                        name: $quotedDeviceName,
                        volume: 0.0,
                        getOAuthToken: function(cb) {
                          var id = 'cb_' + (++callbackSeq);
                          pendingCallbacks[id] = cb;
                          $JS_BRIDGE_NAME.requestToken(id);
                        }
                      });

                      player.addListener('ready', function(data) {
                        window.__onyxForceMute();
                        $JS_BRIDGE_NAME.onReady((data && data.device_id) ? data.device_id : '');
                      });
                      player.addListener('not_ready', function(data) {
                        $JS_BRIDGE_NAME.onNotReady((data && data.device_id) ? data.device_id : '');
                      });
                      player.addListener('initialization_error', function(e) {
                        $JS_BRIDGE_NAME.onError('initialization_error', (e && e.message) || 'DRM initialization error');
                      });
                      player.addListener('authentication_error', function(e) {
                        $JS_BRIDGE_NAME.onError('authentication_error', (e && e.message) || 'Authentication error');
                      });
                      player.addListener('account_error', function(e) {
                        $JS_BRIDGE_NAME.onError('account_error', (e && e.message) || 'Spotify Premium required');
                      });
                      player.addListener('playback_error', function(e) {
                        $JS_BRIDGE_NAME.onError('playback_error', (e && e.message) || 'Playback error');
                      });
                      player.connect();
                    } catch (err) {
                      $JS_BRIDGE_NAME.onError('initialization_error', String(err));
                    }
                  };
                })();
                </script>
                <script src="https://sdk.scdn.co/spotify-player.js"></script>
                </body>
                </html>
            """.trimIndent()
        }
    }
}
