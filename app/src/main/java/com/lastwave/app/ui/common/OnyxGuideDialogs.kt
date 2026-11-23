package com.lastwave.app.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Immutable
private data class TourSlide(
    val badge: String,
    val title: String,
    val summary: String,
    val icon: ImageVector,
    val bullets: List<String>,
)

private val ONYX_TOUR_SLIDES = listOf(
    TourSlide(
        badge = "1 of 6 · Navigation",
        title = "Welcome to Onyx Music",
        summary = "Everything is organized into three clean tabs on the bottom dock.",
        icon = Icons.Filled.Home,
        bullets = listOf(
            "Feed: Personalized music discovery, mixes, moods, and podcasts.",
            "Stats: Your live scrobble count, Genres showcase, streaks, and deep charts.",
            "Library: Your playlists, liked songs, downloads, and local device audio.",
            "Tap the gear icon in the top-right corner of any tab to open Settings.",
        ),
    ),
    TourSlide(
        badge = "2 of 6 · Mini Player & Gestures",
        title = "Mini Player & Floating Bubble",
        summary = "Control playback from anywhere without losing your place.",
        icon = Icons.Filled.GraphicEq,
        bullets = listOf(
            "Tap the mini player bar to expand the Full Player.",
            "Swipe the mini player DOWN to collapse it into a compact floating bubble without stopping your music.",
            "Swipe left or right on the cover art or mini player to skip tracks.",
            "Double-tap the album cover in the full player to heart/like a song.",
        ),
    ),
    TourSlide(
        badge = "3 of 6 · Queue & Transitions",
        title = "Decluttered Queue & Instant Undo",
        summary = "Your queue stays clean while keeping powerful tools one tap away.",
        icon = Icons.AutoMirrored.Filled.QueueMusic,
        bullets = listOf(
            "Tap the ⋮ button in the Queue header for Smooth Transitions (ON/OFF), Lock Queue, Recent Queues, Save as Playlist, and Clear Upcoming.",
            "Accidentally started a new song and wiped your queue? Tap the floating '↩ Back to previous queue' pill above the mini player within the first 2 songs to restore your exact queue and song position!",
            "If the app ever closes unexpectedly, Onyx remembers your exact timestamp inside the song.",
        ),
    ),
    TourSlide(
        badge = "4 of 6 · Lyrics & Vibe Mode (AOD)",
        title = "Synced Lyrics & Glowing AOD",
        summary = "Turn your screen into a beat-reactive OLED ambient display.",
        icon = Icons.Filled.AutoAwesome,
        bullets = listOf(
            "Tap the ✨ sparkle button on the player to enter Always-on Display (Vibe Mode).",
            "Inside AOD, tap the bottom 'Style' pill to switch live between glowing Wave ribbons, Ambient Glow, Spectrum Bars, and Aurora!",
            "Need to adjust lyrics timing? Open the ⋮ menu in Lyrics or on the player and tap 'Lyrics sync offset'.",
        ),
    ),
    TourSlide(
        badge = "5 of 6 · Stats, Genres & Multi-Sync",
        title = "Universal History Sync & Genres",
        summary = "Bring your entire listening history from every music platform.",
        icon = Icons.Filled.CloudSync,
        bullets = listOf(
            "Tap 'Genres →' on the Stats card to see your true percentage breakdown by genre and start genre mixes.",
            "Tap 'Sync & Import' in Stats or Scrobbler Settings to pull all past Last.fm, YouTube Music, or ListenBrainz plays — or import .zip/.json/.csv files from Spotify, YouTube Takeout, SoundCloud, stats.fm, or Pano Scrobbler.",
            "Non-music YouTube videos and podcasts are still scrobbled, and cleanly separated into the 'Videos & Other' tab so your Music stats stay pure!",
        ),
    ),
    TourSlide(
        badge = "6 of 6 · Smart Audio & Timers",
        title = "Auto-Best Audio & Custom Timers",
        summary = "Automatic audiophile tuning for every speaker and headphone.",
        icon = Icons.Filled.Headphones,
        bullets = listOf(
            "Enable 'Auto-Best Audio Output' in Settings → Audio to automatically optimize quality, EQ, Clarity, and Spatial Audio for Bluetooth headphones, wired DACs, or phone speakers.",
            "Open Sleep Timer from Quick Actions or any song's ⋮ menu to set presets, End of Song, End of Queue, or any custom Hours + Minutes.",
            "Long-press any setting row in Settings to read a plain-English explanation of what it does.",
        ),
    ),
)

