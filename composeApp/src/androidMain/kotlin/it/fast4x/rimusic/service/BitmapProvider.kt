package it.fast4x.rimusic.service

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.core.graphics.applyCanvas
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.Disposable
import coil.request.ImageRequest
import it.fast4x.rimusic.utils.thumbnail
import it.fast4x.rimusic.appContext
import app.kreate.android.R
import app.kreate.android.drawable.APP_ICON_BITMAP
import me.knighthat.coil.ImageCacheFactory

class BitmapProvider(
    private val bitmapSize: Int,
    private val colorProvider: (isSystemInDarkMode: Boolean) -> Int
) {
    var lastUri: Uri? = null
        private set

    var lastBitmap: Bitmap? = null
    private var lastIsSystemInDarkMode = false

    private var lastEnqueued: Disposable? = null

    private lateinit var defaultBitmap: Bitmap
    private var fallbackBitmap: Bitmap? = null

    val bitmap: Bitmap
        get() = lastBitmap ?: fallbackBitmap ?: defaultBitmap

    var listener: ((Bitmap?) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(lastBitmap)
        }

    init {
        setDefaultBitmap()
        setFallbackBitmap()
    }

    private fun setFallbackBitmap() {
        try {
            // Use the app icon as fallback
            fallbackBitmap = APP_ICON_BITMAP
        } catch (e: Exception) {
            // If the app icon fails, create a simple bitmap
            fallbackBitmap = Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888).applyCanvas {
                drawColor(0xFF666666.toInt()) // Gris par défaut
            }
        }
    }

    fun setDefaultBitmap(): Boolean {
        val isSystemInDarkMode = appContext().resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

        if (::defaultBitmap.isInitialized && isSystemInDarkMode == lastIsSystemInDarkMode) return false

        lastIsSystemInDarkMode = isSystemInDarkMode

        runCatching {
            defaultBitmap =
                Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888).applyCanvas {
                    drawColor(colorProvider(isSystemInDarkMode))
                }
        }.onFailure {
            // En cas d'échec, utiliser le fallback
            defaultBitmap = fallbackBitmap ?: Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888)
        }

        return lastBitmap == null
    }

    fun load(uri: Uri?, callback: () -> Unit) {
        if (uri == null) {
            lastUri = null
            lastBitmap = fallbackBitmap
            callback()
            return
        }

        if (lastUri == uri) {
            callback()
            return
        }

        lastUri = uri

        // Check if we should use the network based on connection quality and cache availability
        if (ImageCacheFactory.shouldUseNetwork(uri.toString())) {
            // Good connection and image not cached, load from the network
            loadFromNetwork(uri, callback)
        } else {
            // Bad connection or image cached, load with cache priority
            loadWithCachePriority(uri, callback)
        }
    }

    private fun loadWithCachePriority(uri: Uri, callback: () -> Unit) {
        try {
            // Check if the image is cached
            val isCached = ImageCacheFactory.isImageCached(uri.toString())
            
            // Create a request that prioritizes the cache
            val request = ImageRequest.Builder(appContext())
                .data(uri.toString())
                .diskCacheKey(uri.toString())
                .diskCachePolicy(CachePolicy.ENABLED)
                .networkCachePolicy(if (isCached) CachePolicy.DISABLED else CachePolicy.ENABLED)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .listener(
                    onError = { _, _ ->
                        lastBitmap = fallbackBitmap
                        callback()
                    },
                    onSuccess = { _, result ->
                        val drawable = result.drawable
                        if (drawable is BitmapDrawable) {
                            lastBitmap = drawable.bitmap
                        } else {
                            lastBitmap = fallbackBitmap
                        }
                        callback()
                    }
                )
                .build()

            lastEnqueued = ImageCacheFactory.LOADER.enqueue(request)
        } catch (e: Exception) {
            lastBitmap = fallbackBitmap
            callback()
        }
    }

    private fun loadFromNetwork(uri: Uri, callback: () -> Unit) {
        try {
            val request = ImageRequest.Builder(appContext())
                .data(uri.toString())
                .diskCacheKey(uri.toString())
                .diskCachePolicy(CachePolicy.ENABLED)
                .networkCachePolicy(CachePolicy.ENABLED)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .listener(
                    onError = { _, _ ->
                        // En cas d'échec, essayer de précharger l'image pour le prochain essai
                        ImageCacheFactory.preloadImage(uri.toString())
                        lastBitmap = fallbackBitmap
                        callback()
                    },
                    onSuccess = { _, result ->
                        val drawable = result.drawable
                        if (drawable is BitmapDrawable) {
                            lastBitmap = drawable.bitmap
                        } else {
                            lastBitmap = fallbackBitmap
                        }
                        callback()
                    }
                )
                .build()

            lastEnqueued = ImageCacheFactory.LOADER.enqueue(request)
        } catch (e: Exception) {
            lastBitmap = fallbackBitmap
            callback()
        }
    }

    fun clear() {
        lastEnqueued?.dispose()
        lastUri = null
        lastBitmap = null
    }
}
