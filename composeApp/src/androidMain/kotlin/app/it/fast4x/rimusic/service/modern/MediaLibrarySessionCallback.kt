package app.it.fast4x.rimusic.service.modern

import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastMap
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import app.kreate.android.R
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import it.fast4x.innertube.Innertube
import it.fast4x.innertube.models.bodies.SearchBody
import it.fast4x.innertube.requests.searchPage
import it.fast4x.innertube.utils.from
import app.it.fast4x.rimusic.Database
import app.it.fast4x.rimusic.enums.MaxTopPlaylistItems
import app.it.fast4x.rimusic.enums.SongSortBy
import app.it.fast4x.rimusic.enums.SortOrder
import app.it.fast4x.rimusic.models.Song
import app.it.fast4x.rimusic.service.MyDownloadHelper
import app.it.fast4x.rimusic.repository.QuickPicksRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import app.it.fast4x.rimusic.MONTHLY_PREFIX
import app.it.fast4x.rimusic.PINNED_PREFIX
import app.it.fast4x.rimusic.PIPED_PREFIX
import app.it.fast4x.rimusic.service.modern.MediaSessionConstants.ID_ALBUMS_FAVORITES
import app.it.fast4x.rimusic.service.modern.MediaSessionConstants.ID_ALBUMS_LIBRARY
import app.it.fast4x.rimusic.service.modern.MediaSessionConstants.ID_ARTISTS_FAVORITES
import app.it.fast4x.rimusic.service.modern.MediaSessionConstants.ID_ARTISTS_LIBRARY
import app.it.fast4x.rimusic.service.modern.MediaSessionConstants.ID_PLAYLISTS_LOCAL
import app.it.fast4x.rimusic.service.modern.MediaSessionConstants.ID_PLAYLISTS_MONTHLY
import app.it.fast4x.rimusic.service.modern.MediaSessionConstants.ID_PLAYLISTS_PINNED
import app.it.fast4x.rimusic.service.modern.MediaSessionConstants.ID_PLAYLISTS_PIPED
import app.it.fast4x.rimusic.service.modern.MediaSessionConstants.ID_PLAYLISTS_YT
import app.it.fast4x.rimusic.service.modern.MediaSessionConstants.ID_QUICK_PICKS
import app.it.fast4x.rimusic.utils.MaxTopPlaylistItemsKey
import app.it.fast4x.rimusic.utils.asSong
import app.it.fast4x.rimusic.utils.showMonthlyPlaylistsKey
import app.it.fast4x.rimusic.utils.getEnum
import app.it.fast4x.rimusic.utils.persistentQueueKey
import app.it.fast4x.rimusic.utils.preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import app.kreate.android.me.knighthat.database.ext.FormatWithSong

