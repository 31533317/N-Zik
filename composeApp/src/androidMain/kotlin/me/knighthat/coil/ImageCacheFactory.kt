package me.knighthat.coil

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import app.kreate.android.R
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.AsyncImagePainter.State
import coil3.compose.rememberAsyncImagePainter
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.request.SuccessResult
import coil3.toBitmap
import it.fast4x.innertube.models.Thumbnail
import it.fast4x.rimusic.appContext
import it.fast4x.rimusic.enums.CoilDiskCacheMaxSize
import it.fast4x.rimusic.enums.ExoPlayerCacheLocation
import it.fast4x.rimusic.enums.ImageQualityFormat
import it.fast4x.rimusic.thumbnailShape
import it.fast4x.rimusic.utils.coilCustomDiskCacheKey
import it.fast4x.rimusic.utils.coilDiskCacheMaxSizeKey
import it.fast4x.rimusic.utils.exoPlayerCacheLocationKey
import it.fast4x.rimusic.utils.getEnum
import it.fast4x.rimusic.utils.imageQualityFormatKey
import it.fast4x.rimusic.utils.isConnectionMeteredEnabledKey
import it.fast4x.rimusic.utils.preferences
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import timber.log.Timber
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoilApi::class)
object ImageCacheFactory {

    internal const val TAG = "ImageCacheFactory"

    val DISK_CACHE: DiskCache by lazy {
        val preferences = appContext().preferences
        val diskSize = preferences.getEnum(coilDiskCacheMaxSizeKey, CoilDiskCacheMaxSize.`128MB`)
        val cacheLocation = preferences.getEnum(exoPlayerCacheLocationKey, ExoPlayerCacheLocation.System)
        val cacheDir = when (cacheLocation) {
            ExoPlayerCacheLocation.System -> appContext().cacheDir
            ExoPlayerCacheLocation.Private -> appContext().filesDir
        }.resolve("coil")
        val maxSizeBytes = when (diskSize) {
            CoilDiskCacheMaxSize.Custom -> {
                val customSize = preferences.getInt(coilCustomDiskCacheKey, 128)
                customSize.times(1000L).times(1000)
            }
            else -> diskSize.bytes
        }
        DiskCache.Builder()
            .directory(cacheDir.toPath().toOkioPath())
            .maxSizeBytes(maxSizeBytes)
            .build()
    }

    enum class NetworkQuality(val size: Int, val ttl: Long) {
        LOW(300, TimeUnit.HOURS.toMillis(1)),
        MEDIUM(720, TimeUnit.HOURS.toMillis(24)),
        HIGH(1200, TimeUnit.DAYS.toMillis(14))
    }

    data class DownloadDecision(val useNetwork: Boolean, val quality: NetworkQuality)

    private const val COOLDOWN_MS = 10 * 1000L
    private val cooldownMap = ConcurrentHashMap<String, Long>()
    private val cacheKeyMap = ConcurrentHashMap<String, String>()

    internal object PlaylistThumbnailStore {
        private const val PREFS_NAME = "playlist_thumbnail_store"
        private fun getPrefs() = appContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Store format: "lowResUrl|highResUrl"
        fun save(id: String, url: String, isHigh: Boolean) {
            try {
                val current = getPrefs().getString(id, null)
                val parts = current?.split("|")
                var low = parts?.getOrNull(0).orEmpty()
                var high = parts?.getOrNull(1).orEmpty()

                if (isHigh) high = url else low = url

                if (low.isNotEmpty() || high.isNotEmpty()) {
                    getPrefs().edit().putString(id, "$low|$high").apply()
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to save playlist thumbnail for $id")
            }
        }

        fun getHighUrl(id: String): String? {
            return try {
                val current = getPrefs().getString(id, null) ?: return null
                current.split("|").getOrNull(1)?.takeIf { it.isNotEmpty() }
            } catch (e: Exception) { null }
        }

        fun getLowUrl(id: String): String? {
            return try {
                val current = getPrefs().getString(id, null) ?: return null
                current.split("|").getOrNull(0)?.takeIf { it.isNotEmpty() }
            } catch (e: Exception) { null }
        }

        fun clear(id: String) {
            try {
                getPrefs().edit().remove(id).apply()
            } catch (e: Exception) {}
        }

        fun clearAll() {
            try { getPrefs().edit().clear().apply() } catch (e: Exception) {}
        }
    }

