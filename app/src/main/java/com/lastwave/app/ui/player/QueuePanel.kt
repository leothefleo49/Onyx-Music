@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.lastwave.app.ui.player

import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import com.lastwave.app.data.playlist.isLikedIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.combinedClickable
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.lastwave.app.ui.common.PredictiveBackScreen
import com.lastwave.app.playback.haptics.MusicHapticsConfig
import com.lastwave.app.playback.haptics.MusicHapticsEngineMode
import com.lastwave.app.playback.haptics.MusicHapticsIntensity
import com.lastwave.app.playback.haptics.MusicHapticsMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.lerp
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.common.isTabletOrWideScreen
import com.lastwave.app.ui.theme.LocalMd3NavBar
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import androidx.media3.common.Player
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.lyrics.LyricsRepository
import com.lastwave.app.data.lyrics.LyricsResult
import com.lastwave.app.data.lyrics.LyricsSyncType
import kotlinx.coroutines.flow.update
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.playlist.SavedPlaylist
import com.lastwave.app.data.playlist.LIKED_SONGS_MODE
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.MusicPlayerState
import com.lastwave.app.playback.PlaybackChromeState
import com.lastwave.app.playback.formatSampleRateKHz
import com.lastwave.app.playback.withoutProgress
import com.lastwave.app.playback.isSpatialAudioCodec
import com.lastwave.app.playback.qualityBadgeLabel
import com.lastwave.app.playback.SignalPathReport
import com.lastwave.app.playback.PlaybackProgressState
import com.lastwave.app.data.artwork.ArtworkNormalizer
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.toGeneratedTrack
import com.lastwave.app.playback.resolve.MetadataLog
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ArtworkViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.lastwave.app.ui.common.ExpressiveInlineLoadingIndicator
import com.lastwave.app.ui.common.ExpressiveMotion
import com.lastwave.app.ui.common.PlaylistCover
import com.lastwave.app.ui.common.TrackContextMenuSheet
import com.lastwave.app.ui.common.TrackMenuCapabilities
import com.lastwave.app.ui.common.TrackMenuTarget
import com.lastwave.app.ui.common.GroupGap
import com.lastwave.app.ui.common.TopCurvedSectionClipShape
import com.lastwave.app.ui.common.groupPositionFor
import com.lastwave.app.ui.common.groupShape
import com.lastwave.app.ui.theme.LocalLiquidGlass
import com.lastwave.app.ui.theme.LocalIsDarkTheme
import com.lastwave.app.ui.theme.LiquidGlassSurface
import com.lastwave.app.ui.theme.liquidGlass
import com.lastwave.app.ui.theme.liquidGlassChrome
import com.lastwave.app.ui.theme.liquidGlassContainerColor
import com.lastwave.app.ui.theme.liquidGlassSource
import com.lastwave.app.ui.theme.isLiquidGlassBackdropSupported
import com.lastwave.app.ui.theme.LocalLiquidGlassBackdrop
import com.lastwave.app.ui.theme.LocalLiquidGlassOverlayBackdrop
import com.lastwave.app.ui.theme.LiquidGlassPreset
import com.lastwave.app.ui.theme.Backdrop
import com.lastwave.app.ui.theme.rememberLayerBackdrop
import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.scale
import kotlinx.coroutines.isActive
import java.nio.IntBuffer
import kotlin.time.Duration.Companion.seconds
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.abs


