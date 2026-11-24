package com.lastwave.app.data.friends

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Immutable
data class DiscoveredContact(
    val displayName: String,
    val detail: String,
    val contactHash: String,
    val matchedOnyxFriend: OnyxFriend? = null,
)

/**
 * Privacy-first opt-in Contacts Discovery helper.
 *
 * Only runs after explicit user opt-in AND runtime `READ_CONTACTS` permission.
 * Normalizes phone numbers / emails locally and compares SHA-256 hashes against
 * known Onyx friend signatures without ever uploading raw contact books.
 */
object ContactsDiscoveryHelper {

    fun hasContactsPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    suspend fun scanContacts(
        context: Context,
        knownFriends: List<OnyxFriend>,
    ): List<DiscoveredContact> = withContext(Dispatchers.IO) {
        if (!hasContactsPermission(context)) return@withContext emptyList()

        val friendByHash = knownFriends
            .mapNotNull { f -> f.contactHash?.takeIf { it.isNotBlank() }?.let { it to f } }
            .toMap()
        val friendByName = knownFriends.associateBy { it.displayName.trim().lowercase() }

        val seen = LinkedHashMap<String, DiscoveredContact>()
        val resolver = context.contentResolver

        // 1. Phone contacts
        runCatching {
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext() && seen.size < 400) {
                    val name = if (nameCol >= 0) cursor.getString(nameCol)?.trim().orEmpty() else ""
                    val rawNumber = if (numCol >= 0) cursor.getString(numCol)?.trim().orEmpty() else ""
                    if (name.isBlank() || rawNumber.isBlank()) continue
                    val digits = rawNumber.filter { it.isDigit() }
                    if (digits.length < 7) continue
                    val norm = "tel:${digits.takeLast(10)}"
                    val hash = OnyxFriendsStore.sha256Short(norm)
                    if (hash !in seen) {
                        val matched = friendByHash[hash] ?: friendByName[name.lowercase()]
                        seen[hash] = DiscoveredContact(
                            displayName = name,
                            detail = rawNumber,
                            contactHash = hash,
                            matchedOnyxFriend = matched,
                        )
                    }
                }
            }
        }

        // 2. Email contacts (for contacts without a phone number)
        runCatching {
            resolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Email.ADDRESS,
                ),
                null,
                null,
                "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME} ASC",
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME)
                val emailCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
                while (cursor.moveToNext() && seen.size < 500) {
                    val name = if (nameCol >= 0) cursor.getString(nameCol)?.trim().orEmpty() else ""
                    val email = if (emailCol >= 0) cursor.getString(emailCol)?.trim()?.lowercase().orEmpty() else ""
                    if (email.isBlank()) continue
                    val display = name.ifBlank { email.substringBefore('@') }
                    val hash = OnyxFriendsStore.sha256Short("mailto:$email")
                    if (hash !in seen && seen.values.none { it.displayName.equals(display, ignoreCase = true) }) {
                        val matched = friendByHash[hash] ?: friendByName[display.lowercase()]
                        seen[hash] = DiscoveredContact(
                            displayName = display,
                            detail = email,
                            contactHash = hash,
                            matchedOnyxFriend = matched,
                        )
                    }
                }
            }
        }

        seen.values.sortedWith(
            compareByDescending<DiscoveredContact> { it.matchedOnyxFriend != null }
                .thenBy { it.displayName.lowercase() },
        )
    }
}