    private object CacheMetadataStore {
        private const val PREFS_NAME = "image_cache_metadata"
        private fun getPrefs() = appContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun save(url: String, quality: NetworkQuality) {
            try {
                val key = url.hashCode().toString()
                getPrefs().edit().putString(key, "${quality.name}:${System.currentTimeMillis()}").apply()
            } catch (e: Exception) {}
        }

        fun get(url: String): NetworkQuality? {
            return try {
                val key = url.hashCode().toString()
                val value = getPrefs().getString(key, null) ?: return null
                NetworkQuality.valueOf(value.substringBefore(":"))
            } catch (e: Exception) { null }
        }

        fun remove(url: String) {
            try { getPrefs().edit().remove(url.hashCode().toString()).apply() } catch (e: Exception) {}
        }

        fun clearAll() {
            try { getPrefs().edit().clear().apply() } catch (e: Exception) {}
        }
    }

    val LOADER: ImageLoader by lazy {
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        ImageLoader.Builder(appContext())
            .crossfade(true)
            .memoryCache { MemoryCache.Builder().maxSizePercent(appContext(), 0.15).strongReferencesEnabled(true).build() }
            .diskCache(DISK_CACHE)
            .components {
                add(OkHttpNetworkFetcherFactory(httpClient))
            }
            .build()
    }

    fun generateCacheKeySync(url: String?, quality: NetworkQuality): String {
        if (url.isNullOrBlank()) return "empty"
        
        val id = url.getYouTubeId()
        if (id != null && (url.contains("pl_c") || url.contains("podcasts"))) {
            val resSuffix = if (url.isYouTubeHighRes()) "HIGH" else "LOW"
            return "playlist_${id}_$resSuffix"
        }

        val cacheKey = "${url}_${quality.size}"
        return cacheKeyMap.getOrPut(cacheKey) {
            try {
                val md = MessageDigest.getInstance("MD5")
                val digest = md.digest(url.toByteArray())
                digest.fold("") { str, it -> str + "%02x".format(it) }
            } catch (e: Exception) { "${url.hashCode()}_${quality.size}" }
        }
    }

    fun clearCacheForKey(url: String?, quality: NetworkQuality) {
        if (url == null) return
        val key = generateCacheKeySync(url, quality)
        try {
            LOADER.memoryCache?.remove(MemoryCache.Key(key))
            DISK_CACHE.remove(key)
        } catch (e: Exception) {}
    }

