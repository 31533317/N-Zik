package it.fast4x.rimusic.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import me.knighthat.coil.ImageCacheFactory
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future
import java.util.concurrent.ExecutionException
import app.kreate.android.R
import it.fast4x.rimusic.appContext

@UnstableApi
class CoilBitmapLoader(
    private val context: Context,
    private val scope: CoroutineScope,
    private val bitmapSize: Int,
) : BitmapLoader {
    
    private val fallbackBitmap: Bitmap by lazy {
        try {
            BitmapFactory.decodeResource(appContext().resources, R.drawable.ic_launcher)
        } catch (e: Exception) {
            // If the fallback image fails, create a simple bitmap
            Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888).apply {
                // Create a simple gray bitmap as ultimate fallback
            }
        }
    }
    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        scope.future(Dispatchers.IO) {
            BitmapFactory.decodeByteArray(data, 0, data.size) ?: error("Could not decode image data")
        }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        scope.future(Dispatchers.IO) {
            // Check if URI is valid
            if (uri.toString().isBlank() || uri.toString() == "null") {
                fallbackBitmap
            } else {
                try {
                    val result = ImageCacheFactory.LOADER.execute(
                        ImageRequest.Builder(context)
                            .networkCachePolicy(CachePolicy.ENABLED)
                            .data(uri.thumbnail(bitmapSize))
                            .size(bitmapSize)
                            .bitmapConfig(Bitmap.Config.ARGB_8888)
                            .allowHardware(false)
                            .diskCacheKey(uri.thumbnail(bitmapSize).toString())
                            .build()
                    )
                    if (result is ErrorResult) {
                        // Use fallback image instead of throwing exception
                        fallbackBitmap
                    } else {
                        try {
                            (result.drawable as BitmapDrawable).bitmap
                        } catch (e: Exception) {
                            // Use fallback image if drawable conversion fails
                            fallbackBitmap
                        }
                    }
                } catch (e: Exception) {
                    // Use fallback image for any other exception
                    fallbackBitmap
                }
            }
        }
}