internal var isQueueLockedState by mutableStateOf(false)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun QueuePanel(
    state: MusicPlayerState,
    player: MusicPlayer,
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    previewQueues: List<com.lastwave.app.data.queue.PreviewQueue> = emptyList(),
    onPlayPreviewQueue: (com.lastwave.app.data.queue.PreviewQueue) -> Unit = {},
    onSaveRecentQueue: (com.lastwave.app.data.queue.PreviewQueue) -> Unit = {},
    onSaveCurrentQueue: () -> Unit = {},
    autoMixEnabled: Boolean = false,
    onToggleQueueTransitions: () -> Unit = {},
    onDisableBitPerfectForTransitions: () -> Unit = {},
    undoQueueSwitch: com.lastwave.app.playback.QueueSwitchUndoSnapshot? = null,
    onUndoQueueSwitch: () -> Unit = {},
    onDismissUndoQueueSwitch: () -> Unit = {},
) {
    val context = LocalContext.current
    val queuePrefs = remember(context) {
        context.applicationContext.getSharedPreferences("onyx_queue_prefs", android.content.Context.MODE_PRIVATE)
    }
    LaunchedEffect(queuePrefs) {
        isQueueLockedState = queuePrefs.getBoolean("queue_locked", false)
    }
    fun setQueueLocked(locked: Boolean) {
        isQueueLockedState = locked
        queuePrefs.edit().putBoolean("queue_locked", locked).apply()
    }
    val isQueueLocked = isQueueLockedState
    var showQueueOptionsSheet by remember { mutableStateOf(false) }
    var showRecentQueuesSheet by remember { mutableStateOf(false) }
    var showQueueHelpDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var dragStartOffsetY by remember { mutableFloatStateOf(0f) }
    var menuIndex by remember { mutableStateOf<Int?>(null) }
    var edgeScrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun cancelEdgeScroll() {
        edgeScrollJob?.cancel()
        edgeScrollJob = null
    }
    DisposableEffect(Unit) {
        onDispose { cancelEdgeScroll() }
    }

    // Stable keys so animateItem() can animate moves instead of treating
    // every shifted row as a new item. Duplicates get occurrence suffixes.
    val queueKeys = remember(state.queue) {
        val counts = mutableMapOf<String, Int>()
        state.queue.map { item ->
            val base = item.videoId ?: item.playbackUrl
                ?: "${item.artist}|${item.title}|${item.album}"
            val n = counts.getOrDefault(base, 0)
            counts[base] = n + 1
            "$base#$n"
        }
    }
    val currentQueue by rememberUpdatedState(state.queue)
    val currentQueueKeys by rememberUpdatedState(queueKeys)
    fun moveDraggedItem() {
        val key = draggingKey ?: return
        val source = currentQueueKeys.indexOf(key)
        val visible = listState.layoutInfo.visibleItemsInfo
        val draggedInfo = visible.firstOrNull { it.key == key } ?: return
        // Wait for both composition and layout to acknowledge the last move; otherwise
        // another pointer/scroll callback can move the neighbor now at the old index.
        if (source !in currentQueue.indices || source != draggingIndex || draggedInfo.index != source) return
        val center = dragStartOffsetY + dragOffsetY + draggedInfo.size / 2f
        val target = visible.firstOrNull { info ->
            info.index != source && info.index in currentQueue.indices &&
                center >= info.offset && center <= info.offset + info.size &&
                (if (info.index > source) center > info.offset + info.size / 2f
                else center < info.offset + info.size / 2f)
        } ?: return
        // Keep the viewport fixed when the first visible song changes index.
        listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        draggingIndex = target.index
        player.moveQueueItem(source, target.index)
        runCatching { haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) }
    }
    // Abort a stale drag if the queue itself changes underneath us
    // (track ended, clear-upcoming, fresh playQueue, …).
    LaunchedEffect(queueKeys) {
        val key = draggingKey ?: return@LaunchedEffect
        if (key !in queueKeys) {
            cancelEdgeScroll()
            draggingKey = null
            dragOffsetY = 0f
        } else {
            // A rejected move would otherwise leave the move guard waiting on an index that never arrives.
            draggingIndex = queueKeys.indexOf(key)
        }
    }
    // Follow the now-playing song in long queues: auto-scroll on every track
    // change (and on first open), unless the user is dragging or has manually
    // scrolled within the last few seconds. The eye button still jumps on demand.
    var userScrollHoldUntil by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) userScrollHoldUntil = System.currentTimeMillis() + 3_000L
    }
    LaunchedEffect(state.currentIndex, state.queue.size) {
        if (draggingKey != null || state.queue.isEmpty()) return@LaunchedEffect
        if (System.currentTimeMillis() < userScrollHoldUntil) return@LaunchedEffect
        val target = state.currentIndex.coerceIn(0, state.queue.size - 1)
        runCatching { listState.animateScrollToItem(target) }
    }
    // True upcoming play order (shuffle/repeat aware). Under shuffle the list
    // below stays in playlist order, so without this strip you can never tell
    // what plays next â€” and Play-next adds look "lost".
    val shuffleUpcoming = remember(state.queue, state.currentIndex, state.shuffleEnabled, state.repeatMode) {
        if (state.shuffleEnabled) {
            runCatching { player.peekUpcomingIndices(state.queue.size.coerceAtLeast(1)) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
    }.filter { it in state.queue.indices && it != state.currentIndex }

    var recentlyRemoved by remember { mutableStateOf<Pair<PlayableTrack, Int>?>(null) }
    var clearedQueue by remember { mutableStateOf<ClearedQueueSnapshot?>(null) }
    var undoJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    Box(modifier = modifier) {
        Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Up next",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isQueueLocked) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Lock,
                                    contentDescription = "Queue locked",
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp),
                                )
                                Text(
                                    "Locked",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                )
                            }
                        }
                    }
                    if (autoMixEnabled) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                        ) {
                            Text(
                                if (player.bitPerfectEnabled) "Mix paused" else "Mix ON",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
                if (state.queue.isNotEmpty()) {
                    val upcomingTracks = if (state.shuffleEnabled && shuffleUpcoming.isNotEmpty()) {
                        shuffleUpcoming.mapNotNull { state.queue.getOrNull(it) }
                    } else {
                        state.queue.drop(state.currentIndex.coerceAtLeast(0) + 1)
                    }
                    val remainingStats = QueueStatsFormatter.formatQueueStats(upcomingTracks)
                    Text(
                        "${state.currentIndex.coerceAtLeast(0) + 1} playing" +
                            if (upcomingTracks.isNotEmpty()) " \u2022 $remainingStats left" else "" +
                            if (state.isEndlessQueue) " \u2022 Unlimited" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    showQueueOptionsSheet = true
                },
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.14f)),
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "Queue options",
                    tint = Color.White.copy(alpha = 0.94f),
                )
            }
        }
        // Prominent undo banner when user just switched away from another queue.
        if (undoQueueSwitch != null) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onUndoQueueSwitch() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.History,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Back to ${undoQueueSwitch.displayLabel}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "Resume track #${undoQueueSwitch.currentIndex + 1} right where you left off",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                            maxLines = 1,
                        )
                    }
                    IconButton(
                        onClick = onDismissUndoQueueSwitch,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Dismiss",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
        if (shuffleUpcoming.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    "Play order \u2022 ${shuffleUpcoming.size} up next",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.60f),
                    fontWeight = FontWeight.Bold,
                )
                shuffleUpcoming.forEachIndexed { order, queueIndex ->
                    val item = state.queue[queueIndex]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { player.seekToQueueItem(queueIndex) }
                            .padding(horizontal = 4.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${order + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(20.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        PlayerArtwork(item, Modifier.size(36.dp), 8.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            Text(
                                item.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.92f),
                            )
                            Text(
                                item.artist,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.60f),
                            )
                        }
                        Text(
                            "#${queueIndex + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.40f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .clip(TopCurvedSectionClipShape(horizontalInset = 0.dp, cornerRadius = 28.dp)),
            contentPadding = PaddingValues(bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(GroupGap),
        ) {
            itemsIndexed(
                state.queue,
                key = { index, _ -> queueKeys.getOrNull(index) ?: index.toString() },
                contentType = { _, _ -> "queue_item" },
            ) { index, item ->
                val currentIndex by rememberUpdatedState(index)
                val isCurrent = index == state.currentIndex
                val stableKey = queueKeys.getOrNull(index) ?: index.toString()
                val isDragging = stableKey == draggingKey
                val dragTranslationY by remember(stableKey) {
                    derivedStateOf {
                        if (stableKey == draggingKey) {
                            dragStartOffsetY + dragOffsetY - (listState.layoutInfo.visibleItemsInfo
                                .firstOrNull { it.key == stableKey }?.offset ?: dragStartOffsetY.toInt())
                        } else 0f
                    }
                }
                val rowOffsetY = remember(stableKey) { Animatable(0f) }
                fun finishDrag() {
                    cancelEdgeScroll()
                    val offset = dragTranslationY
                    scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        rowOffsetY.snapTo(offset)
                        draggingKey = null
                        dragOffsetY = 0f
                        rowOffsetY.animateTo(0f, ExpressiveMotion.effectsDefault())
                    }
                }
                val position = groupPositionFor(index, state.queue.size)
                val itemShape = groupShape(position)

                val dismissState = androidx.compose.material3.rememberSwipeToDismissBoxState(
                    confirmValueChange = { dismissValue ->
                        if (dismissValue == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart) {
                            val resolvedIndex = currentQueueKeys.indexOf(stableKey).takeIf { it >= 0 } ?: currentIndex
                            if (resolvedIndex in currentQueue.indices) {
                                val removedTrack = currentQueue[resolvedIndex]
                                player.removeQueueItem(resolvedIndex)
                                recentlyRemoved = removedTrack to resolvedIndex
                                undoJob?.cancel()
                                undoJob = scope.launch {
                                    delay(3000L)
                                    recentlyRemoved = null
                                }
                                haptics.performHapticFeedback(HapticFeedbackType.ToggleOff)
                                true
                            } else {
                                false
                            }
                        } else {
                            false
                        }
                    }
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDragging || rowOffsetY.value != 0f) 10f else 0f)
                        .animateItem(
                            fadeInSpec = null,
                            fadeOutSpec = null,
                            placementSpec = if (isDragging) null else ExpressiveMotion.effectsDefault(),
                        )
                        .graphicsLayer {
                            // Compensate the new layout origin immediately, without an animation frame of lag.
                            translationY = if (isDragging) dragTranslationY else rowOffsetY.value
                            shadowElevation = if (isDragging) 18f else 0f
                            alpha = if (isDragging) 0.96f else 1f
                        },
                ) {
                    androidx.compose.material3.SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = false,
                        enableDismissFromEndToStart = !isQueueLocked && draggingKey == null,
                        modifier = Modifier.fillMaxWidth(),
                        backgroundContent = {
                            if (dismissState.dismissDirection == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .clip(itemShape)
                                        .background(MaterialTheme.colorScheme.errorContainer)
                                        .padding(horizontal = 24.dp),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    Icon(
                                        Icons.Filled.DeleteOutline,
                                        contentDescription = stringResource(com.lastwave.app.R.string.player_delete_cd),
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                        },
                        content = {
                            LiquidGlassSurface(
                                glassModifier = Modifier.liquidGlassChrome(itemShape, LocalLiquidGlass.current),
                                onClick = { player.seekToQueueItem(currentQueueKeys.indexOf(stableKey).takeIf { it >= 0 } ?: currentIndex) },
                                shape = itemShape,
                                color = if (isCurrent) {
                                    if (LocalLiquidGlass.current) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                                    else MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    if (LocalLiquidGlass.current) MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.70f)
                                    else MaterialTheme.colorScheme.surfaceContainerHighest
                                },
                                contentColor = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "${index + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.width(30.dp),
                                    )
                                    PlayerArtwork(item, Modifier.size(50.dp), 13.dp)
                                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                        Text(
                                            item.title,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                                        )
                                        Text(
                                            item.artist,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f)
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    IconButton(
                                        onClick = { menuIndex = currentQueueKeys.indexOf(stableKey).takeIf { it >= 0 } ?: currentIndex },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.MoreVert,
                                            contentDescription = stringResource(com.lastwave.app.R.string.player_menu_cd),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (!isQueueLocked) {
                                        Icon(
                                            Icons.Filled.DragHandle,
                                            stringResource(com.lastwave.app.R.string.queue_drag_hint),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isDragging) 1f else 0.6f),
                                            modifier = Modifier
                                                .padding(start = 8.dp)
                                                .size(40.dp)
                                                .clip(RoundedCornerShape(14.dp))
                                                .background(
                                                    if (isDragging) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                                    else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                                                )
                                                .padding(8.dp)
                                                .pointerInput(stableKey) {
                                                    detectDragGestures(
                                                        onDragStart = {
                                                            val info = listState.layoutInfo.visibleItemsInfo
                                                                .firstOrNull { it.key == stableKey } ?: return@detectDragGestures
                                                            draggingKey = stableKey
                                                            draggingIndex = info.index
                                                            dragStartOffsetY = info.offset + rowOffsetY.value
                                                            dragOffsetY = 0f
                                                            cancelEdgeScroll()
                                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        },
                                                        onDragEnd = { finishDrag() },
                                                        onDragCancel = { finishDrag() },
                                                        onDrag = { change, dragAmount ->
                                                            change.consume()
                                                            if (draggingKey != stableKey) return@detectDragGestures
                                                            dragOffsetY += dragAmount.y
                                                            moveDraggedItem()
                                                            val layoutInfo = listState.layoutInfo
                                                            val draggedInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.key == stableKey }
                                                                ?: return@detectDragGestures
                                                            val draggedCenter = dragStartOffsetY + dragOffsetY + draggedInfo.size / 2f

                                                            val viewportStart = runCatching { layoutInfo.viewportStartOffset }.getOrDefault(0)
                                                            val viewportEnd = runCatching { layoutInfo.viewportEndOffset }.getOrDefault(0)
                                                            if (viewportEnd <= viewportStart) {
                                                                cancelEdgeScroll()
                                                                return@detectDragGestures
                                                            }
                                                            val edgeZone = 180
                                                            val direction = when {
                                                                draggedCenter < viewportStart + edgeZone -> -1f
                                                                draggedCenter > viewportEnd - edgeZone -> 1f
                                                                else -> 0f
                                                            }
                                                            if (direction == 0f) {
                                                                cancelEdgeScroll()
                                                            } else if (edgeScrollJob?.isActive != true) {
                                                                edgeScrollJob = scope.launch {
                                                                    while (isActive) {
                                                                        androidx.compose.runtime.withFrameNanos { }
                                                                        val scrolled = listState.scrollBy(direction * 10f)
                                                                        moveDraggedItem()
                                                                        if (scrolled == 0f) break
                                                                    }
                                                                }
                                                            }
                                                        },
                                                    )
                                                },
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }

    AnimatedVisibility(
        visible = recentlyRemoved != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 16.dp + bottomPadding)
            .padding(horizontal = 20.dp),
    ) {
        val removed = recentlyRemoved
        if (removed != null) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.95f),
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "\"${removed.first.title}\" removed",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    TextButton(
                        onClick = {
                            val (track, index) = removed
                            player.insertQueueItem(index, track)
                            recentlyRemoved = null
                            undoJob?.cancel()
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.inversePrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "Undo",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
    AnimatedVisibility(
        visible = clearedQueue != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 16.dp + bottomPadding)
            .padding(horizontal = 20.dp),
    ) {
        val cleared = clearedQueue
        if (cleared != null) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.95f),
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Queue cleared",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    TextButton(
                        onClick = {
                            player.playQueue(
                                tracks = cleared.tracks,
                                startIndex = cleared.index.coerceIn(0, cleared.tracks.lastIndex.coerceAtLeast(0)),
                                startPositionMs = cleared.positionMs,
                            )
                            clearedQueue = null
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.inversePrimary,
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text("Undo", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
    }

    menuIndex?.let { index ->
        val itemTrack = state.queue.getOrNull(index)
        if (itemTrack != null) {
            com.lastwave.app.ui.common.TrackContextMenuSheet(
                target = com.lastwave.app.ui.common.TrackMenuTarget.Track(itemTrack.title, itemTrack.artist, itemTrack.album ?: ""),
                capabilities = com.lastwave.app.ui.common.TrackMenuCapabilities(),
                playableTrack = itemTrack,
                onDismiss = { menuIndex = null },
                onRemoveFromQueue = if (isQueueLocked) null else ({ player.removeQueueItem(index) })
            )
        } else {
            menuIndex = null
        }
    }

    if (showQueueOptionsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showQueueOptionsSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Queue options",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp),
                )

                // 1. Smooth transitions (true ON/OFF toggle)
                Surface(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onToggleQueueTransitions()
                    },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = com.lastwave.app.ui.player.QualityGlyphs.AutoMix,
                                contentDescription = null,
                                tint = if (autoMixEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Smooth transitions (Automix)",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    if (autoMixEnabled) {
                                        if (player.bitPerfectEnabled) "ON, but paused while Bit-Perfect audio is active"
                                        else "ON — beat-aware blending between upcoming songs"
                                    } else {
                                        "OFF — songs play back-to-back without fading"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = autoMixEnabled,
                                onCheckedChange = { onToggleQueueTransitions() },
                            )
                        }
                        if (autoMixEnabled && player.bitPerfectEnabled) {
                            Spacer(Modifier.height(6.dp))
                            TextButton(
                                onClick = { onDisableBitPerfectForTransitions() },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text("Turn off Bit-Perfect so transitions can play")
                            }
                        }
                    }
                }

                // 2. Lock queue (true ON/OFF toggle)
                Surface(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        setQueueLocked(!isQueueLocked)
                    },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = if (isQueueLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                            contentDescription = null,
                            tint = if (isQueueLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Lock queue",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                "Prevent accidental reordering, swipe-removes, or clearing",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = isQueueLocked,
                            onCheckedChange = { setQueueLocked(it) },
                        )
                    }
                }

                // 3. Jump to current song
                Surface(
                    onClick = {
                        showQueueOptionsSheet = false
                        userScrollHoldUntil = 0L
                        scope.launch { listState.animateScrollToItem(state.currentIndex.coerceAtLeast(0)) }
                    },
                    enabled = state.currentIndex in state.queue.indices,
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Filled.Visibility, contentDescription = null, modifier = Modifier.size(22.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Jump to current song", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text("Scroll the queue straight to what's playing now", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // 4. Recent queues
                Surface(
                    onClick = {
                        showQueueOptionsSheet = false
                        showRecentQueuesSheet = true
                    },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(22.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Recent queues (${previewQueues.size})", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text("Replay past queues from your exact spot or save them as playlists", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // 5. Save current queue as playlist
                Surface(
                    onClick = {
                        showQueueOptionsSheet = false
                        onSaveCurrentQueue()
                    },
                    enabled = state.queue.isNotEmpty(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Filled.PlaylistAdd, contentDescription = null, modifier = Modifier.size(22.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Save queue as playlist", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text("Keep all ${state.queue.size} songs in your Library", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // 6. Clear upcoming songs
                Surface(
                    onClick = {
                        showQueueOptionsSheet = false
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        clearedQueue = ClearedQueueSnapshot(
                            tracks = state.queue.toList(),
                            index = state.currentIndex.coerceAtLeast(0),
                            positionMs = player.state.value.positionMs,
                        )
                        player.clearQueueKeepCurrent()
                    },
                    enabled = state.queue.size > 1 && !isQueueLocked,
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            tint = if (state.queue.size > 1 && !isQueueLocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.size(22.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text("Clear upcoming songs", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(
                                if (isQueueLocked) "Unlock queue first to clear" else "Keep only the current song (with Undo)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // 7. Queue help
                Surface(
                    onClick = {
                        showQueueOptionsSheet = false
                        showQueueHelpDialog = true
                    },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Column(Modifier.weight(1f)) {
                            Text("How Queue works", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text("Quick guide to shuffle order, transitions, locking & history", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    if (showRecentQueuesSheet) {
        ModalBottomSheet(
            onDismissRequest = { showRecentQueuesSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Recent queues",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Tap any queue to pick up right where you left off, or save it as a playlist.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                if (previewQueues.isEmpty()) {
                    Text(
                        "No recent queues saved yet. Play any playlist or album and it will show up here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else {
                    previewQueues.forEach { queue ->
                        Surface(
                            onClick = {
                                showRecentQueuesSheet = false
                                onPlayPreviewQueue(queue)
                            },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(20.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        queue.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val posSec = (queue.positionMs / 1000L).coerceAtLeast(0L)
                                    val posLabel = if (posSec > 0L) " \u2022 ${posSec / 60}:${"%02d".format(posSec % 60)}" else ""
                                    Text(
                                        "${queue.tracks.size} songs \u2022 Track #${queue.startIndex + 1}$posLabel" +
                                            if (queue.shuffleEnabled) " \u2022 Shuffled" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                                IconButton(
                                    onClick = { onSaveRecentQueue(queue) },
                                ) {
                                    Icon(
                                        Icons.Filled.PlaylistAdd,
                                        contentDescription = "Save queue as playlist",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showQueueHelpDialog) {
        AlertDialog(
            onDismissRequest = { showQueueHelpDialog = false },
            title = { Text("How Queue works") },
            text = {
                Text(
                    "• Reorder songs: Drag the handle on the right of any song.\n" +
                        "• Remove a song: Swipe it left (tap Undo if accidental).\n" +
                        "• Lock queue: Freezes drag, swipe, and Clear so nothing moves by mistake.\n" +
                        "• Smooth transitions: Blends one song into the next automatically (requires Bit-Perfect mode OFF).\n" +
                        "• Recent queues: Every queue you play is saved with your exact song & timestamp so you can jump back anytime.\n" +
                        "• Accidental switch? Tap the 'Go back' banner above the mini player to return right where you were.",
                )
            },
            confirmButton = {
                TextButton(onClick = { showQueueHelpDialog = false }) { Text("Got it") }
            },
        )
    }
}

private data class ClearedQueueSnapshot(
    val tracks: List<PlayableTrack>,
    val index: Int,
    val positionMs: Long,
)