    fun getNetworkQuality(): NetworkQuality {
        val context = appContext()
        val forcedQuality = context.preferences.getEnum(imageQualityFormatKey, ImageQualityFormat.Auto)
        if (forcedQuality != ImageQualityFormat.Auto) {
            val quality = when (forcedQuality) {
                ImageQualityFormat.High -> NetworkQuality.HIGH
                ImageQualityFormat.Medium -> NetworkQuality.MEDIUM
                else -> NetworkQuality.LOW
            }
            Timber.tag(TAG).d("📡 [NET] Forced quality: $quality")
            return quality
        }

        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NetworkQuality.LOW
            val bandwidth = capabilities.linkDownstreamBandwidthKbps
            
            val quality = when {
                bandwidth > 20000 -> NetworkQuality.HIGH
                bandwidth > 5000 -> NetworkQuality.MEDIUM
                else -> NetworkQuality.LOW
            }
            Timber.tag(TAG).v("📡 [NET] Auto quality: $quality (bandwidth=${bandwidth}kbps)")
            quality
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "📡 [NET] Error detecting quality, fallback to LOW")
            NetworkQuality.LOW
        }
    }

    fun getCurrentNetworkQuality(): NetworkQuality = getNetworkQuality()

    fun getDownloadDecision(thumbnailUrl: String?): DownloadDecision {
        if (thumbnailUrl.isNullOrBlank() || thumbnailUrl == "null") {
            Timber.tag(TAG).d("📂 [DECISION] Invalid URL, no download")
            return DownloadDecision(false, NetworkQuality.LOW)
        }
        
        val lastAttempt = cooldownMap[thumbnailUrl]
        val now = System.currentTimeMillis()
        if (lastAttempt != null && (now - lastAttempt) < COOLDOWN_MS) {
            val cachedQuality = CacheMetadataStore.get(thumbnailUrl) ?: NetworkQuality.LOW
            Timber.tag(TAG).d("🧠 [CACHE] Cooldown active, use cache: $cachedQuality")
            return DownloadDecision(false, cachedQuality)
        }

        val currentQuality = getNetworkQuality()
        val cachedQuality = CacheMetadataStore.get(thumbnailUrl)

        if (cachedQuality == null) {
            cooldownMap[thumbnailUrl] = now
            Timber.tag(TAG).d("🧠 [CACHE] No cache metadata, download: $currentQuality")
            return DownloadDecision(true, currentQuality)
        }

        if (currentQuality.ordinal > cachedQuality.ordinal) {
            cooldownMap[thumbnailUrl] = now
            Timber.tag(TAG).d("🚀 [UPGRADE] $cachedQuality -> $currentQuality")
            return DownloadDecision(true, currentQuality)
        }

        Timber.tag(TAG).v("🧠 [CACHE] Use cached: $cachedQuality")
        return DownloadDecision(false, cachedQuality)
    }

    @Composable
    fun Thumbnail(
        thumbnailUrl: String?,
        contentDescription: String? = null,
        contentScale: ContentScale = ContentScale.Crop,
        modifier: Modifier = Modifier.clip(thumbnailShape()).fillMaxSize()
    ) {
        val validUrl = if (thumbnailUrl.isNullOrBlank() || thumbnailUrl == "null") null else thumbnailUrl
        val decision = getDownloadDecision(validUrl)
        var currentUrl by remember(validUrl) { mutableStateOf(validUrl?.thumbnail(decision.quality.size)) }
        
        Timber.tag(TAG).v("🖼️ [START] Thumbnail: $validUrl -> $currentUrl")
        
        val request = ImageRequest.Builder(appContext())
            .data(currentUrl)
            .diskCacheKey(generateCacheKeySync(currentUrl, decision.quality))
            .memoryCacheKey(generateCacheKeySync(currentUrl, decision.quality))
            .listener(
                onSuccess = { _, result ->
                    val dataSource = result.dataSource
                    Timber.tag(TAG).i("✅ [SUCCESS] Thumbnail from $dataSource | $currentUrl")
                    if (dataSource != coil3.decode.DataSource.MEMORY_CACHE) {
                        val id = currentUrl?.getYouTubeId()
                        if (id != null) {
                            PlaylistThumbnailStore.save(id, currentUrl!!, currentUrl!!.isYouTubeHighRes())
                        }
                    }
                    if (validUrl != null && decision.useNetwork) {
                        CacheMetadataStore.save(validUrl, decision.quality)
                    }
                },
                onError = { _, result ->
                    val errorMsg = result.throwable.message ?: ""
                    val id = currentUrl?.getYouTubeId()
                    
                    // Un-swap fallback for Playlists/Podcasts (handling expired signatures)
                    // We are more aggressive: any error on a swapped URL triggers un-swap.
                    if (currentUrl != validUrl && id != null &&
                        (currentUrl?.contains("i.ytimg.com/pl_c/") == true || currentUrl?.contains("podcasts") == true)) {
                        
                        Timber.tag(TAG).w("♻️ [UN-SWAP] Thumbnail failed ($errorMsg), clearing cache & store. ID: $id")
                        clearCacheForKey(currentUrl, decision.quality)
                        PlaylistThumbnailStore.clear(id)
                        currentUrl = validUrl
                        return@listener
                    }
                    
                    if (errorMsg.contains("404") && currentUrl?.contains("i.ytimg.com/vi/") == true) {
                        val fallback = currentUrl.getNextYouTubeFallback()
                        if (fallback != null) {
                            Timber.tag(TAG).w("♻️ [FALLBACK] 404 on $currentUrl, trying $fallback")
                            currentUrl = fallback
                            return@listener
                        }
                    }
                    Timber.tag(TAG).e("❌ [ERROR] Thumbnail: $errorMsg | original=$validUrl | final=$currentUrl")
                }
            )
            .build()

        AsyncImage(
            model = request,
            imageLoader = LOADER,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier,
            placeholder = painterResource(R.drawable.loader),
            error = painterResource(R.drawable.ic_launcher_box),
            fallback = painterResource(R.drawable.ic_launcher_box)
        )
    }

    @Composable
    fun Painter(
        thumbnailUrl: String?,
        contentScale: ContentScale = ContentScale.Crop,
        @DrawableRes placeholder: Int? = null,
        @DrawableRes error: Int = R.drawable.ic_launcher_box,
        @DrawableRes fallback: Int = R.drawable.ic_launcher_box,
        onLoading: ((State.Loading) -> Unit)? = null,
        onSuccess: ((State.Success) -> Unit)? = null,
        onError: ((State.Error) -> Unit)? = null
    ): AsyncImagePainter {
        val validUrl = if (thumbnailUrl.isNullOrBlank() || thumbnailUrl == "null") null else thumbnailUrl
        val decision = getDownloadDecision(validUrl)
        var currentUrl by remember(validUrl) { mutableStateOf(validUrl?.thumbnail(decision.quality.size)) }
        
        Timber.tag(TAG).v("🎨 [START] Painter: $validUrl -> $currentUrl")
        
        val request = ImageRequest.Builder(appContext())
            .data(currentUrl)
            .diskCacheKey(generateCacheKeySync(currentUrl, decision.quality))
            .memoryCacheKey(generateCacheKeySync(currentUrl, decision.quality))
            .listener(
                onSuccess = { _, result ->
                    val dataSource = result.dataSource
                    Timber.tag(TAG).i("✅ [SUCCESS] Painter from $dataSource | $currentUrl")
                    if (dataSource != coil3.decode.DataSource.MEMORY_CACHE) {
                        val id = currentUrl?.getYouTubeId()
                        if (id != null) {
                            PlaylistThumbnailStore.save(id, currentUrl!!, currentUrl!!.isYouTubeHighRes())
                        }
                    }
                    if (validUrl != null && decision.useNetwork) {
                        CacheMetadataStore.save(validUrl, decision.quality)
                    }
                },
                onError = { _, result ->
                    val errorMsg = result.throwable.message ?: ""
                    val id = currentUrl?.getYouTubeId()

                    if (currentUrl != validUrl && id != null &&
                        (currentUrl?.contains("i.ytimg.com/pl_c/") == true || currentUrl?.contains("podcasts") == true)) {
                        
                        Timber.tag(TAG).w("♻️ [UN-SWAP] Painter failed ($errorMsg), clearing cache & store. ID: $id")
                        clearCacheForKey(currentUrl, decision.quality)
                        PlaylistThumbnailStore.clear(id)
                        currentUrl = validUrl
                        return@listener
                    }

                    if (errorMsg.contains("404") && currentUrl?.contains("i.ytimg.com/vi/") == true) {
                        val fallback = currentUrl.getNextYouTubeFallback()
                        if (fallback != null) {
                            Timber.tag(TAG).w("[FALLBACK] 404 on $currentUrl, trying $fallback")
                            currentUrl = fallback
                            return@listener
                        }
                    }
                }
            )
            .build()

        return rememberAsyncImagePainter(
            model = request,
            imageLoader = LOADER,
            contentScale = contentScale,
            placeholder = painterResource(placeholder ?: R.drawable.loader),
            error = painterResource(error),
            fallback = painterResource(fallback),
            onLoading = onLoading,
            onSuccess = onSuccess,
            onError = { state ->
                Timber.tag(TAG).e("❌ [UI_ERROR] Painter: ${state.result.throwable.message}")
                onError?.invoke(state)
            }
        )
    }

    @Composable
    fun AsyncImage(
        thumbnailUrl: String?,
        contentDescription: String? = null,
        contentScale: ContentScale = ContentScale.Crop,
        modifier: Modifier = Modifier,
        onLoading: ((State.Loading) -> Unit)? = null,
        onSuccess: ((State.Success) -> Unit)? = null,
        onError: ((State.Error) -> Unit)? = null
    ) {
        val validUrl = if (thumbnailUrl.isNullOrBlank() || thumbnailUrl == "null") null else thumbnailUrl
        val decision = getDownloadDecision(validUrl)
        var currentUrl by remember(validUrl) { mutableStateOf(validUrl?.thumbnail(decision.quality.size)) }
        Timber.tag(TAG).v("🖼️ [START] AsyncImage: $validUrl -> $currentUrl")
        
        val request = ImageRequest.Builder(appContext())
            .data(currentUrl)
            .diskCacheKey(generateCacheKeySync(currentUrl, decision.quality))
            .memoryCacheKey(generateCacheKeySync(currentUrl, decision.quality))
            .listener(
                onSuccess = { _, result ->
                    val dataSource = result.dataSource
                    Timber.tag(TAG).i("✅ [SUCCESS] AsyncImage from $dataSource | $currentUrl")
                    if (dataSource != coil3.decode.DataSource.MEMORY_CACHE) {
                        val id = currentUrl?.getYouTubeId()
                        if (id != null) {
                            PlaylistThumbnailStore.save(id, currentUrl!!, currentUrl!!.isYouTubeHighRes())
                        }
                    }
                    if (validUrl != null && decision.useNetwork) {
                        CacheMetadataStore.save(validUrl, decision.quality)
                    }
                },
                onError = { _, result ->
                    val errorMsg = result.throwable.message ?: ""
                    val id = currentUrl?.getYouTubeId()

                    if (currentUrl != validUrl && id != null &&
                        (currentUrl?.contains("i.ytimg.com/pl_c/") == true || currentUrl?.contains("podcasts") == true)) {
                        
                        Timber.tag(TAG).w("♻️ [UN-SWAP] AsyncImage failed ($errorMsg), clearing cache & store. ID: $id")
                        clearCacheForKey(currentUrl, decision.quality)
                        PlaylistThumbnailStore.clear(id)
                        currentUrl = validUrl
                        return@listener
                    }

                    if (errorMsg.contains("404") && currentUrl?.contains("i.ytimg.com/vi/") == true) {
                        val fallback = currentUrl.getNextYouTubeFallback()
                        if (fallback != null) {
                            Timber.tag(TAG).w("♻️ [FALLBACK] 404 on $currentUrl, trying $fallback")
                            currentUrl = fallback
                            return@listener
                        }
                    }
                    Timber.tag(TAG).e("❌ [ERROR] AsyncImage: $errorMsg | url=$currentUrl")
                }
            )
            .build()

        coil3.compose.AsyncImage(
            model = request,
            imageLoader = LOADER,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier,
            placeholder = painterResource(R.drawable.loader),
            error = painterResource(R.drawable.ic_launcher_box),
            fallback = painterResource(R.drawable.ic_launcher_box),
            onLoading = onLoading,
            onSuccess = onSuccess,
            onError = { state ->
                val errorMsg = state.result.throwable.message ?: ""
                if (errorMsg.contains("404") && currentUrl?.contains("i.ytimg.com/vi/") == true) {
                    val fallback = currentUrl.getNextYouTubeFallback()
                    if (fallback != null) {
                        // Re-trigger via currentUrl in listener above
                        return@AsyncImage
                    }
                }
                Timber.tag(TAG).e("❌ [UI_ERROR] AsyncImage: $errorMsg | url=$currentUrl")
                onError?.invoke(state)
            }
        )
    }

    suspend fun loadBitmap(url: String?, allowHardware: Boolean = false): Bitmap? {
        if (url.isNullOrBlank() || url == "null") {
            Timber.tag(TAG).d("📂 [LOAD] Invalid URL, returning null")
            return null
        }
        
        val decision = getDownloadDecision(url)
        var currentUrl = url.thumbnail(decision.quality.size)
        var lastError: String? = null
        
        while (currentUrl != null) {
            Timber.tag(TAG).v("📂 [START] loadBitmap: $url -> $currentUrl")
            
            val request = ImageRequest.Builder(appContext())
                .data(currentUrl)
                .diskCacheKey(generateCacheKeySync(currentUrl, decision.quality))
                .memoryCacheKey(generateCacheKeySync(currentUrl, decision.quality))
                .allowHardware(allowHardware)
                .build()
                
            val result = LOADER.execute(request)
            if (result.image != null) {
                val dataSource = (result as? SuccessResult)?.dataSource
                Timber.tag(TAG).i("✅ [SUCCESS] loadBitmap from $dataSource | $currentUrl")
                
                if (dataSource != null && dataSource != coil3.decode.DataSource.MEMORY_CACHE) {
                    val id = currentUrl.getYouTubeId()
                    if (id != null) {
                        PlaylistThumbnailStore.save(id, currentUrl, currentUrl.isYouTubeHighRes())
                    }
                }
                if (decision.useNetwork) {
                    CacheMetadataStore.save(url, decision.quality)
                }
                return result.image!!.toBitmap()
            }
            
            lastError = (result as? coil3.request.ErrorResult)?.throwable?.message ?: "Unknown error"
            
            // Un-swap fallback for loadBitmap
            val id = currentUrl.getYouTubeId()
            if (currentUrl != url && id != null && (currentUrl.contains("pl_c") || currentUrl.contains("podcasts"))) {
                
                Timber.tag(TAG).w("♻️ [UN-SWAP] loadBitmap failed ($lastError), clearing cache & store. ID: $id")
                clearCacheForKey(currentUrl, decision.quality)
                PlaylistThumbnailStore.clear(id)
                currentUrl = url
                continue
            }

            if (lastError.contains("404") && currentUrl.contains("i.ytimg.com/vi/")) {
                val fallback = currentUrl.getNextYouTubeFallback()
                if (fallback != null) {
                    Timber.tag(TAG).w("♻️ [FALLBACK] 404 on $currentUrl, trying $fallback")
                    currentUrl = fallback
                    continue
                }
            }
            break
        }
        
        Timber.tag(TAG).e("❌ [ERROR] loadBitmap: $lastError | url=$currentUrl")
        return null
    }

    fun preloadImage(thumbnailUrl: String?) {
        if (thumbnailUrl.isNullOrBlank() || thumbnailUrl == "null") {
            Timber.tag(TAG).d("📂 [PRELOAD] Invalid URL, skipping")
            return
        }
        
        val decision = getDownloadDecision(thumbnailUrl)
        val finalUrl = thumbnailUrl.thumbnail(decision.quality.size)
        
        fun enqueueWithFallback(url: String) {
            Timber.tag(TAG).v("⚡ [START] preloadImage: $thumbnailUrl -> $url")
            val request = ImageRequest.Builder(appContext())
                .data(url)
                .diskCacheKey(generateCacheKeySync(thumbnailUrl, decision.quality))
                .memoryCacheKey(generateCacheKeySync(thumbnailUrl, decision.quality))
                .listener(
                    onSuccess = { _, result ->
                        Timber.tag(TAG).i("✅ [SUCCESS] preloadImage from ${result.dataSource} | $url")
                        if (decision.useNetwork) {
                            CacheMetadataStore.save(thumbnailUrl, decision.quality)
                        }
                    },
                    onError = { _, result ->
                        val errorMsg = result.throwable.message ?: ""
                        if (errorMsg.contains("404") && url.contains("i.ytimg.com/vi/")) {
                            val fallback = url.getNextYouTubeFallback()
                            if (fallback != null) {
                                Timber.tag(TAG).w("♻️ [FALLBACK] 404 on $url, trying $fallback")
                                enqueueWithFallback(fallback)
                                return@listener
                            }
                        }
                        Timber.tag(TAG).e("❌ [ERROR] preloadImage: $errorMsg | url=$url")
                    }
                )
                .build()
            LOADER.enqueue(request)
        }
        
        if (finalUrl != null) enqueueWithFallback(finalUrl)
    }

    fun isImageCached(thumbnailUrl: String?): Boolean = CacheMetadataStore.get(thumbnailUrl ?: "") != null

    fun getDiskCache(): DiskCache = DISK_CACHE

    fun clearImageCache() {
        try {
            // 1. On vide d'abord la mémoire vive (RAM)
            LOADER.memoryCache?.clear()
            
            // 2. On vide le cache disque (Storage)
            DISK_CACHE.clear()
            
            // 3. On vide les registres de signatures apprises (Playlists)
            PlaylistThumbnailStore.clearAll()
            
            // 4. On vide les métadonnées de qualité
            CacheMetadataStore.clearAll()
            
            // 5. On réinitialise les registres temporaires
            cacheKeyMap.clear()
            cooldownMap.clear()
            
            Timber.tag(TAG).i("🧹 [CLEAN] Cache TOTAL vidé (RAM + DISK + STORES)")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "❌ [CLEAN] Erreur lors du nettoyage du cache")
        }
    }

    fun getCacheSize(): Long = try { DISK_CACHE.size } catch (e: Exception) { 0L }
}

