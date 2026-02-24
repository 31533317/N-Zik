package app.it.fast4x.rimusic.service.modern

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.core.net.toUri
import app.it.fast4x.rimusic.EXPLICIT_PREFIX
import app.it.fast4x.rimusic.cleanPrefix
import app.it.fast4x.rimusic.models.Song
import app.it.fast4x.rimusic.utils.EXPLICIT_BUNDLE_TAG
import app.it.fast4x.rimusic.utils.asMediaItem
import app.it.fast4x.rimusic.utils.persistentQueueKey
import androidx.media3.session.MediaConstants.EXTRAS_KEY_IS_EXPLICIT
import app.n_zik.android.core.coil.thumbnail

@UnstableApi
object MediaItemMapper {

    fun mapArtistToMediaItem(
        parentId: String,
        id: String,
        name: String,
        thumbnailUrl: String?,
        subtext: String? = null,
        searchPath: String = ""
    ): MediaItem = browsableMediaItem(
        id = "$parentId/$id",
        title = name,
        subtitle = subtext,
        iconUri = thumbnailUrl?.thumbnail(480)?.toUri(), // ENHANCED QUALITY
        mediaType = MediaMetadata.MEDIA_TYPE_ARTIST,
        path = searchPath.ifEmpty { parentId }
    )

    fun mapAlbumToMediaItem(
        parentId: String,
        id: String,
        title: String,
        authorsText: String?,
        thumbnailUrl: String?,
        searchPath: String = ""
    ): MediaItem = browsableMediaItem(
        id = "$parentId/$id",
        title = title,
        subtitle = authorsText,
        iconUri = thumbnailUrl?.thumbnail(480)?.toUri(), // ENHANCED QUALITY
        mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
        path = searchPath.ifEmpty { parentId }
    )


    fun mapSongToMediaItem(song: Song, path: String): MediaItem =
        MediaItem.Builder()
            .setMediaId("$path/${song.id}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.cleanTitle())

                    .setSubtitle(song.cleanArtistsText())
                    .setArtist(song.cleanArtistsText())
                    .setArtworkUri(song.thumbnailUrl?.thumbnail(480)?.toUri()) // ENHANCED QUALITY
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(if (path.contains(MediaSessionConstants.ID_SEARCH_VIDEOS)) MediaMetadata.MEDIA_TYPE_VIDEO else MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setExtras(Bundle().apply {
                        val isExplicit = song.title.startsWith(EXPLICIT_PREFIX)
                        putBoolean(EXTRAS_KEY_IS_EXPLICIT, isExplicit)
                        putBoolean(EXPLICIT_BUNDLE_TAG, isExplicit)
                    })
                    .build()
            )
            .build()

    fun mapSongToMediaItem(song: Song, isFromPersistentQueue: Boolean = false): MediaItem {
        val bundle = Bundle().apply {
            putBoolean(persistentQueueKey, isFromPersistentQueue)
        }

        val mediaItem = song.asMediaItem
        val metadata: MediaMetadata = mediaItem.mediaMetadata
            .buildUpon()
            .setExtras(bundle)
            .build()

        return mediaItem.buildUpon().setMediaMetadata(metadata).build()
    }

    fun browsableMediaItem(
        id: String,
        title: String,
        subtitle: String?,
        iconUri: Uri?,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_MUSIC,
        path: String = ""
    ): MediaItem {
        val cleanTitle = cleanPrefix(title)
        
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(cleanTitle)
                    .setSubtitle(subtitle)
                    .setArtist(subtitle)
                    .setArtworkUri(iconUri)
                    .setIsPlayable(false)
                    .setIsBrowsable(true)
                    .setMediaType(mediaType)
                    .build()
            )
            .build()
    }


    fun drawableUri(context: Context, @DrawableRes id: Int): Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.resources.getResourcePackageName(id))
        .appendPath(context.resources.getResourceTypeName(id))
        .appendPath(context.resources.getResourceEntryName(id))
        .build()
}
