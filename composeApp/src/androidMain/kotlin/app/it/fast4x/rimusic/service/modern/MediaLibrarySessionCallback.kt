package app.it.fast4x.rimusic.service.modern

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.annotation.OptIn
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastMap
import androidx.core.net.toUri
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
import app.it.fast4x.rimusic.EXPLICIT_PREFIX
import app.it.fast4x.rimusic.cleanPrefix
import app.it.fast4x.rimusic.enums.MaxTopPlaylistItems
import app.it.fast4x.rimusic.enums.StatisticsType
import app.it.fast4x.rimusic.models.Song
import app.it.fast4x.rimusic.service.MyDownloadHelper
import app.it.fast4x.rimusic.service.modern.MediaSessionConstants.ID_CACHED
import app.it.fast4x.rimusic.service.modern.MediaSessionConstants.ID_DOWNLOADED
import app.it.fast4x.rimusic.service.modern.MediaSessionConstants.ID_FAVORITES
import app.it.fast4x.rimusic.service.modern.MediaSessionConstants.ID_ONDEVICE
import app.it.fast4x.rimusic.service.modern.MediaSessionConstants.ID_QUICK_PICKS
import app.it.fast4x.rimusic.service.modern.MediaSessionConstants.ID_LUCKY_SHUFFLE
import app.it.fast4x.rimusic.repository.QuickPicksRepository
import app.it.fast4x.rimusic.service.modern.MediaSessionConstants.ID_TOP
import app.it.fast4x.rimusic.utils.MaxTopPlaylistItemsKey
import app.n_zik.android.core.coil.thumbnail
import it.fast4x.innertube.models.bodies.NextBody
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import app.it.fast4x.rimusic.utils.EXPLICIT_BUNDLE_TAG
import app.it.fast4x.rimusic.utils.asMediaItem
import androidx.media3.session.MediaConstants.EXTRAS_KEY_IS_EXPLICIT
import app.it.fast4x.rimusic.utils.asSong
import app.it.fast4x.rimusic.utils.getEnum
import app.it.fast4x.rimusic.utils.persistentQueueKey
import app.it.fast4x.rimusic.utils.preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
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
            browsableMediaItem(
                "${MediaSessionConstants.ID_SEARCH_SONGS}/$query",
                context.getString(R.string.songs),
                null,
                drawableUri(R.drawable.musical_notes),
                MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
            ),
            browsableMediaItem(
                "${MediaSessionConstants.ID_SEARCH_ARTISTS}/$query",
                context.getString(R.string.artists),
                null,
                drawableUri(R.drawable.people),
                MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS
            ),
            browsableMediaItem(
                "${MediaSessionConstants.ID_SEARCH_ALBUMS}/$query",
                context.getString(R.string.albums),
                null,
                drawableUri(R.drawable.album),
                MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
            ),
            browsableMediaItem(
                "${MediaSessionConstants.ID_SEARCH_VIDEOS}/$query",
                context.getString(R.string.videos),
                null,
                drawableUri(R.drawable.video),
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
        LibraryResult.ofItemList(
            when (parentId) {
                PlayerServiceModern.ROOT -> listOf(
                    browsableMediaItem(
                        ID_QUICK_PICKS,
                        context.getString(R.string.quick_picks),
                        null,
                        drawableUri(R.drawable.sparkles),
                        MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                    ),
                    browsableMediaItem(
                        PlayerServiceModern.SONG,
                        context.getString(R.string.songs),
                        null,
                        drawableUri(R.drawable.musical_notes),
                        MediaMetadata.MEDIA_TYPE_PLAYLIST
                    ),
                    browsableMediaItem(
                        PlayerServiceModern.ARTIST,
                        context.getString(R.string.artists),
                        null,
                        drawableUri(R.drawable.people),
                        MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS
                    ),
                    browsableMediaItem(
                        PlayerServiceModern.ALBUM,
                        context.getString(R.string.albums),
                        null,
                        drawableUri(R.drawable.album),
                        MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
                    ),
                    browsableMediaItem(
                        PlayerServiceModern.PLAYLIST,
                        context.getString(R.string.playlists),
                        null,
                        drawableUri(R.drawable.library),
                        MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
                    )
                )

                ID_QUICK_PICKS -> {
                    val luckyItem = MediaItem.Builder()
                        .setMediaId(ID_LUCKY_SHUFFLE)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(context.getString(R.string.lucky_shuffle))
                                .setArtworkUri(drawableUri(R.drawable.random))
                                .setIsPlayable(true)
                                .setIsBrowsable(false)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                .build()
                        )
                        .build()

                    val trending = QuickPicksRepository.trendingList.value.map { it.toMediaItem(parentId) }
                    val related = QuickPicksRepository.relatedPage.value?.songs?.map { it.asSong.toMediaItem(parentId) } ?: emptyList()

                    (listOf(luckyItem) + (trending + related).distinctBy { it.mediaId })
                }

                PlayerServiceModern.SONG -> database.songTable.all().first().map { it.toMediaItem(parentId) }

                PlayerServiceModern.ARTIST -> database.artistTable.allFollowing().first().map { artist ->
                    browsableMediaItem(
                        "${PlayerServiceModern.ARTIST}/${artist.id}",
                        artist.name ?: "",
                        "",
                        artist.thumbnailUrl?.toUri(),
                        MediaMetadata.MEDIA_TYPE_ARTIST
                    )
                }

                PlayerServiceModern.ALBUM -> database.albumTable.all().first().map { album ->
                    browsableMediaItem(
                        "${PlayerServiceModern.ALBUM}/${album.id}",
                        album.title ?: "",
                        album.authorsText,
                        album.thumbnailUrl?.toUri(),
                        MediaMetadata.MEDIA_TYPE_ALBUM
                    )
                }

                PlayerServiceModern.PLAYLIST -> {
                    val likedSongCount = database.songTable.allFavorites().first().size
                    val cachedSongCount = getCountCachedSongs().first()
                    val downloadedSongCount = getCountDownloadedSongs().first()
                    val onDeviceSongCount = database.songTable.allOnDevice().first().size
                    val playlists = database.playlistTable.sortPreviewsBySongCount().first()
                    listOf(
                        browsableMediaItem(
                            "${PlayerServiceModern.PLAYLIST}/${ID_FAVORITES}",
                            context.getString(R.string.favorites),
                            likedSongCount.toString(),
                            drawableUri(R.drawable.heart),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST
                        ),
                        browsableMediaItem(
                            "${PlayerServiceModern.PLAYLIST}/${ID_CACHED}",
                            context.getString(R.string.cached),
                            cachedSongCount.toString(),
                            drawableUri(R.drawable.download),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST
                        ),
                        browsableMediaItem(
                            "${PlayerServiceModern.PLAYLIST}/${ID_DOWNLOADED}",
                            context.getString(R.string.downloaded),
                            downloadedSongCount.toString(),
                            drawableUri(R.drawable.downloaded),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST
                        ),
                        browsableMediaItem(
                            "${PlayerServiceModern.PLAYLIST}/${ID_TOP}",
                            context.getString(R.string.playlist_top),
                            null,
                            drawableUri(R.drawable.trending),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST
                        ),
                        browsableMediaItem(
                            "${PlayerServiceModern.PLAYLIST}/${ID_ONDEVICE}",
                            context.getString(R.string.on_device),
                            onDeviceSongCount.toString(),
                            drawableUri(R.drawable.devices),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST
                        )
                    ) + playlists.map { playlist ->
                        browsableMediaItem(
                            "${PlayerServiceModern.PLAYLIST}/${playlist.playlist.id}",
                            playlist.playlist.name,
                            playlist.songCount.toString(),
                            drawableUri(R.drawable.library),
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
                            searchedSongs.map { it.toMediaItem(parentId) }
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
                                browsableMediaItem(
                                    "${PlayerServiceModern.ARTIST}/${artist.key}",
                                    artist.info?.name ?: "",
                                    artist.subscribersCountText,
                                    artist.thumbnail?.url?.toUri(),
                                    MediaMetadata.MEDIA_TYPE_ARTIST
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
                                browsableMediaItem(
                                    "${PlayerServiceModern.ALBUM}/${album.key}",
                                    album.info?.name ?: "",
                                    album.authors?.joinToString(", ") { it.name ?: "" },
                                    album.thumbnail?.url?.toUri(),
                                    MediaMetadata.MEDIA_TYPE_ALBUM
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
                                video.asSong.toMediaItem(parentId)
                            }
                        }
                        PlayerServiceModern.ARTIST -> database.songArtistMapTable.allSongsBy(parts[1]).first().map { it.toMediaItem(parentId) }
                        PlayerServiceModern.ALBUM -> database.songAlbumMapTable.allSongsOf(parts[1]).first().map { it.toMediaItem(parentId) }
                        PlayerServiceModern.PLAYLIST -> {
                            val playlistId = parts[1]
                            when (playlistId) {
                                ID_FAVORITES -> database.songTable.allFavorites().map { it.reversed() }
                                ID_CACHED -> database.formatTable
                                    .allWithSongs()
                                    .map { list ->
                                        list.fastFilter {
                                            val contentLength = it.format.contentLength
                                            contentLength != null && binder.cache.isCached(it.song.id, 0L, contentLength)
                                        }
                                            .reversed()
                                            .fastMap(FormatWithSong::song)
                                    }
                                ID_TOP -> database.eventTable
                                    .findSongsMostPlayedBetween(
                                        from = 0,
                                        limit = context.preferences
                                            .getEnum(MaxTopPlaylistItemsKey, MaxTopPlaylistItems.`10`)
                                            .toInt()
                                    )
                                ID_ONDEVICE -> database.songTable.allOnDevice()
                                ID_DOWNLOADED -> {
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
                            }.first().map { it.toMediaItem(parentId) }
                        }
                        else -> emptyList()
                    }
                }
            },
            params
        )
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
                ?.toMediaItem()
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
        if (mediaItems.firstOrNull()?.mediaId == ID_LUCKY_SHUFFLE) {
            val allSongs = (QuickPicksRepository.trendingList.value + (QuickPicksRepository.relatedPage.value?.songs?.map { it.asSong } ?: emptyList()))
                .distinctBy { it.id }
                .shuffled()

            if (allSongs.isNotEmpty()) {
                return@future MediaSession.MediaItemsWithStartPosition(allSongs.map { it.toMediaItem() }, 0, 0)
            }
        }

        var queryList = emptyList<Song>()
        var startIdx = startIndex

        runCatching {
            var songId = ""

            val paths = mediaItems.first().mediaId.split( "/" )
            when( paths.first() ) {
                ID_QUICK_PICKS -> {
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
                        ID_FAVORITES -> database.songTable.allFavorites().map { it.reversed() }
                        ID_CACHED -> database.formatTable
                                             .allWithSongs()
                                             .map { list ->
                                                 list.fastFilter {
                                                     val contentLength = it.format.contentLength
                                                     contentLength != null && binder.cache.isCached( it.song.id, 0L, contentLength )
                                                 }
                                                     .reversed()
                                                     .fastMap( FormatWithSong::song )
                                             }
                        ID_TOP -> database.eventTable
                                           .findSongsMostPlayedBetween(
                                               from = 0,
                                               limit = context.preferences
                                                   .getEnum(MaxTopPlaylistItemsKey, MaxTopPlaylistItems.`10`)
                                                   .toInt()
                                           )
                        ID_ONDEVICE -> database.songTable.allOnDevice()
                        ID_DOWNLOADED -> {
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

        return@future MediaSession.MediaItemsWithStartPosition( queryList.map{ it.toMediaItem() }, startIdx, startPositionMs )
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
                mediaItems = map { it.mediaItem.asSong.toMediaItem( true ) }
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

    private fun drawableUri(@DrawableRes id: Int) = Uri.Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.resources.getResourcePackageName(id))
        .appendPath(context.resources.getResourceTypeName(id))
        .appendPath(context.resources.getResourceEntryName(id))
        .build()

    private fun browsableMediaItem(
        id: String,
        title: String,
        subtitle: String?,
        iconUri: Uri?,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_MUSIC
    ) =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(cleanPrefix(title))
                    .setSubtitle(subtitle)
                    .setArtist(subtitle)
                    .setArtworkUri(iconUri)
                    .setIsPlayable(false)
                    .setIsBrowsable(true)
                    .setMediaType(mediaType)
                    .build()
            )
            .build()

    private fun Song.toMediaItem(path: String) =
        MediaItem.Builder()
            .setMediaId("$path/$id")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(if (path.contains(MediaSessionConstants.ID_SEARCH_VIDEOS)) "🎥 " + cleanTitle() else cleanTitle())
                    .setSubtitle(cleanArtistsText())
                    .setArtist(cleanArtistsText())
                    .setArtworkUri(thumbnailUrl?.thumbnail(480)?.toUri())
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(if (path.contains(MediaSessionConstants.ID_SEARCH_VIDEOS)) MediaMetadata.MEDIA_TYPE_VIDEO else MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setExtras(Bundle().apply {
                        val isExplicit = title.startsWith(EXPLICIT_PREFIX)
                        putBoolean(EXTRAS_KEY_IS_EXPLICIT, isExplicit)
                        putBoolean(EXPLICIT_BUNDLE_TAG, isExplicit)
                    })
                    .build()
            )
            .build()

    private fun Song.toMediaItem(isFromPersistentQueue: Boolean = false): MediaItem {
        val bundle = Bundle().apply {
            putBoolean(persistentQueueKey, isFromPersistentQueue)
        }

        val mediaItem = asMediaItem
        val metadata: MediaMetadata = mediaItem.mediaMetadata
                                               .buildUpon()
                                               .setExtras(bundle)
                                               .build()

        return mediaItem.buildUpon().setMediaMetadata(metadata).build()
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

object MediaSessionConstants {
    const val ID_QUICK_PICKS = "QUICK_PICKS"
    const val ID_LUCKY_SHUFFLE = "LUCKY_SHUFFLE"
    const val ID_SEARCH_SONGS = "SEARCH_SONGS"
    const val ID_SEARCH_ARTISTS = "SEARCH_ARTISTS"
    const val ID_SEARCH_ALBUMS = "SEARCH_ALBUMS"
    const val ID_SEARCH_VIDEOS = "SEARCH_VIDEOS"
    const val ID_FAVORITES = "FAVORITES"
    const val ID_CACHED = "CACHED"
    const val ID_DOWNLOADED = "DOWNLOADED"
    const val ID_TOP = "TOP"
    const val ID_ONDEVICE = "ONDEVICE"
    const val ACTION_TOGGLE_DOWNLOAD = "TOGGLE_DOWNLOAD"
    const val ACTION_TOGGLE_LIKE = "TOGGLE_LIKE"
    const val ACTION_TOGGLE_SHUFFLE = "TOGGLE_SHUFFLE"
    const val ACTION_TOGGLE_REPEAT_MODE = "TOGGLE_REPEAT_MODE"
    const val ACTION_START_RADIO = "START_RADIO"
    const val ACTION_SEARCH = "ACTION_SEARCH"
    val CommandToggleDownload = SessionCommand(ACTION_TOGGLE_DOWNLOAD, Bundle.EMPTY)
    val CommandToggleLike = SessionCommand(ACTION_TOGGLE_LIKE, Bundle.EMPTY)
    val CommandToggleShuffle = SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY)
    val CommandToggleRepeatMode = SessionCommand(ACTION_TOGGLE_REPEAT_MODE, Bundle.EMPTY)
    val CommandStartRadio = SessionCommand(ACTION_START_RADIO, Bundle.EMPTY)
    val CommandSearch = SessionCommand(ACTION_SEARCH, Bundle.EMPTY)
}