fun String.resize(width: Int? = null, height: Int? = null): String {
    if (width == null && height == null) return this
    
    if (contains("googleusercontent.com")) {
        // Redimensionnement intelligent pour Google (wX-hY ou sX)
        val w = width ?: height ?: 0
        val h = height ?: width ?: 0
        
        return if (contains("=w") && contains("-h")) {
            replace(Regex("=w\\d+"), "=w$w").replace(Regex("-h\\d+"), "-h$h")
        } else if (contains("=s") || contains("-s")) {
            replace(Regex("([=-])s\\d+"), "$1s$w")
        } else {
            // Tentative de remplacement global si les marqueurs sont présents mais non groupés
            replace(Regex("([=-])w\\d+"), "$1w$w")
                .replace(Regex("([=-])h\\d+"), "$1h$h")
                .replace(Regex("([=-])s\\d+"), "$1s$w")
        }
    }
    
    if (startsWith("https://yt3.ggpht.com")) {
        val s = width ?: height ?: 0
        return replace(Regex("([=-])s\\d+"), "$1s$s")
    }
    
    return this
}

private fun String.getYouTubeId(): String? {
    return try {
        when {
            contains("i.ytimg.com/vi/") -> substringAfter("/vi/").substringBefore("/")
            contains("i.ytimg.com/pl_c/") -> substringAfter("/pl_c/").substringBefore("/")
            contains("i.ytimg.com/podcasts_artwork/") -> substringAfter("/podcasts_artwork/").substringBefore("/")
            else -> null
        }?.takeIf { it.isNotEmpty() && it != this }
    } catch (e: Exception) { null }
}