@UnstableApi
class MediaLibrarySessionCallback(
    val context: Context,
    val database: Database,
    val downloadHelper: MyDownloadHelper
) : MediaLibrarySession.Callback {
    private val scope = CoroutineScope(Dispatchers.Main) + Job()
    private var observationJob: Job? = null
    lateinit var binder: PlayerServiceModern.Binder
    var toggleLike: () -> Unit = {}
    var toggleDownload: () -> Unit = {}
    var toggleRepeat: () -> Unit = {}
    var toggleShuffle: () -> Unit = {}
    var startRadio: () -> Unit = {}
    var callPause: () -> Unit = {}
    var actionSearch: () -> Unit = {}
    
    var searchedSongs: List<Song> = emptyList()
    var searchedArtists: List<Innertube.ArtistItem> = emptyList()
    var searchedVideos: List<Innertube.VideoItem> = emptyList()
    var searchedAlbums: List<Innertube.AlbumItem> = emptyList()

    fun observeRepository(session: MediaLibrarySession) {
        observationJob?.cancel()
        observationJob = scope.launch {
            combine(
                QuickPicksRepository.trendingList,
                QuickPicksRepository.relatedPage,
                database.artistTable.allFollowing(),
                database.albumTable.all(),
                database.songTable.all(),
                database.eventTable.findSongsMostPlayedBetween(0L),
                database.playlistTable.allAsPreview(),
                downloadHelper.downloads,
                database.formatTable.allWithSongs()
            ) { _ -> Unit }.collect {
                session.notifyChildrenChanged(PlayerServiceModern.ROOT, 0, null)
                session.notifyChildrenChanged(ID_QUICK_PICKS, 0, null)
                session.notifyChildrenChanged(PlayerServiceModern.SONG, 0, null)
                session.notifyChildrenChanged(PlayerServiceModern.ARTIST, 0, null)
                session.notifyChildrenChanged(PlayerServiceModern.ALBUM, 0, null)
                session.notifyChildrenChanged(PlayerServiceModern.PLAYLIST, 0, null)
            }
        }
    }


    fun release() {
        observationJob?.cancel()
        scope.cancel()
    }

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
        val connectionResult = super.onConnect(session, controller)
        return MediaSession.ConnectionResult.accept(
            connectionResult.availableSessionCommands.buildUpon()
                .add(MediaSessionConstants.CommandToggleDownload)
                .add(MediaSessionConstants.CommandToggleLike)
                .add(MediaSessionConstants.CommandToggleShuffle)
                .add(MediaSessionConstants.CommandToggleRepeatMode)
                .add(MediaSessionConstants.CommandStartRadio)
                .add(MediaSessionConstants.CommandSearch)
                .build(),
            connectionResult.availablePlayerCommands.buildUpon()
                .add(androidx.media3.common.Player.COMMAND_PLAY_PAUSE)
                .add(androidx.media3.common.Player.COMMAND_PREPARE)
                .add(androidx.media3.common.Player.COMMAND_STOP)
                .build()
        )
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        println("PlayerServiceModern MediaLibrarySessionCallback.onSearch: $query")
        session.notifySearchResultChanged(browser, query, 0, params)
        return Futures.immediateFuture(LibraryResult.ofVoid(params))
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        println("PlayerServiceModern MediaLibrarySessionCallback.onGetSearchResult: $query")
        val results = listOf(
            MediaItemMapper.browsableMediaItem(
                "${MediaSessionConstants.ID_SEARCH_SONGS}/$query",
                context.getString(R.string.songs),
                null,
                MediaItemMapper.drawableUri(context, R.drawable.musical_notes),
                MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
            ),
            MediaItemMapper.browsableMediaItem(
                "${MediaSessionConstants.ID_SEARCH_ARTISTS}/$query",
                context.getString(R.string.artists),
                null,
                MediaItemMapper.drawableUri(context, R.drawable.people),
                MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS
            ),
            MediaItemMapper.browsableMediaItem(
                "${MediaSessionConstants.ID_SEARCH_ALBUMS}/$query",
                context.getString(R.string.albums),
                null,
                MediaItemMapper.drawableUri(context, R.drawable.album),
                MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
            ),
            MediaItemMapper.browsableMediaItem(
                "${MediaSessionConstants.ID_SEARCH_VIDEOS}/$query",
                context.getString(R.string.videos),
                null,
                MediaItemMapper.drawableUri(context, R.drawable.video),
                MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
            )
        )
        return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(results), params))
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        when (customCommand.customAction) {
            MediaSessionConstants.ACTION_TOGGLE_LIKE -> toggleLike()
            MediaSessionConstants.ACTION_TOGGLE_DOWNLOAD -> toggleDownload()
            MediaSessionConstants.ACTION_TOGGLE_SHUFFLE -> toggleShuffle()
            MediaSessionConstants.ACTION_TOGGLE_REPEAT_MODE -> toggleRepeat()
            MediaSessionConstants.ACTION_START_RADIO -> startRadio()
            MediaSessionConstants.ACTION_SEARCH -> actionSearch()
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    @OptIn(UnstableApi::class)
    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
        LibraryResult.ofItem(
            MediaItem.Builder()
                .setMediaId(PlayerServiceModern.ROOT)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsPlayable(false)
                        .setIsBrowsable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build()
                )
                .build(),
            params
        )
    )

    @OptIn(UnstableApi::class)
    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
        val list = when (parentId) {
                PlayerServiceModern.ROOT -> {
                    val songsCount = database.songTable.sortAll(SongSortBy.DateAdded, SortOrder.Descending, excludeHidden = true).first().size
                    val artistsCount = database.artistTable.allInLibrary().first().size
                    val albumsCount = database.albumTable.allInLibrary().first().size
                    val playlistsCount = database.playlistTable.allAsPreview().first().size

                    listOf(
                        MediaItemMapper.browsableMediaItem(
                            MediaSessionConstants.ID_QUICK_PICKS,
                            context.getString(R.string.quick_picks),
                            null,
                            MediaItemMapper.drawableUri(context, R.drawable.sparkles),
                            MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                        ),
                        MediaItemMapper.browsableMediaItem(
                            PlayerServiceModern.SONG,
                            context.getString(R.string.songs),
                            null,
                            MediaItemMapper.drawableUri(context, R.drawable.musical_notes),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST
                        ),
                        MediaItemMapper.browsableMediaItem(
                            PlayerServiceModern.ARTIST,
                            context.getString(R.string.artists),
                            null,
                            MediaItemMapper.drawableUri(context, R.drawable.people),
                            MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS
                        ),
                        MediaItemMapper.browsableMediaItem(
                            PlayerServiceModern.ALBUM,
                            context.getString(R.string.albums),
                            null,
                            MediaItemMapper.drawableUri(context, R.drawable.album),
                            MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
                        ),
                        MediaItemMapper.browsableMediaItem(
                            PlayerServiceModern.PLAYLIST,
                            context.getString(R.string.library),
                            null,
                            MediaItemMapper.drawableUri(context, R.drawable.library),
                            MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
                        ),
                        MediaItemMapper.browsableMediaItem(
                            MediaSessionConstants.ID_SONGS_OTHERS,
                            context.getString(R.string.other),
                            null,
                            MediaItemMapper.drawableUri(context, R.drawable.library),
                            MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
                        )
                    )
                }

                MediaSessionConstants.ID_QUICK_PICKS -> {
                    val luckyItem = MediaItem.Builder()
                        .setMediaId(MediaSessionConstants.ID_LUCKY_SHUFFLE)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(context.getString(R.string.lucky_shuffle))
                                .setArtworkUri(MediaItemMapper.drawableUri(context, R.drawable.random))
                                .setIsPlayable(true)
                                .setIsBrowsable(false)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                .build()
                        )
                        .build()

                    val trending = QuickPicksRepository.trendingList.value.map { MediaItemMapper.mapSongToMediaItem(it, parentId) }
                    val related = QuickPicksRepository.relatedPage.value?.songs?.map { MediaItemMapper.mapSongToMediaItem(it.asSong, parentId) } ?: emptyList()

                    (listOf(luckyItem) + (trending + related).distinctBy { it.mediaId })
                }

                PlayerServiceModern.SONG -> {
                    val allCount = database.songTable.sortAll(SongSortBy.DateAdded, SortOrder.Descending, excludeHidden = true).first().size
                    val favoritesCount = database.songTable.allFavorites().first().size
                    
                    listOf(
                        MediaItemMapper.browsableMediaItem(
                            MediaSessionConstants.ID_SONGS_ALL,
                            context.getString(R.string.all),
                            allCount.toString(),
                            MediaItemMapper.drawableUri(context, R.drawable.musical_notes),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST
                        ),
                        MediaItemMapper.browsableMediaItem(
                            MediaSessionConstants.ID_SONGS_FAVORITES,
                            context.getString(R.string.favorites),
                            favoritesCount.toString(),
                            MediaItemMapper.drawableUri(context, R.drawable.heart),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST
                        )
                    )
                }

                MediaSessionConstants.ID_SONGS_OTHERS -> {
                    val downloadedCount = getCountDownloadedSongs().first()
                    val onDeviceCount = database.songTable.allOnDevice().first().size
                    val cachedCount = getCountCachedSongs().first()
                    val topCount = database.eventTable.findSongsMostPlayedBetween(
                        from = 0,
                        limit = context.preferences.getEnum(MaxTopPlaylistItemsKey, MaxTopPlaylistItems.`10`).toInt()
                    ).first().size

                    listOf(
                        MediaItemMapper.browsableMediaItem(
                            MediaSessionConstants.ID_SONGS_DOWNLOADED,
                            context.getString(R.string.downloaded),
                            downloadedCount.toString(),
                            MediaItemMapper.drawableUri(context, R.drawable.downloaded),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST
                        ),
                        MediaItemMapper.browsableMediaItem(
                            MediaSessionConstants.ID_SONGS_CACHED,
                            context.getString(R.string.cached),
                            cachedCount.toString(),
                            MediaItemMapper.drawableUri(context, R.drawable.download),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST
                        ),
                        MediaItemMapper.browsableMediaItem(
                            MediaSessionConstants.ID_SONGS_ONDEVICE,
                            context.getString(R.string.on_device),
                            onDeviceCount.toString(),
                            MediaItemMapper.drawableUri(context, R.drawable.devices),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST
                        ),
                        MediaItemMapper.browsableMediaItem(
                            MediaSessionConstants.ID_SONGS_TOP,
                            context.getString(R.string.playlist_top),
                            topCount.toString(),
                            MediaItemMapper.drawableUri(context, R.drawable.trending),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST
                        )
                    )
                }

                MediaSessionConstants.ID_SONGS_TOP -> {
                    val shuffleItem = MediaSessionConstants.shuffleItem(context, MediaSessionConstants.ID_SONGS_TOP_SHUFFLE)
                    val songs = database.eventTable
                        .findSongsMostPlayedBetween(
                            from = 0,
                            limit = context.preferences
                                .getEnum(MaxTopPlaylistItemsKey, MaxTopPlaylistItems.`10`)
                                .toInt()
                        ).first().map { MediaItemMapper.mapSongToMediaItem(it, parentId) }
                    listOf(shuffleItem) + songs
                }

                MediaSessionConstants.ID_SONGS_ALL -> {
                    val shuffleItem = MediaSessionConstants.shuffleItem(context, MediaSessionConstants.ID_SONGS_ALL_SHUFFLE)
                    val songs = database.songTable.sortAll(SongSortBy.DateAdded, SortOrder.Descending, excludeHidden = true).first().map { MediaItemMapper.mapSongToMediaItem(it, parentId) }
                    listOf(shuffleItem) + songs
                }

                MediaSessionConstants.ID_SONGS_FAVORITES -> {
                    val shuffleItem = MediaSessionConstants.shuffleItem(context, MediaSessionConstants.ID_SONGS_FAVORITES_SHUFFLE)
                    val songs = database.songTable.allFavorites().first().reversed().map { MediaItemMapper.mapSongToMediaItem(it, parentId) }
                    listOf(shuffleItem) + songs
                }

                MediaSessionConstants.ID_SONGS_DOWNLOADED -> {
                    val shuffleItem = MediaSessionConstants.shuffleItem(context, MediaSessionConstants.ID_SONGS_DOWNLOADED_SHUFFLE)
                    val downloads = downloadHelper.downloads.value
                    val songs = database.songTable.all(excludeHidden = false).first()
                        .fastFilter { downloads[it.id]?.state == Download.STATE_COMPLETED }
                        .sortedByDescending { downloads[it.id]?.updateTimeMs ?: 0L }
                        .map { MediaItemMapper.mapSongToMediaItem(it, parentId) }
                    listOf(shuffleItem) + songs
                }

                MediaSessionConstants.ID_SONGS_ONDEVICE -> {
                    val shuffleItem = MediaSessionConstants.shuffleItem(context, MediaSessionConstants.ID_SONGS_ONDEVICE_SHUFFLE)
                    val songs = database.songTable.allOnDevice().first().map { MediaItemMapper.mapSongToMediaItem(it, parentId) }
                    listOf(shuffleItem) + songs
                }

                MediaSessionConstants.ID_SONGS_CACHED -> {
                    val shuffleItem = MediaSessionConstants.shuffleItem(context, MediaSessionConstants.ID_SONGS_CACHED_SHUFFLE)
                    val songs = database.formatTable.allWithSongs().first()
                        .fastFilter {
                            val contentLength = it.format.contentLength
                            contentLength != null && binder.cache.isCached(it.song.id, 0L, contentLength)
                        }
                        .reversed()
                        .fastMap(FormatWithSong::song)
                        .map { MediaItemMapper.mapSongToMediaItem(it, parentId) }
                    listOf(shuffleItem) + songs
                }

                PlayerServiceModern.ARTIST -> {
                    val libraryCount = database.artistTable.allInLibrary().first().size
                    val favoritesCount = database.artistTable.allFollowing().first().size
                    listOf(
                        MediaItemMapper.browsableMediaItem(
                            ID_ARTISTS_LIBRARY,
                            context.getString(R.string.library),
                            libraryCount.toString(),
                            MediaItemMapper.drawableUri(context, R.drawable.artist),
                            MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS
                        ),
                        MediaItemMapper.browsableMediaItem(
                            ID_ARTISTS_FAVORITES,
                            context.getString(R.string.favorites),
                            favoritesCount.toString(),
                            MediaItemMapper.drawableUri(context, R.drawable.heart),
                            MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS
                        )
                    )
                }

                MediaSessionConstants.ID_ARTISTS_LIBRARY -> {
                    val shuffleItem = MediaSessionConstants.shuffleItem(context, MediaSessionConstants.ID_ARTISTS_LIBRARY_SHUFFLE)
                    val artists = database.artistTable.allInLibrary().first().map { artist ->
                        MediaItemMapper.browsableMediaItem(
                            "${PlayerServiceModern.ARTIST}/${artist.id}",
                            artist.name ?: "",
                            null,
                            MediaItemMapper.drawableUri(context, R.drawable.artist),
                            MediaMetadata.MEDIA_TYPE_ARTIST
                        )
                    }
                    listOf(shuffleItem) + artists
                }

                MediaSessionConstants.ID_ARTISTS_FAVORITES -> {
                    val shuffleItem = MediaSessionConstants.shuffleItem(context, MediaSessionConstants.ID_ARTISTS_FAVORITES_SHUFFLE)
                    val artists = database.artistTable.allFollowing().first().map { artist ->
                        MediaItemMapper.browsableMediaItem(
                            "${PlayerServiceModern.ARTIST}/${artist.id}",
                            artist.name ?: "",
                            null,
                            MediaItemMapper.drawableUri(context, R.drawable.artist),
                            MediaMetadata.MEDIA_TYPE_ARTIST
                        )
                    }
                    listOf(shuffleItem) + artists
                }

                PlayerServiceModern.ALBUM -> {
                    val libraryCount = database.albumTable.allInLibrary().first().size
                    val bookmarkedCount = database.albumTable.allBookmarked().first().size
                    listOf(
                        MediaItemMapper.browsableMediaItem(
                            ID_ALBUMS_LIBRARY,
                            context.getString(R.string.library),
                            libraryCount.toString(),
                            MediaItemMapper.drawableUri(context, R.drawable.album),
                            MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
                        ),
                        MediaItemMapper.browsableMediaItem(
                            ID_ALBUMS_FAVORITES,
                            context.getString(R.string.favorites),
                            bookmarkedCount.toString(),
                            MediaItemMapper.drawableUri(context, R.drawable.heart),
                            MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
                        )
                    )
                }

                MediaSessionConstants.ID_ALBUMS_LIBRARY -> {
                    val shuffleItem = MediaSessionConstants.shuffleItem(context, MediaSessionConstants.ID_ALBUMS_LIBRARY_SHUFFLE)
                    val albums = database.albumTable.allInLibrary().first().map { album ->
                        MediaItemMapper.mapAlbumToMediaItem(
                            PlayerServiceModern.ALBUM,
                            album.id,
                            album.title ?: "",
                            album.authorsText,
                            album.thumbnailUrl
                        )
                    }
                    listOf(shuffleItem) + albums
                }

                MediaSessionConstants.ID_ALBUMS_FAVORITES -> {
                    val shuffleItem = MediaSessionConstants.shuffleItem(context, MediaSessionConstants.ID_ALBUMS_FAVORITES_SHUFFLE)
                    val albums = database.albumTable.allBookmarked().first().map { album ->
                        MediaItemMapper.mapAlbumToMediaItem(
                            PlayerServiceModern.ALBUM,
                            album.id,
                            album.title ?: "",
                            album.authorsText,
                            album.thumbnailUrl
                        )
                    }
                    listOf(shuffleItem) + albums
                }

                PlayerServiceModern.PLAYLIST -> {
                    val playlists = database.playlistTable.allAsPreview().first()
                    val showMonthlyPlaylists = context.preferences.getBoolean(showMonthlyPlaylistsKey, true)
                    
                    val localCount = playlists.filter { 
                        !it.playlist.isYoutubePlaylist && 
                        !it.playlist.name.startsWith(PIPED_PREFIX, true) && 
                        !it.playlist.name.startsWith(PINNED_PREFIX, true) && 
                        !it.playlist.name.startsWith(MONTHLY_PREFIX, true)
                    }.size
                    val ytCount = playlists.filter { it.playlist.isYoutubePlaylist }.size
                    val pipedCount = playlists.filter { it.playlist.name.startsWith(PIPED_PREFIX, true) }.size
                    val pinnedCount = playlists.filter { it.playlist.name.startsWith(PINNED_PREFIX, true) }.size
                    val monthlyCount = playlists.filter { it.playlist.name.startsWith(MONTHLY_PREFIX, true) }.size

                    val playlistItems = mutableListOf<MediaItem>()
                    
                    playlistItems.add(
                        MediaItemMapper.browsableMediaItem(
                            ID_PLAYLISTS_PINNED,
                            context.getString(R.string.pinned_playlists),
                            pinnedCount.toString(),
                            MediaItemMapper.drawableUri(context, R.drawable.pin_filled),
                            MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
                        )
                    )
                    playlistItems.add(
                        MediaItemMapper.browsableMediaItem(
                            ID_PLAYLISTS_LOCAL,
                            context.getString(R.string.playlists),
                            localCount.toString(),
                            MediaItemMapper.drawableUri(context, R.drawable.library),
                            MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
                        )
                    )
                    
                    if (showMonthlyPlaylists) {
                        playlistItems.add(
                            MediaItemMapper.browsableMediaItem(
                                ID_PLAYLISTS_MONTHLY,
                                context.getString(R.string.monthly_playlists),
                                monthlyCount.toString(),
                                MediaItemMapper.drawableUri(context, R.drawable.calendar),
                                MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
                            )
                        )
                    }
                    
                    playlistItems.add(
                        MediaItemMapper.browsableMediaItem(
                            ID_PLAYLISTS_YT,
                            context.getString(R.string.yt_playlists),
                            ytCount.toString(),
                            MediaItemMapper.drawableUri(context, R.drawable.ytmusic),
                            MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
                        )
                    )
                    playlistItems.add(
                        MediaItemMapper.browsableMediaItem(
                            ID_PLAYLISTS_PIPED,
                            context.getString(R.string.piped_playlists),
                            pipedCount.toString(),
                            MediaItemMapper.drawableUri(context, R.drawable.piped_logo),
                            MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
                        )
                    )
                    
                    playlistItems
                }

                ID_PLAYLISTS_LOCAL -> {
                    database.playlistTable.allAsPreview().first().filter { 
                        !it.playlist.isYoutubePlaylist && 
                        !it.playlist.name.startsWith(PIPED_PREFIX, true) && 
                        !it.playlist.name.startsWith(PINNED_PREFIX, true) && 
                        !it.playlist.name.startsWith(MONTHLY_PREFIX, true)
                    }.map { preview ->
                        MediaItemMapper.browsableMediaItem(
                            "${PlayerServiceModern.PLAYLIST}/${preview.playlist.id}",
                            preview.playlist.name,
                            preview.songCount.toString(),
                            MediaItemMapper.drawableUri(context, R.drawable.library),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST
                        )
                    }
                }

                ID_PLAYLISTS_YT -> {
                    database.playlistTable.allAsPreview().first().filter { it.playlist.isYoutubePlaylist }.map { preview ->
                        MediaItemMapper.browsableMediaItem(
                            "${PlayerServiceModern.PLAYLIST}/${preview.playlist.id}",
                            preview.playlist.name,
                            preview.songCount.toString(),
                            MediaItemMapper.drawableUri(context, R.drawable.library),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST
                        )
                    }
                }

                ID_PLAYLISTS_PIPED -> {
                    database.playlistTable.allAsPreview().first().filter { it.playlist.name.startsWith(PIPED_PREFIX, true) }.map { preview ->
                        MediaItemMapper.browsableMediaItem(
                            "${PlayerServiceModern.PLAYLIST}/${preview.playlist.id}",
                            preview.playlist.name,
                            preview.songCount.toString(),
                            MediaItemMapper.drawableUri(context, R.drawable.library),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST
                        )
                    }
                }

                ID_PLAYLISTS_PINNED -> {
                    database.playlistTable.allAsPreview().first().filter { it.playlist.name.startsWith(PINNED_PREFIX, true) }.map { preview ->
                        MediaItemMapper.browsableMediaItem(
                            "${PlayerServiceModern.PLAYLIST}/${preview.playlist.id}",
                            preview.playlist.name,
                            preview.songCount.toString(),
                            MediaItemMapper.drawableUri(context, R.drawable.library),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST
                        )
                    }
                }

                ID_PLAYLISTS_MONTHLY -> {
                    database.playlistTable.allAsPreview().first().filter { it.playlist.name.startsWith(MONTHLY_PREFIX, true) }.map { preview ->
                        MediaItemMapper.browsableMediaItem(
                            "${PlayerServiceModern.PLAYLIST}/${preview.playlist.id}",
                            preview.playlist.name,
                            preview.songCount.toString(),
                            MediaItemMapper.drawableUri(context, R.drawable.library),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST
                        )
                    }
                }

                else -> {
                    val parts = parentId.split("/")
                    when (parts[0]) {
                        MediaSessionConstants.ID_SEARCH_SONGS -> {
                            val query = parts[1]
                            searchedSongs = Innertube.searchPage(
                                body = SearchBody(
                                    query = query,
                                    params = Innertube.SearchFilter.Song.value
                                ),
                                fromMusicShelfRendererContent = Innertube.SongItem.Companion::from
                            )?.getOrNull()?.items?.map { it.asSong } ?: emptyList()
                            searchedSongs.map { MediaItemMapper.mapSongToMediaItem(it, parentId) }
                        }
                        MediaSessionConstants.ID_SEARCH_ARTISTS -> {
                            val query = parts[1]
                            searchedArtists = Innertube.searchPage(
                                body = SearchBody(
                                    query = query,
                                    params = Innertube.SearchFilter.Artist.value
                                ),
                                fromMusicShelfRendererContent = Innertube.ArtistItem.Companion::from
                            )?.getOrNull()?.items?.mapNotNull { it as? Innertube.ArtistItem } ?: emptyList()
                            searchedArtists.map { artist ->
                                MediaItemMapper.mapArtistToMediaItem(
                                    PlayerServiceModern.ARTIST,
                                    artist.key ?: "",
                                    artist.info?.name ?: "",
                                    artist.thumbnail?.url,
                                    artist.subscribersCountText
                                )
                            }
                        }
                        MediaSessionConstants.ID_SEARCH_ALBUMS -> {
                            val query = parts[1]
                            searchedAlbums = Innertube.searchPage(
                                body = SearchBody(
                                    query = query,
                                    params = Innertube.SearchFilter.Album.value
                                ),
                                fromMusicShelfRendererContent = Innertube.AlbumItem.Companion::from
                            )?.getOrNull()?.items?.mapNotNull { it as? Innertube.AlbumItem } ?: emptyList()
                            searchedAlbums.map { album ->
                                MediaItemMapper.mapAlbumToMediaItem(
                                    PlayerServiceModern.ALBUM,
                                    album.key ?: "",
                                    album.info?.name ?: "",
                                    album.authors?.joinToString(", ") { it.name ?: "" },
                                    album.thumbnail?.url
                                )
                            }
                        }
                        MediaSessionConstants.ID_SEARCH_VIDEOS -> {
                            val query = parts[1]
                            searchedVideos = Innertube.searchPage(
                                body = SearchBody(
                                    query = query,
                                    params = Innertube.SearchFilter.Video.value
                                ),
                                fromMusicShelfRendererContent = Innertube.VideoItem.Companion::from
                            )?.getOrNull()?.items?.mapNotNull { it as? Innertube.VideoItem } ?: emptyList()
                            searchedVideos.map { video ->
                                MediaItemMapper.mapSongToMediaItem(video.asSong, parentId)
                            }
                        }
                        PlayerServiceModern.ARTIST -> database.songArtistMapTable.allSongsBy(parts[1]).first().map { MediaItemMapper.mapSongToMediaItem(it, parentId) }
                        PlayerServiceModern.ALBUM -> database.songAlbumMapTable.allSongsOf(parts[1]).first().map { MediaItemMapper.mapSongToMediaItem(it, parentId) }
                        PlayerServiceModern.PLAYLIST -> {
                            val playlistId = parts[1]
                            when (playlistId) {
                                MediaSessionConstants.ID_FAVORITES -> database.songTable.allFavorites().map { it.reversed() }
                                MediaSessionConstants.ID_CACHED -> database.formatTable
                                    .allWithSongs()
                                    .map { list ->
                                        list.fastFilter {
                                            val contentLength = it.format.contentLength
                                            contentLength != null && binder.cache.isCached(it.song.id, 0L, contentLength)
                                        }
                                            .reversed()
                                            .fastMap(FormatWithSong::song)
                                    }
                                MediaSessionConstants.ID_TOP -> database.eventTable
                                    .findSongsMostPlayedBetween(
                                        from = 0,
                                        limit = context.preferences
                                            .getEnum(MaxTopPlaylistItemsKey, MaxTopPlaylistItems.`10`)
                                            .toInt()
                                    )
                                MediaSessionConstants.ID_ONDEVICE -> database.songTable.allOnDevice()
                                MediaSessionConstants.ID_DOWNLOADED -> {
                                    val downloads = downloadHelper.downloads.value
                                    database.songTable
                                        .all(excludeHidden = false)
                                        .map { songs ->
                                            songs.fastFilter {
                                                downloads[it.id]?.state == Download.STATE_COMPLETED
                                            }
                                                .sortedByDescending { downloads[it.id]?.updateTimeMs ?: 0L }
                                        }
                                }
                                else -> database.songPlaylistMapTable.allSongsOf(playlistId.toLong())
                            }.first().map { MediaItemMapper.mapSongToMediaItem(it, parentId) }
                        }
                        else -> emptyList()
                    }
                }
        }
        LibraryResult.ofItemList(ImmutableList.copyOf(list), params)
    }

    @OptIn(UnstableApi::class)
    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = scope.future(Dispatchers.IO) {
        println("PlayerServiceModern MediaLibrarySessionCallback.onGetItem: $mediaId")

        database.songTable
                .findById( mediaId )
                .first()
                ?.let { MediaItemMapper.mapSongToMediaItem(it) }
                ?.let {
                    LibraryResult.ofItem( it, null )
                }
                ?: LibraryResult.ofError( SessionError.ERROR_UNKNOWN )
    }

    // Play from Android Auto
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
        if (mediaItems.firstOrNull()?.mediaId == MediaSessionConstants.ID_LUCKY_SHUFFLE) {
            val allSongs = (QuickPicksRepository.trendingList.value + (QuickPicksRepository.relatedPage.value?.songs?.map { it.asSong } ?: emptyList()))
                .distinctBy { it.id }
                .shuffled()

            if (allSongs.isNotEmpty()) {
                return@future MediaSession.MediaItemsWithStartPosition(allSongs.map { MediaItemMapper.mapSongToMediaItem(it) }, 0, 0)
            }
        }

        if (mediaItems.firstOrNull()?.mediaId == MediaSessionConstants.ID_SONG_SHUFFLE || mediaItems.firstOrNull()?.mediaId == MediaSessionConstants.ID_SONGS_ALL_SHUFFLE) {
            val allSongs = database.songTable.sortAll(SongSortBy.DateAdded, SortOrder.Descending, excludeHidden = true).first().shuffled()
            if (allSongs.isNotEmpty()) {
                return@future MediaSession.MediaItemsWithStartPosition(allSongs.map { MediaItemMapper.mapSongToMediaItem(it) }, 0, 0)
            }
        }

        if (mediaItems.firstOrNull()?.mediaId == MediaSessionConstants.ID_SONGS_FAVORITES_SHUFFLE) {
            val allSongs = database.songTable.allFavorites().first().shuffled()
            if (allSongs.isNotEmpty()) {
                return@future MediaSession.MediaItemsWithStartPosition(allSongs.map { MediaItemMapper.mapSongToMediaItem(it) }, 0, 0)
            }
        }

        if (mediaItems.firstOrNull()?.mediaId == MediaSessionConstants.ID_SONGS_DOWNLOADED_SHUFFLE) {
            val downloads = downloadHelper.downloads.value
            val allSongs = database.songTable.all(excludeHidden = false).first()
                .fastFilter { downloads[it.id]?.state == Download.STATE_COMPLETED }
                .shuffled()
            if (allSongs.isNotEmpty()) {
                return@future MediaSession.MediaItemsWithStartPosition(allSongs.map { MediaItemMapper.mapSongToMediaItem(it) }, 0, 0)
            }
        }

        if (mediaItems.firstOrNull()?.mediaId == MediaSessionConstants.ID_SONGS_ONDEVICE_SHUFFLE) {
            val allSongs = database.songTable.allOnDevice().first().shuffled()
            if (allSongs.isNotEmpty()) {
                return@future MediaSession.MediaItemsWithStartPosition(allSongs.map { MediaItemMapper.mapSongToMediaItem(it) }, 0, 0)
            }
        }

        if (mediaItems.firstOrNull()?.mediaId == MediaSessionConstants.ID_SONGS_CACHED_SHUFFLE) {
            val allSongs = database.formatTable.allWithSongs().first()
                .fastFilter {
                    val contentLength = it.format.contentLength
                    contentLength != null && binder.cache.isCached(it.song.id, 0L, contentLength)
                }
                .fastMap(FormatWithSong::song)
                .shuffled()
            if (allSongs.isNotEmpty()) {
                return@future MediaSession.MediaItemsWithStartPosition(allSongs.map { MediaItemMapper.mapSongToMediaItem(it) }, 0, 0)
            }
        }

        if (mediaItems.firstOrNull()?.mediaId == MediaSessionConstants.ID_SONGS_TOP_SHUFFLE) {
            val allSongs = database.eventTable
                .findSongsMostPlayedBetween(
                    from = 0,
                    limit = context.preferences
                        .getEnum(MaxTopPlaylistItemsKey, MaxTopPlaylistItems.`10`)
                        .toInt()
                ).first().shuffled()
            if (allSongs.isNotEmpty()) {
                return@future MediaSession.MediaItemsWithStartPosition(allSongs.map { MediaItemMapper.mapSongToMediaItem(it) }, 0, 0)
            }
        }

        if (mediaItems.firstOrNull()?.mediaId == MediaSessionConstants.ID_ALBUM_SHUFFLE || mediaItems.firstOrNull()?.mediaId == MediaSessionConstants.ID_ALBUMS_LIBRARY_SHUFFLE) {
            val allSongs = database.albumTable.allInLibrary().first()
                .flatMap { database.songAlbumMapTable.allSongsOf(it.id).first() }
                .distinctBy { it.id }
                .shuffled()
            if (allSongs.isNotEmpty()) {
                return@future MediaSession.MediaItemsWithStartPosition(allSongs.map { MediaItemMapper.mapSongToMediaItem(it) }, 0, 0)
            }
        }

        if (mediaItems.firstOrNull()?.mediaId == MediaSessionConstants.ID_ALBUMS_FAVORITES_SHUFFLE) {
            val allSongs = database.albumTable.allBookmarked().first()
                .flatMap { database.songAlbumMapTable.allSongsOf(it.id).first() }
                .distinctBy { it.id }
                .shuffled()
            if (allSongs.isNotEmpty()) {
                return@future MediaSession.MediaItemsWithStartPosition(allSongs.map { MediaItemMapper.mapSongToMediaItem(it) }, 0, 0)
            }
        }

        if (mediaItems.firstOrNull()?.mediaId == MediaSessionConstants.ID_ARTIST_SHUFFLE || mediaItems.firstOrNull()?.mediaId == MediaSessionConstants.ID_ARTISTS_LIBRARY_SHUFFLE) {
            val allSongs = database.artistTable.allInLibrary().first()
                .flatMap { database.songArtistMapTable.allSongsBy(it.id).first() }
                .distinctBy { it.id }
                .shuffled()
            if (allSongs.isNotEmpty()) {
                return@future MediaSession.MediaItemsWithStartPosition(allSongs.map { MediaItemMapper.mapSongToMediaItem(it) }, 0, 0)
            }
        }

        if (mediaItems.firstOrNull()?.mediaId == MediaSessionConstants.ID_ARTISTS_FAVORITES_SHUFFLE) {
            val allSongs = database.artistTable.allFollowing().first()
                .flatMap { database.songArtistMapTable.allSongsBy(it.id).first() }
                .distinctBy { it.id }
                .shuffled()
            if (allSongs.isNotEmpty()) {
                return@future MediaSession.MediaItemsWithStartPosition(allSongs.map { MediaItemMapper.mapSongToMediaItem(it) }, 0, 0)
            }
        }

        var queryList = emptyList<Song>()
        var startIdx = startIndex

        runCatching {
            var songId = ""

            val paths = mediaItems.first().mediaId.split( "/" )
            when( paths.first() ) {
                MediaSessionConstants.ID_QUICK_PICKS -> {
                    songId = paths[1]
                    queryList = (QuickPicksRepository.trendingList.value + (QuickPicksRepository.relatedPage.value?.songs?.map { it.asSong } ?: emptyList())).distinctBy { it.id }
                }
                MediaSessionConstants.ID_SEARCH_SONGS -> {
                    songId = paths[2]
                    queryList = searchedSongs
                }
                MediaSessionConstants.ID_SEARCH_VIDEOS -> {
                    songId = paths[2]
                    queryList = searchedVideos.map { it.asSong }
                }
                PlayerServiceModern.SEARCHED -> {
                    songId = paths[1]
                    queryList = searchedSongs
                }
                PlayerServiceModern.SONG -> {
                    songId = paths[1]
                    queryList = database.songTable.all().first()
                }
                PlayerServiceModern.ARTIST -> {
                    songId = paths[2]
                    queryList = database.songArtistMapTable.allSongsBy( paths[1] ).first()
                }
                PlayerServiceModern.ALBUM -> {
                    songId = paths[2]
                    queryList = database.songAlbumMapTable.allSongsOf( paths[1] ).first()
                }
                PlayerServiceModern.PLAYLIST -> {
                    val playlistId = paths[1]
                    songId = paths[2]
                    queryList = when ( playlistId ) {
                        MediaSessionConstants.ID_FAVORITES -> database.songTable.allFavorites().map { it.reversed() }
                        MediaSessionConstants.ID_CACHED -> database.formatTable
                                             .allWithSongs()
                                             .map { list ->
                                                 list.fastFilter {
                                                     val contentLength = it.format.contentLength
                                                     contentLength != null && binder.cache.isCached( it.song.id, 0L, contentLength )
                                                 }
                                                     .reversed()
                                                     .fastMap( FormatWithSong::song )
                                             }
                        MediaSessionConstants.ID_TOP -> database.eventTable
                                           .findSongsMostPlayedBetween(
                                               from = 0,
                                               limit = context.preferences
                                                   .getEnum(MaxTopPlaylistItemsKey, MaxTopPlaylistItems.`10`)
                                                   .toInt()
                                           )
                        MediaSessionConstants.ID_ONDEVICE -> database.songTable.allOnDevice()
                        MediaSessionConstants.ID_DOWNLOADED -> {
                            val downloads = downloadHelper.downloads.value
                            database.songTable
                                    .all( excludeHidden = false )
                                    .map { songs ->
                                        songs.fastFilter {
                                                 downloads[it.id]?.state == Download.STATE_COMPLETED
                                             }
                                             .sortedByDescending { downloads[it.id]?.updateTimeMs ?: 0L }
                                    }
                        }

                        else -> database.songPlaylistMapTable.allSongsOf( playlistId.toLong() )
                    }.first()
                }
            }

            startIdx = queryList.indexOfFirst { it.id == songId }.coerceAtLeast( 0 )
        }

        return@future MediaSession.MediaItemsWithStartPosition( queryList.map{ MediaItemMapper.mapSongToMediaItem(it) }, startIdx, startPositionMs )
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val settablePlaylist = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        val defaultResult =
            MediaSession.MediaItemsWithStartPosition(
                emptyList(),
                0,
                0
            )
        if(!context.preferences.getBoolean(persistentQueueKey, false))
            return Futures.immediateFuture(defaultResult)

        scope.future {
            val startIndex: Int
            val startPositionMs: Long
            val mediaItems: List<MediaItem>

            database.queueTable.all().first().run {
                indexOfFirst { it.position != null }.coerceAtLeast( 0 )
                                                    .let {
                                                        startIndex = it
                                                        startPositionMs = it.toLong()
                                                    }
                mediaItems = map { MediaItemMapper.mapSongToMediaItem(it.mediaItem.asSong, true) }
            }

            val resumptionPlaylist = MediaSession.MediaItemsWithStartPosition(
                mediaItems,
                startIndex,
                startPositionMs
            )
            settablePlaylist.set(resumptionPlaylist)
        }
        return settablePlaylist
    }

    private fun getCountCachedSongs() =
        database.formatTable
                .allWithSongs()
                .map { list ->
                    list.filter {
                            val contentLength = it.format.contentLength
                            contentLength != null && binder.cache.isCached( it.song.id, 0L, contentLength )
                        }
                        .size
                }

    private fun getCountDownloadedSongs() = downloadHelper.downloads.map {
        it.filter {
            it.value.state == Download.STATE_COMPLETED
        }.size
    }
}
