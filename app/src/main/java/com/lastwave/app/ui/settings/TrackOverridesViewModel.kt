package com.lastwave.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.local.AlbumOverride
import com.lastwave.app.data.local.DefaultPlayerMode
import com.lastwave.app.data.local.TrackOverride
import com.lastwave.app.data.local.TrackOverridesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TrackOverridesUiState(
    val all: List<TrackOverride> = emptyList(),
    val filtered: List<TrackOverride> = emptyList(),
    val allAlbums: List<AlbumOverride> = emptyList(),
    val filteredAlbums: List<AlbumOverride> = emptyList(),
    val query: String = "",
)

@HiltViewModel
class TrackOverridesViewModel @Inject constructor(
    private val repository: TrackOverridesRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    val state: StateFlow<TrackOverridesUiState> = combine(
        repository.overrides,
        repository.albumOverrides,
        query,
    ) { trackMap, albumMap, q ->
        val allTracks = trackMap.values.sortedBy { it.title.lowercase() }
        val filteredTracks = if (q.isBlank()) {
            allTracks
        } else {
            allTracks.filter {
                it.title.contains(q, true) ||
                    it.artist.contains(q, true) ||
                    settingLabel(it).contains(q, true)
            }
        }
        val allAlbums = albumMap.values.sortedBy { it.album.lowercase() }
        val filteredAlbums = if (q.isBlank()) {
            allAlbums
        } else {
            allAlbums.filter {
                it.album.contains(q, true) ||
                    it.artist.contains(q, true) ||
                    albumSettingLabel(it).contains(q, true)
            }
        }
        TrackOverridesUiState(
            all = allTracks,
            filtered = filteredTracks,
            allAlbums = allAlbums,
            filteredAlbums = filteredAlbums,
            query = q,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrackOverridesUiState())

    fun setQuery(value: String) {
        query.value = value
    }

    fun save(override: TrackOverride) {
        viewModelScope.launch {
            repository.setOverride(override)
            _toast.value = if (override.isActive) "Saved for \"${override.title}\"" else "Reset to defaults"
        }
    }

    fun remove(id: String) {
        viewModelScope.launch {
            repository.removeOverride(id)
            _toast.value = "Override removed"
        }
    }

    fun saveAlbum(override: AlbumOverride) {
        viewModelScope.launch {
            repository.setAlbumOverride(override)
            _toast.value = if (override.isActive) "Saved for album \"${override.album}\"" else "Album reset to defaults"
        }
    }

    fun removeAlbum(id: String) {
        viewModelScope.launch {
            repository.removeAlbumOverride(id)
            _toast.value = "Album override removed"
        }
    }

    fun dismissToast() {
        _toast.value = null
    }

    companion object {
        fun settingLabel(override: TrackOverride): String = buildList {
            if (override.defaultPlayMode != DefaultPlayerMode.FOLLOW_GLOBAL) add(override.defaultPlayMode.label.lowercase())
            if (override.videoDisabled) add("video off")
            if (override.canvasDisabled) add("canvas off")
            if (override.animationsDisabled) add("reduced motion")
        }.joinToString(", ").ifBlank { "defaults" }

        fun albumSettingLabel(override: AlbumOverride): String = buildList {
            if (override.defaultPlayMode != DefaultPlayerMode.FOLLOW_GLOBAL) add(override.defaultPlayMode.label.lowercase())
            if (override.videoDisabled) add("video off")
            if (override.canvasDisabled) add("canvas off")
            if (override.animationsDisabled) add("reduced motion")
        }.joinToString(", ").ifBlank { "defaults" }
    }
}
