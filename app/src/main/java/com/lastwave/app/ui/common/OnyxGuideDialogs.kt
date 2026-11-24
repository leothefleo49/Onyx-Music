package com.lastwave.app.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.text.style.TextOverflow
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
    val isExperiencePicker: Boolean = false,
)

private val ONYX_TOUR_SLIDES = listOf(
    TourSlide(
        badge = "1 of 3 · Essentials",
        title = "Welcome to Onyx Music",
        summary = "Three simple tabs on the bottom dock keep everything easy to find.",
        icon = Icons.Filled.Home,
        bullets = listOf(
            "Home: Music picks & shelves that stay put until you pull down to refresh.",
            "Stats: Your listening overview, Genres showcase, and Onyx Wrapped.",
            "Library: Playlists, Liked Songs, downloads, and local files.",
            "Tap the gear icon in the top-right corner anytime to open Settings.",
        ),
    ),
    TourSlide(
        badge = "2 of 3 · Your Style",
        title = "Choose Your Experience",
        summary = "Start clean and simple, or unlock every audiophile & DJ control right away. You can switch anytime in Settings.",
        icon = Icons.Filled.Tune,
        bullets = emptyList(),
        isExperiencePicker = true,
    ),
    TourSlide(
        badge = "3 of 3 · Your Music",
        title = "Bring Your Music & Friends",
        summary = "Everything works immediately offline or online — connect accounts only if you want.",
        icon = Icons.Filled.CloudSync,
        bullets = listOf(
            "Spotify & YouTube Music: Your playlists and Liked Songs sync live and stay hearted automatically.",
            "History & Stats: Pull all past Spotify, YouTube Music, Last.fm, or Takeout plays with built-in double-scrobble protection.",
            "Vibe Mode & Friends: Tap ✨ on the player for glowing Half/Full AOD, and add Onyx Friends by handle, code, or contacts.",
        ),
    ),
)

/**
 * Streamlined 3-step welcome & experience-level onboarding tour.
 */
@Composable
fun OnyxInteractiveTourDialog(
    onComplete: () -> Unit,
    initialAdvancedMode: Boolean = false,
    onSelectAdvancedMode: ((Boolean) -> Unit)? = null,
) {
    var stepIndex by remember { mutableIntStateOf(0) }
    var selectedAdvanced by remember(initialAdvancedMode) { mutableStateOf(initialAdvancedMode) }
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
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
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
                            maxLines = 1,
                        )
                    }
                    TextButton(onClick = onComplete) {
                        Text("Skip")
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
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(160)) },
                    label = "tourSlide",
                ) { current ->
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (stepIndex == 0) {
                                OnyxBrandLogo(
                                    size = 46.dp,
                                    shape = RoundedCornerShape(14.dp),
                                )
                            } else {
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
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = current.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = current.summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        if (current.isExperiencePicker) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                ExperienceTierCard(
                                    title = "Standard Mode (Recommended)",
                                    subtitle = "Clean, approachable controls for music, playlists, lyrics, Vibe Mode & stats — technical jargon stays tucked away.",
                                    selected = !selectedAdvanced,
                                    onClick = {
                                        selectedAdvanced = false
                                        onSelectAdvancedMode?.invoke(false)
                                    },
                                )
                                ExperienceTierCard(
                                    title = "Pro & Audiophile Mode",
                                    subtitle = "Shows everything upfront: Manual DJ Mix Studio, USB DAC Bit-Perfect, SoXR resampler, AutoEQ, Spatial Audio & deep UI options.",
                                    selected = selectedAdvanced,
                                    onClick = {
                                        selectedAdvanced = true
                                        onSelectAdvancedMode?.invoke(true)
                                    },
                                )
                            }
                        } else {
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
                                onSelectAdvancedMode?.invoke(selectedAdvanced)
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

@Composable
private fun ExperienceTierCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f)
        },
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    lineHeight = 18.sp,
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

private enum class HelpTier(val label: String) {
    GETTING_STARTED("Getting Started"),
    PRO_AUDIOPHILE("Pro & Audiophile"),
}

