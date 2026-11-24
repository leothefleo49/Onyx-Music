package com.lastwave.app.ui.recognition

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.lastwave.app.R
import com.lastwave.app.data.recognition.RecognizedSong
import com.lastwave.app.ui.common.HeaderActionIcon

/**
 * Header action for Shazam-speed song identification, Sing/Lyrics search,
 * and Hum-to-Search. Starts listening in one tap like Shazam.
 */
@Composable
fun MusicRecognitionButton(key: String) {
    val viewModel: MusicRecognitionViewModel = hiltViewModel(key = key)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val audioLevel by viewModel.audioLevel.collectAsStateWithLifecycle()
    val defaults by viewModel.defaults.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var sheetOpen by remember { mutableStateOf(false) }
    var activeMode by remember { mutableStateOf(RecognitionMode.SONG) }
    var showSensitivity by remember { mutableStateOf(false) }
    var lyricInput by remember { mutableStateOf("") }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty()
            if (matches.isNotEmpty()) {
                lyricInput = matches.first()
                viewModel.identifyFromLyrics(matches)
            }
        }
    }

    val startVoiceLyrics = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 4)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Sing or say a line from the song...")
        }
        val started = runCatching { speechLauncher.launch(intent); true }.getOrDefault(false)
        if (!started) {
            Toast.makeText(context, "Voice input unavailable — type a lyric line below", Toast.LENGTH_SHORT).show()
        }
    }

    val launchSongListening = {
        activeMode = RecognitionMode.SONG
        sheetOpen = true
        viewModel.start(humMode = false, sensitivityOverride = defaults.second)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchSongListening() else {
            sheetOpen = true
            viewModel.onPermissionDenied()
        }
    }

    val onTriggerListen = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) launchSongListening() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    HeaderActionIcon(Icons.Filled.GraphicEq, stringResource(R.string.recognize_music)) {
        onTriggerListen()
    }

    if (sheetOpen || state != RecognitionState.Idle) {
        RecognitionSheet(
            state = state,
            activeMode = activeMode,
            audioLevel = audioLevel,
            sensitivity = defaults.second,
            showSensitivity = showSensitivity,
            lyricInput = lyricInput,
            hasGoogleHum = remember(context) { canLaunchGoogleHumSearch(context) },
            onSelectMode = { mode ->
                activeMode = mode
                when (mode) {
                    RecognitionMode.SONG -> onTriggerListen()
                    RecognitionMode.LYRICS -> {
                        viewModel.dismiss()
                        sheetOpen = true
                        startVoiceLyrics()
                    }
                    RecognitionMode.HUM -> {
                        viewModel.dismiss()
                        sheetOpen = true
                    }
                }
            },
            onToggleSensitivity = { showSensitivity = !showSensitivity },
            onSensitivityChange = viewModel::updateSensitivity,
            onLyricInputChange = { lyricInput = it },
            onSubmitLyrics = {
                if (lyricInput.isNotBlank()) {
                    viewModel.identifyFromLyrics(listOf(lyricInput))
                }
            },
            onVoiceLyrics = startVoiceLyrics,
            onLaunchGoogleHum = {
                if (launchGoogleHumSearch(context)) {
                    sheetOpen = false
                    viewModel.dismiss()
                } else {
                    Toast.makeText(
                        context,
                        "Install the Google app for wordless humming, or use Sing / Lyrics",
                        Toast.LENGTH_LONG,
                    ).show()
                    activeMode = RecognitionMode.LYRICS
                }
            },
            onRetry = {
                when (activeMode) {
                    RecognitionMode.SONG -> onTriggerListen()
                    RecognitionMode.LYRICS -> startVoiceLyrics()
                    RecognitionMode.HUM -> launchGoogleHumSearch(context)
                }
            },
            onDismiss = {
                sheetOpen = false
                viewModel.dismiss()
            },
            onOpenSettings = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                sheetOpen = false
                viewModel.dismiss()
            },
            onPlay = { song ->
                sheetOpen = false
                viewModel.playSong(song)
            },
            onSearch = { song ->
                sheetOpen = false
                viewModel.searchFor(song)
            },
        )
    }
}

