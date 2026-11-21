package com.lastwave.app.playback.automix.curator

import androidx.compose.runtime.Immutable
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.playback.automix.analysis.AcousticSignature
import com.lastwave.app.playback.automix.data.TransitionOverrideEntity

/**
 * Candidate source weighting policy for Tier 3 Deep Curated Mix ("Insanely Good Transitions").
 */
enum class SourcePolicy {
    LEAN_LIKED_LISTENED,
    NEUTRAL_DISCOVER,
    STRICTLY_LIKED_OR_PLAYLISTS,
}

/**
 * Genre selection policy for Tier 3 Deep Curated Mix.
 */
sealed interface GenrePolicy {
    data object AutoFromTaste : GenrePolicy
    data object NoPreference : GenrePolicy
    data class SelectedGenres(val genres: Set<String>) : GenrePolicy
}

/**
 * Origin classification of a candidate track for source preference scoring.
 */
enum class CandidateOrigin {
    LIKED,
    PLAYLIST,
    MOST_LISTENED,
    DISCOVER,
}

@Immutable
data class DeepMixRequest(
    val title: String = "Deep Transition Mix",
    val sourcePolicy: SourcePolicy = SourcePolicy.LEAN_LIKED_LISTENED,
    val genrePolicy: GenrePolicy = GenrePolicy.AutoFromTaste,
    val targetTrackCount: Int = 20,
    val minTransitionScore: Float = 0.60f,
) {
    val safeTargetTrackCount: Int
        get() = targetTrackCount.coerceIn(MIN_TRACKS, MAX_TRACKS)

    companion object {
        const val MIN_TRACKS = 6
        const val MAX_TRACKS = 50
    }
}

data class CuratedCandidate(
    val track: GeneratedTrack,
    val trackKey: String,
    val signature: AcousticSignature,
    val origin: CandidateOrigin,
    val playCount: Int = 0,
    val matchedGenres: Set<String> = emptySet(),
)

data class TransitionEdgeScore(
    val fromKey: String,
    val toKey: String,
    val totalScore: Float,
    val bpmScore: Float,
    val harmonicScore: Float,
    val energyVocalScore: Float,
    val sourceScore: Float,
    val keyPairLabel: String,
    val bpmPairLabel: String,
    val suggestedDurationSec: Int,
)

data class SequencedMixResult(
    val orderedCandidates: List<CuratedCandidate>,
    val edges: List<TransitionEdgeScore>,
    val edgeRecipes: List<TransitionOverrideEntity>,
    val averageScore: Float,
    val minEdgeScore: Float,
)

enum class CurationStage {
    IDLE,
    GATHERING_CANDIDATES,
    ANALYZING_SIGNATURES,
    SCORING_PAIRS,
    SEQUENCING_PATH,
    SAVING_PLAYLIST,
    COMPLETED,
    FAILED,
    CANCELLED,
}

@Immutable
data class DeepMixEdgeSummary(
    val fromTitle: String,
    val fromArtist: String,
    val toTitle: String,
    val toArtist: String,
    val score: Float,
    val bpmPair: String,
    val keyPair: String,
    val transitionDurationSec: Int,
)

@Immutable
data class DeepMixProgressState(
    val stage: CurationStage = CurationStage.IDLE,
    val progress: Float = 0f,
    val statusDetail: String = "",
    val candidatesFound: Int = 0,
    val tracksAnalyzed: Int = 0,
    val totalToAnalyze: Int = 0,
    val averageTransitionScore: Float? = null,
    val minTransitionScore: Float? = null,
    val savedPlaylistId: Long? = null,
    val savedPlaylistTitle: String? = null,
    val savedTracks: List<GeneratedTrack> = emptyList(),
    val edgeSummaries: List<DeepMixEdgeSummary> = emptyList(),
    val resolvedGenres: List<String> = emptyList(),
    val errorMessage: String? = null,
) {
    val isRunning: Boolean
        get() = stage == CurationStage.GATHERING_CANDIDATES ||
            stage == CurationStage.ANALYZING_SIGNATURES ||
            stage == CurationStage.SCORING_PAIRS ||
            stage == CurationStage.SEQUENCING_PATH ||
            stage == CurationStage.SAVING_PLAYLIST
}