@Immutable
private data class HelpGuideEntry(
    val tier: HelpTier,
    val category: String,
    val question: String,
    val answer: String,
)

private val HELP_GUIDE_ENTRIES = listOf(
    // ── Getting Started (Plain-English Essentials) ──
    HelpGuideEntry(
        tier = HelpTier.GETTING_STARTED,
        category = "Basics",
        question = "How do I navigate Onyx Music & open Settings?",
        answer = "Use the bottom dock to switch between Home (discovery), Stats (charts & genres), and Library (playlists, liked songs & downloads). Tap the gear icon in the top-right corner of any tab to jump straight into Settings.",
    ),
    HelpGuideEntry(
        tier = HelpTier.GETTING_STARTED,
        category = "Basics",
        question = "Why didn't my Home feed jump or disappear while I was looking at a song?",
        answer = "Onyx keeps your visible Home shelves frozen until you pull down to refresh or tap the 'New Home picks ready' pill, so you never lose something you were about to tap.",
    ),
    HelpGuideEntry(
        tier = HelpTier.GETTING_STARTED,
        category = "Player & Queue",
        question = "How do I collapse the Mini Player or undo a queue switch?",
        answer = "Swipe DOWN on the Mini Player to shrink it into a compact floating button while music keeps playing. If you accidentally replace your queue, tap the floating 'Back to previous queue' pill to restore your old queue and exact timestamp.",
    ),
    HelpGuideEntry(
        tier = HelpTier.GETTING_STARTED,
        category = "Sync & Likes",
        question = "How do Spotify and YouTube Music Liked Songs stay hearted?",
        answer = "Connect Spotify and/or YouTube Music in Settings → Connected Services. Onyx syncs your playlists and Liked Songs live in the background so every song you hearted on Spotify or YouTube Music automatically shows as hearted in Onyx too.",
    ),
    HelpGuideEntry(
        tier = HelpTier.GETTING_STARTED,
        category = "Vibe Mode (AOD)",
        question = "What is the ✨ Sparkle button on the player? (Half AOD vs Full AOD)",
        answer = "Tap ✨ to open Vibe Mode with glowing beat-reactive edge lighting. By default it starts in Half AOD — keeping your screen awake while letting you switch apps or press the power button normally. Tap the top pill anytime to switch to Full AOD (immersive OLED lock).",
    ),
    HelpGuideEntry(
        tier = HelpTier.GETTING_STARTED,
        category = "Social",
        question = "How do Onyx Friends and Contacts Discovery work?",
        answer = "Open Settings → Social & Listening Together → Onyx Friends & Contacts. Share your Onyx handle/code or invite link directly, or opt into the Contacts tab to privately check which contacts are on Onyx and send them a 1-tap invite.",
    ),
    HelpGuideEntry(
        tier = HelpTier.GETTING_STARTED,
        category = "Basics",
        question = "Where do I change Recommendation Provider or switch Simple/Pro Settings?",
        answer = "Recommendation Provider (YouTube Music or Spotify) lives in Settings → Home, Feed & Library. Toggle 'Pro & Audiophile Settings' at the very top of Settings anytime to show or hide advanced technical options.",
    ),

    // ── Pro & Audiophile ──
    HelpGuideEntry(
        tier = HelpTier.PRO_AUDIOPHILE,
        category = "DJ & AutoMix",
        question = "How do I use the Manual DJ Mix Studio?",
        answer = "Open the Audio Controls sheet inside the Full Player and tap 'Manual DJ Mix Studio' (or open AutoMix controls). Use 1-tap DJ presets (Club Bass Swap, Filter Wash, Vocal-Safe), live 3-band EQ Kill buttons, HPF/LPF Filter Sweeps, ±15% tempo nudges, Deck Gain Trims, and Set Cue-Out Here.",
    ),
    HelpGuideEntry(
        tier = HelpTier.PRO_AUDIOPHILE,
        category = "Stats & Scrobbler",
        question = "How do I import ALL past Spotify & YouTube Music history without Last.fm duplicates?",
        answer = "Open Stats → Sync & Import (or Settings → Scrobbling & History → Scrobbler & History Import). Sync recent Spotify/YT/Last.fm plays live, or import your full Spotify Privacy ZIP or Google Takeout HTML/JSON archive. Turn on Double-Scrobble Protection so external Last.fm apps never double-count Onyx plays.",
    ),
    HelpGuideEntry(
        tier = HelpTier.PRO_AUDIOPHILE,
        category = "Stats & Scrobbler",
        question = "How do Genres & Listening Insights work?",
        answer = "Onyx normalizes and resolves genres using Last.fm, Deezer, and iTunes with persistent caching, then computes true share percentages across all your listening time. Tap any genre in Stats or Genres Showcase to sort by Most Played, Recommended, or A–Z.",
    ),
    HelpGuideEntry(
        tier = HelpTier.PRO_AUDIOPHILE,
        category = "Audio Engine",
        question = "Why aren't AutoMix / Crossfade working when Bit-Perfect is enabled?",
        answer = "Bit-Perfect Mode bypasses all software DSP and sends untouched PCM straight to your hardware/USB DAC, which disables software mixing and EQ. Turn off Bit-Perfect in Settings → Audio → Pro & Audiophile Engine to re-enable AutoMix, EQ, and Clarity.",
    ),
    HelpGuideEntry(
        tier = HelpTier.PRO_AUDIOPHILE,
        category = "Audio Engine",
        question = "How does fast startup & Hi-Res quality switching work?",
        answer = "Onyx checks cached & best-quality streams within 0.25s; if the Hi-Res/FLAC server takes longer, it starts playing immediately and smoothly upgrades once to your selected quality without ping-ponging codecs up and down.",
    ),
    HelpGuideEntry(
        tier = HelpTier.PRO_AUDIOPHILE,
        category = "Lyrics",
        question = "How do I fix out-of-sync lyrics (Lyrics Sync Offset)?",
        answer = "Inside the Lyrics view (or from the player's ⋮ menu), tap 'Lyrics sync offset' and nudge by ±0.1s or ±0.5s. Your offset is remembered per song.",
    ),
)