fun String?.thumbnail(size: Int): String? {
    if (this == null) return this
    
    val quality = if (size > 600) ImageCacheFactory.NetworkQuality.HIGH else ImageCacheFactory.NetworkQuality.LOW
    
    if (contains("i.ytimg.com/pl_c/") || contains("i.ytimg.com/podcasts_artwork/")) {
        val id = getYouTubeId()
        if (id != null) {
            // Priorité absolue à la version apprise (HQ) si elle existe
            val learnedHigh = ImageCacheFactory.PlaylistThumbnailStore.getHighUrl(id)
            if (learnedHigh != null) {
                return learnedHigh
            }

            // Si on demande de la HQ mais qu'on ne l'a pas apprise, on garde l'original.
            // ON NE FAIT PLUS DE SWAP mwEIC -> mwEKC car ça 404 sans la nouvelle signature 'rs'.
            if (quality == ImageCacheFactory.NetworkQuality.HIGH) {
                return this
            } else {
                val smallerUrl = ImageCacheFactory.PlaylistThumbnailStore.getLowUrl(id)
                if (smallerUrl != null && smallerUrl != this) {
                    return smallerUrl
                }
            }
        }
        return this 
    }

    // i.ytimg: modify video thumbnails based on size (Un-signing)
    when {
        contains("i.ytimg.com/vi/") -> {
            val suffix = when {
                size > 1000 -> "maxresdefault.jpg"
                size > 600 -> "sddefault.jpg"
                size > 300 -> "hqdefault.jpg"
                else -> "mqdefault.jpg"
            }
            // "Un-signing": Strip parameters and force suffix for best quality
            // Mes tests confirment que les vidéos fonctionnent sans signature contrairement aux playlists
            return replace(Regex("/[^/?]+\\.jpg(\\?.*)?$"), "/$suffix")
        }
        // Playlists and Podcasts (/pl_c/, /podcasts_artwork/) are usually signed
        // and highly sensitive to path changes. We leave them as is.
    }
    
    // googleusercontent & yt3.ggpht: modify resolution parameters safely
    if (contains("googleusercontent.com") || contains("yt3.ggpht.com")) {
        return replace(Regex("([=/-])w\\d+(?![0-9a-zA-Z])"), "$1w$size")
            .replace(Regex("([=/-])h\\d+(?![0-9a-zA-Z])"), "$1h$size")
            .replace(Regex("([=/-])s\\d+(?![0-9a-zA-Z])"), "$1s$size")
    }
    
    return this
}

private fun String?.getNextYouTubeFallback(): String? {
    if (this == null || !contains("i.ytimg.com/vi/")) return null
    return when {
        contains("maxresdefault.jpg") -> replace("maxresdefault.jpg", "sddefault.jpg")
        contains("sddefault.jpg") -> replace("sddefault.jpg", "hqdefault.jpg")
        contains("hqdefault.jpg") -> replace("hqdefault.jpg", "mqdefault.jpg")
        else -> null
    }
}

fun String?.thumbnail(): String? = this

fun Uri?.thumbnail(size: Int): Uri? = this?.toString()?.thumbnail(size)?.toUri()

fun Thumbnail.size(size: Int): String = url.thumbnail(size) ?: url

private fun String.isYouTubeHighRes(): Boolean = 
    contains("mwEK") || contains("maxres")
