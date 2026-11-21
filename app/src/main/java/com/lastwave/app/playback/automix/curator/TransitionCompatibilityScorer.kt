package com.lastwave.app.playback.automix.curator

import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.automix.analysis.AutoMixCamelotClassifier
import com.lastwave.app.playback.automix.analysis.VocalPresenceGateDetector
import com.lastwave.app.playback.automix.api.AutoMixSettings
import com.lastwave.app.playback.automix.api.AutoMixTier
import com.lastwave.app.playback.automix.data.TransitionOverrideEntity
import com.lastwave.app.playback.automix.engine.SmartBeatTransitionPlanner
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pairwise directed transition scorer and Greedy + 2-opt Hamiltonian path sequencer
 * for Tier 3 Deep Curated Mix ("Insanely Good Transitions").
 *
 * Weights:
 * - BPM compatibility (1:1, 1:2, 2:1 within +-5% stretch window): 35%
 * - Camelot harmonic key compatibility: 30%
 * - Energy/LUFS smoothness & intro/outro non-vocal overlap: 25%
 * - Source preference (liked/listened vs discover policy): 10%
 */
object TransitionCompatibilityScorer {

    const val WEIGHT_BPM = 0.35f
    const val WEIGHT_HARMONIC = 0.30f
    const val WEIGHT_ENERGY_VOCAL = 0.25f
    const val WEIGHT_SOURCE = 0.10f

    private const val SAME_ARTIST_CONSECUTIVE_PENALTY = 0.08f
    private const val MAX_TWO_OPT_PASSES = 60

    fun scoreTransition(
        from: CuratedCandidate,
        to: CuratedCandidate,
        sourcePolicy: SourcePolicy = SourcePolicy.LEAN_LIKED_LISTENED,
    ): TransitionEdgeScore {
        val bpmA = from.signature.beatGrid.bpm.value.takeIf { it.isFinite() && it > 0f } ?: 120f
        val bpmB = to.signature.beatGrid.bpm.value.takeIf { it.isFinite() && it > 0f } ?: 120f

        // 1. BPM compatibility (35%) — checks direct, half-time (2x), and double-time (0.5x).
        val (bpmScore, effectiveDeltaPct) = scoreBpmCompatibility(bpmA, bpmB)

        // 2. Camelot harmonic key compatibility (30%).
        val harmonicEval = AutoMixCamelotClassifier.evaluateCompatibility(
            keyOut = from.signature.camelotKey,
            keyIn = to.signature.camelotKey,
            tempoDeltaPercent = effectiveDeltaPct,
        )
        val harmonicScore = harmonicEval.score.coerceIn(0f, 1f)

        // 3. Energy/LUFS smoothness & intro/outro non-vocal overlap (25%).
        val energyVocalScore = scoreEnergyAndVocalCleanliness(from, to)

        // 4. Source preference weight (10%).
        val sourceScore = scoreSourcePreference(to, sourcePolicy)

        var total = (bpmScore * WEIGHT_BPM) +
            (harmonicScore * WEIGHT_HARMONIC) +
            (energyVocalScore * WEIGHT_ENERGY_VOCAL) +
            (sourceScore * WEIGHT_SOURCE)

        if (from.track.artist.isNotBlank() &&
            from.track.artist.equals(to.track.artist, ignoreCase = true)
        ) {
            total -= SAME_ARTIST_CONSECUTIVE_PENALTY
        }

        val clampedTotal = total.coerceIn(0f, 1f)
        val suggestedDurationSec = when {
            clampedTotal >= 0.85f -> 18
            clampedTotal >= 0.72f -> 14
            clampedTotal >= 0.58f -> 10
            else -> 8
        }

        return TransitionEdgeScore(
            fromKey = from.trackKey,
            toKey = to.trackKey,
            totalScore = clampedTotal,
            bpmScore = bpmScore,
            harmonicScore = harmonicScore,
            energyVocalScore = energyVocalScore,
            sourceScore = sourceScore,
            keyPairLabel = harmonicEval.keyPairLabel,
            bpmPairLabel = "${bpmA.roundToInt()} -> ${bpmB.roundToInt()} BPM",
            suggestedDurationSec = suggestedDurationSec,
        )
    }