/**
 * Searchable two-tier in-app Help & Feature Guide bottom sheet
 * (Getting Started for everyday listeners + Pro & Audiophile for power users).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnyxHelpCenterSheet(
    onDismiss: () -> Unit,
    onReplayTour: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var selectedTier by remember { mutableStateOf(HelpTier.GETTING_STARTED) }

    val filtered = remember(query, selectedTier) {
        val q = query.trim().lowercase()
        HELP_GUIDE_ENTRIES.filter { entry ->
            // When searching, search across BOTH tiers so users always find their answer!
            val tierMatch = q.isNotEmpty() || entry.tier == selectedTier
            val qMatch = q.isEmpty() ||
                entry.question.lowercase().contains(q) ||
                entry.answer.lowercase().contains(q) ||
                entry.category.lowercase().contains(q)
            tierMatch && qMatch
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
            // Non-squished header row: Logo + Title + Close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OnyxBrandLogo(
                    size = 38.dp,
                    shape = RoundedCornerShape(12.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Help & Feature Guide",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Plain-English answers for basic & pro users",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close help")
                }
            }

            if (onReplayTour != null) {
                FilledTonalButton(
                    onClick = {
                        onDismiss()
                        onReplayTour()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Replay 3-Step Welcome Tour")
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Ask anything (DJ mix, AOD, Spotify, scrobbler…)") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            // Two-tier audience tabs (Getting Started vs Pro & Audiophile)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                HelpTier.entries.forEach { tier ->
                    FilterChip(
                        selected = selectedTier == tier && query.isBlank(),
                        onClick = {
                            selectedTier = tier
                            if (query.isNotBlank()) query = ""
                        },
                        label = {
                            Text(
                                text = tier.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        modifier = Modifier.weight(1f),
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
                                text = "${item.tier.label} · ${item.category}".uppercase(),
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
