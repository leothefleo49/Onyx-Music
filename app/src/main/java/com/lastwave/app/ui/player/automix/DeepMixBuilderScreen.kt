package com.lastwave.app.ui.player.automix

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lastwave.app.R
import com.lastwave.app.data.generate.GenresRepository
import com.lastwave.app.data.generate.TasteProfileProvider
import com.lastwave.app.data.generate.youtubeVideoIdOrNull
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.automix.api.AutoMixPreferences
import com.lastwave.app.playback.automix.api.AutoMixTier
import com.lastwave.app.playback.automix.curator.CurationStage
import com.lastwave.app.playback.automix.curator.DeepMixCuratorEngine
import com.lastwave.app.playback.automix.curator.DeepMixEdgeSummary
import com.lastwave.app.playback.automix.curator.DeepMixProgressState
import com.lastwave.app.playback.automix.curator.DeepMixRequest
import com.lastwave.app.playback.automix.curator.GenrePolicy
import com.lastwave.app.playback.automix.curator.SourcePolicy
import com.lastwave.app.ui.theme.LiquidGlassPreset
import com.lastwave.app.ui.theme.liquidGlassChrome
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

enum class GenrePolicyMode {
    AUTO_FROM_TASTE,
    NO_PREFERENCE,
    SELECTED_GENRES,
}

@Immutable
data class DeepMixBuilderUiState(
    val title: String = "Deep Transition Mix",
    val sourcePolicy: SourcePolicy = SourcePolicy.LEAN_LIKED_LISTENED,
    val genreMode: GenrePolicyMode = GenrePolicyMode.AUTO_FROM_TASTE,
    val selectedGenres: Set<String> = emptySet(),
    val availableGenres: List<String> = DEFAULT_GENRES,
    val detectedTasteGenres: List<String> = emptyList(),
    val targetTrackCount: Int = 20,
) {
    companion object {
        val DEFAULT_GENRES = listOf(
            "Electronic",
            "House",
            "Techno",
            "Synthwave",
            "Pop",
            "Hip-Hop",
            "R&B",
            "Indie",
            "Rock",
            "Lo-Fi",
            "Jazz",
            "Ambient",
            "Drum & Bass",
            "Afrobeats",
        )
    }
}