    internal fun scoreBpmCompatibility(bpmA: Float, bpmB: Float): Pair<Float, Float> {
        if (bpmA <= 0f || bpmB <= 0f) return 0.5f to 0f

        val directDelta = abs(bpmB - bpmA) / bpmA
        val halfTimeDelta = abs((bpmB * 2.0f) - bpmA) / bpmA
        val doubleTimeDelta = abs((bpmB * 0.5f) - bpmA) / bpmA

        val minDelta = minOf(directDelta, halfTimeDelta, doubleTimeDelta)
        val isDirect = minDelta == directDelta
        val deltaPct = minDelta * 100f

        val baseScore = when {
            deltaPct <= 1.0f -> 1.0f
            deltaPct <= 3.0f -> 1.0f - ((deltaPct - 1.0f) / 2.0f) * 0.10f // 1.00 .. 0.90
            deltaPct <= 5.0f -> 0.90f - ((deltaPct - 3.0f) / 2.0f) * 0.12f // 0.90 .. 0.78
            deltaPct <= 6.0f -> 0.78f - ((deltaPct - 5.0f) / 1.0f) * 0.13f // 0.78 .. 0.65
            deltaPct <= 12.0f -> 0.65f - ((deltaPct - 6.0f) / 6.0f) * 0.35f // 0.65 .. 0.30
            else -> (0.30f - ((deltaPct - 12.0f) / 30.0f) * 0.20f).coerceAtLeast(0.10f)
        }

        // Half/double-time transitions are musical but slightly less seamless than exact 1:1.
        val multiplier = if (isDirect) 1.0f else 0.90f
        return (baseScore * multiplier).coerceIn(0f, 1f) to deltaPct
    }

    internal fun scoreEnergyAndVocalCleanliness(
        from: CuratedCandidate,
        to: CuratedCandidate,
    ): Float {
        // LUFS delta component (50% of this sub-score)
        val lufsA = from.signature.integratedLufs.value.takeIf { it.isFinite() } ?: -14.0f
        val lufsB = to.signature.integratedLufs.value.takeIf { it.isFinite() } ?: -14.0f
        val lufsDelta = abs(lufsA - lufsB)
        val lufsScore = when {
            lufsDelta <= 1.5f -> 1.0f
            lufsDelta <= 4.0f -> 1.0f - ((lufsDelta - 1.5f) / 2.5f) * 0.25f
            lufsDelta <= 10.0f -> 0.75f - ((lufsDelta - 4.0f) / 6.0f) * 0.50f
            else -> 0.20f
        }

        // Vocal non-overlap component (50% of this sub-score)
        val spliceDurationMs = 12_000L
        val outDur = from.signature.durationMs.coerceAtLeast(spliceDurationMs)
        val cueOutStartMs = maxOf(0L, outDur - spliceDurationMs)
        val cueInStartMs = to.signature.beatGrid.firstDownbeatMs.coerceIn(0L, 4_000L)

        val overlapMs = measureVocalOverlapMs(
            from = from,
            to = to,
            cueOutStartMs = cueOutStartMs,
            cueInStartMs = cueInStartMs,
            spliceDurationMs = spliceDurationMs,
        )
        val vocalScore = when {
            overlapMs == 0L -> 1.0f
            overlapMs <= VocalPresenceGateDetector.MAX_ALLOWED_VOCAL_OVERLAP_MS -> 0.88f
            overlapMs <= 1_500L -> 0.65f
            overlapMs <= 4_000L -> 0.35f
            else -> 0.10f
        }

        return ((lufsScore * 0.50f) + (vocalScore * 0.50f)).coerceIn(0f, 1f)
    }

