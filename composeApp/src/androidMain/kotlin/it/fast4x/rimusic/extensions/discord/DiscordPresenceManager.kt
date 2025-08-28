package it.fast4x.rimusic.extensions.discord

import android.content.Context
import androidx.media3.common.MediaItem
import com.my.kizzyrpc.KizzyRPC
import com.my.kizzyrpc.model.Activity
import com.my.kizzyrpc.model.Assets
import com.my.kizzyrpc.model.Timestamps
import kotlinx.coroutines.*
import okhttp3.*
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.SupervisorJob
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import java.io.FileOutputStream
import androidx.core.graphics.scale
import app.kreate.android.R
import me.knighthat.utils.Toaster
import me.knighthat.utils.isNetworkAvailable
import timber.log.Timber

    /**
     * THIS IS STILL IN BETA AND MAY NOT WORK AS EXPECTED AND CAUSE CRASH
     * Call this method when the playing state changes.
     * - isPlaying = true : send the "playing" presence and refresh it every 10s
     * - isPlaying = false : launch a timer, then send the "paused" presence (frozen time)
     */


class DiscordPresenceManager(
    private val context: Context,
    private val getToken: () -> String?,
    private val externalScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private var rpc: KizzyRPC? = null
    private var lastToken: String? = null
    private var lastMediaItem: MediaItem? = null
    private var lastPosition: Long = 0L
    private var isStopped = false
    private val discordScope = externalScope
    private var refreshJob: Job? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val uploadApi = "https://tmpfiles.org/api/v1/upload"
    @Volatile private var isUpdatingPresence = false
    @Volatile private var smallAssetUrl: String? = null
    @Volatile private var albumAssetUrl: String? = null


    /**
     * Validate the token
     */

     internal suspend fun validateToken(token: String): Boolean? = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable(context)) return@withContext null
        val request = Request.Builder()
            .url("https://discord.com/api/v9/users/@me")
            .header("Authorization", token)
            .get()
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        }.getOrElse {
            Timber.tag("DiscordPresence").e(it, "Error validating token: ${it.message}")
            if (it is java.io.IOException) {
                null
            } else {
                false
            }
        }
    }


    fun onPlayingStateChanged(mediaItem: MediaItem?, isPlaying: Boolean, position: Long = 0L, duration: Long = 0L, now: Long = System.currentTimeMillis(), getCurrentPosition: (() -> Long)? = null, isPlayingProvider: (() -> Boolean)? = null) {
        if (isStopped) return
        val token = getToken() ?: return
        if (token.isEmpty()) return

        if (!isNetworkAvailable(context)) {
            return
        }

        refreshJob?.cancel()
        refreshJob = null

        if (token != lastToken) {
            rpc?.closeRPC()
            rpc = KizzyRPC(token)
            lastToken = token
        }

        lastMediaItem = mediaItem
        lastPosition = position
        if (mediaItem == null) {
            sendPausedPresence(duration, now, position)
            return
        }
        if (isPlaying) {
            sendPlayingPresence(mediaItem, position, duration, now)
            startRefreshJob(
                isPlayingProvider = isPlayingProvider ?: { true },
                mediaItem = mediaItem,
                getCurrentPosition = getCurrentPosition ?: { position },
                pausedPosition = position,
                duration = duration
            )
        } else {
            sendPausedPresence(duration, now, position)
            startRefreshJob(
                isPlayingProvider = isPlayingProvider ?: { false },
                mediaItem = mediaItem,
                getCurrentPosition = getCurrentPosition ?: { position },
                pausedPosition = position,
                duration = duration
            )
        }
    }

    /**
     * Send the "Paused" presence with the frozen time.
     */
    private fun sendPausedPresence(duration: Long, now: Long, pausedPosition: Long) {
        if (isStopped) return
        val mediaItem = lastMediaItem ?: return
        val frozenTimestamp = now - pausedPosition
        val title = mediaItem.mediaMetadata.title?.toString().takeIf { !it.isNullOrBlank() } ?: context.getString(R.string.unknown_title)
        val artist = mediaItem.mediaMetadata.artist?.toString().takeIf { !it.isNullOrBlank() } ?: context.getString(R.string.unknown_artist)
        discordScope.launch {
            if (isStopped) return@launch
            sendActivity(
                mediaItem = mediaItem,
                details = "⏸️ Paused: $title",
                state = artist,
                start = frozenTimestamp,
                end = frozenTimestamp,
                status = "online",
                paused = true
            )
        }
    }

    /**
     * Send a custom discord activity
     */
    private suspend fun sendActivity(
        mediaItem: MediaItem,
        details: String,
        state: String,
        start: Long,
        end: Long,
        status: String,
        paused: Boolean
    ) {
        if (isStopped) return
        val token = getToken() ?: return
        if (token.isEmpty()) return

        when (validateToken(token)) {
            false -> {
                Timber.tag("DiscordPresence").e("Invalid token, stopping presence updates")
                Toaster.e(R.string.discord_token_text_invalid)
                return
            }
            null -> {
                Timber.tag("DiscordPresence").w("Network error while updating presence, skipping.")
                return
            }
            true -> { /* Token is valid, continue */ }
        }

        if (token != lastToken) {
            rpc?.closeRPC()
            rpc = KizzyRPC(token)
            lastToken = token
        }
        val largeImageUrl = getLargeImageUrl(mediaItem)
        val smallImageUrl = getSmallImageUrl()
        val largeTextValue = if (state.isNotBlank()) "$details - $state" else details
        runCatching {
            rpc?.setActivity(
                activity = Activity(
                    applicationId = "1379051016007454760",
                    name = "N-Zik",
                    details = details,
                    state = state,
                    type = TypeDiscordActivity.LISTENING.value,
                    timestamps = Timestamps(
                        start = start,
                        end = end
                    ),
                    assets = Assets(
                        largeImage = largeImageUrl,
                        smallImage = smallImageUrl,
                        largeText = largeTextValue,
                        smallText = "v${getVersionName(context)}",
                    ),
                    buttons = listOf("Get N-Zik", "Listen to YTMusic"),
                    metadata = com.my.kizzyrpc.model.Metadata(
                        listOf(
                            "https://github.com/NEVARLeVrai/N-Zik/",
                            "https://music.youtube.com/watch?v=${mediaItem.mediaId}",
                        )
                    )
                ),
                status = status,
                since = System.currentTimeMillis()
            )
        }.onFailure {
            Timber.tag("DiscordPresence").e(it, "Error setActivity: ${it.message}")
        }
    }

    /**
     * Close the discord presence (STOP)
     */
    fun onStop() {
        isStopped = true
        refreshJob?.cancel()
        rpc?.closeRPC()
        discordScope.cancel()
    }

    /**
     * Get the version name of the app
     */
    fun getVersionName(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }
    
    /**
     * Get the type of the discord activity
     */
    enum class TypeDiscordActivity (val value: Int) {
        PLAYING(0),
        STREAMING(1),
        LISTENING(2),
        WATCHING(3),
        COMPETING(5)
    }

    /**
     * Upload an image to the tmpfiles.org
     */
    private suspend fun uploadImage(file: File): String? = withContext(Dispatchers.IO) {
        val compressed = if (file.length() > 1_000_000) compressImage(file) else file
        try {
            if (!isNetworkAvailable(context)) {
                return@withContext null
            }
            val requestBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", compressed!!.name, compressed.readBytes().toRequestBody("image/*".toMediaType()))
                .build()
            val request = Request.Builder().url(uploadApi).post(requestBody).build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: return@runCatching null
                    json.decodeFromString<ApiResponse>(body).data.url
                }
            }.onFailure {
                Timber.tag("DiscordPresence").e(it, "Error upload: ${it.message}")
            }.getOrNull()?.replace("https://tmpfiles.org/", "https://tmpfiles.org/dl/")
        } finally {
            if (compressed != null && compressed != file) compressed.delete()
        }
    }

    /**
     * Get the artwork file
     */
    private fun getArtworkFile(mediaItem: MediaItem): File? {
        val uri = mediaItem.mediaMetadata.artworkUri ?: return null
        val file = if (uri.scheme == "file" || uri.scheme == null) File(uri.path!!) else null
        if (file != null && file.length() > 1_000_000) {
            return compressImage(file)
        }
        return file
    }

    /**
     * Get the discord asset uri
     */
    private suspend fun getDiscordAssetUri(imageUrl: String, token: String): String? = withContext(Dispatchers.IO) {
        if (imageUrl.startsWith("mp:")) return@withContext imageUrl
        val api = "https://discord.com/api/v9/applications/1379051016007454760/external-assets"
        val request = Request.Builder().url(api)
            .header("Authorization", token)
            .post("{\"urls\":[\"$imageUrl\"]}".toRequestBody("application/json".toMediaType()))
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@runCatching null
                val jsonElement = Json.parseToJsonElement(body)
                
                // Handle both JsonArray and JsonObject responses
                val jsonArr = when {
                    jsonElement is kotlinx.serialization.json.JsonArray -> jsonElement
                    jsonElement is kotlinx.serialization.json.JsonObject -> {
                        // If it's a JsonObject, try to extract array from common fields
                        jsonElement["data"]?.jsonArray ?: jsonElement["results"]?.jsonArray ?: return@runCatching null
                    }
                    else -> {
                        Timber.tag("DiscordPresence").e("Unexpected JSON response type: ${jsonElement::class.simpleName}")
                        return@runCatching null
                    }
                }
                
                val externalAssetPath = jsonArr.firstOrNull()?.jsonObject?.get("external_asset_path")?.toString()?.replace("\"", "")
                externalAssetPath?.let { "mp:$it" }
            }
        }.onFailure {
            Timber.tag("DiscordPresence").e(it, "Error assetUri: ${it.message}")
        }.getOrNull()
    }

    /**
     * Get the large image url
     */
    private suspend fun getLargeImageUrl(mediaItem: MediaItem): String? {
        val token = getToken() ?: return null
        val artworkFile = getArtworkFile(mediaItem)

        val url = if (artworkFile != null && artworkFile.exists()) {
            uploadImage(artworkFile)
        } else {
            mediaItem.mediaMetadata.artworkUri?.toString()?.takeIf { it.startsWith("http") }
        }
        artworkFile?.delete()

        val asset = if (url != null) getDiscordAssetUri(url, token) else null

        if (asset != null) return asset

        // Fallback logic starts here if no artwork is found
        if (albumAssetUrl != null) return albumAssetUrl

        val fallbackUrl = "https://raw.githubusercontent.com/NEVARLeVrai/N-Zik/main/assets/discord/fallback_album.png"
        val fallbackAsset = getDiscordAssetUri(fallbackUrl, token)
        if (fallbackAsset != null) {
            albumAssetUrl = fallbackAsset
        }
        return fallbackAsset
    }

    /**
     * Get the small image url
     */
    private suspend fun getSmallImageUrl(): String? {
        if (smallAssetUrl != null) return smallAssetUrl

        val token = getToken() ?: return null
        // Use a direct, permanent URL to the fallback image for reliability and speed,
        // just like it's done for Google/YouTube artworks.
        val url = "https://raw.githubusercontent.com/NEVARLeVrai/N-Zik/main/assets/discord/fallback_app.png"

        val asset = getDiscordAssetUri(url, token)
        if (asset != null) {
            smallAssetUrl = asset
        }
        return asset
    }

    /**
     * Send a custom discord activity
     */
    private fun sendPlayingPresence(mediaItem: MediaItem, position: Long, duration: Long, now: Long) {
        val start = now - position
        val end = start + duration
        val title = mediaItem.mediaMetadata.title?.toString().takeIf { !it.isNullOrBlank() } ?: context.getString(R.string.unknown_title)
        val artist = mediaItem.mediaMetadata.artist?.toString().takeIf { !it.isNullOrBlank() } ?: context.getString(R.string.unknown_artist)
        discordScope.launch {
            sendActivity(
                mediaItem = mediaItem,
                details = title,
                state = artist,
                start = start,
                end = end,
                status = "online",
                paused = false
            )
        }
    }


    /**
     * Start the refresh job
     */
    private fun startRefreshJob(
        isPlayingProvider: () -> Boolean,
        mediaItem: MediaItem,
        getCurrentPosition: () -> Long,
        pausedPosition: Long,
        duration: Long
    ) {
        refreshJob = discordScope.launch {
            while (isActive && !isStopped) {
                delay(15_000L)
                if (!isNetworkAvailable(context)) {
                    continue
                }
                val isPlaying = isPlayingProvider()
                if (isPlaying) {
                    val pos = getCurrentPosition()
                    sendPlayingPresence(mediaItem, pos, duration, System.currentTimeMillis())
                } else {
                    sendPausedPresence(duration, System.currentTimeMillis(), pausedPosition)
                }
            }
        }
    }

    /**
     * Compress an image
     */
    private fun compressImage(file: File, maxSize: Int = 512): File? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = false }
            var bitmap = BitmapFactory.decodeFile(file.absolutePath, options)

            if (bitmap.width > maxSize || bitmap.height > maxSize) {
                val ratio = minOf(maxSize.toFloat() / bitmap.width, maxSize.toFloat() / bitmap.height)
                val width = (bitmap.width * ratio).toInt()
                val height = (bitmap.height * ratio).toInt()
                bitmap = bitmap.scale(width, height)
            }

            val compressedFile = File.createTempFile("compressed_", ".jpg", file.parentFile)
            FileOutputStream(compressedFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 5, out)
            }
            compressedFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

@Serializable
data class ApiResponse(val status: String, val data: Data) {
    @Serializable
    data class Data(val url: String)
}