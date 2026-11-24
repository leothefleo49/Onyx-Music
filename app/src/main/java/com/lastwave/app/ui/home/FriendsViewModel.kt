package com.lastwave.app.ui.home

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.friends.ContactsDiscoveryHelper
import com.lastwave.app.data.friends.DiscoveredContact
import com.lastwave.app.data.friends.OnyxFriend
import com.lastwave.app.data.friends.OnyxFriendInvite
import com.lastwave.app.data.friends.OnyxFriendsStore
import com.lastwave.app.data.friends.OnyxUserProfile
import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.repository.HomeRepository
import com.lastwave.app.data.ytmusic.YtMusicAuthManager
import com.lastwave.app.playback.MusicPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class FriendsTab(val label: String) {
    FRIENDS("My Friends"),
    ADD("My Code"),
    CONTACTS("Contacts"),
}

@Immutable
data class OnyxFriendsUiState(
    val profile: OnyxUserProfile = OnyxUserProfile("", "Onyx Listener", "listener", "ONX-0000-0000"),
    val friends: List<OnyxFriend> = emptyList(),
    val pendingInvite: OnyxFriendInvite? = null,
    val contactsDiscoveryEnabled: Boolean = false,
    val hasContactsPermission: Boolean = false,
    val isScanningContacts: Boolean = false,
    val contacts: List<DiscoveredContact> = emptyList(),
    val isLastFmConnected: Boolean = false,
    val isImportingLastFm: Boolean = false,
    val currentTrackLabel: String? = null,
    val statusMessage: String? = null,
) {
    val sortedFriends: List<OnyxFriend>
        get() = friends.sortedWith(compareByDescending<OnyxFriend> { it.isPinned }.thenByDescending { it.addedAtMs })
}