    private fun measureVocalOverlapMs(
        from: CuratedCandidate,
        to: CuratedCandidate,
        cueOutStartMs: Long,
        cueInStartMs: Long,
        spliceDurationMs: Long,
    ): Long {
        if (!from.signature.outroVocalMask.hasActiveVocals || !to.signature.introVocalMask.hasActiveVocals) {
            return 0L
        }
        var overlap = 0L
        val step = 100L
        var t = 0L
        while (t < spliceDurationMs) {
            if (from.signature.outroVocalMask.isVocalActiveAt(cueOutStartMs + t) &&
                to.signature.introVocalMask.isVocalActiveAt(cueInStartMs + t)
            ) {
                overlap += step
            }
            t += step
        }
        return overlap
    }

    internal fun scoreSourcePreference(
        candidate: CuratedCandidate,
        policy: SourcePolicy,
    ): Float = when (policy) {
        SourcePolicy.STRICTLY_LIKED_OR_PLAYLISTS -> when (candidate.origin) {
            CandidateOrigin.LIKED -> 1.0f
            CandidateOrigin.PLAYLIST -> 0.90f
            CandidateOrigin.MOST_LISTENED -> 0.75f
            CandidateOrigin.DISCOVER -> 0.0f
        }

        SourcePolicy.LEAN_LIKED_LISTENED -> when (candidate.origin) {
            CandidateOrigin.LIKED -> 1.0f
            CandidateOrigin.MOST_LISTENED -> (0.80f + (candidate.playCount.coerceAtMost(20) * 0.01f)).coerceAtMost(0.98f)
            CandidateOrigin.PLAYLIST -> 0.72f
            CandidateOrigin.DISCOVER -> 0.38f
        }

        SourcePolicy.NEUTRAL_DISCOVER -> when (candidate.origin) {
            CandidateOrigin.DISCOVER -> 0.85f
            CandidateOrigin.LIKED -> 0.70f
            CandidateOrigin.PLAYLIST -> 0.70f
            CandidateOrigin.MOST_LISTENED -> 0.68f
        }
    }