@HiltViewModel
class DeepMixBuilderViewModel @Inject constructor(
    private val curatorEngine: DeepMixCuratorEngine,
    private val genresRepository: GenresRepository,
    private val tasteProfileProvider: TasteProfileProvider,
    private val musicPlayer: MusicPlayer,
    private val autoMixPreferences: AutoMixPreferences,
) : ViewModel() {

    val curationProgress: StateFlow<DeepMixProgressState> = curatorEngine.progress

    private val _uiState = MutableStateFlow(DeepMixBuilderUiState())
    val uiState: StateFlow<DeepMixBuilderUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val topStats = runCatching { genresRepository.fetchGenreStats("overall") }
                .getOrDefault(emptyList())
                .map { it.name.trim() }
                .filter { it.isNotEmpty() }
            val tasteTags = if (topStats.isNotEmpty()) {
                topStats.take(8)
            } else {
                runCatching { tasteProfileProvider.get().topTags.toList().take(8) }
                    .getOrDefault(emptyList())
            }
            if (tasteTags.isNotEmpty()) {
                _uiState.update { current ->
                    val merged = (tasteTags + current.availableGenres)
                        .distinctBy { it.lowercase() }
                    current.copy(
                        detectedTasteGenres = tasteTags.take(5),
                        availableGenres = merged,
                    )
                }
            }
        }
    }

    fun setTitle(value: String) {
        _uiState.update { it.copy(title = value) }
    }

    fun setSourcePolicy(policy: SourcePolicy) {
        _uiState.update { it.copy(sourcePolicy = policy) }
    }

    fun setGenreMode(mode: GenrePolicyMode) {
        _uiState.update { it.copy(genreMode = mode) }
    }

    fun toggleGenre(genre: String) {
        _uiState.update { state ->
            val updated = if (genre in state.selectedGenres) {
                state.selectedGenres - genre
            } else {
                state.selectedGenres + genre
            }
            state.copy(
                genreMode = GenrePolicyMode.SELECTED_GENRES,
                selectedGenres = updated,
            )
        }
    }

    fun addCustomGenre(raw: String) {
        val clean = raw.trim()
        if (clean.isEmpty()) return
        _uiState.update { state ->
            val merged = (listOf(clean) + state.availableGenres).distinctBy { it.lowercase() }
            state.copy(
                genreMode = GenrePolicyMode.SELECTED_GENRES,
                availableGenres = merged,
                selectedGenres = state.selectedGenres + clean,
            )
        }
    }

    fun setTargetTrackCount(count: Int) {
        _uiState.update {
            it.copy(targetTrackCount = count.coerceIn(DeepMixRequest.MIN_TRACKS, DeepMixRequest.MAX_TRACKS))
        }
    }

    fun startBuild() {
        if (curationProgress.value.isRunning) return
        val s = _uiState.value
        val genrePolicy = when (s.genreMode) {
            GenrePolicyMode.AUTO_FROM_TASTE -> GenrePolicy.AutoFromTaste
            GenrePolicyMode.NO_PREFERENCE -> GenrePolicy.NoPreference
            GenrePolicyMode.SELECTED_GENRES -> {
                if (s.selectedGenres.isEmpty()) GenrePolicy.NoPreference
                else GenrePolicy.SelectedGenres(s.selectedGenres)
            }
        }
        curatorEngine.startCuration(
            DeepMixRequest(
                title = s.title.trim().ifBlank { "Deep Transition Mix" },
                sourcePolicy = s.sourcePolicy,
                genrePolicy = genrePolicy,
                targetTrackCount = s.targetTrackCount,
            ),
        )
    }

    fun cancelBuild() {
        curatorEngine.cancelCuration()
    }

    fun playCompletedMix() {
        val state = curationProgress.value
        if (state.savedTracks.isEmpty()) return
        val playable = state.savedTracks.map { t ->
            PlayableTrack(
                title = t.name,
                artist = t.artist,
                album = t.album,
                artworkUrl = t.artworkUrl,
                videoId = t.youtubeVideoIdOrNull(),
                playbackUrl = t.url.takeIf { it.isNotBlank() },
                durationMs = t.durationMs,
            )
        }
        viewModelScope.launch(NonCancellable + Dispatchers.IO) {
            autoMixPreferences.setAutoMixEnabled(true)
            autoMixPreferences.setAutoMixTier(AutoMixTier.DEEP_CURATED_MIX)
        }
        musicPlayer.playQueue(
            tracks = playable,
            startIndex = 0,
            sourceLabel = state.savedPlaylistTitle ?: "Deep Transition Mix",
            startShuffled = false,
            enableRadioContinuation = false,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeepMixBuilderScreen(
    onBack: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    viewModel: DeepMixBuilderViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val progress by viewModel.curationProgress.collectAsStateWithLifecycle()
    var customGenreDraft by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.deep_mix_back_cd),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.deep_mix_title),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.deep_mix_subtitle),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. Live Progress / Result Card (if active, completed, or failed)
            item(key = "progress_section") {
                AnimatedVisibility(visible = progress.stage != CurationStage.IDLE) {
                    CurationStatusCard(
                        progress = progress,
                        onCancel = viewModel::cancelBuild,
                        onPlayMix = viewModel::playCompletedMix,
                        onOpenPlaylist = { id -> onOpenPlaylist(id) },
                    )
                }
            }

            // 2. Playlist Title
            item(key = "title_input") {
                OutlinedTextField(
                    value = ui.title,
                    onValueChange = viewModel::setTitle,
                    label = { Text(stringResource(R.string.deep_mix_playlist_name_label)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 3. Track Source Policy
            item(key = "source_policy") {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.deep_mix_source_section),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        )

                        SourcePolicyRow(
                            title = stringResource(R.string.deep_mix_source_lean_title),
                            description = stringResource(R.string.deep_mix_source_lean_desc),
                            selected = ui.sourcePolicy == SourcePolicy.LEAN_LIKED_LISTENED,
                            onClick = { viewModel.setSourcePolicy(SourcePolicy.LEAN_LIKED_LISTENED) },
                        )
                        SourcePolicyRow(
                            title = stringResource(R.string.deep_mix_source_neutral_title),
                            description = stringResource(R.string.deep_mix_source_neutral_desc),
                            selected = ui.sourcePolicy == SourcePolicy.NEUTRAL_DISCOVER,
                            onClick = { viewModel.setSourcePolicy(SourcePolicy.NEUTRAL_DISCOVER) },
                        )
                        SourcePolicyRow(
                            title = stringResource(R.string.deep_mix_source_strict_title),
                            description = stringResource(R.string.deep_mix_source_strict_desc),
                            selected = ui.sourcePolicy == SourcePolicy.STRICTLY_LIKED_OR_PLAYLISTS,
                            onClick = { viewModel.setSourcePolicy(SourcePolicy.STRICTLY_LIKED_OR_PLAYLISTS) },
                        )
                    }
                }
            }

            // 4. Genre Focus Policy
            item(key = "genre_policy") {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.deep_mix_genre_section),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            GenreModeChip(
                                label = stringResource(R.string.deep_mix_genre_auto),
                                selected = ui.genreMode == GenrePolicyMode.AUTO_FROM_TASTE,
                                onClick = { viewModel.setGenreMode(GenrePolicyMode.AUTO_FROM_TASTE) },
                                modifier = Modifier.weight(1f),
                            )
                            GenreModeChip(
                                label = stringResource(R.string.deep_mix_genre_none),
                                selected = ui.genreMode == GenrePolicyMode.NO_PREFERENCE,
                                onClick = { viewModel.setGenreMode(GenrePolicyMode.NO_PREFERENCE) },
                                modifier = Modifier.weight(1f),
                            )
                            GenreModeChip(
                                label = stringResource(R.string.deep_mix_genre_selected),
                                selected = ui.genreMode == GenrePolicyMode.SELECTED_GENRES,
                                onClick = { viewModel.setGenreMode(GenrePolicyMode.SELECTED_GENRES) },
                                modifier = Modifier.weight(1f),
                            )
                        }

                        if (ui.genreMode == GenrePolicyMode.AUTO_FROM_TASTE && ui.detectedTasteGenres.isNotEmpty()) {
                            Text(
                                text = "Detected from your taste: ${ui.detectedTasteGenres.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        if (ui.genreMode == GenrePolicyMode.SELECTED_GENRES) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                ui.availableGenres.forEach { genre ->
                                    val selected = genre in ui.selectedGenres
                                    FilterChip(
                                        selected = selected,
                                        onClick = { viewModel.toggleGenre(genre) },
                                        label = { Text(genre) },
                                        leadingIcon = if (selected) {
                                            {
                                                Icon(
                                                    imageVector = Icons.Rounded.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        } else null,
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedTextField(
                                    value = customGenreDraft,
                                    onValueChange = { customGenreDraft = it },
                                    placeholder = { Text(stringResource(R.string.deep_mix_custom_genre_hint)) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(1f),
                                )
                                FilledTonalButton(
                                    onClick = {
                                        viewModel.addCustomGenre(customGenreDraft)
                                        customGenreDraft = ""
                                    },
                                    enabled = customGenreDraft.isNotBlank(),
                                    modifier = Modifier.height(52.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Add,
                                        contentDescription = "Add custom genre",
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 5. Mix Length Slider
            item(key = "track_count") {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.deep_mix_track_count_section),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            )
                            Text(
                                text = "${ui.targetTrackCount} tracks",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Slider(
                            value = ui.targetTrackCount.toFloat(),
                            onValueChange = { viewModel.setTargetTrackCount(it.roundToInt()) },
                            valueRange = 8f..40f,
                            steps = 31,
                        )
                    }
                }
            }

            // 6. Primary Build CTA
            item(key = "build_cta") {
                Button(
                    onClick = viewModel::startBuild,
                    enabled = !progress.isRunning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .liquidGlassChrome(
                            shape = RoundedCornerShape(27.dp),
                            preset = LiquidGlassPreset.PlayerControls,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.deep_mix_build_button),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }

            // 7. Completed Sequenced Edge Map
            if (progress.stage == CurationStage.COMPLETED && progress.edgeSummaries.isNotEmpty()) {
                item(key = "edges_header") {
                    Text(
                        text = stringResource(R.string.deep_mix_edges_header),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                itemsIndexed(
                    items = progress.edgeSummaries,
                    key = { index, edge -> "$index:${edge.fromTitle}:${edge.toTitle}" },
                ) { index, edge ->
                    EdgeSummaryRow(index = index + 1, edge = edge)
                }
            }

            item(key = "bottom_spacer") {
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun CurationStatusCard(
    progress: DeepMixProgressState,
    onCancel: () -> Unit,
    onPlayMix: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
) {
    val containerColor = when (progress.stage) {
        CurationStage.FAILED -> MaterialTheme.colorScheme.errorContainer
        CurationStage.COMPLETED -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when (progress.stage) {
        CurationStage.FAILED -> MaterialTheme.colorScheme.onErrorContainer
        CurationStage.COMPLETED -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = progress.errorMessage ?: progress.statusDetail.ifBlank { progress.stage.name },
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    )
                }
                if (progress.isRunning) {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.deep_mix_cancel_button),
                        )
                    }
                }
            }

            if (progress.isRunning) {
                LinearProgressIndicator(
                    progress = { progress.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
                val pct = (progress.progress * 100f).roundToInt().coerceIn(0, 100)
                Text(
                    text = "$pct% complete \u00B7 Runs in background if you leave this screen",
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.80f),
                )
            }

            if (progress.stage == CurationStage.COMPLETED) {
                val avgPct = ((progress.averageTransitionScore ?: 0f) * 100f).roundToInt()
                Text(
                    text = "${progress.savedPlaylistTitle ?: "Deep Transition Mix"} \u00B7 $avgPct% avg transition match",
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = onPlayMix,
                        modifier = Modifier.weight(1f).height(46.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.deep_mix_play_now))
                    }
                    progress.savedPlaylistId?.let { id ->
                        OutlinedButton(
                            onClick = { onOpenPlaylist(id) },
                            modifier = Modifier.weight(1f).height(46.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.deep_mix_open_playlist))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourcePolicyRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GenreModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = modifier.height(44.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 6.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EdgeSummaryRow(
    index: Int,
    edge: DeepMixEdgeSummary,
) {
    val scorePct = (edge.score * 100f).roundToInt().coerceIn(0, 100)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "$index",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "${edge.fromTitle} -> ${edge.toTitle}",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${edge.bpmPair} \u00B7 ${edge.keyPair} \u00B7 ${edge.transitionDurationSec}s blend",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    text = "$scorePct%",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}