@HiltViewModel
class FriendsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val friendsStore: OnyxFriendsStore,
    private val homeRepository: HomeRepository,
    private val sessionPreferences: SessionPreferences,
    private val ytAuth: YtMusicAuthManager,
    private val musicPlayer: MusicPlayer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        OnyxFriendsUiState(
            profile = friendsStore.profile.value,
            friends = friendsStore.friends.value,
            pendingInvite = friendsStore.pendingInvite.value,
            contactsDiscoveryEnabled = friendsStore.contactsDiscoveryEnabled.value,
            hasContactsPermission = ContactsDiscoveryHelper.hasContactsPermission(context),
        ),
    )
    val uiState: StateFlow<OnyxFriendsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                friendsStore.profile,
                friendsStore.friends,
                friendsStore.pendingInvite,
                friendsStore.contactsDiscoveryEnabled,
            ) { profile, friends, invite, contactsOptIn ->
                _uiState.value.copy(
                    profile = profile,
                    friends = friends,
                    pendingInvite = invite,
                    contactsDiscoveryEnabled = contactsOptIn,
                    hasContactsPermission = ContactsDiscoveryHelper.hasContactsPermission(context),
                )
            }.collect { next ->
                _uiState.value = next
            }
        }
        viewModelScope.launch {
            combine(sessionPreferences.session, ytAuth.connection) { sess, yt ->
                val connected = sess.username.isNotBlank() &&
                    !sess.username.equals("Guest User", ignoreCase = true) &&
                    sess.apiKey.isNotBlank()
                val suggested = yt.accountName.takeIf { yt.isConnected && it.isNotBlank() }
                    ?: sess.username.takeIf { connected }
                connected to suggested
            }.collect { (connected, suggested) ->
                friendsStore.seedDefaultNameIfUnset(suggested)
                _uiState.update { it.copy(isLastFmConnected = connected) }
            }
        }
        viewModelScope.launch {
            musicPlayer.chromeState.collect { chrome ->
                val label = chrome.current?.let { "${it.title} — ${it.artist}" }
                _uiState.update { it.copy(currentTrackLabel = label) }
            }
        }
        if (friendsStore.contactsDiscoveryEnabled.value && ContactsDiscoveryHelper.hasContactsPermission(context)) {
            scanContacts()
        }
    }

    fun updateProfile(displayName: String, handle: String) {
        friendsStore.updateProfile(displayName, handle)
        _uiState.update { it.copy(statusMessage = "Updated your Onyx profile") }
    }

    fun addFriendFromInput(input: String, displayName: String = "") {
        friendsStore.addFriendFromInput(input, displayName).fold(
            onSuccess = { added ->
                _uiState.update { it.copy(statusMessage = "Added ${added.displayName} to Onyx Friends") }
            },
            onFailure = { err ->
                _uiState.update { it.copy(statusMessage = err.message ?: "Couldn't add friend") }
            },
        )
    }

    fun acceptPendingInvite() {
        val invite = friendsStore.pendingInvite.value ?: return
        friendsStore.consumePendingInvite()
        friendsStore.addFriend(
            OnyxFriend(
                id = invite.userId ?: invite.code.ifBlank { "handle:${invite.handle}" },
                displayName = invite.displayName,
                handle = invite.handle,
                friendCode = invite.code,
                contactHash = invite.contactHash,
            ),
        ).fold(
            onSuccess = { added ->
                _uiState.update { it.copy(statusMessage = "${added.displayName} added to Onyx Friends!") }
            },
            onFailure = { err ->
                _uiState.update { it.copy(statusMessage = err.message) }
            },
        )
    }

    fun dismissPendingInvite() {
        friendsStore.consumePendingInvite()
    }

    fun togglePinned(friendId: String) = friendsStore.togglePinned(friendId)

    fun removeFriend(friendId: String) {
        friendsStore.removeFriend(friendId)
        _uiState.update { it.copy(statusMessage = "Friend removed") }
    }

    fun recordSharedTrack(friendId: String, trackLabel: String) {
        friendsStore.recordSharedTrack(friendId, trackLabel)
    }

    fun buildInviteMessage(): String = friendsStore.buildInviteMessage()
    fun buildInviteUri(): String = friendsStore.buildInviteUri()

    fun setContactsDiscoveryEnabled(enabled: Boolean) {
        friendsStore.setContactsDiscoveryEnabled(enabled)
        if (enabled && ContactsDiscoveryHelper.hasContactsPermission(context)) {
            scanContacts()
        } else if (!enabled) {
            _uiState.update { it.copy(contacts = emptyList()) }
        }
    }

    fun onContactsPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(hasContactsPermission = granted) }
        if (granted) {
            friendsStore.setContactsDiscoveryEnabled(true)
            scanContacts()
        } else {
            friendsStore.setContactsDiscoveryEnabled(false)
            _uiState.update { it.copy(statusMessage = "Contacts permission denied — Contacts Discovery stays off") }
        }
    }

    fun scanContacts() {
        if (!ContactsDiscoveryHelper.hasContactsPermission(context)) {
            _uiState.update { it.copy(hasContactsPermission = false) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isScanningContacts = true, hasContactsPermission = true) }
            val results = ContactsDiscoveryHelper.scanContacts(context, friendsStore.friends.value)
            _uiState.update { it.copy(isScanningContacts = false, contacts = results) }
        }
    }

    /** Optional secondary import for users who already follow friends on Last.fm. */
    fun importFromLastFm() {
        if (_uiState.value.isImportingLastFm) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isImportingLastFm = true) }
            homeRepository.fetchFriends().fold(
                onSuccess = { list ->
                    var addedCount = 0
                    for (entry in list) {
                        val res = friendsStore.addFriend(
                            OnyxFriend(
                                id = "lastfm:${entry.name.lowercase()}",
                                displayName = entry.displayName,
                                handle = OnyxFriendsStore.sanitizeHandle(entry.name),
                                lastFmUsername = entry.name,
                                avatarUrl = entry.avatarUrl,
                            ),
                        )
                        if (res.isSuccess) addedCount++
                    }
                    _uiState.update {
                        it.copy(
                            isImportingLastFm = false,
                            statusMessage = if (addedCount > 0) "Imported $addedCount friend(s) into Onyx" else "No new friends to import",
                        )
                    }
                },
                onFailure = {
                    _uiState.update { it.copy(isImportingLastFm = false, statusMessage = "Couldn't load friends to import") }
                },
            )
        }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }
}
