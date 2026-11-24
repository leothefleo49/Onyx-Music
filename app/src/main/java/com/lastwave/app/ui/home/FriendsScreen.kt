package com.lastwave.app.ui.home

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.friends.OnyxFriend
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.GlassHeaderLayout
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.common.safeDrawingBottomPadding
import com.lastwave.app.ui.common.safeHorizontalContentPadding
import com.lastwave.app.ui.player.LocalMiniPlayerScrollClearance

/**
 * Onyx-native Friends & Contacts Discovery screen.
 *
 * Completely owned by Onyx Music (no "Last.fm friends" identity!):
 *  1. My Friends — pin/remove friends, share tracks, launch Onyx Jam sessions
 *  2. My Code — your Onyx profile, shareable ONX-XXXX-XXXX code & deep link, add friend
 *  3. Contacts — opt-in Contacts Discovery mode with local privacy hashing & 1-tap invite
 */
@Composable
fun FriendsScreen(
    viewModel: HomeViewModel,
    onBack: () -> Unit,
    onOpenFriendProfile: (username: String, displayName: String?, avatarUrl: String?) -> Unit = { _, _, _ -> },
    friendsViewModel: FriendsViewModel = hiltViewModel(),
) {
    val state by friendsViewModel.uiState.collectAsStateWithLifecycle()
    val homeUiState by viewModel.uiState.collectAsStateWithLifecycle()
    val jamViewModel: com.lastwave.app.ui.jam.LastWaveJamViewModel = hiltViewModel()
    val context = LocalContext.current

    var selectedTab by remember { mutableStateOf(FriendsTab.FRIENDS) }
    var showJamSheet by remember { mutableStateOf(false) }
    var showEditProfileDialog by remember { mutableStateOf(false) }
    var addCodeInput by remember { mutableStateOf("") }
    var addNameInput by remember { mutableStateOf("") }
    var contactSearch by remember { mutableStateOf("") }

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        friendsViewModel.onContactsPermissionResult(granted)
    }

    fun copyText(label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText(label, text))
        android.widget.Toast.makeText(context, "$label copied", android.widget.Toast.LENGTH_SHORT).show()
    }

    fun shareText(subject: String, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, subject)) }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        GlassHeaderLayout(
            Modifier
                .fillMaxSize()
                .adaptiveContentWidth(maxWidth = 760.dp),
            header = { headerModifier, headerBackdrop ->
                ExpressiveHeader(
                    modifier = headerModifier,
                    backdrop = headerBackdrop,
                    title = "Onyx Friends",
                    subtitle = "@${state.profile.handle} \u00b7 ${state.profile.friendCode}",
                    onBack = onBack,
                    actions = {
                        IconButton(onClick = { shareText("Join me on Onyx Music", friendsViewModel.buildInviteMessage()) }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share your Onyx invite")
                        }
                    },
                )
            },
        ) { headerInset ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .safeHorizontalContentPadding(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp + headerInset,
                    bottom = 28.dp + LocalMiniPlayerScrollClearance.current + safeDrawingBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Status banner
                state.statusMessage?.let { msg ->
                    item(key = "status_banner") {
                        Surface(
                            onClick = friendsViewModel::clearStatusMessage,
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            )
                        }
                    }
                }

                // Segmented 3-tab selector
                item(key = "friends_tabs") {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            FriendsTab.entries.forEach { tab ->
                                val active = selectedTab == tab
                                Surface(
                                    onClick = { selectedTab = tab },
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (active) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Box(
                                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = tab.label,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                            color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                when (selectedTab) {
                    FriendsTab.FRIENDS -> {
                        if (homeUiState.isViewingFriend) {
                            item(key = "back_to_own_profile") {
                                Surface(
                                    onClick = {
                                        viewModel.returnToOwnProfile()
                                        onBack()
                                    },
                                    shape = RoundedCornerShape(18.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Filled.AccountCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text("Back to my profile", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                            Text("Return Stats view to your own account", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }

                        if (state.sortedFriends.isEmpty()) {
                            item(key = "friends_empty") {
                                Surface(
                                    shape = RoundedCornerShape(24.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(22.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.GroupAdd,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(40.dp),
                                        )
                                        Text(
                                            text = "No Onyx Friends yet",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            text = "Share your Onyx friend code, add friends by code or invite link, or check which of your contacts use Onyx.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Button(
                                                onClick = { selectedTab = FriendsTab.ADD },
                                                modifier = Modifier.weight(1f),
                                            ) {
                                                Text("Add Friend", maxLines = 1)
                                            }
                                            FilledTonalButton(
                                                onClick = { selectedTab = FriendsTab.CONTACTS },
                                                modifier = Modifier.weight(1f),
                                            ) {
                                                Text("Find Contacts", maxLines = 1)
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            items(state.sortedFriends, key = { it.id }) { friend ->
                                OnyxFriendCard(
                                    friend = friend,
                                    canShareCurrentTrack = !state.currentTrackLabel.isNullOrBlank(),
                                    onOpen = {
                                        if (!friend.lastFmUsername.isNullOrBlank()) {
                                            onOpenFriendProfile(friend.lastFmUsername, friend.displayName, friend.avatarUrl)
                                        }
                                    },
                                    onStartJam = { showJamSheet = true },
                                    onShareTrack = {
                                        val track = state.currentTrackLabel ?: return@OnyxFriendCard
                                        friendsViewModel.recordSharedTrack(friend.id, track)
                                        shareText(
                                            "Listen with me on Onyx Music",
                                            "Hey ${friend.displayName}, check out $track on Onyx Music!",
                                        )
                                    },
                                    onTogglePin = { friendsViewModel.togglePinned(friend.id) },
                                    onRemove = { friendsViewModel.removeFriend(friend.id) },
                                )
                            }
                        }

                        if (state.isLastFmConnected) {
                            item(key = "import_lastfm_optional") {
                                OutlinedButton(
                                    onClick = friendsViewModel::importFromLastFm,
                                    enabled = !state.isImportingLastFm,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        if (state.isImportingLastFm) "Importing…" else "Import Existing Last.fm Friends into Onyx",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }

                    FriendsTab.ADD -> {
                        // Your Onyx Identity & Shareable Code Card
                        item(key = "my_onyx_code") {
                            Surface(
                                shape = RoundedCornerShape(24.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Filled.QrCode2,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(28.dp),
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                text = state.profile.displayName,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = "@${state.profile.handle}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                            )
                                        }
                                        TextButton(onClick = { showEditProfileDialog = true }) {
                                            Text("Edit")
                                        }
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    text = "YOUR ONYX FRIEND CODE",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                Text(
                                                    text = state.profile.friendCode,
                                                    style = MaterialTheme.typography.titleLarge,
                                                    fontWeight = FontWeight.Black,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                            IconButton(onClick = { copyText("Onyx friend code", state.profile.friendCode) }) {
                                                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy friend code")
                                            }
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Button(
                                            onClick = { shareText("Add me on Onyx Music", friendsViewModel.buildInviteMessage()) },
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Share Invite", maxLines = 1)
                                        }
                                        OutlinedButton(
                                            onClick = { copyText("Onyx invite link", friendsViewModel.buildInviteUri()) },
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Text("Copy Link", maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }

                        // Add Friend Form
                        item(key = "add_friend_form") {
                            Surface(
                                shape = RoundedCornerShape(24.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        text = "Add an Onyx Friend",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = "Paste their ONX-XXXX-XXXX code, onyxmusic:// invite link, or @handle.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    OutlinedTextField(
                                        value = addCodeInput,
                                        onValueChange = { addCodeInput = it },
                                        label = { Text("Friend code, invite link, or @handle") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    OutlinedTextField(
                                        value = addNameInput,
                                        onValueChange = { addNameInput = it },
                                        label = { Text("Nickname (optional)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Button(
                                        onClick = {
                                            friendsViewModel.addFriendFromInput(addCodeInput, addNameInput)
                                            addCodeInput = ""
                                            addNameInput = ""
                                            selectedTab = FriendsTab.FRIENDS
                                        },
                                        enabled = addCodeInput.isNotBlank(),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Add Friend")
                                    }
                                }
                            }
                        }
                    }

                    FriendsTab.CONTACTS -> {
                        item(key = "contacts_privacy_card") {
                            Surface(
                                shape = RoundedCornerShape(24.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Filled.Contacts,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp),
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                text = "Contacts Discovery Mode",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                            )
                                            Text(
                                                text = "Check which contacts already use Onyx or invite them with one tap. Contact matching uses local SHA-256 hashes on your device — your contact book is never uploaded.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Switch(
                                            checked = state.contactsDiscoveryEnabled && state.hasContactsPermission,
                                            onCheckedChange = { enable ->
                                                if (enable) {
                                                    if (state.hasContactsPermission) {
                                                        friendsViewModel.setContactsDiscoveryEnabled(true)
                                                    } else {
                                                        contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                                                    }
                                                } else {
                                                    friendsViewModel.setContactsDiscoveryEnabled(false)
                                                }
                                            },
                                        )
                                    }

                                    if (!state.hasContactsPermission) {
                                        Button(
                                            onClick = { contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS) },
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text("Allow Contacts Access to Find Friends")
                                        }
                                    }
                                }
                            }
                        }

                        if (state.contactsDiscoveryEnabled && state.hasContactsPermission) {
                            item(key = "contacts_search") {
                                OutlinedTextField(
                                    value = contactSearch,
                                    onValueChange = { contactSearch = it },
                                    placeholder = { Text("Search contacts…") },
                                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            if (state.isScanningContacts) {
                                item(key = "contacts_loading") {
                                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator()
                                    }
                                }
                            } else {
                                val filteredContacts = state.contacts.filter { c ->
                                    contactSearch.isBlank() ||
                                        c.displayName.contains(contactSearch, ignoreCase = true) ||
                                        c.detail.contains(contactSearch, ignoreCase = true)
                                }
                                if (filteredContacts.isEmpty()) {
                                    item(key = "contacts_empty") {
                                        Text(
                                            text = "No matching contacts found on this device.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(12.dp),
                                        )
                                    }
                                } else {
                                    items(filteredContacts, key = { it.contactHash }) { contact ->
                                        Surface(
                                            shape = RoundedCornerShape(18.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(40.dp)
                                                        .clip(CircleShape)
                                                        .background(
                                                            if (contact.matchedOnyxFriend != null) {
                                                                MaterialTheme.colorScheme.primaryContainer
                                                            } else {
                                                                MaterialTheme.colorScheme.surfaceContainerHighest
                                                            },
                                                        ),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Text(
                                                        text = contact.displayName.take(1).uppercase(),
                                                        style = MaterialTheme.typography.titleMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (contact.matchedOnyxFriend != null) {
                                                            MaterialTheme.colorScheme.onPrimaryContainer
                                                        } else {
                                                            MaterialTheme.colorScheme.onSurfaceVariant
                                                        },
                                                    )
                                                }
                                                Spacer(Modifier.width(12.dp))
                                                Column(Modifier.weight(1f)) {
                                                    Text(
                                                        text = contact.displayName,
                                                        style = MaterialTheme.typography.bodyLarge,
                                                        fontWeight = FontWeight.SemiBold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    Text(
                                                        text = if (contact.matchedOnyxFriend != null) {
                                                            "Connected on Onyx (@${contact.matchedOnyxFriend.handle})"
                                                        } else {
                                                            contact.detail
                                                        },
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = if (contact.matchedOnyxFriend != null) {
                                                            MaterialTheme.colorScheme.primary
                                                        } else {
                                                            MaterialTheme.colorScheme.onSurfaceVariant
                                                        },
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                                Spacer(Modifier.width(8.dp))
                                                if (contact.matchedOnyxFriend != null) {
                                                    FilledTonalButton(onClick = { showJamSheet = true }) {
                                                        Text("Jam", maxLines = 1)
                                                    }
                                                } else {
                                                    OutlinedButton(
                                                        onClick = {
                                                            shareText(
                                                                "Invite ${contact.displayName} to Onyx Music",
                                                                "Hey ${contact.displayName}! ${friendsViewModel.buildInviteMessage()}",
                                                            )
                                                        },
                                                    ) {
                                                        Text("Invite", maxLines = 1)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Incoming deep-link friend invite confirmation
    state.pendingInvite?.let { invite ->
        AlertDialog(
            onDismissRequest = friendsViewModel::dismissPendingInvite,
            title = { Text("Add Onyx Friend?") },
            text = {
                Text("${invite.displayName} (@${invite.handle}${if (invite.code.isNotBlank()) " · ${invite.code}" else ""}) invited you to connect on Onyx Music.")
            },
            confirmButton = {
                Button(onClick = friendsViewModel::acceptPendingInvite) {
                    Text("Add Friend")
                }
            },
            dismissButton = {
                TextButton(onClick = friendsViewModel::dismissPendingInvite) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showEditProfileDialog) {
        var nameDraft by remember(state.profile) { mutableStateOf(state.profile.displayName) }
        var handleDraft by remember(state.profile) { mutableStateOf(state.profile.handle) }
        AlertDialog(
            onDismissRequest = { showEditProfileDialog = false },
            title = { Text("Edit Onyx Profile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it },
                        label = { Text("Display name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = handleDraft,
                        onValueChange = { handleDraft = it },
                        label = { Text("@handle") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        friendsViewModel.updateProfile(nameDraft, handleDraft)
                        showEditProfileDialog = false
                    },
                    enabled = nameDraft.isNotBlank(),
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditProfileDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showJamSheet) {
        com.lastwave.app.ui.jam.LastWaveJamSheet(
            viewModel = jamViewModel,
            onDismiss = { showJamSheet = false },
        )
    }
}

@Composable
private fun OnyxFriendCard(
    friend: OnyxFriend,
    canShareCurrentTrack: Boolean,
    onOpen: () -> Unit,
    onStartJam: () -> Unit,
    onShareTrack: () -> Unit,
    onTogglePin: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        onClick = onOpen,
        enabled = !friend.lastFmUsername.isNullOrBlank(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (!friend.avatarUrl.isNullOrBlank()) {
                    ArtworkImage(
                        name = friend.displayName,
                        artist = friend.handle,
                        embeddedUrl = friend.avatarUrl,
                        fallbackIcon = Icons.Filled.AccountCircle,
                        modifier = Modifier.size(46.dp).clip(CircleShape),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = friend.displayName.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = friend.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append("@${friend.handle}")
                            if (friend.friendCode.isNotBlank()) append(" \u00b7 ${friend.friendCode}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!friend.lastSharedTrack.isNullOrBlank()) {
                        Text(
                            text = "Last shared: ${friend.lastSharedTrack}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onTogglePin) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = if (friend.isPinned) "Unpin" else "Pin",
                        tint = if (friend.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Remove friend",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = onStartJam,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Headphones, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Start Jam", maxLines = 1)
                }
                OutlinedButton(
                    onClick = onShareTrack,
                    enabled = canShareCurrentTrack,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Share Song", maxLines = 1)
                }
            }
        }
    }
}