    /**
     * Sequences up to [targetCount] candidates to maximize cumulative pairwise transition quality
     * using multi-start greedy construction followed by directed 2-opt local search and pool-swap refinement.
     */
    fun sequenceOptimalPath(
        candidates: List<CuratedCandidate>,
        targetCount: Int,
        sourcePolicy: SourcePolicy = SourcePolicy.LEAN_LIKED_LISTENED,
    ): SequencedMixResult {
        val unique = candidates.distinctBy { it.trackKey }
        if (unique.isEmpty()) {
            return SequencedMixResult(emptyList(), emptyList(), emptyList(), 0f, 0f)
        }
        if (unique.size == 1) {
            return SequencedMixResult(unique, emptyList(), emptyList(), 1f, 1f)
        }

        val n = unique.size
        val desiredLen = targetCount.coerceIn(2, n)

        // Precompute full directed pairwise score matrix.
        val edgeMatrix = Array(n) { i ->
            Array(n) { j ->
                if (i == j) null else scoreTransition(unique[i], unique[j], sourcePolicy)
            }
        }
        val scoreMatrix = Array(n) { i ->
            FloatArray(n) { j ->
                if (i == j) -1f else edgeMatrix[i][j]!!.totalScore
            }
        }

        // 1. Multi-start Greedy Construction from top candidate starting nodes.
        val startSeeds = (0 until n)
            .sortedByDescending { idx ->
                var bestOut = 0f
                for (j in 0 until n) {
                    if (j != idx && scoreMatrix[idx][j] > bestOut) bestOut = scoreMatrix[idx][j]
                }
                bestOut + (scoreSourcePreference(unique[idx], sourcePolicy) * 0.15f)
            }
            .take(minOf(n, 16))

        var bestPath = IntArray(0)
        var bestTotal = Float.NEGATIVE_INFINITY

        for (start in startSeeds) {
            val path = buildGreedyPath(start, desiredLen, n, scoreMatrix)
            val total = pathScore(path, scoreMatrix)
            if (total > bestTotal) {
                bestTotal = total
                bestPath = path
            }
        }

        // 2. Directed 2-Opt Local Search: test reversing sub-segments [i..j] when total directed score improves.
        bestPath = improveWithTwoOpt(bestPath, scoreMatrix)

        // 3. Pool-swap refinement: if extra candidates remain unused, swap them into weak positions when better.
        if (n > desiredLen) {
            bestPath = improveWithPoolSwaps(bestPath, n, scoreMatrix)
            bestPath = improveWithTwoOpt(bestPath, scoreMatrix)
        }

        val ordered = bestPath.map { unique[it] }
        val edges = ArrayList<TransitionEdgeScore>(ordered.size - 1)
        val recipes = ArrayList<TransitionOverrideEntity>(ordered.size - 1)

        val smartSettings = AutoMixSettings(
            autoMixEnabled = true,
            tier = AutoMixTier.DEEP_CURATED_MIX,
            spectralFilterSweepsEnabled = true,
        )

        for (idx in 0 until bestPath.size - 1) {
            val fromIdx = bestPath[idx]
            val toIdx = bestPath[idx + 1]
            val fromCand = unique[fromIdx]
            val toCand = unique[toIdx]
            val edge = edgeMatrix[fromIdx][toIdx]!!
            edges.add(edge)

            val plan = SmartBeatTransitionPlanner.planTransition(
                outgoingTrack = fromCand.toPlayableTrack(),
                outgoingSignature = fromCand.signature,
                incomingTrack = toCand.toPlayableTrack(),
                incomingSignature = toCand.signature,
                settings = smartSettings.copy(targetDurationSeconds = edge.suggestedDurationSec),
                outgoingDurationMs = fromCand.signature.durationMs,
                incomingDurationMs = toCand.signature.durationMs,
            )

            recipes.add(
                TransitionOverrideEntity(
                    fromTrackKey = fromCand.trackKey,
                    toTrackKey = toCand.trackKey,
                    bpmMatchEnabled = abs(plan.tempoRatioIncoming - 1.0f) <= SmartBeatTransitionPlanner.MAX_SMART_TEMPO_STRETCH_FRACTION,
                    targetTempoRatio = plan.tempoRatioIncoming,
                    transitionDurationSec = (plan.durationMs / 1_000L).toInt()
                        .coerceIn(TransitionOverrideEntity.MIN_DURATION_SEC, TransitionOverrideEntity.MAX_DURATION_SEC),
                    curveType = plan.curveType.name,
                    eqLowSwap = plan.eqLowSwap,
                    eqMidSwap = plan.eqMidSwap,
                    eqHighSwap = plan.eqHighSwap,
                    cueOutMs = plan.cueOutStartMs,
                    cueInMs = plan.cueInStartMs,
                    filterSweepEnabled = plan.filterSweepEnabled,
                    compatibilityScore = edge.totalScore,
                ).normalized(),
            )
        }

        val avgScore = if (edges.isEmpty()) 1f else edges.map { it.totalScore }.average().toFloat()
        val minScore = edges.minOfOrNull { it.totalScore } ?: 1f

        return SequencedMixResult(
            orderedCandidates = ordered,
            edges = edges,
            edgeRecipes = recipes,
            averageScore = avgScore,
            minEdgeScore = minScore,
        )
    }

    private fun buildGreedyPath(
        start: Int,
        length: Int,
        n: Int,
        scoreMatrix: Array<FloatArray>,
    ): IntArray {
        val visited = BooleanArray(n)
        val path = IntArray(length)
        path[0] = start
        visited[start] = true

        for (step in 1 until length) {
            val prev = path[step - 1]
            var bestNext = -1
            var bestScore = Float.NEGATIVE_INFINITY
            for (cand in 0 until n) {
                if (!visited[cand]) {
                    val s = scoreMatrix[prev][cand]
                    if (s > bestScore) {
                        bestScore = s
                        bestNext = cand
                    }
                }
            }
            if (bestNext == -1) break
            path[step] = bestNext
            visited[bestNext] = true
        }
        return path
    }