private fun canLaunchGoogleHumSearch(context: Context): Boolean {
    val intent = Intent("com.google.android.googlequicksearchbox.MUSIC_SEARCH")
    return intent.resolveActivity(context.packageManager) != null
}

private fun launchGoogleHumSearch(context: Context): Boolean {
    val intent = Intent("com.google.android.googlequicksearchbox.MUSIC_SEARCH")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching {
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecognitionSheet(
    state: RecognitionState,
    activeMode: RecognitionMode,
    audioLevel: Float,
    sensitivity: Int,
    showSensitivity: Boolean,
    lyricInput: String,
    hasGoogleHum: Boolean,
    onSelectMode: (RecognitionMode) -> Unit,
    onToggleSensitivity: () -> Unit,
    onSensitivityChange: (Int) -> Unit,
    onLyricInputChange: (String) -> Unit,
    onSubmitLyrics: () -> Unit,
    onVoiceLyrics: () -> Unit,
    onLaunchGoogleHum: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onPlay: (RecognizedSong) -> Unit,
    onSearch: (RecognizedSong) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Mode Switcher Bar: Song | Sing / Lyrics | Hum
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ModePill(
                    label = "Listen",
                    selected = activeMode == RecognitionMode.SONG,
                    onClick = { onSelectMode(RecognitionMode.SONG) },
                    modifier = Modifier.weight(1f),
                )
                ModePill(
                    label = "Sing / Lyrics",
                    selected = activeMode == RecognitionMode.LYRICS,
                    onClick = { onSelectMode(RecognitionMode.LYRICS) },
                    modifier = Modifier.weight(1f),
                )
                ModePill(
                    label = "Hum a Tune",
                    selected = activeMode == RecognitionMode.HUM,
                    onClick = { onSelectMode(RecognitionMode.HUM) },
                    modifier = Modifier.weight(1f),
                )
            }

            when (state) {
                is RecognitionState.Listening -> {
                    LivePulsingMic(audioLevel = audioLevel)
                    Text(
                        text = if (state.elapsedSec >= 2.8f) "Matching audio..." else stringResource(R.string.recognize_listening),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Instant song recognition (${"%.1f".format(state.elapsedSec)}s)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onToggleSensitivity) {
                        Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Mic sensitivity: $sensitivity%")
                    }
                    if (showSensitivity) {
                        Slider(
                            value = sensitivity.toFloat(),
                            onValueChange = { onSensitivityChange(it.toInt()) },
                            valueRange = 0f..100f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.recognize_cancel)) }
                }

                is RecognitionState.Identifying -> {
                    LivePulsingMic(audioLevel = 0.55f)
                    Text(stringResource(R.string.recognize_identifying), style = MaterialTheme.typography.titleLarge)
                    if (state.hint.isNotBlank()) {
                        Text(
                            state.hint,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.recognize_cancel)) }
                }

                is RecognitionState.Found -> {
                    val song = state.song
                    if (song.coverUrl != null) {
                        AsyncImage(
                            model = song.coverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(164.dp)
                                .clip(RoundedCornerShape(16.dp)),
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            song.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (song.artist.isNotBlank()) {
                            Text(
                                song.artist,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (!song.album.isNullOrBlank()) {
                            Text(
                                song.album,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(onClick = { onPlay(song) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Play Now")
                        }
                        OutlinedButton(onClick = { onSearch(song) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Search")
                        }
                    }
                    if (state.alternates.isNotEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "Other possible matches",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            state.alternates.forEach { alt ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onPlay(alt) },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Filled.MusicNote,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(alt.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            if (alt.artist.isNotBlank()) {
                                                Text(alt.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.recognize_close)) }
                }

                is RecognitionState.Failed -> {
                    Text(
                        stringResource(state.error.messageRes()),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    if (state.error == RecognitionError.PERMISSION) {
                        Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.recognize_open_settings))
                        }
                    } else {
                        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.recognize_retry))
                        }
                        if (state.error == RecognitionError.NO_MATCH && hasGoogleHum) {
                            OutlinedButton(onClick = onLaunchGoogleHum, modifier = Modifier.fillMaxWidth()) {
                                Text("Try Hum-to-Search (Google)")
                            }
                        }
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.recognize_cancel)) }
                }

                RecognitionState.Idle -> {
                    when (activeMode) {
                        RecognitionMode.LYRICS -> {
                            Text(
                                "Sing or say any lyrics",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Speak or sing a line you remember, or type a few words below.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            Button(onClick = onVoiceLyrics, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Tap to Sing or Speak Lyrics")
                            }
                            OutlinedTextField(
                                value = lyricInput,
                                onValueChange = onLyricInputChange,
                                placeholder = { Text("Or type a lyric line...") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { onSubmitLyrics() }),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (lyricInput.isNotBlank()) {
                                OutlinedButton(onClick = onSubmitLyrics, modifier = Modifier.fillMaxWidth()) {
                                    Text("Find Song by Lyrics")
                                }
                            }
                            TextButton(onClick = onDismiss) { Text(stringResource(R.string.recognize_cancel)) }
                        }

                        RecognitionMode.HUM -> {
                            Icon(
                                Icons.Filled.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(48.dp),
                            )
                            Text(
                                "Hum, Whistle, or Sing a Melody",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                if (hasGoogleHum) {
                                    "Opens Google's neural Hum-to-Search for wordless melody matching, or switch to Sing / Lyrics to match right inside Onyx."
                                } else {
                                    "Wordless melody humming requires the Google app on Android. You can also sing or speak a line in Sing / Lyrics mode."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            Button(onClick = onLaunchGoogleHum, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (hasGoogleHum) "Start Hum-to-Search" else "Switch to Sing / Lyrics")
                            }
                            OutlinedButton(
                                onClick = { onSelectMode(RecognitionMode.LYRICS) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Find by Sung / Spoken Lyrics Instead")
                            }
                            TextButton(onClick = onDismiss) { Text(stringResource(R.string.recognize_cancel)) }
                        }

                        RecognitionMode.SONG -> {
                            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                                Text("Start Listening")
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ModePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LivePulsingMic(audioLevel: Float) {
    val pulse = rememberInfiniteTransition(label = "listen")
    val ringScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.65f,
        animationSpec = infiniteRepeatable(tween(950), RepeatMode.Restart),
        label = "ringScale",
    )
    val ringAlpha by pulse.animateFloat(
        initialValue = 0.45f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(950), RepeatMode.Restart),
        label = "ringAlpha",
    )
    val reactiveScale by animateFloatAsState(
        targetValue = 1f + (audioLevel.coerceIn(0f, 1f) * 0.35f),
        animationSpec = tween(110),
        label = "reactiveScale",
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(128.dp)) {
        Box(
            Modifier
                .size(72.dp)
                .scale(ringScale)
                .graphicsLayer { alpha = ringAlpha }
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
        Box(
            Modifier
                .size(76.dp)
                .scale(reactiveScale)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), CircleShape),
        )
        Box(
            Modifier
                .size(68.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

private fun RecognitionError.messageRes() = when (this) {
    RecognitionError.PERMISSION -> R.string.recognize_error_permission
    RecognitionError.MICROPHONE -> R.string.recognize_error_mic
    RecognitionError.SILENCE -> R.string.recognize_error_silence
    RecognitionError.NO_MATCH -> R.string.recognize_error_no_match
    RecognitionError.RATE_LIMITED -> R.string.recognize_error_rate_limited
    RecognitionError.NETWORK -> R.string.recognize_error_network
}