/**
 * Interactive 6-step visual onboarding & feature tour dialog.
 */
@Composable
fun OnyxInteractiveTourDialog(
    onComplete: () -> Unit,
) {
    var stepIndex by remember { mutableIntStateOf(0) }
    val slide = ONYX_TOUR_SLIDES[stepIndex.coerceIn(0, ONYX_TOUR_SLIDES.lastIndex)]

    Dialog(
        onDismissRequest = onComplete,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Top progress & Skip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = slide.badge,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }
                    TextButton(onClick = onComplete) {
                        Text("Skip tour")
                    }
                }

                // Step progress bars
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ONYX_TOUR_SLIDES.indices.forEach { idx ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (idx <= stepIndex) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    },
                                ),
                        )
                    }
                }

                AnimatedContent(
                    targetState = slide,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
                    label = "tourSlide",
                ) { current ->
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = current.icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = current.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = current.summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            current.bullets.forEach { bullet ->
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.65f),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.Top,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .padding(top = 6.dp)
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary),
                                        )
                                        Text(
                                            text = bullet,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            lineHeight = 20.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Navigation buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (stepIndex > 0) {
                        FilledTonalButton(onClick = { stepIndex-- }) {
                            Text("Back")
                        }
                    } else {
                        Spacer(Modifier.width(8.dp))
                    }

                    Button(
                        onClick = {
                            if (stepIndex < ONYX_TOUR_SLIDES.lastIndex) {
                                stepIndex++
                            } else {
                                onComplete()
                            }
                        },
                    ) {
                        Text(
                            text = if (stepIndex < ONYX_TOUR_SLIDES.lastIndex) "Next" else "Start listening",
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Immutable
private data class HelpGuideEntry(
    val category: String,
    val question: String,
    val answer: String,
)

private val HELP_GUIDE_ENTRIES = listOf(
    HelpGuideEntry(
        category = "Player & Queue",
        question = "How do I undo an accidental song or queue switch?",
        answer = "Whenever you tap a song that replaces your active queue, a floating '↩ Back to previous queue' pill appears directly above the Mini Player (and at the top of the Queue tab) for the first 1–2 songs. Tap it to jump right back to your previous queue, song, and exact timestamp!",
    ),
    HelpGuideEntry(
        category = "Player & Queue",
        question = "Where are Queue Lock, Smooth Transitions, Recent Queues, and Clear Upcoming?",
        answer = "Open the player's Queue tab and tap the ⋮ (three-dot) button in the top-right corner of the Queue header. Every queue tool lives in that clean menu, including a real ON/OFF switch for Smooth Transitions and one-tap Save Queue as Playlist.",
    ),
    HelpGuideEntry(
        category = "Player & Queue",
        question = "Why aren't Smooth Transitions / Crossfade blending between songs?",
        answer = "Make sure Smooth Transitions is switched ON in the Queue ⋮ menu, AND check that Bit-Perfect Mode is turned OFF. Bit-Perfect sends raw untouched audio straight to hardware, which bypasses all software mixing. The Queue ⋮ menu shows a one-tap 'Turn off Bit-Perfect' button whenever it's blocking transitions.",
    ),
    HelpGuideEntry(
        category = "Player & Queue",
        question = "How do I collapse the Mini Player into a small floating button?",
        answer = "Swipe DOWN on the Mini Player bar. It collapses into a draggable floating circle so you have full screen space while music keeps playing. Tap the floating button to reopen the player.",
    ),
    HelpGuideEntry(
        category = "Player & Queue",
        question = "How do I use Deep Mix (AI / Smart DJ set builder)?",
        answer = "From the player or mixing controls, open Deep Mix, pick a mood preset (Peak Hour, Late Night, Smooth Blend, EnergyClimb, or Harmonic Flow), choose how many songs you want, and tap Build Deep Mix. Onyx analyzes tempo (BPM), musical key, and energy to sequence a cohesive flow.",
    ),
    HelpGuideEntry(
        category = "Lyrics & AOD",
        question = "What is the ✨ Sparkle button on the player? (Always-on Display / Vibe Mode)",
        answer = "The ✨ button opens Always-on Display (Vibe Mode) — a true black OLED screen showing the time, cover art, synced lyrics, and glowing music-reactive edge waves. Tap the 'Style' pill at the bottom of AOD to cycle live between Wave, Glow, Bars, Aurora, and Off. Double-tap the black background to exit.",
    ),
    HelpGuideEntry(
        category = "Lyrics & AOD",
        question = "How do I fix out-of-sync lyrics (Lyrics Sync Offset)?",
        answer = "Inside the Lyrics view (or from the player's ⋮ menu), open the ⋮ menu and tap 'Lyrics sync offset'. Use -0.5s / -0.1s / +0.1s / +0.5s to line the words up with the vocals. Your offset is saved automatically for that track.",
    ),
    HelpGuideEntry(
        category = "Stats & Sync",
        question = "What does the 'Genres →' button on the Stats card do?",
        answer = "Tapping 'Genres →' opens your Genre Showcase, showing each genre's true percentage share of your music listening (summing to 100%). Tap any genre bar to see your top tracks in that genre, start a personalized Genre Mix, or discover new tracks.",
    ),
    HelpGuideEntry(
        category = "Stats & Sync",
        question = "How do I sync all my past history from Last.fm, Spotify, YouTube, SoundCloud, stats.fm, or Pano Scrobbler?",
        answer = "Open Stats → tap 'Sync & Import' (or go to Settings → Scrobbler & History Sync). You can pull your entire cloud history directly from Last.fm, YouTube Music, or ListenBrainz — or tap 'Import Export File / ZIP' and select any .zip, .json, .jsonl, or .csv export from Spotify, Google/YouTube Takeout, SoundCloud, stats.fm, Pano Scrobbler, or Apple Music!",
    ),
    HelpGuideEntry(
        category = "Stats & Sync",
        question = "How does Onyx separate Music from YouTube videos and podcasts?",
        answer = "Onyx automatically classifies every scrobble and import as Music, Video, or Podcast. Non-music videos and podcasts are still recorded locally, but Stats defaults to the 'Music' tab so your music charts stay 100% pure. Tap the 'Videos & Other' tab in Stats or Play Feed anytime to view your video/podcast history, or tap 'Mark Video' / 'Mark Music' on any item to reclassify it.",
    ),
    HelpGuideEntry(
        category = "Audio & Output",
        question = "What does 'Auto-Best Audio Output' do?",
        answer = "When enabled in Settings → Audio, Onyx detects whether you're listening on Bluetooth headphones (and which codec), wired headphones, a USB DAC, or built-in phone speakers, and automatically picks the optimal quality source, sample rate, Studio Clarity, and per-device profile.",
    ),
    HelpGuideEntry(
        category = "Audio & Output",
        question = "How do I set a custom Sleep Timer?",
        answer = "Open Sleep Timer from Quick Actions (long-press a bottom tab or open the account menu), the player's ⋮ menu, or by tapping the active timer pill. Pick a preset (10m–2h), End of Current Song, End of Queue, or dial in any custom Hours + Minutes.",
    ),
    HelpGuideEntry(
        category = "Audio & Output",
        question = "Where did Friends go?",
        answer = "Friends is located in Settings → Collaboration & Social (right below Onyx Jam) and in the Quick Actions menu, keeping the Library top bar clean and uncluttered.",
    ),
)

private val HELP_CATEGORIES = listOf("All", "Player & Queue", "Lyrics & AOD", "Stats & Sync", "Audio & Output")

/**
 * Searchable plain-English in-app Help & Feature Guide bottom sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnyxHelpCenterSheet(
    onDismiss: () -> Unit,
    onReplayTour: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }

    val filtered = remember(query, selectedCategory) {
        val q = query.trim().lowercase()
        HELP_GUIDE_ENTRIES.filter { entry ->
            val catMatch = selectedCategory == "All" || entry.category == selectedCategory
            val qMatch = q.isEmpty() ||
                entry.question.lowercase().contains(q) ||
                entry.answer.lowercase().contains(q) ||
                entry.category.lowercase().contains(q)
            catMatch && qMatch
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.90f)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Filled.HelpOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp),
                    )
                    Column {
                        Text(
                            text = "Help & Feature Guide",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                        )
                        Text(
                            text = "Plain-English answers for every button & feature",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (onReplayTour != null) {
                    FilledTonalButton(
                        onClick = {
                            onDismiss()
                            onReplayTour()
                        },
                    ) {
                        Text("Interactive Tour")
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close help")
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Ask anything (e.g. transitions, AOD, genres, sync, queue…)") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(HELP_CATEGORIES) { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = cat },
                        label = { Text(cat) },
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(filtered, key = { it.question }) { item ->
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = item.category.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = item.question,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = item.answer,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 20.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}
