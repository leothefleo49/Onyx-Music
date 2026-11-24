package com.lastwave.app.data.friends

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS_NAME = "onyx_friends_store_v1"
private const val KEY_PROFILE = "profile_json"
private const val KEY_FRIENDS = "friends_json"
private const val KEY_CONTACTS_OPT_IN = "contacts_discovery_opt_in"

@Serializable
data class OnyxUserProfile(
    val userId: String,
    val displayName: String,
    val handle: String,
    val friendCode: String,
    val contactHashes: List<String> = emptyList(),
)

@Serializable
data class OnyxFriend(
    val id: String,
    val displayName: String,
    val handle: String,
    val friendCode: String = "",
    val lastFmUsername: String? = null,
    val avatarUrl: String? = null,
    val contactHash: String? = null,
    val isPinned: Boolean = false,
    val addedAtMs: Long = System.currentTimeMillis(),
    val lastSharedTrack: String? = null,
)

data class OnyxFriendInvite(
    val code: String,
    val handle: String,
    val displayName: String,
    val userId: String?,
    val contactHash: String? = null,
)

/**
 * Local-first Onyx-native Friends & Identity store.
 *
 * Manages the user's Onyx profile (display name, @handle, ONX-XXXX-XXXX code),
 * shareable `onyxmusic://friend/add?...` deep links, the user's Onyx friends list,
 * and opt-in Contacts Discovery preferences. Completely independent of Last.fm.
 */