    /**
     * Directed 2-opt refinement on an open Hamiltonian path.
     * Reversing [i..j] also reverses internal directed edges, so we evaluate exact delta safely.
     */
    internal fun improveWithTwoOpt(
        initialPath: IntArray,
        scoreMatrix: Array<FloatArray>,
    ): IntArray {
        val path = initialPath.copyOf()
        val len = path.size
        if (len < 3) return path

        var currentScore = pathScore(path, scoreMatrix)
        var pass = 0
        var improved = true

        while (improved && pass < MAX_TWO_OPT_PASSES) {
            improved = false
            pass++
            for (i in 0 until len - 1) {
                for (j in i + 1 until len) {
                    if (i == 0 && j == len - 1) continue
                    val candidateScore = scoreAfterReversal(path, i, j, scoreMatrix)
                    if (candidateScore > currentScore + 1e-5f) {
                        reverseInPlace(path, i, j)
                        currentScore = candidateScore
                        improved = true
                    }
                }
            }
        }
        return path
    }

    private fun improveWithPoolSwaps(
        initialPath: IntArray,
        poolSize: Int,
        scoreMatrix: Array<FloatArray>,
    ): IntArray {
        val path = initialPath.copyOf()
        val inPath = BooleanArray(poolSize)
        for (idx in path) inPath[idx] = true

        var improved = true
        var passes = 0
        while (improved && passes < 15) {
            improved = false
            passes++
            for (pos in path.indices) {
                val oldNode = path[pos]
                val oldContrib = localContribution(path, pos, oldNode, scoreMatrix)
                var bestUnused = -1
                var bestContrib = oldContrib + 1e-4f

                for (cand in 0 until poolSize) {
                    if (!inPath[cand]) {
                        val candContrib = localContribution(path, pos, cand, scoreMatrix)
                        if (candContrib > bestContrib) {
                            bestContrib = candContrib
                            bestUnused = cand
                        }
                    }
                }
                if (bestUnused != -1) {
                    inPath[oldNode] = false
                    inPath[bestUnused] = true
                    path[pos] = bestUnused
                    improved = true
                }
            }
        }
        return path
    }

    private fun localContribution(
        path: IntArray,
        pos: Int,
        node: Int,
        scoreMatrix: Array<FloatArray>,
    ): Float {
        var sum = 0f
        if (pos > 0) sum += scoreMatrix[path[pos - 1]][node]
        if (pos < path.size - 1) sum += scoreMatrix[node][path[pos + 1]]
        return sum
    }

    internal fun pathScore(path: IntArray, scoreMatrix: Array<FloatArray>): Float {
        var sum = 0f
        for (i in 0 until path.size - 1) {
            sum += scoreMatrix[path[i]][path[i + 1]]
        }
        return sum
    }

    private fun scoreAfterReversal(
        path: IntArray,
        i: Int,
        j: Int,
        scoreMatrix: Array<FloatArray>,
    ): Float {
        var sum = 0f
        val len = path.size
        for (k in 0 until len - 1) {
            val a = if (k in i..j) path[i + j - k] else path[k]
            val b = if ((k + 1) in i..j) path[i + j - (k + 1)] else path[k + 1]
            sum += scoreMatrix[a][b]
        }
        return sum
    }

    private fun reverseInPlace(arr: IntArray, start: Int, end: Int) {
        var l = start
        var r = end
        while (l < r) {
            val tmp = arr[l]
            arr[l] = arr[r]
            arr[r] = tmp
            l++
            r--
        }
    }

    private fun CuratedCandidate.toPlayableTrack(): PlayableTrack = PlayableTrack(
        title = track.name,
        artist = track.artist,
        album = track.album,
        artworkUrl = track.artworkUrl,
        videoId = track.url.takeIf { it.contains("watch?v=") }?.substringAfter("watch?v=")?.substringBefore("&"),
        playbackUrl = track.url.takeIf { it.isNotBlank() },
        durationMs = track.durationMs ?: signature.durationMs,
    )
}
