package sample

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.*

data class Song(val id: String)
data class PlayerState(
    val loading: Boolean = false,
    val selectedSong: Song? = null,
    val error: String? = null,
)

data class ScreenState(
    val selectedSong: Song? = null,
    val songCount: Int = 0,
    val theme: String = "system",
)

data class PlaybackState(
    val activeSong: Song? = null,
    val controlsEnabled: Boolean = false,
)

data class SyncState(
    val lastSongId: String? = null,
    val dirty: Boolean = false,
)

class Repository {
    val songs: StateFlow<List<Song>> = MutableStateFlow(emptyList())

    // Generic cold Flow: v0.11 runtime traces actual collector deliveries too.
    val remoteSongIds: Flow<String> = flow {
        emit("remote-1")
        emit("remote-2")
    }.map { id -> id.uppercase() }
}

class Settings {
    val theme: StateFlow<String> = MutableStateFlow("system")
}

class ExampleViewModel(
    repository: Repository,
    settings: Settings,
    scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(PlayerState())
    val state = _state.asStateFlow()

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState = _playbackState.asStateFlow()

    private val _syncState = MutableStateFlow(SyncState())
    val syncState = _syncState.asStateFlow()

    private val _refreshNeeded = MutableStateFlow(false)
    val refreshNeeded = _refreshNeeded.asStateFlow()

    private val _auditState = MutableStateFlow(0)
    val auditState = _auditState.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    // Cold-flow property whose values can be observed at runtime even though it has no StateFlow value.
    val decoratedRemoteSongIds: Flow<String> = repository.remoteSongIds
        .filter { it.isNotBlank() }
        .mapLatest { "song:$it" }
        .onEach { id -> if (id.endsWith("2")) _events.emit(id) }

    val selectedSongId = state
        .map { it.selectedSong?.id }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    val screenState = combine(
        state,
        repository.songs,
        settings.theme,
    ) { player, songs, theme ->
        ScreenState(
            selectedSong = player.selectedSong,
            songCount = songs.size,
            theme = theme,
        )
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        ScreenState(),
    )

    init {
        scope.launch {
            state.collectLatest { player ->
                if (player.loading) {
                    recordAudit(player)
                }
            }
        }
    }

    // Definite causal side effect from an arbitrary Flow operator lambda:
    // state -> onEach -> syncPlayback() -> _playbackState
    private val playbackSync = state
        .onEach { player -> syncPlayback(player) }
        .launchIn(scope)

    // Definite collectLatest -> helper -> helper -> StateFlow write chain.
    private val syncJob = state
        .onEach { player ->
            if (player.selectedSong != null) {
                writeSyncState(player)
            }
        }
        .launchIn(scope)

    val controlsEnabled = playbackState
        .map { it.controlsEnabled }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private fun recordAudit(player: PlayerState) {
        _auditState.update { count -> count + if (player.loading) 1 else 0 }
    }

    private fun syncPlayback(player: PlayerState) {
        updatePlayback(player)
    }

    private fun updatePlayback(player: PlayerState) {
        _playbackState.update { current ->
            current.copy(
                activeSong = player.selectedSong,
                controlsEnabled = player.selectedSong != null && !player.loading,
            )
        }
    }

    private fun writeSyncState(player: PlayerState) {
        _syncState.update {
            it.copy(
                lastSongId = player.selectedSong?.id,
                dirty = true,
            )
        }
    }

    // Plain read -> write in ordinary imperative code. Static analysis marks this POSSIBLE rather
    // than definite because the Flow itself does not schedule this function; the read and write are
    // simply causally co-located in the same behavior/helper path.
    fun refreshIfNeeded() {
        if (state.value.loading) {
            _refreshNeeded.value = true
        }
    }

    // Read-only usages are kept in the separate READS / OBSERVERS cluster.
    fun debugSnapshot(): String = "selected=${state.value.selectedSong?.id}"

    fun select(song: Song) {
        _state.update { current ->
            current.copy(
                selectedSong = song,
                error = null,
            )
        }
    }

    fun setLoading() {
        _state.update { it.copy(loading = true) }
    }

    fun replaceEverything() {
        _state.value = PlayerState()
    }

    suspend fun emitSharedEvent(value: String) {
        // v0.11 records the suspended emit request and downstream collector deliveries.
        _events.emit(value)
    }
}