@Singleton
class OnyxFriendsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private val _profile = MutableStateFlow(loadOrCreateProfile())
    val profile: StateFlow<OnyxUserProfile> = _profile.asStateFlow()

    private val _friends = MutableStateFlow(loadFriends())
    val friends: StateFlow<List<OnyxFriend>> = _friends.asStateFlow()

    private val _contactsDiscoveryEnabled = MutableStateFlow(prefs.getBoolean(KEY_CONTACTS_OPT_IN, false))
    val contactsDiscoveryEnabled: StateFlow<Boolean> = _contactsDiscoveryEnabled.asStateFlow()

    private val _pendingInvite = MutableStateFlow<OnyxFriendInvite?>(null)
    val pendingInvite: StateFlow<OnyxFriendInvite?> = _pendingInvite.asStateFlow()

    fun updateProfile(displayName: String, handle: String) {
        val cleanName = displayName.trim().ifBlank { _profile.value.displayName }
        val cleanHandle = sanitizeHandle(handle.ifBlank { cleanName })
        val updated = _profile.value.copy(displayName = cleanName, handle = cleanHandle)
        _profile.value = updated
        persistProfile(updated)
    }

    fun seedDefaultNameIfUnset(suggestedName: String?) {
        val clean = suggestedName?.trim().orEmpty()
        if (clean.isBlank() || clean.equals("Guest User", ignoreCase = true) || clean.equals("Google account", ignoreCase = true)) return
        val current = _profile.value
        if (current.displayName == "Onyx Listener") {
            updateProfile(clean, clean)
        }
    }

    fun buildInviteUri(): String {
        val p = _profile.value
        val builder = Uri.Builder()
            .scheme("onyxmusic")
            .authority("friend")
            .appendPath("add")
            .appendQueryParameter("code", p.friendCode)
            .appendQueryParameter("handle", p.handle)
            .appendQueryParameter("name", p.displayName)
            .appendQueryParameter("uid", p.userId)
        p.contactHashes.firstOrNull()?.let { builder.appendQueryParameter("ch", it) }
        return builder.build().toString()
    }

    fun buildInviteMessage(): String {
        val p = _profile.value
        val link = buildInviteUri()
        return "Add me on Onyx Music! My code is ${p.friendCode} (@${p.handle}). Tap to connect: $link"
    }

    fun setPendingInviteFromUri(uri: Uri) {
        val code = uri.getQueryParameter("code").orEmpty().trim().uppercase()
        val handle = sanitizeHandle(uri.getQueryParameter("handle").orEmpty())
        val name = uri.getQueryParameter("name").orEmpty().trim()
        val uid = uri.getQueryParameter("uid")?.takeIf { it.isNotBlank() }
        val ch = uri.getQueryParameter("ch")?.takeIf { it.isNotBlank() }
        if (code.isBlank() && handle.isBlank() && name.isBlank()) return
        _pendingInvite.value = OnyxFriendInvite(
            code = code,
            handle = handle.ifBlank { code.lowercase() },
            displayName = name.ifBlank { handle.ifBlank { code } },
            userId = uid,
            contactHash = ch,
        )
    }

    fun consumePendingInvite() {
        _pendingInvite.value = null
    }

    /**
     * Parses either a pasted `onyxmusic://friend/add?...` link, an `ONX-XXXX-XXXX` code,
     * or a `@handle` / display name, and adds the friend to the user's Onyx Friends list.
     */
    fun addFriendFromInput(rawInput: String, optionalDisplayName: String = ""): Result<OnyxFriend> {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return Result.failure(IllegalArgumentException("Enter an Onyx friend code, link, or @handle"))

        if (trimmed.startsWith("onyxmusic://", ignoreCase = true) || trimmed.contains("code=", ignoreCase = true)) {
            val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
            val code = uri?.getQueryParameter("code").orEmpty().trim().uppercase()
            val handle = sanitizeHandle(uri?.getQueryParameter("handle").orEmpty())
            val name = uri?.getQueryParameter("name").orEmpty().trim()
            val uid = uri?.getQueryParameter("uid")?.takeIf { it.isNotBlank() }
            val ch = uri?.getQueryParameter("ch")?.takeIf { it.isNotBlank() }
            if (code.isNotBlank() || handle.isNotBlank() || name.isNotBlank()) {
                return addFriend(
                    OnyxFriend(
                        id = uid ?: code.ifBlank { UUID.randomUUID().toString() },
                        displayName = optionalDisplayName.trim().ifBlank { name.ifBlank { handle.ifBlank { code } } },
                        handle = handle.ifBlank { sanitizeHandle(name.ifBlank { code }) },
                        friendCode = code,
                        contactHash = ch,
                    ),
                )
            }
        }

        val isCode = trimmed.uppercase().startsWith("ONX-")
        val code = if (isCode) trimmed.uppercase() else ""
        val handle = if (isCode) {
            sanitizeHandle(optionalDisplayName.ifBlank { code })
        } else {
            sanitizeHandle(trimmed)
        }
        val display = optionalDisplayName.trim().ifBlank {
            if (isCode) code else trimmed.removePrefix("@")
        }
        return addFriend(
            OnyxFriend(
                id = if (isCode) code else "handle:$handle",
                displayName = display,
                handle = handle,
                friendCode = code,
            ),
        )
    }

    fun addFriend(friend: OnyxFriend): Result<OnyxFriend> {
        val myCode = _profile.value.friendCode
        if (friend.friendCode.isNotBlank() && friend.friendCode.equals(myCode, ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("That's your own Onyx friend code!"))
        }
        val current = _friends.value
        val duplicate = current.any { existing ->
            (friend.friendCode.isNotBlank() && existing.friendCode.equals(friend.friendCode, ignoreCase = true)) ||
                (friend.handle.isNotBlank() && existing.handle.equals(friend.handle, ignoreCase = true)) ||
                (friend.id == existing.id)
        }
        if (duplicate) {
            return Result.failure(IllegalArgumentException("${friend.displayName} is already in your Onyx Friends."))
        }
        val updated = listOf(friend) + current
        _friends.value = updated
        persistFriends(updated)
        return Result.success(friend)
    }

    fun togglePinned(friendId: String) {
        val updated = _friends.value.map { f ->
            if (f.id == friendId) f.copy(isPinned = !f.isPinned) else f
        }
        _friends.value = updated
        persistFriends(updated)
    }

    fun removeFriend(friendId: String) {
        val updated = _friends.value.filterNot { it.id == friendId }
        _friends.value = updated
        persistFriends(updated)
    }

    fun recordSharedTrack(friendId: String, trackLabel: String) {
        val updated = _friends.value.map { f ->
            if (f.id == friendId) f.copy(lastSharedTrack = trackLabel) else f
        }
        _friends.value = updated
        persistFriends(updated)
    }

    fun setContactsDiscoveryEnabled(enabled: Boolean) {
        _contactsDiscoveryEnabled.value = enabled
        prefs.edit().putBoolean(KEY_CONTACTS_OPT_IN, enabled).apply()
    }

    private fun loadOrCreateProfile(): OnyxUserProfile {
        val raw = prefs.getString(KEY_PROFILE, null)
        if (!raw.isNullOrBlank()) {
            runCatching { return json.decodeFromString<OnyxUserProfile>(raw) }
        }
        val uid = UUID.randomUUID().toString()
        val created = OnyxUserProfile(
            userId = uid,
            displayName = "Onyx Listener",
            handle = "onyx_${uid.take(6).lowercase()}",
            friendCode = generateFriendCode(uid),
        )
        persistProfile(created)
        return created
    }

    private fun loadFriends(): List<OnyxFriend> {
        val raw = prefs.getString(KEY_FRIENDS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<OnyxFriend>>(raw) }.getOrDefault(emptyList())
    }

    private fun persistProfile(profile: OnyxUserProfile) {
        runCatching { prefs.edit().putString(KEY_PROFILE, json.encodeToString(profile)).apply() }
    }

    private fun persistFriends(friends: List<OnyxFriend>) {
        runCatching { prefs.edit().putString(KEY_FRIENDS, json.encodeToString(friends)).apply() }
    }

    companion object {
        private val CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

        fun generateFriendCode(seed: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
            val chars = CharArray(8) { idx ->
                val b = digest[idx].toInt() and 0xFF
                CODE_ALPHABET[b % CODE_ALPHABET.length]
            }
            return "ONX-${String(chars, 0, 4)}-${String(chars, 4, 4)}"
        }

        fun sanitizeHandle(raw: String): String =
            raw.trim().removePrefix("@").lowercase()
                .replace(Regex("[^a-z0-9._-]"), "")
                .take(24)
                .ifBlank { "listener" }

        fun sha256Short(normalizedContact: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(normalizedContact.toByteArray())
            return bytes.take(12).joinToString("") { "%02x".format(it) }
        }
    }
}
